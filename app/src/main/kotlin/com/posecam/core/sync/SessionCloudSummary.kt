package com.posecam.core.sync

/**
 * What the UI knows about one session's cloud state. It is independent of the recording
 * status: a session can be "complete" and "uploading", or "recovered-interrupted" and "synced".
 */
data class SessionCloudSummary(
    val sessionId: String,
    /** Cloud status ignoring connectivity; use [displayStatus] for what to show. */
    val status: SessionCloudStatus,
    val totalFiles: Int,
    val verifiedFiles: Int,
    val totalBytes: Long,
    val transferredBytes: Long,
    val lastError: String?,
    /** The campaign id chosen for this recording, or null while it still has to be chosen. */
    val pipe: String? = null,
    /** True once the backend knows the session: from then on its campaign can no longer be changed. */
    val cloudCreated: Boolean = false,
    /** The marketplace account the recording uploads to; null for rows from before accounts were tracked. */
    val accountId: String? = null,
    /** Whether raw data is uploaded too (the campaign's `collectRaw`). */
    val rawRequired: Boolean = true,
    /** What the marketplace made of the recording once synced: "CHECKING", "NOT_COUNTED", ...; null until then. */
    val marketplaceStatus: String? = null,
    /** "CAMPAIGN_FULL" | "CAMPAIGN_CLOSED" | "DUPLICATE" when [marketplaceStatus] is NOT_COUNTED. */
    val marketplaceReason: String? = null,
    /** Where the pipeline export stands; null when the session is unknown or exporting does not apply. */
    val exportState: ExportState? = null,
    /** The exporter's reason when [exportState] says the export is missing. */
    val exportNote: String? = null,
    /** Export files queued / failed for good / not yet verified. They never affect [status]. */
    val exportFiles: Int = 0,
    val exportFilesFailed: Int = 0,
    val exportFilesVerified: Int = 0,
    /** What the backend last said about the recording reaching the website; only meaningful once [isSynced]. */
    val publishState: PublishState = PublishState.PENDING,
    val publishedSets: Int = 0,
    val publishNote: String? = null,
) {
    /** PENDING work that cannot start because the network policy is not satisfied reads "Waiting for Wi-Fi". */
    fun displayStatus(waitingForNetwork: Boolean): SessionCloudStatus =
        if (status == SessionCloudStatus.PENDING && waitingForNetwork) SessionCloudStatus.WAITING_FOR_WIFI else status

    val isSynced: Boolean get() = status == SessionCloudStatus.SYNCED

    /** Synced, but the marketplace did not count it (campaign full or closed, duplicate): the files are kept, nothing is paid. */
    val notCounted: Boolean get() = isSynced && marketplaceStatus == "NOT_COUNTED"

    /**
     * This recording as seen by [currentAccountId]: another account's recording reads [SessionCloudStatus.OTHER_ACCOUNT]
     * (it is neither uploaded nor counted in the banner). Nobody signed in, or an unowned row, changes nothing.
     */
    fun forAccount(currentAccountId: String?): SessionCloudSummary =
        if (currentAccountId != null && accountId != null && accountId != currentAccountId && status != SessionCloudStatus.SYNCED) {
            copy(status = SessionCloudStatus.OTHER_ACCOUNT)
        } else {
            this
        }

    /**
     * Something about this recording failed for good and only a tap will run it again: a file or the cloud session
     * (shown "Upload failed"), or, once synced, its pipeline export, the upload of it, or the backend's publishing.
     */
    val needsRetry: Boolean get() = needsFileRetry || publishFailed

    /** A file, the cloud session or the pipeline export failed: [UploadCoordinator.syncSession] with retryFailedFiles runs them again. */
    val needsFileRetry: Boolean get() = status == SessionCloudStatus.FAILED || exportState == ExportState.FAILED || exportFilesFailed > 0

    /** Everything is uploaded but the backend gave up putting it on the website; only [PublishTracker.retry] asks it to try again. */
    val publishFailed: Boolean get() = isSynced && publishState == PublishState.FAILED

    /** Synced, and the backend made at least one gallery card from it: it can be found on the website. */
    val isLiveOnWebsite: Boolean get() = isSynced && publishState == PublishState.DONE && publishedSets > 0

    /** What the Recordings screen has to say about the export once the raw upload is done; see [CloudUiText.syncedLine]. */
    val exportFilesOpen: Int get() = exportFiles - exportFilesVerified

    companion object {
        fun from(
            aggregate: SessionUploadAggregate?,
            session: CloudSessionEntity?,
            export: SessionExportAggregate? = null,
        ): SessionCloudSummary {
            val id = aggregate?.sessionId ?: requireNotNull(session).sessionId
            val total = aggregate?.totalFiles ?: 0
            val verified = aggregate?.verifiedFiles ?: 0
            val allVerified = total > 0 && verified == total
            val status = when {
                session?.permanentFailure == true || (aggregate?.failedFiles ?: 0) > 0 -> SessionCloudStatus.FAILED
                // Confirmed by the backend, and nothing has been re-queued since.
                session?.syncedAt != null && (total == 0 || allVerified) -> SessionCloudStatus.SYNCED
                // No campaign yet, and not in the cloud: the collector has to choose one before anything is sent.
                session != null && session.pipe == null && !session.cloudCreated && session.syncedAt == null ->
                    SessionCloudStatus.AWAITING_CAMPAIGN
                // Everything that was queued is in S3, but the export (which holds up SYNCED) is not there: say so.
                allVerified && session?.recordingFinal == true && session.exportState != ExportState.DONE ->
                    SessionCloudStatus.EXPORT_BLOCKED
                // Everything is in S3; only the session-level confirmation is outstanding.
                allVerified && session?.recordingFinal == true -> SessionCloudStatus.VERIFYING
                // Verifying means every byte of the recording is in and only the last checks remain. A file being
                // verified while others still wait or transfer is part of uploading: the row must not flip between
                // "Uploading" and "Verifying" once per file.
                aggregate != null && aggregate.activeFiles > 0 ->
                    if (aggregate.awaitingVerification > 0 && aggregate.awaitingVerification == aggregate.totalFiles - aggregate.verifiedFiles) {
                        SessionCloudStatus.VERIFYING
                    } else {
                        SessionCloudStatus.UPLOADING
                    }
                else -> SessionCloudStatus.PENDING
            }
            return SessionCloudSummary(
                sessionId = id,
                status = status,
                totalFiles = total,
                verifiedFiles = verified,
                totalBytes = aggregate?.totalBytes ?: 0,
                transferredBytes = aggregate?.transferredBytes ?: 0,
                lastError = aggregate?.lastError ?: session?.lastError,
                pipe = session?.pipe,
                cloudCreated = session?.cloudCreated ?: false,
                accountId = session?.accountId,
                rawRequired = session?.rawRequired ?: true,
                marketplaceStatus = session?.marketplaceStatus,
                marketplaceReason = session?.marketplaceReason,
                exportState = session?.exportState,
                exportNote = session?.exportNote,
                exportFiles = export?.files ?: 0,
                exportFilesFailed = export?.failedFiles ?: 0,
                exportFilesVerified = export?.verifiedFiles ?: 0,
                publishState = session?.publishState ?: PublishState.PENDING,
                publishedSets = session?.publishedSets ?: 0,
                publishNote = session?.publishNote,
            )
        }
    }
}
