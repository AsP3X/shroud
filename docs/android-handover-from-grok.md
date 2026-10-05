# Shroud Android port: handover from Grok (phase A) to Claude (UI)

Written 2026-10-02 from local branch `grok/phase-a` at `2e53e5a`. This file is the phase-A
report. `docs/android-handover.md` stays the owner's split and is not edited here. Where this
file and §2 of that document disagree, this file is what the code does.

Specs remain at
`/Users/nvorberg/.claude/projects/-Users-nvorberg-Documents-development-shroud/android-port-specs/`.
`$SRC` means `android/app/src/main/java/de/corespace/shroud`.

Phase B (G9–G11) has not started. Claude's C1–C18 are unblocked for UI work that consumes the
contracts below. A ring on a killed app waits for G9, which attaches `CallSystem`.

---

## 1. Current state

### 1.1 Branches and CI

- **Integration branch:** local `grok/phase-a` at `2e53e5a` (`TASK: Merge the system e2e into the phase-A branch.`). Parents: `1324f72` and `e7d40c2`. Not pushed. Not merged into `dev`.
- **`dev`:** local `8b6c976`, one commit ahead of `origin/dev` at `c0dd437`. That commit fixes `RealtimeClientTest`. It has not been pushed (OPEN-1).
- **GitHub Android CI** on `origin/dev` `c0dd437` is still the red run from the handover (one `RealtimeClientTest` failure). Nothing from phase A is on the remote, so that run has not been replaced.
- **Item branches** (all local, all contained in `grok/phase-a`):

| Branch | Tip | Subject |
| --- | --- | --- |
| `grok/g2-contracts` | `fc05a99` | ADD: Cover the onboarding, devices, photo library and UI flag contracts with unit tests. (impl `b3961e1`) |
| `grok/g3-push` | `78dd114` | ADD: Receive messages through UnifiedPush and the background connection. |
| `grok/g4-call-media` | `6fdbbc8` | FIX: Read call transceivers when they are used… (impl `691b1de`) |
| `grok/g5-call-system` | `0263309` | ADD: Ring a locked phone with a CallStyle full-screen notification… |
| `grok/g6-transcription` | `2787315` | ADD: Transcribe voice notes on device with a sealed per-chat language memory. |
| `grok/g7-media` | `89f2aa4` | ADD: Bake photo edits at send, share decrypted media in memory, and keep camera captures out of the gallery. |
| `grok/g12-parity` | `dc8d485` | FIX: Bind cached plaintext to the sender… (iOS parent `46e8c0b`) |
| `grok/g8-e2e` | `e7d40c2` | ADD: Cover killed-app push, Doze, standby, reboot, and removal in the system e2e. |

- **Follow-up commits on `grok/phase-a` after the item merges:**
  - `3515c45` FIX: Use the app seal for transcription and satisfy the API 30 lint checks.
  - `1324f72` FIX: Call the current onboarding, memory, announcement, and camera URI APIs.
- **`android/w3-*` branches** are still Claude's UI, based on `c0dd437`. Read them. Do not commit to them. Base the UI branch on `grok/phase-a`, because `dev` does not contain K2–K11.

**Gate on the merge** (`2e53e5a`, log `/tmp/shroud-phase-a-gate-merge.log`): `BUILD SUCCESSFUL in 4m 17s`. Tasks: `:app:testDebugUnitTest`, `:app:lintDebug`, `:app:verifyNoMaterial`, `:app:verifyNoGoogleServices`, `:app:verifyNoGoogleClasses`, `:app:assembleDebug`, `:app:assembleRelease`, offline. Unit tests: 2336, 0 failures, 1 skipped.

Earlier, on `1324f72` before G8 was merged: `/tmp/shroud-phase-a-gate2.log` BUILD SUCCESSFUL in 3m 50s, and `/tmp/shroud-phase-a-gate3.log` BUILD SUCCESSFUL in 4m 54s. Those logs do not print a test total. The run that found the seal bug (`/tmp/shroud-phase-a-gate.log`) counted 2334 tests, 1 failed, 1 skipped; `3515c45` fixed that failure. G8 added the two tests that bring the total to 2336.

The G8 device matrix ran the APK built from `e7d40c2` (parent `3515c45` in the G8 worktree), then that commit was merged. The merge adds the FileProvider and the deprecation call-site edits. It was not re-run on a device.

### 1.2 Phase A delivered

G1, G2, G3, G4, G5, G6, G7, G8 and G12 are on `grok/phase-a`. Evidence and what is still owed are in §3. The short version:

- K2–K5, K6, K7, K9–K11 are implemented and wired. Accessors are in §2.
- `CallMediaEngine` and `CallSystem` exist. Production does not call `CallController.attach`. That is G9. Until then a call push reaches the controller and does not ring.
- The system e2e (`android/e2e/system-e2e.sh` + `SystemE2eTest`) passed twice in a row on `shroud_api37` (`emulator-5560`) and twice on `shroud_api30` (`emulator-5562`). Each log prints `BLOCKED on G9: killed-app ring did not post a CallStyle notification` and then `RESULT pass`. That block is the unattached call system. It is not a failure of the script.
- iOS and web plaintext caches bind the sender. `web/src/screens/AppShell.tsx` `mergeMessages` is still id-only. That file is a `.tsx` and was left for a later UI pass (§3 G12).

### 1.3 Still stubbed for the UI pass

| What | Where | State on `grok/phase-a` |
| --- | --- | --- |
| W3 screens | `$SRC/ui/**` except the four files in §2.6 | The interim shell and the W0 stubs. Claude's real screens are on `android/w3-*`. |
| `CallActivity` | `$SRC/ui/calls/CallActivity.kt` | `onCreate` calls `finish()`. C14 replaces the body. The system e2e starts this activity on purpose (§4). |
| Delivery UI | `ui/settings/push/**` | Stub. K6 state is live at `push.registration`. |
| Call screen | `ui/calls/**` | Stub. Video tracks are on `callsMedia.engine` once G9 attaches it. |

`AppContainer` onboarding shims are already gone (`1324f72`). G2 had kept them as `@Deprecated` until G9. A later rule forbids deprecated APIs, so the shims were removed in phase A. Do not put them back.

### 1.4 Open owner items

- **OPEN-1.** Phase A is local only. Neither agent pushes or merges into `dev` until the owner says so.
- **OPEN-2.** The ntfy APK was not downloaded. `android/e2e/up-stub/` is the only distributor the e2e used.
- **OPEN-3.** Answered in §2.1 and on `OnboardingService.establishFromSignup`. There is no `KEYS_EXIST`.
- **OPEN-4.** `design/Android-App.pen` was not opened or saved by this pass. Unsaved Pen edits, if any, are still the owner's.
- **OPEN-5.** The APK stays 64-bit (`arm64-v8a`, `x86_64`). No `armeabi-v7a` build was added.
- **OPEN-6.** Physical phones, a Samsung launcher check, Bluetooth answer, a cellular call during a call, ring latency, and the whisper P7 gate are still owed. Emulator RTF figures in `android/README.md` are unchanged and still miss 0.3.
- **OPEN-7.** The spec folder was readable. It was not copied into the repo.

### 1.5 Machine after this handover

Throwaway AVDs `emulator-5560` and `emulator-5562` were shut down (`adb emu kill`). The throwaway stack `shroud-g8` (API 18080, ntfy 2587, state `/tmp/shroud-g8-e2e`) was stopped with `android/e2e/stack-down.sh`. The owner's emulator `Pixel_10_Pro_XL` (`emulator-5554`) was left running. The dev database container `shroud-postgres` was not touched.

### 1.6 Design

No `.pen` file was edited. The visible product UI did not change. The API 30 removal worker posts a short platform notification (§2.5). That is a WorkManager foreground-service requirement, not a new screen, so it has no frame.

---

## 2. Contract deltas Claude consumes

R1–R5 and K1, K4–K7, K10–K12 stand as written in `docs/android-handover.md` §2, with the deltas below. Signatures were not narrowed.

### 2.1 K2 — `establishFromSignup` (OPEN-3)

`PUT /keys/bundle` upserts `device_identity_keys` `ON CONFLICT (device_id) DO UPDATE` and returns 204. The server has no `KEYS_EXIST` error.

- A retry with the **same** phrase overwrites this device's bundle and succeeds.
- A **different** phrase fails on the client with `CryptoException.PhraseDoesNotMatchAccount` before a foreign key is published.
- `KEYS_REQUIRED` (404, the account has no identity key) is the only server error `establishFromSignup` swallows.
- `accountHasNoKey` is true only for `ErrorCodes.KEYS_REQUIRED` or any 404 from `GET /keys/identity/{user}`.

The sentence in the old handover that says a second `establishFromSignup` fails with `KEYS_EXIST` is superseded by this.

### 2.2 Accessors (shims removed)

Call these. `AppContainer` no longer exposes `api`, `sessionController`, `cryptoController`, `bip39`, `needsLocalNetworkPermission`, or `hasScreenLock`.

| Accessor | Type |
| --- | --- |
| `auth.onboarding` | `OnboardingService` |
| `auth.sessionController` | `SessionController` |
| `auth.devices` | `DevicesController` |
| `keys.cryptoController` | `CryptoController` |
| `keys.bip39` | phrase list and validation |
| `keys.uiFlags` | `UiFlags` (prefs `shroud.ui`, wiped with the other non-kept prefs on Log Out) |
| `media.photoLibrary` | `PhotoLibrary` |
| `media.camera` | `CameraCapture` |
| `media.sharing` | `MediaSharing` (`sharingIfBuilt` is null until the first use) |
| `images.editRenderer` | `MediaEditRenderer`, registered with `images.registerEditBaker` at process start |
| `push.registration` | `PushRegistration` |
| `transcription.voice` | `VoiceTranscription` |
| `calls.controller` | `CallController` |
| `callsMedia.engine` | `WebRtcCallMediaEngine`. Not attached. |
| `callsSystem.system` | `AndroidCallSystem`. Not attached. |
| `callsSystem.screenHooks` | the same object as `system` |

`CallsSystemModule.onCallPush` exists and also calls `CallController.handleCallPush`. Production does not use it. The only production call-push path is `PushModule` → `CallController.handleCallPush`. A second caller would ring twice after G9 attaches the system. The UI does not call either path.

### 2.3 K8 — calls, until G9

`CallController.attach(engine, system)` is how a ring reaches `CallSystem`. G8's in-process test attaches a fake engine and the real system for the duration of that test. Production startup does not attach.

`CallIntents`: extras `call_id` and `action` (`show` or `answer`). Every ring and answer `PendingIntent` targets `de.corespace.shroud.ui.calls.CallActivity`.

`CallScreenHooks`: `onCallScreenShown(callId)` starts the `phoneCall` foreground service if an earlier start was refused. `onCallScreenHidden()` is the pause hook. C14 calls these from `onResume` and `onPause`, and on `answer` calls `CallController.acceptIncoming()`.

`missed_call` already goes to `CallController.handleCallPush`, which calls `system.postMissedCall` when a system is attached.

Video for the call screen, once attached: `callsMedia.engine.eglContext`, `localVideoTrack`, `remoteVideoTrack`, `remoteScreenTrack`. `callsSystem.isOnEarpiece` is the route flag the screen may read.

Incoming shade text is `Incoming voice call` / `Incoming video call` (`CallNotices`). On API 30, `NotificationCompat.CallStyle` replaces the text with the AndroidX string `Incoming call`. Matchers accept either. API 37 `dumpsys notification` prints the pending intent as `de.corespace.shroud startActivity` and omits the activity class. The ring record is package + `channel=calls.incoming` + that text.

Missed-call shade: body `Missed call`, action title `Call back`.

### 2.4 K9 — camera URIs

`takePhoto()` returns `MediaImageSource.ContentUri`. The URI is a `FileProvider` content URI, authority `${applicationId}.cache`, paths in `res/xml/cache_paths.xml`. The file stays under `cacheDir/shroud-*` and is not inserted into MediaStore. `Uri.fromFile` is not used. `startRecording(true)` returns false when `RECORD_AUDIO` is missing, then the CameraX call is allowed to proceed.

### 2.5 Removal confirmation on API 30

`DeviceRemovalWorker` is expedited. On API 30 and below, WorkManager runs that as a foreground service and calls `getForegroundInfo()` before `doWork()`. The worker posts notification id `7102`, channel `push.background` (`NotificationChannels.BACKGROUND_CONNECTION`), title `Shroud`, text `Checking this device`, silent, ongoing. API 31 and above do not call `getForegroundInfo()`. The worker still confirms with `GET /auth/me` before wiping. A missing channel is created at `IMPORTANCE_MIN` with the name `Background connection`.

### 2.6 Deprecated APIs

Do not call a deprecated API, do not add a `@Deprecated` shim, and do not `@Suppress` a deprecation to keep the old call. If the platform has no current replacement, implement the behaviour in the project. A platform method that exists only on API 30–32, with the replacement only on a newer SDK, stays behind an SDK check. On versions that have the new API, the new API is what runs.

Already replaced, with no new screen:

| File | What changed |
| --- | --- |
| `ui/onboarding/SignUpScreen.kt`, `LogInScreen.kt`, `OnboardingSupport.kt` | Call `auth.onboarding`, `auth.sessionController`, `keys.bip39`. |
| `ui/wipe/DeviceWipeOverlay.kt` | TalkBack uses `AccessibilityEvent(TYPE_WINDOW_CONTENT_CHANGED)` with `CONTENT_CHANGE_TYPE_CONTENT_DESCRIPTION`. The spoken text is still `DeviceWipeController.feedback`. |
| `ShroudApplication` | `Application.onTrimMemory` plus `addOnTrimMemoryListener`. Whisper releases at `TRIM_MEMORY_BACKGROUND` and above. |
| `CameraCapture` / `MediaModule` | FileProvider content URIs (§2.4). |

`android/e2e/up-stub` `DistributorReceiver` still calls `intent.getParcelableExtra("pi")` without a `Class` when `SDK_INT < 33`. That is the API 30–32 entry. API 33+ uses the `Class` overload. javac notes the old overload. Leave it. Do not suppress it.

### 2.7 Notifications while the shell is up

`NotificationsModule.onProcessStart` sets `NotificationsController.isSignedIn` from `session != null` and keeps collecting it. A cold process can post. `ui/ShroudApp.kt` `InterimSession` also sets `isSignedIn` from the session and `isUnlocked` from the vault. `onPushWhileRunning` returns true (swallows the shade) when `isSignedIn` is false. When the activity is resumed, unlocked, and banners are on, it shows the in-app banner and returns true. When the activity is not resumed it returns false and the dispatcher posts the shade notification.

C3's shell keeps that split. A shell that forces `isSignedIn` false while a session exists will swallow shade notifications.

### 2.8 Name cache and dedup

UnifiedPush remembers the sender name from the push **before** dedup. The socket often posts first; a later named push would otherwise be dropped and the shade title would stay `Shroud`. Dedup key is `rawKind:messageId`. After reboot the background socket uses `NotificationNameCache` (`notification-names.sealed`). The cache is filled by `rememberAll` when contacts load and by that push path.

A named message notification has `android.title` containing the peer name and `android.text` of `New message` (or `N new messages`). The system e2e treats a title of `Shroud` as not named.

Permanent background-connection text is `Connected to receive messages` (`PushCopy.CONNECTED`), channel `push.background`.

---

## 3. Item reports

### G1. Green baseline

- **Branch:** the gate fixes landed on `grok/phase-a` (`3515c45`, `1324f72`), not as a new push of `8b6c976`.
- **Local gate:** green on `1324f72` (logs in §1.1). GitHub Android CI was not run, because nothing was pushed.
- **Owed:** OPEN-1 push, then a green GitHub Android run.

### G2. K2, K3, K4, K5

- **Commits:** `b3961e1`, `fc05a99`.
- **Wiring:** `WiringTest` reaches `auth.onboarding`, `auth.devices`, `media.photoLibrary`, `keys.uiFlags`. It no longer calls the removed shims.
- **Owed:** nothing in core. UI on `android/w3-lock-onboard`, `android/w3-settings-b` and `android/w3-composer` still has the old direct calls. C2 retargets them (§4). Do not restore shims.

### G3. UnifiedPush and the background connection

- **Commit:** `78dd114`, plus the G8 fixes in `e7d40c2` (name remembered before dedup, `USER_UNLOCKED`, removal foreground info, `isSignedIn` from the session).
- **Distributor:** `android/e2e/up-stub`. It subscribes to the local ntfy. `REGISTER` is `http://localhost:<port>/up<random>?up=1`. On API 31+ a broadcast must not start its foreground service, so the harness starts the exported `StarterActivity` (`Theme.NoDisplay`), which starts `ForwarderService` and finishes. The service type is `specialUse` (with `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`). `dataSync` is refused for a process that was started from `BOOT_COMPLETED` on API 35+.
- **Server:** `client:"android"` on the web-push subscription. Dedup window 10 minutes. `device_removed` is confirmed with `GET /auth/me` before the wipe.
- **Device:** the G8 matrix covered a named push to a killed app, removal after `/auth/me`, the background connection with the user not shown online, both paths posting once, and a `read` that closes the chat. The script disables `com.google.android.gms` when that package is installed. These AOSP images have no GMS. The real ntfy app was not installed (OPEN-2).
- **Owed:** ntfy from F-Droid, on a phone the owner provides.

### G4. Call media

- **Commits:** `691b1de`, `6fdbbc8`.
- **JVM:** SDP sections, sender bitrates, and stats tests are in the unit-test gate. `verifyNoGoogleClasses` is part of that gate.
- **Engine:** `callsMedia.engine`. `preferRelay()` edits the live `PeerConnection` configuration. The DTLS certificate SHA-256 must match the fingerprint in the sealed description. `Factory` and `EglBase` are process-wide; `close()` does not release them.
- **Owed:** `CallMediaEngineLoopbackTest` exists and this pass has no device log of it. A scripted Android↔web call (offer, answer, ICE, frames both ways, hangup, screen share) was not run. Production still has no `attach`, so that call cannot be placed through `CallController` until G9.

### G5. Call system

- **Commit:** `0263309`, plus the foreground-start race fix in `e7d40c2`.
- **Order:** CallStyle notification with a full-screen intent, then Telecom, then the `phoneCall` foreground service. `ForegroundServiceStartNotAllowedException` leaves the notification ringing. `onCallScreenShown` promotes the service later.
- **Race:** `startForegroundService` followed by `stopService` before `onStartCommand` crashes with `ForegroundServiceDidNotStartInTimeException`. `AndroidCallSystem` sets `foregroundStartPending` when the start is accepted and `stopAfterStart` if `stopService` happens first. `onServiceStart` calls `startForeground`, then stops if the session is already gone. Unit test `hangupDuringForegroundStartStillPromotesBeforeStopping`.
- **Device:** inside `SystemE2eTest.aliveProcessPaths` the test attaches the real system, and both emulators posted the incoming call and then `Call back` / `Missed call`. A killed app does not attach, so the script's killed-app ring prints `BLOCKED on G9` and continues. A dumpsys of a production ring to a killed or backgrounded phone waits for G9.
- **Boot process:** on API 35+ a process started from `BOOT_COMPLETED` can later be refused `phoneCall`, `microphone`, `camera`, `mediaPlayback`, `mediaProjection`, and `dataSync` foreground services for the rest of that process. `specialUse` is allowed. Call types were not changed. The killed-app ring force-stops first, so the next process is not the boot process.

### G6. Transcription

- **Commit:** `2787315`. `3515c45` passes the app `StorageSeal` into `TranscriptionSession` (exactly one `StorageSeal(` in `src/main/java`, in `AppContainer.kt`).
- **JVM:** `TranscriptionEngineTests`, language memory, and the transcriber tests are in the unit-test gate. Language stats stay sealed on device.
- **README:** the P7 table is unchanged. Emulator worst RTF for base q5_1 is 0.77 on API 37 and 0.62–0.65 on API 30, against a limit of 0.3.
- **Owed:** a device transcription of an Android-recorded note and an iPhone-recorded note. Physical-phone P7 (OPEN-6).

### G7. Edit, share, capture

- **Commit:** `89f2aa4`, plus FileProvider in `1324f72`.
- **JVM:** render tests (crop, rotate, filter, drawing), identity-edit passthrough, and revoked share URIs are in the unit-test gate. The baker is registered at startup.
- **Share authority:** `${applicationId}.media` on `DecryptedMediaProvider`. Save uses MediaStore `IS_PENDING`.
- **Owed:** a device check that a capture file is under cache / `no_backup` and absent from MediaStore. Camera, editor, and viewer screens are C12–C13.

### G8. System e2e

- **Commit:** `e7d40c2`, merged as `2e53e5a`.
- **How to run** (after `stack-up.sh`, throwaway prefix only):

```bash
SHROUD_E2E_PREFIX=shroud-g8 SHROUD_E2E_STATE=/tmp/shroud-g8-e2e \
SHROUD_E2E_API_PORT=18080 SHROUD_E2E_NTFY_PORT=2587 \
android/e2e/system-e2e.sh emulator-5560
```

The script accepts only `emulator-5560` and `emulator-5562`. It bundles `android/e2e/peer/peer.ts` with esbuild on every run. The peer listens on `127.0.0.1`. The phone reaches the API and the peer at `10.0.2.2` (instrumentation args `shroudApi`, `shroudPeer`). `adb reverse` to `127.0.0.1` did not deliver a device `nc` request to the host listener. The up-stub still uses `http://127.0.0.1:<ntfy port>` through `adb reverse`, and Java's `HttpURLConnection` on that path worked. Do not point `shroudPeer` at `127.0.0.1`.

`am instrument` exits 0 even when a test fails. Failure is `FAILURES!!!` or `Process crashed` in the output. A pass ends with `OK (` and `INSTRUMENTATION_CODE: -1` (`Activity.RESULT_OK`). A crash uses code 0.

- **Green logs** (this script, the `e7d40c2` APK, two in a row on each device):
  - `/tmp/shroud-g8-e2e/runs/emulator-5562-20261002T130458.log` — API 30, `RESULT pass`
  - `/tmp/shroud-g8-e2e/runs/emulator-5562-20261002T130704.log` — API 30, `RESULT pass`
  - `/tmp/shroud-g8-e2e/runs/emulator-5560-20261002T130908.log` — API 37, `RESULT pass`
  - `/tmp/shroud-g8-e2e/runs/emulator-5560-20261002T131106.log` — API 37, `RESULT pass`

  Each prints the G9 block before `RESULT pass`. On API 37 the post-reboot message notification was named (`connected=1` on that step). API 30's killed-app step also showed `connected=1`, which is the background-connection notification still up.

- **In-process (`SystemE2eTest.aliveProcessPaths`):** background socket while locked and not online, push alone, both paths once, `read` closes the chat, ring and missed call after the test calls `attach`, removal only after `GET /auth/me` confirms, then the session file is deleted after revoke.
- **Shell (`system-e2e.sh`):** killed-app named push, `read` closes it, Doze (`dumpsys deviceidle force-idle`), standby bucket `rare`, reboot resume of the background connection, removal while force-stopped. Killed-app ring is the G9 block.
- **Reboot detail the UI must keep working:** `am force-stop` sets `stopped=true`. A push with `FLAG_INCLUDE_STOPPED_PACKAGES` starts the process and does not clear the flag, so `BOOT_COMPLETED` / `USER_UNLOCKED` would be dropped. The launcher runs `ShroudApp` / `InterimSession.start()`, which calls `finishInterruptedWipeIfNeeded()` and deletes the session when a wipe is pending. The script therefore starts `.ui.calls.CallActivity` as root. Today that activity only `finish()`es, so the interim session does not start. It then waits until `package-restrictions.xml` has no `stopped="true"` on `de.corespace.shroud` (API 30 text XML, API 31+ binary XML via `abx2xml`, about 10 seconds). `BootCompletedReceiver` is not direct-boot aware and handles both `BOOT_COMPLETED` and `USER_UNLOCKED`. The shell cannot send `BOOT_COMPLETED`. After unlock the script waits 25 seconds so a later stub start is outside the boot allowlist.
- **Owed:** re-run this matrix on a build of `2e53e5a` if a later change touches push, boot, or `CallActivity`. GitHub does not run it until someone pushes.

### G12. iOS and web parity

- **Commits:** iOS `46e8c0b`, web `dc8d485`.
- **iOS** (`ios/shroud/Services/**`, `ios/ShroudShared/**`, `ios/shroudTests/SenderBoundPlaintextTests.swift`): a cached message or held bubble is reused only for the same sender. After an idempotent media replay the client stores the server row's payload and blob id.
- **Web** (`web/src/crypto/plaintextCache.ts`, `web/src/messaging.ts`, selftests): the vault AAD for a body is `${vaultName}#sender:${sender}`. `loadPlaintext` requires that AAD. A body sealed under the name alone does not open. `loadUnboundPlaintext` exists for the vault-migration selftest. Decode does not call it.
- **Left in place:** `web/src/screens/AppShell.tsx` `mergeMessages` (around line 134) still keys the in-memory thread by message id alone. Changing it is a `.tsx` edit, which phase A does not make. The sealed cache is still sender-bound, so a re-served id does not decrypt as the other sender's plaintext. No visible change, so no `design/webclient.pen` edit.
- **Validation recorded with those commits:** web crypto selftests, `tsc -b`, and `npm run build` in `web/`; iOS sender-bound tests on a simulator with parallel testing off. This handover pass did not re-run them.

### Phase B

G9, G10 and G11 have not started. G9 still attaches `callsMedia.engine` and `callsSystem.system` with `CallController.attach`, finishes any wipe hook that is still partial, and updates `docs/android-plan.md`. The onboarding shims G9 was going to delete are already gone. Claude does not attach the call system.

---

## 4. Constraints on C1–C18

- **Base branch.** Branch the UI work from `grok/phase-a` (`2e53e5a`), not from `dev` `8b6c976` and not from `c0dd437`. The `android/w3-*` branches are the starting material and they are behind. Conflicts in `$SRC/core/**`, `$SRC/di/**` (except `ShellModule.kt`), `AppContainer.kt`, `ShroudApplication.kt`, the manifest, `res/xml`, `res/raw`, or `cpp` are not Claude's to resolve. Stop and report.
- **C2 retarget.** `android/w3-lock-onboard` calls the removed shims and `net.api.identityKey`. `android/w3-settings-b` has `DevicesBackend` / `DeviceNameSeal` in UI. `android/w3-composer` queries `MediaStore` and `getSharedPreferences`. `android/w3-settings-a` calls `net.api.devices`. Replace those with `auth.onboarding`, `auth.devices`, `media.photoLibrary`, and `keys.uiFlags`. Forward 1:1 (R4). Do not add the shims back.
- **C4 wipe overlay.** If the designed overlay replaces `DeviceWipeOverlay`, keep a current accessibility event. `View.announceForAccessibility` is deprecated. The spoken string still comes from `DeviceWipeController.feedback`.
- **C14 `CallActivity`.** Implement show-when-locked, turn-screen-on, `CallScreen`, the K8 hooks, and `acceptIncoming()` on `answer`. Keep `onCreate` from running `InterimSession.start()` / `finishInterruptedWipeIfNeeded()`. The system e2e starts this activity only to clear the package stopped flag. A `CallActivity` that composes the signed-in root will delete the session when a wipe is pending, and the reboot step will fail. Full-screen intents use extras `call_id` and `action`.
- **C14 / C15 and G9.** The call screen can bind tracks and hooks now. A killed or backgrounded phone will not ring until G9 attaches the system. Do not call `CallController.attach` from UI.
- **C15 Delivery.** Map `NoPushReason` to the plan's W3-PUSH copy. Notification sentences and `PushDeliveryHooks.noDeliveryReason()` stay in core. The permanent notification text is `Connected to receive messages`.
- **C16 design.** Every new or changed screen lands in `design/Android-App.pen` through Pencil, then the owner presses ⌘S. The API 30 line `Checking this device` is the removal worker's foreground notification (§2.5). It is not a product screen. Add a frame only if the design pass decides the shade should show it on purpose.
- **C17.** UI journeys live under `androidTest/.../e2e/ui/**`. `android/e2e/system-e2e.sh` and `SystemE2eTest` stay as they are. A `CallActivity` change that breaks the stopped-flag clear is a stop-and-report, with the script line (`am start -n de.corespace.shroud/.ui.calls.CallActivity`).
- **Deprecated APIs.** The rule in §2.6 applies to UI code too.

---

## 5. Do-not-touch (additions to the owner's §5)

- `docs/android-handover.md`. Only the owner edits it.
- `CallController.attach` in production. G9.
- A second call-push path beside `PushModule`.
- `armeabi-v7a`, a downloaded ntfy APK, and a push or merge to `dev`, until the owner answers OPEN-1, OPEN-2, and OPEN-5.
- The owner's emulator `emulator-5554` and the `shroud-postgres` container.
- `.claude/**`, `design/webclient.pen`, and untracked emoji PNGs.

---

## 6. Resume prompt for Claude

```text
You are Claude, the UI engineer on the Shroud Android port
(/Users/nvorberg/Documents/development/shroud). Grok has finished phase A (G1–G8 and G12)
on local branch grok/phase-a at 2e53e5a. That branch is not merged into dev and is not
pushed (OPEN-1). dev is still 8b6c976. Implement ONLY the C* items of
docs/android-handover.md §4, in order C1 → C18, with the deltas in
docs/android-handover-from-grok.md. Do not modify core/, di/ (except di/ShellModule.kt),
AppContainer, ShroudApplication, the manifest, res/raw, res/xml, cpp, build files,
android/e2e scripts, the engine e2e, server/, docs/android-handover.md, iOS or web code.
If a contract is insufficient, STOP that item and list the gap (path, signature, why,
which C item), then continue with the next item that does not depend on it.

BEFORE C1
- Read AGENTS.md (and its rule files), docs/android-handover.md, and docs/android-handover-from-grok.md.
- Branch from grok/phase-a (2e53e5a). Do not branch from dev: K2–K11 are not on dev.
- The android/w3-* branches are your starting material (based on c0dd437). Conflicts in
  Grok-owned paths are not yours. Stop and report.
- Ask the owner about OPEN-4 (unsaved Android-App.pen edits) before any design work.
- AppContainer onboarding shims are already gone. Call auth.onboarding,
  auth.sessionController, keys.cryptoController, keys.bip39, auth.devices,
  media.photoLibrary, keys.uiFlags, media.camera, media.sharing, images.editRenderer,
  push.registration, transcription.voice, calls.controller, callsMedia.engine,
  callsSystem.system, callsSystem.screenHooks. Do not add @Deprecated shims back.
- establishFromSignup does not fail with KEYS_EXIST. Same phrase retries overwrite.
  A different phrase throws CryptoException.PhraseDoesNotMatchAccount. accountHasNoKey
  is true only on KEYS_REQUIRED or a 404 from GET /keys/identity.
- Do not call CallController.attach. G9 does that after C1–C17. Until then a killed app
  does not ring. system-e2e.sh prints BLOCKED on G9 for that step and still passes.
- CallActivity currently only finish()es. The system e2e starts it as root to clear the
  package stopped flag without running InterimSession.start(). Your CallActivity must
  not call finishInterruptedWipeIfNeeded or compose the signed-in root.
- Do not call deprecated APIs. DeviceWipeOverlay already uses an AccessibilityEvent.
  Camera photos are FileProvider content URIs (authority ${applicationId}.cache).
- One production call-push path: PushModule → CallController.handleCallPush. Do not
  also call CallsSystemModule.onCallPush.

ITEMS
C1 integrate the W3 UI branches onto a branch from grok/phase-a.
C2 remove domain logic from ui/ and consume K2–K5 (shims are already gone).
C3 shell. Keep NotificationsController.isSignedIn tied to the session. The module
   already collects it; InterimSession does too. onPushWhileRunning swallows the shade
   when isSignedIn is false, and shows a banner when the activity is resumed.
C4 lock, wipe, onboarding.
C5 chats. C6 settings A. C7 settings B (DevicesController, not DevicesBackend).
C8 contacts. C9 conversation. C10 bubbles. C11 composer (PhotoLibrary, not MediaStore).
C12 photo editors (MediaEditRenderer). C13 camera, viewer, sharing (content URIs,
   MediaSharing). C14 call screen, Calls tab, CallActivity (K8 hooks, no attach).
C15 push Delivery UI (K6). C16 Android-App.pen frames. The "Checking this device"
   notification (id 7102, API 30 removal worker) is not a product screen.
C17 UI journeys under androidTest/.../e2e/ui/**. Do not edit android/e2e/system-e2e.sh.
C18 accessibility and design reconciliation.

RULES
- Gate per item, from android/, with
  JAVA_HOME=/Users/nvorberg/Library/Java/JavaVirtualMachines/ms-21.0.11/Contents/Home:
  ./gradlew :app:testDebugUnitTest :app:lintDebug :app:verifyNoMaterial
  :app:verifyNoGoogleServices :app:assembleDebug.
- Devices: shroud_api37 port 5560, shroud_api30 port 5562, throwaway stack
  android/e2e/stack-up.sh. Never touch emulator-5554 or shroud-postgres.
- Every visible UI change lands in design/Android-App.pen through Pencil MCP
  (open -a /Applications/Pen.app design/Android-App.pen first). Tell the owner the
  .pen change is not on disk until they press ⌘S.
- Commits: one sentence starting "ADD: ", "TASK: " or "FIX: ". Do not push or merge
  into dev without the owner's go. Never rewrite history.
- When C1–C17 are done, tell the owner so Grok can start G9–G11.
```
