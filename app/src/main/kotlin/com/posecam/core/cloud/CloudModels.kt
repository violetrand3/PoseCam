package com.posecam.core.cloud

import java.io.IOException

/** Wire models for the v1 backend API. Field names mirror the JSON exactly (camelCase). */

data class UploadConfig(
    val multipartThresholdBytes: Long,
    val partSizeBytes: Long,
    val presignedUrlTtlSeconds: Long,
)

data class CloudSessionView(
    val sessionId: String,
    val deviceId: String?,
    val createdAt: String?,
    val recordingStatus: String?,
    val cloudStatus: String,
    val totalFiles: Long,
    val verifiedFiles: Long,
    val totalBytes: Long,
    val verifiedBytes: Long,
    val completedAt: String?,
    /** "pending" | "done" | "failed": whether the recording reached the website. Null from a backend that does not say. */
    val publishStatus: String? = null,
    /** How many gallery sets the recording became; 0 with "done" means it was published without a video. */
    val publishedSets: Int = 0,
    val publishedAt: String? = null,
    val publishError: String? = null,
    /**
     * What the marketplace made of the recording (`session.marketplace` of the `complete` answer): null until the session
     * is completed, and from a backend that does not report it.
     */
    val marketplace: MarketplaceInfo? = null,
)

/**
 * `SessionView.marketplace`: [status] is "CHECKING" | "IN_REVIEW" | "APPROVED" | "REJECTED" | "NOT_COUNTED"; [reason] is
 * set for NOT_COUNTED ("CAMPAIGN_FULL" | "CAMPAIGN_CLOSED" | "DUPLICATE") and, for REJECTED, the reviewer's label.
 */
data class MarketplaceInfo(val status: String, val reason: String?, val submissionId: String?) {
    val notCounted: Boolean get() = status == NOT_COUNTED

    companion object {
        const val NOT_COUNTED = "NOT_COUNTED"
    }
}

/** The `take` object of `POST /v1/sessions`: the app's own verdict on the recording. */
data class TakeInfo(
    /** "good" | "redo". */
    val verdict: String,
    val reasons: List<String>,
    val durationSeconds: Double,
    val poseJumps: Int,
    val longestTrackingLossFrames: Int,
)

data class CreateSessionRequest(
    val sessionId: String,
    val deviceId: String,
    val createdAt: String,
    val recordingStatus: String,
    val appVersion: String?,
    /** The campaign the recording is for (the id of one of the collector's accepted campaigns). */
    val campaignId: String? = null,
    /** "manufacturer/model/..." from device.json, for the backend's fraud checks; null when device.json is unreadable. */
    val deviceFingerprint: String? = null,
    val take: TakeInfo? = null,
)

data class CreateSessionResponse(
    val session: CloudSessionView,
    val created: Boolean,
    val config: UploadConfig?,
    /** `requirements.rawRequired`: whether the backend wants the raw files too. Null from a backend that does not say. */
    val rawRequired: Boolean? = null,
)

/** `CampaignRules`: what a recording for the campaign has to look like. Durations are seconds. */
data class CampaignRules(val minDuration: Int, val maxDuration: Int, val orientation: String)

/** `MyCampaign.progress`: [earned] is paise. */
data class CampaignProgress(
    val recorded: Int,
    val inReview: Int,
    val approved: Int,
    val rejected: Int,
    val notCounted: Int,
    val earned: Long,
)

/** One entry of `GET /v1/me/campaigns`: a campaign the collector accepted on the website. Money is paise. */
data class MyCampaign(
    val id: String,
    val title: String,
    val description: String,
    val instructions: String,
    val referenceInfo: String?,
    val currency: String,
    val rewardPerRecording: Long,
    val rewardPoints: Long,
    val rules: CampaignRules?,
    /** True: the app also uploads raw frames / poses / IMU. False (the default): only the export. */
    val collectRaw: Boolean,
    val target: Int,
    val remaining: Int,
    val endDate: String?,
    val enrolledAt: String?,
    val acceptingUploads: Boolean,
    /** "ACTIVE" | "PAUSED" | "COMPLETED" | "CANCELLED". */
    val status: String,
    val progress: CampaignProgress,
) {
    /** New recordings can be made for it: it is running and still has slots. Otherwise recordings would not be paid. */
    val canRecord: Boolean get() = status == STATUS_ACTIVE && acceptingUploads && remaining > 0

    companion object {
        const val STATUS_ACTIVE = "ACTIVE"
    }
}

/** `GET /v1/meta` (public): the website's links and the terms version a sign-up must accept. */
data class AppMeta(
    val termsVersion: String?,
    val termsUrl: String?,
    val privacyUrl: String?,
    val passwordResetUrl: String?,
    val webAppUrl: String?,
    val appDownloadUrl: String?,
)

/** What [CloudApi.requestUpload] asks for: the backend picks S3 key, mode and limits itself. */
data class UploadRequest(
    val relativePath: String,
    val sizeBytes: Long,
    val sha256: String,
)

enum class UploadMode { SINGLE, MULTIPART, ALREADY_VERIFIED }

data class PresignedUpload(
    val relativePath: String,
    val mode: UploadMode,
    /** Presigned URL. Treat as a secret: never log it. Null unless [mode] is SINGLE. */
    val url: String?,
    val headers: Map<String, String>,
    val expiresInSeconds: Long,
    val partSizeBytes: Long?,
)

data class MultipartPartInfo(
    val partNumber: Int,
    val sizeBytes: Long,
    val etag: String?,
    /** Base64 SHA-256 exactly as S3 reports it. */
    val checksumSha256: String?,
)

data class MultipartStart(
    val relativePath: String,
    val uploadId: String,
    val partSizeBytes: Long,
    val partCount: Int,
    val resumed: Boolean,
    val uploadedParts: List<MultipartPartInfo>,
)

data class PartUrlRequest(
    val partNumber: Int,
    val sizeBytes: Long,
    val checksumSha256: String,
)

data class PresignedPart(
    val partNumber: Int,
    val url: String,
    val headers: Map<String, String>,
    val expiresInSeconds: Long,
)

data class CompletedPart(
    val partNumber: Int,
    val etag: String,
    val checksumSha256: String,
)

data class UploadCompletion(val relativePath: String, val state: String, val sizeBytes: Long)

data class VerifiedUpload(val relativePath: String, val state: String, val sizeBytes: Long, val verifiedAt: String?)

data class SessionFileRef(val relativePath: String, val sizeBytes: Long)

/** Base type of every failure surfaced by the cloud layer. */
sealed class CloudException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** True when trying again later (same request) can plausibly succeed. */
    abstract val retryable: Boolean
}

/** The request never produced an HTTP response: offline, DNS, TLS, timeout, connection reset. */
class CloudNetworkException(cause: IOException) :
    CloudException(cause.message ?: cause.javaClass.simpleName, cause) {
    override val retryable: Boolean get() = true
}

/** The backend (or S3) answered with an error status. */
class CloudHttpException(
    val status: Int,
    val code: String?,
    /** The server's own wording, fit to show a person ("Wrong email or password"); [message] adds the status for logs. */
    val reason: String,
    val details: String? = null,
    /** The marketplace's `error.fieldErrors`: form field name to what is wrong with it. Empty for other errors. */
    val fieldErrors: Map<String, String> = emptyMap(),
) : CloudException("HTTP $status${code?.let { " $it" }.orEmpty()}: $reason") {
    /**
     * Rate limiting, server errors and auth problems are all things a later attempt can fix
     * (auth providers refresh tokens; throttling and outages end). Other 4xx are the
     * client's own mistake and will fail identically every time. A 403 PATH_NOT_ALLOWED is not an auth problem: the
     * server's allow-list refuses that path (e.g. an export file whose stem differs from its folder's), for good.
     */
    override val retryable: Boolean
        get() = code !in PERMANENT_CODES && (
            status == 408 || status == 425 || status == 429 || status == 401 ||
                (status == 403 && code != PATH_NOT_ALLOWED) || status >= 500 || code in TRANSIENT_CODES
            )

    /**
     * What to tell a person about this error: plain words for the codes the marketplace uses for recordings, else the
     * server's own message. The raw [message] (with the status) is for logs.
     */
    val userMessage: String
        get() = PERMANENT_MESSAGES[code] ?: reason

    companion object {
        /** Campaign / take refusals: the same request fails identically every time, so the recording is not retried. */
        val PERMANENT_MESSAGES: Map<String, String> = mapOf(
            "NOT_ENROLLED" to "You have not accepted this campaign. Accept it on the website, then choose it again.",
            "CAMPAIGN_NOT_FOUND" to "This campaign no longer exists.",
            "CAMPAIGN_CLOSED" to "This campaign has closed, so this recording can no longer be accepted.",
            "TAKE_NOT_ELIGIBLE" to "This take can't be accepted: redo takes and incomplete recordings are never uploaded.",
        )
        private val PERMANENT_CODES = PERMANENT_MESSAGES.keys

        const val NOT_ENROLLED = "NOT_ENROLLED"
        const val CAMPAIGN_NOT_FOUND = "CAMPAIGN_NOT_FOUND"
        const val CAMPAIGN_CLOSED = "CAMPAIGN_CLOSED"
        const val TAKE_NOT_ELIGIBLE = "TAKE_NOT_ELIGIBLE"
        const val INCOMPLETE = "INCOMPLETE"

        /** S3 answers some transient conditions with a 400: a body corrupted in flight, a stalled upload. */
        private val TRANSIENT_CODES = setOf("BadDigest", "RequestTimeout", "SlowDown", "InternalError")

        const val PATH_NOT_ALLOWED = "PATH_NOT_ALLOWED"
        const val UNAUTHENTICATED = "UNAUTHENTICATED"
        const val SESSION_NOT_FOUND = "SESSION_NOT_FOUND"
        const val UPLOAD_NOT_FOUND = "UPLOAD_NOT_FOUND"
        const val FILE_NOT_FOUND = "FILE_NOT_FOUND"
        const val UPLOAD_CONFLICT = "UPLOAD_CONFLICT"
        const val VERIFICATION_FAILED = "VERIFICATION_FAILED"
        const val SESSION_INCOMPLETE = "SESSION_INCOMPLETE"
        const val S3_NO_SUCH_UPLOAD = "NoSuchUpload"
    }
}
