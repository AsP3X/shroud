# Contract gaps found during the UI pass, for Grok and the owner

Each entry gives: what is missing, where it surfaced, and which C item needs it.

## From C1+C2 (claude/c1-integrate dad4de2)
1. **The interim ON_STOP lock in `ShroudApplication.startProcess()`** (`ProcessLifecycleOwner` `onStop` → `lockWhenBackgrounded()`) duplicates `AppShellController`'s auto-lock. It also overrides the delayed and Never choices.
   - **Fix (G9):** remove that observer. The `AppContainer.onProcessStart` comment "shell.onProcessStart() // replaces the interim ON_STOP lock" is stale; the shell now starts from `MainActivity` (`container.shell.startShell()`).
   - **Needed by:** C3 auto-lock acceptance.
2. **K3 `DevicesController` keeps decrypted labels and rows** in its process-wide state after `lockChatsInMemory()` and after the Log Out wipe.
   - **Fix:** add `fun clear()` (`rows = []`, `hasLoaded = false`), called from `WipeHooksImpl` and `AppContainer.lockChatsInMemory()`, or drop the state when the session user changes.
   - **Needed by:** C7.
3. **K3 `RemoveOutcome` folds a 404 into `Removed`.** iOS shows the info toast "<name> was already removed" (`DevicesView.swift:142-146, 497-499`).
   - **Fix (additive):** `RemoveOutcome.AlreadyRemoved`.
   - **Needed by:** C7.
4. **K1 is missing members that the lock screen and Privacy use:** `keys.historyVault.keySecurity()` (`ui/lock/LockScreenModel.kt` probe, `ui/settings/privacy/PrivacySecurityScreen.kt:142`), `keys.deviceSecurity.strongBiometricAvailable()` and `biometricLabel()`.
   - **Fix:** confirm them as public K1, or add `CryptoController.vaultKeySecurity()` (IO) and so on.
   - **Needed by:** C4, C7.
5. **Media metadata is read in UI:** `ui/conversation/bubble/BubbleServices.kt` `mediaDurationMs` runs `MediaMetadataRetriever` over `media.metadataSource(messageId)`.
   - **Fix:** a core member such as `video.pipeline.durationMs(messageId): Int?`.
   - **Needed by:** C10 (R4).
6. **K5 `UiFlags` is wiped by Log Out,** but the composer's photo "asked once" fact used to survive Log Out (like P6's notification flag).
   - **Fix:** a kept-flag variant of K5 (for example `UiFlags.kept`), if parity matters.
   - **Needed by:** C11.
7. **Optional:** `ContainerShellEnvironment.feedNotificationNames` builds the name map in UI. A core `NotificationNameCache.follow(...)` would make the adapter 1:1.
   - **Needed by:** C3.

## From the 18:00 resume: grok/g9-prep breaks every app-starting JVM test (blocks merging G9)
8. `grok/g9-prep` (7840953) attaches in `AppContainer.onProcessStart()` (line ~138):
   `calls.controller.attach(callsMedia.engine, callsSystem.system)`. Reading `callsMedia.engine`
   constructs `CallMediaEngine`, and `WebRtcRuntime.acquire` loads the native
   `jingle_peerconnection_so` at process start.
   - **Effect in tests:** every Robolectric test that starts `ShroudApplication` dies with
     `UnsatisfiedLinkError: no jingle_peerconnection_so`. Merged into `claude/android-ui` that was 235
     of 2,884 UI tests.
   - **Effect on phones:** WebRTC loads in every process a push, boot or `CallActivity` wakes.
   - **Fix (Grok):** attach without building the engine. For example, `attach` takes a lazy engine
     provider (`() -> CallMediaEngine`), or the engine defers `WebRtcRuntime.acquire()` until the
     first call. Then run the full JVM suite with g9-prep merged onto `claude/android-ui`.
   - **Until then:** `claude/android-ui` does NOT contain g9-prep (the merge was taken back before
     anyone used it). The shell's delayed and Never auto-lock stay overridden by the interim ON_STOP
     lock on devices, and a killed app does not ring.
9. **The unit-test worker has no heap setting** (Gradle's default is 512 MB), and all of the 2,884+ tests
   run in one JVM. A run on `claude/android-ui` died with `OutOfMemoryError: Java heap space` in
   `ComposeControllerTest`; the immediate rerun passed.
   - **Fix (Grok, `android/app/build.gradle.kts`):** `testOptions.unitTests.all { it.maxHeapSize = "2g" }`,
     and possibly `forkEvery`.
   - **Needed by:** every C item's gate.
   - **Update (C12):** root cause found and fixed in UI tests. `ChatRowFontScaleTest` ran with motion on, and Robolectric's `ShadowTrace` piled up 31.7M trace strings. A heap raise is now optional hardening.

## From C14 (claude/c14-calls-ui 37486ca, merged as 479b84a)
10. **P17a route menu (calls D4):** core has no public audio-route API (`CoreCallTelecom` keeps
    `availableEndpoints` private).
    - **Needed on `CallSystem`:** `val audioRoutes: StateFlow<List<CallAudioRoute>>`,
      `val currentRoute: StateFlow<CallAudioRoute?>` and `fun selectRoute(route: CallAudioRoute)`
      (`CallAudioRoute` = type + name).
    - Until then, Speaker stays a toggle.
11. **Deprecation in core:** `core/media/video/Media3VideoExporter.kt:125` uses the deprecated
    `EditedMediaItemSequence.Builder(vararg)` (Grok). The UI one, `BundledEmojiCompatConfig(Context)` in
    `BubbleServices.kt:246`, is C10's to fix.

## From C9 (claude/c9-thread-list f1b72e3, merged as 86d9d70)
12. **The call microphone prompt is not in K1:** `calls.permissions` (`AndroidCallPermissions.prompt: CallPermissionPrompt?`)
    is not in the §2.2 accessor table, though C14's InCallOverlay registers the prompt.
    - **Fix:** confirm `calls.permissions` as public K1 (or add a prompt setter on `CallController`).
      Without the prompt, a call started without `RECORD_AUDIO` toasts "Allow microphone access …" instead of asking.

## From C13 (claude/c13-media-view bfe3134, merged into claude/android-ui)
13. **CORE DEFECT, blocks video capture on HD-only cameras:** `core/media/capture/CameraXSession.kt` `bindReady` uses
    `QualitySelector.from(Quality.FHD, FallbackStrategy.lowerQualityOrHigherThan(Quality.HD))`, which leaves HD out.
    - **Effect:** on the API 30 emulator (supported = [HD]) the selection is empty, the bind fails, and VIDEO shows "No camera available".
    - **Fix:** `FallbackStrategy.lowerQualityOrHigherThan(Quality.FHD)` or `QualitySelector.fromOrderedList(FHD, HD, SD)`.
      Spec §8.3 prints the same wrong selector.
    - **Then rerun:** `MediaViewDeviceTest` (its recorded-clip case is an assumption skip until then).
14. **K9 additions (additive proposals):**
    - `val bindState: StateFlow<Binding | Bound(hasFront, hasBack) | Failed>` (UI polls today);
    - `val zoomRange: ClosedFloatingPointRange<Float>?`;
    - `val hasFlashUnit: Boolean`.
15. **K10, optional:** `shareUri` returns `(Uri, mime)`; the UI uses `ContentResolver.getType` today.

## Status 2026-10-02 18:30 UTC: gaps 8–15 are done in core
Grok's `grok/phase-a` 7e1181b ("Record HD video and load WebRTC only when a call starts") is merged into `claude/android-ui` as 9c3604e:
- **#8:** lazy `WebRtcRuntime.acquire`. The fix is on `grok/g9-prep` d77dd2c. g9-prep merges into `claude/android-ui` only AFTER C3's shell auto-lock is on that branch, because it removes the interim ON_STOP lock.
- **#9:** unit-test workers get `maxHeapSize = "2g"`. C12's reduced-motion fix stays.
- **#10:** `calls.system.audioRoutes`, `currentRoute`, `selectRoute(route)`. `CallAudioRoute` is `type`, `name` and `id`. Without Telecom the list is Earpiece (id `earpiece`) and Speaker (id `speaker`). UI TODO: the call screen's Speaker toggle becomes the route list. Two Bluetooth devices can share a name, so tell them apart by `id`.
- **#11:** core video export no longer uses the deprecated builder. UI TODO (C10): `BundledEmojiCompatConfig(Context)` in `BubbleServices.kt`.
- **#12:** `container.calls.permissions.prompt` is public. No change needed.
- **#13:** recording quality falls back FHD → HD → SD. UI TODO: drop the `assumeTrue(..., false)` skip in `MediaViewDeviceTest`, then rerun it on `shroud_api30` / emulator-5562.
- **#14:** `media.camera.bindState` (`Unbound` / `Binding` / `Bound(hasFront, hasBack)` / `Failed`), `zoomRange`, `hasFlashUnit`. UI TODO: the camera screen stops polling and uses these.
- **#15:** `media.sharing.shareTarget(messageId): ShareTarget?` with `uri` and `mime`. UI TODO: the viewer stops calling `ContentResolver.getType`.

## From C11 (claude/c11-composer 79ebb2e)
16. **K9 has no way to discard a camera clip:** `ui/media/MediaSeams.kt` `PickedMovie.cleanup()` deletes `PickedMovieFile.file`
    (file I/O in UI), and C13 builds `Uri.fromFile(clip.file)`.
    - **Proposal:** `CameraCapture.discard(clip)`, or a content-URI `PickedMovieFile`.
    - **Until then:** the one-line delete stays, and temp files are also swept on lock and launch.
17. **Optional:** a core `video.media.mimeType(uri)` / `isVideo(uri)`. `ContainerComposeServices.mimeType` uses `ContentResolver.getType` today.

## From C7+C15 (claude/c7-settings-b cb3b5ad)
18. **K6 battery exemption:** `PushRegistration` cannot ask for the battery-optimisation exemption again; core asks once inside `BackgroundConnectionController.enable()`.
    - **Proposal:** `fun requestBatteryUnrestricted()`, firing `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.
    - **Until then:** the "Optimized" row opens the app's details settings.
19. **K6, which distributor failed:** `UnifiedPushState.Unavailable(reason)` does not name the distributor.
    - **Proposal (additive):** `Unavailable(reason, distributorPackage: String? = null)`, or `PushRegistration.chosenDistributor: StateFlow<String?>`.
20. **R3, a token in UI:** `NotificationsController.pushSettings(token)` and `sendTest(token)` make the UI pass `session.token`
    (Notifications and Sound screens; C6's `SettingsScreen.kt:109`).
    - **Proposal:** overloads without the token that read the session inside core.

## From C6 (claude/c6-settings-a 1e36f36)
21. **CR-1, manifest (Grok), blocks the visible launcher logo switch:** the `.LauncherSimple` activity-alias in `AndroidManifest.xml`
    still has `android:icon="@mipmap/ic_launcher"` and `android:roundIcon="@mipmap/ic_launcher"`.
    - **Needed:** `@mipmap/ic_launcher_simple` for both.
    - **Until then:** `LauncherIconTest.eachAliasCarriesItsOwnIcon` stays `@Ignore`, and `LauncherIconDeviceTest` fails its icon check.
22. **Optional, K7:** a model-choice member (`models` / `chooseModel(...)`) for P7's opt-in small q5_1 model.
(#20 note: `SettingsScreen.kt:109` only keys an effect on `session?.token`; switching to `session?.userId` drops the read with no core change.)
