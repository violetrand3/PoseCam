package com.posecam.core.cloud

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A signed-in collector: who they are and the tokens that speak for them. The marketplace issues a short-lived access
 * token (15 minutes) and a refresh token (30 days) that is rotated on every use. Neither is ever logged.
 */
data class AuthSession(
    /** The marketplace user id (`user.id`): what every queued recording is bound to. */
    val accountId: String,
    val email: String,
    val name: String?,
    /** The bearer token attached to API calls. */
    val token: String,
    /** Epoch millis after which the backend refuses [token] (now + `expiresIn` when it was issued). */
    val expiresAtMs: Long,
    /** Trades for a new token pair. Single use: the backend revokes the whole login if one is used twice. */
    val refreshToken: String,
) {
    // A data class would print the tokens; this keeps them out of logs and crash reports.
    override fun toString(): String = "AuthSession(accountId=$accountId, email=$email)"
}

/** What the app knows about the collector. [session] is null when nobody is signed in (or the login was revoked). */
data class AuthState(
    val session: AuthSession?,
    /** The last email used on this phone, kept after sign-out to prefill the sign-in form. */
    val lastEmail: String?,
    /** The last account that was signed in, kept after sign-out: recordings made while signed out still belong to it. */
    val lastAccountId: String? = null,
) {
    val isSignedIn: Boolean get() = session != null

    /** True once someone has signed in on this install: from then on recording never waits for a sign-in screen. */
    val hasEverSignedIn: Boolean get() = session != null || lastEmail != null
}

/**
 * Where the tokens live: the app's private SharedPreferences (no backup: `allowBackup=false`), both in the same file.
 * The password is never stored. Signing out forgets the tokens but not the email, the account id, the recordings or
 * their upload queue, which belong to the phone, not to the session.
 *
 * An access token that ran out is NOT a sign-out: the refresh token can still get a new one (see [TokenRefresher]).
 * The collector is signed out only when the refresh token is refused, or on request.
 */
class AuthStore(
    context: Context,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())

    val state: StateFlow<AuthState> = _state.asStateFlow()

    val isSignedIn: Boolean get() = current().isSignedIn

    /** The access token to send, or null when signed out. It may be about to expire (or have expired): see [isExpiring]. */
    fun token(): String? = current().session?.token

    /** The signed-in account's id, or null when signed out. */
    fun accountId(): String? = current().session?.accountId

    fun current(): AuthState = _state.value

    /** True when the access token has [skewMs] or less left; a signed-out store has nothing to refresh. */
    fun isExpiring(skewMs: Long): Boolean {
        val session = current().session ?: return false
        return session.expiresAtMs - clock() <= skewMs
    }

    /** A fresh sign-in or sign-up. */
    @Synchronized
    fun signIn(session: AuthSession) {
        write(session)
        _state.value = AuthState(session, session.email, session.accountId)
    }

    /**
     * The refresh trade succeeded: replace the whole token pair (the old refresh token is dead). Only applies while
     * somebody is signed in, so a refresh that finishes after a sign-out cannot sign them back in.
     */
    @Synchronized
    fun replaceTokens(token: String, refreshToken: String, expiresAtMs: Long, name: String? = null): Boolean {
        val session = _state.value.session ?: return false
        val next = session.copy(token = token, refreshToken = refreshToken, expiresAtMs = expiresAtMs, name = name ?: session.name)
        write(next)
        _state.value = _state.value.copy(session = next)
        return true
    }

    /** The collector chose to sign out, or the refresh token was refused: the tokens go, the email and account id stay. */
    @Synchronized
    fun signOut() {
        prefs.edit().remove(KEY_TOKEN).remove(KEY_REFRESH).remove(KEY_NAME).remove(KEY_EXPIRES).apply()
        _state.value = _state.value.copy(session = null)
    }

    private fun write(session: AuthSession) {
        prefs.edit()
            .putString(KEY_TOKEN, session.token)
            .putString(KEY_REFRESH, session.refreshToken)
            .putString(KEY_ACCOUNT, session.accountId)
            .putString(KEY_EMAIL, session.email)
            .putString(KEY_NAME, session.name)
            .putLong(KEY_EXPIRES, session.expiresAtMs)
            .apply()
    }

    private fun load(): AuthState {
        val email = prefs.getString(KEY_EMAIL, null)?.takeIf { it.isNotBlank() }
        val account = prefs.getString(KEY_ACCOUNT, null)?.takeIf { it.isNotBlank() }
        val token = prefs.getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() }
        val refresh = prefs.getString(KEY_REFRESH, null)?.takeIf { it.isNotBlank() }
        // A login from before the marketplace has no refresh token or account id: it cannot be used, so it is a sign-out.
        val session = if (email != null && account != null && token != null && refresh != null) {
            AuthSession(account, email, prefs.getString(KEY_NAME, null), token, prefs.getLong(KEY_EXPIRES, 0), refresh)
        } else {
            null
        }
        return AuthState(session, email, account)
    }

    private companion object {
        const val PREFS_NAME = "posecam_auth"
        const val KEY_TOKEN = "token"
        const val KEY_REFRESH = "refresh_token"
        const val KEY_ACCOUNT = "account_id"
        const val KEY_EMAIL = "email"
        const val KEY_NAME = "name"
        const val KEY_EXPIRES = "expires_at_ms"
    }
}
