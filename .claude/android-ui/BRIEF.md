# Brief for every Claude UI agent on the Shroud Android port (read fully before working)

You are a Claude UI engineer. Since 2026-10-02 the owner split the Android port: **Claude owns UI only,
Grok owns everything non-UI.** You implement exactly one C item (your prompt names it). Never cross the
boundary "helpfully".

## Sources (read the parts your item needs)
- `/Users/nvorberg/Documents/development/shroud/CLAUDE.md` (project rules).
- Owner's split: `/Users/nvorberg/Documents/development/shroud/docs/android-handover.md`, which is untracked
  and lives only in the main checkout, so read it by that absolute path. You need:
  - §2: rules R1–R5 and contracts K1–K12;
  - §4: your C item's outcome, allowed paths, done-when;
  - §5: do-not-touch.
- Grok's phase-A report: `docs/android-handover-from-grok.md` (committed on the base branch). You need:
  - §2: contract deltas and the **accessor table §2.2**;
  - §4: constraints on C1–C18;
  - §2.6: **no deprecated APIs**.

  Where the two documents disagree, Grok's report is what the code does.
- Plan and area specs (with iOS file:line):
  `/Users/nvorberg/.claude/projects/-Users-nvorberg-Documents-development-shroud/android-port-specs/`.
  Start at `00-plan.md`:
  - §1.7.12–1.7.13: kit, seams and screen entry points;
  - §2.4: the W3 card your item maps to;
  - §6.
- The iOS app (`ios/`, SwiftUI) is the reference, and the web client (`web/`) is the second reference.
  Port behaviour, copy, spacing, colours and states 1:1. Cite the iOS file:line in KDoc for ported behaviour.
- Starting material from the stopped Wave 3 run is on the read-only branches `android/w3-*`. By now the C1
  integration has merged it into the base branch `claude/android-ui`.

## Paths (`$SRC` = `android/app/src/main/java/de/corespace/shroud`)
**Allowed:**
- `$SRC/ui/**`, `$SRC/MainActivity.kt`, `$SRC/di/ShellModule.kt`;
- `android/app/src/main/res/{drawable,mipmap*,values*,font}/**` (keep the names `ic_stat_shroud`, `ic_launcher*` and the alias icons);
- `android/app/src/test/java/de/corespace/shroud/ui/**`;
- `android/app/src/androidTest/java/de/corespace/shroud/{ui,e2e/ui}/**`.

**Forbidden:**
- `$SRC/core/**`, `$SRC/di/**` (except `ShellModule.kt`), `AppContainer.kt`, `ShroudApplication.kt`;
- `AndroidManifest.xml`, `res/{raw,xml}/**`, `app/src/main/cpp/**`, every Gradle or build file;
- `android/e2e/**`, `androidTest/.../e2e/*.kt` (engine/system e2e);
- `server/**`, `docs/**`, `ios/**`, `web/**`;
- `design/**`: code agents never edit `.pen` files; the design item C16 does that;
- `.claude/**`;
- the branches `grok/*` and `android/w3-*` (read only).

## Contract rules (from §2; enforced by review)
- **R1 Threading.** Call controllers from Main. Members documented "call off main" go on `Dispatchers.IO`.
- **R2 Errors.** Core returns `String?` (null = ok) or typed outcomes. Render core sentences verbatim, and turn an `ApiError` into a sentence only through `SessionController.userMessage(e)`.
- **R3 Auth.** No tokens or keys in UI. The session comes from `auth.sessionController.session`.
- **R4 Adapters.** UI-side ports interfaces and their `Container*` adapters only forward 1:1 to public core members. Under `ui/` there is **no** `ShroudApi`, `net.api`, OkHttp, `SharedPreferences`/`getSharedPreferences`/`PrefsFiles`, file I/O, `MediaStore`, crypto, `DeviceNameSeal` or `StorageSeal`. Use:
  - `auth.onboarding`
  - `auth.devices`
  - `media.photoLibrary`
  - `keys.uiFlags`
  - `media.camera`
  - `media.sharing`
  - `images.editRenderer`
  - `push.registration`
  - `transcription.voice`
  - `calls.controller`
  - `callsMedia.engine`
  - `callsSystem.screenHooks`
  - and the K1 controllers.
- **Never call `CallController.attach`** (that is Grok's G9). There is one call-push path (`PushModule`); never call `CallsSystemModule.onCallPush`.
- **No deprecated APIs.** No `@Suppress("DEPRECATION")` to keep one, and no `@Deprecated` shims. An API that exists only on API 30–32 sits behind an SDK check, and newer SDKs run the new API.
- **Contract gap.** If a contract is missing a member, a behaviour, a manifest entry or a resource that core reads, do not edit core. Write the gap (path, exact signature or change, why, which C item needs it) into your final report and work around it only inside `ui/` if that is honest (for example, a disabled state). Otherwise leave that part out.

## Build and test
- **Gradle** always runs through the slot wrapper, from `android/`:
  `/Users/nvorberg/.claude/projects/-Users-nvorberg-Documents-development-shroud/android-port-specs/tools/gw <tasks>`.
  It sets JDK 21 and rations 3 machine-wide build slots, so waiting is normal. Never run `./gradlew` directly.
- **Your gate:** `:app:testDebugUnitTest :app:lintDebug :app:verifyNoMaterial :app:verifyNoGoogleServices :app:assembleDebug :app:compileDebugAndroidTestKotlin`, green before your final report.
  - While iterating, run narrower tasks, such as `:app:testDebugUnitTest --tests 'de.corespace.shroud.ui.chats.*'`.
  - Lint must add no new warnings in your files.
- **Tests:** add JVM tests for every rule you port (Robolectric + the Compose test harness that already exists under `test/.../ui`). Copy test vectors from the iOS tests.
- **Devices** only when your prompt grants an allowance (a named AVD and port):
  - `adb -s emulator-<port>`;
  - `android/e2e/emulator-setup.sh` for the screen lock and the local-network permission;
  - a throwaway stack with `android/e2e/stack-up.sh`, using the env overrides from your prompt;
  - stop the stack and `adb -s emulator-<port> emu kill` when done.

  Never touch `emulator-5554` (the owner's Pixel_10_Pro_XL) or the `shroud-postgres` container. Never download APKs or system images.
- **Shell inside a worktree:** a guard refuses heredocs, computed arguments and `git -C`. Use plain commands, and put scripts in files if you need them.

## Git (resumable work, since usage limits can stop you at any moment)
- **Worktree:** you work in an isolated git worktree.
  - If your branch already exists (`git rev-parse --verify --quiet <branch>`), it holds work from an earlier, interrupted run of your item: `git checkout <branch>` (NEVER `checkout -B`), review what is there, and continue.
  - Otherwise `git checkout -b <branch> claude/android-ui`.
- **Commit EARLY and OFTEN**, at least after every meaningful step and before every long build. Uncommitted work is lost when a session ends. Message: one plain sentence starting `ADD: `, `TASK: ` or `FIX: `, then a blank line and `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- **Never** push, merge into `dev` or `grok/*`, rewrite history, or commit anything under `.claude/`.

## Design
CLAUDE.md wants every UI change in `design/Android-App.pen`. In this run, only the separate design item C16 edits the `.pen` (the Pen app is shared and parallel agents would clobber each other). So in your final report, list every visible UI state you built or changed under **DESIGN NOTES**:
- screen and state;
- exact copy;
- sizes in dp/sp;
- colour tokens (`ui/theme`);
- the iOS frame or `.pen` frame id it mirrors.

## Final report (plain text, this order)
1. **SUMMARY:** what you built and what is left.
2. **BRANCH + HEAD SHA.**
3. **FILES:** grouped by directory.
4. **TESTS:** added tests and gate result, with the test count from the XML.
5. **CONTRACT GAPS:** a list, or "none".
6. **DESIGN NOTES.**
7. **DEVICE CHECKS:** run, with results, or still owed, with exact steps.
8. **MERGE NOTES:** conflicts to expect with other C items; shared files touched.

## Update 2026-10-02 18:00 UTC (read this too)
- **New base content.** `claude/android-ui` now also contains `grok/phase-a` f3c8065. It does NOT contain `grok/g9-prep`: g9-prep builds the WebRTC engine at process start and breaks every app-starting JVM test (GAPS.md #8). So the interim ON_STOP lock is still active and production does not attach calls yet. Treat the g9-prep facts below as coming later. That means:
  - Grok's gap contracts:
    - `DevicesController.clear()` and `RemoveOutcome.AlreadyRemoved` (already handled in Devices);
    - `CryptoController.vaultKeySecurity()`;
    - `video.pipeline.durationMs(messageId)` (bubbles already use it);
    - `keys.uiFlags.kept` (survives Log Out; only for flags that must);
    - `notifications.nameCache.follow(...)` (use it OR the shell's own combine, never both).
  - Release prep: minify, ABI splits, `check-native.sh`.
  - (Later, once g9-prep is fixed and merged: production `CallController.attach`, and the interim ON_STOP lock removed.)

  Read `/Users/nvorberg/Documents/development/shroud/docs/android-handover-from-grok-timer.md` (§1, §3, §6). The UI still never calls `attach`, never wires `CallsSystemModule.onCallPush`, and never starts the shell from `onProcessStart`.
- **Resuming a paused branch.** If your branch already exists, `git checkout <branch>`, then `git merge claude/android-ui` FIRST, so you build on the new base. Resolve conflicts in your own paths, and stop and report on conflicts in Grok paths. Then continue your item.
- **Emulators:**
  - Cold-boot with `-no-snapshot`. The screen-lock PIN is `1234`.
  - A cold boot comes up `RUNNING_LOCKED`: after `sys.boot_completed=1`, run `android/e2e/unlock.sh <serial>`, and check that `adb -s <serial> shell dumpsys user` says `RUNNING_UNLOCKED` before installing tests. Otherwise instrumentation dies with "not encryption aware".
  - `android/e2e/emulator-setup.sh <serial>` is idempotent.
  - Gradle connected tests attach EVERY online device, so always set `ANDROID_SERIAL=emulator-<port>`. The owner's `emulator-5554` is online and must never get an install.
  - `am instrument` exits 0 either way. A pass is `OK (` with `INSTRUMENTATION_CODE: -1`.
- **Debug APK** stays at `android/app/build/outputs/apk/debug/app-debug.apk`. Release now produces only per-ABI APKs. Your gate does not need `assembleRelease`.
- **Screen share.** A `MediaProjection` grant needs the system consent Activity, so screen-share frames can only be obtained from the UI (C14's share button) and not from tests.
- **The web peer** (`android/e2e/peer/peer.ts`, `--media fake|chrome`) needs `web/node_modules/.bin/esbuild`. In a worktree, symlink the main checkout's `web/node_modules` (never commit it). The phone reaches the peer and the API at `10.0.2.2`.

## Update 2026-10-02 18:30 UTC (gaps 8–15 landed in core)
`claude/android-ui` now contains `grok/phase-a` 7e1181b. The new core members are below; the old ones still compile, but prefer the new ones when you touch that code:
- **Camera:** `media.camera.bindState: StateFlow` (`Unbound` / `Binding` / `Bound(hasFront, hasBack)` / `Failed`), `zoomRange: ClosedFloatingPointRange<Float>?` and `hasFlashUnit`. Stop polling the has*Camera flags.
- **Video:** recording falls back FHD → HD → SD, so HD-only cameras record.
- **Sharing:** `media.sharing.shareTarget(messageId): ShareTarget?` gives `uri` and `mime`; there is no need for `ContentResolver.getType`.
- **Calls:** `calls.system.audioRoutes` / `currentRoute` / `selectRoute(route)`, where `CallAudioRoute` is `type`, `name` and `id` (tell routes apart by `id`). `container.calls.permissions.prompt` is public.
- **Tests:** unit-test workers have a 2 GB heap.

`grok/g9-prep` (production `attach`, no interim ON_STOP lock) is merged into `claude/android-ui` by the lead only after C3. Never merge it yourself.

**Shared scratchpad:** several agents share `/private/tmp/.../scratchpad`. Always use item-prefixed file names there (for example `c8-gate.sh`, `c8-gate.log`), or keep logs inside your worktree's `build/` directory. Other agents have overwritten `gate.sh` and `gate.log`.
