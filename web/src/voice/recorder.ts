import { downsampleEnvelope } from "../crypto/mediaPayload";
import { transcribeVoiceNote } from "./transcriber";
import { encodeWav, resample } from "./wav";

export const VOICE_MIN_DURATION = 0.6;
export const VOICE_WAVEFORM_BUCKETS = 44;
export const VOICE_LOCK_PX = 64;
/** One live level per tick (20 Hz, like iOS), so the recording waveform scrolls at a steady pace. */
export const VOICE_METER_MS = 50;

export type VoiceTake = {
  data: Uint8Array;
  mime: string;
  durationMs: number;
  waveform: number[];
  /** On-device transcript, sealed into the payload; null when the browser can't transcribe locally. */
  transcript: string | null;
};

type Listener = () => void;

export type RecState = {
  recording: boolean;
  elapsed: number;
  liveLevels: number[];
  /** Levels metered so far this take; keys the live bars so they keep their identity while scrolling. */
  levelCount: number;
};

const TARGET_RATE = 22050;
/** Enough history to fill the widest recording strip. */
const LIVE_WINDOW = 512;

const listeners = new Set<Listener>();

const IDLE: RecState = { recording: false, elapsed: 0, liveLevels: [], levelCount: 0 };

let snapshot: RecState = IDLE;
let stream: MediaStream | null = null;
let ctx: AudioContext | null = null;
let processor: ScriptProcessorNode | null = null;
let worklet: AudioWorkletNode | null = null;
let analyser: AnalyserNode | null = null;
let mediaRecorder: MediaRecorder | null = null;
let recorderChunks: Blob[] = [];
let recorderMime = "";
let pcmChunks: Float32Array[] = [];
let pcmRate = TARGET_RATE;
let envelope: number[] = [];
let startedAt = 0;
let meterTimer: number | null = null;
let sessionId = 0;
/* Sum of squares and sample count since the last tick. The worklet delivers
   128-frame quanta (~375/s); metering each one made the live bars flicker and
   re-rendered every subscriber hundreds of times a second. */
let meterSum = 0;
let meterCount = 0;
let meterPeak = 0;

function emit(): void {
  snapshot = { ...snapshot };
  for (const fn of listeners) fn();
}

/**
 * Same mapping as iOS `VoiceRecorder.normalize`: a -50 dB floor, average and peak
 * blended 70/30, then a gentle curve. Web notes then draw the same bars as notes
 * from iPhone instead of a flatter, quieter envelope.
 */
function levelFromPower(rms: number, peak: number): number {
  const scaled = (amplitude: number) =>
    amplitude > 0 ? Math.max(0, Math.min(1, (20 * Math.log10(amplitude) + 50) / 50)) : 0;
  return Math.min(1, Math.pow(scaled(rms) * 0.7 + scaled(peak) * 0.3, 0.6));
}

function pushPcm(channel: Float32Array): void {
  if (!snapshot.recording) return;
  pcmChunks.push(new Float32Array(channel));
  let sum = 0;
  let peak = meterPeak;
  for (let i = 0; i < channel.length; i++) {
    const sample = channel[i];
    sum += sample * sample;
    if (Math.abs(sample) > peak) peak = Math.abs(sample);
  }
  meterSum += sum;
  meterCount += channel.length;
  meterPeak = peak;
}

function analyserLevel(): number {
  if (!analyser) return 0;
  const buf = new Uint8Array(new ArrayBuffer(analyser.fftSize));
  analyser.getByteTimeDomainData(buf);
  let sum = 0;
  let peak = 0;
  for (let i = 0; i < buf.length; i++) {
    const n = (buf[i] - 128) / 128;
    sum += n * n;
    if (Math.abs(n) > peak) peak = Math.abs(n);
  }
  return levelFromPower(Math.sqrt(sum / Math.max(1, buf.length)), peak);
}

function tick(): void {
  if (!startedAt) return;
  if (ctx?.state === "suspended") void ctx.resume();
  const level =
    meterCount > 0 ? levelFromPower(Math.sqrt(meterSum / meterCount), meterPeak) : analyserLevel();
  meterSum = 0;
  meterCount = 0;
  meterPeak = 0;
  envelope.push(level);
  const live =
    snapshot.liveLevels.length >= LIVE_WINDOW
      ? snapshot.liveLevels.slice(snapshot.liveLevels.length - LIVE_WINDOW + 1)
      : snapshot.liveLevels.slice();
  live.push(level);
  snapshot = {
    recording: true,
    elapsed: (performance.now() - startedAt) / 1000,
    liveLevels: live,
    levelCount: snapshot.levelCount + 1,
  };
  for (const fn of listeners) fn();
}

function pickRecorderMime(): string | null {
  if (typeof MediaRecorder === "undefined") return null;
  for (const type of ["audio/mp4", "audio/aac", "audio/webm;codecs=opus", "audio/webm"]) {
    if (MediaRecorder.isTypeSupported(type)) return type;
  }
  return null;
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
  if (worklet) {
    try {
      worklet.port.onmessage = null;
      worklet.disconnect();
    } catch {
      /* already disconnected */
    }
    worklet = null;
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
}

async function attachCapture(context: AudioContext, source: MediaStreamAudioSourceNode): Promise<void> {
  /* MediaStreamDestination keeps the graph alive without a muted GainNode
     (Chrome skips processing when gain is 0, which killed both the meter and PCM). */
  const sink = context.createMediaStreamDestination();
  analyser = context.createAnalyser();
  analyser.fftSize = 2048;
  source.connect(analyser);
  analyser.connect(sink);

  try {
    await context.audioWorklet.addModule("/voice-capture-worklet.js");
    const node = new AudioWorkletNode(context, "shroud-capture", {
      numberOfInputs: 1,
      numberOfOutputs: 1,
      channelCount: 1,
    });
    worklet = node;
    node.port.onmessage = (event) => {
      const data = event.data;
      if (data instanceof Float32Array) pushPcm(data);
      else if (data instanceof ArrayBuffer) pushPcm(new Float32Array(data));
    };
    source.connect(node);
    node.connect(sink);
  } catch {
    const proc = context.createScriptProcessor(2048, 1, 1);
    processor = proc;
    source.connect(proc);
    proc.connect(sink);
    proc.onaudioprocess = (event) => {
      pushPcm(event.inputBuffer.getChannelData(0));
    };
  }
}

async function blobToWav(blob: Blob, _fallbackRate: number): Promise<Uint8Array | null> {
  try {
    const raw = await blob.arrayBuffer();
    const tmp = new AudioContext();
    try {
      const decoded = await tmp.decodeAudioData(raw.slice(0));
      const channel = decoded.getChannelData(0);
      const resampled = resample(channel, decoded.sampleRate, TARGET_RATE);
      return encodeWav(resampled, TARGET_RATE);
    } finally {
      void tmp.close();
    }
  } catch {
    if (blob.size < 64) return null;
    /* Last resort: ship the native container (may not play on iOS). */
    return new Uint8Array(await blob.arrayBuffer());
  }
}

function wavFromPcm(pcm: Float32Array[], rate: number): Uint8Array | null {
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
  return data.length > 44 ? data : null;
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
  const media = await navigator.mediaDevices.getUserMedia({ audio: true });
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
    pcmChunks = [];
    pcmRate = ctx.sampleRate;
    envelope = [];
    recorderChunks = [];
    meterSum = 0;
    meterCount = 0;
    meterPeak = 0;
    startedAt = performance.now();
    snapshot = { recording: true, elapsed: 0, liveLevels: [], levelCount: 0 };
    const source = ctx.createMediaStreamSource(stream);
    await attachCapture(ctx, source);
    if (id !== sessionId) {
      teardownGraph();
      return false;
    }
    pcmRate = ctx.sampleRate;

    const mime = pickRecorderMime();
    recorderMime = mime ?? "";
    if (typeof MediaRecorder !== "undefined") {
      mediaRecorder = mime ? new MediaRecorder(media, { mimeType: mime }) : new MediaRecorder(media);
      mediaRecorder.ondataavailable = (event) => {
        if (event.data.size > 0) recorderChunks.push(event.data);
      };
      try {
        mediaRecorder.start(100);
      } catch {
        mediaRecorder.start();
      }
    }

    meterTimer = window.setInterval(tick, VOICE_METER_MS);
    emit();
    return true;
  } catch (err) {
    media.getTracks().forEach((t) => t.stop());
    if (id === sessionId) {
      teardownGraph();
      snapshot = IDLE;
      emit();
    }
    throw err;
  }
}

export function cancelVoiceRecord(): void {
  sessionId += 1;
  teardownGraph();
  pcmChunks = [];
  envelope = [];
  recorderChunks = [];
  startedAt = 0;
  snapshot = IDLE;
  emit();
}

export async function finishVoiceRecord(): Promise<VoiceTake | null> {
  /* Timers are throttled in background tabs, so the last tick can be stale. */
  const duration = startedAt ? (performance.now() - startedAt) / 1000 : snapshot.elapsed;
  const captured = downsampleEnvelope(envelope, VOICE_WAVEFORM_BUCKETS);
  const pcm = pcmChunks.slice();
  const rate = pcmRate;
  const rec = mediaRecorder;
  const mime = recorderMime || rec?.mimeType || "";

  let recordedBlob: Blob | null = null;
  if (rec && rec.state !== "inactive") {
    recordedBlob = await new Promise((resolve) => {
      const chunks = recorderChunks.slice();
      const timer = window.setTimeout(() => {
        resolve(chunks.length ? new Blob(chunks, { type: mime }) : null);
      }, 2500);
      rec.ondataavailable = (event) => {
        if (event.data.size > 0) chunks.push(event.data);
      };
      rec.onstop = () => {
        window.clearTimeout(timer);
        resolve(chunks.length ? new Blob(chunks, { type: mime || rec.mimeType }) : null);
      };
      try {
        rec.stop();
      } catch {
        window.clearTimeout(timer);
        resolve(chunks.length ? new Blob(chunks, { type: mime }) : null);
      }
    });
  }

  teardownGraph(false);
  pcmChunks = [];
  envelope = [];
  recorderChunks = [];
  startedAt = 0;
  snapshot = IDLE;
  emit();

  if (duration < VOICE_MIN_DURATION) return null;

  const transcript = transcribeVoiceNote(pcm, rate);
  const fromPcm = wavFromPcm(pcm, rate);
  if (fromPcm) {
    return {
      data: fromPcm,
      mime: "audio/wav",
      durationMs: Math.max(1, Math.round(duration * 1000)),
      waveform: captured,
      transcript: await transcript,
    };
  }

  if (recordedBlob && recordedBlob.size > 64) {
    const wav = await blobToWav(recordedBlob, rate);
    if (wav && wav.length > 44) {
      const looksWav = wav[0] === 0x52 && wav[1] === 0x49;
      return {
        data: wav,
        mime: looksWav ? "audio/wav" : mime || recordedBlob.type || "audio/webm",
        durationMs: Math.max(1, Math.round(duration * 1000)),
        waveform: captured,
        transcript: await transcript,
      };
    }
  }

  throw new Error("empty recording");
}
