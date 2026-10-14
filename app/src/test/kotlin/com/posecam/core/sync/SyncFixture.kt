package com.posecam.core.sync

import com.posecam.core.cloud.CampaignProgress
import com.posecam.core.cloud.CampaignRules
import com.posecam.core.cloud.MyCampaign
import java.io.File
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory

/** Wires the real repository/processor to in-memory fakes and a temp-dir "app storage". */
class SyncFixture {
    var now = 1_000_000L
    var recording = false
    val root: File = createTempDirectory(prefix = "posecam-sync-").toFile()
    val capturesRoot = File(root, "captures").also { it.mkdirs() }
    val staging = UploadStaging(File(root, "upload-staging"))
    val dao = FakeUploadDao()
    val forgotten = mutableListOf<String>()
    val repo = UploadRepository(dao, Transactor.None, { now }, { forgotten += it })
    val api = FakeCloudApi()
    val s3 = FakeS3Transport()
    val materializer = FrameChunkMaterializer(repo, kotlinx.coroutines.Dispatchers.Unconfined)
    /** The account the fixture's processor uploads for (sessions of the fake DAO belong to it unless a test says otherwise). */
    var signedInAccount: String? = ACCOUNT
    val processor = UploadProcessor(
        repo, api, s3, FileHasher(), { "install-1" }, "test", materializer, isRecording = { recording },
        account = AccountScope({ signedInAccount }),
    )

    /** A processor over the fixture's fakes for a different [AccountScope] (the account tests change who is signed in). */
    fun processorFor(
        scope: AccountScope,
        rawPlan: (String, File) -> List<QueuedFile> = { _, _ -> emptyList() },
        cloud: com.posecam.core.cloud.CloudApi = api,
    ) = UploadProcessor(
        repo, cloud, s3, FileHasher(), { "install-1" }, "test", materializer, isRecording = { recording },
        account = scope, rawPlan = rawPlan,
    )

    fun processorFor(scope: () -> AccountScope) = processorFor(scope())

    /**
     * A PoseCam-style session folder: `captures/<id>/manifest.json` with `"complete"`. By default a GOOD take (30 s, tracked
     * throughout, no jumps); [redo] writes one the verdict calls a redo (two pose jumps).
     */
    fun sessionDir(sessionId: String = SESSION, status: String = "complete", redo: Boolean = false): File {
        val complete = status == "complete"
        val dir = File(capturesRoot, sessionId).also { it.mkdirs() }
        File(dir, "manifest.json").writeText(
            """{
  "format_version": "posecam-5",
  "session_id": "$sessionId",
  "start_wall_time_utc": "2026-09-17T09:00:00.000Z",
  "complete": $complete,
  "frame_count": 900,
  "tracked_frame_count": 900,
  "longest_tracking_gap_s": 0.0,
  "measured_fps": 30.0,
  "first_timestamp_ns": 1000000000,
  "last_timestamp_ns": 31000000000,
  "images": {"dropped": {}, "write_failures": []},
  "pose_jumps": {"count": ${if (redo) 2 else 0}}
}""",
        )
        File(dir, "device.json").writeText("""{"manufacturer":"samsung","model":"SM-G781B","device":"r8q","hardware":"qcom","android_release":"13"}""")
        return dir
    }

    /** Writes [content] at [relative] inside [dir] and describes it as a plain queue entry. */
    fun file(dir: File, relative: String, content: String): QueuedFile {
        val file = File(dir, relative).also { it.parentFile.mkdirs(); it.writeText(content) }
        return QueuedFile(relative, file.absolutePath, UploadSourceKind.PLAIN, file.length())
    }

    /** `frames/000000_<ts>.jpg ...`: [count] fake JPEGs, each with distinct bytes. */
    fun frames(dir: File, count: Int, startIndex: Int = 0) {
        val frames = File(dir, "frames").also { it.mkdirs() }
        for (i in startIndex until startIndex + count) {
            File(frames, "%06d_%d.jpg".format(i, 1_000_000L + i * 33_000_000L)).writeBytes(ByteArray(40 + i % 7) { (i + it).toByte() })
        }
    }

    fun sha(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    /** Queues [files] as a finalized session and marks its cloud session as created. */
    suspend fun queueFinalized(dir: File, vararg files: QueuedFile, created: Boolean = true, status: String = "complete") {
        repo.finalizeSession(dir.name, dir, status, files.toList())
        if (created) repo.markSessionCreated(dir.name)
    }

    suspend fun row(sessionId: String, relative: String) = requireNotNull(dao.findUpload(sessionId, relative))

    companion object {
        const val SESSION = "capture-20260917T090000-a3f9c1"
        const val ACCOUNT = "11111111-1111-4111-8111-111111111111"
        const val OTHER_ACCOUNT = "22222222-2222-4222-8222-222222222222"
        const val CAMPAIGN = "5f0c1a52-3c1e-4e0a-9d6f-0a1b2c3d4e5f"
        const val CAMPAIGN_2 = "7a1d2b63-4d2f-4b1b-8e7a-1b2c3d4e5f60"

        fun campaign(
            id: String,
            title: String,
            collectRaw: Boolean = false,
            status: String = MyCampaign.STATUS_ACTIVE,
            remaining: Int = 10,
            acceptingUploads: Boolean = true,
        ) = MyCampaign(
            id = id, title = title, description = "", instructions = "Record the whole task in one go.", referenceInfo = null,
            currency = "INR", rewardPerRecording = 5000, rewardPoints = 0, rules = CampaignRules(5, 600, "ANY"),
            collectRaw = collectRaw, target = 50, remaining = remaining, endDate = null, enrolledAt = "2026-09-01T00:00:00Z",
            acceptingUploads = acceptingUploads, status = status, progress = CampaignProgress(12, 1, 9, 1, 1, 45000),
        )
    }
}

/** Test convenience: the session is the folder the file lives in (what [SyncFixture.file] creates). */
suspend fun UploadRepository.enqueue(file: QueuedFile): EnqueueResult {
    val path = file.localPath.replace(File.separatorChar, '/')
    val directory = File(path.removeSuffix(file.relativePath).trimEnd('/'))
    return enqueue(directory.name, directory, file)
}
