package com.posecam.core.sync

import com.posecam.SessionFileListener
import com.posecam.core.cloud.CloudLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.File

/**
 * Bridges recording and the upload queue. It is the [SessionFileListener] handed to
 * [com.posecam.PoseRecorder]: the recording thread only calls [Channel.trySend] (never blocks, never
 * touches Room or the network); a single background consumer then does the database work in event
 * order and talks to the scheduler.
 *
 * Uploads of earlier recordings keep running while a new take is recorded, so a collector who records back to back
 * never waits for a backlog to drain (and nothing has to be restarted afterwards). The take itself is never touched:
 * its files are not queued until it is finalized, upload work runs on background-priority threads, and the one
 * heavy job, the pipeline export, waits for Stop. Dropped images (`queue_full`) are counted in each manifest, so a
 * collector or reviewer can tell if upload load ever costs a take frames.
 */
class UploadCoordinator(
    private val repository: UploadRepository,
    private val scheduler: UploadScheduler,
    private val staging: UploadStaging,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    /** The signed-in (or last signed-in) account: every recording started now belongs to it. Null: unowned, adopted later. */
    private val accountId: () -> String? = { null },
    /** The campaign chosen for recording right now, from the phone's cached list; null: nothing is queued for this take. */
    private val activeCampaign: () -> ActiveCampaign? = { null },
) : SessionFileListener {

    private sealed interface Event {
        data class Started(val sessionId: String, val directory: File, val campaign: ActiveCampaign?, val account: String?) : Event
        data class Finalized(val sessionId: String, val directory: File, val status: String) : Event
        data class ChooseCampaign(val sessionId: String, val campaign: ActiveCampaign) : Event
    }

    private val events = Channel<Event>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (event in events) {
                try {
                    handle(event)
                } catch (cancel: kotlinx.coroutines.CancellationException) {
                    throw cancel
                } catch (error: Throwable) {
                    // The queue is rebuilt from the session directories by UploadQueueRecovery on
                    // the next launch, so a failed enqueue is never a permanent loss.
                    CloudLog.e("enqueue_failed", error)
                }
            }
        }
    }

    // ---- SessionFileListener (called on the recording thread: must not block) --------------

    override fun onSessionStarted(sessionId: String, directory: File) {
        // Synchronous and cheap: queue recovery, storage cleanup and the pipeline export consult this set, and must
        // leave a session that is still being written alone.
        ActiveRecordingSessions.add(sessionId)
        // Read here, on the recording thread, so the take belongs to the campaign and account that were active when it began
        // (not whatever has been chosen by the time the event is handled). Both are cheap reads of cached state.
        val campaign = runCatching(activeCampaign).getOrNull()
        val account = runCatching(accountId).getOrNull()
        events.trySend(Event.Started(sessionId, directory, campaign, account))
    }

    override fun onSessionFinalized(sessionId: String, directory: File, recordingStatus: String) {
        events.trySend(Event.Finalized(sessionId, directory, recordingStatus))
    }

    // ---- actions ---------------------------------------------------------------------------

    /**
     * The collector chose the campaign for a recording that has none (an older one) or changed it before upload. Sent
     * through the same ordered channel as the recorder's events, so it can never overtake "started" / "finalized";
     * uploading of this session begins as soon as it is handled. Redo takes and incomplete recordings are refused.
     */
    fun chooseCampaign(sessionId: String, campaign: ActiveCampaign) {
        events.trySend(Event.ChooseCampaign(sessionId, campaign))
    }

    fun requestSync() {
        scope.launch { scheduler.syncNow() }
    }

    /** The user tapped upload on one session: upload just that session now, whatever the policy. */
    fun syncSession(sessionId: String, retryFailedFiles: Boolean = false) {
        scope.launch {
            if (retryFailedFiles) repository.retryFailed(sessionId, accountId())
            // Also first in line, so a whole-queue run that happens to be going picks it up next.
            repository.prioritizeSession(sessionId)
            scheduler.syncNow(sessionId)
        }
    }

    fun retryFailed(sessionId: String?) {
        scope.launch {
            repository.retryFailed(sessionId, accountId())
            scheduler.syncNow(sessionId)
        }
    }

    /** Make sure an upload worker is queued for whatever is waiting; cheap, and a no-op while one already is. */
    fun resumeUploads() {
        scope.launch { scheduler.schedule() }
    }

    /** The export rotation was set: recordings whose export was waiting for it are picked up by the next pass. */
    fun onExportSettingsChanged() {
        scope.launch { scheduler.schedule() }
    }

    fun onNetworkPolicyChanged() {
        scope.launch { scheduler.reschedule() }
    }

    fun onSessionDeletedLocally(sessionId: String) {
        scope.launch { repository.forgetSession(sessionId) }
    }

    // ---- event handling --------------------------------------------------------------------

    private suspend fun handle(event: Event) {
        when (event) {
            is Event.Started -> repository.registerSession(
                event.sessionId, event.directory, event.campaign?.id, event.campaign?.collectRaw ?: true, event.account,
            )
            is Event.Finalized -> {
                val failure = runCatching {
                    val eligible = SessionManifestInfo.read(event.directory)?.eligibleForUpload == true &&
                        event.status == CloudFileRules.STATUS_COMPLETE
                    // Only a good, complete take with a campaign is queued; a redo take or an incomplete recording never is.
                    val session = repository.session(event.sessionId)
                    val files = if (eligible && session?.pipe != null) {
                        UploadPlan.forSession(event.sessionId, event.directory, staging, includeRaw = session.rawRequired)
                    } else {
                        emptyList()
                    }
                    repository.finalizeSession(event.sessionId, event.directory, event.status, files, eligible)
                }.exceptionOrNull()
                // Cleared only after the final rows are queued, so recovery never races a half-enqueued
                // session -- but cleared even if queueing failed, or uploads would stay blocked until the app
                // restarts (recovery re-queues the session then).
                ActiveRecordingSessions.remove(event.sessionId)
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                if (failure != null) CloudLog.e("finalize_failed", failure, "session" to event.sessionId)
                scheduler.schedule()
            }
            is Event.ChooseCampaign -> {
                val session = repository.session(event.sessionId) ?: return
                val directory = File(session.directoryPath)
                val manifest = SessionManifestInfo.read(directory)
                if (!session.recordingFinal || manifest?.eligibleForUpload != true) {
                    CloudLog.w("campaign_refused", "session" to event.sessionId, "reason" to (manifest?.notUploadedReason ?: "not final"))
                    return
                }
                if (repository.assignCampaign(event.sessionId, event.campaign.id, event.campaign.collectRaw, accountId())) {
                    val files = UploadPlan.forSession(event.sessionId, directory, staging, includeRaw = event.campaign.collectRaw)
                    repository.addFiles(event.sessionId, directory, files)
                    scheduler.schedule()
                }
            }
        }
    }
}
