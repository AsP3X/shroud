# Android client — plan

What has to be decided and built for the Android app, beyond what the design already fixes.
Read this before the first commit in `android/`.

| Doc | Role |
| --- | --- |
| `design/Android-App.pen` | Every screen, light and dark, plus the Android-only surfaces (see [Design reference](#design-reference)) |
| [architecture.md](./architecture.md) | Wire formats, sealed shapes, security invariants — all apply unchanged |
| [calls.md](./calls.md) | Call signalling, media, screen sharing, pushes |
| [privacy-options.md](./privacy-options.md) | Privacy settings; Phase 5 (device protections) has Android equivalents below |

**Status (2026-10-01, wave 1 merged):** the port follows a five-wave plan (W0 contracts, W1
foundations, W2 engines, W3 screens and platform, W4 hardening and release). Waves 0 and 1 are in
`android/` (see [android/README.md](../android/README.md)):

- **Foundations:** the full REST client (`ApiClient`, `ShroudApi`, error model with Retry-After,
  connectivity), the one realtime socket (holders, focus and background frames, backoff), every
  sealed plaintext shape, the v1/v2/v3 envelopes and double ratchet, media crypto, sealed device
  names, safety numbers — all pinned by the iOS and web vectors.
- **Keys:** the sealed key stores, the history-key vault bound to the screen lock (Keystore wrap
  key, system biometric prompt), key-material wipe, and `CryptoController` parity with iOS. The
  Lock screen that opens the vault at launch arrives in wave 3; until then the phrase step stands
  in for it and going to the background drops the keys (invariant 5), except while the vault's own
  prompt is up.
- **Session:** every authenticated request and the socket report to the session; three 401s in a
  row and `DEVICE_REMOVED` (token-checked) both sign out and delete the stored identity and vault at
  once, as iOS's full wipe does. Log Out deletes them too. The full wipe overlay is wave 2/3.
- **UI kit:** every colour token, SwiftUI-matched motion, haptics, icons, tiered glass, toasts,
  list rows, bars, settings parts, menus, sheets, dialogs, pull to refresh — no Material.
- **Seams for wave 2** are published with their final signatures (messaging, contacts, media,
  video, links, notifications, calls, wipe hooks, the shared domain model).
- **Server, iOS, web (wave 1):** the server sends UnifiedPush to Android subscriptions and knows
  background sockets; iOS and web read the Android device kind (4); the web sends delivery acks.

Sign Up still has two steps, unlike iOS (*Account*, then *Phrase*; nothing reaches the server
before the phrase step's Create Account), refuses a phone without a screen lock, and asks for
Android 17's local-network permission before the first request to a LAN or emulator-host server.
After unlocking, a temporary placeholder with Log Out stands in for the main shell; it is not in
the design on purpose and goes in wave 3.

## Ground rules

- **Same app, not a Material app.** The design is the iOS design. Chrome (app bar, floating tab
  bar, switches, sheets, context menus) is drawn by the app; Material 3 widgets are not used for it.
  Only system-owned UI differs — the list is the *Android · Platform Notes* card in the design file.
- **Same wire.** Envelopes, ratchet, sender tags, sealed device names, media crypto and safety
  numbers must be byte-compatible with iOS and web. Port against the iOS test vectors
  (`ios/shroudTests`), not against prose.
- **Security invariants 1–13 in architecture.md hold.** Where an invariant names Keychain or
  Face ID, the Android mapping is in [Key storage](#1-key-storage-and-unlock).
- **Design stays in sync.** `android/` ↔ `design/Android-App.pen`, as in `CLAUDE.md`.

## Decisions

These three change the architecture. **All decided on 2026-10-01** by the product owner; the
decision record below supersedes the options tables, which stay for the reasoning.

**Decision record 2026-10-01 (binding):**

- **No Google, one build.** No Firebase/FCM, no Google Play services, ML Kit, Tink or Play Core,
  directly or transitively; no `play`/`foss` flavors. CI enforces it (`verifyNoGoogleServices`,
  `verifyNoGoogleClasses`).
- **Decision 1 (key storage):** as recommended, with these corrections. Items the phone must read
  while locked — call secrets, the UnifiedPush subscription keys, the notification name cache —
  use a Keystore key without user auth and **without** `unlockedDeviceRequired` (readable after
  the first unlock, iOS `AfterFirstUnlockThisDeviceOnly`). There is **no push name key** on
  Android: UnifiedPush carries the sender inside the RFC 8291 ciphertext. The vault's wrap key is
  imported (not generated) and auth-bound; a software-only Keystore is allowed with a one-line
  notice on the lock screen and in Privacy and Security.
- **Decision 2 (push):** **UnifiedPush** (Web Push RFC 8030/8291 + VAPID, which the server already
  sends) through a distributor the user installs, e.g. ntfy, **plus an opt-in "Background
  connection"**: a foreground service that keeps the WebSocket open for phones without a
  distributor. Messages, call rings and the device-removed wipe arrive through either path. The
  UnifiedPush connector library is not used (it depends on Tink): the app implements the receiver
  protocol and RFC 8291 decryption on BouncyCastle. No FCM routes, table or sender on the server.
- **Decision 3 (calls):** ring when the app is killed or the phone locked — a high-urgency
  UnifiedPush message (`Urgency: high`, TTL 60 s = ring time) or a ring on the background
  connection, then a CallStyle notification with a full-screen intent first, Telecom and the
  `phoneCall` foreground service after. Without the Android 14+ full-screen-intent permission the
  heads-up ring is accepted.
- **Other product decisions** (device kind 4 "Android app", notification permission timing,
  screenshot protection, whisper.cpp transcription, CameraX, own release key with direct APK +
  F-Droid, and the rest) are recorded in the port plan; Play is an optional later channel
  shipping the same Google-free APK.

### 1. Key storage and unlock

iOS: `historyKey` is AES-GCM wrapped under a device key gated by user presence
(`HistoryKeyVault`); identity keys are `WhenUnlockedThisDeviceOnly`; the push name key is
readable after first unlock.

| Question | Options | Recommendation |
| --- | --- | --- |
| Wrap key | Keystore AES key, `setUserAuthenticationRequired(true)`, authenticators `BIOMETRIC_STRONG \| DEVICE_CREDENTIAL` | Yes. StrongBox when the device has it, TEE otherwise; never software-only without telling the user |
| Auth validity | Per-use (`CryptoObject`) vs. time window | Per-use: one prompt unwraps the history key, which then lives in memory as on iOS |
| New fingerprint enrolled | `setInvalidatedByBiometricEnrollment(true)` (key dies) vs. `false` | `true`. The lock screen falls back to the phrase — designed as *Locked — Fingerprints Changed* |
| Weak (class 2) face unlock only | Cannot unlock a Keystore key | Offer screen lock and phrase only; label the button by the enrolled strong biometric |
| No screen lock | — | Refuse to open the vault: *Locked — No Screen Lock* |
| Key needed while locked (call secrets, UnifiedPush keys, notification name cache) | Separate Keystore key without user auth; with or without `unlockedDeviceRequired` | **Without** `unlockedDeviceRequired` (decided): mirrors `AfterFirstUnlockThisDeviceOnly`, since pushes and rings arrive while the phone is locked. No push name key on Android (the sender travels inside the UnifiedPush ciphertext) |

Open: what to show on devices whose Keystore is known to be unreliable (keys lost after OS
update). The phrase path covers it, but it needs a test on at least one such device.

### 2. Push transport

The server speaks APNs and Web Push (`server/crates/shroud-server/src/push/`). It has no FCM
client. Android needs one of:

| Option | Server work | Notes |
| --- | --- | --- |
| **FCM data messages** (high priority) | New FCM HTTP v1 client, new token type | Works on nearly every phone sold with Play services. Google sees ids and the sealed name, same as Apple today |
| **UnifiedPush** | Small: it *is* Web Push (RFC 8291 + VAPID), which the server already sends; allow-list of distributor hosts (`WEB_PUSH_ALLOWED_HOSTS`) needs a policy | For de-Googled phones. The user must install a distributor |
| **Persistent socket** (foreground service) | None | Battery cost and a permanent notification; last resort |

~~Recommendation: FCM first, UnifiedPush second.~~ **Decided 2026-10-01: UnifiedPush plus the
opt-in persistent socket ("Background connection"), no FCM** (decision record above). Android
subscriptions use `PUT /push/web/subscription` with `client: "android"` and a distributor host
policy on the server.

The payload stays ids and a kind; the sender's name travels inside the RFC 8291 ciphertext, which
only this phone can open (no sealed `e` for Android).

### 3. Incoming calls

iOS uses PushKit + CallKit. Android has no equivalent pair; the pieces are:

- Telecom self-managed calls (Core-Telecom Jetpack) for audio focus, Bluetooth and
  interaction with cellular calls.
- A `CallStyle` notification with a full-screen intent, and a `phoneCall` foreground service.
- The full-screen-intent permission is restricted on Android 14+ (granted by default only to
  calling apps; declare it for Play). Without it the ring is a heads-up notification on the
  lock screen — acceptable fallback, already in the design.
- A `call` push (high-urgency UnifiedPush, or the background socket) starts the ring;
  `call_ended` stops it. A distributor broadcast carries no foreground-service start exemption,
  so the notification rings first. Same ids as calls.md.

Decided 2026-10-01: the first release rings when the app is killed (all of the above), on the
UnifiedPush and background-connection transports instead of FCM (decision record above).

## Workstreams

Order is roughly dependency order. iOS sources are the reference implementation.

### A. Foundation

- [x] Project: Kotlin, Jetpack Compose, single activity, edge-to-edge. Min SDK 30 (Android 11,
      the oldest in the test matrix; Keystore auth parameters need it). Blur needs 31, fallback
      designed as *Glass — Without Blur*.
- [x] Theme tokens from the design variables (light + dark), Inter bundled, motion constants
      from `ios/shroud/ShroudUI/Theme/Motion.swift`.
- [ ] Reproducible release builds from day one (pinned toolchain, no build timestamps); our own
      release key (decided), so F-Droid can ship our signature. Done so
      far: Gradle wrapper pinned by checksum, versions in `gradle/libs.versions.toml`, no
      dependency metadata in the APK. Not yet checked by building twice and comparing.
- [x] `android:allowBackup="false"` and `dataExtractionRules` excluding everything
      (invariant 4: files excluded from backups).

### B. Crypto and storage

- [x] Port `Services/Crypto`: BIP39, identity keys, double ratchet, message and media crypto,
      sender tags, sealed device names, safety numbers. Pass the iOS vectors (wave 1).
- [ ] Vault per decision 1 (built in wave 1; the Lock screen and the auto-lock delay of
      privacy-options Phase 5 are wave 3). Backgrounding clears the history key and decrypted
      threads from memory (invariant 5).
- [ ] Sealed local store (SQLite or files, AES-256-GCM under `historyKey`), including
      `SealedLocalState` and the ratchet session store.
- [x] Sensitive temp files: app-private cache only, wiped on lock and on start.

### C. Messaging

- [x] REST + WebSocket client, focus reporting (`{type:"focus"}`, `"background": true` for the
      background connection), reconnect rules (wave 1).
- [ ] Known traps carried over from iOS: the server re-keys sent messages (drop the client
      id on send), tombstones reach the newest page only, annotation caches need purging.
- [ ] Device removal: `DEVICE_REMOVED` + wake push wipes at once, also when the app is killed.
- [ ] Log out: the wipe sequence in *Log Out — Clearing this phone*, then forget push tokens.

### D. Notifications

- [ ] Transport per decision 2: UnifiedPush registration (distributor choice, subscription keys
      made on the phone) and the opt-in background connection.
- [ ] The receiver decrypts the RFC 8291 message and posts the notification; a process started
      before the first unlock does nothing until the user unlocks (distributors deliver queued
      pushes then).
- [ ] Channels: Messages, Calls, Contact requests. Per-device settings from the server still
      apply; channel settings in Android can override them and the screen should reflect that.
- [ ] `POST_NOTIFICATIONS` (Android 13+) asked once after the first unlock, then from the
      Settings cards (decided).
- [ ] Badges: launcher dots; count from the push where the launcher supports it.
- [ ] OEM battery restrictions: detect the common ones and link to the right settings page;
      expect late pushes on Xiaomi, Huawei, Oppo, older Samsung regardless.

### E. Calls and screen sharing

- [ ] WebRTC (same stack version policy as iOS), signalling from calls.md unchanged.
- [ ] Decision 3 for ringing. Audio routing (earpiece, speaker, wired, Bluetooth) via Telecom.
- [ ] Mic level for the speaking indicator: iOS polls sender stats
      (`media-source.audioLevel`); same approach works here.
- [ ] Screen share: MediaProjection + `mediaProjection` foreground service; consent dialog at
      every start; tolerate single-app sharing (Android 14+) and projection stopping on its own.
      App audio capture (`AudioPlaybackCapture`) is possible on Android where iOS has none —
      not in v1 (decided).
- [ ] Proximity sensor screen-off during voice calls (iOS gets it for free).

### F. Media, links, voice

- [ ] Metadata strip before encrypt: EXIF/XMP/IPTC for JPEG, HEIC, PNG, WebP; test with real
      Samsung and Pixel camera files (iOS found HEIC XMP and JPEG APP13 survivors).
- [ ] Photos through the system photo picker; `READ_MEDIA_*` only for the Recents strip,
      with the denied and partial states from the design.
- [ ] Video: trim, transcode, thumbnails (Media3 Transformer); match the iOS output limits.
- [ ] Link previews fetched on device, user agent rule from iOS (`WhatsApp/2…`), sealed into
      the message.
- [ ] Voice messages: record, waveform, playback. Transcription: whisper.cpp (decided), still
      gated by a benchmark on a mid-range phone; the Transcription screen's copy depends on it.

### G. Device protections (privacy-options Phase 5 equivalents)

- [ ] **Screenshots / recents.** Recents cover is designed. Decided: a per-API-level scheme
      (`FLAG_SECURE`, Recents, the recording callback) behind one switch, on by default, like
      iOS's "Hide chats during screen recording".
- [ ] **Keyboards.** iOS can refuse third-party keyboards; Android cannot. Set
      `IME_FLAG_NO_PERSONALIZED_LEARNING` on composer, search and phrase fields, and drop the
      "Only Apple keyboards" switch from the Android privacy screen.
- [ ] Clipboard: mark phrase copies sensitive (`EXTRA_IS_SENSITIVE`) and clear as iOS does.

### H. Platform polish

- [ ] Predictive back for every dismissible surface (storyboard in the design).
- [ ] IME insets: composer follows the keyboard frame by frame (*Conversation — Keyboard Open*).
- [ ] Window sizes: 360 dp minimum, list–detail from 600 dp.
- [ ] App icon switch (Detailed / Simple) via `activity-alias`.
- [ ] Haptics mapped from `Haptics.swift`; long-press menu timing from the iOS numbers.
- [ ] Accessibility: TalkBack labels, font scale up to 200 %, touch targets 48 dp.

## Server changes

- [x] UnifiedPush for Android subscriptions (`client: "android"`, distributor host policy,
      `UNIFIEDPUSH_*` settings), background sockets that never count as online; tests in
      `api_push.rs` (wave 1).
- [x] Call pushes for Android: `call` / `video_call` / `call_ended` as high-urgency Web Push with
      TTL = ring time, sent regardless of foreground (wave 1).
- [x] Device list: kind byte 4 "Android app" in the sealed device name; iOS and web read it
      (wave 1); Android writes it from its first release.
- [ ] Share links (`/u/<code>`): Android App Links need `/.well-known/assetlinks.json` with our
      release-key fingerprint.

## Test matrix

| Area | Minimum |
| --- | --- |
| Keystore | One StrongBox phone (Pixel), one TEE-only, one Samsung (also: the vault prompt on One UI); enrol a new fingerprint; remove the screen lock; reboot, unlock, and receive the queued push |
| Push | UnifiedPush with ntfy and the background connection; app killed, Doze, battery saver, one aggressive OEM; AOSP images without Google APIs |
| Calls | Locked, in another app, killed; Bluetooth headset; cellular call arriving mid-call; iPhone ↔ Android and web ↔ Android |
| Screen share | Whole screen and single app; stop from the system chip |
| Media | Metadata strip on real camera files per vendor |
| Layout | 360 dp phone, foldable unfolded, font scale 200 %, dark theme, Android 11 (no blur) |

## Release

- Own release key; direct APK and F-Droid (reproducible build) are the channels (decided). Play
  is an optional later channel shipping the same Google-free APK; it would add the data-safety
  form and the foreground-service and full-screen-intent declarations (`phoneCall`,
  `mediaProjection`, `microphone`, `specialUse` for the background connection). Never `camera`:
  the call service is `phoneCall|microphone|mediaProjection` (calls D3).

## Design reference

Sections in `design/Android-App.pen` that exist only for Android:

| Section | Holds |
| --- | --- |
| Android · Platform Notes (card) | Every deviation from iOS in one place |
| Dark Theme | Eleven screens in dark; all colours are themed variables |
| Android · System Surfaces | Notification shade per kind, incoming-call heads-up, incoming call on a locked phone, recents cover |
| Android · Permissions | System dialogs and denied states for notifications, microphone, camera, photos |
| Android · Keyboard & Screen Sizes | Keyboard open, five screens at 360 × 800, two-pane layout |
| Android · Back, Fallbacks & Recovery | Predictive back storyboard, glass without blur |
| Lock | *Locked — System Biometric Prompt*, *Locked — Fingerprints Changed*, *Locked — No Screen Lock* |
| Screen Share | *Screen Share — System Consent* |
| Onboarding (right of the Platform Notes card) | *Sign Up — Phrase* (+ Dark), *Sign Up — No Screen Lock*, *Log In Flow — Phrase Step · At Launch*, *Permission — Local Network*, *Sign Up — Phrase · Local Network Denied* |

Not designed yet (wave 3 design work, now that the decisions are taken): the Android privacy
screen without the keyboard switch and with the screen-capture switch (G), the push delivery
screen (distributor choice, background connection, battery restriction help) (D), and the
Transcription copy for whisper.cpp (F).
