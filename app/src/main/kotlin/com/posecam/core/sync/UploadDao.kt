package com.posecam.core.sync

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Data access for the upload queue and cloud-session rows. It is an interface so the
 * repository and upload processor can be unit-tested against an in-memory fake.
 */
@Dao
interface UploadDao {
    // ---- uploads ---------------------------------------------------------------------------

    /** Returns the new row id, or -1 when (sessionId, relativePath) is already queued. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertUploadIgnore(upload: UploadEntity): Long

    @Update
    suspend fun updateUpload(upload: UploadEntity)

    @Query("SELECT * FROM uploads WHERE id = :id")
    suspend fun getUpload(id: Long): UploadEntity?

    @Query("SELECT * FROM uploads WHERE sessionId = :sessionId AND relativePath = :relativePath")
    suspend fun findUpload(sessionId: String, relativePath: String): UploadEntity?

    @Query("SELECT * FROM uploads WHERE sessionId = :sessionId ORDER BY relativePath")
    suspend fun uploadsForSession(sessionId: String): List<UploadEntity>

    /**
     * Files a worker can still make progress on, oldest first. Only files of sessions the
     * backend knows about (and has not refused) are returned: a file can't be uploaded into a
     * session that does not exist remotely. And only files of [accountId]'s sessions: another account's recordings are
     * never uploaded by whoever happens to be signed in.
     */
    @Query(
        """
        SELECT u.* FROM uploads u
        JOIN cloud_sessions s ON s.sessionId = u.sessionId
        WHERE u.state IN ('PENDING', 'PREPARING', 'UPLOADING', 'UPLOADED')
          AND s.cloudCreated = 1 AND s.permanentFailure = 0 AND s.accountId = :accountId
        ORDER BY u.createdAt, u.id
        """,
    )
    suspend fun runnableUploads(accountId: String): List<UploadEntity>

    @Query("SELECT MIN(createdAt) FROM uploads WHERE state IN ('PENDING', 'PREPARING', 'UPLOADING', 'UPLOADED')")
    suspend fun minRunnableCreatedAt(): Long?

    /** Queue order is (createdAt, id); lowering it moves a session's pending files to the front. */
    @Query("UPDATE uploads SET createdAt = :createdAt WHERE sessionId = :sessionId AND state IN ('PENDING', 'PREPARING', 'UPLOADING', 'UPLOADED')")
    suspend fun setRunnableCreatedAt(sessionId: String, createdAt: Long)

    @Query("SELECT * FROM uploads WHERE state = 'FAILED' AND (:sessionId IS NULL OR sessionId = :sessionId)")
    suspend fun failedUploads(sessionId: String?): List<UploadEntity>

    @Query("UPDATE uploads SET state = 'PENDING', updatedAt = :now WHERE state = 'PREPARING'")
    suspend fun resetPreparing(now: Long): Int

    @Query("DELETE FROM uploads WHERE id = :id")
    suspend fun deleteUpload(id: Long)

    @Query("DELETE FROM uploads WHERE sessionId = :sessionId")
    suspend fun deleteUploadsForSession(sessionId: String)

    @Query(
        """
        SELECT sessionId,
               COUNT(*) AS totalFiles,
               SUM(CASE WHEN state = 'VERIFIED' THEN 1 ELSE 0 END) AS verifiedFiles,
               SUM(CASE WHEN state = 'FAILED' THEN 1 ELSE 0 END) AS failedFiles,
               SUM(CASE WHEN state IN ('PREPARING', 'UPLOADING', 'UPLOADED') THEN 1 ELSE 0 END) AS activeFiles,
               SUM(CASE WHEN state = 'UPLOADED' THEN 1 ELSE 0 END) AS awaitingVerification,
               SUM(sizeBytes) AS totalBytes,
               SUM(CASE WHEN state IN ('VERIFIED', 'UPLOADED') THEN sizeBytes ELSE uploadedBytes END) AS transferredBytes,
               MAX(lastError) AS lastError
        FROM uploads
        WHERE required = 1
        GROUP BY sessionId
        """,
    )
    fun observeAggregates(): Flow<List<SessionUploadAggregate>>

    @Query(
        """
        SELECT sessionId,
               COUNT(*) AS files,
               SUM(CASE WHEN state = 'VERIFIED' THEN 1 ELSE 0 END) AS verifiedFiles,
               SUM(CASE WHEN state = 'FAILED' THEN 1 ELSE 0 END) AS failedFiles
        FROM uploads
        WHERE kind = 'EXPORT'
        GROUP BY sessionId
        """,
    )
    fun observeExportAggregates(): Flow<List<SessionExportAggregate>>

    // ---- cloud sessions --------------------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSessionIgnore(session: CloudSessionEntity): Long

    @Update
    suspend fun updateSession(session: CloudSessionEntity)

    @Query("SELECT * FROM cloud_sessions WHERE sessionId = :sessionId")
    suspend fun getSession(sessionId: String): CloudSessionEntity?

    @Query("SELECT * FROM cloud_sessions")
    suspend fun allSessions(): List<CloudSessionEntity>

    @Query("SELECT * FROM cloud_sessions")
    fun observeSessions(): Flow<List<CloudSessionEntity>>

    /**
     * Sessions the backend does not know yet. Only once the recording is finished (a take in progress already has its
     * campaign, but must not be offered to the backend before it is judged), the campaign is known, and only [accountId]'s.
     */
    @Query(
        """
        SELECT * FROM cloud_sessions
        WHERE cloudCreated = 0 AND permanentFailure = 0 AND recordingFinal = 1 AND pipe IS NOT NULL AND accountId = :accountId
        """,
    )
    suspend fun sessionsNeedingCreation(accountId: String): List<CloudSessionEntity>

    /** Rows made before accounts were tracked belong to the first account that signs in afterwards. Returns how many. */
    @Query("UPDATE cloud_sessions SET accountId = :accountId WHERE accountId IS NULL")
    suspend fun adoptUnowned(accountId: String): Int

    /**
     * [accountId]'s finalized sessions whose required files are all verified but not yet confirmed SYNCED. The pipeline
     * export is one of the required files, so a recording whose export is missing (off protocol, not made yet) is never here.
     */
    @Query(
        """
        SELECT s.* FROM cloud_sessions s
        WHERE s.recordingFinal = 1 AND s.cloudCreated = 1 AND s.permanentFailure = 0 AND s.syncedAt IS NULL
          AND s.accountId = :accountId AND s.exportState = 'DONE'
          AND EXISTS (SELECT 1 FROM uploads u WHERE u.sessionId = s.sessionId AND u.required = 1)
          AND NOT EXISTS (
              SELECT 1 FROM uploads u
              WHERE u.sessionId = s.sessionId AND u.required = 1 AND u.state != 'VERIFIED'
          )
        """,
    )
    suspend fun sessionsReadyToComplete(accountId: String): List<CloudSessionEntity>

    /**
     * Finalized recordings that have a campaign and whose pipeline export is still to be made. A recording without a
     * campaign is left alone (nothing about it is sent or produced), and so is one the backend refused. [accountId] null
     * means every account's: the export is local work, nothing is sent.
     */
    @Query(
        """
        SELECT * FROM cloud_sessions
        WHERE exportState = 'PENDING' AND recordingFinal = 1 AND permanentFailure = 0 AND pipe IS NOT NULL
          AND (:accountId IS NULL OR accountId = :accountId)
        """,
    )
    suspend fun sessionsNeedingExport(accountId: String?): List<CloudSessionEntity>

    @Query("DELETE FROM cloud_sessions WHERE sessionId = :sessionId")
    suspend fun deleteSession(sessionId: String)
}
