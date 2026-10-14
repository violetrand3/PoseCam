package com.posecam.core.sync

import androidx.room.withTransaction
import com.posecam.core.cloud.CloudLog
import com.posecam.core.cloud.CloudSessionView
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import java.io.File

/** Runs a block atomically. Production uses Room's `withTransaction`; unit tests just run it. */
interface Transactor {
    suspend fun <T> run(block: suspend () -> T): T

    companion object {
        fun forDatabase(database: UploadDatabase): Transactor = object : Transactor {
            override suspend fun <T> run(block: suspend () -> T): T = database.withTransaction { block() }
        }

        val None: Transactor = object : Transactor {
            override suspend fun <T> run(block: suspend () -> T): T = block()
        }
    }
}

enum class EnqueueResult { INSERTED, ALREADY_QUEUED, REQUEUED_CHANGED_FILE }

/** The single owner of upload-queue state transitions. Every operation is idempotent. */
class UploadRepository(
    private val dao: UploadDao,
    private val transactor: Transactor,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Called after a session's rows were dropped, so its staged frame chunks can be deleted too. */
    private val onSessionForgotten: (String) -> Unit = {},
) {
    // ---- enqueue ---------------------------------------------------------------------------

    /**
     * A take started. It belongs to the account that is signed in (or was, last: a recording made while signed out is still
     * its collector's) and is for the campaign that was active when it started; both are fixed here, before anything else
     * can touch the row. [rawRequired] is that campaign's `collectRaw` at this moment.
     */
    suspend fun registerSession(
        sessionId: String,
        directory: File,
        campaignId: String? = null,
        rawRequired: Boolean = true,
        accountId: String? = null,
    ) {
        dao.insertSessionIgnore(newSession(sessionId, directory).copy(pipe = campaignId, rawRequired = rawRequired, accountId = accountId))
    }

    /** Queues one closed file of [sessionId]. Calling it again for the same file is a no-op. */
    suspend fun enqueue(
        sessionId: String,
        directory: File,
        file: QueuedFile,
        /** Queue position (rows run oldest first); null means now, i.e. behind everything queued already. */
        createdAt: Long? = null,
    ): EnqueueResult = transactor.run {
        // A file the backend would reject permanently must never enter the queue.
        require(CloudFileRules.isUploadable(file.relativePath)) { "Not an uploadable session file: ${file.relativePath}" }
        val now = clock()
        dao.insertSessionIgnore(newSession(sessionId, directory))
        val rawRequired = dao.getSession(sessionId)?.rawRequired ?: true
        val row = UploadEntity(
            sessionId = sessionId,
            relativePath = file.relativePath,
            localPath = file.localPath,
            fileType = requireNotNull(CloudFileRules.classify(file.relativePath)),
            kind = file.kind,
            itemCount = file.itemCount,
            required = CloudFileRules.isRequired(file.relativePath, rawRequired),
            sizeBytes = file.sizeBytes,
            sha256 = file.sha256,
            createdAt = createdAt ?: now,
            updatedAt = now,
        )
        if (dao.insertUploadIgnore(row) != NOT_INSERTED) {
            CloudLog.i("file_queued", "session" to sessionId, "path" to file.relativePath, "bytes" to file.sizeBytes)
            return@run EnqueueResult.INSERTED
        }
        val existing = requireNotNull(dao.findUpload(sessionId, file.relativePath))
        // A chunk's queued size is an estimate that becomes the zip's real size once it is built, so
        // sizes can never be compared for it; the number of JPEGs it holds says whether its content changed.
        val unchanged = if (existing.kind == UploadSourceKind.FRAME_CHUNK) {
            existing.itemCount == file.itemCount
        } else {
            existing.sizeBytes == file.sizeBytes
        }
        if (unchanged) return@run EnqueueResult.ALREADY_QUEUED
        // The same file now has different content: the copy we queued (or even verified) is stale.
        // Start it over rather than mixing two versions of its bytes.
        rebaseline(existing, file.sizeBytes, itemCount = file.itemCount, newSha256 = file.sha256)
        EnqueueResult.REQUEUED_CHANGED_FILE
    }

    /**
     * The recording is finalized (or was recovered): queue every file that exists and mark the
     * session final in one transaction, so "recordingFinal" can never be observed before the
     * complete file set is in the queue.
     */
    suspend fun finalizeSession(
        sessionId: String,
        directory: File,
        recordingStatus: String,
        files: List<QueuedFile>,
        /**
         * False for a redo take or an incomplete recording: it is never uploaded, so it keeps no campaign (one it was
         * started with is dropped) and nothing is queued for it. Only possible before the session exists in the cloud.
         */
        eligible: Boolean = true,
    ) {
        transactor.run {
            dao.insertSessionIgnore(newSession(sessionId, directory))
            files.forEach { enqueue(sessionId, directory, it) }
            val session = requireNotNull(dao.getSession(sessionId))
            val dropCampaign = !eligible && !session.cloudCreated && session.pipe != null
            if (!session.recordingFinal || session.recordingStatus != recordingStatus || dropCampaign) {
                dao.updateSession(
                    session.copy(
                        recordingFinal = true, recordingStatus = recordingStatus, updatedAt = clock(),
                        pipe = if (dropCampaign) null else session.pipe,
                    ),
                )
            }
        }
    }

    /** Queues more closed files of a finalized session (idempotent, like [enqueue]). */
    suspend fun addFiles(sessionId: String, directory: File, files: List<QueuedFile>) {
        transactor.run { files.forEach { enqueue(sessionId, directory, it) } }
    }

    /**
     * The user asked for this session to upload now: move its pending files to the front of the queue. The
     * file already in flight finishes first; the next one picked is this session's.
     */
    suspend fun prioritizeSession(sessionId: String) {
        transactor.run {
            val front = (dao.minRunnableCreatedAt() ?: clock()) - 1
            dao.setRunnableCreatedAt(sessionId, front)
        }
    }

    /**
     * Files [sessionId] under the campaign [campaignId] ([collectRaw]: that campaign's `collectRaw`), for [accountId] when the
     * row has no account yet. Only possible until the session exists in the cloud: after that its files already live in a
     * campaign's folder, and moving them would mean different copies in two. Returns whether the choice was recorded.
     *
     * Choosing (or changing) the campaign starts the queue of this recording over: what the backend held under another
     * campaign, or under the old backend, says nothing about this one, so every file is sent again; raw files the campaign
     * does not collect are dropped from the queue (the caller then queues the ones that are missing).
     */
    suspend fun assignCampaign(sessionId: String, campaignId: String, collectRaw: Boolean, accountId: String?): Boolean = transactor.run {
        val session = dao.getSession(sessionId) ?: return@run false
        if (session.cloudCreated) return@run session.pipe == campaignId
        if (accountId != null && session.accountId != null && session.accountId != accountId) return@run false
        val owner = session.accountId ?: accountId
        if (session.pipe == campaignId && session.rawRequired == collectRaw) {
            if (owner != session.accountId) dao.updateSession(session.copy(accountId = owner, updatedAt = clock()))
            return@run true
        }
        resetQueueFor(sessionId, collectRaw)
        // A new campaign is a new chance: a refusal for the old one (not enrolled, closed, gone) no longer applies.
        dao.updateSession(
            session.copy(
                pipe = campaignId, rawRequired = collectRaw, accountId = owner, permanentFailure = false, lastError = null,
                updatedAt = clock(),
            ),
        )
        CloudLog.i("campaign_chosen", "session" to sessionId, "campaign" to campaignId, "raw" to collectRaw)
        true
    }

    /**
     * The backend said it wants (or does not want) the raw data, against what the cached campaign said. Only changes
     * what is *expected*: the caller queues the missing raw files. Rows of raw files are dropped when no longer wanted.
     */
    suspend fun setRawRequired(sessionId: String, rawRequired: Boolean) {
        transactor.run {
            val session = dao.getSession(sessionId) ?: return@run
            if (session.rawRequired == rawRequired) return@run
            dao.uploadsForSession(sessionId).forEach { row ->
                val required = CloudFileRules.isRequired(row.relativePath, rawRequired)
                if (row.kind != UploadSourceKind.EXPORT && !required) {
                    dao.deleteUpload(row.id)
                    if (row.kind == UploadSourceKind.FRAME_CHUNK) runCatching { File(row.localPath).delete() }
                } else if (row.required != required) {
                    dao.updateUpload(row.copy(required = required, updatedAt = clock()))
                }
            }
            dao.updateSession(session.copy(rawRequired = rawRequired, syncedAt = null, updatedAt = clock()))
            CloudLog.i("raw_required_changed", "session" to sessionId, "raw" to rawRequired)
        }
    }

    private suspend fun resetQueueFor(sessionId: String, collectRaw: Boolean) {
        dao.uploadsForSession(sessionId).forEach { row ->
            val required = CloudFileRules.isRequired(row.relativePath, collectRaw)
            if (row.kind != UploadSourceKind.EXPORT && !required) {
                dao.deleteUpload(row.id)
                if (row.kind == UploadSourceKind.FRAME_CHUNK) runCatching { File(row.localPath).delete() }
            } else {
                dao.updateUpload(
                    row.copy(
                        required = required,
                        state = UploadState.PENDING,
                        multipartUploadId = null,
                        multipartState = null,
                        uploadedBytes = 0,
                        verifyFailures = 0,
                        verifiedAt = null,
                        uploadedAt = null,
                        lastError = null,
                        updatedAt = clock(),
                    ),
                )
            }
        }
    }

    /** [accountId] signed in: the rows from before accounts were tracked become theirs. Returns how many. */
    suspend fun adoptUnowned(accountId: String): Int = dao.adoptUnowned(accountId)

    // ---- pipeline export -------------------------------------------------------------------

    suspend fun sessionsNeedingExport(accountId: String? = null): List<CloudSessionEntity> = dao.sessionsNeedingExport(accountId)

    /**
     * The export of [sessionId] was written to staging: queue its files and remember it is done, in one transaction, so a
     * crash can never leave "done" without rows (which would lose the export) or rows without "done" (which would redo it).
     * The files go to the FRONT of the queue: they are about 50 MB against gigabytes of raw data, and they are what
     * LabelNow uses, so a labeler's recording becomes labelable long before the raw upload ends.
     */
    suspend fun completeExport(sessionId: String, files: List<QueuedFile>) {
        transactor.run {
            val session = dao.getSession(sessionId) ?: return@run
            val front = (dao.minRunnableCreatedAt() ?: clock()) - files.size - 1
            files.forEachIndexed { index, file ->
                enqueue(sessionId, File(session.directoryPath), file, createdAt = front + index)
            }
            dao.updateSession(session.copy(exportState = ExportState.DONE, exportNote = null, updatedAt = clock()))
            CloudLog.i("export_queued", "session" to sessionId, "files" to files.size)
        }
    }

    /** The export is not coming ([state] is one of the lasting or failed outcomes); [note] says why, in plain words. */
    suspend fun markExportMissing(sessionId: String, state: ExportState, note: String) {
        require(state != ExportState.PENDING && state != ExportState.DONE)
        mutateSession(sessionId) { it.copy(exportState = state, exportNote = note.take(MAX_ERROR_CHARS)) }
        CloudLog.w("export_missing", "session" to sessionId, "state" to state.name, "note" to note)
    }

    /**
     * Makes the export again: a crashed one (user retry), or one whose staged files vanished before they were uploaded.
     * Rows of an earlier export are dropped; files already verified in the cloud are simply sent again if the new bytes differ.
     */
    suspend fun resetExport(sessionId: String) {
        transactor.run {
            val exportRows = dao.uploadsForSession(sessionId).filter { it.kind == UploadSourceKind.EXPORT }
            exportRows.forEach { dao.deleteUpload(it.id) }
            val session = dao.getSession(sessionId) ?: return@run
            dao.updateSession(session.copy(exportState = ExportState.PENDING, exportNote = null, updatedAt = clock()))
        }
    }

    /** Startup: a worker killed mid-prepare left PREPARING rows; they are plainly runnable again. */
    suspend fun normalizeInterrupted(): Int = dao.resetPreparing(clock())

    /** Drops the queue and session rows of a session whose directory is gone. */
    suspend fun forgetSession(sessionId: String) {
        transactor.run {
            dao.deleteUploadsForSession(sessionId)
            dao.deleteSession(sessionId)
        }
        runCatching { onSessionForgotten(sessionId) }
    }

    // ---- queue access ----------------------------------------------------------------------

    /** What [accountId] can upload now. Another account's files are never in it. */
    suspend fun runnable(accountId: String): List<UploadEntity> = dao.runnableUploads(accountId)

    suspend fun get(id: Long): UploadEntity? = dao.getUpload(id)

    suspend fun uploadsForSession(sessionId: String): List<UploadEntity> = dao.uploadsForSession(sessionId)

    suspend fun session(sessionId: String): CloudSessionEntity? = dao.getSession(sessionId)

    suspend fun allSessions(): List<CloudSessionEntity> = dao.allSessions()

    // ---- state transitions -----------------------------------------------------------------

    suspend fun markPreparing(id: Long) = transition(id, UploadState.PREPARING)

    suspend fun saveSha256(id: Long, sha256: String) = mutate(id) { it.copy(sha256 = sha256) }

    suspend fun markUploading(id: Long) = transition(id, UploadState.UPLOADING)

    /** Persists multipart progress; called per part, so a resume skips the finished parts. */
    suspend fun saveMultipart(id: Long, state: MultipartState) = mutate(id) {
        it.copy(multipartUploadId = state.uploadId, multipartState = state.encode(), uploadedBytes = state.uploadedBytes)
    }

    /** In-flight bytes of a single (non-multipart) PUT, so progress moves during a large upload. */
    suspend fun saveProgress(id: Long, uploadedBytes: Long) = mutate(id) {
        // Never regress, and never claim more than the file: this is display progress only.
        it.copy(uploadedBytes = maxOf(it.uploadedBytes, uploadedBytes.coerceAtMost(it.sizeBytes)))
    }

    suspend fun markUploaded(id: Long) = transition(id, UploadState.UPLOADED) {
        it.copy(uploadedBytes = it.sizeBytes, uploadedAt = clock(), lastError = null)
    }

    suspend fun markVerified(id: Long) = transition(id, UploadState.VERIFIED) {
        it.copy(
            uploadedBytes = it.sizeBytes,
            verifiedAt = clock(),
            uploadedAt = it.uploadedAt ?: clock(),
            lastError = null,
            multipartState = null,
        )
    }

    /** A temporary failure: remember why, keep state (and multipart progress), stay retryable. */
    suspend fun recordRetry(id: Long, error: String) = mutate(id) {
        it.copy(retryCount = it.retryCount + 1, lastError = error.take(MAX_ERROR_CHARS))
    }

    suspend fun markFailed(id: Long, error: String) = transition(id, UploadState.FAILED) {
        it.copy(lastError = error.take(MAX_ERROR_CHARS))
    }

    /** Throws away multipart progress and sends the file again from byte zero. */
    suspend fun resetToPending(id: Long, reason: String, countVerifyFailure: Boolean = false) =
        transition(id, UploadState.PENDING) {
            it.copy(
                multipartUploadId = null,
                multipartState = null,
                uploadedBytes = 0,
                lastError = reason.take(MAX_ERROR_CHARS),
                verifyFailures = it.verifyFailures + if (countVerifyFailure) 1 else 0,
            )
        }

    /**
     * A frame chunk was just built (or found staged): record its real size. Unlike [rebaseline] this does NOT
     * reset the row's state, its hash, or the session's synced mark: the content did not change, only our
     * estimate of its size became exact.
     */
    suspend fun saveMaterialized(id: Long, sizeBytes: Long) = mutate(id) { it.copy(sizeBytes = sizeBytes) }

    /** The local file changed after it was queued; adopt its real size and begin again. */
    suspend fun rebaseline(id: Long, newSizeBytes: Long) {
        val row = dao.getUpload(id) ?: return
        rebaseline(row, newSizeBytes)
    }

    private suspend fun rebaseline(row: UploadEntity, newSizeBytes: Long, itemCount: Int = row.itemCount, newSha256: String? = null) {
        CloudLog.w(
            "file_changed",
            "session" to row.sessionId, "path" to row.relativePath, "was" to row.sizeBytes, "now" to newSizeBytes,
        )
        // A staged chunk is stale too: drop it so it is rebuilt from the JPEGs that exist now.
        if (row.kind == UploadSourceKind.FRAME_CHUNK) runCatching { File(row.localPath).delete() }
        transactor.run {
            dao.updateUpload(
                row.copy(
                    sizeBytes = newSizeBytes,
                    itemCount = itemCount,
                    sha256 = newSha256,
                    state = UploadState.PENDING,
                    multipartUploadId = null,
                    multipartState = null,
                    uploadedBytes = 0,
                    verifiedAt = null,
                    uploadedAt = null,
                    lastError = null,
                    updatedAt = clock(),
                ),
            )
            // New content means an earlier "synced" verdict no longer holds.
            dao.getSession(row.sessionId)?.takeIf { it.syncedAt != null }
                ?.let { dao.updateSession(it.copy(syncedAt = null, updatedAt = clock())) }
        }
    }

    /**
     * User action: put permanently failed files (of one session, or all) back in the queue. With [accountId] only that
     * account's recordings are touched: another account's rows stay exactly as they are.
     */
    suspend fun retryFailed(sessionId: String? = null, accountId: String? = null): Int = transactor.run {
        val mine = dao.allSessions().filter { accountId == null || it.accountId == accountId }.map { it.sessionId }.toSet()
        val failed = dao.failedUploads(sessionId).filter { accountId == null || it.sessionId in mine }
        failed.forEach { resetToPending(it.id, "") }
        // A session the backend refused outright gets another chance as well.
        dao.allSessions()
            .filter { it.permanentFailure && it.sessionId in mine && (sessionId == null || it.sessionId == sessionId) }
            .forEach { dao.updateSession(it.copy(permanentFailure = false, lastError = null, updatedAt = clock())) }
        // So does an export that crashed (storage, encoder): only an explicit retry runs it again.
        dao.allSessions()
            .filter { it.exportState == ExportState.FAILED && it.sessionId in mine && (sessionId == null || it.sessionId == sessionId) }
            .forEach { dao.updateSession(it.copy(exportState = ExportState.PENDING, exportNote = null, updatedAt = clock())) }
        failed.size
    }

    // ---- sessions --------------------------------------------------------------------------

    suspend fun sessionsNeedingCreation(accountId: String): List<CloudSessionEntity> = dao.sessionsNeedingCreation(accountId)

    suspend fun markSessionCreated(sessionId: String) = mutateSession(sessionId) {
        it.copy(cloudCreated = true, lastError = null)
    }

    /** The backend lost track of the session (404): create it again before more uploads. */
    suspend fun markSessionNotCreated(sessionId: String) = mutateSession(sessionId) { it.copy(cloudCreated = false) }

    suspend fun markSessionFailed(sessionId: String, error: String) = mutateSession(sessionId) {
        it.copy(permanentFailure = true, lastError = error.take(MAX_ERROR_CHARS))
    }

    suspend fun noteSessionError(sessionId: String, error: String) = mutateSession(sessionId) {
        it.copy(lastError = error.take(MAX_ERROR_CHARS))
    }

    suspend fun sessionsReadyToComplete(accountId: String): List<CloudSessionEntity> = dao.sessionsReadyToComplete(accountId)

    /**
     * The backend confirmed the session SYNCED. [view] is its answer: `marketplace` says whether the recording was counted
     * (a NOT_COUNTED one is still synced: the files are kept, so the phone may free its copy). A backend that sends no
     * `publishStatus` -- the marketplace's, always -- does not report publishing, so nothing waits for it.
     */
    suspend fun markSessionSynced(sessionId: String, view: CloudSessionView? = null) = mutateSession(sessionId) {
        // Synced again means the backend may publish again, so an earlier website verdict no longer holds.
        it.copy(
            syncedAt = clock(), lastError = null,
            publishState = if (view != null && view.publishStatus == null) PublishState.UNSUPPORTED else PublishState.PENDING,
            publishedSets = 0, publishNote = null,
            marketplaceStatus = view?.marketplace?.status, marketplaceReason = view?.marketplace?.reason,
        )
    }

    /** What the backend last said about this recording reaching the website (see [PublishTracker]). */
    suspend fun recordPublish(sessionId: String, state: PublishState, sets: Int, note: String?) = mutateSession(sessionId) {
        it.copy(publishState = state, publishedSets = sets, publishNote = note?.take(MAX_ERROR_CHARS))
    }

    // ---- observation -----------------------------------------------------------------------

    /** Live per-session cloud progress for the UI. */
    fun observeSummaries(): Flow<Map<String, SessionCloudSummary>> =
        combine(dao.observeAggregates(), dao.observeSessions(), dao.observeExportAggregates()) { aggregates, sessions, exports ->
            val bySession = sessions.associateBy { it.sessionId }
            val byAggregate = aggregates.associateBy { it.sessionId }
            val byExport = exports.associateBy { it.sessionId }
            (bySession.keys + byAggregate.keys).associateWith { id ->
                SessionCloudSummary.from(byAggregate[id], bySession[id], byExport[id])
            }
        }.distinctUntilChanged()

    // ---- internals -------------------------------------------------------------------------

    private suspend fun transition(id: Long, next: UploadState, edit: (UploadEntity) -> UploadEntity = { it }) {
        transactor.run {
            val row = dao.getUpload(id) ?: return@run
            check(row.state.canTransitionTo(next)) {
                "Illegal upload transition ${row.state} -> $next for ${row.relativePath}"
            }
            dao.updateUpload(edit(row).copy(state = next, updatedAt = clock()))
        }
    }

    private suspend fun mutate(id: Long, edit: (UploadEntity) -> UploadEntity) {
        transactor.run {
            val row = dao.getUpload(id) ?: return@run
            dao.updateUpload(edit(row).copy(updatedAt = clock()))
        }
    }

    private suspend fun mutateSession(sessionId: String, edit: (CloudSessionEntity) -> CloudSessionEntity) {
        transactor.run {
            val row = dao.getSession(sessionId) ?: return@run
            dao.updateSession(edit(row).copy(updatedAt = clock()))
        }
    }

    private fun newSession(sessionId: String, directory: File): CloudSessionEntity {
        val now = clock()
        return CloudSessionEntity(sessionId = sessionId, directoryPath = directory.absolutePath, createdAt = now, updatedAt = now)
    }

    private companion object {
        const val NOT_INSERTED = -1L
        const val MAX_ERROR_CHARS = 500
    }
}
