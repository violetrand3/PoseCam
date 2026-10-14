package com.posecam.core.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.TimeZone

class UploadStatsTextTest {
    @Test fun todayAndYesterdayAreNamed() {
        assertEquals("Today", UploadStatsText.dayLabel("2026-10-09", "2026-10-09"))
        assertEquals("Yesterday", UploadStatsText.dayLabel("2026-10-08", "2026-10-09"))
    }

    @Test fun yesterdayCrossesMonthAndYearBoundaries() {
        assertEquals("Yesterday", UploadStatsText.dayLabel("2026-09-30", "2026-10-01"))
        assertEquals("Yesterday", UploadStatsText.dayLabel("2025-12-31", "2026-01-01"))
    }

    @Test fun olderDaysShowTheirDate() {
        val label = UploadStatsText.dayLabel("2026-10-05", "2026-10-09")
        assertEquals(true, label.contains("5") && label.contains("2026"))
    }

    @Test fun anUnreadableDateIsShownAsIs() = assertEquals("garbage", UploadStatsText.dayLabel("garbage", "2026-10-09"))

    @Test fun todayFollowsTheGivenTimeZone() {
        val noonUtc = 1_791_547_200_000L // 2026-10-09T12:00:00Z
        assertEquals("2026-10-09", UploadStatsText.today(noonUtc, TimeZone.getTimeZone("UTC")))
        // 22:00Z on the 9th is already 03:30 on the 10th in India.
        assertEquals("2026-10-10", UploadStatsText.today(noonUtc + 10 * 3_600_000L, TimeZone.getTimeZone("Asia/Kolkata")))
    }

    @Test fun theOffsetIsMinutesEastOfUtc() {
        assertEquals(330, UploadStatsText.tzOffsetMinutes(0, TimeZone.getTimeZone("Asia/Kolkata")))
        assertEquals(-300, UploadStatsText.tzOffsetMinutes(1_791_547_200_000L, TimeZone.getTimeZone("America/Bogota")))
    }

    @Test fun countsArePluralised() {
        assertEquals("1 recording", UploadStatsText.recordings(1))
        assertEquals("0 recordings", UploadStatsText.recordings(0))
        assertEquals("7 recordings", UploadStatsText.recordings(7))
    }

    @Test fun ageOfASavedCopy() {
        assertNull(UploadStatsText.age(1_000, 30_000))
        assertEquals("5 min ago", UploadStatsText.age(0, 5 * 60_000))
        assertEquals("3 h ago", UploadStatsText.age(0, 3 * 3_600_000L))
        assertEquals("2 days ago", UploadStatsText.age(0, 2 * 86_400_000L))
        assertNotNull(UploadStatsText.age(0, 60_000))
    }

    // ---- the day list and a day's recordings -----------------------------------------------------

    private fun rec(id: String, date: String, campaign: String = "c-1", title: String = "Kitchen tidy", marketplace: String? = null) =
        UploadedRecording(id, campaign, title, "2026-10-09T10:00:00.000Z", date, "uploaded", marketplace)

    @Test fun eachDayIsOneRowWithItsCampaignSplitBiggestFirst() {
        val stats = UploadStats(
            "a@b.co", 190, 100, null,
            listOf(
                DayCount("2026-10-09", 100, mapOf("c-2" to 35, "c-1" to 40, "c-3" to 25)),
                DayCount("2026-10-08", 90, mapOf("c-1" to 90)),
            ),
        )

        val rows = UploadStatsText.dayRows(stats, "2026-10-09") { id -> if (id == "c-1") "Kitchen tidy" else null }

        assertEquals("Today", rows[0].title)
        assertEquals(100, rows[0].count)
        assertEquals("Kitchen tidy 40 \u00b7 c-2 35 \u00b7 c-3 25", rows[0].campaigns)
        assertEquals("Yesterday", rows[1].title)
        assertEquals("Kitchen tidy 90", rows[1].campaigns)
    }

    @Test fun tiesKeepAStableOrder() {
        assertEquals("a 2 \u00b7 b 2", UploadStatsText.campaignSplit(mapOf("b" to 2, "a" to 2)) { null })
    }

    @Test fun aRecordingShowsItsNameCampaignAndUploadedStatus() {
        val r = rec("capture-20261009T154304-60c403", "2026-10-09")
        assertEquals("20261009T154304-60c403", UploadStatsText.recordingName(r.sessionId))
        // The phone's own name for the campaign wins; the server's title is the fallback.
        assertEquals("Kitchen tidy \u00b7 Uploaded", UploadStatsText.recordingDetail(r) { null })
        assertEquals("Mine \u00b7 Uploaded", UploadStatsText.recordingDetail(r) { "Mine" })
    }

    @Test fun theMarketplaceStatusIsASmallSecondLineOnlyWhenTheBackendSaysSo() {
        assertNull(UploadStatsText.reviewLine(rec("a", "d")))
        assertEquals("In review", UploadStatsText.reviewLine(rec("a", "d", marketplace = "IN_REVIEW")))
        assertEquals("Approved", UploadStatsText.reviewLine(rec("a", "d", marketplace = "APPROVED")))
        assertEquals("Checking", UploadStatsText.reviewLine(rec("a", "d", marketplace = "CHECKING")))
        assertEquals("Rejected", UploadStatsText.reviewLine(rec("a", "d", marketplace = "REJECTED")))
        assertEquals("Not counted", UploadStatsText.reviewLine(rec("a", "d", marketplace = "NOT_COUNTED")))
    }

    @Test fun aNotCountedRecordingSaysWhyWhenTheReasonIsKnown() {
        assertEquals("Not counted: campaign full", UploadStatsText.marketplaceLabel("NOT_COUNTED", "CAMPAIGN_FULL"))
        assertEquals("Not counted: campaign closed", UploadStatsText.marketplaceLabel("NOT_COUNTED", "CAMPAIGN_CLOSED"))
        assertEquals("Not counted: duplicate recording", UploadStatsText.marketplaceLabel("NOT_COUNTED", "DUPLICATE"))
        assertEquals("Rejected: Blurry video", UploadStatsText.marketplaceLabel("REJECTED", "Blurry video"))
        assertEquals("Something new", UploadStatsText.marketplaceLabel("SOMETHING_NEW", null))
    }

    @Test fun theDaySummaryDescribesTheWholeDay() {
        val day = List(96) { rec("c$it", "d") } + List(2) { rec("r$it", "d", marketplace = "IN_REVIEW") } + List(2) { rec("a$it", "d", marketplace = "APPROVED") }
        assertEquals("100 recordings \u00b7 2 in review \u00b7 2 approved", UploadStatsText.daySummary(day))
        assertEquals("1 recording", UploadStatsText.daySummary(listOf(rec("c", "d"))))
    }

    @Test fun everyRecordingReadsUploadedWhatEverTheOldCopySaid() {
        assertEquals("Uploaded", UploadStatsText.status("uploaded"))
        assertEquals("Uploaded", UploadStatsText.status("publish_failed")) // an old cached copy
    }
}
