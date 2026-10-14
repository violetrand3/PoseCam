package com.posecam.core.cloud

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Executes the call without blocking a thread and cancels it if the coroutine is cancelled. */
internal suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { runCatching { cancel() } }
    enqueue(
        object : Callback {
            override fun onResponse(call: Call, response: Response) {
                if (continuation.isActive) {
                    continuation.resume(response)
                } else {
                    response.close()
                }
            }

            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
        },
    )
}

/**
 * Runs a control-plane call and returns its JSON body. Network trouble becomes [CloudNetworkException]; an error status
 * becomes [CloudHttpException] carrying the server's `{"error": {"code", "message", "details"}}`. Shared by every client
 * of the labeling server so they all fail the same way.
 */
internal suspend fun OkHttpClient.executeJson(request: Request): JSONObject {
    val response = try {
        newCall(request).await()
    } catch (error: IOException) {
        throw CloudNetworkException(error)
    }
    response.use {
        val text = try {
            it.body?.string().orEmpty()
        } catch (error: IOException) {
            throw CloudNetworkException(error)
        }
        if (!it.isSuccessful) throw parseCloudError(it.code, text)
        return try {
            JSONObject(text)
        } catch (error: JSONException) {
            throw CloudHttpException(502, "MALFORMED_RESPONSE", "response is not JSON")
        }
    }
}

/**
 * Both error shapes (the recording endpoints' `{"error":{code,message,details}}` and the marketplace's
 * `{"ok":false,"error":{code,message,fieldErrors}}`) keep `code` and `message` under `error`, so one parser reads both.
 */
internal fun parseCloudError(status: Int, text: String): CloudHttpException {
    val error = runCatching { JSONObject(text).optJSONObject("error") }.getOrNull()
    return CloudHttpException(
        status = status,
        code = error?.optStringOrNull("code"),
        reason = error?.optStringOrNull("message") ?: "request failed",
        details = error?.optJSONObject("details")?.toString(),
        fieldErrors = parseFieldErrors(error?.opt("fieldErrors")),
    )
}

/** `fieldErrors` is a `{field: "message"}` or `{field: ["message", ...]}` object; anything else is ignored. */
internal fun parseFieldErrors(value: Any?): Map<String, String> {
    val json = value as? JSONObject ?: return emptyMap()
    val out = linkedMapOf<String, String>()
    for (key in json.keys()) {
        val entry = json.opt(key)
        val text = when (entry) {
            is String -> entry
            is org.json.JSONArray -> (0 until entry.length()).mapNotNull { entry.optString(it).takeIf { s -> s.isNotBlank() } }.joinToString(" ")
            else -> null
        }
        if (!text.isNullOrBlank()) out[key] = text
    }
    return out
}

/**
 * A marketplace call (`{"ok":true,"data":...}`): returns `data`, which is a [JSONObject], a [org.json.JSONArray] or null.
 * A 2xx answer that says `ok:false` or has no envelope is a backend problem, never a crash in the caller.
 */
internal suspend fun OkHttpClient.executeData(request: Request): Any? = envelopeData(executeJson(request))

internal fun envelopeData(json: JSONObject): Any? {
    if (!json.has("ok") || !json.optBoolean("ok", false)) {
        throw CloudHttpException(502, "MALFORMED_RESPONSE", "response is not a marketplace envelope")
    }
    val data = json.opt("data")
    return if (data == JSONObject.NULL) null else data
}

internal fun JSONObject.optStringOrNull(name: String): String? =
    if (isNull(name)) null else optString(name).takeIf { it.isNotEmpty() }

/**
 * Runs an authenticated control-plane call. [build] makes the request for a given bearer token (null: none). A 401 means
 * the access token was refused: the provider gets one chance to replace it ([AuthProvider.refreshAfterUnauthorized],
 * which shares a single refresh between concurrent callers) and the request is sent again, once, with the new token. A
 * second 401, or a login that is over, is thrown as the 401 it is. A refresh that failed for another reason (offline)
 * is thrown as that retryable error.
 */
internal suspend fun OkHttpClient.executeAuthed(auth: AuthProvider, build: (token: String?) -> Request): JSONObject {
    val token = auth.getToken()
    try {
        return executeJson(build(token))
    } catch (error: CloudHttpException) {
        if (error.status != 401) throw error
        if (!auth.refreshAfterUnauthorized(token)) throw error
    }
    return executeJson(build(auth.getToken()))
}

/** [executeAuthed] for a marketplace endpoint: the envelope's `data`. */
internal suspend fun OkHttpClient.executeAuthedData(auth: AuthProvider, build: (token: String?) -> Request): Any? =
    envelopeData(executeAuthed(auth, build))
