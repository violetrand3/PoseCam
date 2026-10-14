package com.posecam.core.cloud

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONException
import org.json.JSONObject

/**
 * The marketplace's unauthenticated calls: `auth/register`, `auth/login`, `auth/refresh`, `auth/logout` and `meta`. They
 * carry no `Authorization` header (the refresh token is the credential), which is also why [TokenRefresher] can use them
 * without going through the clients that would try to refresh on a 401. Never logs a password or a token.
 */
class AuthEndpoints(
    config: CloudConfig,
    private val installationId: String,
    private val client: OkHttpClient = CloudApiClient.defaultClient(config),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val baseUrl: HttpUrl = config.normalizedBaseUrl.toHttpUrl()

    suspend fun register(email: String, password: String, name: String?, termsVersion: String): AuthSession {
        val body = JSONObject()
            .put("email", email)
            .put("password", password)
            .put("role", ROLE_CONTRIBUTOR)
            .put("termsVersion", termsVersion)
        if (!name.isNullOrBlank()) body.put("name", name.trim())
        return session(post("auth/register", body))
    }

    suspend fun login(email: String, password: String): AuthSession =
        session(post("auth/login", JSONObject().put("email", email).put("password", password)))

    /** Trades a refresh token for a new pair. 401 means the login is over. */
    suspend fun refresh(refreshToken: String): AuthSession =
        session(post("auth/refresh", JSONObject().put("refreshToken", refreshToken)))

    /** Idempotent on the backend. */
    suspend fun logout(refreshToken: String) {
        post("auth/logout", JSONObject().put("refreshToken", refreshToken))
    }

    suspend fun meta(): AppMeta {
        val data = client.executeData(requestFor("meta").get().build()) as? JSONObject
            ?: throw CloudHttpException(502, "MALFORMED_RESPONSE", "no meta")
        return AppMeta(
            termsVersion = data.optStringOrNull("termsVersion"),
            termsUrl = data.optStringOrNull("termsUrl"),
            privacyUrl = data.optStringOrNull("privacyUrl"),
            passwordResetUrl = data.optStringOrNull("passwordResetUrl"),
            webAppUrl = data.optStringOrNull("webAppUrl"),
            appDownloadUrl = data.optStringOrNull("appDownloadUrl"),
        )
    }

    private suspend fun post(path: String, body: JSONObject): Any? =
        client.executeData(requestFor(path).post(body.toString().toRequestBody(JSON_MEDIA_TYPE)).build())

    private fun requestFor(path: String): Request.Builder {
        val url = baseUrl.newBuilder().addPathSegment("v1").apply { path.split('/').forEach { addPathSegment(it) } }.build()
        return Request.Builder().url(url).header("Accept", "application/json").header("X-Device-Id", installationId)
    }

    /** `{user, tokens}` to the app's session: the access token's expiry is now + `expiresIn`. */
    private fun session(data: Any?): AuthSession = try {
        val json = data as? JSONObject ?: throw JSONException("no data")
        val user = json.getJSONObject("user")
        val tokens = json.getJSONObject("tokens")
        AuthSession(
            accountId = user.getString("id"),
            email = user.getString("email"),
            name = user.optStringOrNull("name"),
            token = tokens.getString("accessToken"),
            expiresAtMs = clock() + tokens.getLong("expiresIn") * 1000,
            refreshToken = tokens.getString("refreshToken"),
        )
    } catch (error: JSONException) {
        // A bad shape is the server's fault; the caller sees one error type either way.
        throw CloudHttpException(502, "MALFORMED_RESPONSE", "unexpected response shape")
    }

    private companion object {
        const val ROLE_CONTRIBUTOR = "CONTRIBUTOR"
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
