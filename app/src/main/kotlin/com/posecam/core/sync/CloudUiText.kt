package com.posecam.core.sync

/** Every user-facing string of the Recordings screen's cloud features, as pure functions so they are unit-tested. */
object CloudUiText {
    /** The second line of a recording's row. */
    fun rowStatus(
        summary: SessionCloudSummary?,
        policy: SyncPolicy,
        waitingForNetwork: Boolean,
        /** False until the collector has chosen the export rotation: exports wait for it. */
        exportRotationSet: Boolean = true,
        /** The title of the campaign the recording was filed under; shown on every row that has been filed. */
        campaignTitle: String? = null,
        /**
         * Why this recording is never uploaded -- "redo take" or "recording incomplete" -- or null if it can be. Decided
         * from the recording's own manifest, so it reads the same for old recordings as for the one just made.
         */
        notUploadedReason: String? = null,
    ): String {
        val status = summary?.displayStatus(waitingForNetwork)
        val neverUploaded = notUploadedReason != null &&
            (status == null || status == SessionCloudStatus.LOCAL_ONLY || status == SessionCloudStatus.AWAITING_CAMPAIGN)
        if (neverUploaded) return "Not uploaded: $notUploadedReason"
        if (summary == null || status == null) return "On this phone only"
        val line = statusLine(status, summary, policy, exportRotationSet)
        // Not yet filed (local only, or still waiting for the collector to choose), or filed by someone else: nothing to name.
        val filed = status != SessionCloudStatus.LOCAL_ONLY && status != SessionCloudStatus.AWAITING_CAMPAIGN &&
            status != SessionCloudStatus.OTHER_ACCOUNT
        return if (filed && !campaignTitle.isNullOrBlank()) "$campaignTitle · $line" else line
    }

    private fun statusLine(status: SessionCloudStatus, summary: SessionCloudSummary, policy: SyncPolicy, exportRotationSet: Boolean): String {
        return when (status) {
            SessionCloudStatus.LOCAL_ONLY -> "On this phone only"
            SessionCloudStatus.AWAITING_CAMPAIGN -> "Choose a campaign to upload"
            SessionCloudStatus.OTHER_ACCOUNT -> "Belongs to another account"
            SessionCloudStatus.EXPORT_BLOCKED -> exportBlockedLine(summary, exportRotationSet)
            SessionCloudStatus.WAITING_FOR_WIFI -> "Waiting for Wi-Fi"
            SessionCloudStatus.PENDING ->
                if (policy == SyncPolicy.MANUAL_ONLY) "Not uploaded (manual mode)" else "Queued" + retryNote(summary.lastError)
            SessionCloudStatus.UPLOADING -> CloudCardAction.infoMessage(status, summary, recordingInProgress = false)
            SessionCloudStatus.VERIFYING -> "Verifying"
            SessionCloudStatus.SYNCED -> syncedLine(summary, exportRotationSet)
            SessionCloudStatus.FAILED -> "Upload failed"
        }
    }

    /**
     * Everything else is uploaded but the video export, which the marketplace needs, is not: never "Synced", and always
     * the reason. A recording that can never be exported says so in words a collector can act on.
     */
    private fun exportBlockedLine(summary: SessionCloudSummary, exportRotationSet: Boolean): String = when (summary.exportState) {
        ExportState.OFF_PROTOCOL -> "Not uploaded: off protocol (wrong size, frame rate or focus)"
        ExportState.NOT_EXPORTABLE -> "Not uploaded: it cannot be exported"
        ExportState.FAILED -> "Not uploaded: export failed"
        else -> if (exportRotationSet) "Preparing the video…" else "Set the video orientation to upload"
    }

    /** Why a queued recording is not moving, when the last attempt failed: otherwise "Queued" would say nothing. */
    private fun retryNote(error: String?): String = when {
        error.isNullOrBlank() -> ""
        error.startsWith("Network:") -> " · no connection"
        else -> " · retry: ${error.take(40)}"
    }

    /** Why the marketplace did not count a recording, in plain words (the files are kept either way). */
    fun notCountedText(reason: String?): String = when (reason) {
        "CAMPAIGN_FULL" -> "not counted: the campaign was already full"
        "CAMPAIGN_CLOSED" -> "not counted: the campaign had closed"
        "DUPLICATE" -> "not counted: it was already uploaded"
        else -> "not counted"
    }

    /**
     * "Synced", or "Synced · not counted: <why>". The pipeline-export branches only apply to recordings synced before the
     * export held up SYNCED: a recording synced now always has its export.
     */
    fun syncedLine(summary: SessionCloudSummary, exportRotationSet: Boolean = true): String = when {
        summary.notCounted -> "Synced · ${notCountedText(summary.marketplaceReason)}"
        summary.exportState == ExportState.OFF_PROTOCOL -> "Synced · no export (off protocol)"
        summary.exportState == ExportState.NOT_EXPORTABLE -> "Synced · no export"
        summary.exportState == ExportState.FAILED -> "Synced · export failed"
        summary.exportState == ExportState.PENDING ->
            if (exportRotationSet) "Synced · exporting…" else "Synced · set video rotation"
        summary.exportFilesFailed > 0 -> "Synced · export upload failed"
        summary.exportFilesOpen > 0 -> "Synced · uploading export…"
        else -> publishLine(summary)
    }

    /**
     * Everything is uploaded and the export is in: the last step is the backend putting the recording on the website. The
     * collector only sees "Done" once that has really happened (an export with no sets yet is still on its way).
     */
    private fun publishLine(summary: SessionCloudSummary): String = when (summary.publishState) {
        PublishState.UNSUPPORTED -> "Synced"
        PublishState.FAILED -> "Publish failed"
        PublishState.DONE ->
            if (summary.publishedSets > 0) "Done · live" else "Uploaded · publishing…"
        PublishState.PENDING -> "Uploaded · publishing…"
    }

    /** The dialog behind "Why isn't it on the website?"; null unless the backend gave up publishing it. */
    fun publishFailedDetail(summary: SessionCloudSummary): String? =
        if (summary.publishState != PublishState.FAILED) null
        else "The recording is safely uploaded, but the server could not put it on the website:\n\n" +
            (summary.publishNote ?: "unknown error")

    /** The dialog behind "Why isn't it uploaded?": the exporter's own words, which name what was wrong with the recording. */
    fun missingExportDetail(summary: SessionCloudSummary): String? = when (summary.exportState) {
        ExportState.OFF_PROTOCOL, ExportState.NOT_EXPORTABLE ->
            (summary.exportNote ?: "This recording cannot be exported.") +
                "\n\nThe video export is what a campaign pays for, so this recording is not uploaded."
        ExportState.FAILED -> "Making the video export failed: ${summary.exportNote ?: "unknown error"}"
        else -> null
    }

    /** What the Cloud sync settings show for the export rotation. */
    fun rotationLabel(degrees: Int?): String = when (degrees) {
        null -> "Not set: pipeline exports wait until you choose"
        0 -> "No rotation (phone mounted sideways)"
        90 -> "90° (phone mounted upright)"
        else -> "$degrees°"
    }

    /** [rotationLabel] for a settings row's second line, where the long form would wrap. */
    fun rotationShortLabel(degrees: Int?): String = when (degrees) {
        null -> "Not set"
        0 -> "Sideways (no rotation)"
        90 -> "Upright (90\u00b0)"
        else -> "$degrees\u00b0"
    }

    /** The action menu label for [CloudCardAction.START] / [CloudCardAction.RETRY]; null for an info-only state. */
    fun actionLabel(action: CloudCardAction): String? = when (action) {
        CloudCardAction.START -> "Upload to cloud now"
        CloudCardAction.RETRY -> "Retry failed upload"
        CloudCardAction.CHOOSE_CAMPAIGN, CloudCardAction.INFO -> null // the campaign chooser is its own entry: see [chooseCampaignLabel]
    }

    /** The Recordings menu entry that opens the campaign chooser for a recording not uploaded yet. */
    fun chooseCampaignLabel(hasCampaign: Boolean): String = if (hasCampaign) "Change campaign…" else "Choose a campaign to upload…"

    /** The line the end-of-take dialog shows once a good take is queued under the active campaign. */
    fun uploadingToMessage(campaignTitle: String, policy: SyncPolicy, networkOk: Boolean): String = when {
        policy == SyncPolicy.MANUAL_ONLY -> "$campaignTitle: upload is manual, use Recordings to start it."
        networkOk -> "Uploading to $campaignTitle (${policyShort(policy)})."
        else -> "$campaignTitle: waiting for ${if (policy == SyncPolicy.WIFI_ONLY) "Wi-Fi" else "a network"} to upload."
    }

    /** Why a take just recorded is not uploaded, for the end-of-take dialog. */
    fun notUploadedTakeMessage(redo: Boolean, complete: Boolean): String? = when {
        !complete -> "Not uploaded: the recording is incomplete."
        redo -> "Not uploaded: redo takes are never uploaded. Record it again."
        else -> null
    }

    private fun policyShort(policy: SyncPolicy) = if (policy == SyncPolicy.WIFI_ONLY) "Wi-Fi only" else "any network"

    /** What the Delete dialog says: the real cloud state instead of "make sure it has been shared". */
    fun deleteWarning(cloudEnabled: Boolean, summary: SessionCloudSummary?): String {
        if (!cloudEnabled) return "This cannot be undone. Make sure it has been shared or saved first."
        return if (summary?.isSynced == true) {
            "This recording is synced to the cloud, so deleting it only frees space on this phone."
        } else if (summary?.status == SessionCloudStatus.OTHER_ACCOUNT) {
            "This recording belongs to another account and has not been uploaded: this deletes the only copy. This cannot be undone."
        } else {
            "Not uploaded yet: this deletes the only copy. This cannot be undone."
        }
    }

    fun policyLabel(policy: SyncPolicy): String = when (policy) {
        SyncPolicy.WIFI_ONLY -> "Wi-Fi only"
        SyncPolicy.ANY_NETWORK -> "Any network"
        SyncPolicy.MANUAL_ONLY -> "Manual only"
    }

    fun retentionLabel(enabled: Boolean, days: Int): String =
        if (!enabled) "off" else if (days == 1) "after 1 day" else "after $days days"

    /** "2 synced, 3 waiting" for the storage header; empty when there is nothing to say. */
    fun storageSummary(summaries: Collection<SessionCloudSummary>): String {
        val synced = summaries.count { it.status == SessionCloudStatus.SYNCED }
        val waiting = summaries.count {
            it.status == SessionCloudStatus.PENDING || it.status == SessionCloudStatus.UPLOADING ||
                it.status == SessionCloudStatus.VERIFYING || it.status == SessionCloudStatus.WAITING_FOR_WIFI
        }
        val failed = summaries.count { it.status == SessionCloudStatus.FAILED }
        val needCampaign = summaries.count { it.status == SessionCloudStatus.AWAITING_CAMPAIGN }
        val otherAccount = summaries.count { it.status == SessionCloudStatus.OTHER_ACCOUNT }
        return listOfNotNull(
            "$synced synced".takeIf { synced > 0 },
            "$waiting waiting".takeIf { waiting > 0 },
            "$failed failed".takeIf { failed > 0 },
            "$needCampaign need a campaign".takeIf { needCampaign > 0 },
            "$otherAccount for another account".takeIf { otherAccount > 0 },
        ).joinToString(", ")
    }

    /** The mobile-data confirmation: the byte estimate the user is about to spend. */
    fun mobileDataMessage(totalBytes: Long): String =
        "You are on mobile data. Uploading " +
            (if (totalBytes > 0) "about ${CloudOverview.formatBytes(totalBytes)}" else "this recording") +
            " may use a lot of it."
}
