package com.posecam.core.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The real Room schema and SQL (the fake DAO elsewhere only mirrors it). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UploadDatabaseTest {
    private lateinit var db: UploadDatabase
    private lateinit var dao: UploadDao
    private lateinit var repo: UploadRepository
    private var now = 1_000L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, UploadDatabase::class.java).allowMainThreadQueries().build()
        dao = db.uploadDao()
        repo = UploadRepository(dao, Transactor.forDatabase(db)) { now }
    }

    @After fun tearDown() { db.close() }

    private fun row(session: String, path: String, state: UploadState = UploadState.PENDING, size: Long = 100, uploaded: Long = 0, required: Boolean = true) =
        UploadEntity(sessionId = session, relativePath = path, localPath = "/x/$session/$path", fileType = UploadFileType.METADATA,
            kind = if (path.startsWith("export/")) UploadSourceKind.EXPORT else UploadSourceKind.PLAIN,
            required = required, sizeBytes = size, state = state, uploadedBytes = uploaded, createdAt = now++, updatedAt = now)

    /** An account's session whose export is done unless a test says otherwise (a new real one starts PENDING). */
    private fun session(
        id: String, created: Boolean = true, final: Boolean = false, failed: Boolean = false, synced: Long? = null,
        account: String? = ACCOUNT, export: ExportState = ExportState.DONE,
    ) = CloudSessionEntity(id, "/x/$id", null, final, created, failed, synced, null, 1, 1, accountId = account, exportState = export)

    @Test
    fun theUniqueIndexMakesInsertsIdempotent() = runBlocking {
        val first = dao.insertUploadIgnore(row("s1", "manifest.json"))
        val second = dao.insertUploadIgnore(row("s1", "manifest.json"))
        val otherSession = dao.insertUploadIgnore(row("s2", "manifest.json"))

        assertTrue(first > 0)
        assertEquals(-1L, second)
        assertTrue(otherSession > 0)
        assertEquals(1, dao.uploadsForSession("s1").size)
    }

    @Test
    fun aSessionIsOnlyCreatedInTheCloudOnceItHasACampaign_realSql() = runBlocking {
        dao.insertSessionIgnore(session("no-campaign", created = false, final = true).copy(pipe = null))
        dao.insertSessionIgnore(session("kitchen", created = false, final = true).copy(pipe = "c-kitchen"))
        dao.insertSessionIgnore(session("laundry", created = false, final = true).copy(pipe = "c-laundry"))
        dao.insertSessionIgnore(session("refused", created = false, failed = true, final = true).copy(pipe = "c-kitchen"))
        dao.insertSessionIgnore(session("still-recording", created = false, final = false).copy(pipe = "c-kitchen"))

        assertEquals(setOf("kitchen", "laundry"), dao.sessionsNeedingCreation(ACCOUNT).map { it.sessionId }.toSet())
    }

    @Test
    fun exportRowsAreSummarisedApartFromTheRequiredOnes_realSql() = runBlocking {
        dao.insertSessionIgnore(session("s1"))
        dao.insertUploadIgnore(row("s1", "manifest.json", UploadState.VERIFIED))
        dao.insertUploadIgnore(row("s1", "export/a/RGB_a.mp4", UploadState.VERIFIED, required = false))
        dao.insertUploadIgnore(row("s1", "export/a/AR_Pose_a.txt", UploadState.FAILED, required = false))
        dao.insertUploadIgnore(row("s1", "export/a/posecam_export.json", UploadState.PENDING, required = false))
        dao.insertUploadIgnore(row("s2", "manifest.json", UploadState.VERIFIED))

        val export = dao.observeExportAggregates().first().single()
        val required = dao.observeAggregates().first().single { it.sessionId == "s1" }

        assertEquals(SessionExportAggregate("s1", files = 3, verifiedFiles = 1, failedFiles = 1), export)
        assertEquals(1, required.totalFiles) // exports never change the raw upload's totals or status
        assertEquals(1, required.verifiedFiles)
    }

    @Test
    fun completingAnExportQueuesItsRowsAheadOfTheRawFilesAndMarksItDone_realDatabase() = runBlocking {
        dao.insertSessionIgnore(session("s1").copy(pipe = "white", recordingFinal = true))
        repo.enqueue("s1", java.io.File("/x/s1"), QueuedFile("poses.csv", "/x/s1/poses.csv", UploadSourceKind.PLAIN, 10))
        val stem = "2026-09-17-09_00_05-a3f9c1-s1"
        val files = listOf("RGB_$stem.mp4", "AR_Pose_$stem.txt", "posecam_export.json").map {
            QueuedFile("export/$stem/$it", "/staging/s1/export/$stem/$it", UploadSourceKind.EXPORT, 5)
        }

        repo.completeExport("s1", files)

        assertEquals(ExportState.DONE, dao.getSession("s1")!!.exportState)
        assertTrue(dao.sessionsNeedingExport(null).isEmpty())
        val queued = dao.uploadsForSession("s1").sortedWith(compareBy({ it.createdAt }, { it.id }))
        // The export now holds up SYNCED, like the other files.
        assertEquals(listOf(true, true, true, true), queued.map { it.required })
        assertTrue(queued.take(3).all { it.kind == UploadSourceKind.EXPORT && it.fileType == UploadFileType.EXPORT })
    }

    @Test
    fun assignCampaignFilesTheSessionOnlyUntilItExistsInTheCloud_realDatabase() = runBlocking {
        dao.insertSessionIgnore(session("s1", created = false, final = true).copy(pipe = null))
        assertTrue(dao.sessionsNeedingCreation(ACCOUNT).isEmpty())

        assertTrue(repo.assignCampaign("s1", "c-kitchen", collectRaw = false, accountId = ACCOUNT))
        assertEquals("c-kitchen", dao.getSession("s1")!!.pipe)
        assertEquals(listOf("s1"), dao.sessionsNeedingCreation(ACCOUNT).map { it.sessionId })

        repo.markSessionCreated("s1")
        assertFalse("moving it after its files are in a folder is refused", repo.assignCampaign("s1", "c-laundry", false, ACCOUNT))
        assertEquals("c-kitchen", dao.getSession("s1")!!.pipe)
        assertTrue("repeating the same choice is fine", repo.assignCampaign("s1", "c-kitchen", false, ACCOUNT))
        assertFalse(repo.assignCampaign("unknown-session", "c-kitchen", false, ACCOUNT))
    }

    @Test
    fun anotherAccountsRecordingIsNeverUploadedCreatedOrCompletedForTheSignedInOne_realSql() = runBlocking {
        dao.insertSessionIgnore(session("mine", final = true))
        dao.insertSessionIgnore(session("theirs", final = true, account = OTHER).copy(pipe = "c-kitchen"))
        dao.insertSessionIgnore(session("theirs-new", created = false, account = OTHER, final = true).copy(pipe = "c-kitchen"))
        dao.insertSessionIgnore(session("mine-new", created = false, final = true).copy(pipe = "c-kitchen"))
        for (id in listOf("mine", "theirs")) dao.insertUploadIgnore(row(id, "manifest.json", UploadState.PENDING))
        dao.insertUploadIgnore(row("theirs", "device.json", UploadState.VERIFIED))

        assertEquals(listOf("mine"), dao.runnableUploads(ACCOUNT).map { it.sessionId })
        assertEquals(listOf("theirs"), dao.runnableUploads(OTHER).map { it.sessionId })
        assertEquals(listOf("mine-new"), dao.sessionsNeedingCreation(ACCOUNT).map { it.sessionId })
        assertEquals(listOf("theirs-new"), dao.sessionsNeedingCreation(OTHER).map { it.sessionId })

        // Everything of "theirs" verified: only they can complete it.
        dao.updateUpload(dao.findUpload("theirs", "manifest.json")!!.copy(state = UploadState.VERIFIED))
        assertTrue(dao.sessionsReadyToComplete(ACCOUNT).isEmpty())
        assertEquals(listOf("theirs"), dao.sessionsReadyToComplete(OTHER).map { it.sessionId })
    }

    @Test
    fun rowsFromBeforeAccountsAreAdoptedByTheFirstAccountAndOnlyThem_realSql() = runBlocking {
        dao.insertSessionIgnore(session("legacy-1", created = false, final = true, account = null).copy(pipe = "c-kitchen"))
        dao.insertSessionIgnore(session("legacy-2", created = false, final = true, account = null).copy(pipe = "c-kitchen"))
        dao.insertSessionIgnore(session("owned", created = false, final = true, account = OTHER).copy(pipe = "c-kitchen"))
        assertTrue("an unowned row is nobody's to upload", dao.sessionsNeedingCreation(ACCOUNT).isEmpty())

        assertEquals(2, repo.adoptUnowned(ACCOUNT))

        assertEquals(setOf("legacy-1", "legacy-2"), dao.sessionsNeedingCreation(ACCOUNT).map { it.sessionId }.toSet())
        assertEquals(OTHER, dao.getSession("owned")!!.accountId) // someone else's stays theirs
        assertEquals(0, repo.adoptUnowned("a-third-account")) // nothing is left to adopt
    }

    @Test
    fun aRecordingIsOnlyReadyToCompleteOnceItsExportIsMade_realSql() = runBlocking {
        dao.insertSessionIgnore(session("exported", final = true))
        dao.insertSessionIgnore(session("not-exported", final = true, export = ExportState.PENDING))
        dao.insertSessionIgnore(session("off-protocol", final = true, export = ExportState.OFF_PROTOCOL))
        for (id in listOf("exported", "not-exported", "off-protocol")) dao.insertUploadIgnore(row(id, "manifest.json", UploadState.VERIFIED))

        assertEquals(listOf("exported"), dao.sessionsReadyToComplete(ACCOUNT).map { it.sessionId })
    }

    @Test
    fun repositoryEnqueueIsIdempotentAgainstTheRealDatabase() = runBlocking {
        val file = java.io.File.createTempFile("segment-00001", ".rjmj").let { java.io.File(it.parentFile, "poses.csv").also { f -> f.writeText("x") } }
        val queued = QueuedFile("poses.csv", file.absolutePath, UploadSourceKind.PLAIN, 1)

        assertEquals(EnqueueResult.INSERTED, repo.enqueue("s1", file.parentFile!!, queued))
        assertEquals(EnqueueResult.ALREADY_QUEUED, repo.enqueue("s1", file.parentFile!!, queued))

        assertEquals(1, dao.uploadsForSession("s1").size)
        assertEquals(1, dao.allSessions().size)
    }

    @Test
    fun runnableUploadsOnlyReturnsWorkableFilesOfCreatedUnrefusedSessionsOldestFirst() = runBlocking {
        dao.insertSessionIgnore(session("ok"))
        dao.insertSessionIgnore(session("not-created", created = false))
        dao.insertSessionIgnore(session("refused", failed = true))
        dao.insertUploadIgnore(row("ok", "b.json", UploadState.UPLOADING))
        dao.insertUploadIgnore(row("ok", "a.json", UploadState.PENDING))
        dao.insertUploadIgnore(row("ok", "done.json", UploadState.VERIFIED))
        dao.insertUploadIgnore(row("ok", "bad.json", UploadState.FAILED))
        dao.insertUploadIgnore(row("not-created", "a.json"))
        dao.insertUploadIgnore(row("refused", "a.json"))

        val runnable = dao.runnableUploads(ACCOUNT)

        assertEquals(listOf("b.json", "a.json"), runnable.map { it.relativePath }) // creation order
    }

    @Test
    fun sessionsReadyToCompleteNeedsFinalRecordingAndEveryRequiredFileVerified() = runBlocking {
        dao.insertSessionIgnore(session("ready", final = true))
        dao.insertSessionIgnore(session("still-recording", final = false))
        dao.insertSessionIgnore(session("one-pending", final = true))
        dao.insertSessionIgnore(session("already-synced", final = true, synced = 5))
        dao.insertSessionIgnore(session("no-files", final = true))
        dao.insertUploadIgnore(row("ready", "a", UploadState.VERIFIED))
        dao.insertUploadIgnore(row("ready", "export/2026-09-17-09_00_00-a3f9c1-s1/posecam_export.json", UploadState.PENDING, required = false)) // a legacy optional row: ignored
        dao.insertUploadIgnore(row("still-recording", "a", UploadState.VERIFIED))
        dao.insertUploadIgnore(row("one-pending", "a", UploadState.VERIFIED))
        dao.insertUploadIgnore(row("one-pending", "b", UploadState.PENDING))
        dao.insertUploadIgnore(row("already-synced", "a", UploadState.VERIFIED))

        assertEquals(listOf("ready"), dao.sessionsReadyToComplete(ACCOUNT).map { it.sessionId })
    }

    @Test
    fun aggregatesCountOnlyRequiredFilesAndMeasureProgress() = runBlocking {
        dao.insertSessionIgnore(session("s1"))
        dao.insertUploadIgnore(row("s1", "a", UploadState.VERIFIED, size = 100))
        dao.insertUploadIgnore(row("s1", "b", UploadState.UPLOADING, size = 400, uploaded = 150))
        dao.insertUploadIgnore(row("s1", "c", UploadState.UPLOADED, size = 50))
        dao.insertUploadIgnore(row("s1", "d", UploadState.PENDING, size = 50))
        dao.insertUploadIgnore(row("s1", "export/2026-09-17-09_00_00-a3f9c1-s1/posecam_export.json", UploadState.PENDING, size = 9_999, required = false))

        val aggregate = dao.observeAggregates().first().single()

        assertEquals(4, aggregate.totalFiles)
        assertEquals(1, aggregate.verifiedFiles)
        assertEquals(2, aggregate.activeFiles)
        assertEquals(1, aggregate.awaitingVerification)
        assertEquals(600L, aggregate.totalBytes)
        assertEquals(100L + 150L + 50L, aggregate.transferredBytes) // verified + in-flight + uploaded
    }

    @Test
    fun summariesCombineQueueAndSessionRowsIndependentlyOfRecordingStatus() = runBlocking {
        dao.insertSessionIgnore(session("s1", final = true, synced = 9))
        dao.insertUploadIgnore(row("s1", "a", UploadState.VERIFIED))

        val summary = repo.observeSummaries().first().getValue("s1")

        assertEquals(SessionCloudStatus.SYNCED, summary.status)
    }

    @Test
    fun multipartProgressSurvivesAReopenOfTheRow() = runBlocking {
        dao.insertSessionIgnore(session("s1"))
        val id = dao.insertUploadIgnore(row("s1", "poses.csv", UploadState.PENDING, size = 40))
        repo.markPreparing(id); repo.markUploading(id)
        repo.saveMultipart(id, MultipartState("up", 10, 4).withPart(MultipartState.Part(1, "e", "c", 10)))

        val reloaded = dao.getUpload(id)!!

        assertEquals("up", reloaded.multipartUploadId)
        assertEquals(10L, reloaded.uploadedBytes)
        assertEquals(1, MultipartState.decode(reloaded.multipartState)!!.parts.size)
        assertFalse(reloaded.state.canTransitionTo(UploadState.VERIFIED))
        assertNull(dao.getUpload(999))
    }

    private companion object {
        const val ACCOUNT = "acct-1"
        const val OTHER = "acct-2"
    }
}
