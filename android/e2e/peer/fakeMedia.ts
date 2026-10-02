/*
 * Fake media for the scripted web peer's calls (android/e2e/peer/peer.ts): the stand-ins the web
 * call controller's own selftest uses (web/src/calls/controller.selftest.ts, copied verbatim), so the
 * real web CallController runs in node without WebRTC. A FakePeer writes SDPs as
 * `v=0 / fake-offer … / m=<audio|video> <direction>` lines, opens the other side's `m=` lines the
 * same way and "connects" once offer, answer and at least one remote candidate have crossed —
 * exactly what the Android e2e's fake CallMediaEngine (EngineE2eTest) writes and expects.
 */
let trackIds = 0;

export class FakeTrack {
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
  /** A captured screen's size, as the browser reports it (none for a camera here). */
  size: { width: number; height: number } | null = null;
  /** A browser that cannot change the capture once it is open. */
  refusesConstraints = false;
  getSettings(): MediaTrackSettings {
    return this.kind === "video" ? { facingMode: this.facing, deviceId: this.deviceId, ...this.size } : {};
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

export class FakeStream {
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

export class FakeSender {
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
export class FakeTransceiver {
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

export class FakePeer {
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
