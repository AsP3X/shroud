# Calls

1:1 voice and video calls between any two of a contact's devices: iPhone and web, in any mix.
Media is WebRTC (DTLS-SRTP), peer to peer, or through the TURN relay when the networks in between
refuse a direct path. The API only rings devices and relays signaling it cannot read; call audio
and video never pass through it.

| Piece | Where |
| --- | --- |
| Signaling API | `server/crates/shroud-server/src/routes/calls.rs` |
| TURN logins | `server/crates/shroud-server/src/turn.rs`; coturn in `docker-compose.yml` (profile `calls`) |
| iPhone | `ios/shroud/Services/Calls/` (native WebRTC, CallKit, PushKit) |
| iPhone screen broadcast | `ios/ShroudScreenShare/` (extension), `ios/ShroudShared/ScreenShareWire.swift` |
| Web | `web/src/calls/` |

A call is voice or video by what its two cameras do, not by how it was placed: either side can
switch its camera on or off at any time without restarting the call (below, "Switching between
voice and video"). Either side can also share its screen next to its camera ("Screen sharing").

## Flow

Calls use **protocol 2**: nothing about the media is negotiated until the call is answered, and
then only between the two devices in the call.

1. **Ring.** The caller's device `POST /calls {peer_user_id, modality, protocol: 2}`. The server
   checks the two are contacts, neither blocks the other and neither is in a call, then sends
   `call.ring` to the callee's connected devices, and a push to the others (below).
   Meanwhile the caller opens its camera/microphone, builds its peer connection and gathers ICE
   candidates, so the offer is ready when the call is answered.
2. **Answer.** One callee device `POST /calls/{id}/accept`. That device is now the call's
   `callee_device_id`; `call.accepted` goes to the caller and to the callee's other devices,
   which stop ringing ("answered on another device").
3. **Negotiate.** On `call.accepted` the caller sends its offer, the callee its answer, and both
   trickle ICE candidates, all as sealed signals (below) through `POST /calls/{id}/signal`. The
   server delivers each signal to the other device in the call and nowhere else.
4. **Talk.** While the call rings (caller) or runs (both), each device in it sends
   `POST /calls/{id}/heartbeat` every 10 s.
5. **End.** `POST /calls/{id}/reject` (callee, while ringing) or `/hangup` (anyone, any time)
   ends it; `call.ended` goes to every device of both people.

The caller is always the offerer, also for ICE restarts, so offers never collide.

## REST (`/api/v1`, bearer auth)

| Method | Path | Body → answer |
| --- | --- | --- |
| GET | `/calls/ice-servers` | → `{ice_servers: [{urls, username?, credential?}]}` |
| POST | `/calls` | `{peer_user_id, modality: "voice"\|"video", protocol: 2}` → 201 call |
| GET | `/calls` | `?limit=1..100&before=<created_at>` → `{calls: [call]}`, newest first |
| GET | `/calls/{id}` | → call (with `peer_media_state` for a device in the live call) |
| POST | `/calls/{id}/accept` | `{}` → call (status `active`) |
| POST | `/calls/{id}/reject` | → call |
| POST | `/calls/{id}/hangup` | → call |
| POST | `/calls/{id}/signal` | `{signal_type, payload}` → 204 |
| POST | `/calls/{id}/heartbeat` | → call (read its `status`: an ended call ends here too), with `peer_media_state` |

A call: `{id, caller_user_id, caller_device_id, caller_username, callee_user_id,
callee_device_id?, callee_username, modality, status, ended_reason?, protocol, created_at,
answered_at?, ended_at?, peer_media_state?}`. Usernames are `null` for a deleted account.
`modality` is how the call was placed (it picks the ring, the push and the history's icon); what
the call carries later is up to the devices. `peer_media_state` (`{from_device_id, payload}`)
appears only in `GET /calls/{id}` and heartbeat answers, only to one of the two devices in an
active call, and only once the other device has sent a `media_state`: it is that signal, still
sealed (see "Switching between voice and video").

Errors (`{error: {code, message}}`): `CALL_BUSY` (409, the callee is in a call),
`FORBIDDEN` (not contacts, or blocked; or a device outside
the call signalling), `CALL_NOT_ANSWERED` / `CALL_ENDED` (409, a signal before the answer or
after the end), `VALIDATION_ERROR` (e.g. accepting a call that stopped ringing, or a build
without `protocol: 2`, which is told to update), `RATE_LIMITED`.

**Who may signal.** Only the caller's device and the callee device that answered, and only
while the call is `active`.

**Status and reason** (`status` / `ended_reason`), and what each side shows:

| status | reason | caller sees | callee sees |
| --- | --- | --- | --- |
| `ringing` | | Ringing… | incoming call |
| `active` | | Connecting…, then the timer | same |
| `rejected` | `rejected` | Declined | — |
| `missed` | `declined` | Declined | — |
| `missed` | `timeout` | No answer | Missed call |
| `cancelled` | `cancelled` / `connection_lost` | — | Missed call |
| `ended` | `hangup` | Call ended | Call ended |
| `ended` | `connection_lost` | Connection lost | Connection lost |

A call rings for at most **60 s**. The server ends a call whose device has not been heard from
(heartbeat or signal) for **45 s**: a ringing call as `cancelled`/`connection_lost`, a live one
as `ended`/`connection_lost`. Before `POST /calls` checks for busy, it ends such calls of both
people, so a crashed app never leaves anyone "busy".

## Call history

The Calls tab lists every call of the account, newest first: placed or taken, on any of its
devices, answered or not. The server keeps each call's row (who called whom, from which device,
voice or video, when it was placed, answered and ended, how it ended) for as long as both
accounts exist; `GET /calls` pages through them with `before=<created_at>` (100 at a time on
the iPhone). A call still ringing or running is left out until it ends.

Each row shows the other person, the direction ("Outgoing voice", "Incoming video"), how it
ended ("Missed", "No answer", "Declined", "Cancelled", "Failed") or, for a call that talked,
how long (answer to end, "4:12"), and when it was placed. A call refused as busy (`CALL_BUSY`)
never gets a row, so it is not listed.

Calls in a row with the same person, all within an hour of the newest of them, share one
section: collapsed to the person and "3 calls · 1 missed", a tap on the row unfolds one compact
row per call, each with its own call buttons, along a thread down from the avatar. A call of
theirs we never took reads "Missed" in red, in its row and in the section's count.

| Piece | Where |
| --- | --- |
| iPhone | `CallController.refreshHistory` / `loadOlderHistory`, `Features/Main/CallsView.swift` |
| Web | no call list yet |

The iPhone reloads the list when the tab appears, on pull to refresh, when the socket
(re)connects, on every `call.ended` (sent to every device of both people except the one that
ended it, so a call from another of our devices shows too) and after a call ends on the phone
itself. That call shows at once and gives way to the server's row. The server only knows a call was answered; the phone that ran it also
knows whether the media ever connected, and says "Failed" rather than a duration when it
did not (this launch only).

**Transcripts (planned).** A call's id is the same for both people and all their devices, so a
transcript attaches to it by id. It is call content, not metadata: sealed like messages, never
readable by the server (the same way voice-note transcripts travel as sealed annotations,
architecture.md). Nothing about transcripts is stored yet.

## WebSocket events

| type | fields | to |
| --- | --- | --- |
| `call.ring` | `call` | the callee's devices; the caller's other devices (ignore it) |
| `call.accepted` | `call` | both people's devices except the one that answered |
| `call.signal` | `call_id, from_user_id, from_device_id, signal_type, payload` | the other device in the call |
| `call.ended` | `call` | both people's devices except the one that ended it |

A device that connects while a call rings for its user gets that call's `call.ring` right after
`auth.ok`. With Redis, the replica that published an event may deliver it twice: clients ignore
a ring, answer, end or signal they have already handled.

## Sealed signals

The server relays `payload` without reading it. It is sealed so only the two people in the call
can open it, and so the server cannot swap the DTLS fingerprint in an SDP (it would then sit in
the middle of the call).

**Call secret** (per pair of people, the same on both sides and on each of their devices):

```
shared = X25519(our identity private key, peer identity public key)
lo, hi = the two identity public keys, lower one first (byte order)
secret = HKDF-SHA256(ikm: shared, salt: "shroud-call-v1",
                     info: "shroud-call-secret-v1" ‖ lo ‖ hi, 32 bytes)
```

Only the holders of the two identity keys can compute it. The iPhone derives it for each
contact whenever the chats are unlocked and keeps it in the Keychain
(`AfterFirstUnlockThisDeviceOnly`, service `com.shroud.call-secrets`), because the identity keys
themselves need Face ID and a locked phone must still be able to answer. It is deleted with the
account's other keys at sign-out, and derived again when a contact's key changes.

**Per call and direction:**

```
key(role) = HKDF-SHA256(ikm: secret, salt: call id as 16 bytes,
                        info: "shroud-call-signal-v1|" + role, 32 bytes)
            role = the sender's: "caller" or "callee"
aad       = "shroud-call-v1|" + call id (lowercase, hyphens) + "|" + signal_type
sealed    = nonce (12 random bytes) ‖ AES-256-GCM(key(role), nonce, plaintext, aad) ‖ tag (16)
payload   = "c1." + base64(sealed)          (standard alphabet, padded)
```

The role in the key stops a signal being reflected back to its sender; `signal_type` in the
additional data stops the server relabelling it. A signal that does not open is dropped.

**After the answer, a fresh key.** The first offer and the first answer stay on the keys above.
Each carries `ek`, a new X25519 public key (standard base64, 32 bytes). Once both are known:

```
shared = X25519(our ephemeral private key, their ek)
lo, hi = the two ephemeral public keys, lower one first (byte order)
fs     = HKDF-SHA256(ikm: shared, salt: the call secret,
                     info: "shroud-call-fs-v1" ‖ call id (16 bytes) ‖ lo ‖ hi, 32 bytes)
key(role) = HKDF-SHA256(ikm: fs, salt: call id as 16 bytes,
                        info: "shroud-call-fs-signal-v1|" + role, 32 bytes)
```

ICE candidates, mute and camera updates, restart requests, and later offers and answers use
these keys. `a=candidate` lines are removed from the session description before it is sealed, so
a network address travels only under this per-call key. The ephemeral private key, and the copy
of the call secret used to derive `fs`, are wiped once `fs` exists and again when the call ends.
A later leak of the two identity keys reopens the offer and the answer, and not the addresses
exchanged after them. A peer that omits `ek` is an older build: the rest of that call stays on
the identity signal keys.

**The certificate.** After the media path connects, each client compares the DTLS certificate's
SHA-256 fingerprint with `a=fingerprint:sha-256` in the sealed remote description. A mismatch
ends the call. The browser also remembers the first identity key it sees for a contact (the
vault; the iPhone already does this in the Keychain) and will not place a call or send a new
message after that key changes until the new one is accepted. Until the safety number has been
compared, the call says so. Comparing it does not delay the call.

**Plaintext** is a JSON object; `n` counts up from 1 per sending device, and a receiver drops an
`n` it has seen from that device.

| `signal_type` | plaintext |
| --- | --- |
| `sdp_offer` | `{"t":"offer","sdp":"…","restart":false,"n":1,"ek":"…"}` — `ek` on the first offer only |
| `sdp_answer` | `{"t":"answer","sdp":"…","n":1,"ek":"…"}` — `ek` on the first answer only |
| `ice_candidate` | `{"t":"ice","cs":[{"candidate":"…","sdpMid":"0","sdpMLineIndex":0}],"n":2}` |
| `renegotiate` | `{"t":"restart","n":3}`: the callee asks the caller for an ICE restart |
| `media_state` | `{"t":"media","mic":true,"camera":false,"screen":false,"n":4}`: what the sender sends now; `camera` switches the call between voice and video, `screen` says whether it shares its screen (absent from apps that predate screen sharing) |

Candidates are batched (up to ~100 ms) to keep requests down. Candidates that arrive before the
remote description is set wait for it.

**Test vector** (`docs/calls.md` is the reference; both clients test against it):

```
alice identity private  ef80f1878a337c4c39eeb6578cf9af4c38bc681ed61ec43084d00d1e1657723d
alice identity public   8b29cac884916cb7098ba9d90b61ef21d88596315cd1d04799a1962012e0b467
bob identity private    58b2859744734402b8fa838480195ffd0c8cfbda7bbe22a710a7d4d8b79b1afd
bob identity public     389c6f5486c58d1c61ee17df7793440e1e5e4b7822cae03fc92c9d2a4920e338
x25519 shared           8e190e7874f1b7804fc6c5e9e650e0a2481638c33a1053d9a1d5a78b19d6a12a
call secret             39ea5a3a4128617d799fab4480c1bff13f92bef72ccb0bbc246da1504ba3839f
call id                 0190a3b4-1c2d-7e8f-9a0b-1c2d3e4f5a6b
key(caller)             3a3d8f2724fe45e6af1af14b490fd1a4e43aacb8d8e13a9009eb677c79bebb30
key(callee)             46ac38a6ec923643613298cd51555c02f62c163bf191a62f200740d3a0cfb099
nonce                   000102030405060708090a0b
signal_type             sdp_offer
plaintext               {"t":"offer","sdp":"v=0\r\n","n":1}     (JSON: the \r\n is escaped)
aad                     shroud-call-v1|0190a3b4-1c2d-7e8f-9a0b-1c2d3e4f5a6b|sdp_offer
payload                 c1.AAECAwQFBgcICQoLQDFMfgG6gj47QCtynr5cS3IjS6czaNhnseRvgliRTrXZeUi+lMfrLwnqwjds+cTC3HtQ
```

The per-call key, with the same identity keys and call id. Ephemeral private keys are
`20` repeated for Alice and `30` repeated for Bob (32 bytes each):

```
forward secret          7d4c5c4a5c2a2d1bc6979d871db81e8642b840c0f0b816378ac62bcf8e4112d6
key(caller)             4f366306d6ee25ffa435b12c0cfb2bf937b625c7f056171c0a1386d2163b98f9
key(callee)             e8b850838219ef3c085075166994a08e4d6a9a5c4edc16c867afe15f2c0f2873
```

**What it does not cover.** The first time either client sees a contact's identity key, it
trusts that key (the safety number is how the two people check it). The server sees who calls
whom, when and for how long, and the devices' IP addresses. The TURN relay sees the IPs and the
amount of media, not its content. The offer and the answer stay recoverable from the two
identity keys; the addresses sent after them do not.

## Media

- A voice call starts with sound only, a video call with sound and both cameras; either side can
  turn its camera on or off at any time (next section). Turning the microphone off disables its
  track and sends `media_state`, so the other side shows a muted mark.
- Voice is Opus, mono, about 32 kbps, with in-band error correction and silence suppression.
  Video goes out at up to 1080p30, stepping down and back up with the link ("Camera quality"
  below). Speech is sent ahead of video. The microphone is captured as speech (echo cancellation,
  noise suppression, and gain control on both clients).
- **ICE restart**: when the connection is `failed`, or `disconnected` for 4 s, the caller sends a
  new offer with `restart: true` (at most one every 10 s); the callee asks with `restart`.
  A `failed` link switches that device to the TURN relay for this restart and every later one,
  when the server offered a TURN server. Until that happens, a short disconnect restarts on
  every path, direct ones included.
- **Always relay calls** (Privacy settings, per device: iOS `SecurityPreferences.alwaysRelayCalls`,
  web `calls/relay.ts`): that device's peer connection uses `iceTransportPolicy: relay` from the
  start, so it only ever sends relay candidates and the other person sees the TURN server's
  address, not this device's. Each side's switch covers only its own address. Without a TURN
  server in `GET /calls/ice-servers`, the device refuses to place or answer the call rather than
  connect directly; an answer refused that way isn't sent, so the account's other devices keep
  ringing.
- If no media connects within 30 s of the answer, the device hangs up ("Couldn't connect").

## Camera quality

Each side decides only what its own camera sends, so nothing about it goes on the wire and an
older app on the other end is unaffected. The same ladder runs on every client
(`web/src/calls/videoQuality.ts`, iOS `CallVideoQuality.swift`, Android `CallVideoQuality.kt`).

- **Capture.** The camera opens at up to 1080p and 30 fps (the size nearest 1920×1080). The encoder
  shrinks it to the rung below (`scaleResolutionDownBy`); our own picture stays full size. A
  webcam whose 1080p runs below 25 fps is opened at 720p instead (web), and a camera switch
  re-sizes the shrink and the top rung to the new camera.
- **The ladder.**

  | Rung | Longer side | fps | Most bits per second | Room the link needs to keep it |
  | --- | --- | --- | --- | --- |
  | 1080p | 1920 | 30 | 3.8 Mbps | 2 Mbps |
  | 720p | 1280 | 30 | 2.2 Mbps | 1.1 Mbps |
  | 540p | 960 | 30 | 1.2 Mbps | 600 kbps |
  | 360p | 640 | 30 | 600 kbps | 300 kbps |
  | 270p | 480 | 20 | 300 kbps | 150 kbps |
  | 180p | 320 | 15 | 150 kbps | — |

  A call starts at 720p, or lower when the camera is smaller; the camera's own size is the top
  rung (a 720p webcam never goes to 1080p).
- **Readings.** Every 2 s while the call is connected and the camera goes out at full size, the
  camera's sender stats give the link's bandwidth estimate (`availableOutgoingBitrate` on the
  selected candidate pair, less 50 kbps for speech), the loss the other side reports
  (`fractionLost`), and what holds the encoder back (`qualityLimitationReason`). The first three
  readings of a call, and the first two after each change, are skipped while the link and the
  encoder settle.
- **Down.** Two readings in a row where the encoder is short of bits (`bandwidth`) and the
  estimate is below the rung's minimum go straight to the highest rung the estimate fits. Two in
  a row with 10 % loss or more go down one rung. A low estimate alone is no reason: while the
  camera sends less than the link could carry, the estimate only grows as far as what is sent.
- **Up.** One rung at a time, after 4 clean readings in a row (8 s): under 3 % loss, and an
  encoder held back by neither processor nor bandwidth. Going up does not wait for the estimate
  to show room (it would not, for the reason above): the higher cap makes WebRTC probe the link,
  and a try the link cannot carry steps back down. An upgrade that has to step down again within
  30 s doubles the wait for the next one, up to about a minute; one that holds resets it. On a
  clean link a call reaches 1080p about 14 s in.
- **Within a rung** WebRTC keeps adapting on its own (`balanced`: frame rate and detail
  together), so a sudden drop is covered before the next reading.
- **Starting bitrate.** The iPhone and Android tell the bandwidth estimator to start at 1 Mbps
  (`setBweMinBitrateBps` / `setBitrate`) instead of WebRTC's 300 kbps, so a call is sharp from its
  first seconds. Browsers have no way to set it; the web ramps up from WebRTC's default.
- **Codec.** Both sides list H.264 first on the camera's section, in the offer and in the answer
  (`cameraVideoSdp` / `CallSdp.withCameraVideo` / Android's equivalent), so it is what goes out
  whoever offers: phones and Macs encode it in hardware, where VP8, which browsers list first,
  runs in software and runs out of processor at these sizes. A browser without H.264 keeps its
  own order. Each side also declares at least H.264 level 4.0 there (`h264Level`): a sender may
  hold its frame rate to the level the receiver declares (the iPhone's encoder does), and the
  3.1 that browsers declare carries 1080p at only about 13 fps. Every client decodes 1080p30, so
  4.0 is true; it states what that side receives, not what it sends.
- **Sharing a screen** puts the camera on a thumbnail's worth instead (below), never more than its
  rung, and the ladder waits; it picks up again at the same rung when sharing stops. A camera that
  is off or paused by the system, and a link that is reconnecting, are not read either: a camera
  that sends nothing would read as a clean link.
- Firefox reports neither the bandwidth estimate nor the encoder's limitation: there the camera
  steps down on loss only, and WebRTC's own adaptation within the rung does the rest.

## Switching between voice and video

Video goes on and off inside the running call: no new offer, so ICE, DTLS and the sound are
never touched, and a switch takes as long as a camera needs to open.

- **Every call has a video section both ways.** The caller's offer always brings one
  (`sendrecv`): with its camera on it for a video call, empty for a voice call. The callee sets the
  offer's video section to send and receive before answering, camera or not. An empty section
  sends nothing, so a voice call costs nothing more on the wire.
- **Video on** opens the camera, puts its track on that section's sender (`replaceTrack` on the
  web, `RTCRtpSender.track` on the iPhone) and sends `media_state` with `camera: true`. **Video
  off** takes the track off at once (`null`), sends `camera: false` and closes the camera, so its
  light goes out. The web lets its own picture fade out first (300 ms), and takes the same
  camera back without asking if Video is pressed again meanwhile.
- **Each side switches only its own camera.** The screen follows both. Their picture opens out
  of their face from the first frame after `camera: true` (never black, never a frame from
  before): a circle grows from the face's edge to the screen's corners (420 ms) while the face
  swells and fades. With `camera: false` the circle shrinks back onto the face (380 ms) while the
  face comes straight back in front of it, so the circle ends behind the face. Both ways it moves
  from the first frame and slows evenly (ease out), and turned around halfway it goes back from
  where it is. On the iPhone the circle is a clip view whose bounds and corner radius the render
  server animates, up to 120 Hz whatever the main thread does (no mask layer, no path drawn per
  frame); at rest nothing is clipped, and a closed picture is hidden with its renderer paused until
  their `camera: true` arrives. On the web it is an animated `clip-path` on the `<video>`. Ours
  sits in a corner while it is on (the whole screen only while the call is being placed), and
  with both off it is a voice call again. A camera the system pauses (the app left the screen,
  another app took the camera) counts as off, so the other side sees the face and not a frozen
  frame.
- **The name stays under the face on a voice call,** on the iPhone and on the web: the name, the
  running time and the speaking meter. Nothing is in their way, so they do not move on a timer.
  On the iPhone our own picture is a small corner tile and does not move them either. Their
  picture moves them to the top-leading corner, level with our own picture and clear of it, for
  as long as their camera is actually showing; their camera off brings them back under the face.
  On the way they travel up and across together, on one spring; the face stays in the middle and
  is drawn on top, so a long name passes behind it. On a short stage
  (landscape) the docked face steps out from under that block, beside it where there is room and
  below it otherwise. Only positions change per frame (`CallStageLayout`). Over their picture a
  shade under the status bar keeps the text at 4.5:1 or better even on a white frame. With Reduce
  Motion the block fades across instead. The web puts the name in a pill at the top once a
  picture fills the screen.
- **Sound on the iPhone.** Turning our camera on while the earpiece plays moves the sound to the
  speaker, and back to the earpiece once no video is left (also for a call placed as video), unless
  the speaker was chosen by hand. CallKit's `hasVideo` follows whether any picture is on.
- **Older apps.** A voice call placed by an app from before this carries no video section, and
  such an app answers ours "receive only". Video then stays off on the side that cannot send (the
  button says why), and the call goes on as before.

**The server's part.** A switch (a camera, or a screen) is a sealed `media_state` like any other, so the server does not
learn of it. It keeps each device's latest one (`calls.caller_media_state` /
`callee_media_state`, at most 4 KiB, cleared when the call ends) and hands the other device's to
the two devices in the call as `peer_media_state` on `GET /calls/{id}` and on every heartbeat. A
device whose socket missed a switch catches up there: at once when its socket is back (`auth.ok`
reads the call), else with the next heartbeat. The copy is opened and checked like any signal,
and a device takes a `media_state` only if its `n` is newer than the last one it took from that
device: a kept copy that arrives after a newer signal changes nothing.

## Screen sharing

Either person shares their screen at any time, next to their camera and never instead of it; both
can share at once. Like video, it needs no new offer: ICE, DTLS and the sound are never touched.

- **Two more sections in every call.** After the camera's, the caller's offer brings a video
  section for the screen and an audio section for its sound, both `sendrecv` and empty until
  someone shares. The callee sets both to send and receive before answering, as it does the
  camera's. The sections carry no names: each end tells them apart by their place among those of
  their kind — the first audio section is the microphone, the first video the camera, the second
  video the screen, the second audio its sound (`sectionOf` on the web, `CallMediaEngine.sections`
  on the iPhone). An empty section sends nothing.
- **Share on** puts the captured screen on its section (`replaceTrack` / `RTCRtpSender.track`) and
  sends `media_state` with `screen: true`; **off** takes it off at once and sends `screen: false`.
  `screen` is always sent by an app that knows screens, so its presence also says "I can show
  yours": Share works only once the other side's `media_state` carried it and the screen's section
  can carry ours. Until then Share looks off, and pressing it says why: "once the call has
  connected" while it connects, "their app needs an update" with an older app on either side (the
  call goes on as before).
- **Codec.** Both sides list VP8 first on the screen's picture section, in the offer and in the
  answer (`screenVideoSdp` / `CallSdp.withScreenVideo`), so VP8 is what goes out there whoever
  offers: an iPhone shares from the background, where its hardware H.264 encoder may not run, and
  VP8 is encoded in software. The camera's section puts H.264 first ("Camera quality").
- **Resolution and frame rate.** Like Discord, the sharer picks both before sharing or while it
  runs (on the web from a small arrow on the Share button's shoulder; on the iPhone in the menu
  that Share opens): 720p, 1080p or Source (the screen's own pixels), and 15,
  30 or 60 fps. A change while sharing applies at once (the web's `applyConstraints` on the capture,
  the iPhone's broadcast told over its socket, and the encoder's limits on both), with no new picker
  and no new offer. The choice is kept per device (web: `localStorage` `shroud.screenQuality`,
  `screenQuality.ts`; iPhone: `UserDefaults`, `ScreenShareQuality`); it describes only what this
  device sends, so nothing about it goes on the wire to the other side. On the web, 720p and 1080p
  fit the capture inside 1280×720 and 1920×1080. The picker opens the capture at the most any choice
  uses (the screen's own pixels, up to 60 fps), because a browser may never raise a capture above
  what it was opened with (Chrome), and `applyConstraints` narrows it to the choice before it goes
  out; where a browser refuses, the encoder shrinks the full-size picture instead
  (`scaleResolutionDownBy`). On the iPhone they cap the longest side at 1280 and 1920 (a tall phone
  screen at 1080p is 1920 pixels high); Source only makes a side even, cutting off one column of an
  odd-width screen rather than resampling it. Defaults: 1080p at 30 fps on the web,
  1080p at 15 fps on the iPhone (every frame costs its broadcast extension a scale and a JPEG). The
  frame rate is a ceiling: a tight link or a busy extension sends fewer.

  | Most bits per second | 15 fps | 30 fps | 60 fps |
  | --- | --- | --- | --- |
  | 720p | 1.2 Mbps | 1.8 Mbps | 2.8 Mbps |
  | 1080p | 1.8 Mbps | 2.5 Mbps | 4 Mbps |
  | Source | 3 Mbps | 4.5 Mbps | 6.5 Mbps |
- **Encoding.** Up to 30 fps the screen goes out as screen content (`contentHint: detail` on the
  web, a screencast source on the iPhone), keeping its sharpness and giving up frames when the link
  is tight (`maintain-resolution`). At 60 fps it was picked for motion (a game, a video): the web
  hints `motion`, and both give up some detail and some frames in turn (`balanced`). Either way it
  goes behind speech and ahead of the camera. While a side shares, its camera
  drops to a thumbnail's worth (at most 350 kbps, 640 pixels on its longer side, 15 fps), since the other side shows it
  as a tile. The screen's sound is Opus in stereo at about 128 kbps, never silenced (`usedtx=0`),
  set in its own section's `a=fmtp` so the microphone keeps its speech settings.
- **Showing theirs.** Their screen fills the call from its first frame, fitted whole on black; their
  camera moves into a tile beside ours, and the name goes to the top. The web offers actual size
  (double-click, or the zoom button; drag, scroll or the arrow keys to pan), fullscreen (which ends
  with their share), and lets the controls fade after 3 s without pointer movement — not while their
  sound waits for a click, and a notice brings them back; our own "sharing" pill stays through it.
  The iPhone zooms by pinching or double-tapping (sharp up to the screen's own pixels), a tap shows
  or hides the controls, which also step aside after 4 s, and turning the phone gives a laptop's
  screen more room. Its sound plays apart from their voice (its
  own `<audio>` on the web; WebRTC mixes it on the iPhone, in mono through the voice processing).
- **Sharing ours, web.** The browser's picker (`getDisplayMedia`) opens from the click on Share,
  asking for a tab's or the system's sound too (`restrictOwnAudio`, where the browser knows it, keeps
  this page's own playback out of a system's sound, so they do not hear themselves); Shroud's own
  tab is left out (it would mirror the call into itself). Its "Stop sharing" bar, or the shared window closing, ends the track, and the
  call stops sharing and says so. A browser without a picker (phones) has no Share button and still
  shows the other side's screen. While sharing, a compact red "Sharing screen · Stop" pill sits in
  the top-left corner beside the encryption badge (a sound glyph when sound goes along) and stays
  when the rest fades; a small tile shows what goes out.
- **Sharing ours, iPhone.** Share is a small round glass button in the top-trailing corner, above
  our camera's picture, not in the row of call controls (tinted while sharing). A tap opens its
  menu: Share Screen (Stop Sharing while it runs) first, then Resolution and Frame rate. It steps
  aside with the controls over their screen but keeps its room, so the pictures under it never
  move. Share Screen opens the system's broadcast picker, 0.35 s later so the menu has closed first, with Shroud's extension
  (`ShroudScreenShare`, `de.corespace.shroud.ScreenShare`) chosen and the microphone switch
  hidden; the phone's whole screen is shared, whichever app is in front, until Stop. The
  extension runs in its own process with about 50 MB: it scales each frame to the chosen longest
  side (1280, 1920, or none for Source), compresses it as JPEG, and sends it over a Unix socket in
  the app group's container (`ScreenShareWire`, 1 MB socket buffers) — at most the chosen frame
  rate, dropping frames while the last is still on its way; a write that stalls for 3 s (the app
  stopped reading) ends the broadcast. The app says one thing back on that socket: a 16-byte
  settings message (magic `0x53485351`, "SHSQ", written little-endian like every field; a version;
  the longest side, 0 for Source; the frame rate) as the broadcast connects and whenever
  the choice changes; until it arrives the extension uses 1920 and 15. Anything else the app sends
  ends the broadcast. The app listens on that socket only while a call runs, decodes the frames onto the
  screen's section with the broadcast's orientation as the frame's rotation, and sends the last
  frame again every half second while the screen is still, so a frame lost on the way is soon
  replaced. Stop in Shroud, or the call ending, closes the socket, and the extension ends the
  broadcast; a broadcast started without a call ends at once and says to start it from a call.
  Nothing in the extension reaches the network or is kept. The call screen shows a compact red
  "Sharing screen" pill with a round stop button at the top centre, under the status bar ("Starting…"
  from the broadcast's connection to its first frame; stop works in both). It stays when the
  controls step aside, and the face, the name and the tiles move down by its height while it shows. The simulator cannot broadcast: debug
  simulator builds send a test pattern through the same socket instead (`SimulatedBroadcast`), at
  an iPhone 17 Pro's size and 60 frames a second, so the chosen resolution and frame rate take
  effect as on a phone.
- **Not yet:** the iPhone does not send its apps' sound (stock WebRTC has no way to feed it into
  the call; it needs its own audio capture). The sound section is there both ways already, so it
  can come without changing the protocol.
- **Audio route and CallKit.** A screen on either side counts as video: the sound moves from the
  earpiece to the speaker (unless chosen by hand) and CallKit shows a video call.

The server learns nothing of it: `screen` is sealed in `media_state`, and the frames are
DTLS-SRTP like the camera's. The TURN relay sees more traffic while a screen is shared.

## Pushes

| Device | Incoming call | When the ring ends |
| --- | --- | --- |
| iPhone with a VoIP token | PushKit push, even when the app is in front (CallKit ignores a call it already shows) | PushKit `call_ended` (same call id) so CallKit stops; the app shows "Missed call" itself |
| iPhone without one (older builds) | alert "Incoming call", only when that app is not in front | alert "Missed call", same `apns-collapse-id` |
| Browser | Web Push `call`/`video_call`, only when that tab is not in front | Web Push `missed_call`, same tag |

A device counts as in front while its socket is open and it has not sent `{type:"focus", focused:false}`.
Leaving the app or the tab sends that, so a suspended phone or a hidden tab still gets the push
even if the socket has not dropped. A socket that never says stays "in front" (older apps show
their own notices).

The simulator has no system call screen, and iOS ends every CallKit call there at once, so
simulator builds skip CallKit and run calls in the app only (`CallKitManager.isAvailable`).

A PushKit push carries the same `shroud` object as an alert: `{v, k: "call" | "video_call" | "call_ended",
call, p: caller, e: sealed caller name}` (`NotificationPayload.swift`), expires when the ringing
does, and must be reported to CallKit before the handler returns. The app then connects, checks
the call still rings (`GET /calls/{id}`), and ends the CallKit call at once if not. `call_ended`
ends the CallKit call with that id (or reports one and ends it immediately, which PushKit
requires even when the call is already over). Per-device "notifications off" also stops call
pushes; a muted chat does not.

## TURN

coturn runs in the host's network namespace (it needs thousands of relay ports) behind the
Compose profile `calls`. It accepts only logins the API minted: `--use-auth-secret` with
`TURN_SECRET`, username `<expiry>:<user id>`, password `base64(HMAC-SHA1(TURN_SECRET,
username))`, valid for `TURN_CREDENTIAL_TTL_SECS` (12 h). It refuses to relay to loopback,
private, link-local, CGNAT, multicast and other reserved ranges (so a TURN login cannot reach
Postgres, Redis or Nebular on the Docker networks), and offers no TCP relay (RFC 6062): WebRTC
relays UDP, and reaches the TURN server over TCP where UDP is blocked.
