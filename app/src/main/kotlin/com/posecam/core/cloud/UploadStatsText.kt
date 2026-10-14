package com.posecam.core.cloud

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** Wording and day arithmetic for the "My uploads" screen. Pure, so it is tested without a phone. */
object UploadStatsText {
    private const val DAY = "yyyy-MM-dd"

    /** The phone's current offset from UTC in minutes east, which is how the server is asked to cut days. */
    fun tzOffsetMinutes(now: Long = System.currentTimeMillis(), zone: TimeZone = TimeZone.getDefault()): Int =
        zone.getOffset(now) / 60_000

    /** `Today`, `Yesterday`, or `Fri 9 Oct 2026`. [today] is `yyyy-MM-dd` in the same time zone as [date]. */
    fun dayLabel(date: String, today: String): String {
        if (date == today) return "Today"
        if (date == shiftDay(today, -1)) return "Yesterday"
        val parsed = parse(date) ?: return date
        return utc("EEE d MMM yyyy", Locale.getDefault()).format(parsed)
    }

    fun today(now: Long = System.currentTimeMillis(), zone: TimeZone = TimeZone.getDefault()): String =
        SimpleDateFormat(DAY, Locale.US).apply { timeZone = zone }.format(now)

    /** One row of the day list in "My uploads". */
    data class DayRow(
        val date: String,
        /** "Today", "Yesterday" or "Tue 6 Oct 2026". */
        val title: String,
        val count: Int,
        /** The day's split by campaign, biggest first: "Kitchen 40 \u00b7 Laundry 35". */
        val campaigns: String,
    )

    /** The day list: one row per day, each with its campaign split. [campaignName] turns a campaign id into its title. */
    fun dayRows(stats: UploadStats, today: String, campaignName: (id: String) -> String?): List<DayRow> = stats.days.map { day ->
        DayRow(
            date = day.date,
            title = dayLabel(day.date, today),
            count = day.recordings,
            campaigns = campaignSplit(day.campaigns, campaignName),
        )
    }

    /** "Kitchen 40 \u00b7 Laundry 35": biggest first, ties by name, so the line never reorders itself between visits. */
    fun campaignSplit(campaigns: Map<String, Int>, campaignName: (id: String) -> String?): String =
        campaigns.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .joinToString(" \u00b7 ") { "${campaignName(it.key) ?: it.key} ${it.value}" }

    /** The second line of a recording in a day's list: "Kitchen \u00b7 Uploaded". */
    fun recordingDetail(r: UploadedRecording, campaignName: (id: String) -> String?): String {
        val campaign = campaignName(r.campaignId) ?: r.campaignTitle.ifBlank { "" }
        return listOf(campaign, status(r.status)).filter { it.isNotBlank() }.joinToString(" \u00b7 ")
    }

    /**
     * The small line under a recording once the marketplace has an opinion on it ("In review", "Approved", "Not counted"),
     * or null while the backend does not say (an older backend, or a cached copy).
     */
    fun reviewLine(r: UploadedRecording): String? = r.marketplaceStatus?.let { marketplaceLabel(it, null) }

    /** Plain words for `marketplace.status`, with the reason of a recording that was not counted. */
    fun marketplaceLabel(status: String, reason: String?): String = when (status) {
        "CHECKING" -> "Checking"
        "IN_REVIEW" -> "In review"
        "APPROVED" -> "Approved"
        "REJECTED" -> if (reason.isNullOrBlank()) "Rejected" else "Rejected: $reason"
        "NOT_COUNTED" -> notCountedLabel(reason)
        else -> status.lowercase().replaceFirstChar { it.uppercase() }.replace('_', ' ')
    }

    /** "Not counted: campaign full" / "...: campaign closed" / "...: already uploaded". */
    fun notCountedLabel(reason: String?): String = when (reason) {
        "CAMPAIGN_FULL" -> "Not counted: campaign full"
        "CAMPAIGN_CLOSED" -> "Not counted: campaign closed"
        "DUPLICATE" -> "Not counted: duplicate recording"
        else -> "Not counted"
    }

    /** The name a recording is listed under: its folder name without the "capture-" prefix. */
    fun recordingName(sessionId: String): String = sessionId.removePrefix("capture-")

    /** The summary line over a day's list: "100 recordings" (and how many the marketplace is still looking at, when it says). */
    fun daySummary(recordings: List<UploadedRecording>): String {
        val reviewing = recordings.count { it.marketplaceStatus == "CHECKING" || it.marketplaceStatus == "IN_REVIEW" }
        val approved = recordings.count { it.marketplaceStatus == "APPROVED" }
        return listOfNotNull(
            recordings(recordings.size),
            "$reviewing in review".takeIf { reviewing > 0 },
            "$approved approved".takeIf { approved > 0 },
        ).joinToString(" \u00b7 ")
    }

    /** The marketplace backend only ever reports "uploaded"; anything else (an old cached copy) reads as uploaded too. */
    fun status(@Suppress("UNUSED_PARAMETER") wire: String): String = "Uploaded"

    fun recordings(count: Int): String = if (count == 1) "1 recording" else "$count recordings"

    /** "Updated 5 min ago" for a cached answer, or null when it is under a minute old. */
    fun age(savedAtMs: Long, now: Long): String? {
        val minutes = (now - savedAtMs) / 60_000
        return when {
            minutes < 1 -> null
            minutes < 60 -> "$minutes min ago"
            minutes < 24 * 60 -> "${minutes / 60} h ago"
            else -> "${minutes / (24 * 60)} days ago"
        }
    }

    // A calendar day has no time zone: it is read and written as UTC midnight so shifting it never slides across a date.
    private fun utc(pattern: String, locale: Locale = Locale.US) =
        SimpleDateFormat(pattern, locale).apply { timeZone = TimeZone.getTimeZone("UTC") }

    private fun parse(date: String) = runCatching { utc(DAY).parse(date) }.getOrNull()

    private fun shiftDay(date: String, days: Int): String? {
        val parsed = parse(date) ?: return null
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { time = parsed; add(Calendar.DAY_OF_MONTH, days) }
        return utc(DAY).format(calendar.time)
    }
}
