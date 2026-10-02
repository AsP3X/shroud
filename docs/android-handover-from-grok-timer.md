# Shroud Android port: handover from Grok to Claude (timer)

Written 2026-10-02 13:31 UTC from local `grok/phase-a` at `0499172`. This is the continuation
after `docs/android-handover-from-grok.md` (phase A, written at `2e53e5a`). That file stays the
phase-A report. Where it still says G9 has not started, or that production does not attach the
call system, this file is the later state. `docs/android-handover.md` stays the owner's split
and is not edited here.

`$SRC` means `android/app/src/main/java/de/corespace/shroud`. Specs remain at
`/Users/nvorberg/.claude/projects/-Users-nvorberg-Documents-development-shroud/android-port-specs/`.

Nothing here was pushed. Nothing was merged into `dev` (OPEN-1).

---

## 1. Read this first at 16:20 UTC

Emulators `emulator-5560` (`shroud_api37`) and `emulator-5562` (`shroud_api30`) are **already
shut down**. They are free for the UI pass. `emulator-5554` (`Pixel_10_Pro_XL`) was left
running and is still the owner's. Do not install on it, and do not run Gradle
`connectedAndroidTest` without `ANDROID_SERIAL` or `adb -s`. A Gradle connected test attaches
every online device.

Cold-boot those AVDs with `-no-snapshot`. Both have screen-lock PIN `1234`
(`android/e2e/emulator-setup.sh`). A cold boot comes up `RUNNING_LOCKED`. Instrumentation then
dies with `SecurityException: Package de.corespace.shroud is not encryption aware` and the
`am instrument` client can sit there with a process record at pid 0. Run `android/e2e/unlock.sh
<serial>` after `sys.boot_completed=1` and a focused window, and check `dumpsys user` says
`RUNNING_UNLOCKED` before installing tests. `android/e2e/emulator-setup.sh <serial>` is
idempotent and also grants `ACCESS_LOCAL_NETWORK` on API 37.

`adb shell am instrument` often exits 0 either way. A pass in these images is the runner line
`OK (1 test)`. `INSTRUMENTATION_CODE: -1` is `RESULT_OK`. A crash is code 0 plus `Process
crashed`.

Base the UI branch on **`grok/phase-a` at `0499172`**, not on `dev` (`8b6c976`) and not on
`origin/dev` (`c0dd437`). `dev` does not contain K2–K11, the gap contracts, or the release
split.

---

## 2. Branches

| Branch | Tip | On `grok/phase-a`? |
| --- | --- | --- |
| `grok/phase-a` | `0499172` | yes |
| `grok/gaps-1` | `f03c4f7` | yes, merge `34afac8` |
| `grok/w4-non-ui` | `1b98f24` | yes, merge `2cc6ef1` |
| `grok/device-checks` | `7d21316` | yes, merge `0499172` |
| `grok/g9-prep` | `7840953` | **no** |

`grok/g9-prep`'s parent is `2cc6ef1`, which is an ancestor of `0499172`. Merging it onto
current `phase-a` does not touch the device-check files. **Do not merge it into `phase-a` by
itself.** The owner merges it in the same step as `claude/android-ui`.

### 2.1 Why G9 stays off `phase-a`

`phase-a` still installs the interim `ProcessLifecycleOwner` `ON_STOP` observer in
`ShroudApplication.startProcess()`. That calls `lockChatsInMemory()` on every backgrounding and
overrides a delayed or Never auto-lock.

`claude/android-ui` already owns auto-lock. `ShellModule.startShell()` (read from that branch,
not edited) starts `AppShellController` from `MainActivity`. `onBackground` waits out
`vaultPromptInFlight`, then `PROMPT_RETURN_GRACE_MS` (1 second), and locks only if the app is
still backgrounded. `onProcessStart()` on that shell is intentionally not the start: a process
woken by a push, boot, or `CallActivity` must not run the launch sequence.
`finishInterruptedWipeIfNeeded` from that path would end the session, and the system e2e starts
`CallActivity` only to clear the stopped-package flag.

Take `grok/g9-prep` **into the UI branch** when you want to test the shell's own auto-lock.
Leaving the `ON_STOP` observer in place hides delayed and Never. Merging G9 into `phase-a`
before the shell lands removes background locking for every other build.

`CallActivity.onCreate` still calls `finish()`. Keep that until C14. Do not make `CallActivity`
compose the signed-in root.

### 2.2 What `grok/g9-prep` actually changes

Commit `7840953`. Files: `AppContainer.kt`, `ShroudApplication.kt`, `CallsSystemModule.kt`,
`WipeHooksImpl.kt`, `docs/android-plan.md`. It does not edit `ui/`, `MainActivity`, or
`ShellModule.kt`.

- The only production attach, after `calls.onProcessStart()` and `callsSystem.onProcessStart()`:

  `calls.controller.attach(callsMedia.engine, callsSystem.system)`

- `CallsSystemModule.onCallPush` still calls `handleCallPush` and is still **not** wired.
  `PushModule` is the only production caller. Wiring `onCallPush` as well double-rings.
- A wipe calls `keys.sensitiveTempFiles.sweep()` with no age, so every `cacheDir/shroud-*`
  file goes. Chat lock still sweeps with `SensitiveTempFiles.STALE_AGE_MS` (10 minutes).
- `devices.clear()` is already on `phase-a` inside `haltWriters()`. G9 does not add a second one.
- The `ON_STOP` observer, `lockWhenBackgrounded()`, and `pendingPromptLock` are deleted on this
  branch. The shell keeps the vault-prompt wait. Do not put that wait back in
  `ShroudApplication`.
- `shell.onProcessStart()` stays a no-op call. The comment says the shell starts from
  `MainActivity` via `container.shell.startShell()`.

Until that commit is in the build you are running, `callsMedia.engine` and `callsSystem.system`
exist and are **not** attached. A call push reaches `CallController` and does not ring. G8's
killed-app log line `BLOCKED on G9` is that fact, not a script failure.

The UI does not call `attach`. Tests may.

---

## 3. Contracts now on `phase-a` (`f03c4f7`)

JVM tests for these passed (`106` tests, `0` failures) before the merge. The full phase-A gate
was **not** re-run on `0499172`.

### 3.1 Devices

`auth.devices` builds the controller. `auth.devicesIfBuilt` is null until something has read
`devices`. Wipe and chat lock call `devicesIfBuilt?.clear()` so a cold wipe does not build the
list just to drop it.

`DevicesController.clear()` drops rows, sealed names, the error, and the loading flag
(`hasLoaded` false). A refresh that started before `clear()` does not publish.

`remove(id)`:

- success → `RemoveOutcome.Removed` (unchanged)
- DELETE 404 → `RemoveOutcome.AlreadyRemoved`, and the row is still dropped
- this phone → `Failed` (Log Out is the only removal)

`removeAllOthers()` never returns `AlreadyRemoved`. A 404 there still counts as removed.

`claude/android-ui` `ui/settings/devices/DevicesModel.kt` `when (devices.remove(...))` has no
`AlreadyRemoved` branch. That `when` will not compile against `0499172`. Add it. Copy from
`DevicesView.swift:497-499`: info toast `"<name> was already removed"`. The row is already gone.
`revokeAllOthers()` can keep treating only `Removed` as the success toast. Its `when` also needs
a branch for `AlreadyRemoved` so it compiles, even though core does not return it.

### 3.2 Lock screen and biometrics

Still public, and already what `LockScreenModel` and `PrivacySecurityScreen` call:

- `keys.deviceSecurity.strongBiometricAvailable()`
- `keys.deviceSecurity.biometricLabel()`
- `keys.historyVault.keySecurity(): VaultKeyStore.Security?`

Also new, and the one to use when the caller is not already on `Dispatchers.IO`:

- `suspend fun CryptoController.vaultKeySecurity(): VaultKeyStore.Security?`

It reads `historyVault.keySecurity()` on IO and does not prompt. Null when this phone has no
vault. `LockScreenModel.probe` is already inside `withContext(IO)`, so the existing
`vault.keySecurity()` call is safe. Prefer `crypto.vaultKeySecurity()` if that probe stops
hopping to IO.

### 3.3 Clip duration

`VideoPipeline.durationMs(messageId: UUID): Int?` on `container.video.pipeline` (`VideoMedia`).
Positive milliseconds, or null when there is no local media, chats are locked, the retriever
cannot open the source, or the duration is missing or not positive. The source and the
retriever are closed with `close()`.

`claude/android-ui` `ContainerBubbleServices.mediaDurationMs` still builds its own
`MediaMetadataRetriever` and calls `release()`. `release()` is deprecated. Replace that body
with `container.video.pipeline.durationMs(messageId)` and delete the retriever. Do not keep
`release()`.

### 3.4 Flags that survive Log Out

`keys.uiFlags` is still prefs `shroud.ui` and is still wiped on Log Out.

`keys.uiFlags.kept` is a second `UiFlags` on prefs `shroud.device`. `WipeKeepList` already keeps
that whole file. `kept.kept` is the same instance. Writes while `StorageSeal` is sealed are
dropped on both.

`claude/android-ui` stores the photo asked-once flag on the wiped file:

`ComposeServices.PHOTO_ACCESS_REQUESTED_FLAG = "ui.photos.permissionRequested"` via
`keys.uiFlags`. Its comment says Log Out clears it. Leave it there. Move a flag to
`keys.uiFlags.kept` only when that flag must survive Log Out. The notification asked-once key
`notifications.permissionAsked` is already on `shroud.device` and already survives.

### 3.5 Notification names

`notifications.nameCache.follow(scope, contacts, incomingRequests, conversations): Job`

Order matches `ContainerShellEnvironment.feedNotificationNames`: chats, then
`request.user?.username`, then contacts win on the same id. An empty map is not written.
Repeats are skipped. Cancelling the `Job` stops collection. Production DI does **not** start it.

The shell's `feedNotificationNames` already writes `nameCache.rememberAll`. Replace that
`combine` with `follow`, or keep the `combine`. Do not run both.

### 3.6 Cache FileProvider

`AndroidManifest.xml` cache provider (`${applicationId}.cache`) now has
`android:grantUriPermissions="true"`. `exported` stays false. `FileProvider.attachInfo` crashes
the process when the flag is false, which is what the first device-check install did.
`CacheFileProviderManifestTest` locks the flag. Do not set it back to false.

---

## 4. Device checks already run (do not repeat unless the engine changes)

Throwaway stack `shroud-devcheck` (API `18081`, ntfy `2588`, Postgres `55473`, Redis `56393`,
state `/tmp/shroud-devcheck`) was started for these and then stopped with `stack-down.sh`. It
did not touch `shroud-postgres`.

| Check | Where | Result |
| --- | --- | --- |
| `CallMediaEngineLoopbackTest` | both emulators | pass, 1.974s and 1.4s. Logs `/tmp/shroud-devcheck/loopback-5560.log`, `loopback-5562.log` |
| `WebRtcCallE2eTest` | `emulator-5560` | pass, 15.94s. Log `/tmp/shroud-devcheck/webrtc-5560.log` |
| `AndroidVoiceNoteTranscriptionDeviceTest` | `emulator-5560` | pass, 24.186s. Log `/tmp/shroud-devcheck/transcription-5560.log` |

`WebRtcCallE2eTest` (`androidTest/.../e2e/WebRtcCallE2eTest.kt`) attaches the real
`CallMediaEngine` to a scripted web peer: `android/e2e/peer/peer.ts --media chrome`. Default
`--media` is still `fake`, which is what `engine-e2e.sh` uses. Chrome is headless, launched by
`android/e2e/peer/chromeMedia.ts`. The phone talks to the API and the peer at `10.0.2.2`, not
`127.0.0.1`. Both directions connected. `canSendScreen` was true and the web peer reported
`canShare`. `startScreen(ScreenCaptureGrant(0, Intent()))` was false.

**Screen frames are still blocked.** The platform only issues a `MediaProjection` token after
the system consent dialog. That needs an Activity. There is no supported, non-deprecated way to
mint the token from an instrumented test. C14 (or whichever screen owns the share button) is
the place that obtains the grant and calls `startScreen`. The engine-level negotiation check
above is done.

**An iPhone-recorded note was not transcribed.** The repo has no `.m4a` or `.caf` voice
fixture, including under `ios/`. None was downloaded or invented. The Android path encodes
`androidTest/assets/whisper/jfk.wav` with `AacM4aWriter` and expects the transcript to contain
`country`. The model is `ggml-base-q5_1`, downloaded on first use, not vendored. The whisper P7
RTF gate (≤ 0.3) is still owed (OPEN-6).

A worktree that runs the peer needs `web/node_modules/.bin/esbuild` (`npm ci` in `web/`, or a
symlink at that path). Do not commit `node_modules`.

---

## 5. Release prep on `phase-a` (W4, non-UI)

Commits `cf2c334`, `f18d543`, `6365d3b`, `7a0e7c4`, `1b98f24`.

- Release minify and resource shrinking are on. No universal release APK.
- Installable release outputs: `app-arm64-v8a-release.apk` (versionCode 2001) and
  `app-x86_64-release.apk` (versionCode 4001). There is no `app-release.apk` to install.
- Debug stays one APK at `android/app/build/outputs/apk/debug/app-debug.apk`. `system-e2e.sh`
  still uses that path.
- Signing is created only when `SHROUD_RELEASE_STORE` is set. v1 signing is off. The owner's
  key is not in the repo and was not generated here.
- `android/e2e/repro-build.sh` builds release twice with a throwaway `/tmp` key and compares
  bytes. It passed: arm64 `39055528` bytes, x86_64 `37760602` bytes, both identical across the
  two builds. The script deletes the keystore and does not print the password.
- `android/app/src/main/cpp/check-native.sh` is POSIX `sh`. It checks every `.so` in the APK,
  including WebRTC, for a 16 KB ELF LOAD alignment, and runs `zipalign -P 16`. The Ubuntu job
  `.github/workflows/android.yml` runs it on both unsigned release splits. That job has not been
  executed on GitHub (no push).
- F-Droid listing text: `android/fastlane/metadata/android/en-US/{title,short_description,full_description}.txt`.
  `fdroid build` was not run. NonFreeNet stays off. ntfy is the user's own distributor.
- No P1 or P2 core defect with a failing test was found, so there is no stab-core commit.
- `docs/architecture.md` and `docs/android-plan.md` on `phase-a` describe kind byte 4,
  UnifiedPush plus the opt-in background connection, on-device whisper.cpp, and native WebRTC.
  They still say `CallController.attach` is not wired and the signed-in UI is the interim root.
  `g9-prep` updates the attach sentence on its own copy of `android-plan.md` only.

`assembleRelease` is heavier than before (R8 plus both ABIs). Debug installs for e2e are
unchanged.

---

## 6. What Claude changes, and what stays

UI work stays on `claude/android-ui` (or a branch cut from `0499172` plus that UI). Do not
commit on `grok/*`. Do not push. Do not merge into `dev`.

Must change in the UI once it compiles against `0499172`:

1. `DevicesModel.revoke` handles `RemoveOutcome.AlreadyRemoved` with the info toast
   `"<name> was already removed"`.
2. `ContainerBubbleServices.mediaDurationMs` forwards to `container.video.pipeline.durationMs`
   and stops calling `MediaMetadataRetriever.release()`.

Optional, same behaviour either way:

- `feedNotificationNames` may call `notifications.nameCache.follow` instead of its own `combine`.
- `LockScreenModel` may call `crypto.vaultKeySecurity()` instead of `historyVault.keySecurity()`.

Do not move `ui.photos.permissionRequested` onto `uiFlags.kept`.

Do not call `CallController.attach` from a screen. Do not wire `CallsSystemModule.onCallPush`.
Do not start the shell from `onProcessStart`. Do not remove the `ON_STOP` lock on `phase-a`.
That removal is only on `grok/g9-prep`, taken together with the shell.

No `.pen` file was edited in this pass. The visible product UI did not change. Screen-share
consent and the already-removed toast are the UI pieces this work is waiting on.

---

## 7. Still owed (unchanged)

- **OPEN-1.** Local only. No push, no merge to `dev`, until the owner says so.
- **OPEN-2.** ntfy APK not downloaded. `android/e2e/up-stub/` is the distributor stand-in.
- **OPEN-4.** `design/Android-App.pen` was not opened or saved here.
- **OPEN-5.** 64-bit only (`arm64-v8a`, `x86_64`).
- **OPEN-6.** Physical phones, Samsung launcher, Bluetooth answer, a cellular call during a
  call, ring latency, whisper P7 (emulator RTF still above 0.3).
- **OPEN-7.** The spec folder was readable and was not copied into the repo.
- GitHub Android CI is still the old red run on `origin/dev` `c0dd437`. Phase A and this
  continuation are not on the remote.
- Killed-app CallStyle ring still waits until G9 is in the build under test.
- `web/src/screens/AppShell.tsx` `mergeMessages` is still id-only (phase-A G12 note). That
  `.tsx` was not edited.
- iPhone-recorded transcription fixture: missing.
- Real screen-share frames: need the consent Activity.
