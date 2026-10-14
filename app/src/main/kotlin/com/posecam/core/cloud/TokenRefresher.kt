package com.posecam.core.cloud

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Keeps the access token alive. Refresh tokens ROTATE: each is good for one trade, and trading one twice makes the
 * backend revoke the whole login. So there is exactly one refresh in flight at any time ([lock]), shared by every caller
 * (the upload queue, the account screens, the campaign list): the callers that arrive while one runs wait for it and use
 * its result instead of trading the same refresh token again.
 *
 * Outcomes of a refresh:
 *  * the new pair is stored and the caller retries;
 *  * the backend answers 401: the login is over, so the collector is signed out (tokens gone; email, recordings and the
 *    queue stay);
 *  * anything else (no network, a 5xx, a 429): nothing changes and the failure is thrown as the retryable error it is.
 *    Being offline never signs anyone out.
 */
class TokenRefresher(
    private val store: AuthStore,
    private val endpoints: AuthEndpoints,
) {
    private val lock = Mutex()

    /**
     * Before a call or a queue pass: refreshes when the access token has [skewMs] or less left. Best effort -- a failure
     * is swallowed here, because the token may still be good and a 401 will bring the real answer.
     */
    suspend fun refreshIfExpiring(skewMs: Long = EXPIRY_SKEW_MS) {
        if (!store.isExpiring(skewMs)) return
        try {
            lock.withLock {
                // Somebody else may have refreshed while this one waited for the lock.
                if (!store.isExpiring(skewMs)) return
                refreshLocked()
            }
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (ignored: CloudException) {
            CloudLog.i("token_refresh_deferred", "reason" to ignored.javaClass.simpleName)
        }
    }

    /**
     * The backend refused [rejectedToken] with 401. Returns true when a usable token is now stored (retry the request
     * once), false when the login is over. Throws the [CloudException] of a failed refresh that did not end the login.
     */
    suspend fun refreshAfterRejection(rejectedToken: String?): Boolean = lock.withLock {
        val session = store.current().session ?: return false
        // The token that was refused is no longer the stored one: another caller already traded it. Use theirs.
        if (rejectedToken != null && session.token != rejectedToken) return true
        refreshLocked()
    }

    /** Caller holds [lock]. */
    private suspend fun refreshLocked(): Boolean {
        val session = store.current().session ?: return false
        val fresh = try {
            endpoints.refresh(session.refreshToken)
        } catch (error: CloudHttpException) {
            if (error.status == 401) {
                CloudLog.w("token_refresh_refused", "status" to error.status)
                store.signOut()
                return false
            }
            throw error
        }
        val stored = store.replaceTokens(fresh.token, fresh.refreshToken, fresh.expiresAtMs, fresh.name)
        if (stored) CloudLog.i("token_refreshed")
        return stored
    }

    companion object {
        /** Refresh this long before the access token runs out, so a long call does not start with a token about to die. */
        const val EXPIRY_SKEW_MS = 60_000L
    }
}
