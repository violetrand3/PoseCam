package com.posecam.core.cloud

/**
 * Supplies the bearer token attached to backend API calls. This is the single seam for
 * authentication: [UserAuthProvider] hands over the signed-in collector's token, and nothing else (the upload worker,
 * the repository, recording) knows about it -- [CloudApiClient] and [AccountApiClient] simply add
 * `Authorization: Bearer <token>` whenever a token is returned, and retry a request once after a 401 if
 * [refreshAfterUnauthorized] says a fresh token is available.
 *
 * The token is only ever sent to the backend API. It is never sent to S3: presigned URLs
 * carry their own signature and an extra Authorization header would invalidate them.
 */
interface AuthProvider {
    /** The token to send now (refreshed first if it is about to expire), or null when nobody is signed in. */
    suspend fun getToken(): String?

    /**
     * The backend answered 401 UNAUTHENTICATED to a request sent with [rejectedToken] (null: sent with none). Returns
     * true when a fresh token is stored and the request may be retried once; false when the login is over. Throws a
     * [CloudException] when the refresh itself failed in a way that does not end the login (offline, a server error):
     * that is retryable and must not sign anyone out.
     */
    suspend fun refreshAfterUnauthorized(rejectedToken: String?): Boolean {
        onUnauthorized()
        return false
    }

    /** The token was refused and cannot be replaced. Providers that cannot refresh forget it here. */
    fun onUnauthorized() = Unit
}
