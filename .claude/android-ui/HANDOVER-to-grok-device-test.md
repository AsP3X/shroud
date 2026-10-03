You are Grok, continuing the Shroud Android port (repo /Users/nvorberg/Documents/development/shroud).
The owner wants to test the app on a REAL Android phone TODAY. Claude is out of weekly budget until
2026-10-05, so the owner hands you the rest. Part A is today's priority. Do it first, end to end.

STATE (all local, nothing pushed)
- **`claude/android-ui` @ 95cd9f3** is the complete integration:
  - all UI screens (C1–C15) and C13b;
  - your `grok/phase-a` 7e1181b (gaps #8–15);
  - `grok/g9-prep` d77dd2c (production `CallController.attach`; interim ON_STOP lock removed).
- **`grok/phase-a`** has no commits that `claude/android-ui` lacks.
- **Final gate on that tree was green:** `:app:testDebugUnitTest` 3,618 tests / 0 failures, `lintDebug`, `verifyNoMaterial`, `verifyNoGoogleServices`, `verifyNoGoogleClasses`, `assembleDebug`, `assembleRelease` (per-ABI unsigned APKs), and `compileDebugAndroidTestKotlin`. C13b then passed 3,627 on its branch.
- **Device-verified so far:**
  - API 37 emulator: shell smoke (sign up, notification prompt, tabs, Devices, Log Out wipe, log in with phrase, contact accept, lock-screen notification tap into the chat), your `system-e2e.sh` (RESULT pass), two-pane, hero zoom.
  - API 30 emulator: `MediaViewDeviceTest` OK (5 tests), including an HD recorded clip.
- **Open gaps:** read `.claude/android-ui/GAPS.md`. #1–15 are done; #16–22 are open. Progress: `.claude/android-ui/PROGRESS.md`. Specs: `/Users/nvorberg/.claude/projects/-Users-nvorberg-Documents-development-shroud/android-port-specs/00-plan.md`.

OWNER'S REASSIGNMENT
For the items in Parts A and C, the owner lets you edit UI files too (`$SRC/ui/**`, `MainActivity.kt`, `di/ShellModule.kt`, UI resources and UI tests). Rules for that:
- **Keep these as they are:**
  - every frozen contract;
  - R1–R5 (UI adapters forward 1:1 to core; no tokens, keys, SharedPreferences, MediaStore, file I/O or crypto in `ui/`);
  - no deprecated APIs (no `@Suppress` to keep one).
- **Design files:** never touch them (`.pen`, Pencil only). For every visible UI change you make, write its screen, state, copy, dp/sp and colour tokens into your report, so Claude's design pass (C16) can draw it.
- **Ownership:** you own everything non-UI as before.

DEVICE AND MACHINE RULES
- **The owner's phone:** it connects over USB. Find its serial with `adb devices`; it is NOT `emulator-*`. `emulator-5554` (Pixel_10_Pro_XL) is the owner's emulator: never install on it or aim tests at it.
- **Gradle connected tests attach EVERY online device:** ALWAYS set `ANDROID_SERIAL=<serial>`. Never run instrumentation on the owner's phone unless Part A says so; their phone is for their manual test.
- **Emulators:** use the AVDs `shroud_api37` (port 5560) and `shroud_api30` (port 5562): `-no-snapshot`, PIN 1234, `android/e2e/unlock.sh` until `dumpsys user` says RUNNING_UNLOCKED, then `android/e2e/emulator-setup.sh`. One e2e stack at a time.
- **Gradle:** goes through `/Users/nvorberg/.claude/projects/-Users-nvorberg-Documents-development-shroud/android-port-specs/tools/gw` (JDK 21, build slots).
- **Git:**
  - Work on the new branch `integration/device-test`, cut from `claude/android-ui` 95cd9f3. Commit early and often: "ADD:/TASK:/FIX: <sentence>".
  - Do not push and do not merge into `dev` unless the owner says so (OPEN-1). Never rewrite history.
  - Leave the main checkout on `grok/phase-a`, and work in a worktree.
- **The repo is PUBLIC on GitHub:** if the owner later says push, scan for secrets first.

PART A: A BUILD THE OWNER CAN TEST ON A REAL PHONE TODAY
A1. Create `integration/device-test` from `claude/android-ui` (95cd9f3) in a worktree. Copy the main checkout's gitignored `android/local.properties` into it.
A2. Fix the blockers:
  - **GAPS #21:** in `AndroidManifest.xml`, the `.LauncherSimple` activity-alias must use `android:icon="@mipmap/ic_launcher_simple"` and `android:roundIcon="@mipmap/ic_launcher_simple"`. Then un-`@Ignore` `LauncherIconTest.eachAliasCarriesItsOwnIcon`.
  - **Anything else that crashes or blocks** the quick device run in A4. Fix only real blockers today; list the rest.
A3. Run the gate: `:app:testDebugUnitTest :app:lintDebug :app:verifyNoMaterial :app:verifyNoGoogleServices :app:verifyNoGoogleClasses :app:assembleDebug :app:compileDebugAndroidTestKotlin`. Then build `android/app/build/outputs/apk/debug/app-debug.apk`. The debug build is the one for today: it is signed with the debug key, and the owner's release key does not exist yet.
A4. Quick automated pre-check on the API 37 emulator (5560), each class with `ANDROID_SERIAL=emulator-5560` and the stack where the class needs it:
  - `android/e2e/system-e2e.sh emulator-5560`. Calls now attach in production, so the killed-app ring should no longer print "BLOCKED on G9". Report what it prints.
  - Instrumented, from `android/` (`gw :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=<FQCN>`):
    - `de.corespace.shroud.ui.onboarding.LockOnboardJourneyTest`
    - `de.corespace.shroud.ui.wipe.DeviceWipeOverlayTest`
    - `de.corespace.shroud.ui.conversation.MessageHoldDeviceTest`
    - `de.corespace.shroud.ui.conversation.composer.ComposerDeviceTest`
    - `de.corespace.shroud.ui.contacts.ContactsJourneyTest` (it needs the stack and two web peers, as engine-e2e.sh starts them)
    - `de.corespace.shroud.ui.settings.LauncherIconDeviceTest` (after A2)

  `am instrument` exits 0 either way: a pass is "OK (" and `INSTRUMENTATION_CODE: -1`. Fix blockers; record anything non-blocking.
A5. Prepare the owner's phone session (recommended setup: the local stack, because it has every Android server feature):
  1. Start the local stack: `android/e2e/stack-up.sh` (API on 127.0.0.1:8080, ntfy on 2586).
  2. With the phone on USB:
     - `adb -s <phone> reverse tcp:8080 tcp:8080`
     - `adb -s <phone> reverse tcp:2586 tcp:2586`
     - `adb -s <phone> install -r android/app/build/outputs/apk/debug/app-debug.apk`

     If a Shroud build with another signature is installed, ask the owner before uninstalling it.
  3. In the app: Welcome › server sheet › Self-hosted, host `localhost`, port `8080`, path `/api/v1`. Plain HTTP is allowed for localhost, and localhost needs no Android 17 local-network permission.
  4. Second participant: the web client on the Mac (`cd web && npm ci` if `node_modules` is missing, then `npm run dev`, and open http://localhost:5173). Vite proxies `/api` and the socket to 127.0.0.1:8080. Alternatively an emulator.
  5. Push while the app is closed, either way:
     - Settings › Notifications and Sounds › Delivery › "Background connection" ON (no distributor needed);
     - or the owner installs the ntfy app from F-Droid on their phone, sets its server to `http://localhost:2586` (the adb reverse covers it), and picks ntfy as the distributor.
  6. Calls: signalling runs over the reversed port. Media needs the phone and the Mac on the same Wi-Fi (there is no TURN locally); say so if calls connect but have no audio.

  Official server alternative (api.shroud.app): use it only if its deployed server contains the Android server changes (UnifiedPush subscriptions with `client:"android"`, background sockets, the `device_removed` wake). Check `GET /api/v1/config` against `server/` on dev before recommending it, and remember that it uses real accounts. If unsure, say "local stack only".
A6. Hand the owner a short TEST SHEET to follow on the phone:
  - sign up (phrase, screen lock);
  - add the web user;
  - text, reply, link preview, photo (Original/HD, edits), video, voice note with transcript;
  - reactions, delete for me / for everyone, mute, Notes;
  - a voice call and a video call;
  - lock, then unlock (auto-lock Immediately / 1 min / Never);
  - notifications with the app backgrounded and with it closed (Background connection);
  - Settings › Devices shows "Android app" and the green tile;
  - Log Out, then the wipe overlay, then Welcome;
  - the launcher icon switch in Appearance;
  - plus the known limits below.

  Include the exact adb, stack and web commands.

KNOWN LIMITS TO TELL THE OWNER (do not try to fix today)
- **Design:** the `.pen` designs are not updated for the new screens (C16, Claude).
- **Recents:** on Android 15+ the live Recents preview still shows the current screen (P5 rules out FLAG_SECURE there; an owner decision is open).
- **Transcription:** on-device whisper downloads its model on first use. Emulator speed misses the P7 gate; a real phone may be fine.
- **APK:** 64-bit only (OPEN-5).
- **Not yet verified anywhere:** Bluetooth or wired audio routes (the call screen still has only a Speaker toggle, C14b), Samsung launcher behaviour, and a cellular call during a call.
- **Notifications settings:** core's notification-settings API still takes the session token in UI (GAPS #20).

PART B: YOUR REMAINING NON-UI WORK (after Part A)
- **GAPS #16:** `CameraCapture.discard(clip)`, or a content-URI `PickedMovieFile`, so UI stops deleting files.
- **#17:** an optional `isVideo` / mime lookup.
- **#18:** `PushRegistration.requestBatteryUnrestricted()`.
- **#19:** `Unavailable(reason, distributorPackage)` or `chosenDistributor`.
- **#20:** token-free `pushSettings()` / `sendTest()` overloads.
- **#22:** optional, a transcription model choice.
- **Phase B, on the integration branch:** G10 (cross-client matrix, non-UI rows) and G11 (W4: stab-core, release signing with the owner's offline key, reproducible build, F-Droid build, X4-SRV `assetlinks.json`, X4-DOCS).

PART C: UI LEFTOVERS THE OWNER REASSIGNS TO YOU (after A; each with tests)
- **C14b:** the call screen's audio-route menu (Phone, Speaker, each Bluetooth or wired route by `id`) on `calls.system.audioRoutes` / `currentRoute` / `selectRoute`, replacing the Speaker toggle (GAPS #10).
- **C5 tidy-up:** `ui/chats/ChatsScreen.kt` can call `ToastHost(toasts)` now that `Toast.kt` reads `LocalTabBarClearance`. Remove `ChatsLayout.toastLift`; `ChatsScreensRenderTest.deletedToast` (104 dp) guards it.
- **Kit fixes:**
  - `ui/components/Buttons.kt:74`: a disabled PrimaryButton's `.alpha(0.45f)` clips its drop shadow. Use `graphicsLayer { alpha = …; compositingStrategy = CompositingStrategy.ModulateAlpha }`.
  - `ShroudSheet` should reset `LocalGlassBackdrop`.
  - The kit `ToggleRow` should speak its subtitle to TalkBack.
- **C17 (the rest of the device runs):** the same classes as A4 on API 30 (5562), `MediaViewDeviceTest`, and the cross-client matrix UI rows (plan §6.4).
- **Not yours:**
  - C16 design (Pencil only, plus the owner's ⌘S). Claude does it after 2026-10-05.
  - C18 accessibility is optional. Do it only if Parts A–C are done, and list every visible change.

REPORT (to the owner)
1. **What is ready to test now:** the APK path, and the exact setup commands.
2. **Test sheet.**
3. **Results:** gate and device results (pass/fail, with log paths).
4. **Fixes made:** commits on `integration/device-test`.
5. **What is still open:** Parts B and C, and gaps.
6. **Every visible UI change you made**, for C16.

If a task needs a decision only the owner can make (accounts on the official server, uninstalling an existing app, pushing), stop and ask.
