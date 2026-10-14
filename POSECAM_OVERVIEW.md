# PoseCam: Overview

PoseCam is an Android app that turns an ARCore-capable phone into a **data recorder for robot learning**. Mounted on a handheld gripper, it records a human demonstration as synchronized video frames, 6-DoF camera poses and raw IMU data at 30 fps, entirely on the phone. The recordings are exported as MP4 + pose text files (the AnySense-style imitation-learning format), uploaded to the DataCollect marketplace, where each good recording is reviewed and paid per approved recording.

No iPhone, depth sensor or external tracking system is needed.

- **App id:** `com.posecam` · **Version:** 0.3.0 (versionCode 4) · **Language:** Kotlin
- **Min Android:** 7.0 (API 24) · **Target/Compile SDK:** 37 · **ARCore:** 1.56
- **Recording format version:** `posecam-5`
- **License:** MIT

---

## 1. The big picture

```
 Contributor's phone (PoseCam)                     DataCollect marketplace            Website
┌───────────────────────────────┐        ┌─────────────────────────┐     ┌──────────────────┐
│ Pick a campaign (accepted on  │        │ API: auth, campaigns,   │     │ accept campaigns │
│ the website), then record:    │        │ sessions, review        │     │ review, progress │
│ frames + poses + IMU          │ presign│ mints presigned URLs    │ ──► │ and earnings     │
│ Verdict: "good" / "redo"      │ ─────► │                         │     └──────────────────┘
│ Export MP4 + AR_Pose.txt      │        │ Cloudflare R2 (storage) │
│ Background upload (WorkManager)│  PUT  │ bytes go straight to R2 │
└───────────────────────────────┘ ─────► └─────────────────────────┘
```

1. A **contributor** accepts a campaign on the website and chooses it in the app (**My campaigns**) before recording.
2. They record a demonstration with the phone mounted on a gripper; the app judges the take ("Take looks good" or "Better to record this one again").
3. A **good, complete** take is queued automatically under the active campaign. Redo takes and incomplete recordings are never uploaded.
4. The app builds the **pipeline export** (MP4 + pose text) and uploads it (plus the raw data only if the campaign collects it) in the background.
5. The backend verifies the upload and creates the marketplace submission; review, approval and earnings are on the website.

---

## 2. What the app records

Everything is raw ARCore/sensor output. Nothing is smoothed or re-based on the device; processing happens offline.

| Stream | Source | Rate | Stored in |
|---|---|---|---|
| RGB frames | ARCore CPU image (YUV), JPEG-encoded | 30 fps (640×480) | `frames/*.jpg` |
| Camera pose (6-DoF) | `Camera.getPose()` | per frame | `poses.csv` |
| IMU | Accelerometer + uncalibrated gyro | about 420 Hz | `imu.csv` |
| Per-frame camera metadata | Camera2: exposure, ISO, focus, OIS, rolling shutter | per frame | `frame_metadata.csv` |
| Intrinsics | fx, fy, cx, cy, width, height | per session | `intrinsics.json` |
| Device info | Phone model, Camera2 characteristics, IMU sensors | per session | `device.json` |
| Session info | Versions, camera config, stats, detected pose jumps | per session | `manifest.json` |

### Design principles

- **Every frame accounted for.** One row per camera frame, including untracked frames and frames whose image was dropped under load. Frame *N* in the CSV is frame *N* on disk, so indices never shift silently.
- **One clock.** Frames, poses and IMU samples share the sensor clock; wall-clock time is never mixed in.
- **Raw data, processed offline.** ARCore relocalization "pose jumps" are detected and logged in `manifest.json`, then handled at export time instead of being hidden on the device.
- **Never blocks the camera.** JPEG encoding runs on a bounded background queue. If it overflows, the frame is dropped and the reason is recorded (`dropped:queue_full`, `not_yet_available`, `deadline_exceeded`, `resources_exhausted`).
- **Device-independent.** No hardcoded camera configs. Each session records its own intrinsics, camera-to-IMU axes and clock checks.
- **Recording never needs the network.** Only uploading does.

### Pipeline (inside the app)

```
ARCore session (GL thread) ──► pose + timestamp ──► poses.csv
        │
        └─► CPU image (YUV) ──► bounded queue ──► JPEG encoder ──► frames/
                                     └─ full? drop + log reason

SensorManager ──► accelerometer + uncalibrated gyro (~420 Hz) ──► imu.csv
Camera2 metadata ──► exposure, focus, OIS, rolling shutter ──► frame_metadata.csv
```

### Recording folder (`posecam-5`)

```
capture-20260916T213140-9c433c/
├── frames/             000000_<timestamp_ns>.jpg, ...  (sensor-native orientation)
├── poses.csv           frame_index,timestamp_ns,tx,ty,tz,qx,qy,qz,qw,tracking_state,image
├── frame_metadata.csv  exposure, ISO, focus distance, OIS mode, rolling shutter skew per frame
├── imu.csv             timestamp_ns,sensor,x,y,z,bias_x,bias_y,bias_z
├── intrinsics.json     fx, fy, cx, cy, width, height
├── device.json         phone model, Camera2 characteristics, IMU sensors
└── manifest.json       session info, versions, camera config, stats, detected pose jumps
```

Session ids look like `capture-YYYYMMDDTHHMMSS-xxxxxx`. Untracked frames keep their row, with empty pose fields and the tracking state. IMU axes are the phone's sensor frame, not the camera's. See [docs/COORDINATES.md](docs/COORDINATES.md) for every frame convention.

---

## 3. The collector's workflow

### Screens

| Screen | Purpose |
|---|---|
| **Capture** (`CaptureActivity`, launcher, portrait-locked) | Live camera, tracking status, the active campaign with a **Change** button, Record/Stop (disabled until a campaign is chosen, when cloud upload is on). Bottom row: **640×480**, **Focus: auto** (both locked to the team's protocol; they turn red if changed), and **Recordings**. |
| **Recordings** (`SessionsActivity`) | Lists sessions with length, frames, size and cloud status. Tap one to export, upload, retry or delete. Has the banner with sync progress, **Sync now/Retry**, **Cloud sync settings** and **Delete all synced**. |
| **Sign in** (`AuthActivity`) | The website's account: email + password sign-up (with Terms and Privacy acceptance) / sign-in (once, on first launch), and Forgot password. |
| **My campaigns** (`MyCampaignsActivity`) | The campaigns accepted on the website, as cards (instructions, progress, pay per approved recording, deadline). Tap one to make it the active campaign. Cached for offline use. |
| **My uploads** (`UploadStatsActivity`) | The account screen: Log out, Delete account (opens the website), My campaigns, **See progress and earnings on the web**, total recordings uploaded and a day-by-day list, counted by the server. Cached for offline viewing. |
| **Day uploads** (`DayUploadsActivity`) | One day's recordings with their campaign: "Uploaded", and a small line such as "In review" / "Approved" when the backend says. |

### Taking a demo

1. Open the app and allow camera access (install "Google Play Services for AR" if asked).
2. Move the phone slowly toward textured surfaces until the status says **Ready to record**. The Record button stays off until ARCore has tracked continuously, with no pose jumps, for **3 seconds** (`TrackingGate`), because ARCore keeps re-scaling its pose during the first seconds.
3. Hold the gripper **wide open**, press **Record**, do **one** demonstration (open and close the gripper fully at least once), press **Stop**.
4. Read the verdict dialog.

**One recording = one demo.**

### Feedback without looking at the screen

The phone faces away from the collector on the gripper, so problems are signalled by vibration:

| Vibration | Meaning | What to do |
|---|---|---|
| One long buzz | Tracking lost | Point at textured objects, not a bare wall. If it repeats, redo the take. |
| Three short buzzes | Pose jumped | Finish, but expect to redo; the recording is split at that point. |
| Two medium buzzes at the end | The finished take is worth redoing | Read the verdict message. |

### Take verdict (`TakeVerdict`)

After Stop, the app says **"Take looks good"** or **"Better to record this one again"** with reasons. A redo is recommended if any of these hold:

- the take is shorter than **5 s**
- there was at least one **pose jump**
- tracking was lost for longer than **5 frames (about 0.17 s)**

Short gaps and a few missing images are repaired by the exporter, and are mentioned as notes only.

### Practical requirements

- Both gripper jaws fully inside the frame, with margin, and pointing up in the exported video.
- Plain background (nothing in the jaws' colour) and good light (a desk lamp is enough).
- Keep **640×480** and **Focus: auto**. Recordings made with other settings are refused by the export.
- At least about 4 GB free per hour of recording. The app refuses to start below 4 GB free and shows the minutes left.
- Record unplugged (the phone heats up when charging). Don't switch apps or lock the screen mid-recording.
- On Samsung phones, set the battery to **Unrestricted** for PoseCam.

Full handouts: [docs/RECORDING_TIPS.md](docs/RECORDING_TIPS.md) and [docs/SETUP_GUIDE.md](docs/SETUP_GUIDE.md).

---

## 4. Export: turning a recording into training data

The app and the desktop tool `tools/export_anysense.py` implement the **same export policy**. They were checked to produce byte-identical pose files across 37 recordings.

```
exports/2026-09-17-14_58_35-<6hex>-s1/
├── RGB_<stem>.mp4          H.264, 30 fps
├── AR_Pose_<stem>.txt      one line per video frame: "<epoch_ms>" ,qx,qy,qz,qw,tx,ty,tz
└── posecam_export.json     provenance
```

- Pose line *N* belongs to video frame *N*. Poses use the OpenGL camera convention (−Z forward, +Y up), the same as ARKit.
- **Segments:** by default a recording is cut into one clean segment per jump-free stretch (`-s1`, `-s2`, ...). Cuts happen at every ARCore pose jump and wherever more than 5 camera images in a row were dropped. Gaps of up to 5 frames are interpolated. Each segment must be at least 3 s.
- **"Keep each recording as one video"** (Cloud sync settings) exports the whole take as one `-s1` video instead. Jumps then stay inside the video and are listed in `posecam_export.json`. It is off by default.
- **Rotation** is set once (which way is "up" so the jaws point up) and remembered. Until it is set, nothing is exported, because a wrongly rotated video would otherwise be uploaded and labeled unnoticed.
- An export is about 45-50 MB per 2.5 min, against about 3.4 GB/hour for raw data.

---

## 5. Cloud upload

Cloud upload is **optional and off by default**. With no backend URL built into the app, none of the upload code runs. See [docs/CLOUD_UPLOAD.md](docs/CLOUD_UPLOAD.md) for the full description.

### Flow

```
PoseRecorder ──onSessionStarted / onSessionFinalized──► UploadCoordinator ──► Room queue (posecam-uploads.db)
Active campaign (stored when the take starts); good + complete take ──► PipelineExportStage (MP4 + AR_Pose per segment) ──► front of the queue
WorkManager chain ──► UploadWorker ──► UploadProcessor:
   POST /v1/sessions                    create the cloud session (campaignId + take verdict; Bearer token, refreshed on 401)
   POST .../uploads/presign             SINGLE | MULTIPART | ALREADY_VERIFIED
   PUT bytes straight to R2             presigned URL, no auth header
   POST .../uploads/verify              backend checks size + SHA-256 → VERIFIED
   POST .../sessions/{id}/complete      manifest + device + export (+ raw if the campaign collects it) VERIFIED → SYNCED
LocalRetentionManager deletes synced recordings from the phone
```

### Key behaviours

- **Campaigns.** The active campaign is chosen **before** recording and stored on the session when the take starts. A good, complete take is queued under it automatically (the end-of-take dialog asks nothing: "Uploading to <campaign>"). Redo takes and incomplete recordings are **never uploaded** ("Not uploaded: redo take"). Older recordings (and ones made with no campaign) show "Choose a campaign to upload"; a recording that is not in the cloud yet can be moved to another campaign in Recordings. Nothing is uploaded, and no cloud session is created, until a campaign is set.
- **Uploads run while you record.** The take being recorded isn't queued until finalized, and upload work runs on background-priority threads. Only the pipeline export (video re-encoding) waits for Stop.
- **Retries.** Temporary failures retry after 15 s, doubling to 1 min. A 15-minute watchdog restarts the queue after a process kill. Only permanent errors become `FAILED`.
- **Verified, not assumed.** A file is `VERIFIED` only after the backend checked the stored object's size and SHA-256 (single PUTs). Multipart files (`imu.csv`, MP4s) are verified by size.
- **Frame chunks.** JPEGs travel in deterministic zip chunks of 1,000 frames (`frames-00000.zip`, ...) to avoid thousands of API calls. Chunks are built one at a time just before upload and deleted once verified.
- **Safe local cleanup.** A local recording is deleted only after the backend confirms the whole session `SYNCED` and every file the app was supposed to upload (the export included) is verified. If the campaign does not collect raw data, the local raw frames count as covered. The marketplace does not report publishing, so deletion is allowed right after SYNCED. "Delete all synced" removes everything that's safe to remove at once.
- **Network policy** (Cloud sync settings): Wi-Fi only (default), any network, or manual only. Optionally ask before using mobile data. Retention period for synced recordings is configurable.

### What gets uploaded

| Path | Required for SYNCED |
|---|---|
| `manifest.json`, `device.json` | always |
| `export/<stem>/RGB_<stem>.mp4`, `AR_Pose_<stem>.txt`, `posecam_export.json` | always (every segment the exporter produced) |
| `intrinsics.json`, `poses.csv`, `frame_metadata.csv`, `imu.csv` | only if the campaign's `collectRaw` is true |
| `frames-NNNNN.zip` (1,000 JPEGs each) | only if `collectRaw` is true |

The export holds up SYNCED: if it is impossible or fails (off protocol, no rotation chosen, export failed), the recording is **not** synced, and the Recordings row says why ("Not uploaded: off protocol", "export failed" with a Retry button). When `collectRaw` is false the raw files are never queued. The backend's answer to `complete` carries `session.marketplace`: a `NOT_COUNTED` recording (campaign full/closed, duplicate) is still synced, and its row says it was not counted and why.

### Status labels a collector sees

`On this phone only` / `Choose a campaign to upload` / `Not uploaded: redo take` → `Waiting for Wi-Fi` → `Queued` → `Uploading 45%` → `Verifying` (or `Preparing the video…`) → `Synced` (or `Upload failed`, `Belongs to another account`). A foreground-service notification shows upload progress, and result notifications appear only when the app is off screen.

### Accounts

One account for the app and the website (**same email and password**, `AuthActivity`; sign-up requires accepting the Terms and Privacy Policy and sends the terms version). The app keeps a 15-minute access token and a 30-day refresh token (private preferences), never the password. A `401` refreshes the login once and retries once (single-flight, because refresh tokens rotate); only a refused refresh token signs the collector out. Recording works without signing in, and uploads wait. **Log out** keeps recordings and the queue.

Every recording belongs to the account that made it. Uploads only run for the signed-in account's recordings; another account's stay untouched ("Belongs to another account"), and recordings from before accounts were tracked go to the first account that signs in afterwards.

---

## 6. Backend

| Item | Details |
|---|---|
| **Backend** | The DataCollect marketplace API (contract: `marketplace_backend/docs/APP_API_CONTRACT.md`). It signs presigned **Cloudflare R2** URLs; the phone PUTs bytes directly to R2. |
| **App base URL** | The API root, no path prefix: `https://<api-host>` (the app adds `/v1/...`). Release builds must be https; debug builds also allow cleartext HTTP for a local server. |
| **Responses** | Marketplace endpoints (`auth/*`, `meta`, `me/campaigns`): `{ok, data}` / `{ok:false, error:{code, message, fieldErrors?}}`. Recording endpoints (`sessions/*`, `me/stats`, `me/uploads`): bare JSON and `{error:{code, message, details?}}`. |
| **Old backends** | The LabelNow `labeling-server` and the original [backend/](backend/README.md) (FastAPI + Lambda + S3) used pipes and their own accounts; the app no longer targets them. |

### API (all under `/v1`)

| Method + path | Purpose |
|---|---|
| `POST /auth/register`, `/auth/login`, `/auth/refresh`, `/auth/logout` | The website's account; `{user, tokens}` |
| `GET /meta` | Terms version and the website's links (cached) |
| `GET /me/campaigns` | The accepted campaigns (My campaigns, the picker) |
| `POST /sessions` | Idempotent session create (`campaignId`, `take`, `deviceFingerprint`) |
| `GET /sessions/{id}` | Session + file list |
| `POST /sessions/{id}/uploads/presign` | Get a presigned PUT (single / multipart / already verified) |
| `POST /sessions/{id}/uploads/multipart/{start,parts,complete}` | Multipart flow |
| `POST /sessions/{id}/uploads/verify` | Server checks the stored object |
| `POST /sessions/{id}/complete` | Mark `SYNCED`; answers `session.marketplace` |
| `GET /me/stats`, `GET /me/uploads` | Upload history for My uploads / Day uploads |

### Security notes

- Uploads always need sign-in. Access tokens are short-lived (15 minutes); the refresh token rotates on every use and a reused one revokes the login, so refreshes are single-flight. Tokens are never logged or sent to storage.
- The client never supplies bucket names or S3 keys. The server builds keys (`campaigns/<campaignId>/<sessionId>/...`) from the campaign and a strict allow-list of `relativePath` values.
- Presigned URLs are short-lived. The `x-amz-checksum-sha256` header returned by the server must be forwarded on PUT; dropping it silently turns integrity checking into a size check.
- Frames may show people and homes: sign-up says so and requires accepting the Terms and Privacy Policy; keep the bucket private.
- Deleting an account is `DELETE /me` on the website side (the app links to it).

---

## 7. Tools (for the person who runs the analysis)

Python tools in [tools/](tools/), run with [uv](https://docs.astral.sh/uv/) (dependencies install automatically).

| Tool | What it does |
|---|---|
| `pull_captures.sh` | Copies sessions from the phone into `./data/` over adb |
| `check_sync.py` | Timing, pose/image/IMU consistency (run on every recording) |
| `plot_trajectory.py` | Summary + top-down trajectory plot (metric scale check) |
| `overlay_check.py` | Draws world-fixed axes onto the frames to check alignment |
| `check_imu_alignment.py` | Camera↔IMU axes mapping and time offset |
| `calibrate_camera.py` | Checkerboard intrinsics / lens distortion |
| `make_checkerboard.py` | Renders a checkerboard for calibration |
| `check_gripper_view.py` | Checks that both jaws are visible and clear of the frame edges |
| `export_anysense.py` | Desktop export to MP4 + pose text |
| `check_export.py` | Delivery gate for an exported demo (frames == pose lines, jaws visible, gripper opens and closes) |
| `posecam_io.py` | Shared reader code |

One-time checks for each new phone model (sync, IMU axes, metric accuracy, lens distortion, heat, overlay, gripper view) are in [docs/SETUP_GUIDE.md](docs/SETUP_GUIDE.md) section 5. **Do these before a phone records real data**, because several problems (wrong IMU axes, stabilization, wrong scale) are silent.

---

## 8. Measured performance

| | Samsung Galaxy S20 FE | Tecno Pova 5G |
|---|---|---|
| Frame rate (640×480) | 29.9–30.0 fps | 29.9 fps |
| Dropped images | 0 in a 5-min recording | 0 in four 2.5–5.3 min recordings |
| Storage (JPEG q75) | ~3.4 GB/h | ~3.4–3.9 GB/h |
| IMU rate | ~419 Hz | ~422 Hz |

Scale was checked against a tape measure on the S20 FE: distances were within about 4% of the real ones. A walked 2 × 1.5 m loop measured 6.74 m (tape: 7.0 m) and closed to within 3.3 cm of its start.

---

## 9. Code map

```
app/src/main/kotlin/com/posecam/
├── CaptureActivity / PoseCamApp          capture screen, application setup
├── PoseRecorder, ImuRecorder, FrameWriter,
│   ImageGrabber, BufferPool, YuvBuffer   recording pipeline
├── TrackingGate, PoseJumpDetector,
│   TakeVerdict, TakeResultDialog         tracking readiness, jump detection, end-of-take verdict
├── Intrinsics, CameraConfigs, DeviceInfo,
│   FrameMetadata(+Reader)                per-device camera and sensor info
├── SessionExport, PipelineExporter,
│   Mp4Writer, ExportRotationDialog       on-phone export (MP4 + AR_Pose)
├── SessionZipper, SessionsActivity,
│   SessionsCloudUi                       Recordings screen, raw zip share/save
├── AuthActivity, MyCampaignsActivity,
│   UploadStatsActivity, DayUploadsActivity  sign-in, campaigns, account and upload history
├── core/cloud/                           API client, auth store, config, models
└── core/sync/                            upload queue (Room), coordinator, processor, worker,
                                          scheduler, watchdog, multipart/S3 transport,
                                          frame chunking, retention/cleanup, campaign catalog
app/src/test/                             unit tests (Robolectric, Room, MockWebServer)
backend/                                  legacy FastAPI + SAM (AWS) backend
cloud/                                    path-rules.json + backend rule checker + dev e2e launcher
docs/                                     setup guide, recording tips, coordinates, cloud upload docs
tools/                                    Python analysis and export tools
```

Libraries: Kotlin, ARCore, Camera2, Room (upload queue), WorkManager (background uploads), OkHttp (cloud client), KSP.

---

## 10. Build and install

Requires the Android SDK and an [ARCore-supported phone](https://developers.google.com/ar/devices) with USB debugging enabled.

```bash
./gradlew assembleDebug testDebugUnitTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

- **Release build:** `./gradlew assembleRelease`. Signing comes from a `keystore.properties` file that is not in the repository. R8 minification is deliberately off because ARCore and the Camera2 metadata paths use reflection.
- **Cloud upload URL:** set `posecam.cloud.baseUrl.debug` / `posecam.cloud.baseUrl.release` (the API root, no path prefix) in `local.properties`, via a `-P` Gradle flag, or the environment variable `POSECAM_CLOUD_BASEURL_<BUILDTYPE>`. A blank URL leaves cloud upload off. Release builds require `https` (the build fails otherwise). `posecam.web.appUrl.<buildType>` optionally sets the fallback website links used until `GET /v1/meta` was fetched.
- **Debug against a local server:** `adb reverse tcp:4000 tcp:4000`, then build with `-Pposecam.cloud.baseUrl.debug=http://127.0.0.1:4000`.
- **Upgrading from 0.2.x:** 0.3.0 is signed with a different key, so the old app must be uninstalled first. Uninstalling deletes recordings still inside the app, so export or upload them first.

Permissions: `CAMERA`, `HIGH_SAMPLING_RATE_SENSORS` (full IMU rate on Android 12+), `VIBRATE`, `INTERNET`, `ACCESS_NETWORK_STATE`, `FOREGROUND_SERVICE` (+ `_DATA_SYNC`), `POST_NOTIFICATIONS`. The app requires ARCore (`android.hardware.camera.ar`).

---

## 11. Known limits

- The automatic export stage around the pipeline exporter was verified with a fake exporter in tests; the real `MediaCodec` encoder path is verified against the reference exporter, but the full automatic flow had not been exercised on a phone at the time the cloud docs were written.
- Exports are made inside an upload pass, so under the default Wi-Fi-only policy they wait for Wi-Fi even though making them needs no network.
- After a hard kill, an in-flight PUT may already have landed, so a re-send can leave two identical object versions.
- Pose jumps put the poses on either side in different ARCore frames; separate sessions (app restarts) have different world origins.
- Only phones on Google's ARCore supported list work.

---

## 12. Further reading

| Document | Contents |
|---|---|
| [README.md](README.md) | Public project README |
| [docs/SETUP_GUIDE.md](docs/SETUP_GUIDE.md) | New-phone setup and one-time validation checks |
| [docs/RECORDING_TIPS.md](docs/RECORDING_TIPS.md) | One-page handout for collectors |
| [docs/COORDINATES.md](docs/COORDINATES.md) | Every coordinate frame and timestamp convention |
| [docs/CLOUD_UPLOAD.md](docs/CLOUD_UPLOAD.md) | Cloud upload feature in detail |
| [POSECAM_CLOUD_UPLOAD_GUIDE.md](POSECAM_CLOUD_UPLOAD_GUIDE.md) | Original cloud upload design |
| [backend/README.md](backend/README.md) | Legacy AWS backend |
