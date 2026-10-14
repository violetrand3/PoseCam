package com.posecam.core.sync

import com.posecam.core.cloud.CampaignProgress
import com.posecam.core.cloud.MyCampaign
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Every user-facing string of the campaign screens, as pure functions so they are unit-tested. */
object CampaignUiText {
    const val EMPTY_TITLE = "Accept a campaign on the website first"
    const val EMPTY_DETAIL = "Campaigns are browsed and accepted on the website. Once you accept one it shows up here, and you can record for it."
    const val OPEN_WEBSITE = "Open the website"
    const val NO_CAMPAIGN_REASON = "Choose a campaign to record"
    const val NEED_CAMPAIGN_ON_WEB = "Accept a campaign on the website to record"

    private const val SNIPPET_CHARS = 140

    /** The first ~140 characters of the instructions on one line, cut at a word and ended with an ellipsis if cut. */
    fun instructionSnippet(instructions: String): String {
        val text = instructions.trim().replace(Regex("\\s+"), " ")
        if (text.length <= SNIPPET_CHARS) return text
        val cut = text.take(SNIPPET_CHARS)
        val atWord = cut.lastIndexOf(' ').takeIf { it >= SNIPPET_CHARS * 2 / 3 } ?: SNIPPET_CHARS
        return cut.take(atWord).trimEnd(',', '.', ';', ':', ' ') + "…"
    }

    /** "12 recorded · 9 approved", and what is still in review or was not counted when there is any. */
    fun progressLine(progress: CampaignProgress): String = listOfNotNull(
        "${progress.recorded} recorded",
        "${progress.approved} approved",
        "${progress.inReview} in review".takeIf { progress.inReview > 0 },
        "${progress.rejected} rejected".takeIf { progress.rejected > 0 },
        "${progress.notCounted} not counted".takeIf { progress.notCounted > 0 },
    ).joinToString(" · ")

    /** Paise to rupees: 5000 -> "₹50", 5050 -> "₹50.50". Other currencies are shown with their code. */
    fun money(paise: Long, currency: String): String {
        val whole = paise / 100
        val cents = (paise % 100).toInt()
        val amount = if (cents == 0) "$whole" else "%d.%02d".format(Locale.US, whole, cents)
        return if (currency.equals("INR", ignoreCase = true)) "₹$amount" else "$currency $amount"
    }

    /** "₹50 per approved recording". */
    fun payLine(campaign: MyCampaign): String = "${money(campaign.rewardPerRecording, campaign.currency)} per approved recording"

    /** "Ends 12 Nov 2026", or "No deadline". [endDate] is an ISO-8601 time. */
    fun deadlineLine(endDate: String?): String {
        val parsed = endDate?.let { parseIso(it) } ?: return "No deadline"
        val out = SimpleDateFormat("d MMM yyyy", Locale.getDefault()).apply { timeZone = TimeZone.getDefault() }
        return "Ends ${out.format(parsed)}"
    }

    /** Why a campaign in the list cannot be recorded for; null when it can. */
    fun unavailableReason(campaign: MyCampaign): String? = when {
        campaign.status != MyCampaign.STATUS_ACTIVE || !campaign.acceptingUploads -> "Not taking new recordings"
        campaign.remaining <= 0 -> "No recordings left to take"
        else -> null
    }

    /** The capture screen's line at the top: the campaign's name, or what to do when there is none. */
    fun captureHeader(active: MyCampaign?, anyCampaigns: Boolean): String = when {
        active != null -> active.title
        anyCampaigns -> NO_CAMPAIGN_REASON
        else -> NEED_CAMPAIGN_ON_WEB
    }

    /** Parses `2026-11-12T00:00:00Z` / `...00.000Z`; null if it is not a time. */
    internal fun parseIso(text: String): java.util.Date? = runCatching {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssX", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            .parse(text.replace(Regex("\\.\\d+"), ""))
    }.getOrNull()
}
