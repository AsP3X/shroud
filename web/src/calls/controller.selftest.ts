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
import { sdpWithoutCandidates } from "./logic";
import {
  CAMERA_RELEASE_MS,
  CAMERA_UNAVAILABLE,
  SCREEN_ENDED,
  SCREEN_NOT_YET,
  SCREEN_UNAVAILABLE,
  VIDEO_UNAVAILABLE,
  type CallPeer,
  type CallView,
} from "./logic";
import { RELAY_UNAVAILABLE } from "./relay";

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
  muted = false;
  readyState: "live" | "ended" = "live";
  contentHint = "";
  readonly id = `track-${++trackIds}`;
  onmute: (() => void) | null = null;
  onunmute: (() => void) | null = null;
  onended: (() => void) | null = null;
  constructor(
    readonly kind: "audio" | "video",
    private readonly facing: string | undefined = undefined,
    private readonly deviceId = "cam-front",
  ) {}
  stop(): void {
    this.readyState = "ended";
  }
  /** A capture's size, as the browser reports it. */
  size: { width: number; height: number } | null = null;
  /** The capture's frame rate, where the browser reports one. */
  frameRate: number | undefined = undefined;
  /** A browser that cannot change the capture once it is open. */
  refusesConstraints = false;
  getSettings(): MediaTrackSettings {
    if (this.kind !== "video") return {};
    const rate = this.frameRate === undefined ? {} : { frameRate: this.frameRate };
    return { facingMode: this.facing, deviceId: this.deviceId, ...this.size, ...rate };
  }
  /** What `applyConstraints` was given, in order. */
  readonly applied: MediaTrackConstraints[] = [];
  async applyConstraints(constraints: MediaTrackConstraints): Promise<void> {
    this.applied.push(constraints);
    if (this.refusesConstraints) throw Object.assign(new Error("over"), { name: "OverconstrainedError" });
  }
  /** The system pauses or resumes the capture, as a browser reports it. */
  pause(paused: boolean): void {
    this.muted = paused;
    (paused ? this.onmute : this.onunmute)?.();
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
type Direction = "sendrecv" | "sendonly" | "recvonly" | "inactive" | "stopped";

const sends = (d: string) => d === "sendrecv" || d === "sendonly";
const receives = (d: string) => d === "sendrecv" || d === "recvonly";
function direction(send: boolean, receive: boolean): Direction {
  return send ? (receive ? "sendrecv" : "sendonly") : receive ? "recvonly" : "inactive";
}
/** The same section seen from the other end. */
function reversed(d: string): Direction {
  return direction(receives(d), sends(d));
}

class FakeSender {
  /** Every replaceTrack, in order (null takes the track off). */
  readonly replaced: (FakeTrack | null)[] = [];
  constructor(
    public track: FakeTrack | null,
    private readonly peer: FakePeer,
  ) {}
  async replaceTrack(next: FakeTrack | null): Promise<void> {
    if (this.peer.closed) throw Object.assign(new Error("closed"), { name: "InvalidStateError" });
    if (next && next.kind !== "video" && next.kind !== "audio") throw new TypeError("kind");
    this.track = next;
    this.replaced.push(next);
  }
  getParameters(): { encodings: { maxBitrate?: number; priority?: string }[] } {
    return { encodings: [{}] };
  }
  /** The camera's stats, as the test sets them on the peer. */
  async getStats(): Promise<Map<string, Record<string, unknown>>> {
    const stats = this.track?.kind === "video" ? this.peer.cameraStats : [];
    return new Map(stats.map((stat) => [String(stat.id), stat]));
  }
  async setParameters(params: {
    encodings?: { maxBitrate?: number; maxFramerate?: number; priority?: string; scaleResolutionDownBy?: number }[];
    degradationPreference?: string;
  }): Promise<void> {
    const encoding = params.encodings?.[0];
    this.peer.tuned.push({
      kind: this.track?.kind ?? "",
      track: this.track,
      maxBitrate: encoding?.maxBitrate,
      maxFramerate: encoding?.maxFramerate,
      priority: encoding?.priority,
      scale: encoding?.scaleResolutionDownBy,
      degradation: params.degradationPreference,
    });
  }
}

/** A transceiver as a browser keeps it: a sender, a receiver whose track exists from the start,
 *  and the direction asked for next to the one the last offer and answer settled. */
class FakeTransceiver {
  mid: string | null = null;
  currentDirection: Direction | null = null;
  readonly receiver: { track: FakeTrack };
  /** ontrack fired for it (once the other side sends on it). */
  announced = false;
  constructor(
    readonly kind: "audio" | "video",
    public direction: Direction,
    readonly sender: FakeSender,
    readonly byAddTrack: boolean,
  ) {
    this.receiver = { track: new FakeTrack(kind) };
  }
}

/** One media section of a fake SDP: `m=video sendrecv`. */
function sections(sdp: string): { kind: "audio" | "video"; direction: Direction }[] {
  return [...sdp.matchAll(/^m=(audio|video) (\w+)$/gm)].map((m) => ({
    kind: m[1] as "audio" | "video",
    direction: m[2] as Direction,
  }));
}

class FakePeer {
  localDescription: Description | null = null;
  remoteDescription: Description | null = null;
  signalingState = "stable";
  connectionState = "new";
  iceConnectionState = "new";
  onicecandidate: ((event: { candidate: unknown }) => void) | null = null;
  ontrack: ((event: { track: FakeTrack; streams: FakeStream[]; transceiver: FakeTransceiver }) => void) | null = null;
  onconnectionstatechange: (() => void) | null = null;
  oniceconnectionstatechange: (() => void) | null = null;
  readonly list: FakeTransceiver[] = [];
  /** addTransceiver calls, as `kind:direction`. */
  readonly transceivers: string[] = [];
  readonly remoteCandidates: unknown[] = [];
  readonly descriptionsSet: string[] = [];
  readonly tuned: {
    kind: string;
    track?: FakeTrack | null;
    maxBitrate?: number;
    maxFramerate?: number;
    priority?: string;
    scale?: number;
    degradation?: string;
  }[] = [];
  /** What the camera's sender reports (`getStats`): none until a test sets them. */
  cameraStats: Record<string, unknown>[] = [];
  closed = false;
  /** The network refuses every path (for timeouts). */
  blocked = false;
  /** Offers leave video out, as an older app's voice call did. */
  legacyVoice = false;
  /** Offers carry one section of each kind, as apps from before screen sharing did. */
  legacyScreen = false;
  private generation = 0;

  constructor(
    readonly name: string,
    public config: RTCConfiguration,
  ) {}

  setConfiguration(config: RTCConfiguration): void {
    this.config = config;
  }
  getConfiguration(): RTCConfiguration {
    return { ...this.config };
  }

  addTrack(track: FakeTrack): FakeSender {
    const sender = new FakeSender(track, this);
    this.list.push(new FakeTransceiver(track.kind, "sendrecv", sender, true));
    return sender;
  }
  addTransceiver(kind: "audio" | "video", init: { direction?: Direction }): FakeTransceiver {
    const transceiver = new FakeTransceiver(kind, init.direction ?? "sendrecv", new FakeSender(null, this), false);
    this.list.push(transceiver);
    this.transceivers.push(`${kind}:${transceiver.direction}`);
    return transceiver;
  }
  getTransceivers(): FakeTransceiver[] {
    return [...this.list];
  }
  getSenders(): FakeSender[] {
    return this.list.map((t) => t.sender);
  }
  /** The sender of the video section (the one the camera goes on). */
  get videoSender(): FakeSender | undefined {
    return this.list.find((t) => t.kind === "video")?.sender;
  }
  /** The senders of the screen's sections: the second video and the second audio. */
  get screenSender(): FakeSender | undefined {
    return this.list.filter((t) => t.kind === "video")[1]?.sender;
  }
  get soundSender(): FakeSender | undefined {
    return this.list.filter((t) => t.kind === "audio")[1]?.sender;
  }
  /** What an offer carries, in order. */
  private offered(): FakeTransceiver[] {
    const seen = { audio: 0, video: 0 };
    return this.list.filter((t) => {
      const place = seen[t.kind]++;
      if (this.legacyVoice && t.kind === "video") return false;
      return !(this.legacyScreen && place > 0);
    });
  }
  private describe(type: string, lines: string[]): string {
    return `v=0\r\nfake-${type} peer=${this.name} gen=${this.generation}\r\n${lines.map((l) => `${l}\r\n`).join("")}`;
  }
  async createOffer(options?: { iceRestart?: boolean }): Promise<Description> {
    if (options?.iceRestart) this.generation += 1;
    return { type: "offer", sdp: this.describe("offer", this.offered().map((t) => `m=${t.kind} ${t.direction}`)) };
  }
  async createAnswer(): Promise<Description> {
    const remoteGeneration = /gen=(\d+)/.exec(this.remoteDescription?.sdp ?? "")?.[1];
    this.generation = Number(remoteGeneration ?? this.generation);
    const lines = sections(this.remoteDescription?.sdp ?? "").map((section, index) => {
      const t = this.list.find((x) => x.mid === String(index));
      const offered = reversed(section.direction);
      const answer = t ? direction(sends(t.direction) && sends(offered), receives(t.direction) && receives(offered)) : "inactive";
      return `m=${section.kind} ${answer}`;
    });
    return { type: "answer", sdp: this.describe("answer", lines) };
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
    if (description.type === "offer") {
      let index = 0;
      for (const t of this.offered()) t.mid = String(index++);
    } else {
      sections(description.sdp).forEach((section, index) => {
        const t = this.list.find((x) => x.mid === String(index));
        if (t) t.currentDirection = section.direction;
      });
    }
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
    sections(description.sdp).forEach((section, index) => {
      const mid = String(index);
      let t = this.list.find((x) => x.mid === mid);
      if (description.type === "offer" && !t) {
        // A section is matched with a transceiver addTrack made (JSEP), or gets a new one that
        // only receives until told otherwise.
        t = this.list.find((x) => x.mid === null && x.byAddTrack && x.kind === section.kind);
        if (!t) {
          t = new FakeTransceiver(section.kind, "recvonly", new FakeSender(null, this), false);
          this.list.push(t);
        }
        t.mid = mid;
      }
      if (!t) return;
      if (description.type === "answer") t.currentDirection = reversed(section.direction);
      // The other end sends on it: its track shows up here (once).
      if (sends(section.direction) && !t.announced) {
        t.announced = true;
        this.ontrack?.({ track: t.receiver.track, streams: [], transceiver: t });
      }
    });
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
  /** What plays in the screen's sound slot. */
  screenAudio: unknown = null;
  /** The browser can share a screen; picks made, and how the picker answers. */
  canPickScreen = true;
  picks = 0;
  pickError: { name: string; message: string } | null = null;
  /** The picked screen comes with sound. */
  screenWithSound = true;
  /** The picked window closes before its screen is on the call. */
  pickedEndsAtOnce = false;
  readonly displays: FakeStream[] = [];
  /** What each picker was asked for. */
  readonly pickOptions: DisplayMediaStreamOptions[] = [];
  /** The next picked screen's size, and whether its browser refuses to narrow it. */
  nextDisplaySize: { width: number; height: number } | null = null;
  nextDisplayRefuses = false;
  awake = false;
  holds = 0;
  interrupted = 0;
  connected = true;
  denyMic = false;
  denyCamera = false;
  /** The camera's 1080p runs at 15 fps. */
  slowCamera = false;
  /** Peer connections made from now on never connect. */
  blockPeers = false;
  /** Offers leave video out, as an older app's voice call did. */
  legacyVoice = false;
  /** Offers leave the screen's sections out, as apps from before screen sharing did. */
  legacyScreen = false;
  /** Settings → Privacy → Always relay calls. */
  alwaysRelay = false;
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
    const device = this;
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
          const camera = new FakeTrack("video", facing, facing === "user" ? "cam-front" : "cam-back");
          // A 1080p front camera (the encoder sends a rung below it) and a 720p back one.
          camera.size = facing === "user" ? { width: 1920, height: 1080 } : { width: 1280, height: 720 };
          if (this.slowCamera) camera.frameRate = 15;
          tracks.push(camera);
        }
        const stream = new FakeStream(tracks);
        this.streams.push(stream);
        return stream as unknown as MediaStream;
      },
      cameras: async () => this.cameras,
      // A getter, so a device can be made a browser without a picker after it was built.
      get getDisplayMedia() {
        if (!device.canPickScreen) return undefined;
        return async (options: DisplayMediaStreamOptions) => {
          device.picks += 1;
          device.pickOptions.push(options);
          if (device.pickError) throw device.pickError;
          const tracks = [new FakeTrack("video")];
          if (options.audio && device.screenWithSound) tracks.push(new FakeTrack("audio"));
          if (device.pickedEndsAtOnce) tracks[0].readyState = "ended";
          tracks[0].size = device.nextDisplaySize;
          tracks[0].refusesConstraints = device.nextDisplayRefuses;
          const stream = new FakeStream(tracks);
          device.displays.push(stream);
          return stream as unknown as MediaStream;
        };
      },
      createPeer: (config) => {
        const peer = new FakePeer(this.id, config);
        peer.blocked = this.blockPeers;
        peer.legacyVoice = this.legacyVoice;
        peer.legacyScreen = this.legacyScreen;
        this.peers.push(peer);
        return peer as unknown as RTCPeerConnection;
      },
      createStream: (tracks) => new FakeStream(tracks as unknown as FakeTrack[]) as unknown as MediaStream,
      alwaysRelay: () => this.alwaysRelay,
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
      playAudio: async (stream, slot) => {
        if (slot === "screen") this.screenAudio = stream;
        else this.audio = stream;
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
  /** `GET /calls/ice-servers` hands out STUN only, as a server without coturn does. */
  noRelay = false;
  /** Holds `POST /calls`'s response back (the ring is out already), until released. */
  holdCreate: Promise<void> | null = null;
  /** The latest `media_state` per `call id:sending device`, as the server keeps it. */
  readonly media = new Map<string, string>();

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

  /** The call as one device reads it: in a live call, with the other device's latest media state. */
  private forDevice(call: CallInfo, device: Device): CallInfo {
    const out = this.copy(call);
    if (call.status !== "active" || !call.callee_device_id) return out;
    const other =
      device.id === call.caller_device_id
        ? call.callee_device_id
        : device.id === call.callee_device_id
          ? call.caller_device_id
          : null;
    const payload = other ? this.media.get(`${call.id}:${other}`) : undefined;
    if (other && payload) out.peer_media_state = { from_device_id: other, payload };
    return out;
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
        if (this.noRelay) return [{ urls: "stun:stun.test:3478" }];
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
        return this.forDevice(this.get(callId, device), device);
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
        if (signalType === "media_state") this.media.set(`${call.id}:${device.id}`, payload);
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
        return this.forDevice(this.get(callId, device), device);
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

/**
 * Opens every signal one device sent in a call, in order. The first offer and answer use the
 * identity keys. Later signals use the per-call key, which this helper does not have: they are
 * marked `forward` when the identity key cannot open them.
 */
async function plaintexts(server: Server, from: Device, to: Device, role: "caller" | "callee") {
  const out: Record<string, unknown>[] = [];
  for (const signal of server.signals.filter((s) => s.from === from.id)) {
    const secret = deriveCallSecret(to.user.privateKey, to.user.publicKey, from.user.publicKey);
    const keys = await callKeys(secret, signal.callId, role === "caller" ? "callee" : "caller");
    secret.fill(0);
    try {
      const opened = await openSignal(keys.receive, signal.callId, signal.type, signal.payload);
      if (typeof opened.sdp === "string") opened.sdp = sdpWithoutCandidates(opened.sdp);
      out.push({ type: signal.type, ...opened });
    } catch {
      out.push({ type: signal.type, forward: true });
    }
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
  check(a1.peer.config.iceCandidatePoolSize === 1, "one ICE candidate is gathered ahead of the answer");
  check(a1.peer.config.iceTransportPolicy === "all", "the call tries a direct path first");
  check(a1.peer.config.iceServers?.[0]?.username === "1:u", "the TURN login is passed on");
  check(
    a1.peer.tuned.some((t) => t.kind === "audio" && t.maxBitrate === 32_000 && t.priority === "high"),
    "speech stays near 32 kbps and keeps priority",
  );
  check(
    (a1.streams[0].getAudioTracks()[0] as { contentHint?: string }).contentHint === "speech",
    "the microphone is marked as speech",
  );
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
  check(b1.peer.transceivers.length === 0, "the callee adds no section of its own");
  check(b1.peer.list.find((t) => t.kind === "audio")?.sender.track?.kind === "audio", "the callee sends its microphone");
  check(
    a1.peer.transceivers.join() === "video:sendrecv,video:sendrecv,audio:sendrecv",
    `a voice call still offers video, and the screen's picture and sound, both ways (${a1.peer.transceivers.join()})`,
  );
  const calleeVideo = b1.peer.list.find((t) => t.kind === "video");
  check(
    calleeVideo?.direction === "sendrecv" && calleeVideo.currentDirection === "sendrecv" && calleeVideo.sender.track === null,
    "the callee takes it both ways, with no camera on it yet",
  );
  check(a1.view?.canVideo === true && b1.view?.canVideo === true, "so either side can switch to video");
  check(a1.view?.cameraOn === false && b1.view?.remoteCamera === false, "no camera in a voice call");

  const fromCaller = await plaintexts(server, a1, b1, "caller");
  const fromCallee = await plaintexts(server, b1, a1, "callee");
  check(fromCaller[0]?.type === "sdp_offer" && fromCaller[0].t === "offer" && fromCaller[0].restart === false, "the offer first");
  check(
    JSON.stringify(Object.keys(fromCaller[0])) === JSON.stringify(["type", "t", "sdp", "restart", "ek", "n"]),
    "the offer's fields as docs/calls.md has them",
  );
  check(typeof fromCaller[0].ek === "string" && typeof fromCallee[0].ek === "string", "both sides send a fresh key");
  check(!String(fromCaller[0].sdp).includes("a=candidate:"), "the offer carries no network address");
  check(fromCaller[0].n === 1, "the offer is numbered 1");
  check(fromCallee[0]?.t === "answer" && fromCallee[0].n === 1, "the callee answers first, numbered 1");
  const callerIce = fromCaller.filter((s) => s.type === "ice_candidate");
  const calleeIce = fromCallee.filter((s) => s.type === "ice_candidate");
  check(callerIce.length === 1 && calleeIce.length === 1, "candidates travel in one batch");
  check(
    callerIce[0]?.forward === true && calleeIce[0]?.forward === true && fromCaller.some((s) => s.type === "media_state" && s.forward === true),
    "addresses and camera state are sealed with the per-call key",
  );
  check(a1.peer.remoteCandidates.length === 3 && b1.peer.remoteCandidates.length === 3, "those addresses still connect");
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
  check(a1.view?.cameraOn === true && a1.view.mirrorSelf === true && a1.view.canSwitchCamera, "a mirrored front camera, and a second one");
  check(
    a1.peer.tuned.some((t) => t.kind === "video" && t.maxBitrate === 2_200_000 && t.scale === 1.5 && t.priority === "low"),
    "video starts at 720p from the 1080p camera and yields to speech",
  );
  // The link has room: the camera climbs to 1080p; then it tightens and the camera steps down.
  const cameraTune = () => a1.peer.tuned.filter((t) => t.kind === "video").at(-1);
  const link = (estimate: number, limitation = "none") => [
    { id: "OT", type: "outbound-rtp", kind: "video", qualityLimitationReason: limitation },
    { id: "RI", type: "remote-inbound-rtp", kind: "video", fractionLost: 0 },
    { id: "T", type: "transport", selectedCandidatePairId: "CP" },
    { id: "CP", type: "candidate-pair", availableOutgoingBitrate: estimate },
  ];
  a1.peer.cameraStats = link(6_000_000);
  await clock.advance(6 * 2_000);
  check(cameraTune()?.maxBitrate === 2_200_000, "six readings in, still 720p");
  await clock.advance(2_000);
  check(cameraTune()?.maxBitrate === 3_800_000 && cameraTune()?.scale === 1, `clean readings: 1080p (${JSON.stringify(cameraTune())})`);
  a1.peer.cameraStats = link(500_000, "bandwidth");
  await clock.advance(4 * 2_000);
  check(cameraTune()?.maxBitrate === 600_000 && cameraTune()?.scale === 3, `a tight link: 360p (${JSON.stringify(cameraTune())})`);
  a1.peer.cameraStats = [];
  // A video call goes to voice: both cameras off, no new offer, every event still doubled.
  const offers = server.signals.filter((s) => s.type === "sdp_offer").length;
  a1.controller.toggleCamera();
  b1.controller.toggleCamera();
  await clock.advance(CAMERA_RELEASE_MS);
  check(a1.view?.remoteCamera === false && b1.view?.remoteCamera === false, "a video call becomes a voice call");
  check(a1.peer.videoSender?.track === null && b1.peer.videoSender?.track === null, "no video goes out either way");
  check(server.signals.filter((s) => s.type === "sdp_offer").length === offers, "without a new offer");
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
  check(a1.view?.notice === CAMERA_UNAVAILABLE && a1.view.cameraOn === false, "told the camera is unavailable");
  check(a1.peer.transceivers[0] === "video:sendrecv", "still offers video both ways, to turn on later");
  b1.controller.accept();
  await clock.advance(ICE);
  await clock.advance(ICE);
  check(phase(a1) === "active" && b1.view?.remoteCamera === false, "bob sees alice's camera is off");
  check(a1.view?.remoteVideo === true && a1.view.remoteCamera === true, "alice sees bob");
  a1.controller.toggleCamera();
  await clock.advance(0);
  check(a1.view?.cameraOn === false, "still no camera to turn on");
  check(a1.view?.notice === "Allow camera access in your browser to turn on video.", "and why");
  await clock.advance(6_000);
  check(a1.view?.notice === null, "the notice passes");
  // Allowed now: her video comes on mid-call.
  a1.denyCamera = false;
  a1.controller.toggleCamera();
  await clock.advance(0);
  check(a1.view?.cameraOn === true && b1.view?.remoteCamera === true, "alice's video comes on");
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
  check(b1.peer.videoSender?.track?.getSettings().facingMode === "environment", "the sender sends the back camera");
  check(b1.view?.localStream?.getVideoTracks()[0]?.getSettings().facingMode === "environment", "and our picture shows it");
  const backTune = b1.peer.tuned.filter((t) => t.kind === "video").at(-1);
  check(
    backTune?.track === b1.peer.videoSender?.track && backTune?.scale === 1 && backTune.maxBitrate === 2_200_000,
    `the encoder follows the 720p back camera (${JSON.stringify({ scale: backTune?.scale, max: backTune?.maxBitrate })})`,
  );
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
  const offersFrom = (id: string) => server.signals.filter((s) => s.from === id && s.type === "sdp_offer");
  check(offersFrom(a1.id).length === 2, "a restart offer follows the first");
  check(a1.peer.config.iceTransportPolicy === "relay", "a failed link falls back to the relay");
  check(b1.peer.config.iceTransportPolicy === "all", "bob has not failed, so he still tries every path");

  // Bob's side breaks: he asks, alice waits out the 10 s since her last restart, then offers.
  b1.peer.setState("disconnected");
  check(b1.view?.reconnecting === true, "bob reconnecting");
  await clock.advance(4_000);
  const asked = server.signals.filter((s) => s.from === b1.id && s.type === "renegotiate");
  check(asked.length === 1, "after 4 s disconnected the callee asks for a restart");
  check(b1.peer.config.iceTransportPolicy === "all", "a short disconnect does not force the relay");
  a1.peer.setState("disconnected");
  await clock.advance(ICE);
  check(offersFrom(a1.id).length === 2, "not within 10 s of the last one");
  await clock.advance(10_000);
  await clock.advance(ICE);
  check(offersFrom(a1.id).length === 3, "then the restart goes");
  await clock.advance(ICE);
  check(!a1.view?.reconnecting && !b1.view?.reconnecting && phase(b1) === "active", "both recovered");

  // A connection that never comes back ends the call.
  a1.peer.blocked = true;
  b1.peer.blocked = true;
  a1.peer.setState("failed");
  b1.peer.setState("failed");
  check(b1.peer.config.iceTransportPolicy === "relay", "a failed link on bob falls back to the relay too");
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

/* --- 20. a voice call becomes a video call and back, from either side, without a new offer ---- */
{
  const { clock, server, alice, bob } = world();
  const a1 = new Device(alice, "a1", server, clock);
  const b1 = new Device(bob, "b1", server, clock);
  await connect(clock, a1, b1);
  check(b1.view?.remoteVideo === true && b1.view.remoteCamera === false, "their video track is there, with no picture yet");
  const offers = () => server.signals.filter((s) => s.type === "sdp_offer").length;
  const offersBefore = offers();
  const opened = a1.streams.length;

  // Alice turns video on.
  a1.controller.toggleCamera();
  check(a1.view?.cameraPending === true && a1.view.cameraOn === false, "the camera opens");
  await clock.advance(0);
  check(a1.view?.cameraOn === true && a1.view.cameraPending === false, "video on");
  const camera = a1.streams[opened]?.getVideoTracks()[0];
  check(Boolean(camera) && a1.peer.videoSender?.track === camera, "the camera goes on the video section");
  check(a1.view?.localStream?.getVideoTracks()[0] === (camera as unknown as MediaStreamTrack), "and into our own picture");
  check(a1.view?.localStream?.getAudioTracks().length === 1, "next to the microphone");
  check(a1.view?.mirrorSelf === true && a1.view.canSwitchCamera, "the front camera, mirrored; a second one to flip to");
  check(b1.view?.remoteCamera === true, "bob is told");
  check(a1.peer.tuned.some((t) => t.kind === "video" && t.maxBitrate === 2_200_000), "tuned like a video call's video");

  // Bob turns his on too; then alice goes back to voice while bob stays on video.
  b1.controller.toggleCamera();
  await clock.advance(0);
  check(b1.view?.cameraOn === true && a1.view?.remoteCamera === true, "both on video");
  a1.controller.toggleCamera();
  check(a1.view?.cameraOn === false, "alice's video off");
  await clock.advance(0);
  check(a1.peer.videoSender?.track === null && b1.view?.remoteCamera === false, "nothing more goes out, and bob is told");
  check(camera?.readyState === "live", "her own picture fades out before the camera closes");
  await clock.advance(CAMERA_RELEASE_MS);
  check(camera?.readyState === "ended", "then the camera closes");
  check(a1.view?.localStream?.getVideoTracks().length === 0, "and leaves her picture");
  check(a1.view?.remoteCamera === true, "bob's video goes on");
  b1.controller.toggleCamera();
  await clock.advance(CAMERA_RELEASE_MS);
  check(a1.view?.remoteCamera === false && b1.view?.cameraOn === false, "a voice call again");

  check(offers() === offersBefore, "no new offer for any of it");
  check(phase(a1) === "active" && phase(b1) === "active", "the same call");
  check(a1.peers.length === 1 && b1.peers.length === 1 && !a1.peer.closed, "on the same connection");
  check([...server.calls.values()][0].modality === "voice", "the server's call stays as it was placed");

  // On, off and on again quickly: the camera from a moment ago comes back without asking again.
  a1.controller.toggleCamera();
  await clock.advance(0);
  const count = a1.streams.length;
  a1.controller.toggleCamera();
  a1.controller.toggleCamera();
  await clock.advance(0);
  check(a1.view?.cameraOn === true && a1.streams.length === count, "back on with the same camera");
  await clock.advance(CAMERA_RELEASE_MS);
  check(a1.peer.videoSender?.track?.readyState === "live" && b1.view?.remoteCamera === true, "and it stays on");
  // A press while the camera is still opening is not a second camera.
  a1.controller.toggleCamera();
  await clock.advance(CAMERA_RELEASE_MS);
  a1.controller.toggleCamera();
  a1.controller.toggleCamera();
  await clock.advance(0);
  check(a1.streams.length === count + 1 && a1.view?.cameraOn === true, "one camera per press that counts");

  // Hung up with the camera on: everything closes.
  a1.controller.hangup();
  await clock.advance(0);
  check(a1.streams.every((s) => s.getTracks().every((t) => t.readyState === "ended")), "camera and microphone released");
  check(b1.view?.endedText === "Call ended", "bob hears it");
  await clock.advance(2_000);
  check(a1.view === null && b1.view === null, "both idle");
}

/* --- 21. a camera refused mid-call; an older caller whose voice call has no video -------------- */
{
  const { clock, server, alice, bob } = world();
  const a1 = new Device(alice, "a1", server, clock);
  const b1 = new Device(bob, "b1", server, clock);
  await connect(clock, a1, b1);
  a1.denyCamera = true;
  a1.controller.toggleCamera();
  await clock.advance(0);
  check(a1.view?.cameraOn === false && a1.view.cameraPending === false, "no camera, no video");
  check(a1.view?.notice === "Allow camera access in your browser to turn on video.", "and why");
  check(b1.view?.remoteCamera === false && phase(a1) === "active" && phase(b1) === "active", "the call goes on");
  a1.controller.hangup();
  await clock.advance(2_000);

  const old = new Device(alice, "a2", server, clock);
  old.legacyVoice = true;
  await connect(clock, old, b1);
  check(b1.view?.canVideo === false, "an older app's voice call brings no video to switch to");
  const asked = b1.streams.length;
  b1.controller.toggleCamera();
  await clock.advance(0);
  check(b1.view?.cameraOn === false && b1.streams.length === asked, "the camera is not even opened");
  check(b1.view?.notice === VIDEO_UNAVAILABLE, "and why");
  check(phase(b1) === "active" && phase(old) === "active", "the call goes on");
  old.controller.hangup();
  await clock.advance(2_000);
}

/* --- 22. a switch lost in a socket gap is caught up from the server ---------------------------- */
{
  const { clock, server, alice, bob } = world();
  const a1 = new Device(alice, "a1", server, clock);
  const b1 = new Device(bob, "b1", server, clock);
  await connect(clock, a1, b1);
  b1.connected = false;
  a1.controller.toggleCamera();
  await clock.advance(0);
  check(a1.view?.cameraOn === true && b1.view?.remoteCamera === false, "the switch did not reach bob");
  b1.connected = true;
  b1.controller.handle({ type: "auth.ok", raw: { type: "auth.ok" } });
  await clock.advance(0);
  check(b1.view?.remoteCamera === true, "back on the socket, bob reads it from the server");

  // Lost again, and no reconnect noticed: the next heartbeat brings it.
  b1.connected = false;
  a1.controller.toggleCamera();
  await clock.advance(0);
  b1.connected = true;
  check(b1.view?.remoteCamera === true, "the switch back was lost too");
  await clock.advance(10_000);
  check(b1.view?.remoteCamera === false, "the heartbeat brings it");

  // The server's copy can be older than one that came over the socket: it changes nothing.
  await clock.advance(CAMERA_RELEASE_MS);
  b1.connected = false;
  a1.controller.toggleCamera();
  await clock.advance(0);
  const lost = server.signals.filter((s) => s.from === "a1" && s.type === "media_state").pop()!;
  b1.connected = true;
  a1.controller.toggleCamera();
  await clock.advance(0);
  check(b1.view?.remoteCamera === false, "bob has the latest: off");
  server.media.set(`${lost.callId}:a1`, lost.payload);
  await clock.advance(10_000);
  check(b1.view?.remoteCamera === false, "an older copy from the server does not undo it");
  a1.controller.hangup();
  await clock.advance(2_000);
}

/* --- 23. the system pauses our camera; the camera goes away ------------------------------------ */
{
  const { clock, server, alice, bob } = world();
  const a1 = new Device(alice, "a1", server, clock);
  const b1 = new Device(bob, "b1", server, clock);
  await connect(clock, a1, b1, "video");
  const camera = a1.peer.videoSender?.track;
  check(Boolean(camera) && b1.view?.remoteCamera === true, "a video call");
  camera!.pause(true);
  await clock.advance(0);
  check(b1.view?.remoteCamera === false, "paused: bob sees alice's face, not a frozen frame");
  // A paused camera sends nothing, so its stats look clean: the ladder must not climb on them.
  const tunesBefore = a1.peer.tuned.filter((t) => t.kind === "video").length;
  a1.peer.cameraStats = [
    { id: "OT", type: "outbound-rtp", kind: "video", qualityLimitationReason: "none" },
    { id: "T", type: "transport", selectedCandidatePairId: "CP" },
    { id: "CP", type: "candidate-pair", availableOutgoingBitrate: 6_000_000 },
  ];
  await clock.advance(30_000);
  check(a1.peer.tuned.filter((t) => t.kind === "video").length === tunesBefore, "a paused camera is not read");
  a1.peer.cameraStats = [];
  check(a1.view?.cameraOn === true, "alice's video stays on, waiting for the camera");
  camera!.pause(false);
  await clock.advance(0);
  check(b1.view?.remoteCamera === true, "resumed: her picture again");
  camera!.readyState = "ended";
  camera!.onended?.();
  await clock.advance(0);
  check(a1.view?.cameraOn === false && a1.view.notice === CAMERA_UNAVAILABLE, "a camera that goes away turns video off");
  check(b1.view?.remoteCamera === false && a1.peer.videoSender?.track === null, "and bob is told");
  a1.controller.toggleCamera();
  await clock.advance(0);
  check(a1.view?.cameraOn === true && b1.view?.remoteCamera === true, "a camera that comes back can be turned on again");
  a1.controller.hangup();
  await clock.advance(2_000);
}

/* --- 24. a screen shared next to the camera, from either side, without a new offer ------------- */
{
  const { clock, server, alice, bob } = world();
  const a1 = new Device(alice, "a1", server, clock);
  const b1 = new Device(bob, "b1", server, clock);
  await connect(clock, a1, b1);
  const offers = () => server.signals.filter((s) => s.type === "sdp_offer").length;
  const offersBefore = offers();
  check(a1.view?.canShare === true && b1.view?.canShare === true, "both can share: the sections go both ways and both apps show screens");
  check(a1.view?.shareSupported === true && a1.view.remoteScreen === false, "nothing shared yet");
  const calleeScreen = b1.peer.list.filter((t) => t.kind === "video")[1];
  check(calleeScreen?.direction === "sendrecv", `the callee takes the screen's section both ways (${calleeScreen?.direction})`);
  check(b1.view?.remoteScreenStream?.getVideoTracks().length === 1, "their screen's picture is there, apart from the camera");
  check(b1.view?.remoteStream?.getVideoTracks().length === 1, "and the camera's stream keeps one picture");
  check(b1.view?.remoteScreenStream?.getAudioTracks().length === 1 && b1.screenAudio !== null, "the screen's sound plays in its own slot");
  check(b1.audio !== b1.screenAudio, "apart from the microphone");

  // Alice shares a tab with its sound.
  a1.controller.toggleScreen();
  check(a1.picks === 1 && a1.view?.screenPending === true, "the picker opens within the click");
  await clock.advance(0);
  const display = a1.displays[0];
  const picture = display.getVideoTracks()[0];
  const sound = display.getAudioTracks()[0];
  check(a1.view?.screenOn === true && a1.view.screenPending === false && a1.view.screenSound, "sharing, with sound");
  check(a1.peer.screenSender?.track === picture && a1.peer.soundSender?.track === sound, "on the screen's own sections");
  check(a1.peer.videoSender?.track === null, "the camera's section is left alone");
  check(picture.contentHint === "detail" && sound.contentHint === "music", "sharp text; sound as it is");
  check(a1.view?.screenStream === (display as unknown as MediaStream), "her own preview");
  check(b1.view?.remoteScreen === true && b1.view.remoteCamera === false, "bob is told: a screen, no camera");
  const screenTune = a1.peer.tuned.filter((t) => t.track === picture).at(-1);
  check(
    screenTune?.maxBitrate === 2_500_000 && screenTune.degradation === "maintain-resolution" && screenTune.priority === "medium",
    `the screen keeps its sharpness at about 2.5 Mbps (${JSON.stringify(screenTune)})`,
  );
  check(a1.peer.tuned.some((t) => t.track === sound && t.maxBitrate === 128_000), "its sound at about 128 kbps");

  // With her camera on as well, the camera drops to a tile's worth.
  a1.controller.toggleCamera();
  await clock.advance(0);
  const camera = a1.peer.videoSender?.track;
  const cameraTune = a1.peer.tuned.filter((t) => t.track === camera).at(-1);
  check(a1.view?.cameraOn === true && b1.view?.remoteCamera === true && b1.view.remoteScreen, "camera and screen together");
  check(cameraTune?.maxBitrate === 350_000 && cameraTune.scale === 3, `the camera as a thumbnail (${JSON.stringify(cameraTune)})`);

  // Bob shares too: both at once.
  b1.controller.toggleScreen();
  await clock.advance(0);
  check(b1.view?.screenOn === true && a1.view?.remoteScreen === true, "bob shares as well");
  check(b1.peer.screenSender?.track === b1.displays[0].getVideoTracks()[0], "on the section he took from the offer");

  // Alice stops from the call screen: no notice, the capture ends, bob is told.
  a1.controller.toggleScreen();
  await clock.advance(0);
  check(a1.view?.screenOn === false && a1.view.notice === null && a1.view.screenStream === null, "alice stopped sharing");
  check(picture.readyState === "ended" && sound.readyState === "ended", "the capture ends");
  check(a1.peer.screenSender?.track === null && a1.peer.soundSender?.track === null, "nothing more goes out");
  check(b1.view?.remoteScreen === false && b1.view.remoteCamera === true, "bob sees her camera again, full size");
  const cameraBack = a1.peer.tuned.filter((t) => t.track === camera).at(-1);
  check(cameraBack?.maxBitrate === 2_200_000 && cameraBack.scale === 1.5, "the camera gets its rung back");

  // Bob stops from the browser's own bar (the track ends): he is told why.
  const bobPicture = b1.displays[0].getVideoTracks()[0];
  bobPicture.readyState = "ended";
  bobPicture.onended?.();
  await clock.advance(0);
  check(b1.view?.screenOn === false && b1.view.notice === SCREEN_ENDED, "the browser's stop is noticed");
  check(a1.view?.remoteScreen === false, "and alice is told");

  // A closed picker says nothing; a system refusal says what to do.
  a1.pickError = { name: "NotAllowedError", message: "Permission denied" };
  a1.controller.toggleScreen();
  await clock.advance(0);
  check(a1.view?.screenOn === false && a1.view.screenPending === false && a1.view.notice === null, "a closed picker is not an error");
  a1.pickError = { name: "NotAllowedError", message: "Permission denied by system" };
  a1.controller.toggleScreen();
  await clock.advance(0);
  check(a1.view?.notice?.startsWith("Allow screen recording") === true, `a system refusal says what to do (${a1.view?.notice})`);
  a1.pickError = null;

  // The chosen window closes while it is being put on the call: nothing stays "shared".
  a1.pickedEndsAtOnce = true;
  a1.controller.toggleScreen();
  await clock.advance(0);
  check(a1.view?.screenOn === false && a1.view.notice === SCREEN_ENDED, `a capture that ended in setup is not left on (${a1.view?.notice})`);
  check(a1.peer.screenSender?.track === null && b1.view?.remoteScreen === false, "nothing goes out, and bob hears nothing of it");
  check(a1.displays.at(-1)!.getTracks().every((t) => t.readyState === "ended"), "and the capture is released");
  a1.pickedEndsAtOnce = false;

  // A screen without sound (a window, a browser that offers none).
  a1.screenWithSound = false;
  a1.controller.toggleScreen();
  await clock.advance(0);
  check(a1.view?.screenOn === true && a1.view.screenSound === false, "shared without sound");
  check(a1.peer.soundSender?.track === null && b1.view?.remoteScreen === true, "the sound's section stays empty");

  check(offers() === offersBefore, "no new offer for any of it");
  check(a1.peers.length === 1 && b1.peers.length === 1, "on the same connection");

  // Hung up while sharing: everything closes.
  a1.controller.hangup();
  await clock.advance(0);
  check(a1.displays.every((d) => d.getTracks().every((t) => t.readyState === "ended")), "the capture ends with the call");
  check(b1.screenAudio === null, "the screen's sound slot is let go");
  await clock.advance(2_000);
}

/* --- 24b. the screen's resolution and frame rate, chosen before sharing and changed while it runs */
{
  const { clock, server, alice, bob } = world();
  const a1 = new Device(alice, "a1", server, clock);
  const b1 = new Device(bob, "b1", server, clock);
  await connect(clock, a1, b1);
  const offers = () => server.signals.filter((s) => s.type === "sdp_offer").length;
  const offersBefore = offers();
  check(a1.view?.screenQuality.resolution === "1080p" && a1.view.screenQuality.frameRate === 30, "1080p at 30 fps to begin with");

  // Chosen before sharing: the picker opens the capture at the ceiling, and it is narrowed to the
  // choice before it goes out (a browser may never raise a capture above what it was opened at).
  a1.controller.setScreenQuality({ resolution: "720p", frameRate: 15 });
  check(a1.view?.screenQuality.resolution === "720p" && a1.view.screenQuality.frameRate === 15, "the choice shows at once");
  a1.controller.toggleScreen();
  await clock.advance(0);
  const asked = a1.pickOptions[0]?.video as MediaTrackConstraints;
  check(JSON.stringify(asked) === JSON.stringify({ frameRate: { max: 60 } }), `the picker asks for the ceiling (${JSON.stringify(asked)})`);
  const picture = a1.displays[0].getVideoTracks()[0];
  check(
    JSON.stringify(picture.applied[0]) === JSON.stringify({ width: { max: 1280 }, height: { max: 720 }, frameRate: { ideal: 15, max: 15 } }),
    `narrowed to 720p at 15 fps (${JSON.stringify(picture.applied[0])})`,
  );
  const first = a1.peer.tuned.filter((t) => t.track === picture).at(-1);
  check(first?.maxFramerate === 15 && first.maxBitrate === 1_200_000, `sent at 15 fps and 1.2 Mbps (${JSON.stringify(first)})`);
  check(picture.contentHint === "detail", "sharp text");

  // Changed while sharing: the capture and the encoder follow, without a new picker or offer.
  a1.controller.setScreenQuality({ resolution: "source", frameRate: 60 });
  await clock.advance(0);
  check(a1.picks === 1, "no new picker");
  check(JSON.stringify(picture.applied.at(-1)) === JSON.stringify({ frameRate: { ideal: 60, max: 60 } }), "the capture lifts its size limit, at 60 fps");
  const second = a1.peer.tuned.filter((t) => t.track === picture).at(-1);
  check(
    second?.maxFramerate === 60 && second.maxBitrate === 6_500_000 && second.degradation === "balanced",
    `sent at 60 fps and 6.5 Mbps, giving up some of each (${JSON.stringify(second)})`,
  );
  check(picture.contentHint === "motion", "encoded for motion");
  check(offers() === offersBefore, "no new offer");
  check(b1.view?.remoteScreen === true, "bob still sees it");

  // Kept for the next share in this call.
  a1.controller.toggleScreen();
  await clock.advance(0);
  a1.controller.toggleScreen();
  await clock.advance(0);
  const nextPicture = a1.displays[1].getVideoTracks()[0];
  check(JSON.stringify(nextPicture.applied[0]) === JSON.stringify({ frameRate: { ideal: 60, max: 60 } }), "the next share is narrowed to the source at 60 fps");

  // A browser that refuses to narrow the capture: the encoder shrinks the full-size picture.
  a1.controller.toggleScreen();
  await clock.advance(0);
  a1.controller.setScreenQuality({ resolution: "1080p", frameRate: 30 });
  a1.nextDisplaySize = { width: 3840, height: 2160 };
  a1.nextDisplayRefuses = true;
  a1.controller.toggleScreen();
  await clock.advance(0);
  const stubborn = a1.displays[2].getVideoTracks()[0];
  const fitted = a1.peer.tuned.filter((t) => t.track === stubborn).at(-1);
  check(a1.view?.screenOn === true, "shared all the same");
  check(fitted?.scale === 2, `4K shrunk by 2 to fit 1080p (${JSON.stringify(fitted)})`);
  a1.controller.setScreenQuality({ resolution: "source", frameRate: 30 });
  await clock.advance(0);
  check(a1.peer.tuned.filter((t) => t.track === stubborn).at(-1)?.scale === 1, "source: full size");
  a1.controller.hangup();
  await clock.advance(4_000);
}

/* --- 25. screens with an older app, and in a browser that cannot share ------------------------- */
{
  const { clock, server, alice, bob } = world();
  // Before the call connects, Share says to wait rather than blaming their app.
  const early = new Device(alice, "a0", server, clock);
  const other = new Device(bob, "b0", server, clock);
  early.controller.start({ id: bob.id, username: "bob" }, "voice");
  await clock.advance(0);
  early.controller.toggleScreen();
  check(early.picks === 0 && early.view?.notice === SCREEN_NOT_YET, `not yet (${early.view?.notice})`);
  early.controller.hangup();
  await clock.advance(2_000);
  other.connected = false;

  const old = new Device(alice, "a1", server, clock);
  const b1 = new Device(bob, "b1", server, clock);
  old.legacyScreen = true;
  await connect(clock, old, b1);
  check(b1.view?.canShare === false, "an older caller's offer brings no screen section");
  b1.controller.toggleScreen();
  await clock.advance(0);
  check(b1.picks === 0 && b1.view?.notice === SCREEN_UNAVAILABLE, "the picker does not even open, and why");
  check(phase(b1) === "active" && b1.view?.canVideo === true, "the call and its video go on");
  old.controller.hangup();
  await clock.advance(2_000);

  const phone = new Device(alice, "a2", server, clock);
  phone.canPickScreen = false;
  await connect(clock, phone, b1);
  check(phone.view?.shareSupported === false, "a browser without a screen picker cannot share");
  phone.controller.toggleScreen();
  await clock.advance(0);
  check(phone.view?.screenOn === false && phone.view.notice === null, "and nothing happens");
  check(b1.view?.canShare === true, "but can still see a screen: the other side may share");
  b1.controller.toggleScreen();
  await clock.advance(0);
  check(phone.view?.remoteScreen === true && phone.view.remoteScreenStream?.getVideoTracks().length === 1, "it shows bob's");
  phone.controller.hangup();
  await clock.advance(2_000);
}

/* --- always relay calls: relayed from the start, and refused where no relay exists ---------- */
{
  const { clock, server, alice, bob } = world();
  const a1 = new Device(alice, "a1", server, clock);
  const b1 = new Device(bob, "b1", server, clock);
  a1.alwaysRelay = true;

  await connect(clock, a1, b1);
  check(a1.peer.config.iceTransportPolicy === "relay", "the caller gathers relay candidates only");
  check(b1.peer.config.iceTransportPolicy === "all", "the callee's own setting stays theirs");
  a1.peer.setState("failed");
  await clock.advance(ICE);
  await clock.advance(ICE);
  check(a1.peer.config.iceTransportPolicy === "relay", "a failed link stays on the relay");
  a1.controller.hangup();
  await clock.advance(4_000);

  server.noRelay = true;
  const rings = server.requests.filter((r) => r === "a1 create").length;
  a1.controller.start({ id: bob.id, username: "bob" }, "voice");
  await clock.advance(0);
  check(a1.view?.endedText === RELAY_UNAVAILABLE, `no relay: the call is refused, not sent direct (${a1.view?.endedText})`);
  check(server.requests.filter((r) => r === "a1 create").length === rings, "and no ring goes out");
  check(a1.peers.every((p) => p.config.iceTransportPolicy === "relay"), "no direct peer connection was made");
  await clock.advance(4_000);

  // Answering with the switch on and no relay: this device never accepts over a direct path.
  server.noRelay = false;
  a1.alwaysRelay = false;
  b1.alwaysRelay = true;
  a1.controller.start({ id: bob.id, username: "bob" }, "voice");
  await clock.advance(0);
  server.noRelay = true;
  const accepts = server.requests.filter((r) => r === "b1 accept").length;
  b1.controller.accept();
  await clock.advance(0);
  check(b1.view?.endedText === RELAY_UNAVAILABLE, `the callee sees why it can't answer (${b1.view?.endedText})`);
  check(server.requests.filter((r) => r === "b1 accept").length === accepts, "and never accepts");
  a1.controller.hangup();
  await clock.advance(4_000);
}

/* --- a webcam whose 1080p runs slowly opens at 720p ------------------------------------------ */
{
  const { clock, server, alice, bob } = world();
  const a1 = new Device(alice, "a1", server, clock);
  const b1 = new Device(bob, "b1", server, clock);
  a1.slowCamera = true;
  await connect(clock, a1, b1, "video");
  const camera = a1.peer.videoSender?.track;
  check(
    JSON.stringify(camera?.applied[0]) === JSON.stringify({ width: { ideal: 1280 }, height: { ideal: 720 }, frameRate: { ideal: 30, max: 30 } }),
    `a 15 fps 1080p is narrowed to 720p (${JSON.stringify(camera?.applied)})`,
  );
  check(b1.peer.videoSender?.track?.applied.length === 0, "a smooth 1080p is left alone");
  a1.controller.hangup();
  await clock.advance(4_000);
}

console.log("calls controller selftest ok");
