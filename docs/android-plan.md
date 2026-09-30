# Android client — plan

What has to be decided and built for the Android app, beyond what the design already fixes.
Read this before the first commit in `android/`.

| Doc | Role |
| --- | --- |
| `design/Android-App.pen` | Every screen, light and dark, plus the Android-only surfaces (see [Design reference](#design-reference)) |
| [architecture.md](./architecture.md) | Wire formats, sealed shapes, security invariants — all apply unchanged |
| [calls.md](./calls.md) | Call signalling, media, screen sharing, pushes |
| [privacy-options.md](./privacy-options.md) | Privacy settings; Phase 5 (device protections) has Android equivalents below |

**Status (2026-09-30):** design done, no code. Nothing below is implemented or verified on a
device; version numbers and API behaviour should be checked against current Android docs when
each item is picked up.

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

## Decide before writing code

These three change the architecture. Each has a recommendation; none is settled.

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
| Key needed while locked (push name key) | Separate Keystore key without user auth, `unlockedDeviceRequired` | Yes — mirrors `AfterFirstUnlockThisDeviceOnly`. It opens sender names only, never messages |

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

Recommendation: FCM first, UnifiedPush second behind the same client interface, no persistent
socket. Decide before building because it sets the device-registration API and the
notification service.

Whatever the transport, the payload rule stands: ids and a kind, plus the sealed sender name
`e` under the key the device registered (AAD `shroud-push-v1|kind|thread|peer`).

### 3. Incoming calls

iOS uses PushKit + CallKit. Android has no equivalent pair; the pieces are:

- Telecom self-managed calls (Core-Telecom Jetpack) for audio focus, Bluetooth and
  interaction with cellular calls.
- A `CallStyle` notification with a full-screen intent, and a `phoneCall` foreground service.
- The full-screen-intent permission is restricted on Android 14+ (granted by default only to
  calling apps; declare it for Play). Without it the ring is a heads-up notification on the
  lock screen — acceptable fallback, already in the design.
- A `call` push must start the ring within the high-priority FCM window; `call_ended` must
  stop it. Same ids as calls.md.

Decide: whether the first release rings when the app is killed (needs all of the above) or
only while it is in memory. Recommendation: all of it — a messenger that misses calls when
closed reads as broken.

## Workstreams

Order is roughly dependency order. iOS sources are the reference implementation.

### A. Foundation

- [ ] Project: Kotlin, Jetpack Compose, single activity, edge-to-edge. Min SDK: decide (26
      covers Keystore features used here; blur needs 31, fallback designed as *Glass — Without Blur*).
- [ ] Theme tokens from the design variables (light + dark), Inter bundled, motion constants
      from `ios/shroud/ShroudUI/Theme/Motion.swift`.
- [ ] Reproducible release builds from day one (pinned toolchain, no build timestamps);
      decide on Play App Signing vs. own key, since it affects who can verify a build.
- [ ] `android:allowBackup="false"` and `dataExtractionRules` excluding everything
      (invariant 4: files excluded from backups).

### B. Crypto and storage

- [ ] Port `Services/Crypto`: BIP39, identity keys, double ratchet, message and media crypto,
      sender tags, sealed device names, safety numbers. Pass the iOS vectors.
- [ ] Vault per decision 1. Backgrounding clears the history key and decrypted threads
      from memory (invariant 5); auto-lock delay as in privacy-options Phase 5.
- [ ] Sealed local store (SQLite or files, AES-256-GCM under `historyKey`), including
      `SealedLocalState` and the ratchet session store.
- [ ] Sensitive temp files: app-private cache only, wiped on lock and on start.

### C. Messaging

- [ ] REST + WebSocket client, focus reporting (`{type:"focus"}`), reconnect rules.
- [ ] Known traps carried over from iOS: the server re-keys sent messages (drop the client
      id on send), tombstones reach the newest page only, annotation caches need purging.
- [ ] Device removal: `DEVICE_REMOVED` + wake push wipes at once, also when the app is killed.
- [ ] Log out: the wipe sequence in *Log Out — Clearing this phone*, then forget push tokens.

### D. Notifications

- [ ] Transport per decision 2; token registration with the per-device name key.
- [ ] Messaging service opens `e` and posts the notification; without the key (before first
      unlock) it posts without a name, never a wrong one.
- [ ] Channels: Messages, Calls, Contact requests. Per-device settings from the server still
      apply; channel settings in Android can override them and the screen should reflect that.
- [ ] `POST_NOTIFICATIONS` (Android 13+) asked from the Allow card only.
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
      the protocol already has the sound section; decide whether to send it.
- [ ] Proximity sensor screen-off during voice calls (iOS gets it for free).

### F. Media, links, voice

- [ ] Metadata strip before encrypt: EXIF/XMP/IPTC for JPEG, HEIC, PNG, WebP; test with real
      Samsung and Pixel camera files (iOS found HEIC XMP and JPEG APP13 survivors).
- [ ] Photos through the system photo picker; `READ_MEDIA_*` only for the Recents strip,
      with the denied and partial states from the design.
- [ ] Video: trim, transcode, thumbnails (Media3 Transformer); match the iOS output limits.
- [ ] Link previews fetched on device, user agent rule from iOS (`WhatsApp/2…`), sealed into
      the message.
- [ ] Voice messages: record, waveform, playback. Transcription: no WhisperKit — evaluate
      whisper.cpp vs. a TFLite build for speed and size on a mid-range phone before promising
      the same model; the Transcription screen's copy depends on the result.

### G. Device protections (privacy-options Phase 5 equivalents)

- [ ] **Screenshots / recents.** Recents cover is designed. Blocking screenshots
      (`FLAG_SECURE`) also blocks the user's own — product decision: off by default with a
      switch, or on. iOS has "Hide chats during screen recording" on by default; the closest
      match is `FLAG_SECURE` tied to that switch.
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

- [ ] Push transport(s) from decision 2; device `platform` value for Android; tests next to
      `api_notifications.rs`.
- [ ] Call pushes for Android: `call` / `video_call` / `call_ended` as high-priority data
      messages with TTL = ring time.
- [ ] Device list: an Android device type for the Devices screen icon.
- [ ] Share links (`/u/<code>`): Android App Links need `/.well-known/assetlinks.json`.

## Test matrix

| Area | Minimum |
| --- | --- |
| Keystore | One StrongBox phone (Pixel), one TEE-only, one Samsung; enrol a new fingerprint; remove the screen lock; reboot and receive a push before first unlock |
| Push | App killed, Doze, battery saver, one aggressive OEM; device without Play services if UnifiedPush ships |
| Calls | Locked, in another app, killed; Bluetooth headset; cellular call arriving mid-call; iPhone ↔ Android and web ↔ Android |
| Screen share | Whole screen and single app; stop from the system chip |
| Media | Metadata strip on real camera files per vendor |
| Layout | 360 dp phone, foldable unfolded, font scale 200 %, dark theme, Android 11 (no blur) |

## Release

- Play data-safety form; foreground-service type declarations (`phoneCall`,
  `mediaProjection`, `microphone`, `camera`); full-screen-intent declaration.
- Decide on a second channel (F-Droid or signed APK) — it is what makes reproducible builds
  and UnifiedPush matter.

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

Not designed yet, because each depends on a decision above: the Android privacy screen
without the keyboard switch and with a screenshot switch (G), battery-restriction help (D),
and the Transcription copy if the model differs (F).
