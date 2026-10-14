package com.posecam.core.sync

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The allow-list must stay in step with the backend's PoseCam profile: a path the backend rejects fails
 * permanently on the phone. `cloud/path-rules.json` is the shared source of truth; this test checks the Kotlin
 * side, and `cloud/check_backend_rules.py` checks the backend against the same file.
 */
class CloudFileRulesTest {
    private val rules = JSONObject(File("../cloud/path-rules.json").readText())

    @Test fun everyAllowedPathIsClassifiedAsTheSharedRulesSay() {
        val allowed = rules.getJSONObject("paths").getJSONArray("allowed")
        assertTrue(allowed.length() >= 10)
        for (i in 0 until allowed.length()) {
            val entry = allowed.getJSONObject(i)
            val path = entry.getString("path")
            assertEquals(path, UploadFileType.valueOf(entry.getString("type")), CloudFileRules.classify(path))
            // The shared file's "required" is the legacy flag: what holds up SYNCED when the raw data is collected and the
            // export was optional. Exports now always hold it up; the raw files only for a campaign that collects them.
            val type = UploadFileType.valueOf(entry.getString("type"))
            assertEquals(path, type == UploadFileType.EXPORT || entry.getBoolean("required"), CloudFileRules.isRequired(path, rawRequired = true))
            assertTrue(path, CloudFileRules.isUploadable(path))
        }
    }

    @Test fun everyRejectedPathIsRejected() {
        val rejected = rules.getJSONObject("paths").getJSONArray("rejected")
        for (i in 0 until rejected.length()) {
            val path = rejected.getString(i)
            assertNull(path, CloudFileRules.classify(path))
            assertFalse(path, CloudFileRules.isRequired(path, rawRequired = true))
            assertFalse(path, CloudFileRules.isRequired(path, rawRequired = false))
        }
    }

    @Test fun sessionIdsFollowTheSharedRules() {
        val ids = rules.getJSONObject("sessionIds")
        for (kind in listOf("valid", "invalid")) {
            val list = ids.getJSONArray(kind)
            for (i in 0 until list.length()) {
                assertEquals(list.getString(i), kind == "valid", CloudFileRules.isValidSessionId(list.getString(i)))
            }
        }
    }

    @Test fun theIdPoseRecorderGeneratesIsAlwaysValid() {
        repeat(200) {
            val id = com.posecam.PoseRecorder.newSessionId(System.currentTimeMillis() + it * 86_400_000L)
            assertTrue(id, CloudFileRules.isValidSessionId(id))
        }
    }

    @Test fun exportsAreRequiredForSyncWhateverTheCampaignCollects() {
        val export = "export/2026-09-16-14_30_52-a3f9c1-s1/posecam_export.json"
        assertEquals(UploadFileType.EXPORT, CloudFileRules.classify(export))
        assertTrue(CloudFileRules.isRequired(export, rawRequired = false))
        assertTrue(CloudFileRules.isRequired(export, rawRequired = true))
    }

    @Test fun manifestAndDeviceAreAlwaysRequired() {
        for (raw in listOf(true, false)) {
            assertTrue(CloudFileRules.isRequired("manifest.json", raw))
            assertTrue(CloudFileRules.isRequired("device.json", raw))
        }
    }

    @Test fun rawDataIsRequiredOnlyWhenTheCampaignCollectsIt() {
        val raw = listOf("intrinsics.json", "poses.csv", "frame_metadata.csv", "imu.csv", "frames-00000.zip", "frames-00042.zip")
        for (path in raw) {
            assertTrue(path, CloudFileRules.isRequired(path, rawRequired = true))
            assertFalse(path, CloudFileRules.isRequired(path, rawRequired = false))
            assertTrue(path, CloudFileRules.isRaw(path))
            assertTrue(path, CloudFileRules.isUploadable(path)) // the allow-list itself does not depend on the campaign
        }
        assertFalse(CloudFileRules.isRaw("manifest.json"))
        assertFalse(CloudFileRules.isRaw("export/2026-09-16-14_30_52-a3f9c1-s1/posecam_export.json"))
        assertFalse(CloudFileRules.isRaw("notes.txt"))
    }

    @Test fun listPlainUploadableSkipsChunksJpegsAndStrayFiles() {
        val dir = createTempDir()
        listOf("manifest.json", "poses.csv", "imu.csv", "frames-00000.zip", "scratch.tmp", "trajectory.png", "notes.txt")
            .forEach { File(dir, it).writeText("x") }
        File(dir, "frames").mkdirs()
        File(dir, "frames/000000_1.jpg").writeText("x")

        assertEquals(listOf("imu.csv", "manifest.json", "poses.csv"), CloudFileRules.listPlainUploadable(dir).map { it.name })
        dir.deleteRecursively()
    }

    @Test fun statusConstantsMatchWhatPoseRecorderReports() {
        assertEquals(CloudFileRules.STATUS_COMPLETE, com.posecam.PoseRecorder.STATUS_COMPLETE)
        assertEquals(CloudFileRules.STATUS_INCOMPLETE, com.posecam.PoseRecorder.STATUS_INCOMPLETE)
    }
}
