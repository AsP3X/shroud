import { ApiError, type CallModality, type CallSignalType } from "../api/client";
import { b64ToBytes } from "../crypto/bytes";
import { PeerKeyChanged } from "../crypto/peerIdentity";
import type { CallRole } from "./crypto";

/*
 * The parts of a call that need no browser: what each state says, which signals to believe, when
 * to try an ICE restart. The controller (controller.ts) drives these; the selftest checks them.
 */

/** A call ringing out, ringing in, being connected, running, or just over (idle is no call). */
export type CallPhase = "outgoing" | "incoming" | "connecting" | "active" | "ended";

/**
 * An incoming call stays a banner until it is opened or connects. The line that says it ended
 * stays there too: a ring the callee never opened must not take over the screen.
 * Once it connects, `openedKey` is that call's key, so the ending uses the full screen.
 */
export function incomingStaysInBanner(
  view: { key: number; phase: CallPhase; role: CallRole },
  openedKey: number | null,
): boolean {
  if (view.role !== "callee" || openedKey === view.key) return false;
  return view.phase === "incoming" || view.phase === "ended";
}

export type CallPeer = { id: string; username: string };

/** What the call screen shows; one object per change (the store hands it to React). */
export type CallView = {
  /** New for each call, so the screen can tell a new call from an update. */
  key: number;
  phase: CallPhase;
  callId: string | null;
  role: CallRole;
  modality: CallModality;
  peer: CallPeer;
  /** Outgoing, before the server confirmed the ring ("Calling…", then "Ringing…"). */
  dialing: boolean;
  /** Media dropped mid-call and ICE is recovering. */
  reconnecting: boolean;
  /** When media first connected (epoch ms), for the timer. */
  connectedAt: number | null;
  micOn: boolean;
  /** Our camera is on and sent: with theirs, it makes a voice call a video call. */
  cameraOn: boolean;
  /** The camera is being opened (Video was pressed). */
  cameraPending: boolean;
  /** This call can carry our video. False only with an older app on the other side. */
  canVideo: boolean;
  canSwitchCamera: boolean;
  /** The self-view shows a front camera, so it is mirrored. */
  mirrorSelf: boolean;
  /** What the other side says it sends (`media_state`). */
  remoteMic: boolean;
  remoteCamera: boolean;
  /** The other side's video track is there (every call has one; it carries frames while their camera is on). */
  remoteVideo: boolean;
  /** We share our screen (a tab, a window or a whole screen), next to the camera. */
  screenOn: boolean;
  /** The browser's picker is open, or the chosen screen is being put on the call. */
  screenPending: boolean;
  /** Our share carries the tab's or the system's sound. */
  screenSound: boolean;
  /** This browser can share a screen at all (phones cannot). */
  shareSupported: boolean;
  /** This call can carry our screen and their app can show it. False with an older app on either side. */
  canShare: boolean;
  /** What they say they send (`media_state`): their screen. */
  remoteScreen: boolean;
  localStream: MediaStream | null;
  remoteStream: MediaStream | null;
  /** Our shared screen, for the small preview while it goes out. */
  screenStream: MediaStream | null;
  /** Their shared screen (and its sound), apart from their camera. */
  remoteScreenStream: MediaStream | null;
  /** The browser refused to play the other side's audio until a click. */
  audioBlocked: boolean;
  /** Said once it ended; the screen closes at once when there is nothing to say. */
  endedText: string | null;
  /** A passing note, e.g. that the camera could not open. */
  notice: string | null;
  /** Collapsed into the floating pill so the chats stay usable. */
  minimized: boolean;
  /** The safety number, when this contact's key is pinned and has not been compared. */
  safety: { number: string; verified: boolean } | null;
  /** The call ended because their identity key changed. The screen can trust the new one. */
  keyChanged: boolean;
};

/* Timings from docs/calls.md, and the client's own. */
export const HEARTBEAT_MS = 10_000;
/** The server rings for 60 s; past this the caller gives up by itself. */
export const OUTGOING_RING_LIMIT_MS = 75_000;
/** A ring nobody ended by then is over (the server's ring is 60 s). */
export const INCOMING_RING_LIMIT_MS = 70_000;
/** No media within this long of the answer: hang up ("Couldn't connect"). */
export const CONNECT_TIMEOUT_MS = 30_000;
/** `disconnected` this long counts as broken: time for an ICE restart. */
export const DISCONNECTED_GRACE_MS = 4_000;
export const RESTART_GAP_MS = 10_000;
/** Reconnecting this long ends the call ("Connection lost"). */
export const RECONNECT_LIMIT_MS = 30_000;
/** Candidates gathered this close together travel in one signal. */
export const ICE_BATCH_MS = 100;
export const ICE_BATCH_MAX = 20;
/** Video off: nothing more is sent at once, and the camera closes this much later, once our own
 *  picture has faded out. Video back on within it takes the same camera, without asking again. */
export const CAMERA_RELEASE_MS = 300;
/** How long the ended screen stays; errors a little longer, to be read. */
export const ENDED_VISIBLE_MS = 2_000;
export const ERROR_VISIBLE_MS = 4_000;
export const NOTICE_VISIBLE_MS = 6_000;

/** An error whose text is ready for the call screen. */
export class CallFailure extends Error {
  constructor(message: string) {
    super(message);
    this.name = "CallFailure";
  }
}

/**
 * What a side shows when a call ends with this status and reason (the table in docs/calls.md);
 * null shows nothing. One deviation: a caller whose own ring the server dropped for silence
 * (`cancelled`/`connection_lost`, e.g. a laptop that slept) reads "Connection lost".
 */
export function endedText(status: string, reason: string | null | undefined, role: CallRole): string | null {
  const caller = role === "caller";
  switch (status) {
    case "rejected":
      return caller ? "Declined" : null;
    case "missed":
      if (reason === "declined") return caller ? "Declined" : null;
      return caller ? "No answer" : "Missed call";
    case "cancelled":
      if (!caller) return "Missed call";
      return reason === "connection_lost" ? "Connection lost" : null;
    case "ended":
      return reason === "connection_lost" ? "Connection lost" : "Call ended";
    default:
      return "Call ended";
  }
}

/** Still ringing or running: nothing to end. */
export function isLive(status: string): boolean {
  return status === "ringing" || status === "active";
}

/** A failed call request, for the call screen. */
export function callErrorText(err: unknown, peerName: string): string {
  if (err instanceof PeerKeyChanged) return err.message;
  if (err instanceof CallFailure) return err.message;
  if (err instanceof ApiError) {
    switch (err.code) {
      case "CALL_BUSY":
        return `${peerName} is on another call.`;
      case "CALL_IN_PROGRESS":
        return "You’re already in a call.";
      case "FORBIDDEN":
        return `You can’t call ${peerName}.`;
      case "RATE_LIMITED":
        return "Too many calls. Try again in a moment.";
      case "transport":
        return "Couldn’t reach Shroud. Check your connection.";
    }
  }
  return "Couldn’t start the call.";
}

function errorName(err: unknown): string {
  if (err && typeof err === "object" && "name" in err && typeof err.name === "string") return err.name;
  return "";
}

/** Why the microphone would not open, and what to do about it. */
export function mediaErrorText(err: unknown, answering: boolean): string {
  if (err instanceof CallFailure) return err.message;
  switch (errorName(err)) {
    case "NotAllowedError":
    case "SecurityError":
      return answering
        ? "Allow microphone access in your browser to answer calls."
        : "Allow microphone access in your browser to call.";
    case "NotFoundError":
    case "OverconstrainedError":
      return "No microphone found. Connect one and try again.";
    case "NotReadableError":
    case "AbortError":
      return "Your microphone is in use by another app.";
  }
  return "Couldn’t start your microphone.";
}

/** Camera errors worth another try without the camera (the call then goes on audio only). */
export function cameraOnlyFailure(err: unknown): boolean {
  return ["NotAllowedError", "NotFoundError", "NotReadableError", "OverconstrainedError", "AbortError"].includes(
    errorName(err),
  );
}

export const CAMERA_UNAVAILABLE = "Your camera isn’t available, so your video is off.";
export const VIDEO_UNAVAILABLE = "Video isn’t available in this call. Their app needs an update.";
export const SCREEN_UNAVAILABLE = "Screen sharing isn’t available in this call. Their app needs an update.";
export const SCREEN_ENDED = "Your screen is no longer shared.";
export const SCREEN_NOT_YET = "You can share your screen once the call has connected.";

/**
 * Why the screen could not be shared, or null when the person closed the picker (nothing to say).
 * Browsers report both a cancelled picker and a refusal by the system as NotAllowedError; only
 * the system's names it.
 */
export function screenErrorText(err: unknown): string | null {
  switch (errorName(err)) {
    case "NotAllowedError":
    case "SecurityError": {
      const message = err && typeof err === "object" && "message" in err ? String(err.message) : "";
      return /system/i.test(message)
        ? "Allow screen recording for your browser in your computer’s settings, then try again."
        : null;
    }
    case "NotFoundError":
      return "There’s no screen to share.";
    case "NotReadableError":
    case "AbortError":
      return "Your screen couldn’t be captured. Try again.";
  }
  return "Couldn’t share your screen.";
}

/** Why the camera would not open when Video was pressed mid-call. */
export function cameraErrorText(err: unknown): string {
  switch (errorName(err)) {
    case "NotAllowedError":
    case "SecurityError":
      return "Allow camera access in your browser to turn on video.";
    case "NotFoundError":
    case "OverconstrainedError":
      return "No camera found.";
    case "NotReadableError":
    case "AbortError":
      return "Your camera is in use by another app.";
  }
  return CAMERA_UNAVAILABLE;
}

/**
 * The picture that fills the screen, if any: theirs once it shows, else ours while the call is
 * still being placed or connected (as FaceTime does). During the call our own camera stays in the
 * corner over their face, so it is always clear whether they can see us or only we see us.
 */
export function videoLayout(
  view: Pick<CallView, "phase">,
  mine: boolean,
  theirs: boolean,
): "theirs" | "mine" | null {
  if (theirs) return "theirs";
  if (mine && (view.phase === "outgoing" || view.phase === "connecting")) return "mine";
  return null;
}

/** 00:42, 12:05, 1:02:03. */
export function callClock(ms: number): string {
  const total = Math.max(0, Math.floor((Number.isFinite(ms) ? ms : 0) / 1000));
  const hours = Math.floor(total / 3600);
  const minutes = Math.floor((total % 3600) / 60);
  const seconds = total % 60;
  const two = (value: number) => value.toString().padStart(2, "0");
  return hours > 0 ? `${hours}:${two(minutes)}:${two(seconds)}` : `${two(minutes)}:${two(seconds)}`;
}

/** The line under the name. */
export function statusLine(view: CallView, now: number): string {
  switch (view.phase) {
    case "outgoing":
      return view.dialing ? "Calling…" : "Ringing…";
    case "incoming":
      return view.modality === "video" ? "Incoming video call" : "Incoming voice call";
    case "connecting":
      return "Connecting…";
    case "active":
      return view.reconnecting ? "Reconnecting…" : callClock(now - (view.connectedAt ?? now));
    case "ended":
      return view.endedText ?? "Call ended";
  }
}

/* --- signals ---------------------------------------------------------------------------------- */

export type IceCandidateJson = { candidate: string; sdpMid: string | null; sdpMLineIndex: number | null };

/** The plaintext of a sealed signal (docs/calls.md), `n` counting up per sending device. */
export type Signal =
  | { t: "offer"; sdp: string; restart: boolean; n: number; ek?: string }
  | { t: "answer"; sdp: string; n: number; ek?: string }
  | { t: "ice"; cs: IceCandidateJson[]; n: number }
  | { t: "restart"; n: number }
  | { t: "media"; mic: boolean; camera: boolean; screen?: boolean; n: number };

/** A signal's body before the sender numbers it. */
export type SignalBody =
  | { t: "offer"; sdp: string; restart: boolean; ek?: string }
  | { t: "answer"; sdp: string; ek?: string }
  | { t: "ice"; cs: IceCandidateJson[] }
  | { t: "restart" }
  | { t: "media"; mic: boolean; camera: boolean; screen: boolean };

const SIGNAL_TYPE: Record<Signal["t"], CallSignalType> = {
  offer: "sdp_offer",
  answer: "sdp_answer",
  ice: "ice_candidate",
  restart: "renegotiate",
  media: "media_state",
};

/** The `signal_type` a plaintext travels under. */
export function signalTypeOf(t: Signal["t"]): CallSignalType {
  return SIGNAL_TYPE[t];
}

const VOICE_FMTP = ["useinbandfec=1", "usedtx=1", "stereo=0", "sprop-stereo=0", "maxaveragebitrate=32000"];
/** A shared screen's sound is music as often as speech: stereo, never silenced, room to breathe. */
const SCREEN_SOUND_FMTP = ["useinbandfec=1", "usedtx=0", "stereo=1", "sprop-stereo=1", "maxaveragebitrate=128000"];

/**
 * Opus for a voice call: error correction and silence suppression, mono, about 32 kbps.
 * An SDP with no Opus line is unchanged. Line endings are kept. A parameter is replaced
 * only when it is its own key, so `stereo` does not rewrite `sprop-stereo`.
 */
export function voiceSdp(sdp: string): string {
  const eol = sdp.includes("\r\n") ? "\r\n" : "\n";
  const lines = sdp.split(/\r\n|\n/);
  const map = lines.map(opusPayload).find((pt) => pt);
  if (!map) return lines.join(eol);
  const index = lines.findIndex((line) => isFmtp(line, map));
  if (index < 0) {
    const at = lines.findIndex((line) => isRtpmap(line, map));
    if (at < 0) return lines.join(eol);
    lines.splice(at + 1, 0, `a=fmtp:${map} ${VOICE_FMTP.join(";")}`);
    return lines.join(eol);
  }
  lines[index] = VOICE_FMTP.reduce(applyParam, lines[index]);
  return lines.join(eol);
}

/**
 * VP8 first on the screen's picture (the second video section; the first is the camera). A
 * phone shares its screen from the background, where its hardware H.264 encoder may not run; VP8
 * is encoded in software everywhere. Both sides put it first in their own descriptions, so it is
 * what either one sends there whoever offers. An SDP without that section, or without VP8, is
 * unchanged.
 */
export function screenVideoSdp(sdp: string): string {
  const eol = sdp.includes("\r\n") ? "\r\n" : "\n";
  const lines = sdp.split(/\r\n|\n/);
  const starts = lines.flatMap((line, index) => (/^m=video[ \t]/i.test(line) ? [index] : []));
  if (starts.length < 2) return lines.join(eol);
  const from = starts[1];
  let to = lines.findIndex((line, index) => index > from && /^m=/i.test(line));
  if (to < 0) to = lines.length;
  const vp8 = new Set(
    lines.slice(from, to).flatMap((line) => {
      const match = /^a=rtpmap:(\d+) VP8\/90000/i.exec(line);
      return match ? [match[1]] : [];
    }),
  );
  if (vp8.size === 0) return lines.join(eol);
  const parts = lines[from].split(" ");
  // m=video <port> <proto> <payload types…>
  const types = parts.slice(3);
  lines[from] = [...parts.slice(0, 3), ...types.filter((pt) => vp8.has(pt)), ...types.filter((pt) => !vp8.has(pt))].join(" ");
  return lines.join(eol);
}

/**
 * Opus for the screen's sound: the second audio section (the first is the microphone), stereo at
 * about 128 kbps. Each section has its own `a=fmtp`, so the microphone keeps its speech settings.
 * An SDP without that section (an older app) is unchanged.
 */
export function screenSoundSdp(sdp: string): string {
  const eol = sdp.includes("\r\n") ? "\r\n" : "\n";
  const lines = sdp.split(/\r\n|\n/);
  const starts = lines.flatMap((line, index) => (/^m=audio[ \t]/i.test(line) ? [index] : []));
  if (starts.length < 2) return lines.join(eol);
  const from = starts[1];
  let to = lines.findIndex((line, index) => index > from && /^m=/i.test(line));
  if (to < 0) to = lines.length;
  const map = lines.slice(from, to).map(opusPayload).find((pt) => pt);
  if (!map) return lines.join(eol);
  const index = lines.findIndex((line, at) => at > from && at < to && isFmtp(line, map));
  if (index < 0) {
    const at = lines.findIndex((line, i) => i > from && i < to && isRtpmap(line, map));
    if (at < 0) return lines.join(eol);
    lines.splice(at + 1, 0, `a=fmtp:${map} ${SCREEN_SOUND_FMTP.join(";")}`);
    return lines.join(eol);
  }
  lines[index] = SCREEN_SOUND_FMTP.reduce(applyParam, lines[index]);
  return lines.join(eol);
}

function opusPayload(line: string): string | undefined {
  return /^a=rtpmap:(\d+) opus\/48000/i.exec(line)?.[1];
}

function isRtpmap(line: string, pt: string): boolean {
  return new RegExp(`^a=rtpmap:${pt}[ \\t]`, "i").test(line);
}

function isFmtp(line: string, pt: string): boolean {
  const match = new RegExp(`^a=fmtp:${pt}(?=$|[ \\t;])`, "i").exec(line);
  return Boolean(match);
}

/** Sets `key=value` from an `extra` of that shape, or appends it. */
function applyParam(line: string, extra: string): string {
  const eq = extra.indexOf("=");
  const key = extra.slice(0, eq);
  const token = `${key}=`;
  let from = 0;
  while (from <= line.length) {
    const at = line.indexOf(token, from);
    if (at < 0) break;
    const prev = at === 0 ? "" : line[at - 1];
    if (at === 0 || prev === ";" || prev === " " || prev === "\t") {
      let end = at + token.length;
      while (end < line.length && line[end] !== ";" && line[end] !== " " && line[end] !== "\t") end += 1;
      return line.slice(0, at) + extra + line.slice(end);
    }
    from = at + token.length;
  }
  return `${line};${extra}`;
}

/**
 * A fresh X25519 public key on the first offer or answer, standard base64 of 32 bytes.
 * Absent is an older peer. Present but not a key rejects the signal.
 */
function readEphemeral(value: unknown): string | false | undefined {
  if (value === undefined) return undefined;
  if (typeof value !== "string") return false;
  try {
    const bytes = b64ToBytes(value);
    if (bytes.length !== 32) return false;
    return value;
  } catch {
    return false;
  }
}

/** ICE addresses leave the session description. They travel later, under the per-call key. */
export function sdpWithoutCandidates(sdp: string): string {
  const eol = sdp.includes("\r\n") ? "\r\n" : "\n";
  return sdp
    .split(/\r\n|\n/)
    .filter((line) => !/^a=candidate:/i.test(line))
    .join(eol);
}

/** The SHA-256 DTLS fingerprint in a session description, lowercase, or null when it has none. */
export function sdpFingerprint(sdp: string): string | null {
  const match = /^a=fingerprint:sha-256[ \t]+([0-9a-f:]+)[ \t]*$/im.exec(sdp);
  return match ? match[1].toLowerCase() : null;
}

/** The certificate fingerprint and the one in the sealed description name the same certificate. */
export function fingerprintsMatch(sdpPrint: string, certPrint: string): boolean {
  const norm = (value: string) => value.replace(/[\s:]/g, "").toLowerCase();
  const left = norm(sdpPrint);
  const right = norm(certPrint);
  return left.length > 0 && left === right;
}

/** More candidates than any real batch holds: the rest are dropped. */
const MAX_CANDIDATES = 64;

function readCandidate(raw: unknown): IceCandidateJson | null {
  if (!raw || typeof raw !== "object") return null;
  const c = raw as Record<string, unknown>;
  if (typeof c.candidate !== "string" || !c.candidate) return null;
  const sdpMid = typeof c.sdpMid === "string" ? c.sdpMid : null;
  const index = c.sdpMLineIndex;
  const sdpMLineIndex = typeof index === "number" && Number.isInteger(index) && index >= 0 ? index : null;
  // A candidate needs one of the two to find its media section.
  if (sdpMid === null && sdpMLineIndex === null) return null;
  return { candidate: c.candidate, sdpMid, sdpMLineIndex };
}

/**
 * An opened plaintext as a signal, or null when it is malformed or not what `signalType` says
 * (the type in the additional data is authenticated; a body claiming another is dropped).
 */
export function readSignal(signalType: string, value: Record<string, unknown>): Signal | null {
  const n = value.n;
  if (typeof n !== "number" || !Number.isSafeInteger(n) || n < 1) return null;
  const t = value.t;
  if (typeof t !== "string" || !(t in SIGNAL_TYPE) || SIGNAL_TYPE[t as Signal["t"]] !== signalType) return null;
  switch (t) {
    case "offer": {
      if (typeof value.sdp !== "string" || !value.sdp) return null;
      const ek = readEphemeral(value.ek);
      if (ek === false) return null;
      return { t, sdp: value.sdp, restart: value.restart === true, n, ...(ek ? { ek } : {}) };
    }
    case "answer": {
      if (typeof value.sdp !== "string" || !value.sdp) return null;
      const ek = readEphemeral(value.ek);
      if (ek === false) return null;
      return { t, sdp: value.sdp, n, ...(ek ? { ek } : {}) };
    }
    case "ice": {
      if (!Array.isArray(value.cs)) return null;
      const cs = value.cs
        .slice(0, MAX_CANDIDATES)
        .map(readCandidate)
        .filter((c): c is IceCandidateJson => c !== null);
      return { t, cs, n };
    }
    case "restart":
      return { t, n };
    case "media":
      if (typeof value.mic !== "boolean" || typeof value.camera !== "boolean") return null;
      // Absent from an app that cannot share or show a screen; anything else but a boolean is wrong.
      if (value.screen !== undefined && typeof value.screen !== "boolean") return null;
      return { t, mic: value.mic, camera: value.camera, ...(value.screen !== undefined ? { screen: value.screen } : {}), n };
  }
  return null;
}

/** The `n` values each sending device has used, so a signal delivered twice counts once. */
export class SeenSignals {
  private readonly seen = new Map<string, Set<number>>();
  private readonly media = new Map<string, number>();

  /** True the first time this device's `n` comes by. */
  first(deviceId: string, n: number): boolean {
    const key = deviceId.toLowerCase();
    let used = this.seen.get(key);
    if (!used) {
      used = new Set();
      this.seen.set(key, used);
    }
    if (used.has(n)) return false;
    used.add(n);
    return true;
  }

  /**
   * True when a media state numbered `n` is newer than the last one taken from this device.
   * The server hands back the latest it kept (on a heartbeat, after a reconnect), and that answer
   * can arrive after a newer one came over the socket: the older one must not undo it.
   */
  newerMedia(deviceId: string, n: number): boolean {
    const key = deviceId.toLowerCase();
    if (n <= (this.media.get(key) ?? 0)) return false;
    this.media.set(key, n);
    return true;
  }
}

/** At most one ICE restart (or request for one) per `RESTART_GAP_MS`. */
export class RestartGate {
  private last = Number.NEGATIVE_INFINITY;

  /** How long until the next one may go; 0 when it may go now. */
  waitMs(now: number): number {
    return Math.max(0, this.last + RESTART_GAP_MS - now);
  }

  mark(now: number): void {
    this.last = now;
  }
}

export type LinkState = "new" | "connecting" | "connected" | "disconnected" | "failed" | "closed";

const LINK_STATES: readonly string[] = ["new", "connecting", "connected", "disconnected", "failed", "closed"];

/** The peer connection's state; from ICE alone where a browser has no `connectionState`. */
export function linkState(connectionState: string | undefined, iceConnectionState: string | undefined): LinkState {
  if (connectionState && LINK_STATES.includes(connectionState)) return connectionState as LinkState;
  switch (iceConnectionState) {
    case "checking":
      return "connecting";
    case "connected":
    case "completed":
      return "connected";
    case "disconnected":
      return "disconnected";
    case "failed":
      return "failed";
    case "closed":
      return "closed";
    default:
      return "new";
  }
}

/** Ids compare without case (the server writes them lowercase; iOS may not). */
export function sameId(a: string | null | undefined, b: string | null | undefined): boolean {
  return Boolean(a) && Boolean(b) && a!.toLowerCase() === b!.toLowerCase();
}
