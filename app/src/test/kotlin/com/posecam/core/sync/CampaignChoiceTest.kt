package com.posecam.core.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A recording is queued for upload under the campaign that was active when the take began, and only if the take is good
 * and complete. A redo take or an incomplete recording is never queued, offered or uploaded.
 */
class CampaignChoiceTest {
    private val f = SyncFixture()
    private val scheduler = FakeScheduler()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var active: ActiveCampaign? = ActiveCampaign(SyncFixture.CAMPAIGN, "Kitchen tidy", collectRaw = false)
    private var account: String? = SyncFixture.ACCOUNT
    private val coordinator = UploadCoordinator(
        f.repo, scheduler, f.staging, scope, accountId = { account }, activeCampaign = { active },
    )
    private val id = "capture-20260917T090000-a3f9c1"

    init {
        f.dao.defaultPipe = null // production behaviour: the coordinator decides the campaign, not the test double
        f.dao.defaultAccountId = null
        f.api.multipartThreshold = Long.MAX_VALUE
    }

    @After fun tearDown() { ActiveRecordingSessions.remove(id); scope.coroutineContext[kotlinx.coroutines.Job]?.cancel() }

    private suspend fun until(condition: suspend () -> Boolean) = withTimeout(5_000) { while (!condition()) delay(10) }

    private suspend fun finishedTake(redo: Boolean = false, status: String = "complete", frames: Int = 20): File {
        val dir = f.sessionDir(id, status = status, redo = redo)
        f.file(dir, "poses.csv", "poses")
        f.file(dir, "intrinsics.json", "{}")
        f.frames(dir, frames)
        coordinator.onSessionStarted(id, dir)
        coordinator.onSessionFinalized(id, dir, status)
        until { f.repo.session(id)?.recordingFinal == true && !ActiveRecordingSessions.contains(id) }
        return dir
    }

    private fun paths() = runBlocking { f.repo.uploadsForSession(id).map { it.relativePath } }

    // ---- a good, complete take is queued under the active campaign --------------------------------

    @Test
    fun aGoodTakeIsQueuedAutomaticallyUnderTheCampaignThatWasActiveWhenItStarted() = runBlocking {
        finishedTake()
        active = ActiveCampaign(SyncFixture.CAMPAIGN_2, "Laundry", false) // changed after the take began: irrelevant

        val session = f.repo.session(id)!!
        assertEquals(SyncFixture.CAMPAIGN, session.pipe)
        assertEquals(SyncFixture.ACCOUNT, session.accountId)
        assertFalse(session.rawRequired)

        assertEquals(QueueRunResult.DONE, f.processor.runQueue())

        assertEquals(SyncFixture.CAMPAIGN, f.api.createdCampaigns[id]) // sent as campaignId, not as a pipe
        assertNotNull(f.repo.session(id)!!.syncedAt)
        assertEquals(SessionCloudStatus.SYNCED, summary().status)
    }

    @Test
    fun theCreateSessionBodyCarriesTheTakeAndTheDeviceFingerprint() = runBlocking {
        finishedTake()

        f.processor.runQueue()

        val request = f.api.createdRequests.single()
        assertEquals(id, request.sessionId)
        assertEquals("install-1", request.deviceId)
        assertEquals("2026-09-17T09:00:00.000Z", request.createdAt)
        assertEquals("complete", request.recordingStatus)
        assertEquals("test", request.appVersion)
        assertEquals("samsung/SM-G781B/r8q/qcom/android13", request.deviceFingerprint)
        val take = request.take!!
        assertEquals("good", take.verdict)
        assertEquals(30.0, take.durationSeconds, 0.0)
        assertEquals(0, take.poseJumps)
        assertEquals(0, take.longestTrackingLossFrames)
        assertTrue(take.reasons.isEmpty())
    }

    @Test
    fun withoutRawCollectionOnlyManifestAndDeviceAreQueuedAndNoRawFileIsSent() = runBlocking {
        finishedTake()

        assertEquals(listOf("device.json", "manifest.json"), paths())
        f.processor.runQueue()

        assertEquals(setOf("device.json", "manifest.json"), f.api.verified.toSet())
        assertTrue(f.api.verified.none { it == "poses.csv" || it == "intrinsics.json" || it.startsWith("frames-") })
        assertTrue(f.repo.uploadsForSession(id).all { it.required })
    }

    @Test
    fun whenTheCampaignCollectsRawDataTheRawFilesAndFrameChunksAreQueuedToo() = runBlocking {
        active = ActiveCampaign(SyncFixture.CAMPAIGN, "Kitchen tidy", collectRaw = true)
        finishedTake()

        assertEquals(setOf("device.json", "manifest.json", "poses.csv", "intrinsics.json", "frames-00000.zip"), paths().toSet())
        assertTrue(f.repo.session(id)!!.rawRequired)
        f.processor.runQueue()
        assertNotNull(f.repo.session(id)!!.syncedAt)
        assertTrue("frames-00000.zip" in f.api.verified)
    }

    // ---- redo takes and incomplete recordings are never uploaded -----------------------------------

    @Test
    fun aRedoTakeIsNeverQueuedCreatedOrSent() = runBlocking {
        finishedTake(redo = true)

        assertEquals(QueueRunResult.DONE, f.processor.runQueue())

        assertEquals(emptyList<String>(), paths())
        assertNull(f.repo.session(id)!!.pipe) // the campaign it started with is dropped
        assertEquals(0, f.api.calls.size)
        assertFalse(f.processor.hasWork())
        assertEquals(SessionCloudStatus.AWAITING_CAMPAIGN, summary().status)
    }

    @Test
    fun anIncompleteRecordingIsNeverQueuedCreatedOrSent() = runBlocking {
        finishedTake(status = "incomplete")

        f.processor.runQueue()

        assertEquals(emptyList<String>(), paths())
        assertEquals(0, f.api.calls.size)
    }

    @Test
    fun noCampaignChosenMeansNothingIsQueued() = runBlocking {
        active = null
        finishedTake()

        f.processor.runQueue()

        assertEquals(emptyList<String>(), paths())
        assertNull(f.repo.session(id)!!.pipe)
        assertEquals(0, f.api.calls.size)
    }

    @Test
    fun aRedoTakeCannotBeGivenACampaignLater() = runBlocking {
        finishedTake(redo = true)

        coordinator.chooseCampaign(id, ActiveCampaign(SyncFixture.CAMPAIGN, "Kitchen tidy", false))
        delay(200)

        assertNull(f.repo.session(id)!!.pipe)
        assertEquals(emptyList<String>(), paths())
        f.processor.runQueue()
        assertEquals(0, f.api.calls.size)
    }

    @Test
    fun aSessionMarkedIncompleteNeverReachesTheBackendEvenIfItHadACampaign() = runBlocking {
        finishedTake()
        f.dao.updateSession(f.repo.session(id)!!.copy(recordingStatus = "incomplete"))

        f.processor.runQueue()

        assertEquals(0, f.api.callsTo("createSession"))
        assertTrue(f.repo.session(id)!!.permanentFailure)
        assertTrue(f.repo.session(id)!!.lastError!!.startsWith("Not uploaded"))
    }

    @Test
    fun aTakeStillBeingRecordedIsNeverOfferedToTheBackendAlthoughItAlreadyHasACampaign() = runBlocking {
        val dir = f.sessionDir(id, status = "recording")
        coordinator.onSessionStarted(id, dir)
        until { f.repo.session(id) != null }

        assertEquals(SyncFixture.CAMPAIGN, f.repo.session(id)!!.pipe)
        assertFalse(f.processor.hasWork())
        assertEquals(QueueRunResult.DONE, f.processor.runQueue())
        assertEquals(0, f.api.calls.size)
    }

    @Test
    fun theVerdictIsReadFromTheManifestWithTheSameRulesAsTheDialog() {
        fun take(manifest: String): TakeSummary {
            val dir = createTempDir().also { File(it, "manifest.json").writeText(manifest) }
            return SessionManifestInfo.read(dir)!!.take
        }
        val base = """"complete": true, "frame_count": 900, "tracked_frame_count": 900, "first_timestamp_ns": 0, "last_timestamp_ns": 30000000000, "measured_fps": 30.0"""
        assertFalse(take("{$base, \"longest_tracking_gap_s\": 0.1, \"pose_jumps\": {\"count\": 0}}").redo)
        assertTrue(take("{$base, \"longest_tracking_gap_s\": 0.2, \"pose_jumps\": {\"count\": 0}}").redo)
        val jumpy = take("{$base, \"longest_tracking_gap_s\": 0.0, \"pose_jumps\": {\"count\": 2}}")
        assertTrue(jumpy.redo)
        assertEquals(2, jumpy.poseJumps)
        assertEquals("redo", jumpy.verdict)
        assertTrue(jumpy.reasons.single().contains("jumped 2 times"))
        // 4 s long: under the 5 s minimum.
        assertTrue(take("{${base.replace("30000000000", "4000000000")}, \"pose_jumps\": {\"count\": 0}}").redo)
        // 3 frames lost for 0.1 s: reported as frames at the measured rate.
        assertEquals(3, take("{$base, \"longest_tracking_gap_s\": 0.1, \"pose_jumps\": {\"count\": 0}}").longestTrackingLossFrames)
    }

    // ---- choosing or changing the campaign of a recording that is not uploaded yet -------------------

    @Test
    fun anOlderGoodRecordingWithNoCampaignIsQueuedWhenOneIsChosen() = runBlocking {
        active = null
        finishedTake()
        val before = scheduler.scheduled

        coordinator.chooseCampaign(id, ActiveCampaign(SyncFixture.CAMPAIGN_2, "Laundry", collectRaw = false))
        until { f.repo.session(id)?.pipe == SyncFixture.CAMPAIGN_2 }
        until { scheduler.scheduled > before && paths().isNotEmpty() }

        assertEquals(listOf("device.json", "manifest.json"), paths())
        assertEquals(SessionCloudStatus.PENDING, summary().status)
        assertEquals(SyncFixture.ACCOUNT, f.repo.session(id)!!.accountId)
        f.processor.runQueue()
        assertEquals(SyncFixture.CAMPAIGN_2, f.api.createdCampaigns[id])
    }

    @Test
    fun changingTheCampaignBeforeUploadRestartsTheQueueAndDropsRawFilesTheNewCampaignDoesNotCollect() = runBlocking {
        active = ActiveCampaign(SyncFixture.CAMPAIGN, "Kitchen tidy", collectRaw = true)
        finishedTake()
        assertTrue("poses.csv" in paths())

        coordinator.chooseCampaign(id, ActiveCampaign(SyncFixture.CAMPAIGN_2, "Laundry", collectRaw = false))
        until { f.repo.session(id)?.pipe == SyncFixture.CAMPAIGN_2 }

        assertEquals(listOf("device.json", "manifest.json"), paths())
        assertFalse(f.repo.session(id)!!.rawRequired)
        assertTrue(f.repo.uploadsForSession(id).all { it.state == UploadState.PENDING })
    }

    @Test
    fun changingToACampaignThatCollectsRawQueuesTheRawFilesToo() = runBlocking {
        finishedTake()
        assertEquals(listOf("device.json", "manifest.json"), paths())

        coordinator.chooseCampaign(id, ActiveCampaign(SyncFixture.CAMPAIGN_2, "Laundry", collectRaw = true))
        until { paths().size > 2 }

        assertEquals(setOf("device.json", "manifest.json", "poses.csv", "intrinsics.json", "frames-00000.zip"), paths().toSet())
        assertTrue(f.repo.session(id)!!.rawRequired)
    }

    @Test
    fun aCampaignCannotBeChangedOnceTheSessionIsInTheCloud() = runBlocking {
        finishedTake()
        f.processor.runQueue()

        coordinator.chooseCampaign(id, ActiveCampaign(SyncFixture.CAMPAIGN_2, "Laundry", false))
        delay(200)

        assertEquals(SyncFixture.CAMPAIGN, f.repo.session(id)!!.pipe)
    }

    @Test
    fun choosingForAnUnknownSessionDoesNothingAndSchedulesNothing() = runBlocking {
        val before = scheduler.scheduled

        coordinator.chooseCampaign("capture-20260101T000000-ffffff", ActiveCampaign(SyncFixture.CAMPAIGN, "Kitchen tidy", false))
        delay(200)

        assertEquals(before, scheduler.scheduled)
        assertTrue(f.dao.allSessions().isEmpty())
    }

    @Test
    fun theChoiceCannotOvertakeTheRecordersOwnEvents() = runBlocking {
        active = null
        val dir = f.sessionDir(id)
        coordinator.onSessionStarted(id, dir)
        coordinator.onSessionFinalized(id, dir, "complete")
        coordinator.chooseCampaign(id, ActiveCampaign(SyncFixture.CAMPAIGN_2, "Laundry", false))

        until { f.repo.session(id)?.pipe == SyncFixture.CAMPAIGN_2 }
        assertTrue(f.repo.session(id)!!.recordingFinal)
        until { paths().isNotEmpty() }
    }

    // ---- recordings from before this change ---------------------------------------------------------

    @Test
    fun recoveryAdoptsOlderRecordingsButNeverUploadsThemWithoutACampaign() = runBlocking {
        val dir = f.sessionDir(id)
        f.frames(dir, 10)
        UploadQueueRecovery(f.capturesRoot, f.repo, scheduler, f.staging) { false }.run()

        f.processor.runQueue()

        assertEquals(0, f.api.calls.size) // no surprise backlog upload after the update
        assertEquals(SessionCloudStatus.AWAITING_CAMPAIGN, summary().status)

        // The collector files it from the Recordings screen.
        coordinator.chooseCampaign(id, ActiveCampaign(SyncFixture.CAMPAIGN, "Kitchen tidy", false))
        until { f.repo.session(id)?.pipe != null && paths().isNotEmpty() }
        f.processor.runQueue()
        assertEquals(SyncFixture.CAMPAIGN, f.api.createdCampaigns[id])
    }

    @Test
    fun recoveryNeverQueuesARedoTakeOrAKilledTakeEvenWhenItHasACampaign() = runBlocking {
        val redo = f.sessionDir(id, redo = true)
        f.repo.registerSession(id, redo, SyncFixture.CAMPAIGN, false, SyncFixture.ACCOUNT)
        val killed = f.sessionDir("capture-20260917T091000-b00002", status = "incomplete")
        f.repo.registerSession(killed.name, killed, SyncFixture.CAMPAIGN, false, SyncFixture.ACCOUNT)

        UploadQueueRecovery(f.capturesRoot, f.repo, scheduler, f.staging) { false }.run()
        f.processor.runQueue()

        assertEquals(0, f.api.calls.size)
        assertNull(f.repo.session(id)!!.pipe)
        assertNull(f.repo.session(killed.name)!!.pipe)
        assertTrue(f.repo.uploadsForSession(id).isEmpty() && f.repo.uploadsForSession(killed.name).isEmpty())
    }

    @Test
    fun recoveryRebuildsTheQueueOfAGoodRecordingWithTheFilesItsCampaignCollects() = runBlocking {
        val dir = f.sessionDir(id)
        f.frames(dir, 10)
        f.file(dir, "poses.csv", "poses")
        f.repo.registerSession(id, dir, SyncFixture.CAMPAIGN, rawRequired = false, accountId = SyncFixture.ACCOUNT)

        UploadQueueRecovery(f.capturesRoot, f.repo, scheduler, f.staging) { false }.run()

        assertEquals(listOf("device.json", "manifest.json"), paths())
    }

    // ---- wording ------------------------------------------------------------------------------------

    @Test
    fun theOverviewDoesNotCallAnUnfiledRecordingWaitingOrSyncing() {
        val overview = CloudOverview.from(listOf(summary(SessionCloudStatus.AWAITING_CAMPAIGN)), SyncPolicy.WIFI_ONLY, false)
        assertEquals(CloudOverview.Kind.NONE, overview.kind)
        assertEquals(0, overview.waiting)
    }

    @Test
    fun theMenuOffersTheCampaignChooserForAnUnfiledRecording() {
        assertEquals(CloudCardAction.CHOOSE_CAMPAIGN, CloudCardAction.forStatus(SessionCloudStatus.AWAITING_CAMPAIGN, false))
        assertEquals(CloudCardAction.INFO, CloudCardAction.forStatus(SessionCloudStatus.AWAITING_CAMPAIGN, true))
        assertEquals(CloudCardAction.INFO, CloudCardAction.forStatus(SessionCloudStatus.OTHER_ACCOUNT, false))
        assertEquals(CloudCardAction.INFO, CloudCardAction.forStatus(SessionCloudStatus.EXPORT_BLOCKED, false))
    }

    private suspend fun summary() = SessionCloudSummary.from(
        f.dao.aggregates().firstOrNull { it.sessionId == id }, f.repo.session(id),
    )

    private fun summary(status: SessionCloudStatus) = SessionCloudSummary("capture-x", status, 0, 0, 0, 0, null)
}
