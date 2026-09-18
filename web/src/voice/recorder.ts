import { downsampleEnvelope } from "../crypto/mediaPayload";
import { encodeWav, resample } from "./wav";

export const VOICE_MIN_DURATION = 0.6;
export const VOICE_WAVEFORM_BUCKETS = 44;
export const VOICE_LOCK_PX = 64;

export type VoiceTake = {
  data: Uint8Array;
  mime: string;
  durationMs: number;
  waveform: number[];
};

type Listener = () => void;

type RecState = {
  recording: boolean;
  elapsed: number;
  liveLevels: number[];
};

const TARGET_RATE = 22050;
const LIVE_WINDOW = 44;
const METER_MS = 50;

const listeners = new Set<Listener>();

let snapshot: RecState = { recording: false, elapsed: 0, liveLevels: [] };
let stream: MediaStream | null = null;
let ctx: AudioContext | null = null;
let processor: ScriptProcessorNode | null = null;
let analyser: AnalyserNode | null = null;
let mediaRecorder: MediaRecorder | null = null;
let recorderChunks: Blob[] = [];
let recorderMime = "";
let pcmChunks: Float32Array[] = [];
let pcmRate = TARGET_RATE;
let envelope: number[] = [];
let startedAt = 0;
let meterTimer: number | null = null;
let meterBuf: Uint8Array<ArrayBuffer> | null = null;
let sessionId = 0;

function emit(): void {
  snapshot = { ...snapshot, liveLevels: snapshot.liveLevels.slice() };
  for (const fn of listeners) fn();
}

function pickRecorderMime(): string | null {
  if (typeof MediaRecorder === "undefined") return null;
  for (const type of ["audio/mp4", "audio/aac"]) {
    if (MediaRecorder.isTypeSupported(type)) return type;
  }
  return null;
}

function rmsLevel(data: Uint8Array): number {
  let sum = 0;
  for (let i = 0; i < data.length; i++) {
    const n = (data[i] - 128) / 128;
    sum += n * n;
  }
  const rms = Math.sqrt(sum / Math.max(1, data.length));
  return Math.min(1, Math.pow(Math.min(1, rms * 3.2), 0.6));
}

function tickMeter(): void {
  if (!analyser || !meterBuf || !startedAt) return;
  analyser.getByteTimeDomainData(meterBuf);
  const level = rmsLevel(meterBuf);
  envelope.push(level);
  const live = snapshot.liveLevels.concat(level);
  snapshot = {
    recording: true,
    elapsed: (performance.now() - startedAt) / 1000,
    liveLevels: live.length > LIVE_WINDOW ? live.slice(live.length - LIVE_WINDOW) : live,
  };
  emit();
}

function teardownGraph(stopRecorder = true): void {
  if (meterTimer != null) {
    window.clearInterval(meterTimer);
    meterTimer = null;
  }
  if (processor) {
    processor.onaudioprocess = null;
    try {
      processor.disconnect();
    } catch {
      /* already disconnected */
    }
    processor = null;
  }
  if (analyser) {
    try {
      analyser.disconnect();
    } catch {
      /* already disconnected */
    }
    analyser = null;
  }
  if (stopRecorder && mediaRecorder && mediaRecorder.state !== "inactive") {
    try {
      mediaRecorder.stop();
    } catch {
      /* ignore */
    }
  }
  mediaRecorder = null;
  if (ctx) {
    void ctx.close();
    ctx = null;
  }
  stream?.getTracks().forEach((t) => t.stop());
  stream = null;
  meterBuf = null;
}

export function getVoiceRecorder(): RecState {
  return snapshot;
}

export function subscribeVoiceRecorder(listener: Listener): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

export async function startVoiceRecord(): Promise<boolean> {
  if (snapshot.recording) return true;
  const id = ++sessionId;
  const media = await navigator.mediaDevices.getUserMedia({
    audio: { channelCount: 1, echoCancellation: true, noiseSuppression: true },
  });
  if (id !== sessionId) {
    media.getTracks().forEach((t) => t.stop());
    return false;
  }
  stream = media;
  try {
    const AC =
      window.AudioContext ||
      (window as unknown as { webkitAudioContext: typeof AudioContext }).webkitAudioContext;
    ctx = new AC();
    if (ctx.state === "suspended") await ctx.resume();
    if (id !== sessionId) {
      teardownGraph();
      return false;
    }
    const source = ctx.createMediaStreamSource(stream);
    analyser = ctx.createAnalyser();
    analyser.fftSize = 2048;
    analyser.smoothingTimeConstant = 0.4;
    source.connect(analyser);
    meterBuf = new Uint8Array(new ArrayBuffer(analyser.fftSize));

    const mime = pickRecorderMime();
    recorderChunks = [];
    recorderMime = mime ?? "audio/wav";
    pcmChunks = [];
    pcmRate = ctx.sampleRate;
    envelope = [];
    startedAt = performance.now();
    snapshot = { recording: true, elapsed: 0, liveLevels: [] };

    if (mime) {
      mediaRecorder = new MediaRecorder(stream, { mimeType: mime });
      mediaRecorder.ondataavailable = (event) => {
        if (event.data.size > 0) recorderChunks.push(event.data);
      };
      mediaRecorder.start();
    } else {
      const proc = ctx.createScriptProcessor(4096, 1, 1);
      processor = proc;
      const mute = ctx.createGain();
      mute.gain.value = 0;
      analyser.connect(proc);
      proc.connect(mute);
      mute.connect(ctx.destination);
      proc.onaudioprocess = (event) => {
        if (!snapshot.recording) return;
        pcmChunks.push(new Float32Array(event.inputBuffer.getChannelData(0)));
      };
    }

    meterTimer = window.setInterval(tickMeter, METER_MS);
    emit();
    return true;
  } catch (err) {
    media.getTracks().forEach((t) => t.stop());
    if (id === sessionId) {
      teardownGraph();
      snapshot = { recording: false, elapsed: 0, liveLevels: [] };
      emit();
    }
    throw err;
  }
}

export function cancelVoiceRecord(): void {
  sessionId += 1;
  teardownGraph();
  recorderChunks = [];
  pcmChunks = [];
  envelope = [];
  startedAt = 0;
  snapshot = { recording: false, elapsed: 0, liveLevels: [] };
  emit();
}

export async function finishVoiceRecord(): Promise<VoiceTake | null> {
  const duration = snapshot.elapsed;
  const captured = downsampleEnvelope(envelope, VOICE_WAVEFORM_BUCKETS);
  const mime = recorderMime;
  const pcm = pcmChunks.slice();
  const rate = pcmRate;
  const rec = mediaRecorder;

  let recorded: Uint8Array | null = null;
  if (rec && rec.state !== "inactive") {
    recorded = await new Promise((resolve) => {
      const chunks = recorderChunks.slice();
      const done = (data: Uint8Array) => resolve(data);
      const timer = window.setTimeout(() => done(new Uint8Array()), 3000);
      rec.ondataavailable = (event) => {
        if (event.data.size > 0) chunks.push(event.data);
      };
      rec.onstop = () => {
        window.clearTimeout(timer);
        void new Blob(chunks, { type: mime }).arrayBuffer().then(
          (buf) => done(new Uint8Array(buf)),
          () => done(new Uint8Array()),
        );
      };
      try {
        rec.stop();
      } catch {
        window.clearTimeout(timer);
        done(new Uint8Array());
      }
    });
  }

  teardownGraph(false);
  recorderChunks = [];
  pcmChunks = [];
  envelope = [];
  startedAt = 0;
  snapshot = { recording: false, elapsed: 0, liveLevels: [] };
  emit();

  if (duration < VOICE_MIN_DURATION) return null;

  if (recorded && recorded.length > 0) {
    return {
      data: recorded,
      mime: mime || "audio/mp4",
      durationMs: Math.max(1, Math.round(duration * 1000)),
      waveform: captured,
    };
  }

  let total = 0;
  for (const part of pcm) total += part.length;
  if (total === 0) return null;
  const merged = new Float32Array(total);
  let offset = 0;
  for (const part of pcm) {
    merged.set(part, offset);
    offset += part.length;
  }
  const resampled = resample(merged, rate, TARGET_RATE);
  const data = encodeWav(resampled, TARGET_RATE);
  if (!data.length) return null;
  return {
    data,
    mime: "audio/wav",
    durationMs: Math.max(1, Math.round(duration * 1000)),
    waveform: captured,
  };
}
