import { x25519 } from "@noble/curves/ed25519.js";
import { ApiError, type CallInfo, type CallModality, type CallSignalType, type IceServer } from "../api/client";
import { b64ToBytes, bytesToB64 } from "../crypto/bytes";
import { PEER_KEY_CHANGED, PeerKeyChanged } from "../crypto/peerIdentity";
import type { RealtimeEvent } from "../realtime";
import { RELAY_UNAVAILABLE } from "./relay";
import {
  callKeys,
  deriveCallSecret,
  deriveForwardSecret,
  forwardKeys,
  openSignal,
  sealSignal,
  type CallKeys,
  type CallRole,
} from "./crypto";
import {
  CAMERA_RELEASE_MS,
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
  SCREEN_ENDED,
  SCREEN_NOT_YET,
  SCREEN_UNAVAILABLE,
  SeenSignals,
  VIDEO_UNAVAILABLE,
  cameraErrorText,
  cameraOnlyFailure,
  callErrorText,
  cameraVideoSdp,
  endedText,
  fingerprintsMatch,
  isLive,
  linkState,
  mediaErrorText,
  readSignal,
  sameId,
  sdpFingerprint,
  screenErrorText,
  screenSoundSdp,
  screenVideoSdp,
  sdpWithoutCandidates,
  signalTypeOf,
  voiceSdp,
  type CallPeer,
  type CallPhase,
  type CallView,
  type CallViewSize,
  type IceCandidateJson,
  type Signal,
  type SignalBody,
} from "./logic";
import {
  SCREEN_CAPTURE_CEILING,
  loadScreenQuality,
  saveScreenQuality,
  screenBitrate,
  screenMotion,
  screenScaleDown,
  screenVideoConstraints,
  type ScreenQuality,
} from "./screenQuality";
import type { FramedCamera } from "./cameraFraming";
import { loadCenterStage, saveCenterStage } from "./centerStage";
import { shapeChanged, type Size } from "./framing";
import {
  CameraQuality,
  QUALITY_SAMPLE_MS,
  cameraEncoding,
  readCameraSample,
  tileOf,
  type CameraRung,
} from "./videoQuality";

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
 * Voice or video is not fixed: every call negotiates a video section both ways from the start, so
 * either side turns its camera on or off at any time by swapping the track on that section and
 * saying so in `media_state`. No new offer, so the call never drops or stalls for it. A shared
 * screen works the same way on two sections of its own (its picture and its sound), next to the
 * camera: the sections are told apart by their place in the offer (`sectionOf`).
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
  /** The safety number for a pinned contact, when the account can show one. */
  safety?: (userId: string) => { number: string; verified: boolean } | null;
  /** The user compared the safety number. */
  confirmSafety?: (userId: string) => void;
  /** The user accepts a changed identity key after comparing safety numbers. */
  acceptKey?: (userId: string) => void;
};

/** Everything outside the controller. */
export type CallEnv = {
  /** Why this browser cannot call at all, or null. */
  unsupported(): string | null;
  getUserMedia(constraints: MediaStreamConstraints): Promise<MediaStream>;
  /**
   * The browser's screen picker. Absent where a browser cannot share a screen (phones). It must be
   * called within the click that asked for it, so nothing is awaited before it.
   */
  getDisplayMedia?(options: DisplayMediaStreamOptions): Promise<MediaStream>;
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
  /**
   * Plays the other side's audio (null stops it); false when the browser wants a click first.
   * Their microphone and their shared screen's sound each play in a slot of their own.
   */
  playAudio(stream: MediaStream | null, slot?: "voice" | "screen"): Promise<boolean>;
  keepAwake(on: boolean): void;
  holdAutoLock(): () => void;
  /** A call takes the microphone: voice notes stop playing and recording. */
  interruptVoice(): void;
  /** The notification for a ring nobody is watching, or for one missed; null takes it down. */
  notifyRing(ring: { peer: CallPeer; modality: CallModality; missed: boolean } | null): void;
  /** Other tabs of this browser (the same device) stop ringing for this call. */
  tellTabs(callId: string): void;
  /**
   * The remote DTLS certificate's SHA-256 fingerprint, once the handshake has one.
   * Null when this browser cannot read it yet. Absent in tests that have no handshake.
   */
  remoteFingerprint?(pc: RTCPeerConnection): Promise<string | null>;
  /**
   * Every call goes through the TURN relay from the start, so the other side never learns this
   * network's address (calls/relay.ts). Absent means direct paths first.
   */
  alwaysRelay?(): boolean;
  /**
   * Our camera framed: cut to their view and, with Center Stage, following faces
   * (cameraFraming.ts). Absent, or null for a camera, where this browser cannot frame.
   */
  /** This browser frames the camera (`frameCamera` gives one), so it is opened at up to 4K. */
  framingSupported?(): boolean;
  frameCamera?(
    camera: MediaStreamTrack,
    options: { view: Size | null; follow: boolean; onSize: () => void; onFollowChange: () => void; onStall: () => void },
  ): FramedCamera | null;
};

/**
 * 1080p at most, front camera first. The encoder sends a rung of the ladder below that, as the
 * link allows (videoQuality.ts).
 */
const VIDEO: MediaTrackConstraints = {
  width: { ideal: 1920 },
  height: { ideal: 1080 },
  frameRate: { ideal: 30, max: 30 },
};
/**
 * Where the camera is framed (docs/calls.md, "Framing and Center Stage"): up to 4K, so a cut to a
 * phone's tall view, or one zoomed in on a face, still has its pixels.
 */
const FRAMED_VIDEO: MediaTrackConstraints = {
  width: { ideal: 3840 },
  height: { ideal: 2160 },
  frameRate: { ideal: 30, max: 30 },
};
/** Their view of our camera is told at most this often while it changes (a window being resized). */
const VIEW_MS = 500;
/** The sizes a camera steps down through while it runs below 25 fps at one: 1440p, 1080p, 720p. */
const SMOOTHER = [
  { width: 2560, height: 1440 },
  { width: 1920, height: 1080 },
  { width: 1280, height: 720 },
];
const AUDIO: MediaTrackConstraints = {
  echoCancellation: true,
  noiseSuppression: true,
  autoGainControl: true,
  channelCount: { ideal: 1 },
};
/**
 * A shared screen, captured at the most any choice can use and then narrowed to the chosen size
 * and frame rate (screenQuality.ts), so a later choice can go up as well as down. The sound comes
 * as it is, without the processing a microphone gets. The browser's own tab is left out of the
 * picker: sharing the call into itself mirrors it without end.
 */
function displayOptions(): DisplayMediaStreamOptions & Record<string, unknown> {
  return {
    video: SCREEN_CAPTURE_CEILING,
    // A system's sound would carry this page's own playback (their voice, their screen's sound)
    // back to them; `restrictOwnAudio` leaves it out where the browser knows how.
    audio: { echoCancellation: false, noiseSuppression: false, autoGainControl: false, restrictOwnAudio: true } as MediaTrackConstraints,
    selfBrowserSurface: "exclude",
    surfaceSwitching: "include",
    systemAudio: "include",
    monitorTypeSurfaces: "include",
  };
}
const AUDIO_MAX_BPS = 32_000;
const SCREEN_SOUND_MAX_BPS = 128_000;

type TimerKey =
  | "ringTimer"
  | "connectTimer"
  | "graceTimer"
  | "reconnectTimer"
  | "restartTimer"
  | "batchTimer"
  | "noticeTimer"
  | "cameraTimer"
  | "viewTimer"
  | "endTimer";
/** What a section carries: by kind, then by place (docs/calls.md, "Screen sharing"). */
type Section = "mic" | "camera" | "screen" | "screenSound";
type IntervalKey = "heartbeat" | "ringCheck" | "qualityCheck";

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
  /** The identity call secret, kept only until the per-call key is derived. */
  identitySecret: Uint8Array | null;
  /** Our ephemeral private key, kept only until the peer's public key arrives. */
  ephPrivate: Uint8Array | null;
  ephPublic: Uint8Array | null;
  /** Post-setup signals. The identity keys, when the peer sent no ephemeral key. */
  forward: CallKeys | null;
  /** The first offer or answer has been taken, so later ones use `forward`. */
  gotSetup: boolean;
  /** Our first answer has been sealed with the identity key. */
  sentAnswer: boolean;
  /** The SHA-256 fingerprint in the sealed remote description. */
  expectedFingerprint: string | null;
  fingerprintChecked: boolean;
  /** Post-setup signals that arrived before the per-call key existed. */
  heldIn: { from: string; type: string; payload: string }[];
  /** Media and candidates waiting until the per-call key exists. */
  heldOut: SignalBody[];
  safety: { number: string; verified: boolean } | null;
  keyChanged: boolean;
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
  /** Our video section: the camera's track goes on its sender and comes off it again. */
  video: RTCRtpTransceiver | null;
  /** How sharp our camera goes out: a rung of the ladder, moved by the link (videoQuality.ts). */
  quality: CameraQuality;
  /** A stats reading is on its way; the next tick waits for it. */
  sampling: boolean;
  /** Our camera as it goes out: framed to their view (null where this browser cannot frame). */
  framed: FramedCamera | null;
  /** Their view of our camera (`media_state` `view`), or null: our camera's own shape. */
  peerView: Size | null;
  /** Our view of their camera, sent in every `media_state`; and the one they last heard. */
  myView: CallViewSize | null;
  sentView: CallViewSize | null;
  /** The open camera (also while its picture fades out after Video went off). */
  camera: MediaStreamTrack | null;
  /** The browser or the system paused the camera (another app took it, the page went away). */
  cameraMuted: boolean;
  cameraPending: boolean;
  /** Our screen's two sections: its picture and its sound. Null in a call with an older app. */
  screen: RTCRtpTransceiver | null;
  screenSound: RTCRtpTransceiver | null;
  /** What the browser captures while we share (picture, and sound when it offers some). */
  display: MediaStream | null;
  screenPending: boolean;
  /** Their app can show a screen: its `media_state` carries `screen`. */
  peerShows: boolean;
  remoteScreen: boolean;
  /** Their screen's picture and sound, apart from their camera. */
  remoteDisplay: MediaStream | null;
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

/** A section that goes both ways (or out only) can carry what we send. */
function sendable(transceiver: RTCRtpTransceiver | null): boolean {
  if (!transceiver) return false;
  const direction = transceiver.currentDirection ?? transceiver.direction;
  return direction === "sendrecv" || direction === "sendonly";
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

/**
 * A camera whose large size runs slower than 25 fps (many webcams give 4K or 1080p at 5 to 15) is
 * stepped down until it runs smoothly, down to 720p: a smooth picture looks better than a
 * stuttering sharp one at every rung. Browsers pick the mode nearest the `ideal`s, which can be
 * such a slow one.
 */
async function smoothCamera(track: MediaStreamTrack | null | undefined): Promise<void> {
  if (!track || typeof track.getSettings !== "function" || typeof track.applyConstraints !== "function") return;
  for (const size of SMOOTHER) {
    const { width = 0, height = 0, frameRate } = track.getSettings();
    if (frameRate === undefined || frameRate >= 25 || Math.max(width, height) <= 1280) return;
    if (Math.max(width, height) <= size.width) continue;
    const narrowed = await track
      .applyConstraints({ width: { ideal: size.width }, height: { ideal: size.height }, frameRate: { ideal: 30, max: 30 } })
      .then(
        () => true,
        () => false,
      );
    if (!narrowed) return;
  }
}

/** Opus as each audio section needs it (speech on the microphone's, music on the screen's),
 *  H.264 first for the camera's picture and VP8 first for the screen's. */
function withVoice(description: RTCSessionDescriptionInit): RTCSessionDescriptionInit {
  return {
    type: description.type,
    sdp: cameraVideoSdp(screenVideoSdp(screenSoundSdp(voiceSdp(description.sdp ?? "")))),
  };
}

/**
 * What a transceiver carries, by its place among those of its kind: the first audio section is
 * the microphone and the first video the camera, the second of each the shared screen's sound and
 * picture. Both ends see the sections in the offer's order, so both agree without naming them.
 */
function sectionOf(pc: RTCPeerConnection, transceiver: RTCRtpTransceiver): Section | null {
  let audio = 0;
  let video = 0;
  for (const t of pc.getTransceivers()) {
    const kind = t.receiver.track?.kind;
    const place = kind === "audio" ? audio++ : kind === "video" ? video++ : -1;
    if (t !== transceiver) continue;
    if (kind === "audio") return place === 0 ? "mic" : place === 1 ? "screenSound" : null;
    if (kind === "video") return place === 0 ? "camera" : place === 1 ? "screen" : null;
    return null;
  }
  return null;
}

/** Which of our senders carry the screen, whether it goes out now, and at what quality. */
type Sharing = { screen: RTCRtpSender | null; sound: RTCRtpSender | null; on: boolean; quality: ScreenQuality };

/**
 * Speech at about 32 kbps, first in line. The camera at its rung of the ladder (videoQuality.ts),
 * shedding rate and detail together within it; while our screen is shared, a thumbnail's worth
 * (they show it as a tile).
 * The screen at the chosen frame rate and a bitrate to match (2.5 Mbps at 1080p and 30 fps),
 * ahead of the camera and behind speech: up to 30 fps it keeps its sharpness and gives up frames
 * when the link is tight, at 60 it gives up some of each. Its sound at about 128 kbps.
 */
function tuneSenders(
  pc: RTCPeerConnection,
  sharing: Sharing,
  camera: CameraRung,
  cameraSize: { width?: number; height?: number },
): void {
  if (typeof pc.getSenders !== "function") return;
  for (const sender of pc.getSenders()) {
    const track = sender.track;
    if (!track || typeof sender.getParameters !== "function" || typeof sender.setParameters !== "function") continue;
    try {
      const params = sender.getParameters();
      const encoding = params.encodings?.[0];
      if (!encoding) continue;
      if (sender === sharing.sound) {
        encoding.maxBitrate = SCREEN_SOUND_MAX_BPS;
        encoding.priority = "medium";
        encoding.networkPriority = "medium";
      } else if (sender === sharing.screen) {
        encoding.maxBitrate = screenBitrate(sharing.quality);
        encoding.maxFramerate = sharing.quality.frameRate;
        encoding.scaleResolutionDownBy = screenScaleDown(track.getSettings?.() ?? {}, sharing.quality);
        encoding.priority = "medium";
        encoding.networkPriority = "medium";
        params.degradationPreference = screenMotion(sharing.quality).degradation;
      } else if (track.kind === "audio") {
        encoding.maxBitrate = AUDIO_MAX_BPS;
        encoding.priority = "high";
        encoding.networkPriority = "high";
      } else if (track.kind === "video") {
        const shape = cameraEncoding(sharing.on ? tileOf(camera) : camera, cameraSize);
        encoding.maxBitrate = shape.maxBitrate;
        encoding.maxFramerate = shape.maxFramerate;
        encoding.scaleResolutionDownBy = shape.scaleResolutionDownBy;
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
  /** How our screen goes out, in every call from this browser (screenQuality.ts). */
  private screenQuality: ScreenQuality = loadScreenQuality();
  /** The last size of the area that shows their camera, measured by the call screen (`setView`). */
  private lastView: CallViewSize | null = null;
  /** Center Stage, in every call from this browser (centerStage.ts). */
  private centerStage = loadCenterStage();

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

  /**
   * Video on or off: a voice call becomes a video call and back, in either direction, without a
   * new offer. Only our own camera; theirs is theirs to switch.
   */
  toggleCamera(): void {
    const call = this.live();
    if (!call || call.cameraPending || call.switching) return;
    if (call.cameraOn) this.cameraOff(call);
    else void this.cameraOnNow(call);
  }

  /** The next camera: the other facing one on a phone, the next device elsewhere. */
  async switchCamera(): Promise<void> {
    const call = this.live();
    const old = call?.camera;
    if (!call || !old || !call.cameraOn || !call.canSwitchCamera || call.switching) return;
    call.switching = true;
    const facing = facingOf(old);
    const oldDevice = old.getSettings?.().deviceId;
    let wanted: MediaTrackConstraints;
    if (facing === "user" || facing === "environment") {
      wanted = { ...this.video(), facingMode: { exact: facing === "user" ? "environment" : "user" } };
    } else {
      const ids = await this.env.cameras().catch(() => [] as string[]);
      const next = ids.length > 1 ? ids[(ids.indexOf(oldDevice ?? "") + 1) % ids.length] : undefined;
      wanted = next ? { ...this.video(), deviceId: { exact: next } } : { ...this.video() };
    }
    // Phones open one camera at a time: the old one closes first.
    old.stop();
    let fresh: MediaStreamTrack | null = null;
    for (const constraints of [wanted, oldDevice ? { ...this.video(), deviceId: { exact: oldDevice } } : this.video()]) {
      try {
        fresh = (await this.env.getUserMedia({ video: constraints })).getVideoTracks()[0] ?? null;
        await smoothCamera(fresh);
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
    if (!fresh) {
      // Neither camera opens now: the call goes on without our video.
      this.cameraOff(call, CAMERA_UNAVAILABLE);
      return;
    }
    fresh.enabled = true;
    // Framed before it goes out, so they never see the new camera uncut for a moment.
    this.useCamera(call, fresh);
    await call.video?.sender.replaceTrack(this.outgoing(call)).catch(() => undefined);
    if (this.gone(call)) return;
    // The other camera may capture at another size: a new ceiling, and a new shrink to the rung.
    this.tune(call);
    this.publish(call);
  }

  /** Video on: the camera opens (or the one still fading out comes back) and goes on our section. */
  private async cameraOnNow(call: Call): Promise<void> {
    const video = call.video;
    if (!video || !this.videoSendable(call)) {
      this.note(call, VIDEO_UNAVAILABLE);
      return;
    }
    this.stop(call, "cameraTimer");
    call.cameraPending = true;
    let track = call.camera?.readyState === "live" ? call.camera : null;
    if (!track) {
      this.publish(call);
      let failure: unknown = null;
      try {
        track = (await this.env.getUserMedia({ video: { ...this.video(), facingMode: "user" } })).getVideoTracks()[0] ?? null;
        await smoothCamera(track);
      } catch (err) {
        failure = err;
      }
      if (this.gone(call)) {
        track?.stop();
        return;
      }
      if (!track) {
        call.cameraPending = false;
        this.note(call, cameraErrorText(failure));
        return;
      }
    }
    track.enabled = true;
    const sent = await video.sender.replaceTrack(track === call.camera ? this.outgoing(call) : track).then(
      () => true,
      () => false,
    );
    if (this.gone(call)) {
      track.stop();
      return;
    }
    call.cameraPending = false;
    if (!sent) {
      // The camera from a moment ago closes as it was about to; a new one closes now.
      if (track === call.camera) {
        this.cameraOff(call, CAMERA_UNAVAILABLE);
      } else {
        track.stop();
        this.note(call, CAMERA_UNAVAILABLE);
      }
      return;
    }
    if (track !== call.camera) this.useCamera(call, track);
    call.cameraOn = true;
    this.tune(call);
    this.sendMediaState(call);
    this.publish(call);
    this.findCameras(call);
  }

  /**
   * Video off: nothing more goes out at once and they are told; our own picture fades out, and
   * only then does the camera close.
   */
  private cameraOff(call: Call, notice: string | null = null): void {
    call.cameraOn = false;
    call.cameraPending = false;
    void call.video?.sender.replaceTrack(null).catch(() => undefined);
    this.sendMediaState(call);
    if (notice) this.note(call, notice);
    else this.publish(call);
    const track = call.camera;
    this.stop(call, "cameraTimer");
    call.cameraTimer = this.env.setTimeout(() => {
      call.cameraTimer = null;
      if (this.gone(call) || call.cameraOn || call.camera !== track) return;
      this.useCamera(call, null);
      this.publish(call);
    }, CAMERA_RELEASE_MS);
  }

  /**
   * Makes `track` our camera (null: none), framed where this browser can, and the local stream to
   * match: our own picture shows what goes out. A section already sending the camera itself
   * moves to the framed track.
   */
  private useCamera(call: Call, track: MediaStreamTrack | null): void {
    const old = call.camera;
    if (old && old !== track) {
      old.onmute = null;
      old.onunmute = null;
      old.onended = null;
      old.stop();
    }
    if (old !== track) {
      call.framed?.stop();
      call.framed = track ? this.frame(call, track) : null;
    }
    call.camera = track;
    call.cameraMuted = track?.muted === true;
    const out = this.outgoing(call);
    const audio = call.local?.getAudioTracks() ?? [];
    call.local = this.env.createStream(out ? [...audio, out] : audio);
    if (!track) return;
    if (out !== track && call.video?.sender.track === track) void call.video.sender.replaceTrack(out).catch(() => undefined);
    call.mirrorSelf = facingOf(track) !== "environment";
    track.onmute = () => this.cameraPaused(call, track, true);
    track.onunmute = () => this.cameraPaused(call, track, false);
    // Unplugged, or the permission taken back: the call goes on without our video.
    track.onended = () => {
      if (!this.gone(call) && call.camera === track && call.cameraOn) this.cameraOff(call, CAMERA_UNAVAILABLE);
    };
  }

  /**
   * The system paused our camera (another app took it, a phone put the page away) or gave it
   * back. They are told, so they see our face instead of the last frame, frozen.
   */
  private cameraPaused(call: Call, track: MediaStreamTrack, paused: boolean): void {
    if (this.gone(call) || call.camera !== track || call.cameraMuted === paused) return;
    call.cameraMuted = paused;
    if (call.cameraOn) this.sendMediaState(call);
    this.publish(call);
  }

  /** The camera constraints: up to 4K where the camera is framed, 1080p where it is not. */
  private video(): MediaTrackConstraints {
    return this.env.framingSupported?.() ? FRAMED_VIDEO : VIDEO;
  }

  /** `track` framed for this call, or null where this browser cannot frame it. */
  private frame(call: Call, track: MediaStreamTrack): FramedCamera | null {
    const framed =
      this.env.frameCamera?.(track, {
        view: call.peerView,
        follow: this.centerStage,
        onSize: () => {
          if (!this.gone(call) && call.framed === framed) this.tune(call);
        },
        onFollowChange: () => {
          if (!this.gone(call) && call.framed === framed) this.publish(call);
        },
        onStall: () => {
          // No frame came out: the camera goes out as it comes.
          if (this.gone(call) || call.framed !== framed || !framed) return;
          framed.stop();
          call.framed = null;
          const camera = call.camera;
          if (camera && call.video?.sender.track === framed.track) void call.video.sender.replaceTrack(camera).catch(() => undefined);
          const audio = call.local?.getAudioTracks() ?? [];
          call.local = this.env.createStream(camera ? [...audio, camera] : audio);
          this.tune(call);
          this.publish(call);
        },
      }) ?? null;
    return framed;
  }

  /** Center Stage on or off, in this call and the next ones from this browser. */
  setCenterStage(on: boolean): void {
    this.centerStage = on;
    saveCenterStage(on);
    const call = this.live();
    if (!call) return;
    call.framed?.setFollow(on);
    this.publish(call);
  }

  /**
   * The size of the area that shows their camera while it fills it (device pixels), or null while
   * it shows their whole picture. They hear of a new shape in our next `media_state`, at most every
   * 500 ms (docs/calls.md, "Framing and Center Stage").
   */
  setView(view: CallViewSize | null): void {
    this.lastView = view;
    const call = this.call;
    if (!call || call.phase === "ended") return;
    call.myView = view;
    const asSize = (v: CallViewSize | null) => (v ? { width: v.w, height: v.h } : null);
    if (!shapeChanged(asSize(call.sentView), asSize(view)) || call.viewTimer !== null) return;
    call.viewTimer = this.env.setTimeout(() => {
      call.viewTimer = null;
      if (this.gone(call)) return;
      if (shapeChanged(asSize(call.sentView), asSize(call.myView))) this.sendMediaState(call);
    }, VIEW_MS);
  }

  /** Our video can go out in this call: its section was offered both ways (every current app does). */
  private videoSendable(call: Call): boolean {
    return sendable(call.video);
  }

  /**
   * Our screen can go out and they can show it: its section goes both ways (an older app's offer
   * has none), and their app said it knows screens.
   */
  private screenSendable(call: Call): boolean {
    return sendable(call.screen) && call.peerShows;
  }

  /**
   * Share the screen, or stop sharing it. The browser's picker opens from the click that got here,
   * so it must open before anything is awaited.
   */
  toggleScreen(): void {
    const call = this.live();
    if (!call || call.screenPending) return;
    if (call.display) {
      this.screenOff(call);
      return;
    }
    if (!this.env.getDisplayMedia) return;
    if (!call.screen || !this.screenSendable(call)) {
      // Before the call connects, their app has not said yet whether it shows screens.
      this.note(call, call.screen && call.phase !== "active" ? SCREEN_NOT_YET : SCREEN_UNAVAILABLE);
      return;
    }
    call.screenPending = true;
    const picked = this.pickScreen();
    this.publish(call);
    void this.screenOnNow(call, picked);
  }

  /**
   * The picker, asked for the screen's sound too. A browser that cannot capture sound and says so
   * at once (a TypeError) is asked again for the picture alone, still inside the same click.
   */
  private pickScreen(): Promise<MediaStream> {
    const pick = (options: DisplayMediaStreamOptions) => {
      try {
        return this.env.getDisplayMedia!(options);
      } catch (err) {
        return Promise.reject(err);
      }
    };
    const options = displayOptions();
    return pick(options).catch((err: unknown) => {
      if (!(err instanceof TypeError)) throw err;
      return pick({ ...options, audio: false });
    });
  }

  /** The chosen screen goes on its sections, its sound with it when the browser offered some. */
  private async screenOnNow(call: Call, picked: Promise<MediaStream>): Promise<void> {
    let stream: MediaStream | null = null;
    let failure: unknown = null;
    try {
      stream = await picked;
    } catch (err) {
      failure = err;
    }
    if (this.gone(call)) {
      stopTracks(stream);
      return;
    }
    const picture = stream?.getVideoTracks()[0] ?? null;
    if (!stream || !picture || !call.screen) {
      stopTracks(stream);
      call.screenPending = false;
      // A closed picker says nothing; a refusal says why.
      const why = stream ? "Couldn’t share your screen." : screenErrorText(failure);
      if (why) this.note(call, why);
      else this.publish(call);
      return;
    }
    // Text and edges stay sharp (or, at 60 fps, motion smooth); the sound is whatever plays, not speech.
    picture.contentHint = screenMotion(this.screenQuality).hint;
    // Opened at the ceiling: narrowed to the choice before it goes out. A browser that refuses
    // sends it at full size, and the encoder shrinks it (tuneSenders).
    await this.narrowScreen(picture, this.screenQuality);
    const sound = stream.getAudioTracks()[0] ?? null;
    if (sound) sound.contentHint = "music";
    const sent = await call.screen.sender.replaceTrack(picture).then(
      () => true,
      () => false,
    );
    const withSound =
      sent && sound !== null && call.screenSound !== null &&
      (await call.screenSound.sender.replaceTrack(sound).then(
        () => true,
        () => false,
      ));
    if (this.gone(call)) {
      stopTracks(stream);
      return;
    }
    call.screenPending = false;
    // Stopped from the browser's bar, or the window closed, while it was being put on the call.
    const ended = picture.readyState === "ended";
    if (!sent || ended) {
      void call.screen.sender.replaceTrack(null).catch(() => undefined);
      void call.screenSound?.sender.replaceTrack(null).catch(() => undefined);
      stopTracks(stream);
      this.note(call, ended ? SCREEN_ENDED : "Couldn’t share your screen.");
      return;
    }
    // Sound that could not go out is not shared, and not said to be.
    if (sound && !withSound) {
      sound.stop();
      stream.removeTrack(sound);
    }
    call.display = stream;
    // The browser's own "Stop sharing", or the shared window closing.
    picture.onended = () => {
      if (!this.gone(call) && call.display === stream) this.screenOff(call, SCREEN_ENDED);
    };
    this.tune(call);
    this.sendMediaState(call);
    this.publish(call);
  }

  /** Stop sharing: nothing more goes out, they are told, and the capture ends. */
  private screenOff(call: Call, notice: string | null = null): void {
    const stream = call.display;
    if (!stream) return;
    call.display = null;
    void call.screen?.sender.replaceTrack(null).catch(() => undefined);
    void call.screenSound?.sender.replaceTrack(null).catch(() => undefined);
    for (const track of stream.getTracks()) {
      track.onended = null;
      track.stop();
    }
    this.tune(call);
    this.sendMediaState(call);
    if (notice) this.note(call, notice);
    else this.publish(call);
  }

  private tune(call: Call): void {
    if (!call.pc) return;
    const settings = this.cameraSize(call);
    call.quality.setCapture(settings);
    tuneSenders(
      call.pc,
      {
        screen: call.screen?.sender ?? null,
        sound: call.screenSound?.sender ?? null,
        on: call.display !== null,
        quality: this.screenQuality,
      },
      call.quality.rung,
      settings,
    );
  }

  /** What reaches the encoder: the framed output's size, or the camera's own. */
  private cameraSize(call: Call): { width?: number; height?: number } {
    return call.framed?.size() ?? call.camera?.getSettings?.() ?? {};
  }

  /** What goes out on our video section: the framed camera, or the camera itself. */
  private outgoing(call: Call): MediaStreamTrack | null {
    return call.framed?.track ?? call.camera;
  }

  /**
   * Every two seconds while the call is up: the camera's stats move it along the ladder, and the
   * encoder takes a new rung at once. Not while the camera is off, paused by the system, or goes
   * out as a tile: a camera that sends nothing reads as a clean link.
   */
  private watchQuality(call: Call): void {
    if (call.qualityCheck !== null) return;
    call.qualityCheck = this.env.setInterval(() => void this.sampleQuality(call), QUALITY_SAMPLE_MS);
  }

  private async sampleQuality(call: Call): Promise<void> {
    const sender = call.video?.sender;
    if (this.gone(call) || !call.pc || !sender || call.sampling) return;
    if (!call.cameraOn || call.cameraMuted || !sender.track || call.display !== null || call.reconnecting) {
      call.quality.pause();
      return;
    }
    if (typeof sender.getStats !== "function") return;
    call.sampling = true;
    let report: RTCStatsReport | null = null;
    try {
      // The sender's own stats: its outbound-rtp, what the other side reports for it, the link.
      report = await sender.getStats();
    } catch {
      report = null;
    }
    call.sampling = false;
    if (!report || this.gone(call) || !call.cameraOn || call.cameraMuted || call.display !== null) return;
    const sample = readCameraSample(report.values() as Iterable<Record<string, unknown>>);
    if (sample && call.quality.sample(sample)) this.tune(call);
  }

  /**
   * The resolution and frame rate our screen goes out at, kept for the next share too. While we
   * share, the capture and the encoder take it at once, without a new picker or a new offer.
   */
  setScreenQuality(quality: ScreenQuality): void {
    this.screenQuality = quality;
    saveScreenQuality(quality);
    const call = this.live();
    if (!call) return;
    const picture = call.display?.getVideoTracks()[0] ?? null;
    if (picture && picture.readyState === "live") {
      picture.contentHint = screenMotion(quality).hint;
      // Tuned again once the capture has its new size: the encoder's own scaling depends on it.
      void this.narrowScreen(picture, quality).then(() => {
        if (!this.gone(call) && call.display?.getVideoTracks()[0] === picture) this.tune(call);
      });
    }
    this.tune(call);
    this.publish(call);
  }

  /** The capture brought to the chosen size and frame rate, where the browser allows it. */
  private async narrowScreen(picture: MediaStreamTrack, quality: ScreenQuality): Promise<void> {
    if (typeof picture.applyConstraints !== "function") return;
    await picture.applyConstraints(screenVideoConstraints(quality)).catch(() => undefined);
  }

  /** With a second camera, Flip is offered. */
  private findCameras(call: Call): void {
    void this.env
      .cameras()
      .then((ids) => {
        if (this.gone(call)) return;
        call.canSwitchCamera = ids.length > 1;
        this.publish(call);
      })
      .catch(() => undefined);
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
    if (call && !this.gone(call) && (call.remote || call.remoteDisplay)) void this.playRemote(call);
  }

  /** Closes the ended screen early. */
  dismiss(): void {
    const call = this.call;
    if (call?.phase === "ended") this.idle(call);
  }

  /** The safety number on screen was compared. */
  confirmSafety(): void {
    const call = this.live() ?? this.call;
    if (!call || call.phase === "ended" || !this.account?.confirmSafety) return;
    this.account.confirmSafety(call.peer.id);
    this.noteSafety(call);
    this.publish(call);
  }

  /** Trusts a changed identity key. A call that never started closes; a ring stays so it can be answered. */
  acceptChangedKey(): void {
    const call = this.call;
    if (!call?.keyChanged || !this.account?.acceptKey) return;
    this.account.acceptKey(call.peer.id);
    call.keyChanged = false;
    if (call.notice === PEER_KEY_CHANGED) call.notice = null;
    if (call.phase === "ended") this.idle(call);
    else this.publish(call);
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
      username: account.peerName(info.caller_user_id) ?? "Contact",
    };
    const call = this.open({
      role: "callee",
      id,
      peer,
      modality: info.modality === "video" ? "video" : "voice",
      phase: "incoming",
      peerDevice: info.caller_device_id.toLowerCase(),
    });
    this.noteSafety(call);
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
      identitySecret: null,
      ephPrivate: null,
      ephPublic: null,
      forward: null,
      gotSetup: false,
      sentAnswer: false,
      expectedFingerprint: null,
      fingerprintChecked: false,
      heldIn: [],
      heldOut: [],
      safety: null,
      keyChanged: false,
      sent: 0,
      seen: new SeenSignals(),
      pc: null,
      iceServers: [],
      wantRelay: false,
      triedRelay: false,
      local: null,
      remote: null,
      video: null,
      quality: new CameraQuality(),
      sampling: false,
      framed: null,
      peerView: null,
      // The call screen measures only when it is laid out anew: a call that follows another on the
      // same screen starts from the last measure.
      myView: this.lastView,
      sentView: null,
      camera: null,
      cameraMuted: false,
      cameraPending: false,
      screen: null,
      screenSound: null,
      display: null,
      screenPending: false,
      peerShows: false,
      remoteScreen: false,
      remoteDisplay: null,
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
      cameraOn: false,
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
      cameraTimer: null,
      viewTimer: null,
      endTimer: null,
      heartbeat: null,
      ringCheck: null,
      qualityCheck: null,
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
      const copy = secret.slice();
      const keys = await callKeys(copy, id, "caller");
      copy.fill(0);
      if (this.gone(call)) {
        secret.fill(0);
        void account.api.hangupCall(id).catch(() => undefined);
        return;
      }
      call.identitySecret = secret;
      call.keys = keys;
      this.noteSafety(call);
      call.id = id;
      call.dialing = false;
      this.env.tone("ringback");
      this.beat(call);
      call.ringTimer = this.env.setTimeout(() => this.finish(call, "No answer", "hangup"), OUTGOING_RING_LIMIT_MS);
      this.publish(call);
      // A quick answer or decline that raced the ring's own response.
      for (const event of call.early.splice(0)) this.handle(event);
    } catch (err) {
      if (!this.gone(call)) this.fail(call, err);
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
      const copy = secret.slice();
      const keys = await callKeys(copy, id, "callee");
      copy.fill(0);
      if (this.gone(call)) {
        secret.fill(0);
        return;
      }
      call.identitySecret = secret;
      call.keys = keys;
      this.noteSafety(call);
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
      // The ring is still up: keep it, and let them trust the new key before answering.
      if (!this.gone(call) && err instanceof PeerKeyChanged) {
        stopTracks(call.local);
        call.local = null;
        const pc = call.pc;
        call.pc = null;
        try {
          pc?.close();
        } catch {
          /* not open yet */
        }
        call.identitySecret?.fill(0);
        call.ephPrivate?.fill(0);
        call.identitySecret = null;
        call.ephPrivate = null;
        call.ephPublic = null;
        call.keys = null;
        call.phase = "incoming";
        call.accepting = false;
        call.keyChanged = true;
        call.notice = err.message;
        this.env.tone("ringtone");
        this.publish(call);
        return;
      }
      if (!this.gone(call)) this.fail(call, err);
    }
  }

  /** Opens the microphone (and camera); false when the call went away meanwhile. */
  private async openMedia(call: Call, answering: boolean): Promise<boolean> {
    let stream: MediaStream;
    let cameraFailed = false;
    try {
      if (call.modality === "video") {
        try {
          stream = await this.env.getUserMedia({ audio: AUDIO, video: { ...this.video(), facingMode: "user" } });
          await smoothCamera(stream.getVideoTracks()[0]);
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
    if (video) {
      this.useCamera(call, video);
      call.cameraOn = true;
      this.findCameras(call);
    }
    if (cameraFailed) this.note(call, CAMERA_UNAVAILABLE);
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

  /** Throws `CallFailure` when calls must be relayed and the server offers no relay: a direct
   *  path would show the other side this network's address, which is what the switch prevents. */
  private buildPeer(call: Call, servers: IceServer[]): void {
    const rtcServers = servers.map(toRtcServer);
    const relayOnly = this.env.alwaysRelay?.() ?? false;
    if (relayOnly && !hasTurnServer(rtcServers)) throw new CallFailure(RELAY_UNAVAILABLE);
    call.iceServers = rtcServers;
    // Already relayed, so the fallback after a failed link has nothing left to switch to.
    call.triedRelay = relayOnly;
    const pc = this.env.createPeer(peerConfig(rtcServers, relayOnly));
    call.pc = pc;
    const local = call.local;
    if (local) for (const track of local.getAudioTracks()) pc.addTrack(track, local);
    // Every call carries a video section both ways, with or without a camera on it yet, so
    // either side can turn video on or off later without another offer. The caller's comes
    // from here; the callee takes the one the offer brings (answerOffer).
    const outgoing = this.outgoing(call);
    if (outgoing && local) {
      const sender = pc.addTrack(outgoing, local);
      call.video = pc.getTransceivers().find((t) => t.sender === sender) ?? null;
    } else if (call.role === "caller") {
      call.video = pc.addTransceiver("video", { direction: "sendrecv", streams: local ? [local] : [] });
    }
    // Then the screen's picture and sound, both ways and empty until someone shares: after the
    // camera, so each end tells the sections apart by their place (sectionOf).
    if (call.role === "caller") {
      call.screen = pc.addTransceiver("video", { direction: "sendrecv" });
      call.screenSound = pc.addTransceiver("audio", { direction: "sendrecv" });
    }
    this.tune(call);
    pc.onicecandidate = (event) => this.gatheredCandidate(call, event.candidate);
    pc.ontrack = (event) => this.remoteTrack(call, event.track, event.transceiver);
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
      this.tune(call);
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
    this.ensureEphemeral(call);
    const ek = call.ephPublic ? bytesToB64(call.ephPublic) : undefined;
    this.send(call, { t: "offer", sdp: sdpWithoutCandidates(sdp), restart: false, ...(ek ? { ek } : {}) });
    this.negotiated(call);
  }

  private negotiated(call: Call): void {
    call.negotiated = true;
    this.flushCandidates(call);
    this.sendMediaState(call);
    this.drainInbound(call);
  }

  /** A fresh X25519 key for this call. Kept until the other side's public key arrives. */
  private ensureEphemeral(call: Call): void {
    if (call.ephPrivate && call.ephPublic) return;
    const secret = x25519.utils.randomSecretKey();
    call.ephPrivate = secret;
    call.ephPublic = x25519.getPublicKey(secret);
  }

  /**
   * Derives the post-setup keys from both ephemeral publics. An older peer sends none:
   * the identity keys keep sealing the rest of that call.
   */
  private async engageForward(call: Call, theirKey: string | undefined): Promise<void> {
    if (call.forward || !call.id || !call.keys) return;
    const ours = call.ephPrivate;
    const pub = call.ephPublic;
    const identity = call.identitySecret;
    let their: Uint8Array | null = null;
    if (theirKey && ours && pub && identity) {
      try {
        their = b64ToBytes(theirKey);
      } catch {
        their = null;
      }
    }
    // A peer that sent a key must use it. Falling back to the long-term key would seal
    // addresses with a key the other side is no longer opening.
    if (theirKey) {
      if (!(their && their.length === 32 && ours && pub && identity)) return;
      const forward = deriveForwardSecret(identity, ours, pub, their, call.id);
      call.forward = await forwardKeys(forward, call.id, call.role);
    } else {
      call.forward = call.keys;
    }
    ours?.fill(0);
    identity?.fill(0);
    call.ephPrivate = null;
    call.identitySecret = null;
  }

  private noteSafety(call: Call): void {
    call.safety = this.account?.safety?.(call.peer.id) ?? null;
  }

  /** A changed identity key stays on screen until it is trusted or dismissed. */
  private fail(call: Call, err: unknown): void {
    call.keyChanged = err instanceof PeerKeyChanged;
    const visible = call.keyChanged ? 0 : ERROR_VISIBLE_MS;
    this.finish(call, callErrorText(err, call.peer.username), null, visible);
  }

  /* --- signals ----------------------------------------------------------------------------- */

  /**
   * The first offer and the first answer stay under the identity keys (they carry the
   * ephemeral public keys). Everything after uses the per-call key, and waits for it.
   */
  private sealingKeys(call: Call, body: SignalBody): CallKeys | null {
    if ((body.t === "offer" && !body.restart) || (body.t === "answer" && !call.sentAnswer)) return call.keys;
    return call.forward;
  }

  /** Seals and sends one signal, in order behind the ones before it. */
  private send(call: Call, body: SignalBody): void {
    const id = call.id;
    const keys = this.sealingKeys(call, body);
    const api = this.account?.api;
    if (!id || !api) return;
    if (!keys) {
      call.heldOut.push(body);
      return;
    }
    if (body.t === "answer") call.sentAnswer = true;
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

  /** Candidates, media state, and inbound signals that waited for the per-call key. */
  private flushHeld(call: Call): void {
    this.flushCandidates(call);
    if (!call.forward) return;
    const held = call.heldOut.splice(0);
    for (const body of held) this.send(call, body);
    this.drainInbound(call);
  }

  private drainInbound(call: Call): void {
    if (!call.forward) return;
    const inbound = call.heldIn.splice(0);
    for (const item of inbound) {
      call.inbox = call.inbox.then(() => this.receive(call, item.from, item.type, item.payload)).catch(() => undefined);
    }
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

  /** Identity key for the first offer and answer; the per-call key after that. */
  private async openingKey(call: Call, type: string): Promise<CryptoKey | "wait" | null> {
    const setup = type === "sdp_offer" || type === "sdp_answer";
    if (setup && !call.gotSetup) return call.keys?.receive ?? null;
    if (!call.forward) return "wait";
    return call.forward.receive;
  }

  private async receive(call: Call, from: string, type: string, payload: string): Promise<void> {
    if (this.gone(call) || !call.keys || !call.id) return;
    const key = await this.openingKey(call, type);
    if (key === "wait") {
      call.heldIn.push({ from, type, payload });
      return;
    }
    if (!key) return;
    let signal: Signal | null;
    try {
      signal = readSignal(type, await openSignal(key, call.id, type as CallSignalType, payload));
    } catch {
      return; // does not open: not from the peer, or tampered with
    }
    if (!signal || !call.seen.first(from, signal.n)) return;
    try {
      switch (signal.t) {
        case "offer":
          if (call.role === "callee") await this.answerOffer(call, signal.sdp, signal.restart, signal.ek);
          return;
        case "answer":
          if (call.role === "caller") await this.takeAnswer(call, signal.sdp, signal.ek);
          return;
        case "ice":
          await this.addRemoteCandidates(call, signal.cs);
          return;
        case "restart":
          if (call.role === "caller") this.restartIce(call);
          return;
        case "media":
          // The latest wins: the server's kept copy can arrive after a newer one.
          if (!call.seen.newerMedia(from, signal.n)) return;
          call.remoteMic = signal.mic;
          call.remoteCamera = signal.camera;
          // An app that knows screens always says whether it shares one; an older one never does.
          if (signal.screen !== undefined) call.peerShows = true;
          call.remoteScreen = signal.screen === true;
          {
            const view = signal.view ? { width: signal.view.w, height: signal.view.h } : null;
            if (shapeChanged(call.peerView, view)) {
              call.peerView = view;
              call.framed?.setView(view);
            }
          }
          this.publish(call);
          return;
      }
    } catch {
      /* one bad signal; the connect and reconnect timers end a call that never recovers */
    }
  }

  /** Callee: the first offer, or an ICE restart. */
  private async answerOffer(call: Call, sdp: string, restart: boolean, ek?: string): Promise<void> {
    const pc = call.pc;
    if (!pc) return;
    try {
      if (!restart) {
        this.ensureEphemeral(call);
        await this.engageForward(call, ek);
        call.gotSetup = true;
      }
      this.noteFingerprint(call, sdp);
      await pc.setRemoteDescription({ type: "offer", sdp });
      if (this.gone(call)) return;
      this.adoptSections(call, pc);
      await this.flushRemoteCandidates(call);
      const answer = withVoice(await pc.createAnswer());
      await pc.setLocalDescription(answer);
      this.tune(call);
      if (this.gone(call)) return;
      const described = sdpWithoutCandidates(pc.localDescription?.sdp ?? answer.sdp ?? "");
      const ours = !call.sentAnswer && call.ephPublic ? bytesToB64(call.ephPublic) : undefined;
      this.send(call, { t: "answer", sdp: described, ...(ours ? { ek: ours } : {}) });
      if (!call.negotiated) {
        this.negotiated(call);
        // Video is known to be possible (or not) from here.
        this.publish(call);
      } else {
        this.flushHeld(call);
      }
    } catch {
      if (!call.negotiated && !this.gone(call)) this.finish(call, "Couldn’t connect", "hangup");
    }
  }

  /**
   * Callee: the offer's camera and screen sections become ours, both ways. Without a track on one
   * the browser would answer "receive only", and turning video on or sharing later would need a
   * new offer. An older caller's offer lacks the screen's (or, for its voice calls, also the
   * camera's): those stay off in that call.
   */
  private adoptSections(call: Call, pc: RTCPeerConnection): void {
    for (const t of pc.getTransceivers()) {
      if (t.direction === "stopped") continue;
      const section = sectionOf(pc, t);
      const slot = section === "camera" ? "video" : section === "screen" ? "screen" : section === "screenSound" ? "screenSound" : null;
      if (!slot || call[slot]) continue;
      if (t.direction === "recvonly") t.direction = "sendrecv";
      else if (t.direction === "inactive") t.direction = "sendonly";
      call[slot] = t;
    }
  }

  private async takeAnswer(call: Call, sdp: string, ek?: string): Promise<void> {
    const pc = call.pc;
    // An answer to an offer that was taken back (a restart overtook it).
    if (!pc || pc.signalingState !== "have-local-offer") return;
    if (!call.gotSetup) {
      await this.engageForward(call, ek);
      call.gotSetup = true;
    }
    this.noteFingerprint(call, sdp);
    await pc.setRemoteDescription({ type: "answer", sdp });
    this.tune(call);
    await this.flushRemoteCandidates(call);
    this.flushHeld(call);
    // The answer settles whether our video can go out (an older app may have taken it one way).
    this.publish(call);
  }

  /** Remembers the fingerprint in the sealed description, and checks again after a restart. */
  private noteFingerprint(call: Call, sdp: string): void {
    const fingerprint = sdpFingerprint(sdp);
    if (!fingerprint) return;
    if (fingerprint !== call.expectedFingerprint) call.fingerprintChecked = false;
    call.expectedFingerprint = fingerprint;
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

  /** Gathered candidates, a batch per signal. They wait for the per-call key, so an address is never sealed with the identity keys. */
  private flushCandidates(call: Call): void {
    this.stop(call, "batchTimer");
    if (!call.negotiated || !call.forward || this.gone(call)) return;
    while (call.gathered.length > 0) this.send(call, { t: "ice", cs: call.gathered.splice(0, ICE_BATCH_MAX) });
  }

  /** What we send now. A camera the system paused counts as off: they see our face, not a still. */
  private sendMediaState(call: Call): void {
    if (!call.negotiated) return;
    const camera = call.cameraOn && call.camera !== null && !call.cameraMuted;
    call.sentView = call.myView;
    this.send(call, {
      t: "media",
      mic: call.micOn,
      camera,
      screen: call.display !== null,
      ...(call.myView ? { view: call.myView } : {}),
    });
  }

  /* --- media and the connection ------------------------------------------------------------ */

  private remoteTrack(call: Call, track: MediaStreamTrack, transceiver?: RTCRtpTransceiver): void {
    if (this.gone(call)) return;
    const section = call.pc && transceiver ? sectionOf(call.pc, transceiver) : null;
    // A section this app does not know (a later protocol's third of a kind) is not shown.
    if (call.pc && transceiver && section === null) return;
    if (section === "screen" || section === "screenSound") {
      // Their screen goes to a stream of its own; its sound plays in its own slot, so their
      // microphone and a video they share never get in each other's way.
      const display = call.remoteDisplay ?? (call.remoteDisplay = this.env.createStream([]));
      if (!display.getTracks().includes(track)) display.addTrack(track);
      if (section === "screenSound") void this.playRemote(call);
      this.publish(call);
      return;
    }
    const stream = call.remote ?? (call.remote = this.env.createStream([]));
    if (!stream.getTracks().includes(track)) stream.addTrack(track);
    if (track.kind === "video") call.remoteVideo = true;
    else void this.playRemote(call);
    this.publish(call);
  }

  private async playRemote(call: Call): Promise<void> {
    const sound = call.remoteDisplay?.getAudioTracks().length ? call.remoteDisplay : null;
    const [voice, screen] = await Promise.all([
      call.remote ? this.env.playAudio(call.remote) : Promise.resolve(true),
      sound ? this.env.playAudio(sound, "screen") : Promise.resolve(true),
    ]);
    const played = voice && screen;
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
        this.checkFingerprint(call);
        if (call.phase === "connecting") {
          call.phase = "active";
          call.connectedAt = this.env.now();
          this.stop(call, "connectTimer");
          this.watchQuality(call);
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
      this.tune(call);
      if (this.gone(call)) return;
      this.send(call, {
        t: "offer",
        sdp: sdpWithoutCandidates(pc.localDescription?.sdp ?? offer.sdp ?? ""),
        restart: true,
      });
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
      this.catchUpMedia(call, info);
      return;
    }
    this.finish(call, endedText(info.status, info.ended_reason, call.role), null);
  }

  /**
   * The other device's latest media state as the server kept it: a camera switch whose signal
   * was lost in a socket gap still arrives, at the latest with the next heartbeat. It is opened
   * and checked like any signal; one already taken, or older than one taken, changes nothing.
   */
  private catchUpMedia(call: Call, info: CallInfo): void {
    const kept = info.peer_media_state;
    if (!kept?.payload || !call.peerDevice || !sameId(kept.from_device_id, call.peerDevice)) return;
    const from = kept.from_device_id;
    call.inbox = call.inbox.then(() => this.receive(call, from, "media_state", kept.payload)).catch(() => undefined);
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
    if (call.keyChanged) return;
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
      "cameraTimer",
      "viewTimer",
    ] as const) {
      this.stop(call, key);
    }
    this.stopInterval(call, "heartbeat");
    this.stopInterval(call, "ringCheck");
    this.stopInterval(call, "qualityCheck");
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
    const camera = call.camera;
    if (camera) {
      camera.onmute = null;
      camera.onunmute = null;
      camera.onended = null;
      camera.stop();
    }
    call.framed?.stop();
    call.framed = null;
    for (const track of call.display?.getTracks() ?? []) track.onended = null;
    stopTracks(call.local);
    stopTracks(call.remote);
    stopTracks(call.display);
    stopTracks(call.remoteDisplay);
    call.local = null;
    call.remote = null;
    call.display = null;
    call.remoteDisplay = null;
    call.screen = null;
    call.screenSound = null;
    call.screenPending = false;
    call.remoteScreen = false;
    call.video = null;
    call.camera = null;
    call.cameraOn = false;
    call.cameraPending = false;
    call.remoteVideo = false;
    call.audioBlocked = false;
    void this.env.playAudio(null);
    void this.env.playAudio(null, "screen");
    call.ephPrivate?.fill(0);
    call.identitySecret?.fill(0);
    call.ephPrivate = null;
    call.ephPublic = null;
    call.identitySecret = null;
    call.keys = null;
    call.forward = null;
    call.heldIn = [];
    call.heldOut = [];
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
      cameraOn: call.cameraOn && call.camera !== null,
      cameraPending: call.cameraPending,
      canVideo: this.videoSendable(call),
      canSwitchCamera: call.canSwitchCamera,
      centerStage: this.centerStage,
      canCenterStage: call.cameraOn && call.framed !== null && call.framed.canFollow(),
      mirrorSelf: call.mirrorSelf,
      remoteMic: call.remoteMic,
      remoteCamera: call.remoteCamera,
      remoteVideo: call.remoteVideo,
      screenOn: call.display !== null,
      screenPending: call.screenPending,
      screenSound: (call.display?.getAudioTracks().length ?? 0) > 0,
      shareSupported: typeof this.env.getDisplayMedia === "function",
      canShare: this.screenSendable(call),
      screenQuality: this.screenQuality,
      remoteScreen: call.remoteScreen,
      localStream: call.local,
      remoteStream: call.remote,
      screenStream: call.display,
      remoteScreenStream: call.remoteDisplay,
      audioBlocked: call.audioBlocked,
      endedText: call.endedText,
      notice: call.notice,
      minimized: call.minimized,
      safety: call.safety,
      keyChanged: call.keyChanged,
    });
  }

  /** Hangs up when the certificate is not the one named in the sealed description. */
  private checkFingerprint(call: Call): void {
    const read = this.env.remoteFingerprint;
    const expected = call.expectedFingerprint;
    const pc = call.pc;
    if (!read || !expected || !pc || call.fingerprintChecked) return;
    void (async () => {
      let got = await read(pc).catch(() => null);
      if (!got && !this.gone(call)) {
        await new Promise<void>((resolve) => this.env.setTimeout(resolve, 400));
        if (this.gone(call) || call.pc !== pc) return;
        got = await read(pc).catch(() => null);
      }
      if (this.gone(call) || call.pc !== pc || !got) return;
      call.fingerprintChecked = true;
      if (!fingerprintsMatch(expected, got)) {
        this.finish(call, "This call couldn’t be verified.", "hangup", ERROR_VISIBLE_MS);
      }
    })();
  }
}
