package com.posecam.core.cloud

/**
 * Sends the signed-in collector's access token, keeping it fresh: shortly before it expires ([TokenRefresher.refreshIfExpiring])
 * and after the backend refuses it with 401 ([TokenRefresher.refreshAfterRejection]). When the refresh token itself is
 * refused the collector is signed out, so the app asks for a sign-in.
 */
class UserAuthProvider(
    private val store: AuthStore,
    private val refresher: TokenRefresher,
) : AuthProvider {
    override suspend fun getToken(): String? {
        refresher.refreshIfExpiring()
        return store.token()
    }

    override suspend fun refreshAfterUnauthorized(rejectedToken: String?): Boolean =
        refresher.refreshAfterRejection(rejectedToken)
}
