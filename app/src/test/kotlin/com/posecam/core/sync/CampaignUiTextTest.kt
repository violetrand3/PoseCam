package com.posecam.core.sync

import com.posecam.core.cloud.CampaignProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CampaignUiTextTest {
    private val campaign = SyncFixture.campaign(SyncFixture.CAMPAIGN, "Kitchen tidy")

    @Test fun theInstructionSnippetIsOneLineOfAboutAHundredAndFortyCharacters() {
        assertEquals("Wipe the counter.", CampaignUiText.instructionSnippet("  Wipe   the\ncounter.  "))
        val long = "Wipe the counter and then " + "put every dish away carefully, ".repeat(10)
        val snippet = CampaignUiText.instructionSnippet(long)
        assertTrue(snippet.length <= 141)
        assertTrue(snippet.endsWith("…"))
        assertTrue(!snippet.contains("\n") && !snippet.contains("  "))
        assertEquals("", CampaignUiText.instructionSnippet("   "))
    }

    @Test fun progressReadsRecordedAndApprovedAndOnlyMentionsWhatIsNotZero() {
        assertEquals("12 recorded · 9 approved", CampaignUiText.progressLine(CampaignProgress(12, 0, 9, 0, 0, 0)))
        assertEquals(
            "12 recorded · 9 approved · 2 in review · 1 rejected · 3 not counted",
            CampaignUiText.progressLine(CampaignProgress(12, 2, 9, 1, 3, 45000)),
        )
    }

    @Test fun paiseBecomeRupees() {
        assertEquals("₹50", CampaignUiText.money(5000, "INR"))
        assertEquals("₹50.50", CampaignUiText.money(5050, "INR"))
        assertEquals("₹0.05", CampaignUiText.money(5, "INR"))
        assertEquals("USD 2", CampaignUiText.money(200, "USD"))
        assertEquals("₹50 per approved recording", CampaignUiText.payLine(campaign))
    }

    @Test fun theDeadlineIsADateOrNoDeadline() {
        assertEquals("No deadline", CampaignUiText.deadlineLine(null))
        assertEquals("No deadline", CampaignUiText.deadlineLine("not a date"))
        assertTrue(CampaignUiText.deadlineLine("2026-11-30T12:00:00.000Z").let { it.startsWith("Ends ") && it.contains("2026") })
    }

    @Test fun aCampaignThatCannotTakeRecordingsSaysWhy() {
        assertNull(CampaignUiText.unavailableReason(campaign))
        assertEquals("Not taking new recordings", CampaignUiText.unavailableReason(SyncFixture.campaign("a", "x", status = "PAUSED")))
        assertEquals("Not taking new recordings", CampaignUiText.unavailableReason(SyncFixture.campaign("a", "x", acceptingUploads = false)))
        assertEquals("No recordings left to take", CampaignUiText.unavailableReason(SyncFixture.campaign("a", "x", remaining = 0)))
    }

    @Test fun theCaptureHeaderNamesTheCampaignOrSaysWhatToDo() {
        assertEquals("Kitchen tidy", CampaignUiText.captureHeader(campaign, anyCampaigns = true))
        assertEquals("Choose a campaign to record", CampaignUiText.captureHeader(null, anyCampaigns = true))
        assertEquals("Accept a campaign on the website to record", CampaignUiText.captureHeader(null, anyCampaigns = false))
        assertEquals("Accept a campaign on the website first", CampaignUiText.EMPTY_TITLE)
    }
}
