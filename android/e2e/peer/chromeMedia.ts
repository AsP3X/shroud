/*
 * A real RTCPeerConnection for the scripted web peer, running in headless Chrome.
 *
 * The fake peer (fakeMedia.ts) writes `m=audio sendrecv` lines and pretends to connect. Android's
 * WebRTC engine cannot complete DTLS or ICE against that. This bridge keeps the peer connection,
 * microphone and transceivers inside Chrome (the same stack the web client uses) and presents the
 * slice of the browser API that web/src/calls/controller.ts calls. Nothing here is logged: session
 * descriptions and candidate addresses stay on the DevTools socket.
 *
 * Chrome is launched with a fake microphone and with mDNS hostnames turned off, so gathered host
 * candidates are literal IPv4 addresses. IPv6 and `.local` candidates are not forwarded; an
 * emulator has no route to them.
 */
import { spawn, type ChildProcess } from "node:child_process";
import { mkdirSync, readFileSync, rmSync } from "node:fs";
import { request as httpRequest } from "node:http";

type Json = Record<string, unknown>;
type Description = { type: string; sdp: string };
type Socket = {
  send(data: string): void;
  close(): void;
  addEventListener(type: "message" | "error" | "close", fn: (ev: { data?: unknown }) => void): void;
};

const CHROME =
  process.env.CHROME_PATH || "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome";

type Pending = { resolve: (value: Json) => void; reject: (error: Error) => void };

export class ChromeMedia {
  iceState = "new";
  private chrome: ChildProcess | null = null;
  private socket: Socket | null = null;
  private sessionId = "";
  private next = 0;
  private readonly pending = new Map<number, Pending>();
  private starting: Promise<void> | null = null;
  private readonly peers = new Set<ChromePeer>();

  constructor(
    private readonly originUrl: string,
    private readonly profileDir: string,
  ) {}

  start(): Promise<void> {
    if (!this.starting) this.starting = this.launch();
    return this.starting;
  }

  async stop(): Promise<void> {
    try {
      this.socket?.close();
    } catch {
      /* already closed */
    }
    this.chrome?.kill("SIGKILL");
    this.chrome = null;
  }

  async userMedia(constraints: Json): Promise<ChromeStream> {
    await this.start();
    const opened = await this.call("gum", constraints);
    const streamId = opened.streamId as number;
    const tracks = (opened.tracks as { id: number; kind: string }[]).map(
      (track) => new ChromeTrack(this, track.id, track.kind),
    );
    return new ChromeStream(streamId, tracks);
  }

  createPeer(config: Json): ChromePeer {
    const peer = new ChromePeer(this, config);
    this.peers.add(peer);
    return peer;
  }

  async fingerprint(peer: ChromePeer): Promise<string | null> {
    if (peer.handle == null) return null;
    const result = await this.call("stats", { peer: peer.handle });
    const value = result.fingerprint;
    return typeof value === "string" && value.length > 0 ? value : null;
  }

  /** @internal */
  async call(name: string, payload: Json): Promise<Json> {
    await this.start();
    const encoded = Buffer.from(JSON.stringify(payload), "utf8").toString("base64");
    const expression = `window.__shroud.call(${JSON.stringify(name)}, ${JSON.stringify(encoded)})`;
    const evaluated = await this.cdp("Runtime.evaluate", {
      expression,
      awaitPromise: true,
      returnByValue: true,
    });
    const details = evaluated.exceptionDetails as { text?: string; exception?: { description?: string } } | undefined;
    if (details) {
      const text = details.exception?.description || details.text || "chrome call failed";
      throw new Error(text.split("\n")[0].slice(0, 180));
    }
    const remote = evaluated.result as { value?: Json } | undefined;
    return remote?.value ?? {};
  }

  /** @internal */
  noteIce(state: string): void {
    this.iceState = state;
  }

  private async launch(): Promise<void> {
    rmSync(this.profileDir, { recursive: true, force: true });
    mkdirSync(this.profileDir, { recursive: true });
    this.chrome = spawn(
      CHROME,
      [
        "--headless=new",
        "--remote-debugging-port=0",
        "--remote-allow-origins=*",
        `--user-data-dir=${this.profileDir}`,
        "--no-first-run",
        "--no-default-browser-check",
        "--use-fake-ui-for-media-stream",
        "--use-fake-device-for-media-stream",
        "--autoplay-policy=no-user-gesture-required",
        "--disable-features=WebRtcHideLocalIpsWithMdns",
        "--no-sandbox",
      ],
      { stdio: "ignore" },
    );
    const stop = () => {
      this.chrome?.kill("SIGKILL");
    };
    process.once("exit", stop);
    process.once("SIGTERM", () => {
      stop();
      process.exit(0);
    });
    process.once("SIGINT", () => {
      stop();
      process.exit(0);
    });
    const port = await readDevtoolsPort(this.profileDir);
    const version = await getJson(`http://127.0.0.1:${port}/json/version`);
    const browserWs = String(version.webSocketDebuggerUrl || "");
    if (!browserWs) throw new Error("chrome did not open a devtools socket");
    this.socket = await openSocket(browserWs, (raw) => this.acceptMessage(raw));
    const origin = new URL(this.originUrl).origin;
    await this.cdp("Browser.grantPermissions", {
      origin,
      permissions: ["audioCapture", "videoCapture"],
    }).catch(() => undefined);
    const created = await this.cdp("Target.createTarget", { url: this.originUrl });
    const targetId = String(created.targetId || "");
    const attached = await this.cdp("Target.attachToTarget", { targetId, flatten: true });
    this.sessionId = String(attached.sessionId || "");
    if (!this.sessionId) throw new Error("chrome did not attach to the page");
    await this.cdp("Page.enable", {});
    await this.cdp("Runtime.enable", {});
    await this.waitUntilReady();
    await this.cdp("Runtime.addBinding", { name: "__shroudEmit" });
    await this.cdp("Runtime.evaluate", { expression: PAGE, awaitPromise: true });
  }

  /** The page script has to land after navigation, or the next load drops it. */
  private async waitUntilReady(): Promise<void> {
    for (let attempt = 0; attempt < 50; attempt += 1) {
      const ready = await this.cdp("Runtime.evaluate", {
        expression: "document.readyState",
        returnByValue: true,
      });
      const value = (ready.result as { value?: string } | undefined)?.value;
      if (value === "interactive" || value === "complete") return;
      await new Promise((resolve) => setTimeout(resolve, 100));
    }
    throw new Error("chrome page did not start");
  }

  private cdp(method: string, params: Json): Promise<Json> {
    const socket = this.socket;
    if (!socket) return Promise.reject(new Error("chrome is not open"));
    const id = ++this.next;
    const message: Json = { id, method, params };
    if (this.sessionId && !method.startsWith("Browser.") && !method.startsWith("Target.")) {
      message.sessionId = this.sessionId;
    }
    if (method === "Target.attachToTarget" || method === "Target.createTarget" || method.startsWith("Browser.")) {
      delete message.sessionId;
    }
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        if (!this.pending.has(id)) return;
        this.pending.delete(id);
        reject(new Error(`devtools timed out: ${method}`));
      }, 20_000);
      this.pending.set(id, {
        resolve: (value) => {
          clearTimeout(timer);
          resolve(value);
        },
        reject: (error) => {
          clearTimeout(timer);
          reject(error);
        },
      });
      socket.send(JSON.stringify(message));
    });
  }

  /** @internal */
  acceptMessage(raw: string): void {
    let message: Json;
    try {
      message = JSON.parse(raw) as Json;
    } catch {
      return;
    }
    const id = message.id as number | undefined;
    if (id != null && this.pending.has(id)) {
      const waiter = this.pending.get(id)!;
      this.pending.delete(id);
      if (message.error) {
        const text = String((message.error as { message?: string }).message || "devtools call failed");
        waiter.reject(new Error(text.slice(0, 180)));
      } else {
        waiter.resolve((message.result as Json) || {});
      }
      return;
    }
    if (message.method === "Runtime.bindingCalled") {
      const params = message.params as { name?: string; payload?: string };
      if (params?.name !== "__shroudEmit" || !params.payload) return;
      let event: Json;
      try {
        event = JSON.parse(params.payload) as Json;
      } catch {
        return;
      }
      for (const peer of this.peers) peer.accept(event);
    }
  }
}

export class ChromeTrack {
  private enabledValue = true;
  private hint = "";
  readyState: "live" | "ended" = "live";
  constructor(
    private readonly media: ChromeMedia,
    readonly id: number,
    readonly kind: string,
  ) {}

  get enabled(): boolean {
    return this.enabledValue;
  }

  set enabled(value: boolean) {
    this.enabledValue = value;
    this.push();
  }

  get contentHint(): string {
    return this.hint;
  }

  set contentHint(value: string) {
    this.hint = value;
    this.push();
  }

  stop(): void {
    this.readyState = "ended";
    this.enabled = false;
  }

  getSettings(): Json {
    return this.kind === "video" ? { deviceId: "fake", width: 1280, height: 720, frameRate: 30 } : { deviceId: "fake" };
  }

  push(): void {
    if (this.id < 0) return;
    void this.media
      .call("track", { id: this.id, enabled: this.enabledValue, contentHint: this.hint })
      .catch(() => undefined);
  }
}

export class ChromeStream {
  constructor(
    readonly id: number,
    private readonly tracks: ChromeTrack[],
  ) {}

  getTracks(): ChromeTrack[] {
    return [...this.tracks];
  }

  getAudioTracks(): ChromeTrack[] {
    return this.tracks.filter((track) => track.kind === "audio");
  }

  getVideoTracks(): ChromeTrack[] {
    return this.tracks.filter((track) => track.kind === "video");
  }

  addTrack(track: ChromeTrack): void {
    if (!this.tracks.includes(track)) this.tracks.push(track);
  }
}

type SenderParams = { encodings?: Json[]; degradationPreference?: string };

export class ChromeSender {
  track: ChromeTrack | null;
  params: SenderParams = { encodings: [{}] };
  constructor(
    readonly peer: ChromePeer,
    track: ChromeTrack | null,
  ) {
    this.track = track;
  }

  getParameters(): SenderParams {
    return JSON.parse(JSON.stringify(this.params)) as SenderParams;
  }

  async setParameters(params: SenderParams): Promise<void> {
    this.params = JSON.parse(JSON.stringify(params)) as SenderParams;
    await this.peer.applyParameters(this, this.params);
  }

  async replaceTrack(next: ChromeTrack | null): Promise<void> {
    this.track = next;
    await this.peer.replaceTrack(this, next);
  }
}

export class ChromeTransceiver {
  mid: string | null = null;
  currentDirection: string | null = null;
  onWire: string | null = null;
  dirty = false;
  readonly receiver: { track: ChromeTrack | null };
  private directionValue: string;

  constructor(
    readonly peer: ChromePeer,
    readonly kind: string,
    direction: string,
    readonly sender: ChromeSender,
  ) {
    this.directionValue = direction;
    this.receiver = { track: new ChromeTrack(peer.media, -1, kind) };
  }

  get direction(): string {
    return this.directionValue;
  }

  set direction(value: string) {
    this.directionValue = value;
    this.dirty = value !== this.onWire;
  }
}

type Op = { op: string; track?: number; stream?: number; kind?: string; direction?: string };

export class ChromePeer {
  handle: number | null = null;
  localDescription: Description | null = null;
  remoteDescription: Description | null = null;
  signalingState = "stable";
  connectionState = "new";
  iceConnectionState = "new";
  onicecandidate: ((event: { candidate: { candidate: string; sdpMid: string | null; sdpMLineIndex: number | null } | null }) => void) | null =
    null;
  ontrack: ((event: { track: ChromeTrack; transceiver: ChromeTransceiver }) => void) | null = null;
  onconnectionstatechange: (() => void) | null = null;
  oniceconnectionstatechange: (() => void) | null = null;
  readonly transceivers: ChromeTransceiver[] = [];
  private readonly ops: Op[] = [];
  private readonly paramQueue = new Map<ChromeSender, SenderParams>();
  private closed = false;

  constructor(
    readonly media: ChromeMedia,
    private config: Json,
  ) {}

  addTrack(track: ChromeTrack, stream: ChromeStream): ChromeSender {
    const sender = new ChromeSender(this, track);
    const transceiver = new ChromeTransceiver(this, track.kind, "sendrecv", sender);
    transceiver.onWire = "sendrecv";
    this.transceivers.push(transceiver);
    this.ops.push({ op: "addTrack", track: track.id, stream: stream.id });
    return sender;
  }

  addTransceiver(kind: string, init: { direction?: string; streams?: ChromeStream[] } = {}): ChromeTransceiver {
    const direction = init.direction ?? "sendrecv";
    const sender = new ChromeSender(this, null);
    const transceiver = new ChromeTransceiver(this, kind, direction, sender);
    transceiver.onWire = direction;
    this.transceivers.push(transceiver);
    this.ops.push({ op: "addTransceiver", kind, direction, stream: init.streams?.[0]?.id });
    return transceiver;
  }

  getTransceivers(): ChromeTransceiver[] {
    return [...this.transceivers];
  }

  getSenders(): ChromeSender[] {
    return this.transceivers.map((transceiver) => transceiver.sender);
  }

  getConfiguration(): Json {
    return { ...this.config };
  }

  setConfiguration(config: Json): void {
    this.config = { ...config };
    if (this.handle != null) void this.media.call("config", { peer: this.handle, config }).catch(() => undefined);
  }

  async createOffer(options?: { iceRestart?: boolean }): Promise<Description> {
    await this.flush();
    const offer = await this.media.call("offer", { peer: this.handle, iceRestart: options?.iceRestart === true });
    return { type: "offer", sdp: String(offer.sdp || "") };
  }

  async createAnswer(): Promise<Description> {
    await this.flush();
    const answer = await this.media.call("answer", { peer: this.handle });
    return { type: "answer", sdp: String(answer.sdp || "") };
  }

  async setLocalDescription(description: Description): Promise<void> {
    await this.flush();
    if (description.type === "rollback") {
      await this.media.call("local", { peer: this.handle, description });
      this.localDescription = null;
      this.signalingState = "stable";
      return;
    }
    const snap = await this.media.call("local", { peer: this.handle, description });
    this.readSnap(snap);
  }

  async setRemoteDescription(description: Description): Promise<void> {
    await this.flush();
    const snap = await this.media.call("remote", { peer: this.handle, description });
    this.readSnap(snap);
  }

  async addIceCandidate(candidate: Json | null): Promise<void> {
    if (this.handle == null) return;
    await this.media.call("candidate", { peer: this.handle, candidate });
  }

  close(): void {
    this.closed = true;
    this.signalingState = "closed";
    this.connectionState = "closed";
    const handle = this.handle;
    this.handle = null;
    if (handle != null) void this.media.call("close", { peer: handle }).catch(() => undefined);
  }

  async applyParameters(sender: ChromeSender, params: SenderParams): Promise<void> {
    if (this.handle == null) {
      this.paramQueue.set(sender, params);
      return;
    }
    const index = this.transceivers.findIndex((transceiver) => transceiver.sender === sender);
    if (index < 0) return;
    await this.media.call("parameters", { peer: this.handle, index, params }).catch(() => undefined);
  }

  async replaceTrack(sender: ChromeSender, track: ChromeTrack | null): Promise<void> {
    await this.flush();
    const index = this.transceivers.findIndex((transceiver) => transceiver.sender === sender);
    if (index < 0 || this.handle == null) return;
    await this.media.call("replace", { peer: this.handle, index, track: track?.id ?? null });
  }

  accept(event: Json): void {
    if (this.closed || event.peer !== this.handle) return;
    if (event.t === "ice") {
      const candidate = event.candidate as { candidate?: string; sdpMid?: string | null; sdpMLineIndex?: number | null } | null;
      if (candidate?.candidate && !forwardable(candidate.candidate)) return;
      this.onicecandidate?.({
        candidate: candidate?.candidate
          ? {
              candidate: candidate.candidate,
              sdpMid: candidate.sdpMid ?? null,
              sdpMLineIndex: candidate.sdpMLineIndex ?? null,
            }
          : null,
      });
      return;
    }
    if (event.t === "state") {
      this.connectionState = String(event.connectionState || this.connectionState);
      this.iceConnectionState = String(event.iceConnectionState || this.iceConnectionState);
      this.signalingState = String(event.signalingState || this.signalingState);
      this.media.noteIce(this.iceConnectionState);
      if (event.which === "connection") this.onconnectionstatechange?.();
      else this.oniceconnectionstatechange?.();
      return;
    }
    if (event.t === "track") {
      const index = event.index as number;
      const transceiver = this.transceivers[index];
      const track = new ChromeTrack(this.media, event.track as number, String(event.kind || "video"));
      if (transceiver) transceiver.receiver.track = track;
      if (transceiver) this.ontrack?.({ track, transceiver });
    }
  }

  private async flush(): Promise<void> {
    if (this.closed) return;
    if (this.handle == null) {
      const created = await this.media.call("create", { config: this.config });
      this.handle = created.peer as number;
    }
    const directions = this.transceivers
      .map((transceiver, index) => ({ index, direction: transceiver.direction, dirty: transceiver.dirty }))
      .filter((row) => row.dirty);
    const parameters = [...this.paramQueue.entries()].map(([sender, params]) => ({
      index: this.transceivers.findIndex((transceiver) => transceiver.sender === sender),
      params,
    }));
    this.paramQueue.clear();
    if (this.ops.length === 0 && directions.length === 0 && parameters.length === 0) return;
    const snap = await this.media.call("setup", {
      peer: this.handle,
      ops: this.ops.splice(0),
      directions,
      parameters: parameters.filter((row) => row.index >= 0),
    });
    for (const transceiver of this.transceivers) {
      if (transceiver.dirty) {
        transceiver.onWire = transceiver.direction;
        transceiver.dirty = false;
      }
    }
    this.readSnap(snap);
  }

  private readSnap(snap: Json): void {
    const local = snap.localDescription as Description | null;
    const remote = snap.remoteDescription as Description | null;
    this.localDescription = local?.sdp ? local : null;
    this.remoteDescription = remote?.sdp ? remote : null;
    if (typeof snap.signalingState === "string") this.signalingState = snap.signalingState;
    if (typeof snap.connectionState === "string") this.connectionState = snap.connectionState;
    if (typeof snap.iceConnectionState === "string") {
      this.iceConnectionState = snap.iceConnectionState;
      this.media.noteIce(this.iceConnectionState);
    }
    const rows = (snap.transceivers as Json[] | undefined) ?? [];
    rows.forEach((row, index) => {
      let transceiver = this.transceivers[index];
      if (!transceiver) {
        const kind = String(row.kind || "video");
        const sender = new ChromeSender(this, null);
        transceiver = new ChromeTransceiver(this, kind, String(row.direction || "recvonly"), sender);
        this.transceivers.push(transceiver);
      }
      transceiver.mid = (row.mid as string | null) ?? null;
      transceiver.currentDirection = (row.currentDirection as string | null) ?? null;
      const wire = String(row.direction || transceiver.direction);
      if (!transceiver.dirty) {
        transceiver.onWire = wire;
        transceiver.direction = wire;
      }
      const params = row.parameters as SenderParams | undefined;
      if (params?.encodings && params.encodings.length > 0) transceiver.sender.params = params;
      if (row.kind && transceiver.receiver.track && transceiver.receiver.track.id < 0) {
        transceiver.receiver.track = new ChromeTrack(this.media, -1, String(row.kind));
      }
    });
  }
}

function forwardable(candidate: string): boolean {
  if (candidate.includes(".local")) return false;
  const ip = candidate.split(" ")[4] ?? "";
  return ip.length > 0 && !ip.includes(":");
}

function readDevtoolsPort(profileDir: string): Promise<number> {
  return new Promise((resolve, reject) => {
    const file = `${profileDir}/DevToolsActivePort`;
    let tries = 0;
    const tick = () => {
      tries += 1;
      try {
        const text = readFileSync(file, "utf8");
        const port = Number(text.split("\n")[0]);
        if (port > 0) {
          resolve(port);
          return;
        }
      } catch {
        /* not written yet */
      }
      if (tries > 100) reject(new Error("chrome did not open a devtools port"));
      else setTimeout(tick, 100);
    };
    tick();
  });
}

function getJson(url: string): Promise<Json> {
  return new Promise((resolve, reject) => {
    const req = httpRequest(url, (res) => {
      const chunks: Buffer[] = [];
      res.on("data", (chunk) => chunks.push(chunk as Buffer));
      res.on("end", () => {
        try {
          resolve(JSON.parse(Buffer.concat(chunks).toString("utf8")) as Json);
        } catch (error) {
          reject(error instanceof Error ? error : new Error("bad devtools response"));
        }
      });
    });
    req.on("error", reject);
    req.end();
  });
}

function openSocket(url: string, onMessage: (raw: string) => void): Promise<Socket> {
  const WS = (globalThis as unknown as { WebSocket: new (url: string) => Socket }).WebSocket;
  return new Promise((resolve, reject) => {
    const socket = new WS(url);
    let opened = false;
    socket.addEventListener("message", (ev) => {
      const data = ev.data;
      const text = typeof data === "string" ? data : data instanceof Uint8Array ? new TextDecoder().decode(data) : String(data ?? "");
      onMessage(text);
    });
    socket.addEventListener("error", () => {
      if (!opened) reject(new Error("devtools socket closed"));
    });
    let tries = 0;
    const wait = () => {
      const state = (socket as unknown as { readyState?: number }).readyState;
      if (state === 1) {
        opened = true;
        resolve(socket);
        return;
      }
      if (++tries > 50) {
        reject(new Error("devtools socket did not open"));
        return;
      }
      setTimeout(wait, 20);
    };
    wait();
  });
}

// The page owns the peer connections. Node calls window.__shroud.call; events come back
// through the DevTools binding. Candidate addresses are not written to the console.
const PAGE = String.raw`
(() => {
  const peers = new Map();
  const tracks = new Map();
  const streams = new Map();
  let n = 1;
  const id = () => n++;
  function snap(pc) {
    return {
      localDescription: pc.localDescription ? { type: pc.localDescription.type, sdp: pc.localDescription.sdp } : null,
      remoteDescription: pc.remoteDescription ? { type: pc.remoteDescription.type, sdp: pc.remoteDescription.sdp } : null,
      signalingState: pc.signalingState,
      connectionState: pc.connectionState,
      iceConnectionState: pc.iceConnectionState,
      transceivers: pc.getTransceivers().map((t) => {
        let parameters = { encodings: [] };
        try { parameters = JSON.parse(JSON.stringify(t.sender.getParameters())); } catch (e) {}
        return { mid: t.mid, direction: t.direction, currentDirection: t.currentDirection, kind: t.receiver.track ? t.receiver.track.kind : null, parameters };
      }),
    };
  }
  function emit(payload) {
    globalThis.__shroudEmit(JSON.stringify(payload));
  }
  function watch(peer, pc) {
    pc.onicecandidate = (event) => {
      const c = event.candidate;
      emit({ t: "ice", peer, candidate: c ? { candidate: c.candidate, sdpMid: c.sdpMid, sdpMLineIndex: c.sdpMLineIndex } : null });
    };
    pc.onconnectionstatechange = () => emit({ t: "state", which: "connection", peer, connectionState: pc.connectionState, iceConnectionState: pc.iceConnectionState, signalingState: pc.signalingState });
    pc.oniceconnectionstatechange = () => emit({ t: "state", which: "ice", peer, connectionState: pc.connectionState, iceConnectionState: pc.iceConnectionState, signalingState: pc.signalingState });
    pc.ontrack = (event) => {
      const track = id();
      tracks.set(track, event.track);
      const index = pc.getTransceivers().indexOf(event.transceiver);
      emit({ t: "track", peer, track, kind: event.track.kind, index });
    };
  }
  globalThis.__shroud = {
    async call(name, b64) {
      const bytes = Uint8Array.from(atob(b64), (c) => c.charCodeAt(0));
      const body = JSON.parse(new TextDecoder().decode(bytes));
      if (name === "gum") {
        const stream = await navigator.mediaDevices.getUserMedia(body);
        const streamId = id();
        streams.set(streamId, stream);
        const opened = [];
        for (const track of stream.getTracks()) {
          const handle = id();
          tracks.set(handle, track);
          opened.push({ id: handle, kind: track.kind });
        }
        return { streamId, tracks: opened };
      }
      if (name === "track") {
        const track = tracks.get(body.id);
        if (track) {
          if (typeof body.enabled === "boolean") track.enabled = body.enabled;
          if (typeof body.contentHint === "string") track.contentHint = body.contentHint;
        }
        return { ok: true };
      }
      if (name === "create") {
        const peer = id();
        const pc = new RTCPeerConnection(body.config);
        peers.set(peer, pc);
        watch(peer, pc);
        return { peer };
      }
      const pc = peers.get(body.peer);
      if (!pc) return { missing: true };
      if (name === "setup") {
        for (const op of body.ops || []) {
          if (op.op === "addTrack") pc.addTrack(tracks.get(op.track), streams.get(op.stream));
          else pc.addTransceiver(op.kind, { direction: op.direction, streams: op.stream ? [streams.get(op.stream)] : [] });
        }
        const list = pc.getTransceivers();
        for (const row of body.directions || []) if (list[row.index]) list[row.index].direction = row.direction;
        for (const row of body.parameters || []) {
          const sender = list[row.index] && list[row.index].sender;
          if (sender) await sender.setParameters(row.params).catch(() => undefined);
        }
        return snap(pc);
      }
      if (name === "offer") return { type: "offer", sdp: (await pc.createOffer(body.iceRestart ? { iceRestart: true } : undefined)).sdp };
      if (name === "answer") return { type: "answer", sdp: (await pc.createAnswer()).sdp };
      if (name === "local") {
        await pc.setLocalDescription(body.description);
        return snap(pc);
      }
      if (name === "remote") {
        await pc.setRemoteDescription(body.description);
        return snap(pc);
      }
      if (name === "candidate") {
        if (body.candidate) await pc.addIceCandidate(body.candidate);
        return { ok: true };
      }
      if (name === "config") {
        pc.setConfiguration(body.config);
        return { ok: true };
      }
      if (name === "parameters") {
        const sender = pc.getTransceivers()[body.index] && pc.getTransceivers()[body.index].sender;
        if (sender) await sender.setParameters(body.params);
        return { ok: true };
      }
      if (name === "replace") {
        const sender = pc.getTransceivers()[body.index] && pc.getTransceivers()[body.index].sender;
        if (sender) await sender.replaceTrack(body.track == null ? null : tracks.get(body.track) || null);
        return { ok: true };
      }
      if (name === "close") {
        pc.close();
        return { ok: true };
      }
      if (name === "stats") {
        const stats = await pc.getStats();
        let remoteId = null;
        for (const report of stats.values()) {
          if (report.type === "transport" && typeof report.remoteCertificateId === "string") remoteId = report.remoteCertificateId;
        }
        const cert = remoteId ? stats.get(remoteId) : null;
        if (!cert || String(cert.fingerprintAlgorithm || "").toLowerCase() !== "sha-256") return { fingerprint: null };
        return { fingerprint: cert.fingerprint || null };
      }
      return { unknown: name };
    },
  };
})();
`;
