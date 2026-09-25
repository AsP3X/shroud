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
| Web | `web/src/calls/` |

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
| GET | `/calls/{id}` | → call |
| POST | `/calls/{id}/accept` | `{}` → call (status `active`) |
| POST | `/calls/{id}/reject` | → call |
| POST | `/calls/{id}/hangup` | → call |
| POST | `/calls/{id}/signal` | `{signal_type, payload}` → 204 |
| POST | `/calls/{id}/heartbeat` | → call (read its `status`: an ended call ends here too) |

A call: `{id, caller_user_id, caller_device_id, caller_username, callee_user_id,
callee_device_id?, callee_username, modality, status, ended_reason?, protocol, created_at,
answered_at?, ended_at?}`. Usernames are `null` for a deleted account.

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

**Plaintext** is a JSON object; `n` counts up from 1 per sending device, and a receiver drops an
`n` it has seen from that device.

| `signal_type` | plaintext |
| --- | --- |
| `sdp_offer` | `{"t":"offer","sdp":"…","restart":false,"n":1}` |
| `sdp_answer` | `{"t":"answer","sdp":"…","n":1}` |
| `ice_candidate` | `{"t":"ice","cs":[{"candidate":"…","sdpMid":"0","sdpMLineIndex":0}],"n":2}` |
| `renegotiate` | `{"t":"restart","n":3}`: the callee asks the caller for an ICE restart |
| `media_state` | `{"t":"media","mic":true,"camera":false,"n":4}`: what the sender sends now |

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

**What it does not cover.** The web client takes a peer's identity key from the server's key
directory (it pins nothing yet), so on the web the server could still hand out its own key; the
iPhone pins keys and shows safety numbers. The server sees who calls whom, when and for how
long, and the devices' IP addresses. The TURN relay sees the IPs and the amount of media, not
its content.

## Media

- Voice calls carry audio only; video calls audio and video. Turning the camera or microphone
  off disables the track and sends `media_state`, so the other side shows the avatar or a muted
  mark instead of black.
- Voice is Opus, mono, about 32 kbps, with in-band error correction and silence suppression.
  Video stays at most 720p30 and about 1.2 Mbps, and gives up frame rate and detail together
  when the link is tight. Speech is sent ahead of video. The microphone is captured as speech
  (echo cancellation, noise suppression, and gain control on both clients).
- **ICE restart**: when the connection is `failed`, or `disconnected` for 4 s, the caller sends a
  new offer with `restart: true` (at most one every 10 s); the callee asks with `restart`.
  A `failed` link switches that device to the TURN relay for this restart and every later one,
  when the server offered a TURN server. Until that happens, a short disconnect restarts on
  every path, direct ones included.
- If no media connects within 30 s of the answer, the device hangs up ("Couldn't connect").

## Pushes

| Device | Incoming call | When it ends unanswered |
| --- | --- | --- |
| iPhone with a VoIP token | PushKit push, even when connected (CallKit ignores a call it already shows) | the app shows "Missed call" itself |
| iPhone without one (older builds) | alert "Incoming call", only when not connected | alert "Missed call", same `apns-collapse-id` |
| Browser | Web Push `call`/`video_call`, only when not connected | Web Push `missed_call`, same tag |

A PushKit push carries the same `shroud` object as an alert: `{v, k: "call" | "video_call",
call, p: caller, e: sealed caller name}` (`NotificationPayload.swift`), expires when the ringing
does, and must be reported to CallKit before the handler returns. The app then connects, checks
the call still rings (`GET /calls/{id}`), and ends the CallKit call at once if not. Per-device
"notifications off" also stops call pushes; a muted chat does not.

## TURN

coturn runs in the host's network namespace (it needs thousands of relay ports) behind the
Compose profile `calls`. It accepts only logins the API minted: `--use-auth-secret` with
`TURN_SECRET`, username `<expiry>:<user id>`, password `base64(HMAC-SHA1(TURN_SECRET,
username))`, valid for `TURN_CREDENTIAL_TTL_SECS` (12 h). It refuses to relay to loopback,
private, link-local, CGNAT, multicast and other reserved ranges (so a TURN login cannot reach
Postgres, Redis or Nebular on the Docker networks), and offers no TCP relay (RFC 6062): WebRTC
relays UDP, and reaches the TURN server over TCP where UDP is blocked.
