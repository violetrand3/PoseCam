package com.posecam.core.cloud

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CloudApiClientTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() { runCatching { server.shutdown() } }

    private fun client(auth: AuthProvider = object : AuthProvider { override suspend fun getToken(): String? = null }) =
        CloudApiClient(CloudConfig(baseUrl = server.url("/").toString().trimEnd('/')), auth, "install-123")

    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setBody(body)

    private val sessionJson = """{"sessionId":"s1","deviceId":"d","createdAt":"2026-01-01T00:00:00Z","recordingStatus":"recording","cloudStatus":"CREATED","totalFiles":0,"verifiedFiles":0,"totalBytes":0,"verifiedBytes":0,"completedAt":null}"""

    @Test
    fun createSessionPostsTheV1ContractAndSendsNoAuthorizationWithoutAToken() = runBlocking {
        server.enqueue(json("""{"session":$sessionJson,"created":true,"config":{"multipartThresholdBytes":104857600,"partSizeBytes":16777216,"presignedUrlTtlSeconds":900}}"""))

        val response = client().createSession(CreateSessionRequest("s1", "install-123", "2026-01-01T00:00:00Z", "recording", "0.1.0"))

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/sessions", request.path)
        assertNull(request.getHeader("Authorization"))
        assertEquals("install-123", request.getHeader("X-Device-Id"))
        val body = JSONObject(request.body.readUtf8())
        assertEquals("s1", body.getString("sessionId"))
        assertEquals("install-123", body.getString("deviceId"))
        assertEquals("recording", body.getString("recordingStatus"))
        assertTrue(response.created)
        assertEquals(16_777_216L, response.config!!.partSizeBytes)
    }

    @Test
    fun getSessionReadsWhetherTheRecordingReachedTheWebsite() = runBlocking {
        server.enqueue(json("""{"session":${sessionJson.dropLast(1)},"publishStatus":"done","publishedAt":"2026-10-09T10:00:00Z","publishedSets":2,"publishError":null},"files":[]}"""))

        val session = client().getSession("s1")

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/v1/sessions/s1", request.path)
        assertEquals("done", session.publishStatus)
        assertEquals(2, session.publishedSets)
        assertEquals("2026-10-09T10:00:00Z", session.publishedAt)
        assertNull(session.publishError)
    }

    @Test
    fun aBackendThatDoesNotReportPublishingGivesANullStatus() = runBlocking {
        server.enqueue(json("""{"session":$sessionJson,"files":[]}"""))

        val session = client().getSession("s1")

        assertNull(session.publishStatus)
        assertEquals(0, session.publishedSets)
    }

    private val campaignJson = """{"id":"5f0c1a52-3c1e-4e0a-9d6f-0a1b2c3d4e5f","title":"Kitchen tidy","description":"d","instructions":"Record the whole task.","referenceInfo":null,
        "currency":"INR","rewardPerRecording":5000,"rewardPoints":10,"rules":{"minDuration":5,"maxDuration":600,"orientation":"PORTRAIT"},"collectRaw":false,
        "target":50,"remaining":38,"endDate":"2026-11-30T00:00:00.000Z","enrolled":true,"enrolledAt":"2026-10-01T09:00:00.000Z","acceptingUploads":true,"status":"ACTIVE",
        "progress":{"recorded":12,"inReview":2,"approved":9,"rejected":1,"notCounted":0,"earned":45000}}"""

    @Test
    fun listCampaignsReadsTheMarketplaceEnvelopeOfMyCampaigns() = runBlocking {
        server.enqueue(json("""{"ok":true,"data":[$campaignJson]}"""))

        val campaigns = client().listCampaigns()

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/v1/me/campaigns", request.path)
        assertEquals("install-123", request.getHeader("X-Device-Id"))
        val c = campaigns.single()
        assertEquals("5f0c1a52-3c1e-4e0a-9d6f-0a1b2c3d4e5f", c.id)
        assertEquals("Kitchen tidy", c.title)
        assertEquals(5000L, c.rewardPerRecording)
        assertFalse(c.collectRaw)
        assertEquals(38, c.remaining)
        assertEquals("2026-11-30T00:00:00.000Z", c.endDate)
        assertEquals(12, c.progress.recorded)
        assertEquals(9, c.progress.approved)
        assertEquals(45000L, c.progress.earned)
        assertTrue(c.canRecord)
    }

    @Test
    fun anEmptyCampaignListIsARealAnswer() = runBlocking {
        server.enqueue(json("""{"ok":true,"data":[]}"""))

        assertTrue(client().listCampaigns().isEmpty())
    }

    @Test
    fun aBodyWithoutTheEnvelopeIsAMalformedResponse() {
        server.enqueue(json("""[{"id":"x"}]"""))
        server.enqueue(json("""{"ok":false,"data":[]}"""))

        repeat(2) {
            val error = assertThrows(CloudHttpException::class.java) { runBlocking { client().listCampaigns() } }
            assertEquals("MALFORMED_RESPONSE", error.code)
        }
    }

    @Test
    fun aStoppedOrFullCampaignCannotBeRecordedFor() = runBlocking {
        server.enqueue(json("""{"ok":true,"data":[${campaignJson.replace("\"remaining\":38", "\"remaining\":0")},${campaignJson.replace("\"status\":\"ACTIVE\"", "\"status\":\"PAUSED\"")}]}"""))

        val campaigns = client().listCampaigns()

        assertFalse(campaigns[0].canRecord)
        assertFalse(campaigns[1].canRecord)
    }

    @Test
    fun createSessionSendsTheCampaignTheDeviceFingerprintAndTheTake() = runBlocking {
        server.enqueue(json("""{"session":$sessionJson,"created":true,"requirements":{"rawRequired":false}}"""))
        val take = TakeInfo("good", listOf("3 frame(s) without tracking, short enough to be filled in."), 152.3, 0, 2)

        val response = client().createSession(
            CreateSessionRequest("s1", "d", "2026-01-01T00:00:00Z", "complete", "0.4.0", campaignId = "c-1", deviceFingerprint = "samsung/SM-G781B", take = take),
        )

        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("c-1", body.getString("campaignId"))
        assertFalse(body.has("pipe")) // replaced by campaignId
        assertEquals("samsung/SM-G781B", body.getString("deviceFingerprint"))
        assertEquals("0.4.0", body.getString("appVersion"))
        val sent = body.getJSONObject("take")
        assertEquals("good", sent.getString("verdict"))
        assertEquals("3 frame(s) without tracking, short enough to be filled in.", sent.getJSONArray("reasons").getString(0))
        assertEquals(152.3, sent.getDouble("durationSeconds"), 0.0)
        assertEquals(0, sent.getInt("poseJumps"))
        assertEquals(2, sent.getInt("longestTrackingLossFrames"))
        assertEquals(false, response.rawRequired)
    }

    @Test
    fun createSessionOmitsWhatItDoesNotKnowAndReadsNoRequirementsAsNull() = runBlocking {
        server.enqueue(json("""{"session":$sessionJson,"created":true}"""))

        val response = client().createSession(CreateSessionRequest("s1", "d", "2026-01-01T00:00:00Z", "complete", "0.3.0"))

        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertFalse(body.has("campaignId") || body.has("deviceFingerprint") || body.has("take"))
        assertNull(response.rawRequired)
    }

    @Test
    fun theCompleteAnswerCarriesWhatTheMarketplaceMadeOfTheRecording() = runBlocking {
        server.enqueue(json("""{"session":${sessionJson.dropLast(1)},"marketplace":{"status":"NOT_COUNTED","reason":"CAMPAIGN_FULL","submissionId":null}}}"""))
        server.enqueue(json("""{"session":${sessionJson.dropLast(1)},"marketplace":{"status":"CHECKING","reason":null,"submissionId":"sub-1"}}}"""))
        val api = client()

        val full = api.completeSession("s1", "complete", listOf(SessionFileRef("manifest.json", 1)))
        val checking = api.completeSession("s1", "complete", listOf(SessionFileRef("manifest.json", 1)))

        assertEquals(MarketplaceInfo("NOT_COUNTED", "CAMPAIGN_FULL", null), full.marketplace)
        assertTrue(full.marketplace!!.notCounted)
        assertEquals(MarketplaceInfo("CHECKING", null, "sub-1"), checking.marketplace)
        assertNull(checking.publishStatus) // the marketplace does not report publishing
    }

    @Test
    fun theCampaignRefusalsArePermanentWithPlainWords() = runBlocking {
        server.enqueue(json("""{"error":{"code":"NOT_ENROLLED","message":"x"}}""", 403))
        server.enqueue(json("""{"error":{"code":"CAMPAIGN_NOT_FOUND","message":"x"}}""", 404))
        server.enqueue(json("""{"error":{"code":"CAMPAIGN_CLOSED","message":"x"}}""", 409))
        server.enqueue(json("""{"error":{"code":"TAKE_NOT_ELIGIBLE","message":"x"}}""", 409))
        server.enqueue(json("""{"ok":false,"error":{"code":"FORBIDDEN","message":"nope"}}""", 403))
        val api = client()

        for (code in listOf("NOT_ENROLLED", "CAMPAIGN_NOT_FOUND", "CAMPAIGN_CLOSED", "TAKE_NOT_ELIGIBLE")) {
            val error = assertThrows(CloudHttpException::class.java) {
                runBlocking { api.createSession(CreateSessionRequest("s1", "d", "t", "complete", null, campaignId = "c")) }
            }
            assertEquals(code, error.code)
            assertFalse("$code must not be retried", error.retryable)
            assertNotEquals("x", error.userMessage) // plain words, not the server's terse text
        }
        // A 403 the app does not know stays what it was.
        val other = assertThrows(CloudHttpException::class.java) { runBlocking { api.getSession("s1") } }
        assertTrue(other.retryable)
    }

    @Test
    fun anExpiredTokenIsRefreshedOnceForTheUploadCallsAndTheCallRetriedOnce() = runBlocking {
        server.enqueue(json("""{"error":{"code":"UNAUTHENTICATED","message":"expired"}}""", 401))
        server.enqueue(json("""{"session":$sessionJson,"created":false}"""))
        var token = "old"
        var refreshes = 0
        val auth = object : AuthProvider {
            override suspend fun getToken() = token
            override suspend fun refreshAfterUnauthorized(rejectedToken: String?): Boolean { refreshes++; token = "new"; return true }
        }

        client(auth).createSession(CreateSessionRequest("s1", "d", "t", "complete", null))

        assertEquals("Bearer old", server.takeRequest().getHeader("Authorization"))
        assertEquals("Bearer new", server.takeRequest().getHeader("Authorization"))
        assertEquals(1, refreshes)
    }

    @Test
    fun addsABearerTokenOnlyWhenTheAuthProviderReturnsOne() = runBlocking {
        server.enqueue(json("""{"session":$sessionJson,"created":false}"""))
        server.enqueue(json("""{"session":$sessionJson,"created":false}"""))
        val request = CreateSessionRequest("s1", "d", "t", "recording", null)

        client(auth = object : AuthProvider { override suspend fun getToken() = "tok-abc" }).createSession(request)
        client(auth = object : AuthProvider { override suspend fun getToken(): String? = null }).createSession(request)

        assertEquals("Bearer tok-abc", server.takeRequest().getHeader("Authorization"))
        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun parsesSingleAndMultipartPresignResponses() = runBlocking {
        server.enqueue(json("""{"relativePath":"manifest.json","mode":"SINGLE","url":"https://s3.example/x?sig=1","method":"PUT","headers":{"Content-Type":"application/json","x-amz-checksum-sha256":"abc="},"expiresInSeconds":900,"partSizeBytes":null}"""))
        server.enqueue(json("""{"relativePath":"segment-00001.rjmj","mode":"MULTIPART","url":null,"method":"PUT","headers":{},"expiresInSeconds":900,"partSizeBytes":16777216}"""))
        val api = client()

        val single = api.requestUpload("s1", UploadRequest("manifest.json", 10, "ab".repeat(32)))
        val multi = api.requestUpload("s1", UploadRequest("segment-00001.rjmj", 200_000_000, "cd".repeat(32)))

        assertEquals(UploadMode.SINGLE, single.mode)
        assertEquals("https://s3.example/x?sig=1", single.url)
        assertEquals("abc=", single.headers["x-amz-checksum-sha256"])
        assertEquals(UploadMode.MULTIPART, multi.mode)
        assertNull(multi.url)
        assertEquals(16_777_216L, multi.partSizeBytes)
        val sent = server.takeRequest()
        assertEquals("/v1/sessions/s1/uploads/presign", sent.path)
        val body = JSONObject(sent.body.readUtf8())
        assertEquals("manifest.json", body.getString("relativePath"))
        assertEquals(10L, body.getLong("sizeBytes"))
        assertEquals("ab".repeat(32), body.getString("sha256"))
        // Android never sends a bucket or an S3 key.
        assertFalse(body.has("bucket") || body.has("key") || body.has("s3Key"))
    }

    @Test
    fun multipartEndpointsUseTheContractPathsAndBodies() = runBlocking {
        server.enqueue(json("""{"relativePath":"p","uploadId":"U","partSizeBytes":4,"partCount":2,"resumed":true,"uploadedParts":[{"partNumber":1,"sizeBytes":4,"etag":"\"e\"","checksumSha256":"c="}]}"""))
        server.enqueue(json("""{"parts":[{"partNumber":2,"url":"https://s3/p2","headers":{"x-amz-checksum-sha256":"d="},"expiresInSeconds":900}]}"""))
        server.enqueue(json("""{"relativePath":"p","state":"UPLOADED","sizeBytes":8}"""))
        server.enqueue(json("""{"relativePath":"p","state":"VERIFIED","sizeBytes":8,"verifiedAt":"2026-01-01T00:00:00Z"}"""))
        server.enqueue(json("""{"session":$sessionJson}"""))
        val api = client()

        val start = api.startMultipartUpload("s1", UploadRequest("p", 8, "00".repeat(32)))
        val urls = api.getMultipartPartUrls("s1", "p", "U", listOf(PartUrlRequest(2, 4, "d=")))
        val done = api.completeMultipartUpload("s1", "p", "U", listOf(CompletedPart(1, "\"e\"", "c="), CompletedPart(2, "\"f\"", "d=")))
        val verified = api.verifyUpload("s1", "p")
        api.completeSession("s1", "complete", listOf(SessionFileRef("p", 8)))

        assertEquals("U", start.uploadId)
        assertTrue(start.resumed)
        assertEquals("\"e\"", start.uploadedParts.single().etag)
        assertEquals("https://s3/p2", urls.single().url)
        assertEquals("UPLOADED", done.state)
        assertEquals("VERIFIED", verified.state)
        assertEquals("/v1/sessions/s1/uploads/multipart/start", server.takeRequest().path)
        val partsReq = server.takeRequest()
        assertEquals("/v1/sessions/s1/uploads/multipart/parts", partsReq.path)
        assertEquals(2, JSONObject(partsReq.body.readUtf8()).getJSONArray("parts").getJSONObject(0).getInt("partNumber"))
        val completeReq = server.takeRequest()
        assertEquals("/v1/sessions/s1/uploads/multipart/complete", completeReq.path)
        assertEquals(2, JSONObject(completeReq.body.readUtf8()).getJSONArray("parts").length())
        assertEquals("/v1/sessions/s1/uploads/verify", server.takeRequest().path)
        val sessionReq = server.takeRequest()
        assertEquals("/v1/sessions/s1/complete", sessionReq.path)
        assertEquals("complete", JSONObject(sessionReq.body.readUtf8()).getString("recordingStatus"))
    }

    @Test
    fun mapsErrorBodiesToTypedExceptionsWithRetryability() = runBlocking {
        server.enqueue(json("""{"error":{"code":"VERIFICATION_FAILED","message":"size","retryable":false,"details":{"reason":"SIZE_MISMATCH"}}}""", 409))
        server.enqueue(json("""{"error":{"code":"INVALID_PATH","message":"bad","retryable":false}}""", 400))
        server.enqueue(json("upstream", 503))
        server.enqueue(json("{}", 429))
        val api = client()

        val verification = assertThrows(CloudHttpException::class.java) { runBlocking { api.verifyUpload("s1", "p") } }
        assertEquals(409, verification.status)
        assertEquals("VERIFICATION_FAILED", verification.code)
        assertTrue(verification.details!!.contains("SIZE_MISMATCH"))
        assertFalse(verification.retryable)

        assertFalse(assertThrows(CloudHttpException::class.java) { runBlocking { api.verifyUpload("s1", "p") } }.retryable)
        assertTrue(assertThrows(CloudHttpException::class.java) { runBlocking { api.verifyUpload("s1", "p") } }.retryable)
        assertTrue(assertThrows(CloudHttpException::class.java) { runBlocking { api.verifyUpload("s1", "p") } }.retryable)
    }

    @Test
    fun anUnreachableServerIsARetryableNetworkException() {
        val api = client()
        server.shutdown()

        val error = assertThrows(CloudNetworkException::class.java) {
            runBlocking { api.verifyUpload("s1", "p") }
        }
        assertTrue(error.retryable)
    }

    @Test
    fun aResponseWithTheWrongShapeIsAMalformedResponseNotACrash() {
        server.enqueue(json("""{"surprise":true}"""))

        val error = assertThrows(CloudHttpException::class.java) {
            runBlocking { client().startMultipartUpload("s1", UploadRequest("p", 8, "00".repeat(32))) }
        }
        assertEquals("MALFORMED_RESPONSE", error.code)
    }
}
