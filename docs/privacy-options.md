# Privacy options — plan

Working plan for the privacy additions agreed on 2026-09-28. Each phase ships on its own and
leaves the app consistent; tick a box only when the change is built **and** verified as the
phase's "Done when" says.

Existing privacy controls, for reference: lock chats in background, the app-switcher cover,
"Lock chats now", blocking, link previews on/off, "Let contacts clear chats for me"
(`users.allow_peer_chat_delete`), notification sender/preview switches.

Ground rules for every phase:

- **Account-level switches live on the server** (`users` columns, `GET/PUT /privacy/settings`),
  because only the server can stop a peer from receiving something. Device-level switches live
  in `UserDefaults` (iOS) / `localStorage` (web) next to the existing ones.
- **Privacy wins on failure.** If a switch can't be honoured (no TURN relay, a file whose metadata
  can't be removed), take the private path or refuse, and say so — never silently leak.
- **Reciprocal where Signal/WhatsApp are.** Turning off read receipts, typing indicators or
  last seen also hides the contact's from you. The server enforces both directions.
- Both clients get every account-level switch; the web client only gets device-level switches
  that make sense in a browser.

---

## Phase 1 — Remove location and camera data from sent media  *(leak fix)*

Today "Original" photos and some videos leave the iPhone byte for byte, carrying GPS, capture
time and device model. The web client is already clean for photos (canvas re-encode in
`web/src/media/prepareImage.ts`) and writes videos with `tags: {}` (`web/src/media/videoWorker.ts`).

What leaks on iOS:

| Path | File | Why |
| --- | --- | --- |
| Original photo passthrough | `ios/shroud/Services/Crypto/MediaCrypto.swift` `passthrough(_:)` | returns library bytes verbatim (default quality is Original) |
| MP4/M4V passthrough, any quality | `ios/shroud/Services/Crypto/VideoMedia.swift` `encode` → `canPassthrough` | `Data(contentsOf: sourceURL)` |
| Untrimmed export | `VideoMedia.export(asset:preset:onProgress:)` | `AVAssetExportSession` translates source metadata when `metadata == nil` |

Measured on macOS ImageIO/AVFoundation (same frameworks as iOS):

- `CGImageDestinationCopyImageSource` with an empty replacement `kCGImageDestinationMetadata`
  (+ `kCGImageMetadataShouldExcludeGPS`, orientation re-added *inside* the metadata, since
  `kCGImageDestinationOrientation` can't be combined with it) is **lossless** (identical decoded
  pixels) and keeps ISO/Apple gain maps. JPEG comes out with only `tiff:Orientation`.
- HEIC keeps its **XMP packet** (e.g. `photoshop:DateCreated`) through that copy, even with
  `kCGImageMetadataShouldExcludeXMP`. → blank disallowed XMP properties in place (same byte length,
  so HEIF item offsets stay valid), keeping `hdrgm:*` / `HDRGainMap:*`.
- PNG is copied unchanged by `CopyImageSource` → re-encode every frame (PNG is lossless anyway;
  an animated PNG keeps its frames, delays and loop count).
- Camera/editor JPEGs keep their **APP13** segment (IPTC: city, country, creator, dates) through the
  copy → it becomes a COM segment of spaces in place, so MPF offsets to a gain map stay valid.
- Export sessions write fresh `mvhd`/`tkhd`/`mdhd` times, so the recording time in the movie
  header doesn't carry over either (checked with a clip back-dated to 2012).
- Video: `session.metadata = [placeholder item]` + `metadataItemFilter = .forSharing()` leaves no
  metadata items (location, make, model, date, title all gone). `metadata = []` alone does nothing;
  `.forSharing()` alone keeps the creation date.

### Todos

- [x] Probe ImageIO / AVFoundation behaviour (above).
- [x] `ios/shroud/Services/Crypto/MediaMetadataScrubber.swift`: `scrubImage(_ data: Data) -> Data?`
      (JPEG/HEIC/HEIF lossless copy + XMP blanking; PNG re-encode) and a verifier that lists any
      tag outside the allow-list. `nil` = couldn't make it clean.
- [x] `MediaCrypto.passthrough` returns scrubbed bytes; on `nil`, fall through to the re-encode path
      (lossy but clean). Retry re-sends the stored, already scrubbed bytes.
- [x] `VideoMedia`: every `AVAssetExportSession` gets the placeholder metadata + `.forSharing()`;
      the MP4 passthrough becomes a passthrough-preset remux with the same settings.
- [x] Update the compose copy: "Original file — sent untouched" → "Original quality · location
      removed" (the banner is one line).
- [x] Tests (`ios/shroudTests/MediaMetadataScrubberTests.swift`, `VideoMediaEncodeTests`): fixtures
      with GPS / Make / Model / serial / dates / XMP → none survive; orientation survives; pixels
      identical for JPEG/HEIC; a movie with a location item comes out without one.
- [x] Web: the mediabunny "copy" path writes a fresh MP4 with no `udta`/`meta` (checked with an
      iPhone `.mov` and an ISO `loci` MP4); the `videoPlan.ts` header was wrong and is fixed.

**Done when:** new unit tests pass on a simulator, and a GPS-tagged HEIC and a location-tagged MP4
sent at Original arrive without any location (checked by reading the uploaded plaintext in a test).

**Status (2026-09-28): done.** `MediaMetadataScrubberTests` (13) and `VideoMediaEncodeTests` pass on an
iOS 26.5 simulator; `originalSendCarriesNoMetadata` checks the bytes `MediaCrypto.encode` hands to
`sealFile`. Also run against the simulator's stock photos: an iPhone HEIC with `{MakerApple}`, GPS and
depth data, and five camera JPEGs with GPS, IPTC and XMP. All six came out clean with identical
pixels; before the APP13 fix every JPEG fell back to the lossy re-encode.

---

## Phase 2 — Read receipts, typing indicators, last seen

Server-enforced, reciprocal account switches.

| Switch | Column (migration `025_privacy_visibility.sql`) | Default | Effect |
| --- | --- | --- | --- |
| Read receipts | `users.send_read_receipts BOOL` | true | Off: contacts never learn when you read; you don't see theirs. Your own unread counts are unaffected. |
| Typing indicators | `users.send_typing BOOL` | true | Off: your typing isn't relayed; you don't receive others'. |
| Last seen & online | `users.share_presence BOOL` | true | Off: contacts see neither "online" nor "last seen"; you don't see theirs. |

### Todos

- [x] Migration + `routes/privacy.rs`: extend `PrivacySettings` / `UpdatePrivacySettings` (all
      fields optional on PUT; one `UPDATE … SET col = COALESCE($n, col)`).
- [x] Server enforcement, both directions:
  - [x] read events: relay a reader's read marker to the peer only if **both** allow receipts;
        mask any peer-read field in REST responses the same way.
  - [x] typing frames: drop unless sender and recipient both allow typing.
  - [x] presence: `GET /presence/:id` and the presence fan-out return offline / no `last_seen_at`
        unless both share presence; turning sharing off pushes an "offline" update to contacts.
- [x] Server integration tests for each rule (run with `--nocapture`, confirm nothing skipped).
- [x] iOS: `PrivacySettingsDTO` fields, three toggles in `PrivacySecurityView` under a new
      "Visibility" card; stop sending typing frames locally when off (saves traffic).
- [x] Web: same three switches in `components/settings/PrivacyView.tsx`; `api.updatePrivacySettings`
      takes a partial object.
- [x] Docs: `docs/server-plan.md` (users table, presence and push, milestone 6).

**Done when:** server tests prove each rule in both directions; both clients show and persist the
switches; with receipts off the sender's ticks stay at "delivered".

**Status (2026-09-28): done.** `tests/api_privacy.rs` (5 cases) and the whole server suite pass against
Postgres 16 with nothing skipped but the three Nebular storage tests. The web switches were driven in a
harness (partial PUT, tick falls back to "delivered"). The iOS screen compiles and its model tests pass,
but wasn't seen on a device: no simulator has a signed-in session.

---

## Phase 3 — Always relay calls

Device-level switch "Always relay calls" (off by default). On: every peer connection starts with
`iceTransportPolicy = relay`, so the contact only ever sees the TURN server's address.

### Todos

- [x] iOS: `SecurityPreferences.alwaysRelayCalls`; `CallMediaEngine.makeConfig()` uses `.relay`
      when set **and** TURN servers exist. Without TURN the call is refused with a clear message
      rather than falling back to a direct path (`CallController.relayPolicy`).
- [x] Web: same switch in `localStorage` (`calls/relay.ts`, `CallEnv.alwaysRelay`); `buildPeer` starts relayed.
- [x] Toggle + explanation in both privacy screens (costs relay bandwidth, slightly more latency).
- [x] Tests: `web/src/calls/controller.selftest.ts` (relayed from the start, stays relayed after a
      failure, refused without TURN when calling and answering); iOS `CallRelayPolicyTests`.
- [x] `docs/calls.md` describes the switch.

**Done when:** selftests pass and an iPhone ↔ web call with the switch on connects over the relay
(only `relay` candidates in the stats).

**Status (2026-09-28): built, tests pass; a live iPhone ↔ web call over coturn is still to do**
(the local stack with the `calls` compose profile, one iPhone and one browser).

---

## Phase 4 — Who can find you

### Todos

- [x] Migration `026_username_discovery.sql`: `users.discoverable_by_username BOOL DEFAULT true`.
- [x] `GET /users/by-username/:u` returns 404 for an undiscoverable user unless the caller is
      the user, a contact, or has a pending request with them either way. Contact requests go by
      user id, which only a lookup hands out, so they need no rule of their own.
- [x] `POST /users/me/share-code` rotates the share code (old QR codes and links stop working;
      rate-limited to 10 an hour).
- [x] iOS + web: "Find me by username" switch and "Reset QR code" action (confirmation, since
      shared codes break). iOS picks the new code up through `/auth/me`; web stores it with the
      session (`saveShareCode`) and shows it at once, and reads `/auth/me` when the shell opens,
      so a reset on another device doesn't leave a dead QR code on screen.
- [x] Server tests for lookup + rotation (`tests/api_privacy.rs`).

Not covered, on purpose: signing up with a taken name still says it's taken (names stay unique),
and anyone who looked up the old code before a reset already has the account's id.

**Status (2026-09-28): done.** Server tests pass; the web section and reset dialog were driven in a
harness; the iOS card compiles and its model tests pass (not seen on a device).

---

## Phase 5 — Device protections (iOS)

### Todos

- [x] **Only Apple keyboards** (device switch, default off):
      `AppDelegate.application(_:shouldAllowExtensionPointIdentifier:)` refuses `.keyboard` when on.
      iOS may keep the answer for the app's lifetime, so the setting says it applies on next start.
- [x] **Hide chats during screen recording** (device switch, default on): `RootView` reads the
      scene's `sceneCaptureState` through an invisible `UIView` and shows the app-switcher cover while
      it is `.active` (recording, mirroring, sharing) and the chats are unlocked. The cover takes
      touches too, so the app is unusable while recording with this on — that's the point.
- [x] **Auto-lock delay**: immediately / 1 / 5 / 15 min / never replaces "Lock chats in
      background" (on → immediately, off → never, migrated on first read). A suspended app runs no
      timers, so `RootView` notes when it left and locks on the way back (at `.inactive`, under the
      cover) once the delay has passed.
- [x] Tests: `AutoLockDelayTests` (due times, migration, default).

Web: not done, on purpose. The web client already locks a few seconds after the tab is hidden and
after 5 minutes idle regardless, so longer delays would be capped at 5 minutes anyway; there is no
keyboard or screen-capture hook in a browser.

**Status (2026-09-28): done.** The privacy screen was rendered in the simulator (light and dark) from
the unit-test host; the capture cover and keyboard block still want a check on a real iPhone
(start a screen recording from Control Center; install a third-party keyboard).

---

## Phase 6 — Disappearing messages  *(design first; largest change)*

- Per-chat timer (off / 1 h / 1 d / 1 w / 4 w), set by either participant; the change is itself a
  sealed control message both sides show ("X set messages to disappear after 1 day").
- The timer travels **inside** the sealed payload so the server can't lie about it; the server also
  gets a coarse `expires_at` on the envelope so it can delete ciphertext and media on time.
- Clients purge expired messages across their whole local store on a timer and on unlock — they
  can't wait for server tombstones, which only reach the newest page.
- Open questions to settle before building: countdown from send or from read; what happens on a
  device that was offline past expiry (drop on receipt); notification content for expired messages.

---

## Deferred

- "Keep location in original photos" switch — not until someone asks; stripping stays unconditional.
- Per-contact exceptions for last seen.
- Screenshot notifications — easy to defeat, gives false assurance.
