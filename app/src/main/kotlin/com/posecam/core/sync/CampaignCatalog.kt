package com.posecam.core.sync

import android.content.Context
import com.posecam.core.cloud.CampaignProgress
import com.posecam.core.cloud.CampaignRules
import com.posecam.core.cloud.CloudApi
import com.posecam.core.cloud.CloudException
import com.posecam.core.cloud.CloudLog
import com.posecam.core.cloud.MyCampaign
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Where the last good campaign list (and the collector's choice) is kept, so recording for a campaign works with no network. */
interface CampaignStore {
    fun load(): String?
    fun save(json: String)
    fun loadActiveId(): String?
    fun saveActiveId(id: String?)
}

class PrefsCampaignStore(context: Context) : CampaignStore {
    private val prefs = context.applicationContext.getSharedPreferences("posecam_campaigns", Context.MODE_PRIVATE)
    override fun load(): String? = prefs.getString(KEY, null)
    override fun save(json: String) { prefs.edit().putString(KEY, json).apply() }
    override fun loadActiveId(): String? = prefs.getString(KEY_ACTIVE, null)
    override fun saveActiveId(id: String?) {
        prefs.edit().apply { if (id == null) remove(KEY_ACTIVE) else putString(KEY_ACTIVE, id) }.apply()
    }

    private companion object {
        const val KEY = "campaigns_v1"
        const val KEY_ACTIVE = "active_campaign_id"
    }
}

/** The campaign recordings are made for right now, as far as the recorder needs to know. */
data class ActiveCampaign(val id: String, val title: String, val collectRaw: Boolean)

/**
 * The campaigns the collector accepted on the website, cached on the phone, and the one chosen for the next recordings.
 *
 * The cache belongs to an account: what one collector accepted is never offered to the next one who signs in on the phone.
 * [current] never touches the network (the capture screen must work in the field, offline). [refresh] replaces the cache with
 * what the backend says -- including an empty list, which is a real answer (nothing accepted, or everything closed) -- and a
 * failed fetch leaves the old list alone.
 *
 * The choice is made BEFORE recording (the phone faces away once it is mounted), remembered, made automatically when there is
 * exactly one campaign, and asked again when the remembered one is no longer in the list.
 */
class CampaignCatalog(private val api: CloudApi, private val store: CampaignStore) {
    /** The cached list for [accountId], newest enrollment first; empty when nothing is cached or it is someone else's. */
    fun current(accountId: String?): List<MyCampaign> {
        if (accountId == null) return emptyList()
        val cached = parsed() ?: return emptyList()
        return if (cached.accountId == accountId) cached.campaigns else emptyList()
    }

    // The Recordings list asks for a title per row: parse the stored JSON once per change, not once per row.
    @Volatile private var memo: Pair<String, Cached?>? = null

    private fun parsed(): Cached? {
        val json = store.load() ?: return null
        memo?.let { if (it.first == json) return it.second }
        return runCatching { parse(json) }.getOrNull().also { memo = json to it }
    }

    /** Fetches the list and caches it for [accountId]. Returns whether the cache was updated; failures are logged and swallowed. */
    suspend fun refresh(accountId: String?): Boolean {
        if (accountId == null) return false
        val fresh = try {
            api.listCampaigns().filter { Pipe.isValidWire(it.id) && it.title.isNotBlank() }.distinctBy { it.id }
        } catch (error: CloudException) {
            CloudLog.i("campaigns_refresh_failed", "reason" to (error.message ?: error.javaClass.simpleName))
            return false
        }
        store.save(serialize(accountId, fresh))
        // A remembered campaign that is gone from the list is forgotten: the collector is asked again.
        if (store.loadActiveId() != null && fresh.none { it.id == store.loadActiveId() }) store.saveActiveId(null)
        return true
    }

    /**
     * The campaign to record for: the remembered one if it is still in the list, otherwise the only one there is, otherwise
     * null (the collector has to choose). A campaign that can no longer take recordings is never the active one.
     */
    fun active(accountId: String?): MyCampaign? {
        val usable = current(accountId).filter { it.canRecord }
        val remembered = store.loadActiveId()?.let { id -> usable.firstOrNull { it.id == id } }
        return remembered ?: usable.singleOrNull()
    }

    fun setActive(campaign: MyCampaign) {
        store.saveActiveId(campaign.id)
    }

    fun title(accountId: String?, id: String): String? = current(accountId).firstOrNull { it.id == id }?.title

    fun find(accountId: String?, id: String): MyCampaign? = current(accountId).firstOrNull { it.id == id }

    companion object {
        fun MyCampaign.toActive() = ActiveCampaign(id, title, collectRaw)

        internal class Cached(val accountId: String, val campaigns: List<MyCampaign>)

        internal fun serialize(accountId: String, campaigns: List<MyCampaign>): String = JSONObject()
            .put("accountId", accountId)
            .put(
                "campaigns",
                JSONArray().also { array ->
                    campaigns.forEach { c ->
                        array.put(
                            JSONObject()
                                .put("id", c.id).put("title", c.title).put("description", c.description)
                                .put("instructions", c.instructions).put("referenceInfo", c.referenceInfo ?: JSONObject.NULL)
                                .put("currency", c.currency).put("rewardPerRecording", c.rewardPerRecording)
                                .put("rewardPoints", c.rewardPoints)
                                .put("rules", c.rules?.let { JSONObject().put("minDuration", it.minDuration).put("maxDuration", it.maxDuration).put("orientation", it.orientation) } ?: JSONObject.NULL)
                                .put("collectRaw", c.collectRaw).put("target", c.target).put("remaining", c.remaining)
                                .put("endDate", c.endDate ?: JSONObject.NULL).put("enrolledAt", c.enrolledAt ?: JSONObject.NULL)
                                .put("acceptingUploads", c.acceptingUploads).put("status", c.status)
                                .put(
                                    "progress",
                                    JSONObject().put("recorded", c.progress.recorded).put("inReview", c.progress.inReview)
                                        .put("approved", c.progress.approved).put("rejected", c.progress.rejected)
                                        .put("notCounted", c.progress.notCounted).put("earned", c.progress.earned),
                                ),
                        )
                    }
                },
            )
            .toString()

        /** Anything malformed is dropped, not guessed at. */
        @Throws(JSONException::class)
        internal fun parse(json: String): Cached {
            val root = JSONObject(json)
            val array = root.getJSONArray("campaigns")
            val campaigns = (0 until array.length()).mapNotNull { index ->
                val c = array.optJSONObject(index) ?: return@mapNotNull null
                val id = c.optString("id")
                val title = c.optString("title")
                if (!Pipe.isValidWire(id) || title.isBlank()) return@mapNotNull null
                val progress = c.optJSONObject("progress")
                MyCampaign(
                    id = id, title = title, description = c.optString("description"), instructions = c.optString("instructions"),
                    referenceInfo = if (c.isNull("referenceInfo")) null else c.optString("referenceInfo"),
                    currency = c.optString("currency", "INR"), rewardPerRecording = c.optLong("rewardPerRecording"),
                    rewardPoints = c.optLong("rewardPoints"),
                    rules = c.optJSONObject("rules")?.let { CampaignRules(it.optInt("minDuration"), it.optInt("maxDuration"), it.optString("orientation", "ANY")) },
                    collectRaw = c.optBoolean("collectRaw", false), target = c.optInt("target"), remaining = c.optInt("remaining"),
                    endDate = if (c.isNull("endDate")) null else c.optString("endDate"),
                    enrolledAt = if (c.isNull("enrolledAt")) null else c.optString("enrolledAt"),
                    acceptingUploads = c.optBoolean("acceptingUploads", true), status = c.optString("status", MyCampaign.STATUS_ACTIVE),
                    progress = CampaignProgress(
                        progress?.optInt("recorded") ?: 0, progress?.optInt("inReview") ?: 0, progress?.optInt("approved") ?: 0,
                        progress?.optInt("rejected") ?: 0, progress?.optInt("notCounted") ?: 0, progress?.optLong("earned") ?: 0,
                    ),
                )
            }.distinctBy { it.id }
            return Cached(root.getString("accountId"), campaigns)
        }
    }
}
