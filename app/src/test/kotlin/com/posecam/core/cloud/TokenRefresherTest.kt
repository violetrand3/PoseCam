package com.posecam.core.cloud

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/**
 * Refresh tokens ROTATE: a reused one revokes the whole login. So the rules under test are about *how many* refresh calls
 * are made as much as about what happens after them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TokenRefresherTest {
    private lateinit var server: MockWebServer
    private lateinit var context: Context
    private var now = 1_000_000L
    private val refreshCalls = AtomicInteger()
    private val refreshBodies = java.util.Collections.synchronizedList(mutableListOf<String>())
    private val seenAuth = java.util.Collections.synchronizedList(mutableListOf<String?>())

    /** What POST /v1/auth/refresh does; the default rotates the pair. */
    private var onRefresh: (RecordedRequest) -> MockResponse = { rotate(it) }
    private var generation = 1

    private val sessionBody = """{"session":{"sessionId":"s1","cloudStatus":"CREATED"}}"""

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("posecam_auth", Context.MODE_PRIVATE).edit().clear().commit()
        server = MockWebServer().also { s ->
            s.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path == "/v1/auth/refresh") {
                        refreshCalls.incrementAndGet()
                        refreshBodies += request.body.readUtf8()
                        return onRefresh(request)
                    }
                    val auth = request.getHeader("Authorization")
                    seenAuth += auth
                    // Only the newest access token is good; anything older has expired.
                    return if (auth == "Bearer access-$generation") {
                        MockResponse().setResponseCode(200).setBody(sessionBody)
                    } else {
                        MockResponse().setResponseCode(401).setBody("""{"error":{"code":"UNAUTHENTICATED","message":"expired"}}""")
                    }
                }
            }
            s.start()
        }
    }

    @After fun tearDown() { runCatching { server.shutdown() } }

    private val config get() = CloudConfig(baseUrl = server.url("/").toString().trimEnd('/'))

    /** The normal answer: the next access token and refresh token, as the backend rotates them. */
    private fun rotate(@Suppress("UNUSED_PARAMETER") request: RecordedRequest): MockResponse {
        Thread.sleep(150) // long enough for every concurrent caller to have been refused and be waiting
        generation++
        val body = """{"ok":true,"data":{"user":{"id":"acct-1","email":"a@b.co","name":"Asha","role":"CONTRIBUTOR"},""" +
            """"tokens":{"accessToken":"access-$generation","refreshToken":"refresh-$generation","tokenType":"Bearer","expiresIn":900}}}"""
        return MockResponse().setResponseCode(200).setBody(body)
    }

    private fun store(expiresAt: Long = now + 900_000, access: String = "access-1") = AuthStore(context) { now }.also {
        it.signIn(AuthSession("acct-1", "a@b.co", "Asha", access, expiresAt, "refresh-1"))
    }

    /** A base URL nobody listens on: the refresh call (and only it) finds no network. */
    private fun deadConfig(): CloudConfig {
        val dead = MockWebServer().also { it.start() }
        val url = dead.url("/").toString().trimEnd('/')
        dead.shutdown()
        return CloudConfig(baseUrl = url)
    }

    private fun wire(store: AuthStore, refreshConfig: CloudConfig = config): Pair<CloudApiClient, TokenRefresher> {
        val endpoints = AuthEndpoints(refreshConfig, "install-1", clock = { now })
        val refresher = TokenRefresher(store, endpoints)
        return CloudApiClient(config, UserAuthProvider(store, refresher), "install-1") to refresher
    }

    @Test fun concurrentRefusalsMakeExactlyOneRefreshCall() = runBlocking {
        val store = store(access = "access-0") // already refused by the server
        val (api, _) = wire(store)

        val sessions = (1..6).map { async(Dispatchers.IO) { api.getSession("s1") } }.awaitAll()

        assertEquals(6, sessions.size) // every call got through after the refresh
        assertEquals(1, refreshCalls.get())
        assertEquals("refresh-1", JSONObject(refreshBodies.single()).getString("refreshToken"))
        assertEquals("access-2", store.token())
        assertEquals("refresh-2", store.current().session!!.refreshToken)
        assertEquals(now + 900_000, store.current().session!!.expiresAtMs)
    }

    @Test fun theRetriedRequestCarriesTheNewTokenAndTheRefreshCarriesNoBearer() = runBlocking {
        val store = store(access = "access-0")
        val (api, _) = wire(store)

        api.getSession("s1")

        assertEquals(listOf("Bearer access-0", "Bearer access-2"), seenAuth.toList()) // refused once, retried once
    }

    @Test fun aRefusedRefreshTokenSignsOutAndKeepsEmailAndAccount() {
        onRefresh = { MockResponse().setResponseCode(401).setBody("""{"ok":false,"error":{"code":"UNAUTHENTICATED","message":"Session expired"}}""") }
        val store = store(access = "access-0")
        val (api, _) = wire(store)

        val error = assertThrows(CloudHttpException::class.java) { runBlocking { api.getSession("s1") } }

        assertEquals(401, error.status)
        assertFalse(store.isSignedIn)
        assertNull(store.token())
        assertEquals("a@b.co", store.current().lastEmail)
        assertEquals("acct-1", store.current().lastAccountId)
        assertEquals(1, refreshCalls.get())
    }

    @Test fun concurrentCallsWhoseRefreshIsRefusedStillMakeOneRefreshCall() = runBlocking {
        onRefresh = { Thread.sleep(150); MockResponse().setResponseCode(401).setBody("""{"error":{"code":"UNAUTHENTICATED","message":"no"}}""") }
        val store = store(access = "access-0")
        val (api, _) = wire(store)

        val results = (1..5).map { async(Dispatchers.IO) { runCatching { api.getSession("s1") } } }.awaitAll()

        assertTrue(results.all { it.exceptionOrNull() is CloudHttpException })
        assertEquals(1, refreshCalls.get()) // the dead refresh token is not tried again by the others
        assertFalse(store.isSignedIn)
    }

    @Test fun noNetworkDuringRefreshIsRetryableAndDoesNotSignOut() {
        val store = store(access = "access-0")
        val (api, _) = wire(store, refreshConfig = deadConfig())

        val error = assertThrows(CloudException::class.java) { runBlocking { api.getSession("s1") } }

        assertTrue(error.toString(), error is CloudNetworkException)
        assertTrue(error.retryable)
        assertTrue(store.isSignedIn)
        assertEquals("refresh-1", store.current().session!!.refreshToken) // unchanged: nothing was used up
        assertEquals("access-0", store.token())
    }

    @Test fun aServerErrorDuringRefreshIsRetryableAndDoesNotSignOut() {
        onRefresh = { MockResponse().setResponseCode(503).setBody("busy") }
        val store = store(access = "access-0")
        val (api, _) = wire(store)

        val error = assertThrows(CloudHttpException::class.java) { runBlocking { api.getSession("s1") } }

        assertEquals(503, error.status)
        assertTrue(error.retryable)
        assertTrue(store.isSignedIn)
    }

    @Test fun anAccessTokenAboutToExpireIsRefreshedBeforeTheCallNotAfterAFailure() = runBlocking {
        generation = 1
        val store = store(expiresAt = now + 30_000, access = "access-1") // 30 s left: inside the 60 s margin
        val (api, _) = wire(store)

        api.getSession("s1")

        assertEquals(1, refreshCalls.get())
        assertEquals(listOf("Bearer access-2"), seenAuth.toList()) // the old token was never sent
    }

    @Test fun aTokenWithPlentyOfTimeLeftIsNotRefreshed() = runBlocking {
        val store = store(expiresAt = now + 600_000)
        val (api, _) = wire(store)

        api.getSession("s1")

        assertEquals(0, refreshCalls.get())
    }

    @Test fun aFailedProactiveRefreshDoesNotStopACallWithATokenThatIsStillGood() = runBlocking {
        val store = store(expiresAt = now + 30_000, access = "access-1")
        val (api, _) = wire(store, refreshConfig = deadConfig())

        api.getSession("s1") // the stored token is still accepted by the server

        assertTrue(store.isSignedIn)
        assertEquals(listOf("Bearer access-1"), seenAuth.toList())
    }

    @Test fun successiveRefreshesUseTheLatestRefreshTokenBecauseTheyRotate() = runBlocking {
        val store = store()
        val (_, refresher) = wire(store)

        assertTrue(refresher.refreshAfterRejection("access-1"))
        assertTrue(refresher.refreshAfterRejection("access-2"))

        assertEquals(listOf("refresh-1", "refresh-2"), refreshBodies.map { JSONObject(it).getString("refreshToken") })
        assertEquals("refresh-3", store.current().session!!.refreshToken)
    }

    @Test fun aRejectedTokenThatAnotherCallAlreadyReplacedNeedsNoRefresh() = runBlocking {
        val store = store(access = "access-2")
        val (_, refresher) = wire(store)

        assertTrue(refresher.refreshAfterRejection("access-1")) // the one that was refused is not the stored one

        assertEquals(0, refreshCalls.get())
    }

    @Test fun nobodySignedInMeansNothingToRefresh() = runBlocking {
        val store = AuthStore(context) { now }
        val (_, refresher) = wire(store)

        assertFalse(refresher.refreshAfterRejection(null))
        refresher.refreshIfExpiring()

        assertEquals(0, refreshCalls.get())
    }
}
