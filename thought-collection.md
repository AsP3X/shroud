# Shroud — architecture thoughts

Working notes on server capabilities, voice/video calls, WebRTC, and deployment.

---

## Core requirements

- The Rust server must support **live text messages**, **voice messages**, and **live calls** (voice first, **video later**).
- Voice and video calls share the same signaling and WebRTC stack; video is a phased capability, not a separate server product.
- The entire stack runs via **Docker Compose** (primary) or can be set up natively for local dev.
- The Rust server must be built with **horizontal scaling** in mind from the start.

---

## Real-time media: three feature types

| Feature | What the server does | Build from scratch? |
| --- | --- | --- |
| **Voice messages** | Store-and-forward encrypted audio blobs (same as text ciphertext relay) | **Yes** — extend the existing message relay + media upload |
| **Live voice calls** | Signaling + NAT traversal; audio stays on devices (or encrypted through TURN) | **Partially** — signaling yes, media stack no |
| **Live video calls** | Same as voice calls — signaling + TURN; adds video track(s) via WebRTC | **Partially** — same signaling path; no custom video stack |

Voice message transcription defaults to **on-device Whisper**; optional **opt-in server transcription** for users who want it (see below). The server never stores audio or transcript plaintext.

**Call modality:** design signaling and call state for **voice + video from the start** (SDP already carries both tracks). Ship **voice-only UI first**, then enable video as a client-side upgrade without new server endpoints.

---

## Voice messages — encryption & transcription

Voice memos are encrypted media messages. Both **sender and recipient** can see a transcript; plaintext audio and transcript never persist on the server.

### Policy

| Tier | Default? | Description |
| --- | --- | --- |
| **Tier 1 — On-device** | **Yes (default)** | Whisper runs on the iOS device after record (sender) or after decrypt (recipient). Full E2E — server never sees audio or transcript plaintext. |
| **Tier 2 — Server assist** | **Opt-in only** | User enables in Settings. Plaintext audio is streamed to a transcription worker, processed in memory, then **immediately destroyed**. Only an **E2E-encrypted transcript blob** is stored in Postgres. |

Tier 2 is a **different trust model** — plaintext exists briefly on the server during Whisper inference. UI must disclose this clearly. Tier 1 remains the default and the core E2E promise.

### Encrypted payload

| Payload | Encrypted? | On server? |
| --- | --- | --- |
| Audio bytes (Opus) | Yes | Ciphertext blob only |
| Transcript text | Yes (message key) | Optional ciphertext cache only |
| Duration, codec, message ID | Envelope metadata | Yes (minimal, non-sensitive) |
| Waveform | Local or encrypted | Never plaintext |

### Tier 1 — On-device (default)

```text
SENDER                              SERVER                         RECIPIENT
──────                              ──────                         ─────────
Record → Whisper → transcript       store ciphertext audio         decrypt audio
Encrypt(audio + optional             + optional encrypted           → Whisper OR
  encrypted transcript)               transcript cache)              fetch encrypted cache
Upload ───────────────────────────► relay ────────────────────────► decrypt → show
Local cache: audio + transcript                                    local cache
```

- **iOS:** on-device **Whisper** via whisper.cpp / Core ML (not Apple Speech — Whisper gives better accuracy).
- **Model delivery:** ship `small` or `base` in-app; optional download of `medium` in Settings (quality vs battery).
- **Sender:** transcribe after record; cache transcript locally; optionally attach encrypted transcript to envelope or upload via transcript API.
- **Recipient:** on expand (or auto-transcribe setting), run local Whisper; or fetch encrypted transcript from server if already cached (from sender upload or prior device).
- **Server:** media upload/download + transcript blob storage — **no transcription endpoints invoked**.

### Tier 2 — Server assist (opt-in)

```text
CLIENT (has plaintext)           TRANSCRIPTION WORKER              POSTGRES
────────────────────             ────────────────────              ────────
User enabled server transcription
     │
POST stream audio ──TLS──►  Whisper in RAM (tmpfs, no disk)
(one-time job token)              │
                                  ├─► encrypt transcript (key from client)
                                  ├─► wipe audio buffers / temp files
                                  └─► store ciphertext only ──────► message_transcripts
```

**Triggers:**

- **Send-time (preferred):** sender has plaintext after recording; if opt-in enabled, stream to worker after uploading encrypted audio.
- **Recipient on-demand:** recipient decrypts locally to play; if opt-in enabled and user taps “Transcribe on server”, stream plaintext to worker (client must provide audio — server cannot decrypt stored ciphertext without keys).

**Worker hardening:**

- Isolated `transcription-worker` service in Compose (separate from API).
- Memory/tmpfs only — no durable audio storage.
- Short-lived single-use job tokens; rate limits; metadata-only logs (never transcript text).
- Horizontal scale: job queue via Redis; workers stateless.

**UI copy (Tier 2):** *“Audio is sent to our server for transcription and is not stored. Only an encrypted transcript is saved. For maximum privacy, keep on-device transcription enabled.”*

### Transcript sync (both tiers)

Recipients (and sender’s other devices) can load a transcript without re-running Whisper when a ciphertext cache exists:

```text
message_transcripts
  message_id           UUID PK/FK
  transcript_ct        BYTEA NOT NULL    -- E2E ciphertext only
  language             TEXT NULL
  source               ENUM('local_upload', 'server_assist')
  created_at           TIMESTAMPTZ
```

**API sketch:**

- `PUT /api/v1/messages/:id/transcript` — client uploads encrypted transcript (Tier 1 cache or after Tier 2).
- `GET /api/v1/messages/:id/transcript` — authorized recipient fetches ciphertext.
- `POST /api/v1/messages/:id/transcribe` — opt-in server assist job only (Tier 2).

No endpoint returns plaintext transcript or stores plaintext audio.

### Compose addition (Tier 2)

Add `transcription-worker` alongside API replicas when server assist ships. Tier 1 requires no extra services beyond media storage.

---

## Voice & video calls: what “from the ground up” means

**Build in Rust (on the existing Axum server):**

1. **Signaling API** — ring, accept, reject, busy, hang up; relay SDP offers/answers and ICE candidates between peers. Support **audio-only and audio+video** SDP from day one (`call_type` or inferred from SDP).
2. **Call state** — minimal metadata in Postgres (call ID, participants, timestamps, status, modality: voice / video). No media payloads, no keys.
3. **Push + CallKit** — VoIP push for incoming calls (APNs); video calls use the same VoIP push path on iOS.
4. **Auth / rate limits** — same session tokens and abuse controls as key-bundle fetch.

**Do not build from scratch:**

- ICE / STUN / TURN protocol stack
- RTP / RTCP, DTLS, SRTP
- Opus (audio) or VP8/VP9/H.264 (video) codecs, jitter buffers, congestion control, simulcast
- SFU / MCU for group voice or group video calls

That is years of specialized engineering. Signal moved **to WebRTC** after years on a custom stack. WhatsApp and most modern messengers use WebRTC on the client and custom signaling on the server.

**Refined principle:** the Rust server owns **call signaling and state**; clients use **WebRTC for voice and video media**; **coturn** handles relay when P2P fails; voice **messages** use the existing ciphertext relay.

---

## Recommended call architecture

```text
┌──────────────┐   encrypted WebRTC media (DTLS-SRTP)   ┌──────────────┐
│   iOS app    │ ◄────────────────────────────────────► │   iOS app    │
└──────┬───────┘                                         └──────────────┘
       │ signaling (SDP, ICE candidates, ring/hangup)
       ▼
┌──────────────┐         optional TURN relay            ┌──────────────┐
│ Rust server  │ ─────────────────────────────────────► │ coturn/TURN  │
│ (signaling)  │   (relays encrypted packets, no decrypt)│ (off-shelf)  │
└──────────────┘                                         └──────────────┘
```

### Phase 1 — 1:1 voice calls

- **iOS:** WebRTC + CallKit; call keys via existing E2E session (Signal-style call setup); audio track only in UI.
- **Server:** `/api/v1/calls/*` routes on Axum — pure signaling relay (video-capable SDP, voice-only clients).
- **TURN:** Deploy **coturn**. Relays encrypted media when P2P fails; cannot decrypt DTLS-SRTP.
- **No SFU** for 1:1 — media goes peer-to-peer when possible.

### Phase 2 — 1:1 video calls

- **iOS:** add camera capture + video track to existing WebRTC peer connection; in-call toggle (voice → video upgrade via renegotiation).
- **Server:** no new endpoints — same signaling relay; optional `modality` field on call records for analytics/metadata.
- **TURN:** same coturn — video increases bandwidth on relay paths; plan TURN capacity accordingly.
- **E2E:** same call-key / insertable-stream layer covers video frames; verify UI shows E2E badge on video calls (per design).

### Phase 3 — group voice & video calls (later)

- Add an SFU (LiveKit, mediasoup, Janus, etc.) or a managed service.
- E2E group calls (voice or video) need insertable streams / frame encryption — much harder than 1:1.
- Video group calls especially need simulcast/SVC and bandwidth adaptation — SFU is effectively required.
- Only worth it once 1:1 voice and video are stable and there is real group-call demand.

---

## WebRTC — pros and cons

### Pros

- **Battle-tested media stack** — ICE, NAT traversal, DTLS-SRTP, Opus (voice), VP8/VP9/H.264 (video), adaptive bitrate, jitter handling. Used by Signal, WhatsApp, Google Meet, Discord (partially).
- **Fits E2E security model** — media encrypted in transit via DTLS-SRTP; app injects call keys so server/TURN cannot decrypt.
- **1:1 can be peer-to-peer** — server bandwidth near zero for media; TURN is fallback only.
- **Native iOS path** — WebRTC.framework + CallKit for lock-screen calls, Bluetooth, CarPlay.
- **Incremental rollout** — 1:1 voice → 1:1 video → group calls; same WebRTC stack throughout; plug in SFU for groups later.
- **Tooling ecosystem** — STUN/TURN servers, debug tools, SFU options.

### Cons

- **Heavy dependency** — large binary on iOS; low-level, callback-heavy API; needs a solid Swift service layer.
- **E2E is not free** — default WebRTC does not replace Signal Protocol for calls; key rotation, renegotiation, and verification UI are our work.
- **Signaling is ours** — WebRTC has no call setup, presence, push, or CallKit; we build `/api/v1/calls/*` and APNs VoIP push.
- **Operational extras** — production almost always needs TURN (mobile NAT); separate service to deploy, monitor, and pay for (bandwidth scales with relayed calls; **video multiplies TURN cost**).
- **Group calls get hard fast** — 3+ participants need an SFU; E2E group calls are significantly more complex; group **video** is an order of magnitude harder than group voice.
- **Platform quirks** — iOS background audio, Bluetooth routes, Wi‑Fi ↔ cellular handoff, CallKit edge cases; video adds camera permissions, PiP, front/back switch, and thermal/battery pressure.
- **Less stack control** — codec and congestion choices largely fixed unless we fork.
- **Metadata leakage** — server still sees who called whom, when, duration, IPs (signaling + TURN). Acceptable for most messengers; not zero metadata.

### WebRTC vs custom media server

| | WebRTC | Custom media server |
| --- | --- | --- |
| Time to 1:1 voice / video | Months | Years |
| E2E alignment | Good (with our key layer) | Same key work, plus all media work |
| Server role | Signaling + TURN only | Everything |
| Group calls later | Plug in SFU | Build SFU too |
| Team fit | Matches small team + Rust API focus | Needs dedicated RTC engineers |

---

## Docker Compose stack (primary deployment)

```text
                    ┌─────────────────────────────────────────┐
                    │  reverse proxy (Traefik / nginx)          │
                    │  :443 / :8080  +  health checks         │
                    └───────────────┬─────────────────────────┘
                                    │
              ┌─────────────────────┼─────────────────────┐
              ▼                     ▼                     ▼
      ┌───────────────┐     ┌───────────────┐     ┌───────────────┐
      │ shroud-server │     │ shroud-server │     │ shroud-server │  ← scale N
      │   (Axum)      │     │   (Axum)      │     │   (Axum)      │
      └───────┬───────┘     └───────┬───────┘     └───────┬───────┘
              │                     │                     │
              └─────────────────────┼─────────────────────┘
                                    │
         ┌──────────────────────────┼──────────────────────────┐
         ▼                          ▼                          ▼
  ┌─────────────┐           ┌─────────────┐           ┌─────────────┐
  │  Postgres   │           │    Redis    │           │   coturn    │
  │  (state)    │           │  pub/sub +  │           │  (TURN only)│
  │             │           │ rate limits │           │  UDP ports  │
  └─────────────┘           └─────────────┘           └─────────────┘
```

| Service | Role | Scales horizontally? |
| --- | --- | --- |
| **shroud-server** | HTTP `/api/v1`, signaling, message relay | **Yes** — stateless where possible |
| **transcription-worker** | Opt-in Whisper jobs (Tier 2 only); no audio persistence | **Yes** — queue-driven workers |
| **Postgres** | Users, sessions, envelopes, call metadata, encrypted transcript cache | **No** (single primary; read replicas later) |
| **Redis** | Cross-instance pub/sub, rate limits, WS fan-out | **Yes** (Cluster/Sentinel in prod) |
| **coturn** | TURN relay for WebRTC when P2P fails | **Yes** — multiple nodes + shared secret |
| **Reverse proxy** | TLS termination, load balance, WS upgrade | Usually 2+ for HA |

Native dev: run Postgres/Redis/coturn in Compose, `cargo run` the API on the host against `localhost`.

Voice/video calls in Compose:

- **Signaling** lives in `shroud-server` (HTTP + WebSocket) — scales with API replicas + Redis.
- **coturn** is a separate container — UDP ports (`3478`, relay range e.g. `49152–65535`); document UDP mapping (Compose UDP is fiddly).
- **Media does not flow through the Rust API** — only P2P or coturn. Scaling API replicas does not increase call bandwidth.

---

## Horizontal scaling — Rust server rules

### 1. Keep HTTP handlers stateless

- Auth via opaque tokens validated against Postgres (or short-TTL JWT + refresh) — any replica can verify.
- No assumption that “this user is on instance N” without a shared bus.

### 2. Real-time paths need Redis (or equivalent)

Message delivery and call signaling (ICE candidates, ring/accept) will use WebSockets. With N replicas, both peers may not hit the same instance.

**Pattern:** each instance subscribes to Redis channels keyed by `user_id` / `device_id`. Instance A publishes; whichever instance holds the peer’s WebSocket delivers.

```text
Client A ──WS──► API-1 ──publish──► Redis ──subscribe──► API-3 ──WS──► Client B
```

Design this before shipping WebSockets.

### 3. Migrations — not on every replica

Run migrations as a one-shot Compose service or job (`RUN_MIGRATIONS=true` on a single container). Multiple concurrent migrators can race and fail.

### 4. Connection pool sizing

`pool_per_instance ≈ floor(postgres_max_connections × 0.7 / replica_count)`

Expose `DATABASE_POOL_MAX` as env from day one (today hardcoded to 10 per instance).

### 5. Idempotency

- Message insert: idempotency keys (`client_message_id`) so retries don’t duplicate.
- Call signaling: events idempotent (duplicate ICE candidate is harmless).
- Postgres = durable truth; Redis = delivery fan-out, not durable storage.

### 6. Health checks

- **Liveness:** process up.
- **Readiness:** DB + Redis reachable.
- Proxy drains on scale-down (graceful SIGTERM shutdown).

---

## Build now vs defer

| Bake in now | Defer until needed |
| --- | --- |
| Stateless handlers, env-based config | Postgres read replicas |
| `REDIS_URL`, `DATABASE_POOL_MAX` in config | Redis Cluster |
| Migration job separation | Kubernetes / Swarm |
| Idempotency keys on writes | SFU for group voice/video |
| Video-capable signaling schema (modality in SDP) | 1:1 video UI & camera pipeline |
| Graceful shutdown | coturn autoscaling |
| Redis pub/sub for WS fan-out | `transcription-worker` + Tier 2 opt-in UI |
| Encrypted transcript cache schema + API | Multi-region |
| On-device Whisper integration (Tier 1 default) | |

---

## Summary

- **Voice messages** → encrypted audio relay; **default on-device Whisper** transcription; optional **opt-in server transcription** (ephemeral audio, encrypted transcript cache only).
- **Live voice calls** → WebRTC on iOS; signaling + call state in Rust; coturn in Compose; Redis for multi-instance WebSocket fan-out.
- **Live video calls (later)** → same stack as voice; add camera/video track on iOS; same signaling + coturn; higher TURN bandwidth; no separate video server.
- **Horizontal scaling** applies to the API/signaling layer, not to inventing a custom WebRTC/media stack.
- **Main bet:** Rust API = stateless metadata + signaling hub; Postgres = durable state; Redis = cross-replica real-time; coturn = encrypted media relay when P2P fails.
