package com.posecam.core.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.posecam.core.cloud.AuthEndpoints
import com.posecam.core.cloud.AuthSession
import com.posecam.core.cloud.AuthStore
import com.posecam.core.cloud.CloudApiClient
import com.posecam.core.cloud.CloudConfig
import com.posecam.core.cloud.TokenRefresher
import com.posecam.core.cloud.UserAuthProvider
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/**
 * The upload queue over the REAL API client, token refresh and a scripted marketplace: an expired access token must be
 * replaced (once) without the queue noticing, and a login that is over must stop the queue without losing anything.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AuthenticatedQueueTest {
    private val f = SyncFixture()
    private lateinit var server: MockWebServer
    private lateinit var context: Context
    private var now = 1_000_000L
    private val refreshCalls = AtomicInteger()
    private val unauthorizedAnswers = AtomicInteger()
    private var generation = 1
    private var refreshAnswer: () -> MockResponse = { rotate() }

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("posecam_auth", Context.MODE_PRIVATE).edit().clear().commit()
        f.api.multipartThreshold = Long.MAX_VALUE
        server = MockWebServer().also { s ->
            s.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    if (path == "/v1/auth/refresh") {
                        refreshCalls.incrementAndGet()
                        return refreshAnswer()
                    }
                    if (request.getHeader("Authorization") != "Bearer access-$generation") {
                        unauthorizedAnswers.incrementAndGet()
                        return MockResponse().setResponseCode(401).setBody("""{"error":{"code":"UNAUTHENTICATED","message":"expired"}}""")
                    }
                    return when {
                        path == "/v1/sessions" -> json("""{"session":{"sessionId":"s","cloudStatus":"CREATED"},"created":true,"requirements":{"rawRequired":false}}""", 201)
                        path.endsWith("/uploads/presign") ->
                            json("""{"relativePath":"x","mode":"SINGLE","url":"https://s3.example/put/x","headers":{},"expiresInSeconds":900}""")
                        path.endsWith("/uploads/verify") -> json("""{"relativePath":"x","state":"VERIFIED","sizeBytes":1}""")
                        path.endsWith("/complete") -> json("""{"session":{"sessionId":"s","cloudStatus":"SYNCED","marketplace":{"status":"CHECKING","reason":null,"submissionId":"sub"}}}""")
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            s.start()
        }
    }

    @After fun tearDown() { runCatching { server.shutdown() } }

    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setBody(body)

    private fun rotate(): MockResponse {
        generation++
        return json(
            """{"ok":true,"data":{"user":{"id":"${SyncFixture.ACCOUNT}","email":"a@b.co","name":"A","role":"CONTRIBUTOR"},""" +
                """"tokens":{"accessToken":"access-$generation","refreshToken":"refresh-$generation","tokenType":"Bearer","expiresIn":900}}}""",
        )
    }

    private val config get() = CloudConfig(baseUrl = server.url("/").toString().trimEnd('/'))

    private fun store(expiresAt: Long = now + 900_000, access: String = "access-1") = AuthStore(context) { now }.also {
        it.signIn(AuthSession(SyncFixture.ACCOUNT, "a@b.co", "A", access, expiresAt, "refresh-1"))
    }

    private fun queue(store: AuthStore, refreshConfig: CloudConfig = config): UploadProcessor {
        val refresher = TokenRefresher(store, AuthEndpoints(refreshConfig, "install-1", clock = { now }))
        val api = CloudApiClient(config, UserAuthProvider(store, refresher), "install-1")
        return f.processorFor(
            AccountScope({ store.accountId() }, { refresher.refreshIfExpiring() }),
            cloud = api,
        )
    }

    private suspend fun recording(): String {
        val id = "capture-20260917T090000-aaaaaa"
        val dir = f.sessionDir(id)
        f.repo.registerSession(id, dir, SyncFixture.CAMPAIGN, rawRequired = false, accountId = SyncFixture.ACCOUNT)
        f.repo.finalizeSession(id, dir, "complete", UploadPlan.forSession(id, dir, f.staging, includeRaw = false))
        return id
    }

    @Test fun anAccessTokenAboutToExpireIsRefreshedBeforeThePassStartsAndNothingIsRefused() = runBlocking {
        val store = store(expiresAt = now + 30_000, access = "access-1") // inside the 60 s margin
        generation = 1
        val id = recording()
        // The server only accepts the newest token; the refresh makes access-2 the one to use.
        val queue = queue(store)

        queue.runQueue()

        assertEquals(1, refreshCalls.get())
        assertEquals(0, unauthorizedAnswers.get())
        assertNotNull(f.repo.session(id)!!.syncedAt)
        assertEquals("CHECKING", f.repo.session(id)!!.marketplaceStatus)
        assertEquals("access-2", store.token())
    }

    @Test fun aTokenTheServerRevokesMidQueueIsRefreshedOnceAndTheQueueCarriesOn() = runBlocking {
        val store = store()
        val id = recording()
        val queue = queue(store)
        // After the first file the server rolls the key: every old token is refused from now on.
        var verifies = 0
        val original = server.dispatcher
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path.orEmpty().endsWith("/uploads/verify") && ++verifies == 1) generation++
                return original.dispatch(request)
            }
        }

        queue.runQueue()

        assertEquals(1, refreshCalls.get())
        assertEquals("access-3", store.token())
        assertEquals("refresh-3", store.current().session!!.refreshToken) // the pair rotated exactly once more
        assertNotNull(f.repo.session(id)!!.syncedAt)
        assertTrue(f.repo.uploadsForSession(id).all { it.state == UploadState.VERIFIED })
    }

    @Test fun aLoginThatIsOverStopsTheQueueWithoutLosingOrFailingAnything() = runBlocking {
        refreshAnswer = { json("""{"ok":false,"error":{"code":"UNAUTHENTICATED","message":"Session expired"}}""", 401) }
        val store = store(access = "access-0")
        val id = recording()
        val queue = queue(store)

        queue.runQueue()

        assertFalse(store.isSignedIn)
        assertEquals(1, refreshCalls.get())
        assertEquals("a@b.co", store.current().lastEmail)
        assertNull(f.repo.session(id)!!.syncedAt)
        assertFalse(f.repo.session(id)!!.permanentFailure)
        assertTrue(f.repo.uploadsForSession(id).none { it.state == UploadState.FAILED })
        // Nothing runs while signed out, and signing in again resumes exactly where it was.
        assertFalse(queue.hasWork())
        store.signIn(AuthSession(SyncFixture.ACCOUNT, "a@b.co", "A", "access-$generation", now + 900_000, "refresh-x"))
        queue.runQueue()
        assertNotNull(f.repo.session(id)!!.syncedAt)
    }

    @Test fun noNetworkWhileRefreshingIsARetryNotASignOut() = runBlocking {
        val dead = MockWebServer().also { it.start() }
        val deadConfig = CloudConfig(baseUrl = dead.url("/").toString().trimEnd('/'))
        dead.shutdown()
        val store = store(access = "access-0")
        val id = recording()
        val queue = queue(store, refreshConfig = deadConfig)

        val result = queue.runQueue()

        assertEquals(QueueRunResult.RETRY, result)
        assertTrue(store.isSignedIn)
        assertEquals("refresh-1", store.current().session!!.refreshToken)
        assertNull(f.repo.session(id)!!.syncedAt)
        assertTrue(f.repo.uploadsForSession(id).none { it.state == UploadState.FAILED })
        // The network is back: the refresh happens and the queue finishes.
        val again = queue(store)
        assertEquals(QueueRunResult.DONE, again.runQueue())
        assertNotNull(f.repo.session(id)!!.syncedAt)
    }
}
