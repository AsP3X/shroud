/**
 * Whole calls between controllers: a fake server with the semantics of docs/calls.md (statuses,
 * who gets which event, signals only to the other device in the call), fake peer connections
 * that "connect" once offer, answer and candidates have crossed, fake media, and a hand-driven
 * clock. Every signal on the wire is opened here too, to check its plaintext.
 * Run: npx esbuild src/calls/controller.selftest.ts --bundle --platform=node --format=esm | node --input-type=module
 */
import { x25519 } from "@noble/curves/ed25519.js";
import { ApiError, type CallInfo, type CallModality, type CallSignalType } from "../api/client";
import type { RealtimeEvent } from "../realtime";
import { CallController, type CallApi, type CallEnv } from "./controller";
import { callKeys, deriveCallSecret, openSignal, sealSignal } from "./crypto";
import { CAMERA_UNAVAILABLE, type CallPeer, type CallView } from "./logic";

function check(ok: boolean, what: string): void {
  if (!ok) throw new Error(`calls controller selftest: ${what}`);
}

/** Lets promises, WebCrypto and the fake server's deliveries run. */
async function settle(): Promise<void> {
  for (let i = 0; i < 12; i += 1) await new Promise((resolve) => setTimeout(resolve, 1));
}

/* --- a clock the test moves ------------------------------------------------------------------ */

class Clock {
  now = 1_700_000_000_000;
  private next = 1;
  private readonly timers = new Map<number, { at: number; run: () => void; every: number | null }>();

  set(run: () => void, ms: number, every: number | null): number {
    const id = this.next++;
    this.timers.set(id, { at: this.now + Math.max(0, ms), run, every });
    return id;
  }

  clear(id: number): void {
    this.timers.delete(id);
  }

  /** Moves time on by `ms`, running what comes due in order and settling between. */
  async advance(ms: number): Promise<void> {
    const end = this.now + ms;
    for (;;) {
      await settle();
      let due: [number, { at: number; run: () => void; every: number | null }] | null = null;
      for (const entry of this.timers) if (entry[1].at <= end && (!due || entry[1].at < due[1].at)) due = entry;
      if (!due) break;
      const [id, timer] = due;
      this.now = Math.max(this.now, timer.at);
      if (timer.every !== null) timer.at += timer.every;
      else this.timers.delete(id);
      timer.run();
    }
    this.now = end;
    await settle();
  }
}

/* --- fake media and peer connections --------------------------------------------------------- */

let trackIds = 0;

class FakeTrack {
  enabled = true;
  readyState: "live" | "ended" = "live";
  readonly id = `track-${++trackIds}`;
  constructor(
    readonly kind: "audio" | "video",
    private readonly facing: string | undefined = undefined,
    private readonly deviceId = "cam-front",
  ) {}
  stop(): void {
    this.readyState = "ended";
  }
  getSettings(): MediaTrackSettings {
    return this.kind === "video" ? { facingMode: this.facing, deviceId: this.deviceId } : {};
  }
}

class FakeStream {
  private readonly tracks: FakeTrack[];
  constructor(tracks: FakeTrack[] = []) {
    this.tracks = [...tracks];
  }
  getTracks(): FakeTrack[] {
    return [...this.tracks];
  }
  getAudioTracks(): FakeTrack[] {
    return this.tracks.filter((t) => t.kind === "audio");
  }
  getVideoTracks(): FakeTrack[] {
    return this.tracks.filter((t) => t.kind === "video");
  }
  addTrack(track: FakeTrack): void {
    if (!this.tracks.includes(track)) this.tracks.push(track);
  }
}

type Description = { type: string; sdp: string };

class FakePeer {
  localDescription: Description | null = null;
  remoteDescription: Description | null = null;
  signalingState = "stable";
  connectionState = "new";
  iceConnectionState = "new";
  onicecandidate: ((event: { candidate: unknown }) => void) | null = null;
  ontrack: ((event: { track: FakeTrack; streams: FakeStream[] }) => void) | null = null;
  onconnectionstatechange: (() => void) | null = null;
  oniceconnectionstatechange: (() => void) | null = null;
  readonly senders: { track: FakeTrack | null; replaceTrack: (track: FakeTrack | null) => Promise<void> }[] = [];
  readonly transceivers: string[] = [];
  readonly remoteCandidates: unknown[] = [];
  readonly remoteTracks: FakeTrack[] = [];
  readonly descriptionsSet: string[] = [];
  closed = false;
  /** The network refuses every path (for timeouts). */
  blocked = false;
  private generation = 0;

  constructor(
    readonly name: string,
    readonly config: RTCConfiguration,
  ) {}

  addTrack(track: FakeTrack): unknown {
    const sender = {
      track: track as FakeTrack | null,
      replaceTrack: async (next: FakeTrack | null) => {
        sender.track = next;
      },
    };
    this.senders.push(sender);
    return sender;
  }
  addTransceiver(kind: string, init: { direction: string }): void {
    this.transceivers.push(`${kind}:${init.direction}`);
  }
  getSenders(): unknown[] {
    return this.senders;
  }
  private describe(type: string): string {
    const kinds = [...this.senders.flatMap((s) => (s.track ? [s.track.kind] : []))];
    return `v=0\r\nfake-${type} peer=${this.name} gen=${this.generation} sends=${kinds.join(",")}\r\n`;
  }
  async createOffer(options?: { iceRestart?: boolean }): Promise<Description> {
    if (options?.iceRestart) this.generation += 1;
    return { type: "offer", sdp: this.describe("offer") };
  }
  async createAnswer(): Promise<Description> {
    const remoteGeneration = /gen=(\d+)/.exec(this.remoteDescription?.sdp ?? "")?.[1];
    this.generation = Number(remoteGeneration ?? this.generation);
    return { type: "answer", sdp: this.describe("answer") };
  }
  async setLocalDescription(description: Description): Promise<void> {
    if (description.type === "rollback") {
      this.localDescription = null;
      this.signalingState = "stable";
      this.descriptionsSet.push("local:rollback");
      return;
    }
    this.localDescription = description;
    this.descriptionsSet.push(`local:${description.type}`);
    this.signalingState = description.type === "offer" ? "have-local-offer" : "stable";
    const generation = this.generation;
    setTimeout(() => {
      if (this.closed) return;
      for (const n of [1, 2, 3]) {
        this.onicecandidate?.({
          candidate: {
            candidate: `candidate:${this.name}-${generation}-${n} 1 udp 2122260223 10.0.0.${n} 5000 typ host`,
            sdpMid: "0",
            sdpMLineIndex: 0,
          },
        });
      }
      this.onicecandidate?.({ candidate: null });
    }, 0);
    this.maybeConnect();
  }
  async setRemoteDescription(description: Description): Promise<void> {
    if (description.type === "answer" && this.signalingState !== "have-local-offer") {
      throw Object.assign(new Error("answer in the wrong state"), { name: "InvalidStateError" });
    }
    this.remoteDescription = description;
    this.descriptionsSet.push(`remote:${description.type}`);
    this.signalingState = description.type === "offer" ? "have-remote-offer" : "stable";
    // A restart needs fresh candidates before it connects again.
    this.remoteCandidates.length = 0;
    const kinds = /sends=([a-z,]*)/.exec(description.sdp)?.[1]?.split(",").filter(Boolean) ?? [];
    for (const kind of kinds) {
      if (this.remoteTracks.some((t) => t.kind === kind)) continue;
      const track = new FakeTrack(kind as "audio" | "video");
      this.remoteTracks.push(track);
      this.ontrack?.({ track, streams: [] });
    }
    this.maybeConnect();
  }
  async addIceCandidate(candidate: unknown): Promise<void> {
    if (!this.remoteDescription) throw Object.assign(new Error("no remote description"), { name: "InvalidStateError" });
    this.remoteCandidates.push(candidate);
    this.maybeConnect();
  }
  private maybeConnect(): void {
    if (this.closed || this.blocked || this.signalingState !== "stable") return;
    if (!this.localDescription || !this.remoteDescription || this.remoteCandidates.length === 0) return;
    if (this.connectionState === "connected") return;
    setTimeout(() => {
      if (!this.closed && !this.blocked) this.setState("connected");
    }, 0);
  }
  setState(state: string): void {
    this.connectionState = state;
    this.iceConnectionState = state;
    this.onconnectionstatechange?.();
    this.oniceconnectionstatechange?.();
  }
  close(): void {
    this.closed = true;
    this.signalingState = "closed";
    this.connectionState = "closed";
  }
}

/* --- a fake server --------------------------------------------------------------------------- */

type WireSignal = { callId: string; from: string; to: string; type: CallSignalType; payload: string };

class Device {
  readonly controller: CallController;
  view: CallView | null = null;
  readonly views: (CallView | null)[] = [];
  readonly peers: FakePeer[] = [];
  readonly streams: FakeStream[] = [];
  readonly rings: ({ peer: CallPeer; modality: CallModality; missed: boolean } | null)[] = [];
  readonly toldTabs: string[] = [];
  tone: string | null = null;
  audio: unknown = null;
  awake = false;
  holds = 0;
  interrupted = 0;
  connected = true;
  denyMic = false;
  denyCamera = false;
  /** Peer connections made from now on never connect. */
  blockPeers = false;
  cameras = ["cam-front", "cam-back"];

  constructor(
    readonly user: User,
    readonly id: string,
    readonly server: Server,
    readonly clock: Clock,
  ) {
    this.controller = new CallController(this.env());
    this.controller.configure({
      userId: user.id,
      deviceId: id,
      api: server.api(this),
      identity: () => ({ privateKey: user.privateKey.slice(), publicKey: user.publicKey.slice() }),
      peerKey: async (userId) => server.user(userId).publicKey,
      peerName: (userId) => server.user(userId).name,
    });
    server.devices.push(this);
  }

  private env(): CallEnv {
    const clock = this.clock;
    return {
      unsupported: () => null,
      getUserMedia: async (constraints) => {
        if (constraints.audio && this.denyMic) throw { name: "NotAllowedError", message: "denied" };
        if (constraints.video && this.denyCamera) throw { name: "NotAllowedError", message: "denied" };
        const tracks: FakeTrack[] = [];
        if (constraints.audio) tracks.push(new FakeTrack("audio"));
        if (constraints.video) {
          const wanted = (constraints.video as MediaTrackConstraints).facingMode;
          const facing =
            typeof wanted === "object" && wanted && "exact" in wanted ? String(wanted.exact) : "user";
          tracks.push(new FakeTrack("video", facing, facing === "user" ? "cam-front" : "cam-back"));
        }
        const stream = new FakeStream(tracks);
        this.streams.push(stream);
        return stream as unknown as MediaStream;
      },
      cameras: async () => this.cameras,
      createPeer: (config) => {
        const peer = new FakePeer(this.id, config);
        peer.blocked = this.blockPeers;
        this.peers.push(peer);
        return peer as unknown as RTCPeerConnection;
      },
      createStream: (tracks) => new FakeStream(tracks as unknown as FakeTrack[]) as unknown as MediaStream,
      now: () => clock.now,
      setTimeout: (run, ms) => clock.set(run, ms, null),
      clearTimeout: (id) => clock.clear(id),
      setInterval: (run, ms) => clock.set(run, ms, ms),
      clearInterval: (id) => clock.clear(id),
      publish: (view) => {
        this.view = view;
        this.views.push(view);
      },
      tone: (kind) => {
        this.tone = kind;
      },
      playAudio: async (stream) => {
        this.audio = stream;
        return true;
      },
      keepAwake: (on) => {
        this.awake = on;
      },
      holdAutoLock: () => {
        this.holds += 1;
        let released = false;
        return () => {
          if (released) return;
          released = true;
          this.holds -= 1;
        };
      },
      interruptVoice: () => {
        this.interrupted += 1;
      },
      notifyRing: (ring) => {
        this.rings.push(ring);
      },
      tellTabs: (callId) => {
        this.toldTabs.push(callId);
      },
    };
  }

  get peer(): FakePeer {
    const peer = this.peers[this.peers.length - 1];
    check(Boolean(peer), `${this.id} has a peer connection`);
    return peer;
  }
}

type User = { id: string; name: string; privateKey: Uint8Array; publicKey: Uint8Array };

class Server {
  readonly calls = new Map<string, CallInfo>();
  readonly devices: Device[] = [];
  readonly users: User[] = [];
  readonly signals: WireSignal[] = [];
  /** Every signal request, refused ones included. */
  readonly attempts: string[] = [];
  readonly requests: string[] = [];
  deliverTwice = false;
  /** Holds `POST /calls`'s response back (the ring is out already), until released. */
  holdCreate: Promise<void> | null = null;

  addUser(name: string): User {
    const privateKey = x25519.utils.randomSecretKey();
    const user = { id: crypto.randomUUID(), name, privateKey, publicKey: x25519.getPublicKey(privateKey) };
    this.users.push(user);
    return user;
  }

  user(id: string): User {
    const user = this.users.find((u) => u.id.toLowerCase() === id.toLowerCase());
    if (!user) throw new ApiError("NOT_FOUND", "no such user", 404);
    return user;
  }

  /** Delivers an event to a device, after the current turn (twice when asked to). */
  deliver(device: Device, event: RealtimeEvent): void {
    if (!device.connected) return;
    const copies = this.deliverTwice ? 2 : 1;
    for (let i = 0; i < copies; i += 1) {
      setTimeout(() => {
        if (device.connected) device.controller.handle(JSON.parse(JSON.stringify(event)) as RealtimeEvent);
      }, 0);
    }
  }

  publish(userIds: string[], except: string | null, event: RealtimeEvent): void {
    for (const device of this.devices) {
      if (userIds.includes(device.user.id) && device.id !== except) this.deliver(device, event);
    }
  }

  private live(userId: string): boolean {
    return [...this.calls.values()].some(
      (c) => (c.status === "ringing" || c.status === "active") && (c.caller_user_id === userId || c.callee_user_id === userId),
    );
  }

  private get(id: string, device: Device): CallInfo {
    const call = this.calls.get(id.toLowerCase());
    if (!call || (call.caller_user_id !== device.user.id && call.callee_user_id !== device.user.id)) {
      throw new ApiError("NOT_FOUND", "Call not found.", 404);
    }
    return call;
  }

  private copy(call: CallInfo): CallInfo {
    return { ...call };
  }

  /** Ends a call the way the server does, telling everyone but `except`. */
  end(call: CallInfo, status: CallInfo["status"], reason: string, except: string | null): void {
    call.status = status;
    call.ended_reason = reason;
    call.ended_at = new Date().toISOString();
    this.publish([call.caller_user_id, call.callee_user_id], except, { type: "call.ended", raw: { type: "call.ended", call: this.copy(call) } });
  }

  api(device: Device): CallApi {
    const log = (what: string) => this.requests.push(`${device.id} ${what}`);
    return {
      iceServers: async () => {
        log("ice-servers");
        return [{ urls: "turn:turn.test:3478", username: "1:u", credential: "c" }];
      },
      createCall: async (peerUserId, modality) => {
        log("create");
        const peer = this.user(peerUserId);
        if (this.live(device.user.id)) throw new ApiError("CALL_IN_PROGRESS", "You are in a call.", 409);
        if (this.live(peer.id)) throw new ApiError("CALL_BUSY", "They are in a call.", 409);
        const call: CallInfo = {
          id: crypto.randomUUID(),
          caller_user_id: device.user.id,
          caller_device_id: device.id,
          caller_username: device.user.name,
          callee_user_id: peer.id,
          callee_username: peer.name,
          modality,
          status: "ringing",
          protocol: 2,
          created_at: new Date().toISOString(),
        };
        this.calls.set(call.id, call);
        this.publish([device.user.id, peer.id], device.id, { type: "call.ring", raw: { type: "call.ring", call: this.copy(call) } });
        if (this.holdCreate) await this.holdCreate;
        return this.copy(call);
      },
      getCall: async (callId) => {
        log("get");
        return this.copy(this.get(callId, device));
      },
      acceptCall: async (callId) => {
        log("accept");
        const call = this.get(callId, device);
        if (call.callee_user_id !== device.user.id) throw new ApiError("FORBIDDEN", "Not yours.", 403);
        if (call.status !== "ringing") throw new ApiError("VALIDATION_ERROR", "Call is no longer ringing.", 400);
        call.status = "active";
        call.callee_device_id = device.id;
        call.answered_at = new Date().toISOString();
        this.publish([call.caller_user_id, call.callee_user_id], device.id, {
          type: "call.accepted",
          raw: { type: "call.accepted", call: this.copy(call) },
        });
        return this.copy(call);
      },
      rejectCall: async (callId) => {
        log("reject");
        const call = this.get(callId, device);
        if (call.status === "ringing" && call.callee_user_id === device.user.id) {
          this.end(call, "rejected", "rejected", device.id);
        }
        return this.copy(call);
      },
      hangupCall: async (callId, keepalive) => {
        log(keepalive ? "hangup keepalive" : "hangup");
        const call = this.get(callId, device);
        if (call.status === "ringing") {
          if (call.caller_user_id === device.user.id) this.end(call, "cancelled", "cancelled", device.id);
          else this.end(call, "missed", "declined", device.id);
        } else if (call.status === "active") {
          this.end(call, "ended", "hangup", device.id);
        }
        return this.copy(call);
      },
      sendSignal: async (callId, signalType, payload) => {
        this.attempts.push(`${device.id} ${signalType}`);
        const call = this.get(callId, device);
        if (call.status === "ringing") throw new ApiError("CALL_NOT_ANSWERED", "Not answered.", 409);
        if (call.status !== "active") throw new ApiError("CALL_ENDED", "Call ended.", 409);
        const other =
          device.id === call.caller_device_id
            ? call.callee_device_id
            : device.id === call.callee_device_id
              ? call.caller_device_id
              : null;
        if (!other) throw new ApiError("FORBIDDEN", "Not in this call.", 403);
        this.signals.push({ callId, from: device.id, to: other, type: signalType, payload });
        const target = this.devices.find((d) => d.id === other);
        if (target) {
          this.deliver(target, {
            type: "call.signal",
            raw: {
              type: "call.signal",
              call_id: call.id,
              from_user_id: device.user.id,
              from_device_id: device.id,
              signal_type: signalType,
              payload,
            },
          });
        }
      },
      heartbeat: async (callId) => {
        log("heartbeat");
        return this.copy(this.get(callId, device));
      },
    };
  }
}

/* --- helpers --------------------------------------------------------------------------------- */

function world() {
  const clock = new Clock();
  const server = new Server();
  const alice = server.addUser("alice");
  const bob = server.addUser("bob");
  const carol = server.addUser("carol");
  return { clock, server, alice, bob, carol };
}

function phase(device: Device): string {
  return device.view?.phase ?? "idle";
}

/** Opens every signal one device sent in a call, in order. */
async function plaintexts(server: Server, from: Device, to: Device, role: "caller" | "callee") {
  const out: Record<string, unknown>[] = [];
  for (const signal of server.signals.filter((s) => s.from === from.id)) {
    const secret = deriveCallSecret(to.user.privateKey, to.user.publicKey, from.user.publicKey);
    const keys = await callKeys(secret, signal.callId, role === "caller" ? "callee" : "caller");
    out.push({ type: signal.type, ...(await openSignal(keys.receive, signal.callId, signal.type, signal.payload)) });
  }
  return out;
}

/** Places a call from `a` and answers it on `b`, up to both sides talking. */
async function connect(clock: Clock, a: Device, b: Device, modality: CallModality = "voice") {
  a.controller.start({ id: b.user.id, username: b.user.name }, modality);
  await clock.advance(0);
  check(phase(b) === "incoming", `${b.id} rings`);
  b.controller.accept();
  await clock.advance(ICE);
  await clock.advance(ICE);
  check(phase(a) === "active" && phase(b) === "active", `${a.id} and ${b.id} are talking (${phase(a)}, ${phase(b)})`);
}

const ICE = 150;

/* --- 1. a voice call, answered on one of two devices, then hung up --------------------------- */
{
  const { clock, server, alice, bob } = world();
  const a1 = new Device(alice, "a1", server, clock);
  const b1 = new Device(bob, "b1", server, clock);
  const b2 = new Device(bob, "b2", server, clock);

  a1.controller.start({ id: bob.id, username: "bob" }, "voice");
  check(a1.view?.phase === "outgoing" && a1.view.dialing, "calling… before the server answers");
  check(a1.holds === 1 && a1.interrupted === 1, "the auto-lock is held and voice notes stop");
  await clock.advance(0);
  check(a1.view?.phase === "outgoing" && !a1.view.dialing && a1.tone === "ringback", "ringing, with the ringback");
  check(server.requests.filter((r) => r === "a1 create").length === 1, "one ring placed");
  check(a1.peer.config.bundlePolicy === "max-bundle", "max-bundle");
  check(a1.peer.config.iceServers?.[0]?.username === "1:u", "the TURN login is passed on");
  check(a1.peer.descriptionsSet.join() === "local:offer", "the offer is ready while it rings");
  await clock.advance(ICE);
  check(server.attempts.length === 0, "nothing is signalled before the answer, candidates included");
  for (const device of [b1, b2]) {
    check(device.view?.phase === "incoming" && device.view.peer.username === "alice", `${device.id} rings`);
    check(device.tone === "ringtone" && device.holds === 1, `${device.id} plays the ringtone and holds the lock`);
    check(device.rings[0]?.missed === false, `${device.id} notifies an unwatched ring`);
  }

  // The caller's heartbeat runs while it rings.
  await clock.advance(10_000);
  check(server.requests.filter((r) => r === "a1 heartbeat").length === 1, "the caller beats while ringing");

  b1.controller.accept();
  check(b1.view?.phase === "connecting" && b1.tone === null, "answering: connecting, the ringtone stops");
  check(b1.toldTabs.length === 1, "this browser's other tabs are told");
  await clock.advance(ICE);
  await clock.advance(ICE);
  check(b2.view?.phase === "ended" && b2.view.endedText === "Answered on another device", "b2 stops ringing");
  check(phase(a1) === "active" && phase(b1) === "active", `both talk (${phase(a1)}, ${phase(b1)})`);
  check(a1.tone === null && a1.awake && b1.awake, "no tones; screens stay awake");
  check((a1.view?.connectedAt ?? 0) > 0 && (b1.view?.connectedAt ?? 0) > 0, "the timers started");
  check(a1.audio !== null && b1.audio !== null, "each plays the other's audio");
  check(b1.peer.transceivers.length === 0 && b1.peer.senders.length === 1, "the callee sends its microphone");

  const fromCaller = await plaintexts(server, a1, b1, "caller");
  const fromCallee = await plaintexts(server, b1, a1, "callee");
  check(fromCaller[0]?.type === "sdp_offer" && fromCaller[0].t === "offer" && fromCaller[0].restart === false, "the offer first");
  check(
    JSON.stringify(Object.keys(fromCaller[0])) === JSON.stringify(["type", "t", "sdp", "restart", "n"]),
    "the offer's fields as docs/calls.md has them",
  );
  check(fromCaller.every((s, i) => s.n === i + 1), "the caller numbers its signals 1, 2, 3…");
  check(fromCallee[0]?.t === "answer" && fromCallee.every((s, i) => s.n === i + 1), "the callee answers first, numbered");
  const callerIce = fromCaller.filter((s) => s.t === "ice");
  check(callerIce.length === 1 && (callerIce[0].cs as unknown[]).length === 3, "candidates travel in one batch");
  check(
    JSON.stringify(Object.keys((callerIce[0].cs as Record<string, unknown>[])[0])) ===
      JSON.stringify(["candidate", "sdpMid", "sdpMLineIndex"]),
    "a candidate's fields",
  );
  check(fromCaller.some((s) => s.t === "media" && s.mic === true && s.camera === false), "the media state goes out");
  check(server.signals.every((s) => s.to !== "b2"), "b2 never gets a signal");

  // Mute: the other side sees it.
  a1.controller.toggleMute();
  check(a1.view?.micOn === false, "muted here");
  check(a1.streams[0].getAudioTracks()[0].enabled === false, "the microphone track is off");
  await clock.advance(0);
  check(b1.view?.remoteMic === false, "b1 sees alice muted");

  await clock.advance(10_000);
  check(server.requests.some((r) => r === "b1 heartbeat"), "the callee beats once in the call");

  await clock.advance(2_000);
  check(b2.view === null && b2.holds === 0, "b2 went back to idle and let the lock go");

  a1.controller.hangup();
  check(a1.view?.phase === "ended" && a1.view.endedText === "Call ended", "hung up: Call ended");
  await clock.advance(0);
  check(b1.view?.phase === "ended" && b1.view.endedText === "Call ended", "b1 hears it: Call ended");
  check(a1.peers[0].closed && b1.peers[0].closed, "peer connections closed");
  check(a1.streams.every((s) => s.getTracks().every((t) => t.readyState === "ended")), "the microphone is released");
  check(!a1.awake && a1.audio === null, "no wake lock, no audio left");
  await clock.advance(2_000);
  check(a1.view === null && b1.view === null && a1.holds === 0 && b1.holds === 0, "both idle, locks free");
  const heartbeatsBefore = server.requests.filter((r) => r.endsWith("heartbeat")).length;
  await clock.advance(30_000);
  check(server.requests.filter((r) => r.endsWith("heartbeat")).length === heartbeatsBefore, "no timers left running");
}

/* --- 2. every event twice (Redis fan-out), and a replayed ring ------------------------------- */
{
  const { clock, server, alice, bob } = world();
  const a1 = new Device(alice, "a1", server, clock);
  const b1 = new Device(bob, "b1", server, clock);
  server.deliverTwice = true;
  a1.controller.start({ id: bob.id, username: "bob" }, "video");
  await clock.advance(0);
  const ringKey = b1.view?.key;
  // The socket reconnects: auth.ok, and the ring again.
  const ring = [...server.calls.values()][0];
  b1.controller.handle({ type: "auth.ok", raw: { type: "auth.ok" } });
  b1.controller.handle({ type: "call.ring", raw: { type: "call.ring", call: { ...ring } } });
  await clock.advance(0);
  check(b1.view?.phase === "incoming" && b1.view.key === ringKey, "a replayed ring is the same ring");
  b1.controller.accept();
  await clock.advance(ICE);
  await clock.advance(ICE);
  check(phase(a1) === "active" && phase(b1) === "active", "a video call connects with every event doubled");
  check(b1.peer.descriptionsSet.join() === "remote:offer,local:answer", "the offer was answered once");
  check(a1.peer.descriptionsSet.join() === "local:offer,remote:answer", "the answer was taken once");
  check(a1.view?.remoteVideo === true && a1.view.remoteCamera === true, "alice sees bob's camera");
  check(a1.view?.hasCamera === true && a1.view.mirrorSelf === true && a1.view.canSwitchCamera, "a mirrored front camera, and a second one");
  b1.controller.hangup();
  await clock.advance(0);
  check(a1.view?.endedText === "Call ended", "ended once");
  await clock.advance(2_000);
  check(a1.view === null && b1.view === null, "both idle");
}

/* --- 3. declined; 4. cancelled while ringing; 5. no answer ------------------------------------ */
{
  const { clock, server, alice, bob } = world();
  const a1 = new Device(alice, "a1", server, clock);
  const b1 = new Device(bob, "b1", server, clock);

  a1.controller.start({ id: bob.id, username: "bob" }, "voice");
  await clock.advance(0);
  b1.controller.decline();
  check(b1.view === null && b1.holds === 0 && b1.tone === null, "declining closes at once (the callee sees nothing)");
  await clock.advance(0);
  check(a1.view?.endedText === "Declined" && a1.tone === null, "the caller reads Declined");
  await clock.advance(2_000);

  a1.controller.start({ id: bob.id, username: "bob" }, "voice");
  await clock.advance(0);
  a1.controller.hangup();
  check(a1.view === null && a1.holds === 0, "cancelling closes at once (the caller sees nothing)");
  await clock.advance(0);
  check(b1.view?.endedText === "Missed call", "the callee reads Missed call");
  check(b1.rings[b1.rings.length - 1]?.missed === true, "and gets a missed-call notification when unwatched");
  await clock.advance(2_000);

  a1.controller.start({ id: bob.id, username: "bob" }, "voice");
  await clock.advance(0);
  const call = [...server.calls.values()].find((c) => c.status === "ringing")!;
  server.end(call, "missed", "timeout", null);
  await clock.advance(0);
  check(a1.view?.endedText === "No answer" && b1.view?.endedText === "Missed call", "the ring ran out");
  await clock.advance(2_000);
  check(a1.holds === 0 && b1.holds === 0, "locks free");
}

/* --- 6. busy; 7. no microphone; 8. no microphone to answer with ------------------------------- */
{
  const { clock, server, alice, bob, carol } = world();
  const a1 = new Device(alice, "a1", server, clock);
  const b1 = new Device(bob, "b1", server, clock);
  const c1 = new Device(carol, "c1", server, clock);
  await connect(clock, b1, c1);
  a1.controller.start({ id: bob.id, username: "bob" }, "voice");
  await clock.advance(0);
  check(a1.view?.endedText === "bob is on another call.", `busy: ${a1.view?.endedText}`);
  check(phase(b1) === "active", "bob's call goes on");
  await clock.advance(4_000);
  check(a1.view === null && a1.holds === 0, "the error closes after a moment");
  c1.controller.hangup();
  await clock.advance(2_000);

  a1.denyMic = true;
  const created = server.calls.size;
  a1.controller.start({ id: bob.id, username: "bob" }, "voice");
  await clock.advance(0);
  check(a1.view?.endedText === "Allow microphone access in your browser to call.", "no microphone, no call");
  check(server.calls.size === created, "nobody was rung");
  await clock.advance(4_000);
  a1.denyMic = false;

  b1.denyMic = true;
  a1.controller.start({ id: bob.id, username: "bob" }, "voice");
  await clock.advance(0);
  b1.controller.accept();
  await clock.advance(0);
  check(b1.view?.phase === "incoming", "back to ringing, to fix the permission");
  check(b1.view?.notice === "Allow microphone access in your browser to answer calls.", "and why");
  check(!server.requests.includes("b1 accept"), "nothing was accepted");
  b1.controller.decline();
  await clock.advance(0);
  check(a1.view?.endedText === "Declined", "then declined");
  await clock.advance(2_000);
}

/* --- 9. a video call without a camera goes on with sound --------------------------------------- */
{
  const { clock, server, alice, bob } = world();
  const a1 = new Device(alice, "a1", server, clock);
  const b1 = new Device(bob, "b1", server, clock);
  a1.denyCamera = true;
  a1.controller.start({ id: bob.id, username: "bob" }, "video");
  await clock.advance(0);
  check(a1.view?.notice === CAMERA_UNAVAILABLE && a1.view.hasCamera === false, "told the camera is unavailable");
  check(a1.peer.transceivers.join() === "video:recvonly", "still receives video");
  b1.controller.accept();
  await clock.advance(ICE);
  await clock.advance(ICE);
  check(phase(a1) === "active" && b1.view?.remoteCamera === false, "bob sees alice's camera is off");
  check(a1.view?.remoteVideo === true && a1.view.remoteCamera === true, "alice sees bob");
  a1.controller.toggleCamera();
  check(a1.view?.cameraOn === false, "no camera to turn on");
  await clock.advance(6_000);
  check(a1.view?.notice === null, "the notice passes");
  // Bob's camera goes off and on.
  b1.controller.toggleCamera();
  await clock.advance(0);
  check(a1.view?.remoteCamera === false, "bob stopped his video");
  b1.controller.toggleCamera();
  await clock.advance(0);
  check(a1.view?.remoteCamera === true, "and started it again");
  // Bob flips his camera.
  await b1.controller.switchCamera();
  check(b1.view?.mirrorSelf === false, "the back camera is not mirrored");
  check(b1.peer.senders.find((s) => s.track?.kind === "video")?.track?.getSettings().facingMode === "environment", "the sender sends the back camera");
  a1.controller.hangup();
  await clock.advance(2_000);
}

/* --- 10. rings to ignore ---------------------------------------------------------------------- */
{
  const { clock, server, alice, bob } = world();
  const a1 = new Device(alice, "a1", server, clock);
  const a2 = new Device(alice, "a2", server, clock);
  const b1 = new Device(bob, "b1", server, clock);
  const old: CallInfo = {
    id: crypto.randomUUID(),
    caller_user_id: alice.id,
    caller_device_id: "a1",
    caller_username: "alice",
    callee_user_id: bob.id,
    callee_username: "bob",
    modality: "voice",
    status: "ringing",
    protocol: 1,
    created_at: new Date().toISOString(),
  };
  b1.controller.handle({ type: "call.ring", raw: { type: "call.ring", call: old } });
  check(b1.view === null, "a protocol-1 ring is ignored");
  b1.controller.handle({ type: "call.ring", raw: { type: "call.ring", call: { ...old, protocol: undefined } } });
  check(b1.view === null, "a ring without a protocol is ignored");
  b1.controller.handle({ type: "call.ring", raw: { type: "call.ring", call: { id: 1 } } });
  check(b1.view === null, "a malformed ring is ignored");
  a1.controller.start({ id: bob.id, username: "bob" }, "voice");
  await clock.advance(0);
  check(a2.view === null, "our other device ignores our own ring");
  check(b1.view?.phase === "incoming", "bob rings");
  // A second ring while this one rings (the server never sends one): ignored.
  const ringing = b1.view;
  b1.controller.handle({ type: "call.ring", raw: { type: "call.ring", call: { ...old, id: crypto.randomUUID(), protocol: 2 } } });
  check(b1.view === ringing && b1.holds === 1, "one ring at a time");
  // Another tab of this browser answered it.
  b1.controller.takenElsewhere(b1.view!.callId!);
  check(b1.view === null && b1.holds === 0, "a ring taken in another tab stops quietly");
  a1.controller.hangup();
  await clock.advance(2_000);
}

/* --- 12. an answer that overtakes the ring's own response -------------------------------------- */
{
  const { clock, server, alice, bob } = world();
  const a1 = new Device(alice, "a1", server, clock);
  const b1 = new Device(bob, "b1", server, clock);
  let release!: () => void;
  server.holdCreate = new Promise<void>((resolve) => {
    release = resolve;
  });
  a1.controller.start({ id: bob.id, username: "bob" }, "voice");
  await clock.advance(0);
  check(a1.view?.dialing === true && b1.view?.phase === "incoming", "b1 rings before alice knows the call id");
  b1.controller.accept();
  await clock.advance(0);
  check(a1.view?.phase === "outgoing", "alice holds the early accept");
  release();
  server.holdCreate = null;
  await clock.advance(ICE);
  await clock.advance(ICE);
  check(phase(a1) === "active" && phase(b1) === "active", "then connects");
  a1.controller.hangup();
  await clock.advance(2_000);
}

/* --- 13. no media within 30 s -------------------------------------------------------------------- */
{
  const { clock, server, alice, bob } = world();
  const a1 = new Device(alice, "a1", server, clock);
  const b1 = new Device(bob, "b1", server, clock);
  a1.blockPeers = true;
  b1.blockPeers = true;
  a1.controller.start({ id: bob.id, username: "bob" }, "voice");
  await clock.advance(0);
  b1.controller.accept();
  await clock.advance(ICE);
  await clock.advance(ICE);
  check(phase(a1) === "connecting" && phase(b1) === "connecting", "stuck connecting");
  await clock.advance(30_000);
  // Both give up at 30 s: whichever goes first hangs up, and the other hears that.
  const texts = [a1.view?.endedText, b1.view?.endedText].sort().join(" / ");
  check(texts === "Call ended / Couldn’t connect", `one side couldn't connect, the other heard it: ${texts}`);
  check([...server.calls.values()][0].status === "ended", "and the call is over on the server");
  await clock.advance(4_000);
}

/* --- 14. ICE restarts: the caller offers, the callee asks -------------------------------------- */
{
  const { clock, server, alice, bob } = world();
  const a1 = new Device(alice, "a1", server, clock);
  const b1 = new Device(bob, "b1", server, clock);
  await connect(clock, a1, b1);
  a1.peer.setState("failed");
  check(a1.view?.reconnecting === true, "Reconnecting…");
  await clock.advance(ICE);
  await clock.advance(ICE);
  check(a1.view?.reconnecting === false && phase(a1) === "active", "recovered");
  let offers = (await plaintexts(server, a1, b1, "caller")).filter((s) => s.t === "offer");
  check(offers.length === 2 && offers[1].restart === true, "a restart offer with restart: true");

  // Bob's side breaks: he asks, alice waits out the 10 s since her last restart, then offers.
  b1.peer.setState("disconnected");
  check(b1.view?.reconnecting === true, "bob reconnecting");
  await clock.advance(4_000);
  const asked = (await plaintexts(server, b1, a1, "callee")).filter((s) => s.t === "restart");
  check(asked.length === 1, "after 4 s disconnected the callee asks for a restart");
  a1.peer.setState("disconnected");
  await clock.advance(ICE);
  offers = (await plaintexts(server, a1, b1, "caller")).filter((s) => s.t === "offer");
  check(offers.length === 2, "not within 10 s of the last one");
  await clock.advance(10_000);
  await clock.advance(ICE);
  offers = (await plaintexts(server, a1, b1, "caller")).filter((s) => s.t === "offer");
  check(offers.length === 3 && offers[2].restart === true, "then the restart goes");
  await clock.advance(ICE);
  check(!a1.view?.reconnecting && !b1.view?.reconnecting && phase(b1) === "active", "both recovered");

  // A connection that never comes back ends the call.
  a1.peer.blocked = true;
  b1.peer.blocked = true;
  a1.peer.setState("failed");
  b1.peer.setState("failed");
  await clock.advance(30_000);
  check(a1.view?.endedText === "Connection lost" || b1.view?.endedText === "Connection lost", "gave up: Connection lost");
  await clock.advance(4_000);
}

/* --- 15. the server ends a call without an event; 16. signals that must not count -------------- */
{
  const { clock, server, alice, bob } = world();
  const a1 = new Device(alice, "a1", server, clock);
  const b1 = new Device(bob, "b1", server, clock);
  await connect(clock, a1, b1);
  const call = [...server.calls.values()][0];

  // Garbage, and a real signal from a device outside the call.
  const real = server.signals.find((s) => s.from === "a1")!;
  const setBefore = b1.peer.descriptionsSet.length;
  for (const raw of [
    { call_id: call.id, from_user_id: alice.id, from_device_id: "a1", signal_type: "sdp_offer", payload: "c1.AAAA" },
    { call_id: call.id, from_user_id: alice.id, from_device_id: "a2", signal_type: real.type, payload: real.payload },
    { call_id: call.id, from_user_id: alice.id, from_device_id: "a1", signal_type: "sdp_answer", payload: real.payload },
    { call_id: call.id, from_user_id: alice.id, from_device_id: "a1", signal_type: real.type, payload: real.payload },
  ]) {
    b1.controller.handle({ type: "call.signal", raw: { type: "call.signal", ...raw } });
  }
  await clock.advance(0);
  check(b1.peer.descriptionsSet.length === setBefore && phase(b1) === "active", "tampered, foreign, relabelled and replayed signals change nothing");

  // A properly sealed offer from the callee: only the caller offers, so it is ignored.
  const bobSecret = deriveCallSecret(bob.privateKey, bob.publicKey, alice.publicKey);
  const bobKeys = await callKeys(bobSecret, call.id, "callee");
  const forged = await sealSignal(bobKeys.send, call.id, "sdp_offer", { t: "offer", sdp: "v=0\r\nsends=audio\r\n", restart: true, n: 900 });
  const callerSet = a1.peer.descriptionsSet.length;
  a1.controller.handle({
    type: "call.signal",
    raw: { type: "call.signal", call_id: call.id, from_user_id: bob.id, from_device_id: "b1", signal_type: "sdp_offer", payload: forged },
  });
  await clock.advance(0);
  check(a1.peer.descriptionsSet.length === callerSet && phase(a1) === "active", "the caller takes no offer");

  // Ended by the server's sweep, and the event lost: the next heartbeat finds out.
  call.status = "ended";
  call.ended_reason = "connection_lost";
  await clock.advance(10_000);
  check(a1.view?.endedText === "Connection lost" && b1.view?.endedText === "Connection lost", "a heartbeat reads the end");
  // The sweep's event arrives late, to the device that caused it too: nothing changes.
  server.publish([alice.id, bob.id], null, { type: "call.ended", raw: { type: "call.ended", call: { ...call } } });
  await clock.advance(0);
  check(a1.view?.endedText === "Connection lost", "a late end event is ignored");
  await clock.advance(2_000);
  check(a1.view === null && b1.view === null, "idle");

  // A signal refused because the call ended (CALL_ENDED) ends it here as well.
  await connect(clock, a1, b1);
  const second = [...server.calls.values()].find((c) => c.status === "active")!;
  second.status = "ended";
  second.ended_reason = "hangup";
  a1.controller.toggleMute();
  await clock.advance(0);
  check(a1.view?.phase === "ended" && a1.view.endedText === "Call ended", "a refused signal ends the call");
  await clock.advance(2_000);
}

/* --- 18. the page closes; 19. the shell locks ---------------------------------------------------- */
{
  const { clock, server, alice, bob } = world();
  const a1 = new Device(alice, "a1", server, clock);
  const b1 = new Device(bob, "b1", server, clock);
  await connect(clock, a1, b1);
  a1.controller.pageHide();
  check(server.requests.includes("a1 hangup keepalive"), "a closing page hangs up with keepalive");
  check(a1.view === null && a1.holds === 0, "and lets go at once");
  await clock.advance(0);
  check(b1.view?.endedText === "Call ended", "the other side hears it at once");
  await clock.advance(2_000);

  // Ringing when the chats are locked by hand: the ring stops here, nobody is told.
  a1.controller.start({ id: bob.id, username: "bob" }, "voice");
  await clock.advance(0);
  const requests = server.requests.length;
  b1.controller.endNow();
  check(b1.view === null && b1.holds === 0 && server.requests.length === requests, "a ring stops quietly on lock");
  check(phase(a1) === "outgoing", "and still rings elsewhere");
  a1.controller.release();
  check(server.requests.includes("a1 hangup") && a1.view === null, "releasing hangs up a call we placed");
  await clock.advance(0);
  // Released: rings are not heard until configured again.
  const b2 = new Device(bob, "b2", server, clock);
  b2.controller.release();
  a1.controller.configure({
    userId: alice.id,
    deviceId: "a1",
    api: server.api(a1),
    identity: () => ({ privateKey: alice.privateKey.slice(), publicKey: alice.publicKey.slice() }),
    peerKey: async (userId) => server.user(userId).publicKey,
    peerName: (userId) => server.user(userId).name,
  });
  await clock.advance(2_000);
  a1.controller.start({ id: bob.id, username: "bob" }, "voice");
  await clock.advance(0);
  check(b2.view === null, "a released controller hears no ring");
  a1.controller.hangup();
  await clock.advance(2_000);
}

console.log("calls controller selftest ok");
