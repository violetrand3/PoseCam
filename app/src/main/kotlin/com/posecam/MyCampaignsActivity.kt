package com.posecam

import android.app.Activity
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.posecam.core.cloud.MyCampaign
import com.posecam.core.sync.CampaignUiText
import com.posecam.core.sync.CloudSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "My campaigns": the campaigns the collector accepted on the website (browsing and accepting stay there). One card each,
 * with what to record, how it is going, what a recording earns and the deadline. Tapping a card makes it the ACTIVE
 * campaign -- chosen BEFORE recording, because the phone faces away once it is mounted -- and returns to the camera.
 *
 * The list is cached on the phone, so this screen and recording work offline; it is refreshed whenever the screen opens.
 */
class MyCampaignsActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var sync: CloudSync
    private lateinit var list: ListView
    private lateinit var empty: View
    private lateinit var note: TextView
    private var campaigns: List<MyCampaign> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_campaigns)
        title = getString(R.string.my_campaigns)
        showBackArrow()
        keepBelowSystemBars()
        sync = CloudSync.get(this)
        list = findViewById(R.id.campaignsList)
        empty = findViewById(R.id.campaignsEmpty)
        note = findViewById(R.id.campaignsNote)
        findViewById<TextView>(R.id.campaignsEmptyTitle).text = CampaignUiText.EMPTY_TITLE
        findViewById<TextView>(R.id.campaignsEmptyDetail).text = CampaignUiText.EMPTY_DETAIL
        findViewById<Button>(R.id.campaignsOpenWeb).apply {
            text = CampaignUiText.OPEN_WEBSITE
            setOnClickListener { openWebLink(sync.meta.current().webAppUrl) }
        }
        list.setOnItemClickListener { _, _, position, _ -> choose(campaigns[position]) }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = handleBackArrow(item) || super.onOptionsItemSelected(item)

    override fun onStart() {
        super.onStart()
        show(sync.myCampaigns())
        if (!sync.config.enabled) return
        scope.launch {
            val updated = try {
                withContext(Dispatchers.IO) { sync.refreshCampaignsNow() }
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                false
            }
            if (updated) {
                show(sync.myCampaigns())
                showNote(null)
            } else {
                showNote("Offline · showing the list saved on this phone")
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun show(items: List<MyCampaign>) {
        campaigns = items
        list.adapter = CampaignAdapter(items)
        list.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun showNote(text: String?) {
        note.text = text
        note.visibility = if (text.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    private fun choose(campaign: MyCampaign) {
        val reason = CampaignUiText.unavailableReason(campaign)
        if (reason != null) {
            Toast.makeText(this, "${campaign.title}: ${reason.replaceFirstChar { it.lowercase() }}", Toast.LENGTH_LONG).show()
            return
        }
        sync.campaigns.setActive(campaign)
        Toast.makeText(this, "Recording for ${campaign.title}", Toast.LENGTH_SHORT).show()
        finish()
    }

    private inner class CampaignAdapter(private val items: List<MyCampaign>) : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val density = resources.displayMetrics.density
            fun dp(value: Int) = (value * density).toInt()
            fun text(tag: String, size: Float, bold: Boolean = false, alpha: Float = 1f) = TextView(this@MyCampaignsActivity).apply {
                this.tag = tag
                textSize = size
                this.alpha = alpha
                if (bold) setTypeface(typeface, Typeface.BOLD)
            }
            val root = (convertView as? LinearLayout) ?: LinearLayout(this@MyCampaignsActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(14))
                addView(text("title", 17f, bold = true))
                addView(text("instructions", 14f, alpha = 0.8f).apply { setPadding(0, dp(4), 0, 0) })
                addView(text("progress", 13f).apply { setPadding(0, dp(8), 0, 0) })
                addView(text("pay", 13f, bold = true).apply { setPadding(0, dp(2), 0, 0) })
                addView(text("deadline", 12f, alpha = 0.65f).apply { setPadding(0, dp(2), 0, 0) })
                addView(text("state", 12f, bold = true).apply { setPadding(0, dp(6), 0, 0) })
            }
            val item = items[position]
            val active = sync.campaigns.active(sync.recordingAccountId())?.id == item.id
            val reason = CampaignUiText.unavailableReason(item)
            root.findViewWithTag<TextView>("title").text = item.title
            root.findViewWithTag<TextView>("instructions").text = CampaignUiText.instructionSnippet(item.instructions.ifBlank { item.description })
            root.findViewWithTag<TextView>("progress").text = CampaignUiText.progressLine(item.progress)
            root.findViewWithTag<TextView>("pay").text = CampaignUiText.payLine(item)
            root.findViewWithTag<TextView>("deadline").text = CampaignUiText.deadlineLine(item.endDate)
            root.findViewWithTag<TextView>("state").apply {
                text = when {
                    reason != null -> reason
                    active -> "Recording for this campaign"
                    else -> "Tap to record for this campaign"
                }
                setTextColor(if (reason != null) 0xFF8D6E00.toInt() else 0xFF00796B.toInt())
            }
            root.alpha = if (reason != null) 0.6f else 1f
            root.background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(if (active) 0xFFE0F2F1.toInt() else 0xFFF5F5F5.toInt())
                setStroke(if (active) dp(2) else dp(1), if (active) 0xFF00796B.toInt() else 0x1F000000)
            }
            return root
        }
    }
}
