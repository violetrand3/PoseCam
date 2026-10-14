package com.posecam.core.cloud

import android.content.Context
import org.json.JSONObject

/**
 * `GET /v1/meta`, cached in prefs so the links (terms, privacy, password reset, the website) and the terms version are
 * there with no signal. A value the backend never sent falls back to [fallback] (build-config constants).
 */
class AppMetaStore(
    context: Context,
    private val fallback: AppMeta = AppMeta(null, null, null, null, null, null),
) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** What the backend last said, field by field over the fallback. Never null; fields can be. */
    fun current(): AppMeta {
        val cached = prefs.getString(KEY, null)?.let { runCatching { decode(it) }.getOrNull() }
        return AppMeta(
            termsVersion = cached?.termsVersion ?: fallback.termsVersion,
            termsUrl = cached?.termsUrl ?: fallback.termsUrl,
            privacyUrl = cached?.privacyUrl ?: fallback.privacyUrl,
            passwordResetUrl = cached?.passwordResetUrl ?: fallback.passwordResetUrl,
            webAppUrl = cached?.webAppUrl ?: fallback.webAppUrl,
            appDownloadUrl = cached?.appDownloadUrl ?: fallback.appDownloadUrl,
        )
    }

    /** True once a real answer from the backend is cached (the terms version is only trusted from there). */
    fun hasFetched(): Boolean = prefs.contains(KEY)

    fun save(meta: AppMeta) {
        prefs.edit().putString(KEY, encode(meta)).apply()
    }

    companion object {
        private const val PREFS_NAME = "posecam_meta"
        private const val KEY = "meta_v1"

        internal fun encode(meta: AppMeta): String = JSONObject()
            .put("termsVersion", meta.termsVersion ?: JSONObject.NULL)
            .put("termsUrl", meta.termsUrl ?: JSONObject.NULL)
            .put("privacyUrl", meta.privacyUrl ?: JSONObject.NULL)
            .put("passwordResetUrl", meta.passwordResetUrl ?: JSONObject.NULL)
            .put("webAppUrl", meta.webAppUrl ?: JSONObject.NULL)
            .put("appDownloadUrl", meta.appDownloadUrl ?: JSONObject.NULL)
            .toString()

        internal fun decode(text: String): AppMeta {
            val json = JSONObject(text)
            return AppMeta(
                json.optStringOrNull("termsVersion"), json.optStringOrNull("termsUrl"), json.optStringOrNull("privacyUrl"),
                json.optStringOrNull("passwordResetUrl"), json.optStringOrNull("webAppUrl"), json.optStringOrNull("appDownloadUrl"),
            )
        }
    }
}
