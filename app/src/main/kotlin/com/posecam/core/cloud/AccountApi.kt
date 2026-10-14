package com.posecam.core.cloud

/**
 * One day of the collector's upload history. [date] is `yyyy-MM-dd` in the phone's time zone. [campaigns] counts the
 * day's recordings per campaign id. The marketplace no longer reports live / publishing / failed: a recording is simply
 * uploaded, and its review status is shown per recording (see [UploadedRecording.marketplaceStatus]).
 */
data class DayCount(
    val date: String,
    val recordings: Int,
    val campaigns: Map<String, Int> = emptyMap(),
)

/** One recording the server has fully received. */
data class UploadedRecording(
    val sessionId: String,
    /** The campaign it was recorded for. */
    val campaignId: String,
    /** The server's name for that campaign, used when the phone does not know it. */
    val campaignTitle: String,
    val uploadedAt: String,
    /** `yyyy-MM-dd` in the phone's time zone: the day it is listed under. */
    val date: String,
    /** Always "uploaded" from the marketplace backend. */
    val status: String,
    /** The marketplace's view of it ("CHECKING", "IN_REVIEW", "APPROVED", "REJECTED", "NOT_COUNTED"), or null. */
    val marketplaceStatus: String? = null,
)

/**
 * What the backend has permanently received from this collector. It is the server's own count of synced recordings, so
 * it does not change when recordings are deleted from the phone.
 */
data class UploadStats(
    val email: String,
    val totalRecordings: Int,
    val uploadedToday: Int,
    val firstUploadAt: String?,
    /** Newest day first; days with no uploads are absent. At most a year is listed; [totalRecordings] counts all. */
    val days: List<DayCount>,
)

/** The collector's marketplace account: sign up, sign in and out, the website's links, and their upload history. */
interface AccountApi {
    /**
     * Creates the account and signs in. [termsVersion] is the version `GET /v1/meta` returned, which the collector accepted.
     * Throws [CloudHttpException] with the server's reason and `fieldErrors` (e.g. `CONFLICT` for a taken email).
     */
    suspend fun register(email: String, password: String, name: String?, termsVersion: String): AuthSession

    /** Throws [CloudHttpException] `INVALID_CREDENTIALS` (or `UNAUTHENTICATED`) for a wrong email or password. */
    suspend fun login(email: String, password: String): AuthSession

    /** Ends the login on the server (best effort, idempotent). The caller clears the local tokens whatever the answer. */
    suspend fun logout(refreshToken: String)

    /** `GET /v1/meta`: terms version and the website's links. Public. */
    suspend fun meta(): AppMeta

    /** Needs a signed-in collector. [tzOffsetMinutes] is minutes east of UTC, so days follow the phone's clock. */
    suspend fun myStats(tzOffsetMinutes: Int): UploadStats

    /** One day's recordings, newest first. [date] is `yyyy-MM-dd` in the phone's time zone. */
    suspend fun uploadsOn(date: String, tzOffsetMinutes: Int): List<UploadedRecording>
}
