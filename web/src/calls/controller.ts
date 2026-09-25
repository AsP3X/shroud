import { ApiError, type CallInfo, type CallModality, type CallSignalType, type IceServer } from "../api/client";
import type { RealtimeEvent } from "../realtime";
import { callKeys, deriveCallSecret, openSignal, sealSignal, type CallKeys, type CallRole } from "./crypto";
import {
  CAMERA_UNAVAILABLE,
  CONNECT_TIMEOUT_MS,
  CallFailure,
  DISCONNECTED_GRACE_MS,
  ENDED_VISIBLE_MS,
  ERROR_VISIBLE_MS,
  HEARTBEAT_MS,
  ICE_BATCH_MAX,
  ICE_BATCH_MS,
  INCOMING_RING_LIMIT_MS,
  NOTICE_VISIBLE_MS,
  OUTGOING_RING_LIMIT_MS,
  RECONNECT_LIMIT_MS,
  RestartGate,
  SeenSignals,
  cameraOnlyFailure,
  callErrorText,
  endedText,
  isLive,
  linkState,
  mediaErrorText,
  readSignal,
  sameId,
  signalTypeOf,
  voiceSdp,
  type CallPeer,
  type CallPhase,
  type CallView,
  type IceCandidateJson,
  type Signal,
  type SignalBody,
} from "./logic";

/*
 * One device's side of 1:1 calls (docs/calls.md, protocol 2).
 *
 *   idle ─start─▶ outgoing ─call.accepted─▶ connecting ─media─▶ active ─▶ ended ─2 s─▶ idle
 *   idle ─call.ring─▶ incoming ─Accept─▶ connecting ─media─▶ active ─▶ ended ─2 s─▶ idle
 *
 * Any state can end: a hangup here or there, a decline, the ring running out, another of our
 * devices answering, no media within 30 s, ICE that does not recover. The caller always offers,
 * ICE restarts included; the callee asks for one with `restart`. Every signal is sealed
 * (crypto.ts) and numbered, and every event handler is idempotent: the server may deliver an
 * event twice, and replays a ring after each reconnect.
 *
 * Browser APIs come in through `CallEnv`, so the selftest can run two controllers against a fake
 * server and fake peer connections.
 */

/** The call endpoints, with the session's token bound. */
export type CallApi = {
  iceServers(): Promise<IceServer[]>;
  createCall(peerUserId: string, modality: CallModality): Promise<CallInfo>;
  getCall(callId: string): Promise<CallInfo>;
  acceptCall(callId: string): Promise<CallInfo>;
  rejectCall(callId: string): Promise<CallInfo>;
  hangupCall(callId: string, keepalive?: boolean): Promise<CallInfo>;
  sendSignal(callId: string, signalType: CallSignalType, payload: string): Promise<void>;
  heartbeat(callId: string): Promise<CallInfo>;
};

export type IdentityKeys = { privateKey: Uint8Array; publicKey: Uint8Array };

/** The signed-in account a controller calls for. */
export type CallAccount = {
  userId: string;
  deviceId: string;
  api: CallApi;
  /** Our identity key pair, a copy the controller may wipe; null while it cannot be read. */
  identity: () => IdentityKeys | null;
  /** A peer's identity public key. */
  peerKey: (userId: string) => Promise<Uint8Array>;
  /** A contact's name, where the server sent none. */
  peerName: (userId: string) => string | null;
};

/** Everything outside the controller. */
export type CallEnv = {
  /** Why this browser cannot call at all, or null. */
  unsupported(): string | null;
  getUserMedia(constraints: MediaStreamConstraints): Promise<MediaStream>;
  /** Device ids of the cameras. */
  cameras(): Promise<string[]>;
  createPeer(config: RTCConfiguration): RTCPeerConnection;
  createStream(tracks: MediaStreamTrack[]): MediaStream;
  now(): number;
  setTimeout(run: () => void, ms: number): number;
  clearTimeout(id: number): void;
  setInterval(run: () => void, ms: number): number;
  clearInterval(id: number): void;
  publish(view: CallView | null): void;
  tone(kind: "ringtone" | "ringback" | null): void;
  /** Plays the other side's audio (null stops it); false when the browser wants a click first. */
  playAudio(stream: MediaStream | null): Promise<boolean>;
  keepAwake(on: boolean): void;
  holdAutoLock(): () => void;
  /** A call takes the microphone: voice notes stop playing and recording. */
  interruptVoice(): void;
  /** The notification for a ring nobody is watching, or for one missed; null takes it down. */
  notifyRing(ring: { peer: CallPeer; modality: CallModality; missed: boolean } | null): void;
  /** Other tabs of this browser (the same device) stop ringing for this call. */
  tellTabs(callId: string): void;
};

/** 720p at most, front camera first. */
const VIDEO: MediaTrackConstraints = {
  width: { ideal: 1280 },
  height: { ideal: 720 },
  frameRate: { ideal: 30, max: 30 },
};
const AUDIO: MediaTrackConstraints = {
  echoCancellation: true,
  noiseSuppression: true,
  autoGainControl: true,
  channelCount: { ideal: 1 },
};
const AUDIO_MAX_BPS = 32_000;
const VIDEO_MAX_BPS = 1_200_000;

type TimerKey =
  | "ringTimer"
  | "connectTimer"
  | "graceTimer"
  | "reconnectTimer"
  | "restartTimer"
  | "batchTimer"
  | "noticeTimer"
  | "endTimer";
type IntervalKey = "heartbeat" | "ringCheck";

type Call = {
  key: number;
  /** The server's id (lowercase); null while an outgoing ring is being placed. */
  id: string | null;
  role: CallRole;
  modality: CallModality;
  peer: CallPeer;
  /** The other device in the call (the caller's, or the callee's once it answered). */
  peerDevice: string | null;
  phase: CallPhase;
  dialing: boolean;
  /** Callee: Accept was pressed and the answer has not been confirmed yet. */
  accepting: boolean;
  keys: CallKeys | null;
  sent: number;
  seen: SeenSignals;
  pc: RTCPeerConnection | null;
  /** The servers handed to the peer connection, kept so a relay fallback can set them again. */
  iceServers: RTCIceServer[];
  /** A failed link has asked for the TURN relay. Later restarts, including a delayed one, keep it. */
  wantRelay: boolean;
  triedRelay: boolean;
  local: MediaStream | null;
  remote: MediaStream | null;
  /** Caller: the offer made while it rang; true once it is the local description. */
  offerReady: Promise<boolean> | null;
  /** The offer (caller) or answer (callee) went: candidates and media state may follow. */
  negotiated: boolean;
  outbox: Promise<void>;
  inbox: Promise<void>;
  /** Events that came before the call could take them (an id not known yet, no peer yet). */
  early: RealtimeEvent[];
  gathered: IceCandidateJson[];
  /** Remote candidates that came before the remote description. */
  pendingRemote: IceCandidateJson[];
  restartGate: RestartGate;
  switching: boolean;
  micOn: boolean;
  cameraOn: boolean;
  hasCamera: boolean;
  canSwitchCamera: boolean;
  mirrorSelf: boolean;
  remoteMic: boolean;
  remoteCamera: boolean;
  remoteVideo: boolean;
  audioBlocked: boolean;
  connectedAt: number | null;
  reconnecting: boolean;
  endedText: string | null;
  notice: string | null;
  minimized: boolean;
  releaseHold: (() => void) | null;
} & Record<TimerKey | IntervalKey, number | null>;

function stopTracks(stream: MediaStream | null): void {
  for (const track of stream?.getTracks() ?? []) track.stop();
}

function facingOf(track: MediaStreamTrack | undefined): string | undefined {
  return track?.getSettings?.().facingMode;
}

function text(value: unknown): string {
  return typeof value === "string" ? value : "";
}

/** A call object from an event, when it has what every handler reads. */
function readCall(raw: unknown): CallInfo | null {
  if (!raw || typeof raw !== "object") return null;
  const call = raw as Partial<CallInfo>;
  for (const field of ["id", "caller_user_id", "caller_device_id", "callee_user_id", "status"] as const) {
    if (typeof call[field] !== "string" || !call[field]) return null;
  }
  return call as CallInfo;
}

function toRtcServer(server: IceServer): RTCIceServer {
  return server.username || server.credential
    ? { urls: server.urls, username: server.username, credential: server.credential }
    : { urls: server.urls };
}

function hasTurnServer(servers: RTCIceServer[]): boolean {
  for (const server of servers) {
    const urls = Array.isArray(server.urls) ? server.urls : [server.urls];
    for (const url of urls) {
      if (/^turns?:/i.test(url)) return true;
    }
  }
  return false;
}

function peerConfig(servers: RTCIceServer[], relay: boolean): RTCConfiguration {
  return {
    iceServers: servers,
    bundlePolicy: "max-bundle",
    rtcpMuxPolicy: "require",
    iceCandidatePoolSize: 1,
    iceTransportPolicy: relay ? "relay" : "all",
  };
}

function withVoice(description: RTCSessionDescriptionInit): RTCSessionDescriptionInit {
  return { type: description.type, sdp: voiceSdp(description.sdp ?? "") };
}

/** Speech at about 32 kbps; video at about 1.2 Mbps, 30 fps, shedding rate and detail together. */
function tuneSenders(pc: RTCPeerConnection): void {
  if (typeof pc.getSenders !== "function") return;
  for (const sender of pc.getSenders()) {
    const track = sender.track;
    if (!track || typeof sender.getParameters !== "function" || typeof sender.setParameters !== "function") continue;
    try {
      const params = sender.getParameters();
      const encoding = params.encodings?.[0];
      if (!encoding) continue;
      if (track.kind === "audio") {
        encoding.maxBitrate = AUDIO_MAX_BPS;
        encoding.priority = "high";
        encoding.networkPriority = "high";
      } else if (track.kind === "video") {
        encoding.maxBitrate = VIDEO_MAX_BPS;
        encoding.maxFramerate = 30;
        // Below speech, so a tight link fills the microphone before the camera.
        encoding.priority = "low";
        encoding.networkPriority = "low";
        params.degradationPreference = "balanced";
      } else {
        continue;
      }
      void Promise.resolve(sender.setParameters(params)).catch(() => undefined);
    } catch {
      /* a sender that cannot be tuned still sends */
    }
  }
}

export class CallController {
  private account: CallAccount | null = null;
  private call: Call | null = null;
  private nextKey = 1;
  /** Calls this device is done with: a replayed ring or a late event cannot bring one back. */
  private readonly finished: string[] = [];

  constructor(private readonly env: CallEnv) {}

  /** The signed-in account; calls start and rings are heard only while one is set. */
  configure(account: CallAccount): void {
    this.account = account;
  }

  /**
   * Ends whatever call there is, at once and without an ended screen: one this device is in
   * hangs up, a ring only stops here (our other devices may still answer it).
   */
  endNow(): void {
    const call = this.call;
    if (!call) return;
    this.finish(call, null, this.inCall(call) ? "hangup" : null);
    this.idle(call);
  }

  /** The chats close (a lock, a sign-out): the call ends and no ring is heard until configured. */
  release(): void {
    this.endNow();
    this.account = null;
  }

  /** The page is going away: the other side hears at once, not after the server's 45 s. */
  pageHide(): void {
    const call = this.call;
    const account = this.account;
    if (!call || !account || call.phase === "ended") return;
    if (call.id && this.inCall(call)) void account.api.hangupCall(call.id, true).catch(() => undefined);
    this.finish(call, null, null);
  }

  /** Busy here: ringing, being connected, or talking. */
  busy(): boolean {
    return this.call !== null && this.call.phase !== "ended";
  }

  /* --- actions from the call screen --------------------------------------------------------- */

  start(peer: CallPeer, modality: CallModality): void {
    const account = this.account;
    if (!account) return;
    // A call this browser still has open is ended first, so a leftover one cannot
    // swallow every later attempt.
    const current = this.call;
    if (current && current.phase !== "ended") {
      const notify = current.phase === "incoming" ? "reject" : current.id ? "hangup" : null;
      this.finish(current, null, notify);
    }
    if (this.busy()) return;
    const call = this.open({
      role: "caller",
      id: null,
      peer: { id: peer.id.toLowerCase(), username: peer.username },
      modality,
      phase: "outgoing",
      peerDevice: null,
    });
    call.dialing = true;
    this.env.interruptVoice();
    this.publish(call);
    const unsupported = this.env.unsupported();
    if (unsupported) {
      this.finish(call, unsupported, null, ERROR_VISIBLE_MS);
      return;
    }
    void this.dial(call, account);
  }

  accept(): void {
    const call = this.call;
    const account = this.account;
    if (!call || !account || call.phase !== "incoming" || !call.id) return;
    const unsupported = this.env.unsupported();
    if (unsupported) {
      this.note(call, unsupported);
      return;
    }
    call.phase = "connecting";
    call.accepting = true;
    call.notice = null;
    this.env.tone(null);
    this.env.notifyRing(null);
    this.env.interruptVoice();
    this.env.tellTabs(call.id);
    this.publish(call);
    void this.answer(call, account);
  }

  decline(): void {
    const call = this.call;
    if (!call || call.phase !== "incoming") return;
    if (call.id) this.env.tellTabs(call.id);
    this.finish(call, endedText("rejected", "rejected", "callee"), "reject");
  }

  hangup(): void {
    const call = this.call;
    if (!call || call.phase === "ended") return;
    if (call.phase === "incoming") {
      this.decline();
      return;
    }
    const ringing = call.role === "caller" && call.phase === "outgoing";
    this.finish(call, ringing ? endedText("cancelled", "cancelled", "caller") : "Call ended", "hangup");
  }

  toggleMute(): void {
    const call = this.live();
    if (!call) return;
    call.micOn = !call.micOn;
    for (const track of call.local?.getAudioTracks() ?? []) track.enabled = call.micOn;
    this.sendMediaState(call);
    this.publish(call);
  }

  toggleCamera(): void {
    const call = this.live();
    if (!call || !call.hasCamera) return;
    call.cameraOn = !call.cameraOn;
    for (const track of call.local?.getVideoTracks() ?? []) track.enabled = call.cameraOn;
    this.sendMediaState(call);
    this.publish(call);
  }

  /** The next camera: the other facing one on a phone, the next device elsewhere. */
  async switchCamera(): Promise<void> {
    const call = this.live();
    const local = call?.local;
    if (!call || !local || !call.hasCamera || !call.canSwitchCamera || call.switching) return;
    const old = local.getVideoTracks()[0];
    if (!old) return;
    call.switching = true;
    const facing = facingOf(old);
    const oldDevice = old.getSettings?.().deviceId;
    let wanted: MediaTrackConstraints;
    if (facing === "user" || facing === "environment") {
      wanted = { ...VIDEO, facingMode: { exact: facing === "user" ? "environment" : "user" } };
    } else {
      const ids = await this.env.cameras().catch(() => [] as string[]);
      const next = ids.length > 1 ? ids[(ids.indexOf(oldDevice ?? "") + 1) % ids.length] : undefined;
      wanted = next ? { ...VIDEO, deviceId: { exact: next } } : { ...VIDEO };
    }
    // Phones open one camera at a time: the old one closes first.
    old.stop();
    let fresh: MediaStreamTrack | null = null;
    for (const constraints of [wanted, oldDevice ? { ...VIDEO, deviceId: { exact: oldDevice } } : VIDEO]) {
      try {
        fresh = (await this.env.getUserMedia({ video: constraints })).getVideoTracks()[0] ?? null;
      } catch {
        fresh = null;
      }
      if (fresh) break;
    }
    call.switching = false;
    if (this.gone(call)) {
      fresh?.stop();
      return;
    }
    const sender = call.pc?.getSenders().find((s) => s.track === old || s.track?.kind === "video") ?? null;
    if (!fresh) {
      // Neither camera opens now: the call goes on without video.
      call.hasCamera = false;
      call.cameraOn = false;
      await sender?.replaceTrack(null).catch(() => undefined);
      call.local = this.env.createStream(local.getAudioTracks());
      this.sendMediaState(call);
      this.note(call, CAMERA_UNAVAILABLE);
      return;
    }
    fresh.enabled = call.cameraOn;
    await sender?.replaceTrack(fresh).catch(() => undefined);
    if (this.gone(call)) {
      fresh.stop();
      return;
    }
    call.local = this.env.createStream([...local.getAudioTracks(), fresh]);
    call.mirrorSelf = facingOf(fresh) !== "environment";
    this.publish(call);
  }

  setMinimized(minimized: boolean): void {
    const call = this.call;
    if (!call || call.minimized === minimized) return;
    call.minimized = minimized;
    this.publish(call);
  }

  /** A click the browser can count as permission to play the other side's audio. */
  resumeAudio(): void {
    const call = this.call;
    if (call && !this.gone(call) && call.remote) void this.playRemote(call);
  }

  /** Closes the ended screen early. */
  dismiss(): void {
    const call = this.call;
    if (call?.phase === "ended") this.idle(call);
  }

  /** Another tab of this browser answered or declined the ring: it stops here, quietly. */
  takenElsewhere(callId: string): void {
    const call = this.call;
    if (call && call.phase === "incoming" && sameId(call.id, callId)) this.finish(call, null, null);
  }

  /* --- socket events --------------------------------------------------------------------------- */

  handle(event: RealtimeEvent): void {
    if (!this.account) return;
    switch (event.type) {
      case "auth.ok":
        // Back after a reconnect: whatever was missed meanwhile is read from the server.
        if (this.call?.id && !this.gone(this.call)) this.check(this.call);
        return;
      case "call.ring":
        this.onRing(readCall(event.raw.call));
        return;
      case "call.accepted":
        this.onAccepted(event, readCall(event.raw.call));
        return;
      case "call.ended":
        this.onEnded(event, readCall(event.raw.call));
        return;
      case "call.signal":
        this.onSignal(event);
        return;
    }
  }

  private onRing(info: CallInfo | null): void {
    const account = this.account;
    if (!account || !info) return;
    // Protocol 1 (older apps) sent its offer with the ring; this client only answers protocol 2.
    if (info.protocol !== 2) return;
    // Our other device calling someone.
    if (sameId(info.caller_user_id, account.userId) || !sameId(info.callee_user_id, account.userId)) return;
    const id = info.id.toLowerCase();
    if (info.status !== "ringing" || this.finished.includes(id)) return;
    if (this.call && (sameId(this.call.id, id) || this.busy())) return;
    const peer = {
      id: info.caller_user_id.toLowerCase(),
      username: info.caller_username ?? account.peerName(info.caller_user_id) ?? "Unknown",
    };
    const call = this.open({
      role: "callee",
      id,
      peer,
      modality: info.modality === "video" ? "video" : "voice",
      phase: "incoming",
      peerDevice: info.caller_device_id.toLowerCase(),
    });
    this.env.tone("ringtone");
    this.env.notifyRing({ peer, modality: call.modality, missed: false });
    call.ringTimer = this.env.setTimeout(() => this.finish(call, "Missed call", null), INCOMING_RING_LIMIT_MS);
    // A missed end event (a socket gap) must not leave it ringing.
    call.ringCheck = this.env.setInterval(() => this.check(call), HEARTBEAT_MS);
    this.publish(call);
  }

  /** An outgoing call whose id is not known yet may be the one an event is about. */
  private mayBeOurs(call: Call, info: CallInfo): boolean {
    return (
      call.dialing &&
      sameId(info.caller_device_id, this.account?.deviceId) &&
      sameId(info.callee_user_id, call.peer.id)
    );
  }

  private onAccepted(event: RealtimeEvent, info: CallInfo | null): void {
    const call = this.call;
    if (!call || !info || this.gone(call)) return;
    if (!call.id) {
      if (this.mayBeOurs(call, info)) call.early.push(event);
      return;
    }
    if (!sameId(call.id, info.id)) return;
    if (call.role === "caller") this.answered(call, info);
    else if (!sameId(info.callee_device_id, this.account?.deviceId)) {
      this.finish(call, "Answered on another device", null);
    }
  }

  private onEnded(event: RealtimeEvent, info: CallInfo | null): void {
    const call = this.call;
    if (!call || !info || this.gone(call)) return;
    if (!call.id) {
      if (this.mayBeOurs(call, info)) call.early.push(event);
      return;
    }
    if (sameId(call.id, info.id)) this.finish(call, endedText(info.status, info.ended_reason, call.role), null);
  }

  private onSignal(event: RealtimeEvent): void {
    const call = this.call;
    if (!call || this.gone(call)) return;
    const raw = event.raw;
    const from = text(raw.from_device_id);
    const type = text(raw.signal_type);
    const payload = text(raw.payload);
    if (!from || !type || !payload) return;
    if (!sameId(call.id, text(raw.call_id)) || !sameId(text(raw.from_user_id), call.peer.id)) return;
    // Only the other device in the call signals; the server says so too, but it is cheap to check.
    if (call.peerDevice && !sameId(from, call.peerDevice)) return;
    if (!call.pc || !call.keys) {
      call.early.push(event);
      return;
    }
    call.inbox = call.inbox.then(() => this.receive(call, from, type, payload)).catch(() => undefined);
  }

  /* --- placing and answering ------------------------------------------------------------------- */

  private open(init: Pick<Call, "role" | "id" | "peer" | "modality" | "phase" | "peerDevice">): Call {
    // The new hold before the old goes, so a lock that came due meanwhile cannot slip in.
    const releaseHold = this.env.holdAutoLock();
    if (this.call) this.idle(this.call);
    const call: Call = {
      ...init,
      key: this.nextKey++,
      dialing: false,
      accepting: false,
      keys: null,
      sent: 0,
      seen: new SeenSignals(),
      pc: null,
      iceServers: [],
      wantRelay: false,
      triedRelay: false,
      local: null,
      remote: null,
      offerReady: null,
      negotiated: false,
      outbox: Promise.resolve(),
      inbox: Promise.resolve(),
      early: [],
      gathered: [],
      pendingRemote: [],
      restartGate: new RestartGate(),
      switching: false,
      micOn: true,
      cameraOn: init.modality === "video",
      hasCamera: false,
      canSwitchCamera: false,
      mirrorSelf: true,
      remoteMic: true,
      remoteCamera: init.modality === "video",
      remoteVideo: false,
      audioBlocked: false,
      connectedAt: null,
      reconnecting: false,
      endedText: null,
      notice: null,
      minimized: false,
      releaseHold,
      ringTimer: null,
      connectTimer: null,
      graceTimer: null,
      reconnectTimer: null,
      restartTimer: null,
      batchTimer: null,
      noticeTimer: null,
      endTimer: null,
      heartbeat: null,
      ringCheck: null,
    };
    this.call = call;
    return call;
  }

  private async dial(call: Call, account: CallAccount): Promise<void> {
    try {
      if (!(await this.openMedia(call, false))) return;
      const [secret, servers] = await Promise.all([this.secretFor(call, account), this.iceServers(account)]);
      if (this.gone(call)) {
        secret.fill(0);
        return;
      }
      this.buildPeer(call, servers);
      call.offerReady = this.prepareOffer(call);
      let info: CallInfo;
      try {
        info = await account.api.createCall(call.peer.id, call.modality);
      } catch (err) {
        secret.fill(0);
        throw err;
      }
      if (this.gone(call)) {
        secret.fill(0);
        // Hung up while the ring was being placed: take it back.
        void account.api.hangupCall(info.id).catch(() => undefined);
        return;
      }
      const id = info.id.toLowerCase();
      call.keys = await callKeys(secret, id, "caller");
      secret.fill(0);
      if (this.gone(call)) {
        void account.api.hangupCall(id).catch(() => undefined);
        return;
      }
      call.id = id;
      call.dialing = false;
      this.env.tone("ringback");
      this.beat(call);
      call.ringTimer = this.env.setTimeout(() => this.finish(call, "No answer", "hangup"), OUTGOING_RING_LIMIT_MS);
      this.publish(call);
      // A quick answer or decline that raced the ring's own response.
      for (const event of call.early.splice(0)) this.handle(event);
    } catch (err) {
      if (!this.gone(call)) this.finish(call, callErrorText(err, call.peer.username), null, ERROR_VISIBLE_MS);
    }
  }

  private async answer(call: Call, account: CallAccount): Promise<void> {
    const id = call.id!;
    try {
      if (!(await this.openMedia(call, true))) return;
    } catch (err) {
      // Nothing was sent yet: back to ringing, so the user can fix the permission and answer,
      // or decline. Our other devices keep ringing meanwhile.
      if (this.gone(call)) return;
      call.phase = "incoming";
      call.accepting = false;
      call.notice = callErrorText(err, call.peer.username);
      this.publish(call);
      return;
    }
    try {
      const [secret, servers] = await Promise.all([this.secretFor(call, account), this.iceServers(account)]);
      if (this.gone(call)) {
        secret.fill(0);
        return;
      }
      call.keys = await callKeys(secret, id, "callee");
      secret.fill(0);
      if (this.gone(call)) return;
      this.buildPeer(call, servers);
      try {
        await account.api.acceptCall(id);
      } catch (err) {
        if (this.gone(call)) return;
        // Stopped ringing meanwhile: ended, or answered on our other device.
        const info = await account.api.getCall(id).catch(() => null);
        if (this.gone(call)) return;
        if (info?.status === "active") {
          this.finish(call, "Answered on another device", null);
        } else if (info && !isLive(info.status)) {
          this.finish(call, endedText(info.status, info.ended_reason, "callee"), null);
        } else {
          const why =
            err instanceof ApiError && err.code === "transport"
              ? callErrorText(err, call.peer.username)
              : "Couldn’t answer the call.";
          this.finish(call, why, null, ERROR_VISIBLE_MS);
        }
        return;
      }
      if (this.gone(call)) return;
      call.accepting = false;
      this.stop(call, "ringTimer");
      this.stopInterval(call, "ringCheck");
      this.beat(call);
      // The offer can overtake this response, and media may be up already.
      if (call.phase === "connecting") {
        call.connectTimer = this.env.setTimeout(
          () => this.finish(call, "Couldn’t connect", "hangup"),
          CONNECT_TIMEOUT_MS,
        );
      }
      this.publish(call);
      // An offer can overtake the accept's own response.
      for (const event of call.early.splice(0)) this.onSignal(event);
    } catch (err) {
      if (!this.gone(call)) this.finish(call, callErrorText(err, call.peer.username), "hangup", ERROR_VISIBLE_MS);
    }
  }

  /** Opens the microphone (and camera); false when the call went away meanwhile. */
  private async openMedia(call: Call, answering: boolean): Promise<boolean> {
    let stream: MediaStream;
    let cameraFailed = false;
    try {
      if (call.modality === "video") {
        try {
          stream = await this.env.getUserMedia({ audio: AUDIO, video: { ...VIDEO, facingMode: "user" } });
        } catch (err) {
          // Blocked or missing camera: the call can still go on with sound.
          if (!cameraOnlyFailure(err)) throw err;
          stream = await this.env.getUserMedia({ audio: AUDIO });
          cameraFailed = true;
        }
      } else {
        stream = await this.env.getUserMedia({ audio: AUDIO });
      }
    } catch (err) {
      throw new CallFailure(mediaErrorText(err, answering));
    }
    if (this.gone(call)) {
      stopTracks(stream);
      return false;
    }
    call.local = stream;
    for (const track of stream.getAudioTracks()) {
      track.enabled = call.micOn;
      track.contentHint = "speech";
    }
    const video = stream.getVideoTracks()[0];
    call.hasCamera = Boolean(video);
    call.cameraOn = Boolean(video);
    call.mirrorSelf = facingOf(video) !== "environment";
    if (cameraFailed) this.note(call, CAMERA_UNAVAILABLE);
    if (video) {
      void this.env
        .cameras()
        .then((ids) => {
          if (this.gone(call)) return;
          call.canSwitchCamera = ids.length > 1;
          this.publish(call);
        })
        .catch(() => undefined);
    }
    this.publish(call);
    return true;
  }

  /** The pair's call secret; the identity copy is wiped once used. */
  private async secretFor(call: Call, account: CallAccount): Promise<Uint8Array> {
    const peerKey = await account.peerKey(call.peer.id);
    const identity = account.identity();
    if (!identity) throw new CallFailure("Unlock Shroud to call.");
    try {
      return deriveCallSecret(identity.privateKey, identity.publicKey, peerKey);
    } finally {
      identity.privateKey.fill(0);
    }
  }

  /** TURN logins are per call; without any, only direct paths can connect. */
  private iceServers(account: CallAccount): Promise<IceServer[]> {
    return account.api.iceServers().catch(() => []);
  }

  private buildPeer(call: Call, servers: IceServer[]): void {
    const rtcServers = servers.map(toRtcServer);
    call.iceServers = rtcServers;
    const pc = this.env.createPeer(peerConfig(rtcServers, false));
    call.pc = pc;
    const local = call.local;
    if (local) for (const track of local.getTracks()) pc.addTrack(track, local);
    // A video call without our camera still receives theirs.
    if (call.role === "caller" && call.modality === "video" && !call.hasCamera) {
      pc.addTransceiver("video", { direction: "recvonly" });
    }
    tuneSenders(pc);
    pc.onicecandidate = (event) => this.gatheredCandidate(call, event.candidate);
    pc.ontrack = (event) => this.remoteTrack(call, event.track);
    pc.onconnectionstatechange = () => this.linkChanged(call);
    pc.oniceconnectionstatechange = () => this.linkChanged(call);
  }

  /** The caller's offer, made while it rings so it is ready the moment the call is answered. */
  private async prepareOffer(call: Call): Promise<boolean> {
    const pc = call.pc;
    if (!pc) return false;
    try {
      const offer = withVoice(await pc.createOffer());
      if (this.gone(call)) return false;
      await pc.setLocalDescription(offer);
      tuneSenders(pc);
      return !this.gone(call);
    } catch {
      if (!this.gone(call)) this.finish(call, "Couldn’t start the call.", "hangup", ERROR_VISIBLE_MS);
      return false;
    }
  }

  /** Caller: the callee answered (the event, or a heartbeat that saw it first). */
  private answered(call: Call, info: CallInfo): void {
    if (call.phase !== "outgoing") return;
    call.peerDevice = info.callee_device_id?.toLowerCase() ?? null;
    call.phase = "connecting";
    this.stop(call, "ringTimer");
    this.env.tone(null);
    call.connectTimer = this.env.setTimeout(() => this.finish(call, "Couldn’t connect", "hangup"), CONNECT_TIMEOUT_MS);
    this.publish(call);
    void this.sendOffer(call);
  }

  private async sendOffer(call: Call): Promise<void> {
    const ready = await call.offerReady;
    if (!ready || this.gone(call) || !call.pc) return;
    const sdp = call.pc.localDescription?.sdp;
    if (!sdp) {
      this.finish(call, "Couldn’t connect", "hangup");
      return;
    }
    this.send(call, { t: "offer", sdp, restart: false });
    this.negotiated(call);
  }

  private negotiated(call: Call): void {
    call.negotiated = true;
    this.flushCandidates(call);
    this.sendMediaState(call);
  }

  /* --- signals ----------------------------------------------------------------------------- */

  /** Seals and sends one signal, in order behind the ones before it. */
  private send(call: Call, body: SignalBody): void {
    const id = call.id;
    const keys = call.keys;
    const api = this.account?.api;
    if (!id || !keys || !api) return;
    const type = signalTypeOf(body.t);
    call.sent += 1;
    const plaintext: Record<string, unknown> = { ...body, n: call.sent };
    call.outbox = call.outbox
      .then(async () => {
        if (this.gone(call)) return;
        const payload = await sealSignal(keys.send, id, type, plaintext);
        await this.deliver(call, api, id, type, payload);
      })
      .catch(() => undefined);
  }

  /** A few tries through a flaky network; a call the server ended is read back and ended here. */
  private async deliver(call: Call, api: CallApi, id: string, type: CallSignalType, payload: string): Promise<void> {
    for (let attempt = 0; ; attempt += 1) {
      if (this.gone(call)) return;
      try {
        await api.sendSignal(id, type, payload);
        return;
      } catch (err) {
        if (err instanceof ApiError && err.code === "CALL_ENDED") {
          this.check(call, "Call ended");
          return;
        }
        const retry =
          attempt < 2 &&
          (!(err instanceof ApiError) || err.code === "transport" || err.status >= 500 || err.status === 429);
        if (!retry) return;
        await new Promise<void>((resolve) => this.env.setTimeout(resolve, attempt === 0 ? 500 : 1500));
      }
    }
  }

  private async receive(call: Call, from: string, type: string, payload: string): Promise<void> {
    if (this.gone(call) || !call.keys || !call.id) return;
    let signal: Signal | null;
    try {
      signal = readSignal(type, await openSignal(call.keys.receive, call.id, type as CallSignalType, payload));
    } catch {
      return; // does not open: not from the peer, or tampered with
    }
    if (!signal || !call.seen.first(from, signal.n)) return;
    try {
      switch (signal.t) {
        case "offer":
          if (call.role === "callee") await this.answerOffer(call, signal.sdp);
          return;
        case "answer":
          if (call.role === "caller") await this.takeAnswer(call, signal.sdp);
          return;
        case "ice":
          await this.addRemoteCandidates(call, signal.cs);
          return;
        case "restart":
          if (call.role === "caller") this.restartIce(call);
          return;
        case "media":
          call.remoteMic = signal.mic;
          call.remoteCamera = signal.camera;
          this.publish(call);
          return;
      }
    } catch {
      /* one bad signal; the connect and reconnect timers end a call that never recovers */
    }
  }

  /** Callee: the first offer, or an ICE restart. */
  private async answerOffer(call: Call, sdp: string): Promise<void> {
    const pc = call.pc;
    if (!pc) return;
    try {
      await pc.setRemoteDescription({ type: "offer", sdp });
      if (this.gone(call)) return;
      await this.flushRemoteCandidates(call);
      const answer = withVoice(await pc.createAnswer());
      await pc.setLocalDescription(answer);
      tuneSenders(pc);
      if (this.gone(call)) return;
      this.send(call, { t: "answer", sdp: pc.localDescription?.sdp ?? answer.sdp ?? "" });
      if (!call.negotiated) this.negotiated(call);
    } catch {
      if (!call.negotiated && !this.gone(call)) this.finish(call, "Couldn’t connect", "hangup");
    }
  }

  private async takeAnswer(call: Call, sdp: string): Promise<void> {
    const pc = call.pc;
    // An answer to an offer that was taken back (a restart overtook it).
    if (!pc || pc.signalingState !== "have-local-offer") return;
    await pc.setRemoteDescription({ type: "answer", sdp });
    tuneSenders(pc);
    await this.flushRemoteCandidates(call);
  }

  private async addRemoteCandidates(call: Call, candidates: IceCandidateJson[]): Promise<void> {
    const pc = call.pc;
    if (!pc) return;
    if (!pc.remoteDescription) {
      call.pendingRemote.push(...candidates);
      return;
    }
    for (const candidate of candidates) await pc.addIceCandidate(candidate).catch(() => undefined);
  }

  private async flushRemoteCandidates(call: Call): Promise<void> {
    const pc = call.pc;
    if (!pc) return;
    for (const candidate of call.pendingRemote.splice(0)) await pc.addIceCandidate(candidate).catch(() => undefined);
  }

  private gatheredCandidate(call: Call, candidate: RTCIceCandidate | null): void {
    if (!candidate?.candidate || this.gone(call)) return;
    call.gathered.push({
      candidate: candidate.candidate,
      sdpMid: candidate.sdpMid ?? null,
      sdpMLineIndex: candidate.sdpMLineIndex ?? null,
    });
    if (call.negotiated && call.batchTimer === null) {
      call.batchTimer = this.env.setTimeout(() => {
        call.batchTimer = null;
        this.flushCandidates(call);
      }, ICE_BATCH_MS);
    }
  }

  /** Gathered candidates, a batch per signal; before the offer or answer went they wait. */
  private flushCandidates(call: Call): void {
    this.stop(call, "batchTimer");
    if (!call.negotiated || this.gone(call)) return;
    while (call.gathered.length > 0) this.send(call, { t: "ice", cs: call.gathered.splice(0, ICE_BATCH_MAX) });
  }

  private sendMediaState(call: Call): void {
    if (!call.negotiated) return;
    this.send(call, { t: "media", mic: call.micOn, camera: call.hasCamera && call.cameraOn });
  }

  /* --- media and the connection ------------------------------------------------------------ */

  private remoteTrack(call: Call, track: MediaStreamTrack): void {
    if (this.gone(call)) return;
    const stream = call.remote ?? (call.remote = this.env.createStream([]));
    if (!stream.getTracks().includes(track)) stream.addTrack(track);
    if (track.kind === "video") call.remoteVideo = true;
    else void this.playRemote(call);
    this.publish(call);
  }

  private async playRemote(call: Call): Promise<void> {
    const played = await this.env.playAudio(call.remote);
    if (this.gone(call) || call.audioBlocked === !played) return;
    call.audioBlocked = !played;
    this.publish(call);
  }

  private linkChanged(call: Call): void {
    const pc = call.pc;
    if (!pc || this.gone(call)) return;
    switch (linkState(pc.connectionState, pc.iceConnectionState)) {
      case "connected":
        this.stop(call, "graceTimer");
        this.stop(call, "reconnectTimer");
        this.stop(call, "restartTimer");
        if (call.phase === "connecting") {
          call.phase = "active";
          call.connectedAt = this.env.now();
          this.stop(call, "connectTimer");
          this.env.keepAwake(true);
          this.publish(call);
        } else if (call.reconnecting) {
          call.reconnecting = false;
          this.publish(call);
        }
        return;
      case "disconnected":
        this.troubled(call);
        if (call.graceTimer === null) {
          call.graceTimer = this.env.setTimeout(() => {
            call.graceTimer = null;
            if (this.broken(call)) this.restartIce(call);
          }, DISCONNECTED_GRACE_MS);
        }
        return;
      case "failed":
        call.wantRelay = true;
        this.troubled(call);
        this.restartIce(call);
        return;
    }
  }

  private broken(call: Call): boolean {
    const pc = call.pc;
    if (!pc) return false;
    const state = linkState(pc.connectionState, pc.iceConnectionState);
    return state === "disconnected" || state === "failed";
  }

  /** Mid-call trouble: "Reconnecting…", and a limit to how long that may last. */
  private troubled(call: Call): void {
    if (call.phase !== "active") return;
    if (!call.reconnecting) {
      call.reconnecting = true;
      this.publish(call);
    }
    if (call.reconnectTimer === null) {
      call.reconnectTimer = this.env.setTimeout(() => this.finish(call, "Connection lost", "hangup"), RECONNECT_LIMIT_MS);
    }
  }

  /** An ICE restart: the caller offers one, the callee asks for one; at most one per 10 s.
   *  A failed link switches to the TURN relay before the wait, and stays there. */
  private restartIce(call: Call): void {
    if (this.gone(call) || !call.pc || !call.negotiated) return;
    if (call.phase !== "active" && call.phase !== "connecting") return;
    if (call.wantRelay) this.preferRelay(call);
    const wait = call.restartGate.waitMs(this.env.now());
    if (wait > 0) {
      if (call.restartTimer === null) {
        call.restartTimer = this.env.setTimeout(() => {
          call.restartTimer = null;
          if (this.broken(call)) this.restartIce(call);
        }, wait);
      }
      return;
    }
    call.restartGate.mark(this.env.now());
    if (call.role === "callee") this.send(call, { t: "restart" });
    else void this.offerRestart(call);
  }

  private async offerRestart(call: Call): Promise<void> {
    const pc = call.pc;
    if (!pc) return;
    try {
      // An offer still waiting for its answer (10 s at least, by the gate) is taken back first.
      if (pc.signalingState === "have-local-offer") await pc.setLocalDescription({ type: "rollback" });
      const offer = withVoice(await pc.createOffer({ iceRestart: true }));
      await pc.setLocalDescription(offer);
      tuneSenders(pc);
      if (this.gone(call)) return;
      this.send(call, { t: "offer", sdp: pc.localDescription?.sdp ?? offer.sdp ?? "", restart: true });
    } catch {
      /* the next state change, or the gate's timer, tries again */
    }
  }

  /** Once, after the direct path has failed and the server offered a TURN server.
   *  Starts from the peer connection's own configuration so the certificate and the candidate
   *  pool stay as they are; replacing them makes the browser reject the update. */
  private preferRelay(call: Call): void {
    const pc = call.pc;
    if (!pc || call.triedRelay || !hasTurnServer(call.iceServers)) return;
    if (typeof pc.setConfiguration !== "function") return;
    try {
      const current =
        typeof pc.getConfiguration === "function" ? pc.getConfiguration() : peerConfig(call.iceServers, false);
      pc.setConfiguration({ ...current, iceTransportPolicy: "relay" });
      call.triedRelay = true;
    } catch {
      /* the restart still tries every path */
    }
  }

  /* --- the server's view --------------------------------------------------------------------- */

  private beat(call: Call): void {
    if (call.heartbeat !== null) return;
    call.heartbeat = this.env.setInterval(() => {
      const api = this.account?.api;
      if (!api || !call.id || this.gone(call)) return;
      api.heartbeat(call.id).then(
        (info) => this.reconcile(call, info),
        (err) => this.unreachable(call, err),
      );
    }, HEARTBEAT_MS);
  }

  /** Reads the call back (a socket gap, a signal refused as ended, a ring's check). */
  private check(call: Call, fallback: string | null = null): void {
    const api = this.account?.api;
    if (!api || !call.id || this.gone(call)) return;
    api.getCall(call.id).then(
      (info) => this.reconcile(call, info),
      (err) => {
        if (fallback) this.finish(call, fallback, null);
        else this.unreachable(call, err);
      },
    );
  }

  private unreachable(call: Call, err: unknown): void {
    if (err instanceof ApiError && err.status === 404) this.finish(call, "Call ended", null);
  }

  /** Brings this device in line with the server's status for the call. */
  private reconcile(call: Call, info: CallInfo): void {
    if (this.gone(call) || !sameId(call.id, info.id) || info.status === "ringing") return;
    if (info.status === "active") {
      if (call.role === "caller") this.answered(call, info);
      else if (call.phase === "incoming") this.finish(call, "Answered on another device", null);
      return;
    }
    this.finish(call, endedText(info.status, info.ended_reason, call.role), null);
  }

  /* --- ending ----------------------------------------------------------------------------- */

  /**
   * Ends the call here: tells the server (`notify`), frees everything the call held, and shows
   * `text` for a moment (nothing at all when it is null). Idempotent.
   */
  private finish(
    call: Call,
    endText: string | null,
    notify: "hangup" | "reject" | null,
    visibleMs = ENDED_VISIBLE_MS,
  ): void {
    if (call.phase === "ended") return;
    const rang = call.phase === "incoming";
    call.phase = "ended";
    call.endedText = endText;
    call.dialing = false;
    call.accepting = false;
    if (call.id) {
      this.finished.push(call.id);
      if (this.finished.length > 20) this.finished.shift();
      const api = this.account?.api;
      if (notify && api) {
        void (notify === "reject" ? api.rejectCall(call.id) : api.hangupCall(call.id)).catch(() => undefined);
      }
    }
    this.teardown(call);
    if (call.role === "callee") {
      this.env.notifyRing(
        rang && endText === "Missed call" ? { peer: call.peer, modality: call.modality, missed: true } : null,
      );
    }
    if (this.call !== call) return;
    if (!endText) {
      this.idle(call);
      return;
    }
    this.publish(call);
    call.endTimer = this.env.setTimeout(() => this.idle(call), visibleMs);
  }

  private teardown(call: Call): void {
    for (const key of [
      "ringTimer",
      "connectTimer",
      "graceTimer",
      "reconnectTimer",
      "restartTimer",
      "batchTimer",
      "noticeTimer",
    ] as const) {
      this.stop(call, key);
    }
    this.stopInterval(call, "heartbeat");
    this.stopInterval(call, "ringCheck");
    this.env.tone(null);
    this.env.keepAwake(false);
    const pc = call.pc;
    if (pc) {
      pc.onicecandidate = null;
      pc.ontrack = null;
      pc.onconnectionstatechange = null;
      pc.oniceconnectionstatechange = null;
      try {
        pc.close();
      } catch {
        /* closed already */
      }
      call.pc = null;
    }
    stopTracks(call.local);
    stopTracks(call.remote);
    call.local = null;
    call.remote = null;
    call.remoteVideo = false;
    call.audioBlocked = false;
    void this.env.playAudio(null);
    call.keys = null;
    call.reconnecting = false;
    call.notice = null;
    call.early = [];
    call.gathered = [];
    call.pendingRemote = [];
  }

  private idle(call: Call): void {
    this.stop(call, "endTimer");
    if (this.call !== call) return;
    if (call.phase !== "ended") {
      // Replaced while live (cannot happen through the UI): it still has to let go.
      this.finish(call, null, null);
      return;
    }
    this.call = null;
    const release = call.releaseHold;
    call.releaseHold = null;
    this.env.publish(null);
    release?.();
  }

  /* --- helpers ----------------------------------------------------------------------------- */

  /** No longer the call this controller is in (ended, or another took its place). */
  private gone(call: Call): boolean {
    return this.call !== call || call.phase === "ended";
  }

  /** This device is part of the call (not only rung by it). */
  private inCall(call: Call): boolean {
    return call.role === "caller" || call.accepting || call.phase === "connecting" || call.phase === "active";
  }

  /** A call past ringing in here, whose media the user can change. */
  private live(): Call | null {
    const call = this.call;
    return call && call.phase !== "ended" && call.phase !== "incoming" ? call : null;
  }

  private note(call: Call, message: string): void {
    call.notice = message;
    this.stop(call, "noticeTimer");
    call.noticeTimer = this.env.setTimeout(() => {
      call.noticeTimer = null;
      if (call.notice !== message) return;
      call.notice = null;
      this.publish(call);
    }, NOTICE_VISIBLE_MS);
    this.publish(call);
  }

  private stop(call: Call, key: TimerKey): void {
    const id = call[key];
    if (id === null) return;
    this.env.clearTimeout(id);
    call[key] = null;
  }

  private stopInterval(call: Call, key: IntervalKey): void {
    const id = call[key];
    if (id === null) return;
    this.env.clearInterval(id);
    call[key] = null;
  }

  private publish(call: Call): void {
    if (this.call !== call) return;
    this.env.publish({
      key: call.key,
      phase: call.phase,
      callId: call.id,
      role: call.role,
      modality: call.modality,
      peer: call.peer,
      dialing: call.dialing,
      reconnecting: call.reconnecting,
      connectedAt: call.connectedAt,
      micOn: call.micOn,
      cameraOn: call.cameraOn,
      hasCamera: call.hasCamera,
      canSwitchCamera: call.canSwitchCamera,
      mirrorSelf: call.mirrorSelf,
      remoteMic: call.remoteMic,
      remoteCamera: call.remoteCamera,
      remoteVideo: call.remoteVideo,
      localStream: call.local,
      remoteStream: call.remote,
      audioBlocked: call.audioBlocked,
      endedText: call.endedText,
      notice: call.notice,
      minimized: call.minimized,
    });
  }
}
