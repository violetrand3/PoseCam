package com.posecam.core.sync

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
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
import java.io.File

/** Version 4 (marketplace accounts and campaigns) must keep a queue that was built by the LabelNow app. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UploadDatabaseMigrationTest {
    private lateinit var context: Context
    private val name = "migration-3-4-test.db"

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(name)
    }

    @After fun tearDown() { context.deleteDatabase(name) }

    /** Creates the database exactly as version 3 shipped, from its exported schema. */
    private fun createVersion3(populate: SupportSQLiteDatabase.() -> Unit) {
        val v3 = JSONObject(File("schemas/com.posecam.core.sync.UploadDatabase/3.json").readText()).getJSONObject("database")
        val statements = buildList {
            val entities = v3.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                add(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices")
                for (j in 0 until (indices?.length() ?: 0)) add(indices!!.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
            }
            val setup = v3.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) add(setup.getString(i))
        }
        val old = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name).callback(object : SupportSQLiteOpenHelper.Callback(3) {
                override fun onCreate(db: SupportSQLiteDatabase) = statements.forEach { db.execSQL(it) }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }).build(),
        )
        old.writableDatabase.populate()
        old.close()
    }

    private fun SupportSQLiteDatabase.session(id: String, pipe: String?, created: Int, failed: Int, syncedAt: Long?, publish: String, error: String?) = execSQL(
        "INSERT INTO cloud_sessions (sessionId, directoryPath, recordingStatus, recordingFinal, cloudCreated, permanentFailure, syncedAt, lastError, " +
            "createdAt, updatedAt, pipe, exportState, publishState, publishedSets) VALUES (?, '/x', 'complete', 1, ?, ?, ?, ?, 1, 1, ?, 'DONE', ?, 1)",
        arrayOf(id, created, failed, syncedAt, error, pipe, publish),
    )

    private fun SupportSQLiteDatabase.upload(id: String, path: String, state: String) = execSQL(
        "INSERT INTO uploads (sessionId, relativePath, localPath, fileType, kind, itemCount, required, sizeBytes, state, uploadedBytes, retryCount, verifyFailures, createdAt, updatedAt) " +
            "VALUES (?, ?, '/x/f', 'METADATA', 'PLAIN', 0, 1, 10, ?, 10, 0, 0, 1, 1)",
        arrayOf(id, path, state),
    )

    private fun open() = Room.databaseBuilder(context, UploadDatabase::class.java, name)
        .addMigrations(UploadDatabase.MIGRATION_1_2, UploadDatabase.MIGRATION_2_3, UploadDatabase.MIGRATION_3_4)
        .allowMainThreadQueries().build()

    @Test fun theQueueSurvivesAndTheNewColumnsStartAtTheirDefaults() = runBlocking {
        createVersion3 {
            session("synced", "black", created = 1, failed = 0, syncedAt = 5_000, publish = "DONE", error = null)
            session("unfinished", "white", created = 1, failed = 1, syncedAt = null, publish = "PENDING", error = "HTTP 400 INVALID_REQUEST: pipe must be one of ...")
            session("unfiled", null, created = 0, failed = 0, syncedAt = null, publish = "PENDING", error = null)
            upload("synced", "manifest.json", "VERIFIED")
            upload("unfinished", "manifest.json", "VERIFIED")
            upload("unfinished", "poses.csv", "UPLOADING")
        }

        val db = open()
        try {
            val dao = db.uploadDao()
            for (id in listOf("synced", "unfinished", "unfiled")) {
                val session = dao.getSession(id)!!
                assertNull("no account yet: the first account to sign in adopts it ($id)", session.accountId)
                assertTrue("it uploaded everything before ($id)", session.rawRequired)
                assertNull(session.marketplaceStatus)
                assertNull(session.marketplaceReason)
                // The LabelNow backend's publishing does not exist any more: nothing waits for it.
                assertEquals(PublishState.UNSUPPORTED, session.publishState)
            }
            // Nothing is lost: every queued file is still there.
            assertEquals(1, dao.uploadsForSession("synced").size)
            assertEquals(2, dao.uploadsForSession("unfinished").size)
        } finally {
            db.close()
        }
    }

    @Test fun anUnfinishedRecordingFiledUnderAPipeNamesNoCampaignAndIsNotInTheCloud() = runBlocking {
        createVersion3 {
            session("unfinished", "white", created = 1, failed = 1, syncedAt = null, publish = "PENDING", error = "boom")
            upload("unfinished", "manifest.json", "VERIFIED")
        }

        val db = open()
        try {
            val session = db.uploadDao().getSession("unfinished")!!
            assertNull(session.pipe) // "Choose a campaign to upload"
            assertFalse(session.cloudCreated) // the new backend has never heard of it
            assertFalse(session.permanentFailure)
            assertNull(session.lastError)
            assertTrue(db.uploadDao().sessionsNeedingCreation("acct").isEmpty())
            assertTrue(db.uploadDao().runnableUploads("acct").isEmpty())
        } finally {
            db.close()
        }
    }

    @Test fun aRecordingAlreadySyncedKeepsItsStateSoItCanStillBeFreedFromThePhone() = runBlocking {
        createVersion3 { session("synced", "black", created = 1, failed = 0, syncedAt = 5_000, publish = "DONE", error = null) }

        val db = open()
        try {
            val session = db.uploadDao().getSession("synced")!!
            assertEquals(5_000L, session.syncedAt)
            assertTrue(session.cloudCreated)
            assertEquals("black", session.pipe) // history, not a campaign; nothing is uploaded for a synced row
            assertEquals(ExportState.DONE, session.exportState)
        } finally {
            db.close()
        }
    }

    @Test fun anAdoptedLegacyRecordingBecomesTheAccountsOnceItChoosesACampaign() = runBlocking {
        createVersion3 {
            session("unfinished", "white", created = 1, failed = 0, syncedAt = null, publish = "PENDING", error = null)
            upload("unfinished", "manifest.json", "VERIFIED")
            upload("unfinished", "poses.csv", "UPLOADING")
        }

        val db = open()
        try {
            val repo = UploadRepository(db.uploadDao(), Transactor.forDatabase(db))
            assertEquals(1, repo.adoptUnowned("acct"))
            assertTrue(repo.assignCampaign("unfinished", "c-kitchen", collectRaw = false, accountId = "acct"))

            // What the old backend verified says nothing about the new one: the queue starts over, without the raw file.
            val rows = db.uploadDao().uploadsForSession("unfinished")
            assertEquals(listOf("manifest.json"), rows.map { it.relativePath })
            assertEquals(UploadState.PENDING, rows.single().state)
            assertEquals(listOf("unfinished"), db.uploadDao().sessionsNeedingCreation("acct").map { it.sessionId })
        } finally {
            db.close()
        }
    }
}
