package com.posecam.core.cloud

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AccountApiClientTest {
    private lateinit var server: MockWebServer
    private var unauthorized = 0
    private val NOW = 1_000_000L

    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() { runCatching { server.shutdown() } }

    private val config get() = CloudConfig(baseUrl = server.url("/").toString().trimEnd('/'))

    private fun client(token: String? = null, provider: AuthProvider? = null) = AccountApiClient(
        config,
        provider ?: object : AuthProvider {
            override suspend fun getToken() = token
            override fun onUnauthorized() { unauthorized++ }
        },
        "install-1",
        endpoints = AuthEndpoints(config, "install-1", clock = { NOW }),
    )

    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setBody(body)

    private val authBody = """{"ok":true,"data":{"user":{"id":"u-1","name":"Asha","email":"a@b.co","role":"CONTRIBUTOR"},""" +
        """"tokens":{"accessToken":"acc.tok","refreshToken":"ref.tok","tokenType":"Bearer","expiresIn":900}}}"""

    @Test
    fun registerPostsTheMarketplaceBodyAndMapsTheTokensToASession() = runBlocking {
        server.enqueue(json(authBody, 201))

        val session = client().register("a@b.co", "hunter2hunter2", "Asha", "2026-10")

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/auth/register", request.path)
        assertNull(request.getHeader("Authorization"))
        val body = JSONObject(request.body.readUtf8())
        assertEquals("a@b.co", body.getString("email"))
        assertEquals("hunter2hunter2", body.getString("password"))
        assertEquals("Asha", body.getString("name"))
        assertEquals("CONTRIBUTOR", body.getString("role"))
        assertEquals("2026-10", body.getString("termsVersion"))
        assertEquals("u-1", session.accountId)
        assertEquals("acc.tok", session.token)
        assertEquals("ref.tok", session.refreshToken)
        assertEquals("Asha", session.name)
        assertEquals(NOW + 900_000, session.expiresAtMs) // now + expiresIn
    }

    @Test
    fun loginCallsTheAuthLoginPath() = runBlocking {
        server.enqueue(json(authBody))

        val session = client().login("a@b.co", "hunter2hunter2")

        val request = server.takeRequest()
        assertEquals("/v1/auth/login", request.path)
        assertEquals(setOf("email", "password"), JSONObject(request.body.readUtf8()).keys().asSequence().toSet())
        assertEquals("u-1", session.accountId)
    }

    @Test
    fun theSessionNeverPrintsItsTokens() {
        val session = AuthSession("u-1", "a@b.co", "Asha", "secret-access", NOW, "secret-refresh")

        assertFalse(session.toString().contains("secret"))
    }

    @Test
    fun aBlankNameIsNotSent() = runBlocking {
        server.enqueue(json(authBody, 201))

        client().register("a@b.co", "hunter2hunter2", "  ", "v1")

        assertFalse(JSONObject(server.takeRequest().body.readUtf8()).has("name"))
    }

    @Test
    fun loginSurfacesTheServersReasonForAWrongPassword() {
        server.enqueue(json("""{"ok":false,"error":{"code":"UNAUTHENTICATED","message":"Wrong email or password"}}""", 401))

        val error = assertThrows(CloudHttpException::class.java) { runBlocking { client().login("a@b.co", "nope") } }

        assertEquals("UNAUTHENTICATED", error.code)
        assertEquals("Wrong email or password", AuthValidation.messageFor(error))
        assertEquals(0, unauthorized) // a failed login is not an expired session
    }

    @Test
    fun aTakenEmailIsExplainedToTheCollector() {
        server.enqueue(json("""{"ok":false,"error":{"code":"CONFLICT","message":"An account with this email already exists"}}""", 409))

        val error = assertThrows(CloudHttpException::class.java) { runBlocking { client().register("a@b.co", "hunter2hunter2", null, "v1") } }

        assertEquals("An account with this email already exists", AuthValidation.messageFor(error))
    }

    @Test
    fun fieldErrorsOfAValidationFailureAreShownPerField() {
        server.enqueue(
            json(
                """{"ok":false,"error":{"code":"VALIDATION","message":"Invalid input","fieldErrors":{"termsVersion":"Terms have changed","password":["Too short","Needs a digit"]}}}""",
                400,
            ),
        )

        val error = assertThrows(CloudHttpException::class.java) { runBlocking { client().register("a@b.co", "short", null, "old") } }

        assertEquals(mapOf("termsVersion" to "Terms have changed", "password" to "Too short Needs a digit"), error.fieldErrors)
        assertEquals("Password: Too short Needs a digit\nTerms: Terms have changed", AuthValidation.messageFor(error))
    }

    @Test
    fun metaIsPublicAndReadFromTheEnvelope() = runBlocking {
        server.enqueue(
            json(
                """{"ok":true,"data":{"termsVersion":"2026-10","termsUrl":"https://w.example/terms","privacyUrl":"https://w.example/privacy",""" +
                    """"passwordResetUrl":"https://w.example/forgot-password","webAppUrl":"https://w.example","appDownloadUrl":null}}""",
            ),
        )

        val meta = client(token = "T").meta()

        val request = server.takeRequest()
        assertEquals("/v1/meta", request.path)
        assertNull(request.getHeader("Authorization"))
        assertEquals("2026-10", meta.termsVersion)
        assertEquals("https://w.example/forgot-password", meta.passwordResetUrl)
        assertEquals("https://w.example", meta.webAppUrl)
        assertNull(meta.appDownloadUrl)
    }

    @Test
    fun logoutSendsTheRefreshTokenWithoutABearer() = runBlocking {
        server.enqueue(json("""{"ok":true,"data":null}"""))

        client(token = "T").logout("ref.tok")

        val request = server.takeRequest()
        assertEquals("/v1/auth/logout", request.path)
        assertNull(request.getHeader("Authorization"))
        assertEquals("ref.tok", JSONObject(request.body.readUtf8()).getString("refreshToken"))
    }

    @Test
    fun aTokenResponseOfTheWrongShapeIsAMalformedResponse() {
        server.enqueue(json("""{"ok":true,"data":{"user":{"id":"u-1","email":"a@b.co"}}}"""))

        val error = assertThrows(CloudHttpException::class.java) { runBlocking { client().login("a@b.co", "x") } }

        assertEquals("MALFORMED_RESPONSE", error.code)
    }

    @Test
    fun statsAreReadWithTheTokenAndThePhonesTimeZone() = runBlocking {
        server.enqueue(
            json(
                """{"email":"a@b.co","totalRecordings":12,"uploadedToday":3,"firstUploadAt":"2026-09-01T05:00:00.000Z",
                    "days":[{"date":"2026-10-09","recordings":3,"campaigns":{"c-1":2,"c-2":1},"pipes":{"c-1":2,"c-2":1}},
                            {"date":"2026-10-08","recordings":9,"campaigns":{"c-1":9},"pipes":{"c-1":9}}]}""",
            ),
        )

        val stats = client(token = "T").myStats(330)

        val request = server.takeRequest()
        assertEquals("/v1/me/stats?tzOffsetMinutes=330", request.path)
        assertEquals("Bearer T", request.getHeader("Authorization"))
        assertEquals("install-1", request.getHeader("X-Device-Id"))
        assertEquals(12, stats.totalRecordings)
        assertEquals(3, stats.uploadedToday)
        assertEquals(
            listOf(DayCount("2026-10-09", 3, mapOf("c-1" to 2, "c-2" to 1)), DayCount("2026-10-08", 9, mapOf("c-1" to 9))),
            stats.days,
        )
    }

    @Test
    fun anOlderStatsAnswerWithPipesAndLiveCountsStillParses() = runBlocking {
        server.enqueue(
            json(
                """{"email":"a@b.co","totalRecordings":1,"uploadedToday":0,"firstUploadAt":null,
                    "days":[{"date":"2026-10-09","recordings":1,"pipes":{"white":1},"live":1,"publishing":0,"failed":0}]}""",
            ),
        )

        val stats = client(token = "T").myStats(0)

        assertEquals(DayCount("2026-10-09", 1, mapOf("white" to 1)), stats.days.single())
    }

    @Test
    fun oneDaysRecordingsAreAskedForByDateAndTimeZone() = runBlocking {
        server.enqueue(
            json(
                """{"date":"2026-10-09","total":2,"recordings":[
                    {"sessionId":"capture-20261009T154304-60c403","campaignId":"c-1","campaignTitle":"Kitchen","pipe":"c-1","pipeLabel":"Kitchen","uploadedAt":"2026-10-09T10:00:00.000Z","date":"2026-10-09","status":"uploaded","marketplaceStatus":"IN_REVIEW"},
                    {"sessionId":"capture-20261009T101500-aaaaaa","campaignId":"c-2","campaignTitle":"Laundry","uploadedAt":"2026-10-09T09:00:00.000Z","date":"2026-10-09","status":"uploaded","marketplaceStatus":null}]}""",
            ),
        )

        val list = client(token = "T").uploadsOn("2026-10-09", 330)

        val request = server.takeRequest()
        assertEquals("/v1/me/uploads?date=2026-10-09&tzOffsetMinutes=330", request.path)
        assertEquals("Bearer T", request.getHeader("Authorization"))
        assertEquals(listOf("c-1", "c-2"), list.map { it.campaignId })
        assertEquals(listOf("Kitchen", "Laundry"), list.map { it.campaignTitle })
        assertEquals("uploaded", list[1].status)
        assertEquals("IN_REVIEW", list[0].marketplaceStatus)
        assertNull(list[1].marketplaceStatus)
    }

    @Test
    fun anOlderUploadsAnswerWithPipeAndPublishStatusesStillParses() = runBlocking {
        server.enqueue(
            json(
                """{"date":"2026-10-09","total":1,"recordings":[{"sessionId":"capture-20261009T154304-60c403","pipe":"white","pipeLabel":"White pipes","uploadedAt":"x","date":"2026-10-09","status":"publish_failed"}]}""",
            ),
        )

        val list = client(token = "T").uploadsOn("2026-10-09", 330)

        assertEquals("white", list.single().campaignId)
        assertEquals("White pipes", list.single().campaignTitle)
    }

    @Test
    fun aRefusedTokenWithNoWayToRefreshIsReportedToTheProvider() {
        server.enqueue(json("""{"error":{"code":"UNAUTHENTICATED","message":"Sign in again"}}""", 401))

        assertThrows(CloudHttpException::class.java) { runBlocking { client(token = "old").myStats(0) } }

        assertEquals(1, unauthorized)
    }

    @Test
    fun aRefusedTokenIsRefreshedOnceAndTheCallRetriedOnce() = runBlocking {
        server.enqueue(json("""{"error":{"code":"UNAUTHENTICATED","message":"expired"}}""", 401))
        server.enqueue(json("""{"email":"a@b.co","totalRecordings":0,"uploadedToday":0,"firstUploadAt":null,"days":[]}"""))
        var token = "old"
        val provider = object : AuthProvider {
            override suspend fun getToken() = token
            override suspend fun refreshAfterUnauthorized(rejectedToken: String?): Boolean {
                assertEquals("old", rejectedToken)
                token = "new"
                return true
            }
        }

        client(provider = provider).myStats(0)

        assertEquals("Bearer old", server.takeRequest().getHeader("Authorization"))
        assertEquals("Bearer new", server.takeRequest().getHeader("Authorization"))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun aSecondRefusalAfterARefreshIsNotRetriedAgain() {
        repeat(2) { server.enqueue(json("""{"error":{"code":"UNAUTHENTICATED","message":"no"}}""", 401)) }
        val provider = object : AuthProvider {
            override suspend fun getToken() = "t"
            override suspend fun refreshAfterUnauthorized(rejectedToken: String?) = true
        }

        val error = assertThrows(CloudHttpException::class.java) { runBlocking { client(provider = provider).myStats(0) } }

        assertEquals(401, error.status)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun theUploadApiRefreshesARefusedTokenToo() = runBlocking {
        server.enqueue(json("""{"error":{"code":"UNAUTHENTICATED","message":"expired"}}""", 401))
        server.enqueue(json("""{"session":{"sessionId":"s1","cloudStatus":"CREATED"}}"""))
        var token = "old"
        val api = CloudApiClient(
            config,
            object : AuthProvider {
                override suspend fun getToken() = token
                override suspend fun refreshAfterUnauthorized(rejectedToken: String?): Boolean { token = "new"; return true }
            },
            "install-1",
        )

        api.getSession("s1")

        assertEquals("Bearer old", server.takeRequest().getHeader("Authorization"))
        assertEquals("Bearer new", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun noConnectionReadsAsOne() {
        server.shutdown()

        val error = assertThrows(CloudNetworkException::class.java) { runBlocking { client().login("a@b.co", "x") } }

        assertTrue(AuthValidation.messageFor(error).contains("internet"))
    }
}
