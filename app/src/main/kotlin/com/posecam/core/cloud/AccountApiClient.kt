package com.posecam.core.cloud

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject

/**
 * OkHttp implementation of [AccountApi]. Never logs a password or a token. Sign-up and sign-in go through
 * [AuthEndpoints] (no bearer token); the history calls are authenticated and refresh the login once on a 401.
 */
class AccountApiClient(
    config: CloudConfig,
    private val authProvider: AuthProvider,
    private val installationId: String,
    private val client: OkHttpClient = CloudApiClient.defaultClient(config),
    private val endpoints: AuthEndpoints = AuthEndpoints(config, installationId, client),
) : AccountApi {
    private val baseUrl: HttpUrl = config.normalizedBaseUrl.toHttpUrl()

    override suspend fun register(email: String, password: String, name: String?, termsVersion: String): AuthSession =
        endpoints.register(email, password, name, termsVersion)

    override suspend fun login(email: String, password: String): AuthSession = endpoints.login(email, password)

    override suspend fun logout(refreshToken: String) = endpoints.logout(refreshToken)

    override suspend fun meta(): AppMeta = endpoints.meta()

    override suspend fun myStats(tzOffsetMinutes: Int): UploadStats {
        val url = baseUrl.newBuilder()
            .addPathSegment("v1").addPathSegment("me").addPathSegment("stats")
            .addQueryParameter("tzOffsetMinutes", tzOffsetMinutes.toString())
            .build()
        val json = client.executeAuthed(authProvider) { token -> requestFor(url, token).get().build() }
        return try {
            val days = json.optJSONArray("days")
            UploadStats(
                email = json.optString("email"),
                totalRecordings = json.getInt("totalRecordings"),
                uploadedToday = json.optInt("uploadedToday", 0),
                firstUploadAt = json.optStringOrNull("firstUploadAt"),
                days = (0 until (days?.length() ?: 0)).mapNotNull { days?.optJSONObject(it) }.map(::parseDay),
            )
        } catch (error: JSONException) {
            throw CloudHttpException(502, "MALFORMED_RESPONSE", "unexpected response shape: ${error.message}")
        }
    }

    override suspend fun uploadsOn(date: String, tzOffsetMinutes: Int): List<UploadedRecording> {
        val url = baseUrl.newBuilder()
            .addPathSegment("v1").addPathSegment("me").addPathSegment("uploads")
            .addQueryParameter("date", date)
            .addQueryParameter("tzOffsetMinutes", tzOffsetMinutes.toString())
            .build()
        val json = client.executeAuthed(authProvider) { token -> requestFor(url, token).get().build() }
        return try {
            parseRecordings(json.optJSONArray("recordings"))
        } catch (error: JSONException) {
            throw CloudHttpException(502, "MALFORMED_RESPONSE", "unexpected response shape: ${error.message}")
        }
    }

    private fun requestFor(url: HttpUrl, token: String?): Request.Builder =
        Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("X-Device-Id", installationId)
            .apply { token?.let { header("Authorization", "Bearer $it") } }

    internal companion object {
        /** Reads `campaigns` (older cached copies and older backends: `pipes`); live/publishing/failed are ignored. */
        fun parseDay(it: JSONObject): DayCount {
            val campaigns = it.optJSONObject("campaigns") ?: it.optJSONObject("pipes")
            return DayCount(
                date = it.getString("date"),
                recordings = it.getInt("recordings"),
                campaigns = campaigns?.keys()?.asSequence()?.associateWith { key -> campaigns.optInt(key) }.orEmpty(),
            )
        }

        /** Reads the new fields, and the old `pipe` / `pipeLabel` ones from a cached copy, without ever throwing on them. */
        fun parseRecordings(array: org.json.JSONArray?): List<UploadedRecording> =
            (0 until (array?.length() ?: 0)).mapNotNull { array?.optJSONObject(it) }.map {
                UploadedRecording(
                    sessionId = it.getString("sessionId"),
                    campaignId = it.optStringOrNull("campaignId") ?: it.optString("pipe"),
                    campaignTitle = it.optStringOrNull("campaignTitle") ?: it.optString("pipeLabel"),
                    uploadedAt = it.optString("uploadedAt"),
                    date = it.getString("date"),
                    status = it.optString("status", "uploaded"),
                    marketplaceStatus = it.optStringOrNull("marketplaceStatus"),
                )
            }
    }
}
