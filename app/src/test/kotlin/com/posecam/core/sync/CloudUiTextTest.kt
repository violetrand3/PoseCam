package com.posecam.core.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudUiTextTest {
    private fun summary(status: SessionCloudStatus, done: Long = 0, total: Long = 0, verified: Int = 0, files: Int = 0) =
        SessionCloudSummary("capture-x", status, files, verified, total, done, null)

    private fun row(s: SessionCloudSummary?, policy: SyncPolicy = SyncPolicy.WIFI_ONLY, waiting: Boolean = false) =
        CloudUiText.rowStatus(s, policy, waiting)

    @Test fun everyStatusHasAHumanLine() {
        assertEquals("On this phone only", row(null))
        assertEquals("On this phone only", row(summary(SessionCloudStatus.LOCAL_ONLY)))
        assertEquals("Queued", row(summary(SessionCloudStatus.PENDING)))
        assertEquals("Verifying", row(summary(SessionCloudStatus.VERIFYING)))
        assertEquals("Synced", row(summary(SessionCloudStatus.SYNCED).copy(publishState = PublishState.UNSUPPORTED)))
        assertEquals("Upload failed", row(summary(SessionCloudStatus.FAILED)))
        assertEquals("Choose a campaign to upload", row(summary(SessionCloudStatus.AWAITING_CAMPAIGN)))
        assertEquals("Belongs to another account", row(summary(SessionCloudStatus.OTHER_ACCOUNT)))
    }

    // ---- the video export holds up SYNCED: the row says why a recording is not uploaded -----------

    private fun blocked(state: ExportState, rotationSet: Boolean = true) = CloudUiText.rowStatus(
        summary(SessionCloudStatus.EXPORT_BLOCKED).copy(exportState = state), SyncPolicy.WIFI_ONLY, false, rotationSet,
    )

    @Test fun aRecordingWhoseExportCannotBeMadeNeverClaimsToBeSyncedAndSaysWhy() {
        assertEquals("Not uploaded: off protocol (wrong size, frame rate or focus)", blocked(ExportState.OFF_PROTOCOL))
        assertEquals("Not uploaded: it cannot be exported", blocked(ExportState.NOT_EXPORTABLE))
        assertEquals("Not uploaded: export failed", blocked(ExportState.FAILED))
        assertEquals("Preparing the video…", blocked(ExportState.PENDING))
        assertEquals("Set the video orientation to upload", blocked(ExportState.PENDING, rotationSet = false))
        for (state in ExportState.entries.filter { it != ExportState.DONE }) assertTrue(!blocked(state).startsWith("Synced"))
    }

    @Test fun anExportProblemExplainsItselfInTheDialog() {
        val offProtocol = summary(SessionCloudStatus.EXPORT_BLOCKED).copy(exportState = ExportState.OFF_PROTOCOL, exportNote = "Recorded at 1280x720.")
        assertTrue(CloudUiText.missingExportDetail(offProtocol)!!.contains("Recorded at 1280x720."))
        assertTrue(CloudUiText.missingExportDetail(offProtocol)!!.contains("not uploaded"))
        assertNull(CloudUiText.missingExportDetail(summary(SessionCloudStatus.SYNCED).copy(exportState = ExportState.DONE)))
    }

    // ---- redo takes, incomplete recordings, and campaigns the marketplace did not count -----------

    @Test fun aRedoTakeOrAnIncompleteRecordingIsNeverOfferedForUpload() {
        for (status in listOf(null, SessionCloudStatus.LOCAL_ONLY, SessionCloudStatus.AWAITING_CAMPAIGN)) {
            val s = status?.let { summary(it) }
            assertEquals("Not uploaded: redo take", CloudUiText.rowStatus(s, SyncPolicy.WIFI_ONLY, false, true, null, "redo take"))
            assertEquals("Not uploaded: recording incomplete", CloudUiText.rowStatus(s, SyncPolicy.WIFI_ONLY, false, true, null, "recording incomplete"))
        }
        // Without a reason the same rows are what they were.
        assertEquals("Choose a campaign to upload", row(summary(SessionCloudStatus.AWAITING_CAMPAIGN)))
    }

    @Test fun aNotCountedRecordingIsStillSyncedAndSaysWhyInPlainWords() {
        fun syncedAs(reason: String?) = summary(SessionCloudStatus.SYNCED)
            .copy(publishState = PublishState.UNSUPPORTED, marketplaceStatus = "NOT_COUNTED", marketplaceReason = reason)
        assertEquals("Synced · not counted: the campaign was already full", row(syncedAs("CAMPAIGN_FULL")))
        assertEquals("Synced · not counted: the campaign had closed", row(syncedAs("CAMPAIGN_CLOSED")))
        assertEquals("Synced · not counted: it was already uploaded", row(syncedAs("DUPLICATE")))
        assertEquals("Synced · not counted", row(syncedAs(null)))
        assertEquals("Synced", row(summary(SessionCloudStatus.SYNCED).copy(publishState = PublishState.UNSUPPORTED, marketplaceStatus = "CHECKING")))
    }

    @Test fun theTakeDialogTellsWhatHappensToTheTake() {
        assertEquals("Kitchen: waiting for Wi-Fi to upload.", CloudUiText.uploadingToMessage("Kitchen", SyncPolicy.WIFI_ONLY, networkOk = false))
        assertEquals("Uploading to Kitchen (Wi-Fi only).", CloudUiText.uploadingToMessage("Kitchen", SyncPolicy.WIFI_ONLY, networkOk = true))
        assertEquals("Uploading to Kitchen (any network).", CloudUiText.uploadingToMessage("Kitchen", SyncPolicy.ANY_NETWORK, networkOk = true))
        assertEquals("Kitchen: upload is manual, use Recordings to start it.", CloudUiText.uploadingToMessage("Kitchen", SyncPolicy.MANUAL_ONLY, networkOk = true))
        assertTrue(CloudUiText.notUploadedTakeMessage(redo = true, complete = true)!!.startsWith("Not uploaded"))
        assertTrue(CloudUiText.notUploadedTakeMessage(redo = false, complete = false)!!.contains("incomplete"))
        assertNull(CloudUiText.notUploadedTakeMessage(redo = false, complete = true))
    }

    @Test fun theCampaignChooserIsTheOnlyWayToUploadAnOlderRecording() {
        assertEquals("Choose a campaign to upload…", CloudUiText.chooseCampaignLabel(hasCampaign = false))
        assertEquals("Change campaign…", CloudUiText.chooseCampaignLabel(hasCampaign = true))
        assertNull(CloudUiText.actionLabel(CloudCardAction.CHOOSE_CAMPAIGN))
    }

    private fun synced(state: PublishState, sets: Int = 0, note: String? = null) =
        summary(SessionCloudStatus.SYNCED).copy(publishState = state, publishedSets = sets, publishNote = note)

    @Test fun syncedReadsDoneOnlyOnceTheBackendPublishedIt() {
        assertEquals("Uploaded · publishing…", row(synced(PublishState.PENDING)))
        assertEquals("Done · live", row(synced(PublishState.DONE, sets = 2)))
    }

    @Test fun aPublishWithNoSetsIsNotDoneYetForARecordingThatWasExported() {
        assertEquals("Uploaded · publishing…", row(synced(PublishState.DONE, sets = 0)))
    }

    @Test fun aFailedPublishSaysWhy() {
        assertEquals("Publish failed", row(synced(PublishState.FAILED, note = "ffmpeg exited 1")))
        assertEquals("Publish failed", row(synced(PublishState.FAILED)))
        assertEquals("ffmpeg exited 1", CloudUiText.publishFailedDetail(synced(PublishState.FAILED, note = "ffmpeg exited 1"))!!.substringAfterLast("\n"))
        assertNull(CloudUiText.publishFailedDetail(synced(PublishState.DONE, sets = 1)))
    }

    @Test fun aBackendThatDoesNotReportPublishingStaysPlainSynced() {
        assertEquals("Synced", row(synced(PublishState.UNSUPPORTED)))
    }

    @Test fun exportProblemsStillComeBeforeThePublishLine() {
        val offProtocol = synced(PublishState.DONE, sets = 0).copy(exportState = ExportState.OFF_PROTOCOL)
        assertEquals("Synced · no export (off protocol)", row(offProtocol))
    }

    @Test fun deleteWarningDoesNotPromiseTheWebsite() {
        assertTrue(CloudUiText.deleteWarning(true, synced(PublishState.UNSUPPORTED)).contains("only frees space on this phone"))
        assertTrue(CloudUiText.deleteWarning(true, summary(SessionCloudStatus.OTHER_ACCOUNT)).contains("another account"))
    }

    @Test fun pendingWorkThatCannotStartReadsWaitingForWifi() {
        assertEquals("Waiting for Wi-Fi", row(summary(SessionCloudStatus.PENDING), waiting = true))
    }

    @Test fun manualModeSaysSoInsteadOfPromisingAnUpload() {
        assertEquals("Not uploaded (manual mode)", row(summary(SessionCloudStatus.PENDING), SyncPolicy.MANUAL_ONLY))
    }

    @Test fun uploadingShowsPercentAndFiles() {
        assertEquals("Uploading 45%", row(summary(SessionCloudStatus.UPLOADING, done = 45, total = 100, verified = 3, files = 9)))
    }

    @Test fun aQueuedRecordingSaysWhyItIsNotMoving() {
        fun queued(error: String?) = row(SessionCloudSummary("capture-x", SessionCloudStatus.PENDING, 4, 0, 100, 0, error))
        assertEquals("Queued", queued(null))
        assertEquals("Queued · no connection", queued("Network: Unable to resolve host"))
        assertEquals("Queued · retry: HTTP 500 INTERNAL: boom", queued("HTTP 500 INTERNAL: boom"))
    }

    @Test fun theCampaignIsShownOnEveryFiledRecording() {
        fun withCampaign(status: SessionCloudStatus, waiting: Boolean = false) =
            CloudUiText.rowStatus(summary(status, done = 45, total = 100), SyncPolicy.WIFI_ONLY, waiting, true, "Kitchen tidy")
        assertEquals("Kitchen tidy · Uploading 45%", withCampaign(SessionCloudStatus.UPLOADING))
        assertEquals("Kitchen tidy · Queued", withCampaign(SessionCloudStatus.PENDING))
        assertEquals("Kitchen tidy · Waiting for Wi-Fi", withCampaign(SessionCloudStatus.PENDING, waiting = true))
        assertEquals("Kitchen tidy · Verifying", withCampaign(SessionCloudStatus.VERIFYING))
        assertEquals("Kitchen tidy · Upload failed", withCampaign(SessionCloudStatus.FAILED))
        assertEquals("Kitchen tidy · Synced", synced(withCampaign = "Kitchen tidy"))
        assertEquals("Synced", synced(withCampaign = null))
    }

    private fun synced(withCampaign: String?) = CloudUiText.rowStatus(
        SessionCloudSummary(
            "capture-x", SessionCloudStatus.SYNCED, 4, 4, 100, 100, null,
            exportState = ExportState.DONE, publishState = PublishState.UNSUPPORTED,
        ),
        SyncPolicy.WIFI_ONLY, false, true, withCampaign,
    )

    @Test fun noCampaignOrAnotherAccountsRecordingNamesNoCampaign() {
        assertEquals("Uploading 45%", row(summary(SessionCloudStatus.UPLOADING, done = 45, total = 100)))
        assertEquals(
            "Choose a campaign to upload",
            CloudUiText.rowStatus(summary(SessionCloudStatus.AWAITING_CAMPAIGN), SyncPolicy.WIFI_ONLY, false, true, "Kitchen tidy"),
        )
        assertEquals(
            "Belongs to another account",
            CloudUiText.rowStatus(summary(SessionCloudStatus.OTHER_ACCOUNT), SyncPolicy.WIFI_ONLY, false, true, "Kitchen tidy"),
        )
    }

    @Test fun onlyStartAndRetryHaveAMenuAction() {
        assertEquals("Upload to cloud now", CloudUiText.actionLabel(CloudCardAction.START))
        assertEquals("Retry failed upload", CloudUiText.actionLabel(CloudCardAction.RETRY))
        assertNull(CloudUiText.actionLabel(CloudCardAction.INFO))
    }

    @Test fun deleteWarningTellsTheTruthAboutTheCloud() {
        assertTrue(CloudUiText.deleteWarning(false, null).contains("shared or saved first")) // exactly the original wording
        assertTrue(CloudUiText.deleteWarning(true, summary(SessionCloudStatus.SYNCED)).contains("synced to the cloud"))
        assertTrue(CloudUiText.deleteWarning(true, summary(SessionCloudStatus.UPLOADING)).contains("only copy"))
        assertTrue(CloudUiText.deleteWarning(true, null).contains("only copy"))
    }

    @Test fun storageSummaryCountsEachGroupAndStaysEmptyWhenThereIsNothing() {
        assertEquals("", CloudUiText.storageSummary(emptyList()))
        assertEquals("", CloudUiText.storageSummary(listOf(summary(SessionCloudStatus.LOCAL_ONLY))))
        assertEquals(
            "2 synced, 3 waiting, 1 failed, 1 need a campaign, 1 for another account",
            CloudUiText.storageSummary(
                listOf(SessionCloudStatus.SYNCED, SessionCloudStatus.SYNCED, SessionCloudStatus.PENDING, SessionCloudStatus.UPLOADING,
                    SessionCloudStatus.WAITING_FOR_WIFI, SessionCloudStatus.FAILED, SessionCloudStatus.AWAITING_CAMPAIGN,
                    SessionCloudStatus.OTHER_ACCOUNT).map { summary(it) },
            ),
        )
    }

    @Test fun labelsForSettings() {
        assertEquals("Wi-Fi only", CloudUiText.policyLabel(SyncPolicy.WIFI_ONLY))
        assertEquals("off", CloudUiText.retentionLabel(false, 7))
        assertEquals("after 1 day", CloudUiText.retentionLabel(true, 1))
        assertEquals("after 7 days", CloudUiText.retentionLabel(true, 7))
    }

    @Test fun mobileDataMessageNamesTheSizeWhenKnown() {
        assertTrue(CloudUiText.mobileDataMessage(200L * 1024 * 1024).contains("200 MB"))
        assertTrue(CloudUiText.mobileDataMessage(0).contains("this recording"))
    }
}
