# Shroud Android port: work split and handover (Claude = UI, Grok = system/dev)

Written 2026-10-02 from the repo state at `dev` 8b6c976. The specs live outside the repo, at
`/Users/nvorberg/.claude/projects/-Users-nvorberg-Documents-development-shroud/android-port-specs/`
(`00-plan.md` plus 13 area specs). In this document, "the plan" means that `00-plan.md`, and
"`$SRC`" means `android/app/src/main/java/de/corespace/shroud`.

---

## 1. Current state

### 1.1 Branches and CI
- **`dev` at 8b6c976 (local)** is one commit ahead of `origin/dev` at c0dd437. That commit is a fix for a racy unit test (`RealtimeClientTest`) and has not been pushed.
- **Android CI on `origin/dev` c0dd437 is red.** One test of 2,177 failed: `RealtimeClientTest.networkAvailableReconnectsAtOnceAndResetsTheBackoff` (it expected Connecting, got Failed after a fast 500). The test is fixed in 8b6c976. It passed 3 runs in a row locally under load, and the full local gate passed on c0dd437.
- **Server CI:** the last run was green at 5073ea4. Nothing under `server/` changed after that.
- **History:** commits with "WIP:" titles were merged into `dev` history from the interrupted runs: 40f8a72, 08a0720, 5170359, fa10bdf, 8970957 and 6e21295. Their content was verified. They stay as they are; no history rewrite.

### 1.2 Done and verified on `dev` (waves 0–2, android/ only)
- **Foundations:**
  - Gradle 9.8, AGP 9.4.1, JDK 21 pin, minSdk 30, target 37.
  - Guard tasks `verifyNoMaterial`, `verifyNoGoogleServices` and `verifyNoGoogleClasses`.
  - Hand-rolled DI: `AppContainer` plus `di/*Module.kt`.
  - CI workflow `.github/workflows/android.yml`.
- **Core, complete with JVM tests:**
  - Networking with typed errors (`core/net`, `ApiError`, `ErrorCodes`) and the wire DTOs.
  - Realtime socket with background-socket support.
  - Crypto: BIP39, X25519/Ed25519, envelopes v1–v3, Double Ratchet, sealed device names with kind byte 4, safety numbers.
  - Keystore sealing and the history-key vault.
  - Session and the full device wipe (`SessionController`, `DeviceWipeController`, `DeviceDataWipe`, `DeviceRemovalWake`).
  - Messaging engines: thread store, decoder, pager, send pipeline, reactions, deletes, read state, typing, Notes, sealed local store.
  - Contacts, privacy and peer identity.
  - Media store, transfers, image encoder and metadata scrubbers.
  - Video encode/playback (Media3).
  - Voice record/playback.
  - Links: detector, preview fetch, composer and opener.
  - Notifications: channels, system notifier, name cache, in-app banner state.
  - Calls core: `CallController`, signalling, secrets, history.
  - whisper.cpp native build (arm64-v8a, x86_64) with its JNI wrapper and benchmark.
- **Tests:**
  - 2,177 JVM tests (1 opt-in skip).
  - Instrumented tests on the `shroud_api37` and `shroud_api30` emulators.
  - Engine e2e (`androidTest/.../e2e/EngineE2eTest`, 5 tests, `android/e2e/engine-e2e.sh`) against a scripted web peer (`android/e2e/peer/peer.ts`). It covers text, reply, link previews, photo, video, voice, reactions (incl. a 409 from a second device), deletes, receipts, typing, unread/read sync, mutes, Notes, contacts (code, link, name, block, key change), call signalling with fake media both ways, and revoke from the web wiping the phone. It passed twice in a row on both emulators.
- **Checked by hand:**
  - The real web client renders every message kind sent by Android.
  - Web Devices shows "Android app" with the green tile.
  - Revoking the Android device from the web wipes the phone.

### 1.3 Stubbed on `dev`
| What | Where | State |
| --- | --- | --- |
| Every W3 screen entry point | `$SRC/ui/{chats,contacts,conversation,calls,camera,media,settings,lock,wipe}/**` | 10–20 line stubs with the frozen signatures of plan §1.7.13 |
| Interim root | `$SRC/ui/ShroudApp.kt` (`InterimSession`), `ui/SignedInPlaceholder.kt`, `ui/navigation/AppRouter.kt` | works; to be replaced by the shell |
| Onboarding shims | `$SRC/AppContainer.kt:167-190` (`api`, `sessionController`, `cryptoController`, `bip39`, `needsLocalNetworkPermission`, `hasScreenLock`) | used by `ui/onboarding/*` |
| Push | `core/push/PushRegistration.kt` (`PushRegistration.Inactive`), `core/push/unifiedpush/UnifiedPushReceiver.kt`, `core/push/backgroundconnection/BackgroundConnectionService.kt`, `core/push/BootCompletedReceiver.kt` | ~20-line stubs; no delivery path exists |
| Call media | `core/calls/media/` | missing; no `CallMediaEngine` implementation |
| Call system | `core/calls/system/CallService.kt`, `ui/calls/CallActivity.kt` | stubs; no Telecom, ringing or CallStyle |
| Transcription | `core/transcription/VoiceTranscription.kt` (`Unavailable`) | engine, model download and language memory missing; native lib + benchmark exist |
| Share/save | `core/media/share/DecryptedMediaProvider.kt` | `openFile` throws; no share or save path |
| Media edits | `core/media/edit/MediaEdits.kt` | types only; no renderer or baker |
| UnifiedPush test distributor | `android/e2e/up-stub/` | missing |

### 1.4 Wave 3: stopped by the owner, work saved on branches (none merged)
Each branch is based on c0dd437. "WIP last" means the last commit is an unverified save that may not compile.

| Branch | Commits | Size | State |
| --- | --- | --- | --- |
| `android/w3-shell` | 3 | 25 files, +5,140 | WIP last. RootScreen, MainShell, tab stacks, FloatingTabBar, two-pane, banner, covers, `AppShellController` and `AppRouter` with tests, `ShellEnvironment` ports, `ContainerShellEnvironment`; deletes the interim root; edits `MainActivity.kt` and `di/ShellModule.kt` |
| `android/w3-lock-onboard` | 8 | 25 files, +4,497 | TASK last. Lock screen (modes, hero, choreography, `LockScreenPorts`), designed wipe overlay, onboarding deltas, `OnboardingServices` ports, `PhraseClipboard` |
| `android/w3-chats` | 3 | 19 files, +3,119 | FIX last. Chats tab, New Chat, `ChatsSource` ports |
| `android/w3-settings-a` | 7 | 21 files, +3,388 | FIX last. Settings root and hero, Appearance, Transcription, Server, BrandLogoMark, launcher icons (`res/drawable`, `res/mipmap-anydpi`) |
| `android/w3-settings-b` | 7 | 14 files, +4,526 | FIX last. Devices, Notifications and Sounds, Sound picker, Privacy and Security. **Contains domain logic in UI:** `DevicesBackend` on `ShroudApi`, `DeviceNameSeal` sealing |
| `android/w3-contacts-ui` | 5 | 17 files, +3,702 | FIX last. Contacts tab, Add Contact, scanner, My QR, profile |
| `android/w3-thread-list` | 3 | 21 files, +3,616 | WIP last. Conversation screen, menu state, jump control, reaction flight; `ConversationBackend` ports |
| `android/w3-thread-bubbles` | 7 | 36 files, +7,418 | WIP last. Bubble layout, text, link, photo, video, voice, typing and Notes bubbles; `BubbleServices` ports |
| `android/w3-composer` | 5 | 25 files, +5,997 | WIP last. Composer, gestures, link strip, Recents strip, Notes todo bar; `ComposeServices` ports. **Contains storage access in UI:** `MediaStore.Images` query, `getSharedPreferences(PrefsFiles.DEVICE)` |
| `android/w3-{media-edit,media-view,calls-media,calls-system,calls-ui,push,transcription}` | 0 | — | Empty; they point at c0dd437 and can be deleted |

Other direct API use found in UI code: `w3-lock-onboard` calls `net.api.identityKey` (for "account has no key"), and `w3-settings-a` calls `net.api.devices`.

### 1.5 Known problems and open owner items
- **Whisper speed:** the P7 transcription gate fails on emulators. Base model worst RTF is 0.77 on API 37 and 0.62–0.65 on API 30, against a limit of 0.3. A measurement on three physical phones is still owed; the emulator numbers are in `android/README.md`.
- **64-bit only:** the APK is 64-bit only (`ndk.abiFilters` arm64-v8a and x86_64), so 32-bit-only phones cannot install it.
- **Native check not in CI:** `app/src/main/cpp/check-native.sh` is zsh with BSD `stat`, so CI on Ubuntu doesn't run it.
- **Two iOS/web flaws:** fixed on Android only, both still present on iOS and web:
  1. The decoder's held-bubble and plaintext cache is keyed by message id alone, so a message id the server re-serves for another sender could reuse the cached plaintext.
  2. After an idempotent media replay, the client caches the media key of an unlinked upload.

  The Claude session "Bind decoder cache reuse to sender on iOS and web" is idle and left no branch or changes in this repo.
- **Unsaved design edits:** `design/Android-App.pen` is open in Pen with 167 top-level frames. The file on disk is the 5073ea4 version (W1 kit). Whether the stopped W3-DESIGN run left unsaved edits in Pen is unknown.
- **Not run yet:**
  - The ntfy Android app path, which needs an APK download the owner hasn't approved.
  - Physical-phone rows: Bluetooth answer, a cellular call during a call, ring latency, the whisper P7 gate.
  - A Samsung launcher icon switch.
- **Machine:** nothing is running except the owner's own emulator `Pixel_10_Pro_XL` (emulator-5554), which neither agent may touch.

---

## 2. Frozen contracts

All the boundaries are in-process Kotlin, so "request/response" means the signature. **Grok exposes; Claude consumes.** "Frozen" means:
- No signature or behaviour change without a new handover.
- Grok may add members, but only additively.
- Claude calls only public members.

### Rules for every contract
- **R1 Threading.** Controllers are main-confined (plan §1.1 rule 3): call them from `Dispatchers.Main`, and suspend functions switch threads internally. Members documented as "call off main" (for example `CryptoController.hasLocalIdentity`) are called from `Dispatchers.IO`.
- **R2 Errors.**
  - User-facing failures come back as `String?` (null = success) or as a typed outcome (`AddContactOutcome`, `ChatDeleteOutcome`). The sentences are iOS copy produced by core.
  - Claude renders them verbatim and never parses them.
  - Thrown exceptions are limited to the documented types: `ApiError` (`core/net/ApiError.kt`, codes in `ErrorCodes`), `CryptoError`/`CryptoException`, `VoiceRecorderException`, `ImageEncodeException`, `TranscribeException`.
  - Claude turns an `ApiError` into a sentence only through `SessionController.userMessage(e)`.
- **R3 Auth.** The session is `auth.sessionController.session: StateFlow<Session?>`.
  - With no session, actions return their "Sign in to …" sentence or do nothing.
  - Anything that opens or seals content needs `keys.cryptoController.unlockedUserId == session.userId`; otherwise it throws `CryptoError.Locked` or returns null.
  - UI code never reads tokens or keys.
- **R4 Adapters.** Claude may keep UI-local ports interfaces and their `Container*` adapters (`ShellEnvironment`, `ChatsSource`, `ComposeServices`, `BubbleServices`, `ConversationBackend`, `LockScreenPorts`, …) **only as 1:1 forwarding** to the public members below. That means:
  - no `ShroudApi`, OkHttp, `SharedPreferences`, files, `MediaStore` queries, crypto or `DeviceNameSeal` calls in `ui/`;
  - no branching beyond null and dispatcher handling.

  Anything more is a Grok contract.
- **R5 Ids.** UUIDs are `java.util.UUID`; wire strings are lowercase. Times are `java.time.Instant`.

### K1 Existing core API (frozen as of 8b6c976)
| Module accessor | Public surface Claude may use | Empty and loading states |
| --- | --- | --- |
| `auth.sessionController` (`core/auth/SessionController.kt`) | `session`, `pendingFullLocalWipe`, `pendingWipeReason`, `validate()`, `sessionAfterFailure()`, `consumePendingFullLocalWipe()`, `userMessage(e)` | `session == null` means signed out |
| `auth.deviceWipe` (`DeviceWipeController`) | `phase` (Idle/Running/Done/Failed), `reason`, `active`, `details`, `leftovers`, `retrying`, `handle`, `isPresented`, `feedback` (announcement + haptic), `start(WipeReason)`, `startIfSessionEnded()`, `retry()`, `continueAfterFailure()`, `finishInterruptedWipeIfNeeded()`, `router` | Idle with `isPresented == false` means draw nothing |
| `keys.cryptoController` | `unlockedUserId`, `needsHistoryUnlock`, `lastUnlockErrorMessage`, `vaultPromptInFlight`, `isUnlocked`, `hasLocalIdentity` (IO), `identityPresence` (IO), `vaultState` (IO), `unlockHistoryIfPossible(userId, automatic, method)`, `establishFromSignup`, `unlockWithPhrase`, `lock(wipeStore)` | — |
| `keys.bip39`, `keys.deviceSecurity.isDeviceSecure` | phrase word list and validation; screen-lock presence | — |
| `messaging.controller` (`MessagingController`) | conversations/thread state: `conversations`, `listStatus`, `lastError`, `isOffline`, `isRealtimeConnected`, `threads`, `thread(peer)`, `activePeerId`, `olderHistoryExhausted`, `loadingOlderPeerIds`, `mediaTransfers`, `peerActivities`, `unreadCounts`, `unreadTotal`, `preview`, `notesLastActivity`, `myUserId`, `myUsername`, `isNotesChat`, `username(peer)`, `foldSharedTranscripts`; lifecycle: `prepareCachedState`, `discardPreparedCachedState`, `start`, `stop(wipeDisk)`, `lockSensitiveMemory`, `setActivePeer`; loading: `refreshConversations(force)`, `loadThread`, `loadOlderMessages`; sending: `sendText`, `sendTodo`, `toggleTodo`, `deleteLocalNote`, `sendImage`, `sendVideo(VideoSendPlan, …)`, `sendVoice`, `retryFailedImage/Video`, `shareTranscript`; media: `ensureImage/Video/Voice/LinkImageLoaded`, `cancelMediaDownload`, `mediaBytes`; deletes and read state: `deleteMessage`, `deleteConversation` → `ChatDeleteOutcome`, `setTyping`, `setRecording`, `markChatRead`, `mute`, `isMuted`, `canMute`, `muteChat(MuteDuration)`, `unmuteChat`; reactions: `canReact`, `myReactions`, `toggleReaction`, `setMyReactions`, `hasUnseenReactions`; `registerArtifactSink` | `ListStatus(isLoadingChats, hasLoadedChats, hasLoadedServerChats, chatsError)`: not loaded = skeleton, cached = list, error under Notes. A thread missing from `threads` means not loaded. `olderHistoryExhausted` holds peers with no older page |
| `contacts.controller`, `contacts.privacy`, `contacts.peerIdentities` | `contacts`, `incomingRequests`, `listState: ContactsListState(isLoading, hasLoaded, error)`, `presence`, `blocked`, `rosterChanges`, `pendingInvite` (App Link prefill), `refresh`, `refreshPresence`, `add(invite)` → `AddContactOutcome`, `accept`, `reject`, `block`, `unblock`, `refreshBlocks`; privacy: `settings`, `hasLoaded`, `refresh`, `update(UpdatePrivacySettingsBody)`, `setAllowsPeerChatDelete`, `rotateShareCode`; identity: `identityChanges`, `verifiedPeers`, `events`, `safetyNumber(peer)`, `isSafetyVerified`, `confirmSafety`, `acceptNewIdentity`; `ContactInviteParser`, `QrMatrix` | `hasLoaded == false` with `isLoading` means skeleton; `error` means list error. `pendingInvite` only pre-fills; the UI never sends on its own |
| `notifications.controller` (`NotificationsController`) | `authorization`, `banner`, `pendingOpen`, `haptics`, `isUnlocked`, `isSignedIn`, `activePeerId`, `refreshAuthorization`, `shouldRequestPermissionAfterUnlock`, `markPermissionRequested`, `show`, `dismissBanner`, `openBanner`, `handleTap`, `clearContactRequestNotifications`, `preferences`, `pushSettings`, `sendTest`; `NotificationTap.from(intent)` (taps only through the `.NotificationTapEntry` alias) | `banner == null` means no banner |
| `media.*`, `images.*`, `video.*`, `voice.*`, `links.*` | `MediaImages.decodePreview`, `ByteCountLabel`, `VideoPlanner.previewPlan` → `VideoSendPlan`, `ChatVideoPlayer` (`state`, `player`, `start(VideoSource)`, `play`, `pause`, `seek`, `setMuted`, `teardown`; one `VideoSource { Message(messageId), Content(uri) }`), `VoiceRecorder` (`state`, `start`, `finish`, `cancel`), `VoicePlaybackCoordinator` (`state`, `toggle`, `pause`, `resume`, `seek`, `cycleRate`, `stop`, `progress`, `hasPlayed`), `VoiceWaveform`, `LinkPreviewComposer` (`state`, `draftChanged`, `dismiss`, `toggleShowsAboveText`, `toggleImageSize`, `takeAttachment`, `reset`), `LinkOpener.open`, `LinkDetector` | — |
| Preferences | `keys.securityPreferences`: `autoLockDelay`, `generatesLinkPreviews`, `alwaysRelayCalls`, `hidesDuringScreenCapture` + setters; `auth.colorTheme` (`theme`, `choose`); `auth.brandLogo` (`style`, `isChanging`, `choose`); `serverConfiguration` (`configuration`, save) | — |
| `calls.controller` (`CallController`) | `ui`, `history`, `lastError`, `callMediaStarting`, `isInCall`, `startCall`, `acceptIncoming`, `rejectIncoming`, `hangup`, `toggleMute`, `toggleVideo`, `toggleSpeaker`, `switchCamera`, `toggleScreenShare` → `ShareAction`, `onScreenCaptureConsent(grant)`, `setScreenShareQuality`, `localAudioLevel`, `safetyNumberForActiveCall`, `confirmSafety`, `refreshHistory`, `loadOlderHistory`, `onAppVisible` | `ui` Idle means no call; `history` has loading, empty and error states |
| `AppContainer` | `lockChatsInMemory()` (messaging memory → keys → 10-minute temp sweep; never only the first two), `appScope`, `appPhase` | — |

Idempotency in K1:
- **Refreshes:** `refreshConversations`, `contacts.refresh` and `privacy.refresh` are single-flight; concurrent callers join the running call, and a cancelled caller doesn't stop it.
- **Sends:** send functions create the client message id. Retries and replays are idempotent at the server, and a replayed media send keeps the server's row.
- **Read state:** `markChatRead` and `toggleReaction` are safe to repeat.
- **Delete-for-me:** deleting an already-deleted message counts as done.

### K2 OnboardingService (new, Grok: `core/auth/OnboardingService.kt`)
Replaces the UI-side `ContainerOnboardingServices` and the `AppContainer` onboarding shims.
```kotlin
interface OnboardingService {
    fun hasScreenLock(): Boolean
    fun needsLocalNetworkPermission(): Boolean
    suspend fun register(username: String, password: String): Session      // throws ApiError
    suspend fun login(username: String, password: String): Session         // throws ApiError
    fun sessionAfterFailure(): SessionController.Validation
    suspend fun establishFromSignup(words: List<String>, session: Session)  // throws CryptoError
    suspend fun unlockWithPhrase(words: List<String>, session: Session)     // throws CryptoError
    suspend fun accountHasNoKey(session: Session): Boolean                  // true only on ApiError code KEYS_REQUIRED / 404 identity key
}
// di: auth.onboarding: OnboardingService
```
- **Auth:** `register` and `login` are unauthenticated. The rest need the given session.
- **Errors:**
  - `ApiError.Server(code, message)` covers taken username, wrong password and rate limit, as today.
  - Network errors are `ApiError.Network`.
  - Phrase errors are `CryptoError` (wrong phrase, mismatch).
  - Render through `SessionController.userMessage`.
- **Idempotency:** `register` is not idempotent (a second call with the same name fails as taken). `establishFromSignup` for an account that already has a key fails with `KEYS_EXIST`. That last part is **OPEN**: the question is under OPEN-3 below.

### K3 DevicesController (new, Grok: `core/devices/DevicesController.kt`)
Replaces `DevicesBackend`/`DeviceNames` in `ui/settings/devices` and the `net.api.devices` call in settings-a.
```kotlin
data class DeviceRow(val id: UUID, val label: DeviceNameSeal.Label?, val kind: DeviceKind, val isThisDevice: Boolean,
                     val createdAt: Instant, val lastSeenAt: Instant?)
data class DevicesState(val rows: List<DeviceRow>, val isLoading: Boolean, val hasLoaded: Boolean, val error: String?, val capacity: Int?)
sealed interface RemoveOutcome { data object Removed : RemoveOutcome; data class Partial(val removed: Int, val failed: Int) : RemoveOutcome; data class Failed(val message: String) : RemoveOutcome }
interface DevicesController {
    val state: StateFlow<DevicesState>        // sorted: this device first, then by lastSeenAt desc (iOS order)
    suspend fun refresh()                     // single-flight
    suspend fun rename(id: UUID, name: String): String?    // seals with kind kept (kind fallback rule), PUT name; null = ok
    suspend fun remove(id: UUID): RemoveOutcome            // 404 counts as Removed
    suspend fun removeAllOthers(): RemoveOutcome
}
// di: auth.devices (or devices.controller)
```
- **Auth:** needs a session. Opening names also needs the chats unlocked; when they're locked, `label = null` and the UI shows the kind noun.
- **Empty state:** before the first load, `rows = []` with `hasLoaded = false`.
- **Errors:** a failed refresh sets `error` (an iOS sentence) and keeps the old rows.

### K4 PhotoLibrary (new, Grok: `core/media/library/PhotoLibrary.kt`)
Replaces the composer's `MediaStore.Images` query.
```kotlin
data class LibraryItem(val uri: Uri, val isVideo: Boolean, val dateTaken: Instant?, val durationMs: Long?)
sealed interface LibraryAccess { data object Full : LibraryAccess; data object Partial : LibraryAccess; data object None : LibraryAccess }
interface PhotoLibrary {
    fun access(): LibraryAccess                                   // READ_MEDIA_* / READ_MEDIA_VISUAL_USER_SELECTED / READ_EXTERNAL_STORAGE per API
    suspend fun recent(limit: Int): List<LibraryItem>             // newest first; [] when access is None or the query fails (never throws)
    suspend fun thumbnail(uri: Uri, maxEdge: Int): Bitmap?        // null on failure
}
// di: media.photoLibrary
```
The permission prompt itself is UI (Claude) and goes through `ui/permissions`.

### K5 UiFlags (new, Grok: `core/storage/UiFlags.kt`)
Replaces `getSharedPreferences(PrefsFiles.DEVICE)` in UI.
```kotlin
interface UiFlags {                         // AFU prefs file "shroud.ui"; no content, names or keys; wiped by the Log Out wipe
    fun get(key: String, default: Boolean = false): Boolean
    fun set(key: String, value: Boolean)
}
// di: keys.uiFlags. Keys are owned by Claude (strings, prefix "ui."); Grok never interprets them.
```

### K6 PushRegistration (seam on `dev`; Grok implements it, Claude draws the Delivery UI)
The shape is plan §1.7.10 as on `dev`: `core/push/PushRegistration.kt`, with `UnifiedPushState`, `NoPushReason`, `Distributor`, `PushDelivery(unifiedPush, backgroundConnection, batteryUnrestricted)`, `coversBackground`, `suppressesLocalAnnouncements`, `delivery: StateFlow<PushDelivery>`, `start`, `stop`, `register`, `onSystemSettingsMaybeChanged`, `distributors()`, `chooseDistributor(pkg?)`, `setBackgroundConnection(enabled)` and `forgetRegistration()`. It is reached as `push.registration`.
- **Errors:** never thrown. Problems surface as `UnifiedPushState.Unavailable(reason)`.
- **Auth:** registration needs a session. When signed out, `delivery` is `Unknown` with no background connection.
- **Empty state:** `distributors()` returns `[]` when no distributor is installed, and `Unavailable(NoDistributorInstalled)` follows.
- **Idempotency:** `chooseDistributor` with the current package does nothing. `setBackgroundConnection(true)` twice keeps one service.
- **Copy split:**
  - Claude owns the Delivery section and screen copy, mapped from `NoPushReason`; the texts are in the plan's W3-PUSH card.
  - Grok owns the notification texts and the `PushDeliveryHooks.noDeliveryReason()` sentence used by the notification test.
  - Grok also owns the permanent notification "Connected to receive messages".

### K7 VoiceTranscription (seam on `dev`; Grok implements it)
The shape is plan §1.7.13 as on `dev`: `isAvailable`, `install: StateFlow<TranscriptionInstallState(phase Idle|Downloading|Transcribing, fractionCompleted, isDeterminate, languageName, messageId)>`, `prepareModel()`, `modelIsInstalled()`, `transcribe(audio, mime, hints, conversationId, tracking)`, `availableLocales()`, `languageOverride` and `handOff(from, to)`. It is reached as `transcription.voice`.
- **Errors:** `TranscribeException(message)`; the message is a user sentence.
- **Empty state:** `Unavailable` means `isAvailable == false` and the UI hides the transcript controls.
- **Idempotency:** `prepareModel` is single-flight.
- **Language stats:** sealed. Nothing leaves the device.

### K8 Calls media and system (seams on `dev`; Grok implements them)
- **`CallMediaEngine` and `CallSystem`:** shapes as in plan §1.7.11 on `dev` (`core/calls/CallSeams.kt`), wired with `CallController.attach(engine, system)`. Claude never calls the engine or the system directly, except for:
  - video rendering through `engine.eglContext`, `localVideoTrack`, `remoteVideoTrack` and `remoteScreenTrack`, reached as `callsMedia.engine`;
  - `callsSystem.isOnEarpiece`.

  Both `callsMedia.engine` and `callsSystem.system` are new accessors that Grok adds; on `dev` the two modules are still empty.
- **New, Grok — CallActivity hooks** (`core/calls/system/CallScreenHooks.kt`):
  ```kotlin
  object CallIntents { const val EXTRA_CALL_ID = "call_id"; const val EXTRA_ACTION = "action"   // "show" | "answer"
      fun parse(intent: Intent): Pair<UUID, String>? }
  interface CallScreenHooks { fun onCallScreenShown(callId: UUID)   // starts the phoneCall FGS if it was refused earlier
                              fun onCallScreenHidden() }
  // di: callsSystem.screenHooks. Grok builds every PendingIntent targeting de.corespace.shroud.ui.calls.CallActivity with these extras.
  ```
  Claude's `CallActivity`:
  - sets show-when-locked and turn-screen-on;
  - renders `CallScreen`;
  - calls the hooks in `onResume` and `onPause`;
  - on `"answer"`, calls `CallController.acceptIncoming()`.
- **Screen-share consent:** Claude launches `MediaProjectionManager.createScreenCaptureIntent()`. The result goes to `CallController.onScreenCaptureConsent(ScreenCaptureGrant?)`.

### K9 CameraCapture (new, Grok: `core/media/capture/CameraCapture.kt`)
```kotlin
interface CameraCapture {
    fun bind(owner: LifecycleOwner, preview: Preview.SurfaceProvider, front: Boolean, video: Boolean)   // CameraX; unbinds the old use cases
    fun unbind()
    val hasFrontCamera: Boolean; val hasBackCamera: Boolean
    suspend fun takePhoto(): MediaImageSource          // private temp file (SensitiveTempFiles), never MediaStore; throws ImageEncodeException
    fun startRecording(withAudio: Boolean): Boolean     // false when the mic permission is missing
    suspend fun stopRecording(): PickedMovieFile?       // data class PickedMovieFile(val file: File, val durationMs: Long)
    fun setTorch(on: Boolean); fun setZoom(ratio: Float)
}
// di: media.camera
```
- **Auth:** needs the chats unlocked.
- **Lifecycle:** temp files are swept by `lockChatsInMemory`.
- **Errors:** a bind failure leaves `hasBackCamera`/`hasFrontCamera` false, and the UI shows the no-camera state.

### K10 MediaSharing (new, Grok: `core/media/share/MediaSharing.kt` + `DecryptedMediaProvider`)
```kotlin
sealed interface SaveOutcome { data object Saved : SaveOutcome; data class Failed(val message: String) : SaveOutcome }
interface MediaSharing {
    suspend fun shareUri(messageId: UUID): Uri?        // content://<app>.share/<random>; decrypted in memory; null when not loaded
    fun revokeAll()                                     // called by Grok on lock and wipe; Claude also calls it when the viewer closes
    suspend fun saveToGallery(messageId: UUID): SaveOutcome   // MediaStore IS_PENDING; metadata already scrubbed
}
// di: media.sharing
```
The share-sheet `Intent` (ACTION_SEND with `FLAG_GRANT_READ_URI_PERMISSION`) is built by Claude.

### K11 MediaEditRenderer (new, Grok: `core/media/edit/MediaEditRenderer.kt`)
```kotlin
interface MediaEditRenderer : MediaEditBaker {          // MediaEditBaker = existing seam in core/media/MediaTypes.kt
    override fun render(image: Bitmap, edits: MediaEdits): Bitmap                  // full-resolution bake used by ImageEncoder at send
    suspend fun preview(source: Bitmap, edits: MediaEdits, maxEdge: Int): Bitmap   // same recipes as render, scaled
    val filters: List<FilterRecipe>                                                  // id + title (iOS titles)
}
// di: images.editRenderer, registered with images.registerEditBaker(...) at startup.
// MediaEdits types: core/media/edit/MediaEdits.kt (frozen; additive only).
```
- **Sending:** Claude sends through `messaging.controller.sendImage(source, peer, caption, quality, edits, replyTo)`.
- **Passthrough:** identity edits stay byte-for-byte (`ImageEncoder` already skips the bake for them).
- **UI side:** crop math, gestures and drawing input are Claude's. They produce `MediaEdits` values.

### K12 Notification and App Link entry
- **Notification taps** arrive only through the non-exported alias `.NotificationTapEntry` (`NotificationTap.ENTRY_ALIAS`). Claude's `MainActivity` passes the intent to `NotificationTap.from(intent)` and then to `notifications.controller.handleTap(...)`; routing then reads `pendingOpen`.
- **App Links** go to `contacts.controller.pendingInvite.value = url`.
- **Manifest:** entries (activities, aliases, intent filters, themes, `windowSoftInputMode`, show-when-locked) are Grok's file. Claude sends exact requests through the stop-and-report rule.

### K13 ClientUpdateChecker (added by Claude at the owner's request; Grok owns it from here)
`core/update/ClientUpdateChecker.kt`, `ShroudApi.clientVersion(platform, version)` (`GET /client-version`, no token) and `di/UpdateModule.kt` (checks on each return to the foreground, at most every 10 minutes, and at once after a server switch; sends `versionName`).
```kotlin
// di: update.checker — main-confined (R1)
val prompt: StateFlow<UpdatePrompt>                       // None | Available(current, latest?, url?) | Required(current, latest?, url?)
val update: StateFlow<ClientUpdate>                       // last answer: status, latestVersion?, updateUrl?, serverVersion? (CURRENT before one)
val isChecking: StateFlow<Boolean>
val lastOutcome: StateFlow<UpdateCheckOutcome?>           // Answered(status) | Failed; null until a check on this server finished (never Skipped)
fun check(): Deferred<UpdateCheckOutcome>                 // joins a running check
suspend fun checkAgain(): UpdateCheckOutcome              // Answered(status) | Failed | Skipped (dropped by a server switch)
fun dismissAvailable(offer: UpdatePrompt.Available)       // "Later" on the offer shown; this process only
```
The UI (`ui/update/UpdatePrompts.kt`, layer 95 in `RootScreen`) opens links with `core/update/UpdateLinkOpener`.
`ClientVersionDto.serverVersion` (`server_version`, nullable: older servers leave it out) feeds `ClientUpdate.serverVersion`, which a failed check keeps and a server switch clears.
`di/UpdateModule.kt` also holds what Settings › About Shroud shows: `appVersion: AppVersion` (`versionName` + `BuildConfig.BASE_VERSION_CODE`, the versionCode without the release splits' ABI offset) and `licenses: OpenSourceLicenses` (`core/about/`, reads `assets/licenses/third_party.json` and the texts next to it; regenerate the JSON with `android/scripts/generate_licenses.py` after a dependency change).

### OPEN items (each with the one question that unblocks it)
- **OPEN-1 (owner):** may each agent merge its own finished branch into `dev` and push once its gate is green, or does the owner merge every branch?
- **OPEN-2 (owner):** may Grok download the ntfy Android APK from F-Droid for the e2e? If not, `up-stub` is the only distributor path.
- **OPEN-3 (Grok, answer from the server code):** what does `POST` identity keys return when the account already has a key? That decides K2's `establishFromSignup` error for a retry.
- **OPEN-4 (owner):** save (⌘S) or discard whatever is unsaved in `Android-App.pen` in Pen before Claude's design pass starts.
- **OPEN-5 (owner):** is the 64-bit-only APK accepted for release, or must Grok add the armeabi-v7a whisper build?
- **OPEN-6 (owner):** who provides the physical phones (and a Samsung) for the owed device rows, and when?
- **OPEN-7 (owner):** can Grok read the spec folder at the path above? If not, copy it into the repo first (for example `docs/android-port-specs/`).

---

## 3. Grok backlog (non-UI only)

**Always allowed for Grok:**
- `$SRC/core/**` and `$SRC/di/**` (except `di/ShellModule.kt`), `$SRC/AppContainer.kt`, `$SRC/ShroudApplication.kt`
- `android/app/src/main/AndroidManifest.xml`, `android/app/src/main/res/{raw,xml}/**`, `android/app/src/main/cpp/**`
- Build files: `android/{build.gradle.kts,settings.gradle.kts,gradle.properties,gradle/**}`, `android/app/{build.gradle.kts,proguard-rules.pro}`
- Tests: `android/app/src/test/java/de/corespace/shroud/{core,di,testing}/**`, `android/app/src/androidTest/java/de/corespace/shroud/{core,lifecycle}/**`, `android/app/src/androidTest/java/de/corespace/shroud/e2e/*.kt` (engine e2e; **not** `e2e/ui/**`)
- `android/e2e/**`, `.github/workflows/**`, `server/**`, `scripts/**`, `docs/**` except this file, `android/README.md`

**Always forbidden for Grok:**
- `$SRC/ui/**`, `$SRC/MainActivity.kt`, `$SRC/di/ShellModule.kt`
- `android/app/src/main/res/{drawable,mipmap*,values*,font}/**`
- `android/app/src/test/java/de/corespace/shroud/ui/**`, `android/app/src/androidTest/java/de/corespace/shroud/{ui,e2e/ui}/**`
- `design/**`, `ios/shroud/{Features,ShroudUI,App}/**`, `web/src/components/**`, any `*.tsx`

### Phase A (before Claude's UI pass)

**G1. Green baseline**
- **Outcome:** `dev` with 8b6c976 passes the full gate locally and in GitHub Android CI.
- **Allowed:** core and di tests only, if a further flake shows up.
- **Forbidden:** the always-forbidden list.
- **Depends on:** OPEN-1 for who pushes.
- **Done when:** `:app:testDebugUnitTest :app:lintDebug :app:verifyNoMaterial :app:verifyNoGoogleServices :app:verifyNoGoogleClasses :app:assembleDebug :app:assembleRelease` is green, and the GitHub Android run for the pushed commit is green.
- **Out of scope:** UI branches.

**G2. UI-support contracts K2, K3, K4, K5**
- **Outcome:** `OnboardingService`, `DevicesController`, `PhotoLibrary` and `UiFlags` exist as written in §2, are wired in `di/`, and are covered by JVM tests:
  - Devices: sort order, this-phone detection, rename sealing with kind fallback, the 404-is-removed rule, partial failure.
  - PhotoLibrary: access by API level, and an empty result on failure.
  - UiFlags: wiped by `DeviceDataWipe`.
- **Reference:** the logic already written on `android/w3-settings-b` (`ui/settings/devices/DevicesModel.kt`), `android/w3-lock-onboard` (`ui/onboarding/OnboardingSupport.kt`) and `android/w3-composer`. Read it, port it into core, and don't edit those branches.
- **Allowed:** `core/{auth,devices,media/library,storage}/**`, `di/**` (not `ShellModule`), the matching tests, and `AppContainer.kt`. The onboarding shims are **kept but marked `@Deprecated`**.
- **Forbidden:** the always-forbidden list.
- **Depends on:** G1, and OPEN-3 for one K2 detail.
- **Done when:** gate green, the new tests pass, and `WiringTest` reaches every new accessor.
- **Out of scope:** changing any UI file, and removing the shims (G9).

**G3. UnifiedPush and background connection (W3-PUSH core, implements K6)**
- **Outcome:** a killed app receives messages, rings and the removal wake through a UnifiedPush distributor. With no distributor and the background connection on, a backgrounded app receives them through the kept socket without showing the user online. Duplicates are dropped. Log Out or removal forgets the registration.
- **Work:** `UnifiedPushProtocol`, `UnifiedPushReceiver`, `DistributorDirectory`, `UnifiedPushSubscriptionStore`, `WebPushDecryptor` (RFC 8291), `PushRegistrar` (`PUT /push/web/subscription` with `client:"android"`), `PushDispatcher`, `PushDedup`, `DeviceRemovalWorker`, `BackgroundConnectionService`, `BackgroundConnectionController`, `KeepAlive`, `BatteryOptimization`, `BootCompletedReceiver`, the manifest entries, `PushModule`, the `push.background` channel and the test distributor `android/e2e/up-stub/`. Plan §1.7.10 and the W3-PUSH card give the details and tests.
- **Allowed:** `core/push/**`, `di/PushModule.kt`, `di/NotificationsModule.kt`, `di/WipeHooksImpl.kt` (`forgetPush`, halting the background service), the manifest, `android/e2e/**`, tests.
- **Forbidden:** `ui/settings/push/**` (Claude's Delivery UI) and the always-forbidden list.
- **Depends on:** G1, OPEN-2.
- **Done when:**
  - All tests in the card pass: `WebPushDecryptorTest` (RFC 8291 Appendix A and the server's `web_push.rs` vectors, failing closed), `UnifiedPushProtocolTest`, `PushRegistrarTest`, `PushDispatcherTest`, `BackgroundConnectionControllerTest`, `DeviceRemovalWorkerTest`.
  - These device checks pass on an emulator with `com.google.android.gms` disabled, using `up-stub` and the local ntfy (`android/e2e/stack-up.sh`), with no UI:
    - a push to a killed app posts a named notification;
    - `device_removed` wipes after `/auth/me` confirms;
    - with the background connection on, a message to a backgrounded locked app posts a notification and the user doesn't show online;
    - a `read` push closes the chat's notification.
  - `verifyNoGoogleServices` is green.
- **Out of scope:** the Delivery screen and section UI and their copy (Claude), and FCM (never).

**G4. WebRTC call media (W3-CALLS-MEDIA, implements `CallMediaEngine`)**
- **Outcome:** voice and video calls connect between an Android emulator and the web client on the local stack, through `CallController`. Screen share works in both directions. The speaking level comes from stats, and the camera pauses in the background.
- **Allowed:** `core/calls/media/**`, `di/CallsMediaModule.kt`, the WebRTC dependency in build files (plan §5; no Google services), and androidTest loopback tests under `core/`.
- **Forbidden:** `ui/calls/**` and the always-forbidden list.
- **Depends on:** G1.
- **Done when:** the loopback androidTests pass on `shroud_api37`; a scripted Android↔web call (engine level, with the web peer extended) completes offer, answer, ICE, media frames both ways and hangup; and `verifyNoGoogleClasses` is green.
- **Out of scope:** the call screen.

**G5. Call system integration (W3-CALLS-SYSTEM, implements `CallSystem` and K8 hooks)**
- **Outcome:** a ring from UnifiedPush (G3) or from `CallRing` on the background socket rings a killed or backgrounded locked phone. It posts the CallStyle notification with a full-screen intent to `CallActivity`, or heads-up without that permission. Telecom is used when available. The order is notification first, then the FGS; a refused `phoneCall` FGS start keeps ringing from the notification. Ringer, proximity and audio routes work. Missed calls get a notification with *Call back*. `call_ended` from either path stops the ring.
- **Allowed:** `core/calls/system/**`, `di/CallsSystemModule.kt`, the manifest, `res/raw/**`, tests.
- **Forbidden:** `ui/calls/**` (including `CallActivity.kt`) and the always-forbidden list.
- **Depends on:** G3, G4.
- **Done when:** JVM tests of the ring order and the FGS-refused path pass (forced in a test). On an emulator, a ring to a killed and to a backgrounded locked phone posts the CallStyle notification with a full-screen `PendingIntent` targeting `CallActivity` with `call_id` and `action`, checked with `dumpsys notification` and no UI. `call_ended` removes it, and the missed-call notification appears after a timeout.
- **Out of scope:** what `CallActivity` draws.

**G6. On-device transcription engine (W3-TRANSCRIPTION core, implements K7)**
- **Outcome:** voice notes from Android and iPhone transcribe on device. The model downloads with progress, language stats are sealed, and nothing leaves the device.
- **Allowed:** `core/transcription/**`, `app/src/main/cpp/**`, `di/TranscriptionModule.kt`, the native build region of the build files, tests.
- **Forbidden:** `ui/settings/TranscriptionScreen.kt`, bubbles, the always-forbidden list.
- **Depends on:** G1, OPEN-5.
- **Done when:** these tests pass: `TranscriptionEngineTests`, `TranscriptionLanguageMemoryTests`, `VoiceTranscriberTests`, and the download against a local HTTP server with progress and cancel. A device test transcribes an Android-recorded and an iPhone-recorded note. The emulator benchmark numbers in the README are updated.
- **Out of scope:** the physical-phone P7 gate (OPEN-6).

**G7. Media edit, share and capture core (K9, K10, K11)**
- **Outcome:**
  - Edits bake at send with byte-for-byte passthrough for identity edits.
  - Previews use the same recipes.
  - Shares go through an in-memory provider whose grants are revoked on close, lock and wipe.
  - Save to Gallery uses `IS_PENDING`.
  - Camera captures never reach the gallery.
- **Allowed:** `core/media/{edit,share,capture}/**`, the manifest (provider authority), `di/{ImageModule,MediaModule}.kt`, `di/WipeHooksImpl.kt` (revoke on wipe), tests.
- **Forbidden:** `ui/media/**`, `ui/camera/**` and the always-forbidden list.
- **Depends on:** G1.
- **Done when:** `MediaEditsTest` and the render tests pass (crop, rotate, filter and drawing pixel checks); a send with identity edits is still byte-for-byte; and the renderer is registered through `images.registerEditBaker`. Provider tests show a revoked URI fails to open after `revokeAll`. A device test confirms that a capture file is under `no_backup`/cache and not in MediaStore.
- **Out of scope:** editor, camera and viewer screens.

**G8. Engine e2e for the W3 system paths**
- **Outcome:** the engine e2e and `android/e2e` scripts cover, without UI:
  - push through `up-stub` to a killed app;
  - the background connection;
  - Doze (`dumpsys deviceidle force-idle`), standby bucket rare, and reboot resume;
  - ring and missed call;
  - removal while closed and while backgrounded;
  - both delivery paths on at once (no duplicates);
  - `read` closing a notification.
- **Allowed:** `androidTest/.../e2e/*.kt`, `android/e2e/**`, `.github/workflows/**`.
- **Forbidden:** `androidTest/.../e2e/ui/**` and the always-forbidden list.
- **Depends on:** G3, G5.
- **Done when:** two green runs in a row on `shroud_api37` and `shroud_api30`.
- **Out of scope:** UI journeys.

### Phase B (after Claude's UI pass C1–C17 reports done)

**G9. Final wiring (W3-INT non-UI)**
- **Outcome:**
  - `AppContainer` onboarding shims are removed.
  - `WipeHooksImpl` is final: push forget, background service halt, share revoke, UiFlags wipe, camera temp sweep.
  - `CallController.attach` gets the real engine and system.
  - `missed_call` pushes go to `CallController.handleCallPush`.
  - `docs/android-plan.md` status is updated.
- **Allowed:** `AppContainer.kt`, `di/**` (not `ShellModule`), `core/**`, `docs/**`, tests.
- **Forbidden:** the always-forbidden list.
- **Depends on:** C1–C17.
- **Done when:** gate green, `WiringTest` green, and no reference to the removed shims (`grep` shows 0).
- **Out of scope:** UI.

**G10. Cross-client matrix, non-UI rows**
- **Outcome:** the plan §6.4/§6.5 rows that need no screen pass between the API 37 and 30 emulators, a fresh iOS simulator and the web client. That covers delivery, receipts, reads and badge sync, push and background-connection notifications, rings, removal and wipe verification, and the device list kind byte 4 on iOS and web. Rows needing UI are listed for Claude's C18. Rows needing a physical phone are recorded as owed.
- **Allowed:** `android/e2e/**`, `androidTest/.../e2e/*.kt`, `docs/**`.
- **Forbidden:** the always-forbidden list.
- **Depends on:** G9.
- **Done when:** a written matrix result in `docs/android-plan.md` with pass/owed per row.
- **Out of scope:** screenshot parity.

**G11. Wave 4, non-UI (W4-STAB-CORE, W4-RELEASE, X4-SRV, X4-DOCS, W4-E2E infrastructure)**
- **Outcome:**
  - No open P1/P2 core bugs.
  - Cold start, a 4,000-message thread and media memory within plan budgets, StrictMode/ANR clean.
  - One reproducible release candidate signed with the owner's key: the key is generated offline by the owner and never committed; a two-build compare matches; R8 verified; 16 KB alignment; per-ABI splits.
  - F-Droid metadata, and `fdroid build` verified locally.
  - `check-native.sh` runs in CI (a macOS job or a port).
  - `assetlinks.json` with the release fingerprint.
  - Docs updated: kind 4, UnifiedPush plus background connection, the transcription engine, the build and signing guide.
- **Allowed:** core, di, build files, `.github/**`, `server/**`, `web/public/.well-known/**` or `web/nginx.conf.template`, `docs/**`, `android/README.md`, `android/e2e/**`.
- **Forbidden:** the always-forbidden list.
- **Depends on:** G10.
- **Done when:** the plan §2.5 acceptance of those packages holds; server tests run with `--nocapture` and 0 skips; the RC APK's `apkanalyzer` shows no `com.google.*`.
- **Out of scope:** W4-A11Y-UI, W4-DESIGN.

**G12. iOS and web parity of the two Android fixes (can run any time after G1)**
- **Outcome:**
  - iOS and web reuse a held bubble or cached plaintext only for the same sender (and, for a peer's message, from that peer's chat).
  - After an idempotent media replay they cache the server-kept row's payload and point at its blob, as Android does since 9bcd150 and 409da0a.
- **Allowed:** `ios/shroud/Services/**`, `ios/ShroudShared/**`, `ios/shroudTests/**`, `web/src/**/*.ts` (no `.tsx`, nothing under `web/src/components/`), web selftests.
- **Forbidden:** iOS views, web components, `design/**`.
- **Depends on:** G1.
- **Done when:**
  - iOS tests pass on a fresh simulator with parallel testing off.
  - The web selftests and `npm run build` pass.
  - A new test per client shows a re-served id from another sender opening for real (and failing), and the replay caching the kept row.
- **Out of scope:** any visible change.

---

## 4. Claude backlog (UI only)

**Always allowed for Claude:**
- `$SRC/ui/**`, `$SRC/MainActivity.kt`, `$SRC/di/ShellModule.kt` (it only builds the shell's UI objects)
- `android/app/src/main/res/{drawable,mipmap*,values*,font}/**`; keep the names `ic_stat_shroud`, `ic_launcher*` and the alias icons, which core and the manifest reference
- Tests: `android/app/src/test/java/de/corespace/shroud/ui/**`, `android/app/src/androidTest/java/de/corespace/shroud/{ui,e2e/ui}/**`
- `design/Android-App.pen`, through Pencil MCP only

**Always forbidden for Claude:**
- `$SRC/core/**`, `$SRC/di/**` except `ShellModule.kt`, `AppContainer.kt`, `ShroudApplication.kt`
- `AndroidManifest.xml`, `res/{raw,xml}/**`, `app/src/main/cpp/**`, build files
- `android/e2e/**`, `androidTest/.../e2e/*.kt` (engine e2e), `server/**`, `docs/**`
- iOS and web code, and the other `.pen` files unless a C item names them
- Any schema, migration, API handler or domain logic
- In `ui/`: any `ShroudApi`, OkHttp, `SharedPreferences`, file I/O, `MediaStore`, crypto or `DeviceNameSeal` use (R4)

**Every C item is done only when:**
- `:app:testDebugUnitTest :app:lintDebug :app:verifyNoMaterial :app:verifyNoGoogleServices :app:assembleDebug` is green;
- the changed screens match their frames in `design/Android-App.pen` (CLAUDE.md); new or changed UI lands in the `.pen` in the same item, through Pencil, followed by the owner's ⌘S and an audited diff.

**C1. Integrate the stopped W3 UI branches**
- **Outcome:** one UI branch based on `dev` (after phase A is merged) contains the work of `android/w3-{shell,lock-onboard,chats,settings-a,settings-b,contacts-ui,thread-list,thread-bubbles,composer}`, compiling with the gate green. The interim root (`ui/ShroudApp.kt`, `SignedInPlaceholder.kt`, `ui/navigation/AppRouter.kt`) is replaced by the shell.
- **Allowed:** the always-allowed list.
- **Forbidden:** the always-forbidden list. Merge conflicts in Grok paths are not resolved by Claude; stop and report.
- **Depends on:** G1, G2.
- **Done when:** gate green, and these W2 rules are kept and tested:
  - the auto-lock lock path calls `AppContainer.lockChatsInMemory()`;
  - taps come only through `.NotificationTapEntry`;
  - the device-name sync stays detached.
- **Out of scope:** finishing the screens (C3–C11).

**C2. Remove domain logic from UI (consume K2–K5)**
- **Outcome:** `ui/` has no `ShroudApi`, `net.api`, `DeviceNameSeal`, `MediaStore`, `getSharedPreferences` or `PrefsFiles` use:
  - Devices uses K3;
  - onboarding uses K2 (no `AppContainer` shims);
  - the Recents strip uses K4;
  - the "asked once" flags use K5.

  The UI-side adapters are pure forwarding (R4).
- **Allowed:** the always-allowed list.
- **Forbidden:** the always-forbidden list.
- **Depends on:** C1, G2.
- **Done when:** a `grep` for those symbols under `$SRC/ui` returns nothing, the gate is green, and the moved tests now run against fakes of K2–K5.
- **Out of scope:** changing K2–K5.

**C3. Shell (W3-SHELL)**
- **Outcome:** the plan's W3-SHELL acceptance:
  - lock routing and prewarm, and the 4 s probe while locked;
  - auto-lock Immediately, delayed and Never, read from `securityPreferences.autoLockDelay`;
  - Recents and capture covers per P5;
  - predictive back storyboard `I3RNnl`;
  - two-pane at ≥ 600 dp and the 360 dp layout;
  - tab-bar font clamp at scale 2.0;
  - a notification tapped on the lock screen opens its chat after unlock;
  - the banner host per `qvEKj`.
- **Allowed:** `ui/shell/**`, `MainActivity.kt`, `di/ShellModule.kt`, tests.
- **Forbidden:** the always-forbidden list.
- **Depends on:** C1.
- **Done when:** the shell tests listed in the plan pass, and a device check on API 30 and 37 covers the covers, two-pane and the tap after unlock. API 33/35 cover checks run on Robolectric at those SDKs.
- **Out of scope:** the auto-lock policy values (K1 preferences).

**C4. Lock screen, wipe overlay, onboarding (W3-LOCK-ONBOARD)**
- **Outcome:**
  - The lock modes Biometric, ScreenLockOnly, NoScreenLock and PhraseNeeded per `yGDcx`, `o5GgZ`, `LRnnR`, `p5OtXk` and `dRyqM`, with the choreography including reduce motion.
  - The designed wipe overlay `qaRQA`/`ZFJ1m`, including failure, driven by K1's `DeviceWipeController`.
  - The onboarding addenda: autofill `ContentType`, and `IME_FLAG_NO_PERSONALIZED_LEARNING` on phrase fields.
- **Allowed:** `ui/{onboarding,lock,wipe}/**`, `ui/components/{OnboardingParts,Fields}.kt`, tests.
- **Forbidden:** the always-forbidden list.
- **Depends on:** C2.
- **Done when:** `LockScreenModeTest`, the Compose UI tests of the addenda and the overlay touch-release test pass, and onboarding works end to end on both emulators.
- **Out of scope:** wipe steps and logic (K1).

**C5. Chats tab and New Chat (W3-CHATS)**
- **Outcome:** skeleton, error under Notes, empty/no matches, offline banner, row menu with the nested mute submenu, delete sheet, pull to refresh, New Chat states; frames `eVLXT`, `Fevlu`, `AqgbA`, `WHZDi`, `MGREE`, `zMjzp`.
- **Allowed:** `ui/chats/**`, tests.
- **Forbidden:** the always-forbidden list.
- **Depends on:** C1.
- **Done when:** `ChatsUiStateTest` passes and the screenshots match those frames.
- **Out of scope:** list data rules (K1 `ListStatus`).

**C6. Settings A (W3-SETTINGS-A)**
- **Outcome:** Settings root and hero math, Appearance with system bar icons, launcher logo switch via `auth.brandLogo`, Transcription screen (K7), Server screen through `AppActions.logOut(switchingTo)`.
- **Allowed:** `ui/settings/{SettingsScreen,SettingsDestination,SettingsHero,AppearanceScreen,TranscriptionScreen,ServerSettingsScreen}.kt`, `ui/components/BrandLogoMark.kt`, launcher drawables and mipmaps, tests.
- **Forbidden:** the always-forbidden list.
- **Depends on:** C1; G6 for the live transcription state.
- **Done when:** the hero math vectors pass, and the logo switch is checked on the Pixel launcher (emulator). The Samsung check is recorded as owed.
- **Out of scope:** the alias mechanics (core `BrandLogoPreference`).

**C7. Settings B (W3-SETTINGS-B)**
- **Outcome:**
  - Devices: list, rename, remove, remove all, details sheet with the green phone tile for kind 4, all on K3.
  - Notifications and Sounds, with `PushDeliverySection` at the top, the permission and system-block cards, and the sound picker.
  - Privacy and Security: auto-lock picker, lock now, blocked list, the per-API device-protection footnote, the software-Keystore notice.
- **Allowed:** `ui/settings/{devices,notifications,privacy}/**`, tests.
- **Forbidden:** the always-forbidden list.
- **Depends on:** C2.
- **Done when:** `DevicesViewModelTest` (against a K3 fake) and `NotificationTestOutcomeCopyTest` pass, and the screenshots match.
- **Out of scope:** device sealing and removal rules (K3).

**C8. Contacts UI (W3-CONTACTS-UI)**
- **Outcome:** list with requests and share footer; add by code, username, link and QR; My QR sheet; profile with safety number, verify, trust new key, block/unblock, delete chat, mute; scanner emits once and unbinds; the designed denied state; App Link prefill never sends by itself; `notifications.controller.clearContactRequestNotifications()` while the requests list is on screen.
- **Allowed:** `ui/contacts/**`, tests.
- **Forbidden:** the always-forbidden list.
- **Depends on:** C1.
- **Done when:** the contacts §9 UI tests pass and the screenshots match `CumKS`, `BNBY4`, `l14KDf`, `w6957`, `oGuCt`, `rqBW3`, `ks0in` and `Hueuv`.
- **Out of scope:** invite parsing (K1).

**C9. Conversation screen (W3-THREAD-LIST)**
- **Outcome:**
  - Pins to the bottom on open, and follows only at the bottom or on an own send.
  - Pages 600 dp from the top.
  - A long press never blocks a scroll and swallows the release (0.45 s holds on photo, link, preview, play and chip).
  - Swipe-to-reply metrics.
  - Back closes the menu and viewers first.
  - Starting a call goes through K1 `CallController.startCall`.
- **Allowed:** the W3-THREAD-LIST files listed in plan §2.4, tests.
- **Forbidden:** the always-forbidden list.
- **Depends on:** C1.
- **Done when:** `JumpToLatestStateTests` (8), `MessageMenuLayoutTests` (11), `MessageMenuSourceFrameTests` (6) and `ReactionPanelLayoutTests` (8) pass, and the hold tests pass on a device.
- **Out of scope:** paging and send rules (K1).

**C10. Bubbles (W3-THREAD-BUBBLES)**
- **Outcome:** screenshot parity with `hc3Jf` and the message, link and media frames; last-line time placement; caches cleared on lock and purge through `registerArtifactSink`; emoji render on API 30; voice transcript UI on K7.
- **Allowed:** `ui/conversation/{bubble,links}/**`, `ui/conversation/reactions/{ReactionChips,ReactionFooterLayout,ReactionEmojiFlow}.kt`, `ui/conversation/{DecodedImageCache,LinkPreviewImageCache}.kt`, tests.
- **Forbidden:** the always-forbidden list.
- **Depends on:** C1; G6 for live transcripts.
- **Done when:** `VoiceTranscriptDisclosureTests` (7) and the waveform, reaction and footer layout tests pass, and the screenshots match.
- **Out of scope:** decoding and media loading (K1).

**C11. Composer (W3-COMPOSER)**
- **Outcome:**
  - Follows the IME frame by frame.
  - Hold-to-record in window coordinates (lock, cancel, no self-lock while the keyboard moves), with the mic permission flows.
  - Reply and link strips, Notes todo bar.
  - Attach sheet and Recents on K4, per P9.
  - Picker to compose; drafts cleared on lock.
- **Allowed:** `ui/conversation/{composer,attach,pickers}/**`, `ui/media/MediaSeams.kt`, tests.
- **Forbidden:** the always-forbidden list.
- **Depends on:** C2.
- **Done when:** `ComposerStateTest` and `ChatLinkBarStateTest` pass, and a device check covers the IME and recording gestures.
- **Out of scope:** encoding and sending (K1).

**C12. Photo compose and editors (W3-MEDIA-EDIT, UI half)**
- **Outcome:** compose screen and editors (crop, rotate, filters, drawing) produce `MediaEdits`, preview through K11 and send through `onSend`. Screenshots match `m7AL9`, `xFhll` and `q6TJHc`.
- **Allowed:** `ui/media/{compose,edit}/**`, tests.
- **Forbidden:** `core/media/edit/**` and the always-forbidden list.
- **Depends on:** C1, G7.
- **Done when:** `CropMathTest` and the UI-side tests pass, and identity edits leave `MediaEdits` empty.
- **Out of scope:** baking (K11).

**C13. Camera, video compose, players, viewer, sharing UI (W3-MEDIA-VIEW, UI half)**
- **Outcome:** camera screen on K9; video compose with trim, quality and mute on K1 `VideoPlanner`; player overlay on `ChatVideoPlayer`; pager and zoom viewer; share sheet and Save to Gallery on K10, with `revokeAll` on close.
- **Allowed:** `ui/camera/**`, `ui/media/{video,viewer}/**`, tests.
- **Forbidden:** `core/media/{share,capture}/**` and the always-forbidden list.
- **Depends on:** C1, G7.
- **Done when:** `MediaViewerLayoutTest` and `TrimMathTest` pass, and a device check covers capture, share and save.
- **Out of scope:** the provider, capture storage and gallery writes.

**C14. Call screen, Calls tab, CallActivity (W3-CALLS-UI and CallActivity)**
- **Outcome:** stage, controls, tiles, share control, safety badge and popover, their shared screen, speaking indicator; Calls tab runs, states and older history; TalkBack focus moves to a new call; `CallActivity` per K8.
- **Allowed:** `ui/calls/**` including `CallActivity.kt`, tests.
- **Forbidden:** the always-forbidden list.
- **Depends on:** C1, G4, G5.
- **Done when:** `CallStageLayoutTests` (15) pass, a voice and a video call against the web client work from the UI on an emulator, and answering from the lock-screen ring lands in `CallActivity`.
- **Out of scope:** media and Telecom.

**C15. Push Delivery UI (W3-PUSH, UI half)**
- **Outcome:** the `PushDeliverySection` row ("Delivery": distributor label, "Background connection" or "Off while Shroud is closed") and the `PushDeliveryScreen` with:
  - "Push distributor" (label or "None"), with a chooser when several are installed;
  - the "Background connection" switch with the card's footer;
  - the battery state;
  - the empty state and the refused-host copy from the card.

  All of it is on K6.
- **Allowed:** `ui/settings/push/**`, tests.
- **Forbidden:** `core/push/**` and the always-forbidden list.
- **Depends on:** C1, G3.
- **Done when:** a JVM test maps every `NoPushReason` to its copy and screenshots match the W3-DESIGN delivery frames.
- **Out of scope:** registration.

**C16. Design frames for W3 (W3-DESIGN, plus W2 leftovers)**
- **Outcome:** every new W3 UI state has a frame in `design/Android-App.pen`, matching the code (same copy, spacing and states). This includes:
  - the push delivery states and the "Connected to receive messages" shade notification;
  - the W2-NOTIF permission and channel frames;
  - the Device Details green phone tile for kind 4.

  No FCM or Google wording remains.
- **Allowed:** `design/Android-App.pen` via Pencil MCP only.
- **Forbidden:** the other `.pen` files and all code.
- **Depends on:** OPEN-4; runs alongside C3–C15 or after them.
- **Done when:** the owner presses ⌘S, and the diff against HEAD is audited (top-level hash diff) and holds only these changes.
- **Out of scope:** iOS and web designs.

**C17. UI journeys and screenshot parity (W3-INT, UI half)**
- **Outcome:** the UiAutomator journeys under `androidTest/.../e2e/ui/**` drive the plan §6.4 rows that need screens, both directions against the iOS simulator and the web. A screenshot comparison against the `.pen` frames is recorded, with the differences listed.
- **Allowed:** `androidTest/.../e2e/ui/**`, `ui/**` fixes found by the journeys.
- **Forbidden:** `android/e2e/**` scripts (Grok's; Claude uses them as they are) and the always-forbidden list.
- **Depends on:** C3–C16, G8.
- **Done when:** the journeys pass twice in a row on API 37 and 30.
- **Out of scope:** non-UI matrix rows (G10).

**C18. Accessibility and design reconciliation (W4-A11Y-UI, W4-DESIGN)**
- **Outcome:** TalkBack pass; font scale 200 %; 360 dp; two-pane; dark mode; API 30 without blur; reduce motion; recomposition counts. Design drift is reconciled, and a Platform Notes card covers channels, UnifiedPush plus background connection copy and protections, with no Google wording.
- **Allowed:** `ui/**`, `MainActivity.kt`, `design/Android-App.pen`.
- **Forbidden:** the always-forbidden list.
- **Depends on:** C17, G10.
- **Done when:** the plan §6.6 checklist is signed, the parity diffs are resolved or ticketed, and the owner has pressed ⌘S on an audited diff.
- **Out of scope:** release.

---

## 5. Do-not-touch (either agent; changes need a new handover)
- **The decision record** (top of the plan):
  - no Firebase or Google services ever;
  - UnifiedPush plus the opt-in background connection, one build;
  - whisper.cpp;
  - P1–P18 as decided;
  - own release key, direct APK and F-Droid.
- **Guard tasks:** `verifyNoMaterial`, `verifyNoGoogleServices` and `verifyNoGoogleClasses` stay, and no Material or Google dependency may be added.
- **Wire and storage formats:** envelopes v1–v3, Double Ratchet, sender tags, sealed device names (kind byte 4 = Android app), safety numbers, the storage magics SHRD1/SHRM1/SHRK1, and the plaintext cache's sender-bound AAD (9bcd150).
- **Frozen contracts K1–K12** and the rules R1–R5.
- **Core-produced user sentences:** the iOS-ported error and notification copy (`NotificationKind.bodyLine`, `WipeStep.title`, `AutoLockDelay.label`, `ColorTheme.title`, `BrandLogoStyle` titles, every `String?` error from controllers).
- **History:** `dev` is never rewritten. The WIP-titled commits stay. No force pushes.
- **The server API contract** (`server/**` routes and payloads) is unchanged, except G11's `assetlinks.json`.
- **iOS and web UI.** `design/iOS-App.pen`, `design/iPad-App.pen` and `design/webclient.pen` are not touched in this handover.
- **Owner and agent configuration:** `CLAUDE.md`, `.claude/**`, the plan folder (read-only for both), `gradle/gradle-daemon-jvm.properties` (JDK 21 pin), the Gradle wrapper and its checksum.
- **The owner's emulator** `Pixel_10_Pro_XL` (emulator-5554), the owner's simulators, and the owner's dev database (`shroud-postgres`). Use throwaway stacks only (`android/e2e/stack-up.sh` with its env overrides).
- **This file** (`docs/android-handover.md`): only the owner edits it.

---

## 6. Handover prompt for Grok

````text
You are Grok, the system/dev engineer on the Shroud end-to-end encrypted messenger (repo:
/Users/nvorberg/Documents/development/shroud). Claude owns all UI. You own everything that is not UI.
Implement ONLY the G* items below, in order (phase A: G1–G8 and G12 now; phase B: G9–G11 only
after the owner tells you Claude's C1–C17 are done). If a task needs a UI change, STOP and report it
(file, what, why) instead of doing it. Never "helpfully" cross into UI paths.

CONTEXT
- Android app: android/ (Kotlin, Compose, no Material, no Google services of any kind), ported 1:1
  from the iOS app (ios/, reference) with the web client (web/) as second reference; Rust server
  (server/) is the wire contract. Master plan + area specs:
  /Users/nvorberg/.claude/projects/-Users-nvorberg-Documents-development-shroud/android-port-specs/00-plan.md
  (read its Decision record, §1, §2.0, §2.4 cards W3-PUSH/W3-CALLS-MEDIA/W3-CALLS-SYSTEM/W3-TRANSCRIPTION/
  W3-MEDIA-EDIT/W3-MEDIA-VIEW/W3-INT, §2.5, §5, §6). If you cannot read that folder, stop and say so.
- Full handover with current state: docs/android-handover.md (sections 1–5). Base: branch dev at 8b6c976.
- Build: JAVA_HOME=/Users/nvorberg/Library/Java/JavaVirtualMachines/ms-21.0.11/Contents/Home; from
  android/: ./gradlew <tasks>. Gate: :app:testDebugUnitTest :app:lintDebug :app:verifyNoMaterial
  :app:verifyNoGoogleServices :app:verifyNoGoogleClasses :app:assembleDebug :app:assembleRelease.
- Devices: AVDs shroud_api37 (use port 5560) and shroud_api30 (port 5562); NDK 30.0.16248370, CMake
  4.1.2. Android 17 needs ACCESS_LOCAL_NETWORK to reach 10.0.2.2 (android/e2e/emulator-setup.sh). Local
  stack: android/e2e/stack-up.sh / stack-down.sh (Postgres, Redis, ntfy in Docker; API from server/;
  env overrides SHROUD_E2E_{PREFIX,STATE,PG_PORT,REDIS_PORT,NTFY_PORT,API_PORT}). Never touch the
  owner's emulator Pixel_10_Pro_XL (emulator-5554), the owner's simulators or the dev DB shroud-postgres.
  Server tests: throwaway Postgres, `cargo test -- --nocapture`, grep for "skipping" must be 0.
- Commits: one plain sentence starting "ADD: ", "TASK: " or "FIX: "; commit early and often on your
  own branch grok/<item> from dev; do not push or merge into dev unless the owner says so (OPEN-1).
  Never rewrite history. Never log tokens, keys, names or plaintext.

YOUR PATHS
Allowed: $SRC/core/**, $SRC/di/** (except di/ShellModule.kt), $SRC/AppContainer.kt,
$SRC/ShroudApplication.kt, android/app/src/main/AndroidManifest.xml, android/app/src/main/res/{raw,xml}/**,
android/app/src/main/cpp/**, android build files (build.gradle.kts, settings.gradle.kts,
gradle.properties, gradle/**, app/build.gradle.kts, app/proguard-rules.pro),
android/app/src/test/java/de/corespace/shroud/{core,di,testing}/**,
android/app/src/androidTest/java/de/corespace/shroud/{core,lifecycle}/**,
android/app/src/androidTest/java/de/corespace/shroud/e2e/*.kt, android/e2e/**, .github/workflows/**,
server/**, scripts/**, docs/** (not docs/android-handover.md), android/README.md; for G12 only:
ios/shroud/Services/**, ios/ShroudShared/**, ios/shroudTests/**, web/src/**/*.ts (not .tsx).
($SRC = android/app/src/main/java/de/corespace/shroud)
FORBIDDEN: $SRC/ui/**, $SRC/MainActivity.kt, $SRC/di/ShellModule.kt,
android/app/src/main/res/{drawable,mipmap*,values*,font}/**, android/app/src/test/java/de/corespace/shroud/ui/**,
android/app/src/androidTest/java/de/corespace/shroud/{ui,e2e/ui}/**, design/** (all .pen files),
ios/shroud/{Features,ShroudUI,App}/**, web/src/components/**, any *.tsx, CLAUDE.md, .claude/**,
the plan folder (read-only), gradle/gradle-daemon-jvm.properties, the Gradle wrapper.
The android/w3-* branches hold Claude's UI work: read them for reference, never commit to them.

FROZEN CONTRACTS YOU EXPOSE (no signature/behaviour change without a new handover; additive only)
R1 Controllers are main-confined; suspend functions switch threads themselves.
R2 User-facing failures are String? (null = ok) or typed outcomes, iOS copy produced by core; thrown
   exceptions only ApiError (codes in ErrorCodes), CryptoError/CryptoException, VoiceRecorderException,
   ImageEncodeException, TranscribeException.
R3 Session from auth.sessionController.session; content operations need cryptoController.unlockedUserId
   == session user, else CryptoError.Locked / null. UI never sees tokens or keys.
R4 The UI may only forward 1:1 to your public members; anything with logic, I/O, API, crypto or
   persistence must be one of your contracts.
K1 Everything public today (8b6c976) on: auth.sessionController, auth.deviceWipe, keys.cryptoController,
   keys.bip39, keys.deviceSecurity, keys.securityPreferences, auth.colorTheme, auth.brandLogo,
   serverConfiguration, messaging.controller (MessagingController), contacts.{controller,privacy,
   peerIdentities}, notifications.controller + NotificationTap, media/images/video/voice/links pipelines
   (MediaImages, ByteCountLabel, VideoPlanner/VideoSendPlan, ChatVideoPlayer + VideoSource, VoiceRecorder,
   VoicePlaybackCoordinator, VoiceWaveform, LinkPreviewComposer, LinkOpener, LinkDetector),
   calls.controller (CallController), AppContainer.lockChatsInMemory/appScope/appPhase, the outcome types
   in core/model/Outcomes.kt (ChatDeleteOutcome, AddContactOutcome, ListStatus, ContactsListState).
   Refreshes are single-flight; sends are idempotent by client message id; delete-for-me of a deleted
   message counts as done.
K2 core/auth/OnboardingService (di auth.onboarding): hasScreenLock(), needsLocalNetworkPermission(),
   suspend register(username,password): Session, suspend login(username,password): Session,
   sessionAfterFailure(): SessionController.Validation, suspend establishFromSignup(words, session),
   suspend unlockWithPhrase(words, session), suspend accountHasNoKey(session): Boolean (true only on
   KEYS_REQUIRED / 404 identity key). Errors ApiError / CryptoError. Keep the AppContainer onboarding
   shims (mark @Deprecated) until G9. OPEN-3: confirm from server/ what a second identity-key upload
   returns and document it on establishFromSignup.
K3 core/devices/DevicesController (di auth.devices): data class DeviceRow(id: UUID, label:
   DeviceNameSeal.Label?, kind: DeviceKind, isThisDevice: Boolean, createdAt: Instant, lastSeenAt:
   Instant?); data class DevicesState(rows, isLoading, hasLoaded, error: String?, capacity: Int?);
   sealed RemoveOutcome { Removed; Partial(removed, failed); Failed(message) };
   val state: StateFlow<DevicesState> (this device first, then lastSeenAt desc); suspend refresh()
   (single-flight); suspend rename(id, name): String? (seals keeping the kind, kind fallback rule);
   suspend remove(id): RemoveOutcome (404 = Removed); suspend removeAllOthers(): RemoveOutcome.
   Locked chats → label null. Port the logic from android/w3-settings-b ui/settings/devices/DevicesModel.kt.
K4 core/media/library/PhotoLibrary (di media.photoLibrary): data class LibraryItem(uri, isVideo,
   dateTaken: Instant?, durationMs: Long?); sealed LibraryAccess { Full; Partial; None }; access();
   suspend recent(limit): List<LibraryItem> (newest first, [] on None or failure, never throws);
   suspend thumbnail(uri, maxEdge): Bitmap?.
K5 core/storage/UiFlags (di keys.uiFlags): AFU prefs "shroud.ui"; get(key, default=false): Boolean;
   set(key, value); wiped by the Log Out wipe; keys are opaque strings owned by Claude ("ui." prefix).
K6 PushRegistration exactly as core/push/PushRegistration.kt on dev (plan §1.7.10): delivery StateFlow,
   start/stop/register/onSystemSettingsMaybeChanged, distributors(), chooseDistributor(pkg?),
   setBackgroundConnection(enabled), forgetRegistration(); never throws (Unavailable(reason));
   signed out → Unknown; distributors() [] when none installed; chooseDistributor(current) no-op;
   setBackgroundConnection(true) twice = one service. You own notification texts and
   PushDeliveryHooks.noDeliveryReason(); the Delivery UI copy is Claude's.
K7 VoiceTranscription exactly as core/transcription/VoiceTranscription.kt on dev; errors
   TranscribeException(user sentence); prepareModel single-flight; nothing leaves the device.
K8 CallMediaEngine / CallSystem exactly as core/calls/CallSeams.kt on dev, wired via
   CallController.attach. New: core/calls/system/CallScreenHooks.kt — object CallIntents {
   EXTRA_CALL_ID="call_id"; EXTRA_ACTION="action" ("show"|"answer"); parse(intent): Pair<UUID,String>? };
   interface CallScreenHooks { onCallScreenShown(callId: UUID) /* starts the phoneCall FGS if refused
   earlier */; onCallScreenHidden() } (di callsSystem.screenHooks). Every ring/answer PendingIntent
   targets de.corespace.shroud.ui.calls.CallActivity with these extras. The UI renders video through
   callsMedia.engine.{eglContext, localVideoTrack, remoteVideoTrack, remoteScreenTrack}.
K9 core/media/capture/CameraCapture (di media.camera): bind(owner: LifecycleOwner, preview:
   Preview.SurfaceProvider, front: Boolean, video: Boolean); unbind(); hasFrontCamera; hasBackCamera;
   suspend takePhoto(): MediaImageSource (private temp file, never MediaStore; throws
   ImageEncodeException); startRecording(withAudio): Boolean; suspend stopRecording(): PickedMovieFile?
   (data class PickedMovieFile(file: File, durationMs: Long)); setTorch(on); setZoom(ratio). Temp files
   swept by lockChatsInMemory. Needs chats unlocked.
K10 core/media/share/MediaSharing (di media.sharing): suspend shareUri(messageId): Uri? (in-memory
   provider, null when not loaded); revokeAll() (you also call it on lock and wipe); suspend
   saveToGallery(messageId): SaveOutcome { Saved; Failed(message) } (MediaStore IS_PENDING).
K11 core/media/edit/MediaEditRenderer : MediaEditBaker (di images.editRenderer, registered with
   images.registerEditBaker at startup): render(image: Bitmap, edits): Bitmap (full-resolution bake used
   by ImageEncoder at send; identity edits keep byte-for-byte passthrough as today); suspend
   preview(source: Bitmap, edits, maxEdge): Bitmap (same recipes, scaled); val filters:
   List<FilterRecipe> (id + iOS title). MediaEdits types frozen, additive only.
K12 Notification taps only through the non-exported .NotificationTapEntry alias; App Links set
   contacts.controller.pendingInvite. You own the manifest; Claude requests manifest changes by report.

YOUR BACKLOG (details, allowed/forbidden files and done-when checks in docs/android-handover.md §3)
Phase A:
G1 Green baseline: dev 8b6c976 passes the gate locally and in GitHub Android CI (core/di test fixes
   only if another flake shows up).
G2 Implement K2, K3, K4, K5 with JVM tests and di wiring; port logic from the android/w3-* branches;
   WiringTest reaches every new accessor. Do not touch UI.
G3 W3-PUSH core: UnifiedPush (own AND_3 receiver, no connector lib), WebPushDecryptor (RFC 8291),
   PushRegistrar (client:"android"), PushDispatcher, PushDedup, DeviceRemovalWorker, background
   connection service/controller/KeepAlive/BatteryOptimization, BootCompletedReceiver, manifest,
   PushModule, android/e2e/up-stub. Tests per the card; device checks with GMS disabled via up-stub +
   local ntfy, no UI. Do not build the Delivery screen.
G4 W3-CALLS-MEDIA: WebRTC CallMediaEngine (no Google services), loopback androidTests, engine-level
   Android↔web call incl. screen share both ways.
G5 W3-CALLS-SYSTEM: CallSystem (Telecom, CallStyle + full-screen intent first, phoneCall FGS after,
   refused-FGS path, ringer, proximity, routes, missed-call notification with Call back, call_ended
   stops the ring) + CallScreenHooks/CallIntents (K8). Do not edit ui/calls/CallActivity.kt.
G6 W3-TRANSCRIPTION core: engine, session, language memory (sealed), transcriber, model download with
   progress; implements K7; update the README emulator benchmark.
G7 K9 CameraCapture, K10 MediaSharing + DecryptedMediaProvider, K11 MediaEditRenderer with tests.
G8 Engine e2e for system paths: push to a killed app, background connection, Doze/standby/reboot,
   ring + missed call, removal closed/backgrounded, both paths on (no duplicates), read closes the
   notification; two green runs on both emulators.
G12 (any time after G1) iOS + web parity of Android fixes 9bcd150 (decoder/plaintext cache bound to
   sender) and 409da0a (replay caches the kept row's payload/blob); non-UI files only.
Phase B (only after the owner confirms Claude's C1–C17 are done):
G9 Final wiring: remove the AppContainer onboarding shims, final WipeHooksImpl (push forget,
   background service halt, share revoke, UiFlags wipe, camera temp sweep), CallController.attach with
   the real engine/system, missed_call → handleCallPush, docs/android-plan.md status.
G10 Cross-client matrix rows of plan §6.4/§6.5 that need no screen (Android API 37/30, fresh iOS
   simulator, web); list the UI rows for Claude; physical-phone rows recorded as owed.
G11 W4 non-UI: W4-STAB-CORE, W4-RELEASE (owner's offline key, reproducible build, R8, 16 KB, per-ABI
   splits, F-Droid recipe, check-native.sh in CI), X4-SRV (assetlinks.json), X4-DOCS, W4-E2E
   infrastructure.

OPEN QUESTIONS (ask the owner, do not guess): OPEN-1 merge/push rights; OPEN-2 may you download the
ntfy APK (else up-stub only); OPEN-5 is a 64-bit-only APK accepted; OPEN-6 physical phones; OPEN-7
spec folder access.

STOP-AND-REPORT RULE: if a G item needs any change under a forbidden path (UI files, resources,
designs, MainActivity, ShellModule), or a contract change, stop, write the exact request (path,
change, reason, which C item consumes it), and continue with the next item that does not need it.
Report at the end of each G item: branch, commits, gate result, device checks run, what is owed.
````

---

## 7. Resume prompt for Claude

````text
You are Claude, the UI engineer on the Shroud Android port (repo /Users/nvorberg/Documents/development/shroud).
Grok has finished the system/dev items G1–G8 (and G12). Implement ONLY the C* items of
docs/android-handover.md §4, in order C1 → C18. Consume the contracts of §2 (R1–R5, K1–K12) exactly as
written. Do NOT modify API, schema, domain logic, core/, di/ (except di/ShellModule.kt), AppContainer,
ShroudApplication, the manifest, res/raw, res/xml, cpp, build files, android/e2e scripts, the engine
e2e, server/, docs/, iOS or web code. If a contract is insufficient (a missing member, a needed
behaviour, a manifest entry, a resource core reads), STOP that item and list the gap — path, exact
signature or change, why, which C item needs it — then continue with the next item that does not
depend on it. Never add logic, I/O, API calls, SharedPreferences, MediaStore, file access or crypto
under ui/: UI-side ports adapters only forward 1:1 to Grok's public members (rule R4).

BEFORE C1
- Read CLAUDE.md, docs/android-handover.md (all sections), the plan
  /Users/nvorberg/.claude/projects/-Users-nvorberg-Documents-development-shroud/android-port-specs/00-plan.md
  (§1.7.12–1.7.13, §2.4 cards of the W3 UI packages, §2.5 W4-A11Y-UI/W4-DESIGN, §6.4, §6.6) and the
  memory notes android-port-plan / android-build-env.
- Confirm with git that Grok's phase-A branches are merged into dev (K2–K5, K6 implemented, K7, K8
  hooks, K9–K11 present in core/). If any is missing, stop and report which.
- Ask the owner about OPEN-4 (unsaved Android-App.pen edits in Pen) before any design work.

YOUR PATHS
Allowed: android/app/src/main/java/de/corespace/shroud/ui/**, .../MainActivity.kt,
.../di/ShellModule.kt, android/app/src/main/res/{drawable,mipmap*,values*,font}/** (keep ic_stat_shroud,
ic_launcher* and the alias icon names), android/app/src/test/java/de/corespace/shroud/ui/**,
android/app/src/androidTest/java/de/corespace/shroud/{ui,e2e/ui}/**, design/Android-App.pen (Pencil MCP only).
Your starting material: branches android/w3-{shell,lock-onboard,chats,settings-a,settings-b,contacts-ui,
thread-list,thread-bubbles,composer} (WIP, some last commits unverified). Merge them onto a UI branch from
dev; conflicts in Grok-owned paths are not yours to resolve — stop and report.

ITEMS (fields in §4): C1 integrate the W3 UI branches; C2 remove domain logic from ui/ (consume K2–K5);
C3 shell; C4 lock/wipe/onboarding; C5 chats; C6 settings A; C7 settings B; C8 contacts; C9 conversation
screen; C10 bubbles; C11 composer; C12 photo compose + editors (K11); C13 camera/video/viewer/sharing UI
(K9, K10); C14 call screen, Calls tab, CallActivity (K8 hooks); C15 push Delivery UI (K6); C16 W3 design
frames in Android-App.pen (+ W2-NOTIF frames, Device Details kind-4 tile); C17 UI journeys + screenshot
parity; C18 accessibility + design reconciliation.

RULES
- Gate per item: from android/ with JAVA_HOME=/Users/nvorberg/Library/Java/JavaVirtualMachines/ms-21.0.11/Contents/Home
  run ./gradlew :app:testDebugUnitTest :app:lintDebug :app:verifyNoMaterial :app:verifyNoGoogleServices
  :app:assembleDebug. Device checks on shroud_api37 (port 5560) / shroud_api30 (port 5562) with a
  throwaway stack (android/e2e/stack-up.sh as it is). Never touch the owner's Pixel_10_Pro_XL emulator.
- Every UI change lands in design/Android-App.pen in the same item, through Pencil MCP only (bring the
  file to the front first: open -a /Applications/Pen.app design/Android-App.pen). Tell the owner the
  .pen change is not on disk until they press ⌘S; audit the diff against HEAD before committing it.
- Render core-produced sentences verbatim (R2); own all other interface copy, matching iOS.
- Commits: one plain sentence starting "ADD: ", "TASK: " or "FIX: ", ending with
  "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"; commit early and often; do not push or merge into dev
  without the owner's go (OPEN-1); never rewrite history.
- When C1–C17 are done, report to the owner so Grok can start phase B (G9–G11).
````
