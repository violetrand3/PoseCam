package com.posecam.core.sync

import com.posecam.TakeVerdict
import com.posecam.core.cloud.TakeInfo
import org.json.JSONObject
import java.io.File

/**
 * The app's verdict on one finished take, worked out from its manifest -- the same numbers and the same [TakeVerdict] the
 * end-of-take dialog uses, so a recording that is queued later (after a restart, or when an older recording gets a
 * campaign) is judged exactly as it was when it was recorded.
 */
data class TakeSummary(
    /** [TakeVerdict.Result.redo]. */
    val redo: Boolean,
    val reasons: List<String>,
    val durationSeconds: Double,
    val poseJumps: Int,
    val longestTrackingLossFrames: Int,
) {
    val verdict: String get() = if (redo) VERDICT_REDO else VERDICT_GOOD

    fun toInfo() = TakeInfo(verdict, reasons, durationSeconds, poseJumps, longestTrackingLossFrames)

    companion object {
        const val VERDICT_GOOD = "good"
        const val VERDICT_REDO = "redo"
    }
}

/**
 * The few manifest facts cloud sync needs. PoseCam writes `"complete": true/false` (false while a
 * take is running and forever after a crash), plus `session_id`, `start_wall_time_utc` and `frame_count`.
 * Parsed with org.json, not regexes (the Recordings list's regexes are brittle; this must not be).
 */
data class SessionManifestInfo(
    val sessionId: String,
    val complete: Boolean,
    val startWallTimeUtc: String?,
    val frameCount: Long,
    /** The take's verdict inputs as the manifest recorded them. */
    val take: TakeSummary = TakeSummary(redo = true, reasons = emptyList(), durationSeconds = 0.0, poseJumps = 0, longestTrackingLossFrames = 0),
) {
    /**
     * What the backend is told. A killed take is "incomplete": it is never uploaded (the marketplace refuses it), but
     * the status still says what the recording is.
     */
    val recordingStatus: String
        get() = if (complete) CloudFileRules.STATUS_COMPLETE else CloudFileRules.STATUS_INCOMPLETE

    /** Only a complete recording whose take is good is ever paid for: redo takes and incomplete ones are never uploaded. */
    val eligibleForUpload: Boolean get() = complete && !take.redo

    /** Why this recording is never uploaded, in the collector's words; null when it can be. */
    val notUploadedReason: String?
        get() = when {
            !complete -> "recording incomplete"
            take.redo -> "redo take"
            else -> null
        }

    companion object {
        /** Null when the manifest is missing or unreadable: such a folder is not (yet) a session. */
        fun read(directory: File): SessionManifestInfo? {
            val manifest = File(directory, "manifest.json")
            if (!manifest.isFile) return null
            return runCatching {
                val json = JSONObject(manifest.readText(Charsets.UTF_8))
                SessionManifestInfo(
                    sessionId = json.optString("session_id").takeIf { it.isNotEmpty() } ?: directory.name,
                    complete = json.optBoolean("complete", false),
                    startWallTimeUtc = json.optString("start_wall_time_utc").takeIf { it.isNotEmpty() && it != "null" },
                    frameCount = json.optLong("frame_count", 0),
                    take = takeOf(json),
                )
            }.getOrNull()
        }

        private fun takeOf(json: JSONObject): TakeSummary {
            val frames = json.optLong("frame_count", 0)
            val tracked = json.optLong("tracked_frame_count", 0)
            val first = json.optLong("first_timestamp_ns", 0)
            val last = json.optLong("last_timestamp_ns", 0)
            val seconds = if (json.has("first_timestamp_ns") && !json.isNull("first_timestamp_ns") && last > first) (last - first) / 1e9 else 0.0
            val jumps = json.optJSONObject("pose_jumps")?.optInt("count", 0) ?: 0
            val gapSeconds = json.optDouble("longest_tracking_gap_s", 0.0).let { if (it.isNaN()) 0.0 else it }
            val images = json.optJSONObject("images")
            val dropped = images?.optJSONObject("dropped")?.let { map -> map.keys().asSequence().sumOf { map.optLong(it) } } ?: 0L
            val writeFailures = images?.optJSONArray("write_failures")?.length() ?: 0
            val verdict = TakeVerdict.of(seconds, frames, tracked, jumps, gapSeconds, dropped + writeFailures)
            val fps = json.optDouble("measured_fps", 30.0).let { if (it.isNaN() || it <= 0) 30.0 else it }
            return TakeSummary(
                redo = verdict.redo,
                reasons = verdict.reasons,
                durationSeconds = Math.round(seconds * 10) / 10.0,
                poseJumps = jumps,
                longestTrackingLossFrames = Math.round(gapSeconds * fps).toInt(),
            )
        }
    }
}
