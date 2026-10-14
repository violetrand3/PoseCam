package com.posecam.core.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionCloudSummaryTest {
    private fun session(
        synced: Long? = null, final: Boolean = true, failed: Boolean = false, created: Boolean = true, pipe: String? = "c-kitchen",
        export: ExportState = ExportState.DONE, account: String? = "acct-1",
    ) = CloudSessionEntity("s", "/x", "complete", final, created, failed, synced, null, 0, 0, pipe, exportState = export, accountId = account)

    private fun agg(total: Int = 4, verified: Int = 0, failed: Int = 0, active: Int = 0, awaiting: Int = 0) =
        SessionUploadAggregate("s", total, verified, failed, active, awaiting, 400, 100, null)

    private fun status(a: SessionUploadAggregate?, s: CloudSessionEntity?) = SessionCloudSummary.from(a, s).status

    @Test fun pendingBecomesWaitingForWifiOnlyWhenTheNetworkDoesNotQualify() {
        val summary = SessionCloudSummary.from(agg(), session())
        assertEquals(SessionCloudStatus.PENDING, summary.displayStatus(waitingForNetwork = false))
        assertEquals(SessionCloudStatus.WAITING_FOR_WIFI, summary.displayStatus(waitingForNetwork = true))
    }

    @Test fun activeTransferIsUploadingAndNeverWaitingForWifi() {
        val summary = SessionCloudSummary.from(agg(active = 1), session())
        assertEquals(SessionCloudStatus.UPLOADING, summary.displayStatus(waitingForNetwork = true))
    }

    @Test fun aFileBeingVerifiedWhileOthersAreStillToSendIsStillUploading() {
        // Without this the row flipped Uploading -> Verifying -> Uploading once per file.
        assertEquals(SessionCloudStatus.UPLOADING, status(agg(total = 8, verified = 5, active = 1, awaiting = 1), session()))
    }

    @Test fun everythingUploadedButNotConfirmedIsVerifying() {
        assertEquals(SessionCloudStatus.VERIFYING, status(agg(total = 4, verified = 2, active = 2, awaiting = 2), session()))
        assertEquals(SessionCloudStatus.VERIFYING, status(agg(total = 4, verified = 4), session()))
    }

    @Test fun syncedNeedsBackendConfirmationAndAllFilesVerified() {
        assertEquals(SessionCloudStatus.SYNCED, status(agg(verified = 4), session(synced = 5)))
        // Re-queued file after sync: no longer synced.
        assertEquals(SessionCloudStatus.PENDING, status(agg(verified = 3), session(synced = 5)))
    }

    @Test fun anyFailureOrRefusedSessionIsFailed() {
        assertEquals(SessionCloudStatus.FAILED, status(agg(failed = 1), session()))
        assertEquals(SessionCloudStatus.FAILED, status(null, session(failed = true)))
    }

    @Test fun aSessionWithOnlyAPendingCreationIsPending() {
        assertEquals(SessionCloudStatus.PENDING, status(null, session(created = false, final = false)))
    }

    @Test fun aRecordingWithoutACampaignAwaitsTheCollectorsChoiceAndNothingElse() {
        assertEquals(SessionCloudStatus.AWAITING_CAMPAIGN, status(agg(), session(created = false, pipe = null)))
        assertEquals(SessionCloudStatus.AWAITING_CAMPAIGN, status(null, session(created = false, final = false, pipe = null)))
    }

    @Test fun choosingACampaignMakesItPendingAndAFailureOrSyncStillWins() {
        assertEquals(SessionCloudStatus.PENDING, status(agg(), session(created = false, pipe = "c-laundry")))
        assertEquals(SessionCloudStatus.FAILED, status(agg(failed = 1), session(created = false, pipe = null)))
        assertEquals(SessionCloudStatus.SYNCED, status(agg(verified = 4), session(synced = 1, created = false, pipe = null)))
    }

    @Test fun theChosenCampaignAndAccountAreCarriedToTheUi() {
        assertEquals("c-kitchen", SessionCloudSummary.from(agg(), session()).pipe)
        assertEquals("acct-1", SessionCloudSummary.from(agg(), session()).accountId)
        assertEquals(null, SessionCloudSummary.from(agg(), session(pipe = null, created = false)).pipe)
    }

    @Test fun aRecordingWithEverythingUploadedButNoExportIsNeverVerifyingOrSynced() {
        for (state in listOf(ExportState.PENDING, ExportState.OFF_PROTOCOL, ExportState.NOT_EXPORTABLE, ExportState.FAILED)) {
            assertEquals(state.name, SessionCloudStatus.EXPORT_BLOCKED, status(agg(total = 2, verified = 2), session(export = state)))
        }
        assertEquals(SessionCloudStatus.VERIFYING, status(agg(total = 2, verified = 2), session(export = ExportState.DONE)))
        // A crashed export is the kind a tap can fix.
        assertEquals(true, SessionCloudSummary.from(agg(total = 2, verified = 2), session(export = ExportState.FAILED)).needsFileRetry)
    }

    @Test fun anotherAccountsRecordingReadsBelongsToAnotherAccountUnlessItIsAlreadySynced() {
        val pending = SessionCloudSummary.from(agg(), session(account = "acct-2"))
        assertEquals(SessionCloudStatus.OTHER_ACCOUNT, pending.forAccount("acct-1").status)
        assertEquals(SessionCloudStatus.PENDING, pending.forAccount("acct-2").status)
        assertEquals(SessionCloudStatus.PENDING, pending.forAccount(null).status) // nobody signed in: unchanged
        val synced = SessionCloudSummary.from(agg(verified = 4), session(synced = 1, account = "acct-2"))
        assertEquals(SessionCloudStatus.SYNCED, synced.forAccount("acct-1").status)
        val unowned = SessionCloudSummary.from(agg(), session(account = null))
        assertEquals(SessionCloudStatus.PENDING, unowned.forAccount("acct-1").status)
    }

    @Test fun theMarketplaceVerdictIsCarriedAndANotCountedRecordingIsStillSynced() {
        val view = SessionCloudSummary.from(
            agg(verified = 4), session(synced = 1).copy(marketplaceStatus = "NOT_COUNTED", marketplaceReason = "CAMPAIGN_FULL"),
        )
        assertEquals(SessionCloudStatus.SYNCED, view.status)
        assertEquals(true, view.notCounted)
        assertEquals("CAMPAIGN_FULL", view.marketplaceReason)
    }

    @Test fun failedUploadsAndFailedExportsNeedARetry() {
        fun summary(a: SessionUploadAggregate?, s: CloudSessionEntity) = SessionCloudSummary.from(a, s)
        assertEquals(true, summary(agg(failed = 1), session()).needsRetry)
        assertEquals(true, summary(agg(total = 4, verified = 4), session(synced = 1).copy(exportState = ExportState.FAILED)).needsRetry)
        assertEquals(true, SessionCloudSummary.from(agg(total = 4, verified = 4), session(synced = 1), SessionExportAggregate("s", 3, 2, 1)).needsRetry)
        val publishFailed = session(synced = 1).copy(publishState = PublishState.FAILED)
        assertEquals(true, summary(agg(total = 4, verified = 4), publishFailed).needsRetry)
        assertEquals(true, summary(agg(total = 4, verified = 4), publishFailed).publishFailed)
        assertEquals(false, summary(agg(total = 4, verified = 4), publishFailed).needsFileRetry)
        assertEquals(false, summary(agg(total = 4, verified = 4), session(synced = 1).copy(publishState = PublishState.PENDING)).needsRetry)
        assertEquals(false, summary(agg(active = 1), session()).needsRetry)
        assertEquals(false, summary(agg(total = 4, verified = 4), session(synced = 1).copy(exportState = ExportState.DONE)).needsRetry)
    }
}
