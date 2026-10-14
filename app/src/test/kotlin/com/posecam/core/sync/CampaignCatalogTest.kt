package com.posecam.core.sync

import com.posecam.core.cloud.CloudNetworkException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class CampaignCatalogTest {
    private class MemoryStore(var json: String? = null, var active: String? = null) : CampaignStore {
        override fun load() = json
        override fun save(json: String) { this.json = json }
        override fun loadActiveId() = active
        override fun saveActiveId(id: String?) { active = id }
    }

    private val api = FakeCloudApi()
    private val store = MemoryStore()
    private val catalog = CampaignCatalog(api, store)
    private val me = SyncFixture.ACCOUNT

    private val kitchen = SyncFixture.campaign(SyncFixture.CAMPAIGN, "Kitchen tidy")
    private val laundry = SyncFixture.campaign(SyncFixture.CAMPAIGN_2, "Laundry")

    @Test fun beforeTheFirstFetchThereAreNoCampaignsAndNothingToRecordFor() {
        assertTrue(catalog.current(me).isEmpty())
        assertNull(catalog.active(me))
        assertTrue(Pipe.DEFAULTS.isEmpty()) // the LabelNow white / black pipes are gone
    }

    @Test fun aFetchedListIsCachedForTheAccountAndSurvivesARestart() = runBlocking {
        api.campaignsOnServer = listOf(kitchen, laundry)

        assertTrue(catalog.refresh(me))

        assertEquals(listOf(SyncFixture.CAMPAIGN, SyncFixture.CAMPAIGN_2), catalog.current(me).map { it.id })
        // A new catalog over the same store is the next app launch, with no network.
        val again = CampaignCatalog(FakeCloudApi(), store).current(me)
        assertEquals(listOf("Kitchen tidy", "Laundry"), again.map { it.title })
        assertEquals(kitchen.progress, again[0].progress)
        assertEquals(kitchen.rewardPerRecording, again[0].rewardPerRecording)
        assertEquals(kitchen.collectRaw, again[0].collectRaw)
        assertEquals("Kitchen tidy", catalog.title(me, SyncFixture.CAMPAIGN))
        assertNull(catalog.title(me, "unknown"))
    }

    @Test fun anotherAccountNeverSeesTheCampaignsOfTheFirst() = runBlocking {
        catalog.refresh(me)

        assertTrue(catalog.current(SyncFixture.OTHER_ACCOUNT).isEmpty())
        assertTrue(catalog.current(null).isEmpty())
        assertNull(catalog.active(SyncFixture.OTHER_ACCOUNT))
    }

    @Test fun aFailedFetchKeepsWhatWasCachedSoRecordingWorksOffline() = runBlocking {
        api.campaignsOnServer = listOf(kitchen, laundry)
        catalog.refresh(me)
        api.failOnce("listCampaigns", CloudNetworkException(IOException("offline")))

        assertFalse(catalog.refresh(me))

        assertEquals(2, catalog.current(me).size)
    }

    @Test fun anEmptyAnswerIsARealAnswerAndClearsTheCacheAndTheChoice() = runBlocking {
        api.campaignsOnServer = listOf(kitchen)
        catalog.refresh(me)
        catalog.setActive(kitchen)
        api.campaignsOnServer = emptyList()

        assertTrue(catalog.refresh(me))

        assertTrue(catalog.current(me).isEmpty())
        assertNull(catalog.active(me))
        assertNull(store.active)
    }

    @Test fun anUnreadableCacheIsEmptyNotACrash() {
        store.json = "this is not json"
        assertTrue(catalog.current(me).isEmpty())
        store.json = """{"accountId":"$me"}"""
        assertTrue(catalog.current(me).isEmpty())
    }

    @Test fun entriesThatAreNotCampaignIdsAreDropped() = runBlocking {
        api.campaignsOnServer = listOf(
            kitchen,
            SyncFixture.campaign("Not-Lowercase", "Capitalised"),
            SyncFixture.campaign("../x", "Traversal"),
            SyncFixture.campaign("trailing-", "Trailing hyphen"),
            SyncFixture.campaign("5f0c1a52-aaaa-4e0a-9d6f-0a1b2c3d4e5f", "  "),
            SyncFixture.campaign(SyncFixture.CAMPAIGN, "Duplicate"),
        )

        catalog.refresh(me)

        assertEquals(listOf("Kitchen tidy"), catalog.current(me).map { it.title })
    }

    // ---- the active campaign: chosen BEFORE recording ----------------------------------------------

    @Test fun exactlyOneCampaignIsSelectedAutomatically() = runBlocking {
        api.campaignsOnServer = listOf(kitchen)
        catalog.refresh(me)

        assertEquals(SyncFixture.CAMPAIGN, catalog.active(me)!!.id)
    }

    @Test fun withSeveralCampaignsTheCollectorMustChooseAndTheChoiceIsRemembered() = runBlocking {
        api.campaignsOnServer = listOf(kitchen, laundry)
        catalog.refresh(me)
        assertNull(catalog.active(me)) // ask

        catalog.setActive(laundry)

        assertEquals("Laundry", catalog.active(me)!!.title)
        assertEquals("Laundry", CampaignCatalog(FakeCloudApi(), store).active(me)!!.title) // survives a restart, offline
    }

    @Test fun aRememberedCampaignThatLeftTheListIsAskedAgain() = runBlocking {
        api.campaignsOnServer = listOf(kitchen, laundry, SyncFixture.campaign("9b2e3c74-5e30-4c2c-9f8b-2c3d4e5f6071", "Garage"))
        catalog.refresh(me)
        catalog.setActive(laundry)
        api.campaignsOnServer = listOf(kitchen, SyncFixture.campaign("9b2e3c74-5e30-4c2c-9f8b-2c3d4e5f6071", "Garage"))

        catalog.refresh(me)

        assertNull(catalog.active(me))
    }

    @Test fun aCampaignThatIsFullOrStoppedIsNeverTheActiveOne() = runBlocking {
        val full = SyncFixture.campaign(SyncFixture.CAMPAIGN, "Full", remaining = 0)
        val paused = SyncFixture.campaign(SyncFixture.CAMPAIGN_2, "Paused", status = "PAUSED")
        api.campaignsOnServer = listOf(full, paused)
        catalog.refresh(me)
        catalog.setActive(full)

        assertNull(catalog.active(me))

        // The only one that can take recordings is selected on its own.
        api.campaignsOnServer = listOf(full, paused, SyncFixture.campaign("9b2e3c74-5e30-4c2c-9f8b-2c3d4e5f6071", "Open"))
        catalog.refresh(me)
        assertEquals("Open", catalog.active(me)!!.title)
    }

    @Test fun campaignsAreTheSameWhenTheirIdsMatch() {
        assertEquals(Pipe("a", "One"), Pipe("a", "Other label"))
        assertFalse(Pipe("a", "One") == Pipe("b", "One"))
        assertTrue(Pipe.isLegacyPipe("white") && Pipe.isLegacyPipe("black") && Pipe.isLegacyPipe("black-white"))
        assertFalse(Pipe.isLegacyPipe(SyncFixture.CAMPAIGN))
        assertTrue(Pipe.isValidWire(SyncFixture.CAMPAIGN)) // a lowercase UUID is accepted as an id
        assertFalse(Pipe.isValidWire(SyncFixture.CAMPAIGN.uppercase()))
    }
}
