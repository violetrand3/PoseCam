package com.posecam.core.sync

import android.content.Context
import com.posecam.BuildConfig
import com.posecam.core.cloud.AccountApi
import com.posecam.core.cloud.AccountApiClient
import com.posecam.core.cloud.AppMeta
import com.posecam.core.cloud.AppMetaStore
import com.posecam.core.cloud.AuthEndpoints
import com.posecam.core.cloud.AuthSession
import com.posecam.core.cloud.AuthStore
import com.posecam.core.cloud.CloudApi
import com.posecam.core.cloud.CloudApiClient
import com.posecam.core.cloud.CloudConfig
import com.posecam.core.cloud.CloudLog
import com.posecam.core.cloud.InstallationId
import com.posecam.core.cloud.TokenRefresher
import com.posecam.core.cloud.UserAuthProvider
import com.posecam.CaptureActivity
import com.posecam.core.sync.CampaignCatalog.Companion.toActive
import com.posecam.SessionFileListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File

/**
 * Process-wide wiring for cloud sync -- a small hand-rolled service locator, deliberately not a
 * DI framework. Everything is created lazily and off the recording path: when the backend URL is
 * not configured, [sessionListener] is null, the database is never opened and recording is
 * byte-for-byte what it was before cloud upload existed.
 *
 * Authentication is one seam: [UserAuthProvider] supplies the signed-in collector's token to [CloudApiClient] and keeps
 * it fresh ([TokenRefresher]); the upload queue knows only whose recordings it may upload ([AccountScope]) and that
 * nothing is sent while nobody is signed in.
 */
class CloudSync private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val config: CloudConfig = CloudConfig.fromBuildConfig()
    val settings: CloudSyncSettings by lazy { CloudSyncSettings(appContext) }
    val networkStatus: NetworkStatus by lazy { NetworkStatus(appContext) }

    /** Who is signed in. Recording never depends on it; uploading does. */
    val auth: AuthStore by lazy { AuthStore(appContext) }
    private val installationId: String by lazy { InstallationId(appContext).get() }

    val notifier: CloudNotifier by lazy { CloudNotifier(appContext) }
    val announcer: SyncAnnouncer by lazy { SyncAnnouncer(PrefsAnnouncementStore(appContext)) }

    /** `getExternalFilesDir(null)`: where PoseCam keeps `captures/` (and, beside it, `upload-staging/`). */
    private val appRoot: File by lazy { appContext.getExternalFilesDir(null) ?: appContext.filesDir }

    /** The recordings folder: one `capture-...` directory per take. Same path CaptureActivity records into. */
    val capturesRoot: File by lazy { File(appRoot, "captures") }

    private val staging: UploadStaging by lazy { UploadStaging(File(appRoot, "upload-staging")) }

    private val database: UploadDatabase by lazy { UploadDatabase.get(appContext) }
    val repository: UploadRepository by lazy {
        UploadRepository(database.uploadDao(), Transactor.forDatabase(database), onSessionForgotten = staging::purgeSession)
    }
    private val scheduler: UploadScheduler by lazy { WorkManagerUploadScheduler(appContext, settings) }
    val coordinator: UploadCoordinator by lazy {
        UploadCoordinator(
            repository, scheduler, staging,
            accountId = ::recordingAccountId,
            activeCampaign = { campaigns.active(recordingAccountId())?.toActive() },
        )
    }

    /**
     * The account a recording made now belongs to: the signed-in one, or -- when the login lapsed while the collector
     * was in the field -- the last one, so that a recording made offline is still theirs. Null only on a phone nobody
     * ever signed in on.
     */
    fun recordingAccountId(): String? = auth.current().let { it.session?.accountId ?: it.lastAccountId }

    private val exportStage: PipelineExportStage by lazy {
        PipelineExportStage(
            repository = repository,
            staging = staging,
            exporter = PipelineSessionExporter(BuildConfig.VERSION_NAME) { settings.exportAsOneVideo },
            rotation = { settings.exportRotationDegrees },
        )
    }

    private val authEndpoints by lazy { AuthEndpoints(config, installationId) }
    private val tokenRefresher by lazy { TokenRefresher(auth, authEndpoints) }
    private val authProvider by lazy { UserAuthProvider(auth, tokenRefresher) }
    private val api: CloudApi by lazy { CloudApiClient(config, authProvider, installationId) }

    /** Sign-up, sign-in and out, and the collector's upload history. */
    val accounts: AccountApi by lazy {
        AccountApiClient(config, authProvider, installationId, CloudApiClient.defaultClient(config), authEndpoints)
    }

    /** The website's links and the terms version, from `GET /v1/meta`, cached; build-config constants until fetched. */
    val meta: AppMetaStore by lazy { AppMetaStore(appContext, fallbackMeta()) }

    private fun fallbackMeta(): AppMeta {
        val web = BuildConfig.WEB_APP_URL.trim().trimEnd('/').takeIf { it.isNotEmpty() }
        return AppMeta(
            termsVersion = null,
            termsUrl = web?.let { "$it/terms" },
            privacyUrl = web?.let { "$it/privacy" },
            passwordResetUrl = web?.let { "$it/forgot-password" },
            webAppUrl = web,
            appDownloadUrl = null,
        )
    }

    /** Fetches `GET /v1/meta` and caches it. Returns the (possibly cached) answer, or null if it was never fetched and failed. */
    suspend fun refreshMeta(): AppMeta? = try {
        accounts.meta().also { meta.save(it) }
    } catch (cancel: kotlinx.coroutines.CancellationException) {
        throw cancel
    } catch (error: Exception) {
        CloudLog.i("meta_refresh_failed", "reason" to error.javaClass.simpleName)
        meta.takeIf { it.hasFetched() }?.current()
    }

    /**
     * The collector signed in or up: what was waiting for a token (campaigns, the queue, uploads) can go. Recordings from
     * before accounts were tracked become theirs.
     */
    fun onSignedIn(session: AuthSession) {
        auth.signIn(session)
        if (!config.enabled) return
        scope.launch { runCatching { repository.adoptUnowned(session.accountId) } }
        refreshCampaigns()
        recoverQueue()
        scope.launch { runCatching { scheduler.schedule() } }
    }

    /** Signing out keeps recordings and their queue; uploads wait for the next sign-in. */
    fun signOut() = auth.signOut()

    /**
     * Log out: ends the login on the server (best effort -- offline is fine) and forgets the tokens here. The email,
     * the recordings and the upload queue stay; the queue's rows wait for the account that owns them.
     */
    suspend fun logOut() {
        val refresh = auth.current().session?.refreshToken
        if (refresh != null && config.enabled) {
            runCatching { accounts.logout(refresh) }.onFailure { CloudLog.i("logout_remote_failed", "reason" to it.javaClass.simpleName) }
        }
        auth.signOut()
    }

    /** The campaigns the collector accepted, cached on the phone for recording offline (empty until the first fetch). */
    val campaigns: CampaignCatalog by lazy { CampaignCatalog(api, PrefsCampaignStore(appContext)) }

    /** The cached campaign list of the account recordings are made for now. */
    fun myCampaigns() = campaigns.current(recordingAccountId())

    /** Fetches the campaign list in the background; never blocks, never throws, keeps the old list on failure. */
    fun refreshCampaigns() {
        if (!config.enabled) return
        scope.launch { runCatching { campaigns.refresh(auth.accountId()) } }
    }

    /** Same, for a screen that wants to know when the list changed. */
    suspend fun refreshCampaignsNow(): Boolean = campaigns.refresh(auth.accountId())

    /** Asks the backend whether synced recordings have reached the website (shown as "Done"). */
    val publishTracker: PublishTracker by lazy { PublishTracker(repository, api) }
    private var publishLoop: Job? = null

    /**
     * While the app is on screen, keeps checking recordings that are synced but not yet on the website. Idempotent. A
     * recording that just turned "Done" may now be deletable, so storage cleanup gets a chance right away.
     */
    fun startPublishTracking() {
        if (!config.enabled || publishLoop?.isActive == true) return
        publishLoop = scope.launch {
            var lastKickAt = System.currentTimeMillis() // recovery has just scheduled the queue: give it a chance first
            while (isActive) {
                if (AppVisibility.inForeground && runCatching { kickDue(System.currentTimeMillis() - lastKickAt) }.getOrDefault(false)) {
                    lastKickAt = System.currentTimeMillis()
                }
                if (AppVisibility.inForeground) {
                    val changed = runCatching { publishTracker.checkDue() }.getOrDefault(0)
                    if (changed > 0) runCatching { retention.cleanup() }
                }
                delay(PUBLISH_TICK_MS)
            }
        }
    }

    /** Starts the queue if recordings are waiting, nothing is uploading and the policy allows it. True if it did. */
    private suspend fun kickDue(sinceLastKickMs: Long): Boolean {
        val policy = settings.policy
        val due = UploadKick.shouldKick(
            signedIn = auth.isSignedIn,
            policy = policy,
            networkSatisfiesPolicy = networkStatus.satisfies(policy),
            passRunning = processor.isRunning,
            sinceLastKickMs = sinceLastKickMs,
            hasWork = processor.hasWork(),
        )
        if (due) {
            CloudLog.i("kick_stalled")
            scheduler.syncNow()
        }
        return due
    }

    val processor: UploadProcessor by lazy {
        UploadProcessor(
            repository = repository,
            api = api,
            transport = OkHttpS3Transport(config),
            hasher = FileHasher(UploadThreads.dispatcher),
            installationId = { installationId },
            appVersion = BuildConfig.VERSION_NAME,
            materializer = FrameChunkMaterializer(repository, UploadThreads.dispatcher),
            exportStage = exportStage,
            account = AccountScope(accountId = { auth.accountId() }, refreshIfExpiring = { tokenRefresher.refreshIfExpiring() }),
            rawPlan = { sessionId, directory -> UploadPlan.forSession(sessionId, directory, staging, includeRaw = true) },
        )
    }

    val retention: LocalRetentionManager by lazy {
        LocalRetentionManager(
            repository = repository,
            enabled = { settings.autoCleanupEnabled },
            retentionDays = { settings.retentionDays },
            storagePressure = { storagePressure(appRoot) },
        )
    }

    /** What recording hands to [com.posecam.PoseRecorder]; null when sync is off. */
    val sessionListener: SessionFileListener? get() = if (config.enabled) coordinator else null

    /**
     * Per-session cloud progress for the UI; empty when sync is off. Another account's recordings read "belongs to another
     * account" (see [SessionCloudSummary.forAccount]) and so do not count as this collector's pending uploads.
     */
    fun observeSummaries(): Flow<Map<String, SessionCloudSummary>> =
        if (config.enabled) {
            combine(repository.observeSummaries(), auth.state) { summaries, state ->
                val account = state.session?.accountId
                summaries.mapValues { it.value.forAccount(account) }
            }
        } else {
            emptyFlow()
        }

    private val _settings by lazy { MutableStateFlow(settings.snapshot()) }

    /** Live settings for the Settings screen. */
    val settingsFlow: StateFlow<CloudSettingsSnapshot> get() = _settings.asStateFlow()

    fun setPolicy(policy: SyncPolicy) {
        settings.policy = policy
        _settings.value = settings.snapshot()
        // Replace waiting work so it picks up the new network constraint immediately.
        if (config.enabled) coordinator.onNetworkPolicyChanged()
    }

    fun setRetentionDays(days: Int) {
        settings.retentionDays = days
        _settings.value = settings.snapshot()
    }

    /** The collector chose which way is up in exported videos: exports that were waiting for it can start. */
    fun setExportRotation(degrees: Int) {
        settings.exportRotationDegrees = degrees
        _settings.value = settings.snapshot()
        if (config.enabled) coordinator.onExportSettingsChanged()
    }

    fun setExportAsOneVideo(enabled: Boolean) {
        settings.exportAsOneVideo = enabled
        _settings.value = settings.snapshot()
    }

    fun setConfirmMobileData(enabled: Boolean) {
        settings.confirmMobileData = enabled
        _settings.value = settings.snapshot()
    }

    /** True on mobile data or any other metered network; used to confirm before a manual upload. */
    fun isMetered(): Boolean = !networkStatus.isUnmetered()

    fun setAutoCleanupEnabled(enabled: Boolean) {
        settings.autoCleanupEnabled = enabled
        _settings.value = settings.snapshot()
    }

    /**
     * True while the current network would not let queued uploads start under the active policy.
     * Live: re-evaluated on every connectivity change and every policy change.
     */
    fun waitingForNetwork(): Flow<Boolean> =
        if (!config.enabled) {
            flowOf(false)
        } else {
            combine(_settings, networkStatus.changes()) { snapshot, _ -> !networkStatus.satisfies(snapshot.policy) }
                .distinctUntilChanged()
        }

    /** App start: rebuild/resume the queue off the main thread. Adopts recordings made before cloud upload existed. */
    fun recoverQueue() {
        if (!config.enabled) return
        UploadWatchdog.enable(appContext)
        startPublishTracking()
        scope.launch {
            runCatching { UploadQueueRecovery(capturesRoot, repository, scheduler, staging).run() }
            runCatching { retention.cleanup() }
        }
    }

    /** Recording is running low on space: reclaim what is already safely in the cloud. */
    fun reclaimStorage() {
        if (!config.enabled) return
        scope.launch { runCatching { retention.cleanup() } }
    }

    /**
     * PoseCam refuses to record below [CaptureActivity.MIN_FREE_GB], so cleanup of already-synced
     * recordings starts 2 GB earlier (6 GB), while there is still room to work in.
     */
    private fun storagePressure(root: File): Boolean = root.usableSpace < (CaptureActivity.MIN_FREE_GB + 2.0) * 1e9

    companion object {
        /** How often the publish loop wakes up; each recording is asked on its own, slower schedule. */
        private const val PUBLISH_TICK_MS = 5_000L

        @Volatile
        private var instance: CloudSync? = null

        fun get(context: Context): CloudSync = instance ?: synchronized(this) {
            instance ?: CloudSync(context).also { instance = it }
        }
    }
}
