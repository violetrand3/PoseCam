# Cloud upload

PoseCam uploads finished recordings in the background to the **DataCollect marketplace** (an API plus Cloudflare R2
storage). A contributor accepts *campaigns* on the website, records for one in the app, and each good recording is reviewed
and paid per approved recording. The contract the app is built against is `marketplace_backend/docs/APP_API_CONTRACT.md`
(where it disagrees with the product wish list, the contract wins). Cloud upload is **off by default**: with no backend
URL built in, the app has no upload code path at all (no database is opened, no listener is installed, nothing is
shown) and behaves like an offline-only recorder. The older design notes are in `POSECAM_CLOUD_UPLOAD_GUIDE.md`.

## What it does

```text
PoseRecorder ──onSessionStarted / onSessionFinalized──> UploadCoordinator ──> Room queue (posecam-uploads.db)
active campaign (chosen BEFORE recording) ─ stored on the session when the take starts
good + complete take  ─────────────────────────>      │   redo / incomplete takes are never queued
                                                              │
        PipelineExportStage (RGB_<stem>.mp4 + AR_Pose_<stem>.txt per clean segment) ──> export/<stem>/... rows, front of the queue
                                                              │
                                  WorkManager unique chain ──> UploadWorker ──> UploadProcessor
                                                                                   │
     POST /v1/sessions        {campaignId, deviceFingerprint, take{...}}  create the cloud session (Bearer token, refreshed on 401)
     POST …/uploads/presign   → SINGLE | MULTIPART | ALREADY_VERIFIED
     PUT bytes straight to S3 (presigned URL, separate HTTP client, no auth header)
     POST …/uploads/verify    (backend HeadObject: size + SHA-256)  → VERIFIED
     POST …/sessions/{id}/complete (manifest, device, the export, + raw only if the campaign collects it: all VERIFIED)  → SYNCED
                                                                                   │
                                                        LocalRetentionManager deletes synced recordings
```

* The **recorder only reports two facts**: a session started, a session was finalized. The listener never blocks and
  never throws into the recorder (`PoseRecorder.notifyListener` swallows everything).
* **Uploads carry on while a take is recorded.** A collector who films back to back never waits for the previous take to
  drain, and nothing is paused and restarted around a take (that restart was slow and sometimes needed a manual "Sync
  now"). The take being recorded is not queued until it is finalized; upload work (hashing, zipping chunks, TLS) runs on
  background-priority threads; the pipeline export, which re-encodes video, is the one job that waits for Stop, and runs
  in the same pass right after it. Uploading shares the phone's CPU, radio and heat with ARCore, so **check on a real phone**
  that `images.dropped` / `queue_full` in `manifest.json` stay at 0 for a take recorded during a large upload.
* **Temporary failures retry within a minute, not hours.** The upload worker retries itself after 15 s, doubling to at
  most 1 min, instead of handing the failure to WorkManager's backoff (which grows to 5 h). Losing the network stops the
  worker and WorkManager restarts it the moment the network returns. A 15-minute watchdog queues a worker if recordings are
  waiting and none is queued (after a process kill, for instance). While the app is on screen it also starts the queue
  itself every 30 s if recordings are waiting, nothing is uploading and the network policy is met by the app's own
  reading: WorkManager can hold a job back on its own idea of the network ("N sessions queued" with nothing happening
  until the app was killed and reopened). A queued recording whose last attempt failed says why in its row.
* The **queue is the source of truth** (Room, unique on `(sessionId, relativePath)`), so enqueueing and recovery are
  idempotent. Temporary failures retry forever with WorkManager backoff; only permanent errors become `FAILED`.
* **Verified, not assumed.** A file is `VERIFIED` only after the backend checked the S3 object (size and SHA-256); a 200
  from S3 proves nothing.
* A local recording is deleted only after the backend confirmed the whole session `SYNCED`, and only if every file the app
  was supposed to upload is verified (the export included). When the campaign does not collect raw data, the local raw
  frames count as covered; when it does, every JPEG on disk must still be inside a verified chunk.

## Campaigns: where a recording is filed

A contributor accepts campaigns on the website (browsing and accepting stay there). The app shows only those:

* **My campaigns** (`MyCampaignsActivity`, opened from Capture's **Change** button or the account screen) lists
  `GET /v1/me/campaigns` as cards: title, the first ~140 characters of the instructions, progress ("12 recorded · 9 approved"),
  pay per approved recording (paise to rupees) and the deadline. Tapping a card makes it the **active campaign**. With none
  accepted it says "Accept a campaign on the website first" with a button for the website, and Record is disabled.
* The campaign is chosen **before recording** (the phone faces away once mounted). The capture screen shows its name with a
  **Change** button. The last choice is remembered, a single campaign is selected automatically, and if the remembered one is
  no longer in the list (or the list changed so a choice is needed) the app asks again. With cloud upload on and no usable
  campaign, Record is disabled and the status line says why. A campaign that is paused/closed or has no slots left is shown
  but cannot be chosen.
* The list is cached in prefs (`PrefsCampaignStore`, per account), so the screen and recording work offline. Recording to the
  phone with cloud upload off is unchanged.
* The active campaign (id and its `collectRaw`) is stored on the session row when the take starts. When the take ends,
  a **good, complete** take is queued automatically under it: the dialog only reports the verdict and
  "Uploading to <campaign> (Wi-Fi only)". A **redo** take or an **incomplete** recording is never queued, offered or sent
  ("Not uploaded: redo take" / "Not uploaded: recording incomplete" in Recordings; the marketplace refuses them with
  `409 TAKE_NOT_ELIGIBLE` anyway). The verdict is re-derived from the manifest with `TakeVerdict`, so recordings queued
  later (after a restart, or by choosing a campaign) are judged exactly as at recording time.
* Recordings from before this change (filed under a LabelNow pipe, or never filed) and good takes made with no campaign show
  **"Choose a campaign to upload"**. In Recordings a recording that is not in the cloud yet can be given or moved to another
  campaign (chooser over the cached list); the queue for it starts over because what the old backend verified says nothing
  about the new one. After the session exists in the cloud the campaign is fixed.
* The campaign id is stored in the existing Room column `cloud_sessions.pipe` and sent as `campaignId`.
  `Pipe`/`Pipe.DEFAULTS` (empty now) only supply id validation (a lowercase UUID) and legacy detection.
* Permanent campaign errors (`NOT_ENROLLED`, `CAMPAIGN_NOT_FOUND`, `CAMPAIGN_CLOSED`, `TAKE_NOT_ELIGIBLE`) are not retried and
  show plain wording; choosing another campaign clears the failure.
* `POST /v1/sessions` sends `campaignId`, `deviceId`, `deviceFingerprint` (manufacturer/model/device/hardware/android release
  from `device.json`), `createdAt`, `recordingStatus`, `appVersion` and `take` (`verdict` good|redo, `reasons`,
  `durationSeconds`, `poseJumps`, `longestTrackingLossFrames`, read from the manifest).

## What is uploaded

All paths are relative to the session folder. The allow-list is `cloud/path-rules.json` (checked by both the Kotlin tests and
`cloud/check_backend_rules.py`; its `required` flags describe the raw-collecting case with an optional export, the app
applies `CloudFileRules.isRequired(path, rawRequired)` below). Objects live at
`campaigns/<campaignId>/<sessionId>/<subdir>/<file>`.

| Path | Source | Required for SYNCED |
|---|---|---|
| `manifest.json`, `device.json` | session folder | always |
| `export/<stem>/RGB_<stem>.mp4`, `AR_Pose_<stem>.txt`, `posecam_export.json` | pipeline export, one folder per clean segment | always: every segment the exporter produced |
| `intrinsics.json`, `poses.csv`, `frame_metadata.csv`, `imu.csv` | session folder | only if the campaign's `collectRaw` is true |
| `frames-00000.zip`, `frames-00001.zip`, … | built from `frames/*.jpg`, 1,000 frames per chunk | only if `collectRaw` is true |

`collectRaw` is read from the cached campaign when the recording is queued and stored on the session row (`rawRequired`).
When it is false the raw files are **not queued at all**. The backend stays the authority: `requirements.rawRequired` in the
`POST /v1/sessions` answer (or a `409 INCOMPLETE` naming missing raw files) makes the app queue the raw files after all.

### Frame chunks

A 2.5 minute take is about 4,500 JPEGs. One queue row and two API calls per JPEG would be ~9,000 calls per take, so
frames travel in zip chunks: chunk *n* holds frame indices `n*1000 … n*1000+999`.

* Built **one at a time, just before upload**, into `getExternalFilesDir(null)/upload-staging/<session>/` (not
  `cacheDir`, which the OS may purge) and deleted as soon as the chunk is verified. At most ~35 MB is staged.
* **Deterministic:** entries sorted by name, STORED, fixed 1980-01-01 timestamp, no extras, written by `DeterministicZip`
  (not `ZipOutputStream`, whose DOS time depends on time zone and file mtimes). The backend records the SHA-256 declared at
  presign, so a chunk rebuilt after a crash must be byte-identical. Tested across time zones and file mtimes.
* A JPEG that failed to write is simply absent from its chunk (`poses.csv` still says `saved`; `manifest.json`
  `images.write_failures` is authoritative). The uploader does not "fix" this.
* The queued size of a chunk is an estimate (sum of JPEG sizes) until it is built; idempotency for chunks therefore compares
  the number of JPEGs, never sizes.

To restore a recording from S3: download the plain files, unzip every `frames-*.zip` into `frames/`.

### Pipeline export (what the marketplace reviews and pays for)

The export is the deliverable: `RGB_<stem>.mp4` + `AR_Pose_<stem>.txt` + `posecam_export.json` (~50 MB, against ~3.4 GB per hour
of raw data), so it **holds up SYNCED**. Once a recording has a campaign, `PipelineExportStage` runs
`PipelineExporter.export()` on it, automatically, and queues the result:

```text
export/<stem>/RGB_<stem>.mp4
export/<stem>/AR_Pose_<stem>.txt
export/<stem>/posecam_export.json
```

* **One folder per clean segment** (`-s1`, `-s2`, …), so one recording can become several labelable sets. `<stem>` is exactly
  what `SessionExport.stem` writes: `YYYY-MM-DD-HH_MM_SS-<6 lowercase hex>-s<N>`, and the stem inside each filename must equal
  its folder's (the server answers `403 PATH_NOT_ALLOWED` otherwise). `UploadPlan.forExport` refuses a folder that breaks this
  or lacks one of the three files, so an export is queued whole or not at all.
* **It runs inside the upload pass**, on the upload worker's background-priority threads, after the session has a campaign and
  before any file is sent. It is **never run while a take is being recorded**: the encoder's progress tick checks, so a take
  that starts mid-export stops it within about a second, its half-written output is deleted, and it simply runs again after Stop.
* **Written to staging** (`upload-staging/<session>/export/`), queued as `EXPORT` rows at the **front** of the queue, and deleted
  from staging as each file is verified. If staging is wiped before upload, the export is made again.
* **The session completes only after the export files are VERIFIED** (`sessionsReadyToComplete` needs `exportState = DONE` and
  every required row verified).
* **The rotation is a setting, not a question per take.** `rotateDegrees` (which way is up so the gripper jaws point up) lives in
  **Cloud sync settings → Pipeline export** and is the same value manual "Export for pipeline" uses (one key,
  `export_rotation_degrees`). Until it has been set, **nothing is exported** (a wrongly rotated video would be uploaded and
  reviewed unnoticed); after a good take the collector is asked once. Exports that waited start as soon as it is chosen.
* **One video or several.** By default a recording is cut into one video per jump-free stretch (the reference exporter's rule):
  at every ARCore pose jump, and wherever more than 5 camera images in a row were dropped. **Cloud sync settings → "Keep each
  recording as one video"** exports the whole take as a single `-s1` video instead (jumps stay inside the video and are listed
  in `posecam_export.json`). Off by default; it applies to exports made after it is switched on.
* **A recording whose export is impossible is never synced, and says why.** Nothing is hidden behind "Synced":

  | Why there is no export | Recordings row | Retried? |
  |---|---|---|
  | off protocol (not 640×480, 30 fps, auto focus) | `Not uploaded: off protocol (wrong size, frame rate or focus)` | no |
  | killed take, no clean stretch, frames missing | `Not uploaded: it cannot be exported` | no |
  | crashed (storage, encoder) | `Not uploaded: export failed` (with a Retry button on the row) | only by **Retry pipeline export** |
  | an export file refused/failed to upload | `Upload failed` (with a Retry button on the row) | only by retry |
  | export still to be made | `Preparing the video…` | automatically |
  | no rotation chosen yet | `Set the video orientation to upload` | when it is chosen |

  Tapping such a recording offers **Why isn't it uploaded?**, which shows the exporter's own message (e.g. "recorded at
  640x360, not the team's 640x480"). The other files may already be uploaded; the session simply never completes.
* **A recording that cannot be exported stays on the phone** (it is never synced, so retention never touches it): the raw
  frames are the only source an export can be remade from.
* Raw data is optional and per campaign (`collectRaw`, default false); without it a recording is ~50 MB instead of ~3.4 GB/hour.

## Statuses and what a collector sees

* Recordings screen: a second line per recording, prefixed with the campaign title once filed (`On this phone only`,
  `Choose a campaign to upload`, `Not uploaded: redo take`, `Not uploaded: recording incomplete`, `Belongs to another account`,
  `Waiting for Wi-Fi`, `Queued`, `Uploading 45%`, `Verifying`, `Preparing the video…`, `Synced`, `Upload failed`), a banner with
  progress and a `Sync now` / `Retry` button, `N synced, M waiting` in the storage header, and a **Cloud sync settings** button
  (upload over Wi-Fi only / any network / manual; the video rotation for pipeline exports; delete synced recordings after N days or never; ask before using mobile data).
* **Not counted.** `POST …/complete` answers with `session.marketplace` (`CHECKING`, or `NOT_COUNTED` with `CAMPAIGN_FULL` |
  `CAMPAIGN_CLOSED` | `DUPLICATE`). A not-counted recording is still SYNCED (the files are kept, the phone may free its copy) and the
  row says so: `Synced · not counted: the campaign was already full` (or `…had closed`, `…it was already uploaded`).
* **Publishing is gone.** The marketplace never sends `publishStatus`, so `PublishState.UNSUPPORTED` is the normal case
  (`markSessionSynced` sets it): retention allows deletion right after SYNCED with the export verified, `PublishTracker` asks
  nothing, and the "Retry publish" / "Why isn't it on the website?" entries never appear. (The code stays for a backend that does
  report it.)
* With cloud upload on, **Share raw recording**, **Save raw zip to Downloads** and the export dialog's **Share export** / **Save export to
  Downloads** are hidden: the app is how recordings leave the phone. Without a backend URL they are all offered exactly as before.
* Tapping a recording adds `Upload to cloud now` / `Retry failed upload`, and, while it is not in the cloud yet,
  `Choose a campaign to upload…` / `Change campaign…`. Delete now says whether the recording is synced or "the only copy".
* End-of-take dialog: the verdict, then `Uploading to <campaign> (Wi-Fi only).`, `<campaign>: waiting for Wi-Fi to upload.`, or
  `Not uploaded: redo takes are never uploaded. Record it again.` Nothing is asked. No new vibration patterns.
* **My uploads** (account screen) and **Day uploads** show the server's history: a recording is `Uploaded`, with a small second
  line from `marketplaceStatus` when the backend sends it (`Checking`, `In review`, `Approved`, `Rejected`, `Not counted`).
  `GET /v1/me/stats` days carry `campaigns` (older answers: `pipes`), no live/publishing/failed. **See progress and earnings on
  the web** opens `webAppUrl`. Old cached copies still load.
* A progress notification (foreground service, type `dataSync`) while uploading, and result notifications only when the app
  is not on screen (in-app Toasts otherwise): all synced, failed, or stalled for 3 h.
* Android 13+: the notification permission is requested once, on first opening Recordings. Refusing it never blocks uploads.

## Configuring a build

A blank URL means off. There is deliberately **no default URL**. The URL is the marketplace **API root** (no path prefix; the app
adds `/v1/...`). Resolution order: `-Pposecam.cloud.baseUrl.<buildType>`, then `local.properties` (git-ignored), then the
environment variable `POSECAM_CLOUD_BASEURL_<BUILDTYPE>`. **Release must be https**: the build fails on an `http://` release URL
and `CloudConfig` refuses cleartext outside debug builds. `posecam.web.appUrl.<buildType>` (optional) is only the fallback
for the website links until `GET /v1/meta` has been fetched once.

```bash
# debug build talking to a backend on the host (emulator loopback)
./gradlew :app:assembleDebug -Pposecam.cloud.baseUrl.debug=http://10.0.2.2:4000
# physical phone on the same Wi-Fi: forward the port over USB instead of using the host's address
adb reverse tcp:4000 tcp:4000
./gradlew :app:assembleDebug -Pposecam.cloud.baseUrl.debug=http://127.0.0.1:4000
# release: https only
./gradlew :app:assembleRelease -Pposecam.cloud.baseUrl.release=https://<api-host> -Pposecam.web.appUrl.release=https://<website>
```

Debug builds allow cleartext HTTP (`src/debug/AndroidManifest.xml`) for a local backend; release builds do not.
New permissions: `INTERNET`, `ACCESS_NETWORK_STATE`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`,
`POST_NOTIFICATIONS`, plus `WAKE_LOCK` and `RECEIVE_BOOT_COMPLETED` merged in by WorkManager.

**First launch after enabling / after updating from a LabelNow build:** queue recovery adopts the recordings on the phone but queues
nothing for them: they show "Choose a campaign to upload" (good, complete ones only), so there is no surprise backlog upload.

## Backend

The DataCollect marketplace backend (see `marketplace_backend/docs/APP_API_CONTRACT.md`). Two response styles: marketplace
endpoints (`auth/*`, `meta`, `me/campaigns`, …) answer `{ok, data}` / `{ok:false, error:{code, message, fieldErrors?}}`; the recording
endpoints (`sessions/*`, `me/stats`, `me/uploads`) answer bare JSON and `{error:{code, message, details?}}`. Both put `code` and
`message` under `error`, so one parser reads both (`CloudHttpException.fieldErrors` carries the form errors). Every call except
`auth/*` and `meta` needs `Authorization: Bearer <accessToken>`; recording calls also send `X-Device-Id`. Presigned-URL PUTs to
storage carry no auth header. R2 has no whole-file SHA-256 for multipart objects, so multipart files are verified by size plus the
per-part SHA-256 values the phone declared; single PUTs carry a signed SHA-256 that R2 checks on receipt.

**Checksums:** `S3Transport.put` forwards every header the backend returns except Content-Type/Length/Host, and that must stay.
The server returns `x-amz-checksum-sha256` as a signed *header* because R2 ignores it as a query parameter (silently, so
"verified" would shrink to a size check). If a PUT ever fails with `SignatureDoesNotMatch`, do not drop the header: that silently
disables integrity checking.

### Accounts

One account for the app and the website: sign up or in with the same **email and password** (`AuthActivity`, once, on first
launch). Recording never needs it; uploading does.

* **Sign-up** needs the box "I accept the Terms and Privacy Policy" (links from `GET /v1/meta`: `termsUrl`, `privacyUrl`), with a
  plain note that frames can show homes and people, and sends `{name, email, password, role: "CONTRIBUTOR", termsVersion}`
  (`termsVersion` from meta, fetched at submit). The API's `fieldErrors` are shown per field. **Forgot password** opens
  `passwordResetUrl`; **Delete account** (account screen) explains and opens `webAppUrl`. Meta is cached in prefs
  (`AppMetaStore`), with build-config fallbacks until it was ever fetched.
* **Tokens.** `tokens` = a 15-minute access token and a 30-day refresh token that **rotates on every use** (a reused one revokes the
  whole login). Both live in the same private SharedPreferences (`AuthStore`, no backup), never logged; the password is never stored.
* **Refresh** (`TokenRefresher`, single-flight): on `401 UNAUTHENTICATED` any authenticated call (`CloudApiClient`, `AccountApiClient`,
  and so the upload queue) refreshes **once**, stores the new pair and retries the request **once**. Concurrent 401s make exactly
  one `/v1/auth/refresh` call (the others wait and use its result). If refresh answers 401 the login is over: tokens go, the email,
  recordings and queue stay, and the app asks for a sign-in. A network error or 5xx during refresh is retryable and signs nobody out.
  The queue also refreshes proactively when the access token is within ~60 s of expiry before each pass (and `getToken()` does so
  before each call).
* **Log out** (account screen) calls `/v1/auth/logout` with the refresh token (best effort, offline is fine), then clears the tokens.
* **Recordings belong to the account that made them.** The account id (`user.id`) is stored on every session row when the take
  starts (the last signed-in account if the login had lapsed). The queue only works for the signed-in account's rows: another
  account's recordings stay untouched and read **"Belongs to another account"** until that account signs in again. Rows from before
  accounts were tracked have no account and are adopted by the first account that signs in afterwards.
* **My uploads / account screen:** total recordings uploaded and a day-by-day list, counted by the server when a recording is
  fully uploaded, so deleting recordings from the phone never lowers it. The last answer is cached for offline viewing.
* **Delete all synced** (Recordings): one tap removes every synced recording that is safe to remove (every file verified, export
  included), without waiting out the retention period. Synced recordings still needed are kept and counted in the confirmation.

## Testing

* `./gradlew testDebugUnitTest --offline`: Robolectric (real Room, SharedPreferences, dialogs), MockWebServer and fakes. Notable
  suites: `TokenRefresherTest` (single-flight refresh, rotation, 401 vs network failure), `AuthenticatedQueueTest` (the queue over the
  real client and refresh), `AccountBindingTest` and `UploadDatabaseTest` (account scoping, legacy adoption),
  `UploadDatabaseMigrationTest` (v3→v4 against the exported schema) and `PipelineExportSettingsTest` (v1→v4),
  `CampaignChoiceTest` / `CampaignCatalogTest` (campaign flow), `PipelineExportUploadTest` (export blocks SYNCED; fake exporter,
  `MediaCodec` does not run on the JVM) and `RecordingToUploadIntegrationTest` (real `PoseRecorder` → coordinator → processor,
  verifying what reaches S3 equals what the recorder wrote).
* The pre-marketplace Python backend in [`backend/`](../backend/README.md) and `cloud/dev/e2e_backend.py` are not updated for
  campaigns or the new auth; they are no longer the app's backend.

## Known limits

* `PipelineExporter` (the real MP4 encoder) is verified against the reference exporter by its own tests; the automatic export
  *stage* around it is verified with a fake exporter, and has not run on a phone.
* Exports are made only inside an upload pass, so on the default Wi-Fi-only policy they wait for Wi-Fi even though making them needs
  no network.
* After a hard kill, an in-flight PUT may already have landed, so a re-send can leave two identical S3 versions of one key.
* The campaign list, the refresh flow and the new screens are verified by unit tests against fakes and MockWebServer only; the
  backend was written in parallel and the app has not talked to it. A recording cannot be made on the emulator (no ARCore).
* The fallback website links (`/terms`, `/privacy`, `/forgot-password` under `posecam.web.appUrl`) are a guess until
  `GET /v1/meta` has been fetched; the contract defines no refresh-token lifetime the app can show.
