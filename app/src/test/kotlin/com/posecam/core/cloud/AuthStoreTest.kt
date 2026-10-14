package com.posecam.core.cloud

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AuthStoreTest {
    private lateinit var context: Context
    private var now = 1_000L

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("posecam_auth", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("posecam_stats", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun store() = AuthStore(context) { now }
    private fun session(expires: Long = 10_000, token: String = "tok", refresh: String = "ref") =
        AuthSession("acct-1", "a@b.co", "Asha", token, expires, refresh)

    @Test fun startsSignedOutAndNeverSignedIn() {
        val state = store().current()
        assertFalse(state.isSignedIn)
        assertFalse(state.hasEverSignedIn)
        assertNull(store().accountId())
    }

    @Test fun signInSurvivesARestartWithBothTokensAndTheAccount() {
        store().signIn(session())

        val again = store()
        assertTrue(again.isSignedIn)
        assertEquals("tok", again.token())
        assertEquals("ref", again.current().session!!.refreshToken)
        assertEquals("acct-1", again.accountId())
        assertEquals("a@b.co", again.current().session!!.email)
        assertEquals(10_000L, again.current().session!!.expiresAtMs)
    }

    @Test fun bothTokensLiveInTheSamePrivatePreferencesFile() {
        store().signIn(session())

        val prefs = context.getSharedPreferences("posecam_auth", Context.MODE_PRIVATE)
        assertEquals("tok", prefs.getString("token", null))
        assertEquals("ref", prefs.getString("refresh_token", null))
    }

    @Test fun anAccessTokenThatRanOutIsNotASignOutBecauseTheRefreshTokenCanReplaceIt() {
        val store = store()
        store.signIn(session(expires = 5_000))

        now = 5_000

        assertTrue(store.isSignedIn)
        assertTrue(store.isExpiring(0))
        assertEquals("ref", store.current().session!!.refreshToken)
    }

    @Test fun isExpiringLooksAheadByTheSkew() {
        val store = store()
        store.signIn(session(expires = 100_000))

        now = 30_000
        assertFalse(store.isExpiring(60_000)) // 70 s left
        now = 45_000
        assertTrue(store.isExpiring(60_000)) // 55 s left
    }

    @Test fun replacingTheTokensKeepsTheAccountAndStoresTheNewPair() {
        val store = store()
        store.signIn(session())

        assertTrue(store.replaceTokens("tok2", "ref2", 99_000))

        val again = store()
        assertEquals("tok2", again.token())
        assertEquals("ref2", again.current().session!!.refreshToken)
        assertEquals("acct-1", again.accountId())
    }

    @Test fun aRefreshThatFinishesAfterASignOutCannotSignThemBackIn() {
        val store = store()
        store.signIn(session())
        store.signOut()

        assertFalse(store.replaceTokens("tok2", "ref2", 99_000))

        assertFalse(store.isSignedIn)
        assertNull(store.token())
    }

    @Test fun signOutForgetsBothTokensButKeepsTheEmailAndTheAccount() {
        val store = store()
        store.signIn(session())

        store.signOut()

        assertNull(store.token())
        assertNull(store.accountId())
        assertEquals("a@b.co", store.current().lastEmail)
        assertEquals("acct-1", store.current().lastAccountId) // a recording made while logged out is still theirs
        assertTrue(store.current().hasEverSignedIn)
        val again = store()
        assertNull(again.token()) // and it stays gone after a restart
        assertEquals("acct-1", again.current().lastAccountId)
        assertNull(context.getSharedPreferences("posecam_auth", Context.MODE_PRIVATE).getString("refresh_token", null))
    }

    @Test fun aLoginFromBeforeTheMarketplaceHasNoRefreshTokenAndIsASignOut() {
        context.getSharedPreferences("posecam_auth", Context.MODE_PRIVATE).edit()
            .putString("token", "old-jwt").putString("email", "a@b.co").putLong("expires_at_ms", 9_999_999).commit()

        val store = store()

        assertFalse(store.isSignedIn)
        assertEquals("a@b.co", store.current().lastEmail)
    }

    @Test fun aDaysListIsKeptPerAccountAndOldDaysAreDropped() {
        val cache = UploadStatsCache(context)
        val one = listOf(UploadedRecording("capture-20261009T100000-aaaaaa", "c-1", "Kitchen", "x", "2026-10-09", "uploaded", "IN_REVIEW"))
        cache.saveDay("a@b.co", "2026-10-09", one)

        assertEquals(one, cache.loadDay("a@b.co", "2026-10-09"))
        assertNull(cache.loadDay("someone@else.com", "2026-10-09"))
        assertNull(cache.loadDay("a@b.co", "2026-10-08"))

        // Forty more days: only the 30 most recent stay.
        for (i in 1..40) cache.saveDay("a@b.co", "2026-08-%02d".format(i.coerceAtMost(31)).let { if (i > 31) "2026-07-%02d".format(i - 31) else it }, one)
        assertEquals(one, cache.loadDay("a@b.co", "2026-10-09"))
        assertNull(cache.loadDay("a@b.co", "2026-07-01"))
    }

    @Test fun statsAreOnlyShownToTheirOwner() {
        val cache = UploadStatsCache(context)
        val stats = UploadStats(
            "a@b.co", 12, 3, "2026-09-01T05:00:00.000Z",
            listOf(DayCount("2026-10-09", 3, mapOf("c-1" to 3)), DayCount("2026-10-08", 9, mapOf("c-1" to 5, "c-2" to 4))),
        )
        cache.save(stats, 42)

        assertEquals(stats, cache.load("a@b.co")!!.stats)
        assertEquals(42L, cache.load("a@b.co")!!.savedAtMs)
        assertNull(cache.load("someone@else.com"))
    }

    @Test fun aCachedCopyWrittenByTheOldAppStillLoadsWithoutCrashing() {
        val old = """{"savedAtMs":7,"email":"a@b.co","totalRecordings":2,"uploadedToday":1,"firstUploadAt":null,
            "days":[{"date":"2026-10-09","recordings":2,"pipes":{"white":2},"live":1,"publishing":1,"failed":0}]}"""
        context.getSharedPreferences("posecam_stats", Context.MODE_PRIVATE).edit().putString("stats", old)
            .putString("day:a@b.co:2026-10-09", """[{"sessionId":"capture-20261009T100000-aaaaaa","pipe":"white","pipeLabel":"White pipes","uploadedAt":"x","date":"2026-10-09","status":"live"}]""")
            .commit()
        val cache = UploadStatsCache(context)

        val stats = cache.load("a@b.co")!!.stats
        val day = cache.loadDay("a@b.co", "2026-10-09")!!.single()

        assertEquals(mapOf("white" to 2), stats.days.single().campaigns)
        assertEquals("white", day.campaignId)
        assertEquals("White pipes", day.campaignTitle)
        assertNull(day.marketplaceStatus)
    }
}
