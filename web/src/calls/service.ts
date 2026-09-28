import { api, type CallModality } from "../api/client";
import { loadNotificationPrefs } from "../notifications/prefs";
import { closeNotifications, notificationPermission, showPageNotification } from "../notifications/push";
import type { RealtimeEvent } from "../realtime";
import { holdAutoLock } from "../session";
import { stopVoice } from "../voice/playback";
import { interruptVoiceRecord } from "../voice/recorder";
import { safetyNumber } from "../crypto/safetyNumber";
import {
  acceptChangedPeerKey,
  markPeerKeyVerified,
  peerKeyVerified,
  storedPeerKey,
} from "../crypto/peerIdentity";
import { CallController, type CallEnv, type IdentityKeys } from "./controller";
import type { CallPeer } from "./logic";
import { alwaysRelaysCalls } from "./relay";
import { publishCallView } from "./store";
import { playCallTone, primeCallTones } from "./tones";

/*
 * Calls for the open chat shell: one controller for the page, wired to the browser. AppShell
 * configures it while unlocked and feeds it the socket's `call.*` events; the call screen
 * (components/CallOverlay.tsx) reads the store and calls the actions below.
 */

/** The tag every call notification shares, pushes included (a missed call replaces the ring). */
const CALLS_TAG = "calls";

/* --- the browser ------------------------------------------------------------------------------ */

let audioOut: HTMLAudioElement | null = null;

/** The other side's audio, through one hidden element the call screen cannot unmount. */
async function playAudio(stream: MediaStream | null): Promise<boolean> {
  if (!stream) {
    if (audioOut) {
      audioOut.pause();
      audioOut.srcObject = null;
      audioOut.remove();
      audioOut = null;
    }
    return true;
  }
  if (!audioOut) {
    audioOut = document.createElement("audio");
    audioOut.autoplay = true;
    audioOut.hidden = true;
    audioOut.setAttribute("playsinline", "");
    document.body.appendChild(audioOut);
  }
  if (audioOut.srcObject !== stream) audioOut.srcObject = stream;
  try {
    await audioOut.play();
    return true;
  } catch (err) {
    // Only a refusal needs the click; an interrupted play() just starts over.
    return !(err instanceof DOMException && err.name === "NotAllowedError");
  }
}

let wakeLock: WakeLockSentinel | null = null;
let wantAwake = false;

async function requestWakeLock(): Promise<void> {
  if (!wantAwake || wakeLock || document.hidden || !("wakeLock" in navigator)) return;
  try {
    const sentinel = await navigator.wakeLock.request("screen");
    if (!wantAwake) {
      void sentinel.release().catch(() => undefined);
      return;
    }
    wakeLock = sentinel;
    sentinel.addEventListener("release", () => {
      if (wakeLock === sentinel) wakeLock = null;
    });
  } catch {
    /* refused (battery saver, a hidden page): best effort */
  }
}

/** The screen stays on while a call runs; a hidden page loses the lock and asks again on return. */
function keepAwake(on: boolean): void {
  wantAwake = on;
  if (on) {
    void requestWakeLock();
    return;
  }
  const held = wakeLock;
  wakeLock = null;
  void held?.release().catch(() => undefined);
}

function notifyRing(ring: { peer: CallPeer; modality: CallModality; missed: boolean } | null): void {
  if (!ring) {
    void closeNotifications(CALLS_TAG);
    return;
  }
  const prefs = loadNotificationPrefs();
  const watching = !document.hidden && document.hasFocus();
  if (watching || !prefs.enabled || notificationPermission() !== "granted") {
    if (ring.missed) void closeNotifications(CALLS_TAG);
    return;
  }
  void showPageNotification({
    kind: ring.missed ? "missed_call" : ring.modality === "video" ? "video_call" : "call",
    tag: CALLS_TAG,
    peer: ring.peer.id,
    title: prefs.showSender ? ring.peer.username : "Shroud",
    body: ring.missed ? "Missed call" : ring.modality === "video" ? "Incoming video call" : "Incoming call",
    silent: prefs.sound === "none",
    requireInteraction: !ring.missed,
  });
}

let channel: BroadcastChannel | null | undefined;

/** Tabs of one browser share a device id, so the server tells them apart from nobody: they tell each other. */
function tabs(): BroadcastChannel | null {
  if (channel !== undefined) return channel;
  try {
    channel = new BroadcastChannel("shroud.calls");
    channel.onmessage = (event: MessageEvent) => {
      const taken = (event.data as { taken?: unknown } | null)?.taken;
      if (typeof taken === "string") controller.takenElsewhere(taken);
    };
  } catch {
    channel = null;
  }
  return channel;
}

/** The SHA-256 fingerprint of the certificate the handshake actually used. */
async function remoteFingerprint(pc: RTCPeerConnection): Promise<string | null> {
  if (typeof pc.getStats !== "function") return null;
  const stats = await pc.getStats();
  let remoteId: string | null = null;
  for (const report of stats.values()) {
    const row = report as { type?: string; remoteCertificateId?: string };
    if (row.type === "transport" && typeof row.remoteCertificateId === "string") {
      remoteId = row.remoteCertificateId;
      break;
    }
  }
  if (!remoteId) return null;
  const cert = stats.get(remoteId) as { type?: string; fingerprint?: string; fingerprintAlgorithm?: string } | undefined;
  if (!cert || cert.type !== "certificate" || typeof cert.fingerprint !== "string") return null;
  const algorithm = cert.fingerprintAlgorithm?.toLowerCase();
  if (algorithm && algorithm !== "sha-256") return null;
  return cert.fingerprint;
}

function unsupported(): string | null {
  if (typeof window !== "undefined" && window.isSecureContext === false) {
    return "Calls need a secure (https) connection.";
  }
  if (typeof RTCPeerConnection === "undefined" || !navigator.mediaDevices?.getUserMedia) {
    return "This browser can’t make calls.";
  }
  return null;
}

const env: CallEnv = {
  unsupported,
  getUserMedia: (constraints) => navigator.mediaDevices.getUserMedia(constraints),
  cameras: async () =>
    (await navigator.mediaDevices.enumerateDevices())
      .filter((device) => device.kind === "videoinput")
      .map((device) => device.deviceId),
  createPeer: (config) => new RTCPeerConnection(config),
  alwaysRelay: alwaysRelaysCalls,
  createStream: (tracks) => new MediaStream(tracks),
  now: () => Date.now(),
  setTimeout: (run, ms) => window.setTimeout(run, ms),
  clearTimeout: (id) => window.clearTimeout(id),
  setInterval: (run, ms) => window.setInterval(run, ms),
  clearInterval: (id) => window.clearInterval(id),
  publish: publishCallView,
  tone: playCallTone,
  playAudio,
  keepAwake,
  holdAutoLock,
  interruptVoice: () => {
    stopVoice();
    interruptVoiceRecord();
  },
  notifyRing,
  tellTabs: (callId) => tabs()?.postMessage({ taken: callId }),
  remoteFingerprint,
};

const controller = new CallController(env);

/* --- configuration -------------------------------------------------------------------------- */

let generation = 0;

function onPageHide(): void {
  controller.pageHide();
}

function onVisible(): void {
  if (!document.hidden) void requestWakeLock();
}

/** A click or key press lets the ringtone sound later, when a ring comes without one. */
function onFirstGesture(): void {
  primeCallTones();
  window.removeEventListener("pointerdown", onFirstGesture);
  window.removeEventListener("keydown", onFirstGesture);
}

/**
 * Calls for the signed-in account, while the chats are unlocked. The disposer hangs up a call
 * this device is in (a lock, a sign-out); a newer configuration (StrictMode's second mount)
 * takes over from an older one.
 */
export function configureCalls(options: {
  token: string;
  userId: string;
  deviceId: string;
  identity: () => IdentityKeys | null;
  peerKey: (userId: string) => Promise<Uint8Array>;
  peerName: (userId: string) => string | null;
}): () => void {
  const mine = ++generation;
  const { token } = options;
  controller.configure({
    userId: options.userId,
    deviceId: options.deviceId,
    identity: options.identity,
    peerKey: options.peerKey,
    peerName: options.peerName,
    safety: (userId) => {
      const local = options.identity();
      const peer = storedPeerKey(userId);
      if (!local || !peer) return null;
      const number = safetyNumber(local.publicKey, peer);
      local.privateKey.fill(0);
      return { number, verified: peerKeyVerified(userId) };
    },
    confirmSafety: (userId) => markPeerKeyVerified(userId),
    acceptKey: (userId) => {
      acceptChangedPeerKey(userId, options.userId);
    },
    api: {
      iceServers: async () => (await api.iceServers(token)).ice_servers ?? [],
      createCall: (peerUserId, modality) => api.createCall(token, peerUserId, modality),
      getCall: (callId) => api.getCall(token, callId),
      acceptCall: (callId) => api.acceptCall(token, callId),
      rejectCall: (callId) => api.rejectCall(token, callId),
      hangupCall: (callId, keepalive) => api.hangupCall(token, callId, { keepalive }),
      sendSignal: (callId, signalType, payload) => api.sendCallSignal(token, callId, signalType, payload),
      heartbeat: (callId) => api.callHeartbeat(token, callId),
    },
  });
  tabs();
  window.addEventListener("pagehide", onPageHide);
  document.addEventListener("visibilitychange", onVisible);
  window.addEventListener("pointerdown", onFirstGesture);
  window.addEventListener("keydown", onFirstGesture);
  // Rings pushed while this browser was locked are over or about to be replayed by the socket;
  // missed calls stay.
  void closeNotifications(CALLS_TAG, ["call", "video_call"]);
  return () => {
    if (mine !== generation) return;
    window.removeEventListener("pagehide", onPageHide);
    document.removeEventListener("visibilitychange", onVisible);
    window.removeEventListener("pointerdown", onFirstGesture);
    window.removeEventListener("keydown", onFirstGesture);
    controller.release();
  };
}

/* --- actions -------------------------------------------------------------------------------- */

/** The socket's `call.*` events, and `auth.ok` (a reconnect may have missed some). */
export function handleCallEvent(event: RealtimeEvent): void {
  controller.handle(event);
}

export function startCall(peer: CallPeer, modality: CallModality): void {
  primeCallTones();
  controller.start(peer, modality);
}

export function acceptCall(): void {
  primeCallTones();
  controller.accept();
}

export function declineCall(): void {
  controller.decline();
}

export function hangUpCall(): void {
  controller.hangup();
}

/** Locking by hand or signing out: the call ends first (the auto-lock waits for calls). */
export function endCallForLock(): void {
  controller.endNow();
}

export function toggleCallMute(): void {
  controller.toggleMute();
}

export function toggleCallCamera(): void {
  controller.toggleCamera();
}

export function switchCallCamera(): void {
  void controller.switchCamera();
}

export function setCallMinimized(minimized: boolean): void {
  controller.setMinimized(minimized);
}

export function resumeCallAudio(): void {
  controller.resumeAudio();
}

export function dismissCall(): void {
  controller.dismiss();
}

/** The safety number on the call screen was compared. */
export function confirmCallSafety(): void {
  controller.confirmSafety();
}

/** Trusts the new identity key that stopped this call. */
export function acceptChangedCallKey(): void {
  controller.acceptChangedKey();
}
