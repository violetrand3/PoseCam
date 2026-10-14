package com.posecam.core.sync

import com.posecam.core.cloud.CloudHttpException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * "Fix the sign-out problem": unfinished uploads used to go to whichever account signed in next. Every recording now
 * belongs to the account that made it, and the queue only ever works for the signed-in one.
 */
class AccountBindingTest {
    private val f = SyncFixture()
    private var refreshes = 0
    private val accounts = f.processorFor { AccountScope({ signedIn }, { refreshes++ }) }
    private var signedIn: String? = SyncFixture.ACCOUNT

    init {
        f.api.multipartThreshold = Long.MAX_VALUE
        f.dao.defaultAccountId = null // each test says whose recording it is
    }

    private suspend fun recording(id: String, owner: String?): File {
        val dir = f.sessionDir(id)
        f.repo.registerSession(id, dir, SyncFixture.CAMPAIGN, rawRequired = false, accountId = owner)
        f.repo.finalizeSession(id, dir, "complete", UploadPlan.forSession(id, dir, f.staging, includeRaw = false))
        return dir
    }

    private val mine = "capture-20260917T090000-aaaaaa"
    private val theirs = "capture-20260917T091000-bbbbbb"

    @Test fun anotherAccountsRecordingIsNeverUploadedByTheSignedInOne() = runBlocking {
        recording(mine, SyncFixture.ACCOUNT)
        recording(theirs, SyncFixture.OTHER_ACCOUNT)

        assertEquals(QueueRunResult.DONE, accounts.runQueue())

        assertEquals(setOf(mine), f.api.createdSessions)
        assertNotNull(f.repo.session(mine)!!.syncedAt)
        // Untouched: not created, not queued as runnable, no error, nothing deleted.
        val other = f.repo.session(theirs)!!
        assertFalse(other.cloudCreated)
        assertNull(other.syncedAt)
        assertNull(other.lastError)
        assertEquals(2, f.repo.uploadsForSession(theirs).size)
        assertTrue(f.repo.uploadsForSession(theirs).all { it.state == UploadState.PENDING && it.retryCount == 0 && it.lastError == null })
        assertTrue(File(other.directoryPath).isDirectory)
    }

    @Test fun signingInAsTheOtherAccountUploadsTheirsAndLeavesTheFirstAlone() = runBlocking {
        recording(mine, SyncFixture.ACCOUNT)
        recording(theirs, SyncFixture.OTHER_ACCOUNT)
        accounts.runQueue()

        signedIn = SyncFixture.OTHER_ACCOUNT
        accounts.runQueue()

        assertEquals(setOf(mine, theirs), f.api.createdSessions)
        assertNotNull(f.repo.session(theirs)!!.syncedAt)
        assertEquals(1, f.api.createdRequests.count { it.sessionId == mine }) // not uploaded a second time
    }

    @Test fun nobodySignedInMeansNothingIsSentOrTouched() = runBlocking {
        recording(mine, SyncFixture.ACCOUNT)
        signedIn = null

        assertFalse(accounts.hasWork())
        assertEquals(QueueRunResult.DONE, accounts.runQueue())

        assertEquals(0, f.api.calls.size)
        assertEquals(0, refreshes)
        assertEquals(UploadState.PENDING, f.row(mine, "manifest.json").state)
    }

    @Test fun hasWorkIgnoresAnotherAccountsRecordings() = runBlocking {
        recording(theirs, SyncFixture.OTHER_ACCOUNT)

        assertFalse(accounts.hasWork())
        signedIn = SyncFixture.OTHER_ACCOUNT
        assertTrue(accounts.hasWork())
    }

    @Test fun aRecordingFromBeforeAccountsWereTrackedIsAdoptedByTheFirstAccountThatSignsIn() = runBlocking {
        recording("capture-20260101T000000-000001", null)
        recording("capture-20260101T000100-000002", null)
        recording(theirs, SyncFixture.OTHER_ACCOUNT)

        accounts.runQueue()

        assertEquals(SyncFixture.ACCOUNT, f.repo.session("capture-20260101T000000-000001")!!.accountId)
        assertEquals(SyncFixture.ACCOUNT, f.repo.session("capture-20260101T000100-000002")!!.accountId)
        assertEquals(setOf("capture-20260101T000000-000001", "capture-20260101T000100-000002"), f.api.createdSessions)
        assertEquals(SyncFixture.OTHER_ACCOUNT, f.repo.session(theirs)!!.accountId) // an owned row is never adopted
        // And a later account finds nothing left to adopt.
        signedIn = SyncFixture.OTHER_ACCOUNT
        accounts.runQueue()
        assertEquals(SyncFixture.ACCOUNT, f.repo.session("capture-20260101T000000-000001")!!.accountId)
    }

    @Test fun signingOutMidPassStopsTheWholePass() = runBlocking {
        recording(mine, SyncFixture.ACCOUNT)
        f.api.verifyBehavior = { signedIn = null } // the collector logs out while the first file is being verified

        accounts.runQueue()

        assertEquals(1, f.api.verified.size) // the second file was never started
        assertNull(f.repo.session(mine)!!.syncedAt)
        assertEquals(0, f.api.callsTo("completeSession"))
        assertTrue(f.repo.uploadsForSession(mine).none { it.state == UploadState.FAILED })
    }

    @Test fun anAccessTokenAboutToExpireIsRefreshedBeforeEachPass() = runBlocking {
        recording(mine, SyncFixture.ACCOUNT)

        accounts.runQueue()
        accounts.runQueue()

        assertEquals(2, refreshes)
    }

    @Test fun retryFailedByAccountDoesNotRevivePermanentlyRefusedSessionsOfOthers() = runBlocking {
        recording(mine, SyncFixture.ACCOUNT)
        recording(theirs, SyncFixture.OTHER_ACCOUNT)
        f.repo.markSessionFailed(mine, "x")
        f.repo.markSessionFailed(theirs, "y")

        f.repo.retryFailed(null, SyncFixture.ACCOUNT)

        assertFalse(f.repo.session(mine)!!.permanentFailure)
        assertTrue(f.repo.session(theirs)!!.permanentFailure)
    }

    @Test fun aCampaignRefusalIsPermanentAndExplainedInPlainWords() = runBlocking {
        recording(mine, SyncFixture.ACCOUNT)
        f.api.failOnce("createSession", CloudHttpException(403, "NOT_ENROLLED", "x"))

        accounts.runQueue()

        val session = f.repo.session(mine)!!
        assertTrue(session.permanentFailure)
        assertEquals("You have not accepted this campaign. Accept it on the website, then choose it again.", session.lastError)
        // Choosing another campaign is a new chance.
        assertTrue(f.repo.assignCampaign(mine, SyncFixture.CAMPAIGN_2, false, SyncFixture.ACCOUNT))
        assertFalse(f.repo.session(mine)!!.permanentFailure)
        assertNull(f.repo.session(mine)!!.lastError)
    }

    @Test fun aNotCountedRecordingIsStillSynced() = runBlocking {
        recording(mine, SyncFixture.ACCOUNT)
        f.api.marketplaceOnComplete = com.posecam.core.cloud.MarketplaceInfo("NOT_COUNTED", "CAMPAIGN_FULL", null)

        accounts.runQueue()

        val session = f.repo.session(mine)!!
        assertNotNull(session.syncedAt) // the files are kept either way: the phone may free its copy
        assertEquals("NOT_COUNTED", session.marketplaceStatus)
        assertEquals("CAMPAIGN_FULL", session.marketplaceReason)
        val summary = SessionCloudSummary.from(f.dao.aggregates().first { it.sessionId == mine }, session)
        assertTrue(summary.notCounted)
        assertEquals(SessionCloudStatus.SYNCED, summary.status)
    }

    @Test fun theBackendAskingForRawDataAfterAllQueuesTheRawFiles() = runBlocking {
        val dir = recording(mine, SyncFixture.ACCOUNT)
        f.file(dir, "poses.csv", "poses")
        f.file(dir, "intrinsics.json", "{}")
        f.api.rawRequiredOnServer = true // the cached campaign was out of date: it collects raw data now
        val withRaw = f.processorFor(
            AccountScope({ signedIn }),
            rawPlan = { id, directory -> UploadPlan.forSession(id, directory, f.staging, includeRaw = true) },
        )

        withRaw.runQueue()

        assertTrue(f.repo.session(mine)!!.rawRequired)
        assertTrue(f.api.verified.containsAll(listOf("poses.csv", "intrinsics.json", "manifest.json", "device.json")))
        assertNotNull(f.repo.session(mine)!!.syncedAt)
    }

    @Test fun aBackendIncompleteAnswerNamingRawFilesQueuesThemToo() = runBlocking {
        val dir = recording(mine, SyncFixture.ACCOUNT)
        f.file(dir, "poses.csv", "poses")
        val withRaw = f.processorFor(
            AccountScope({ signedIn }),
            rawPlan = { id, directory -> UploadPlan.forSession(id, directory, f.staging, includeRaw = true) },
        )
        f.api.failOnce("completeSession", CloudHttpException(409, "INCOMPLETE", "x", """{"unverified":[],"missingRequired":["poses.csv"]}"""))

        assertEquals(QueueRunResult.RETRY, withRaw.runQueue())
        assertEquals(QueueRunResult.DONE, withRaw.runQueue())

        assertTrue(f.repo.session(mine)!!.rawRequired)
        assertTrue("poses.csv" in f.api.verified)
        assertNotNull(f.repo.session(mine)!!.syncedAt)
    }
}
