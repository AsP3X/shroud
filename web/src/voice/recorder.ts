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

function emit(): void {
  snapshot = { ...snapshot, liveLevels: snapshot.liveLevels.slice() };
  for (const fn of listeners) fn();
}

function levelFromPcm(channel: Float32Array): number {
  let sum = 0;
  for (let i = 0; i < channel.length; i++) sum += channel[i] * channel[i];
  const rms = Math.sqrt(sum / Math.max(1, channel.length));
  return Math.min(1, Math.pow(Math.min(1, rms * 4), 0.55));
}

function noteLevel(level: number): void {
  envelope.push(level);
  const live = snapshot.liveLevels.concat(level);
  snapshot = {
    recording: true,
    elapsed: startedAt ? (performance.now() - startedAt) / 1000 : snapshot.elapsed,
    liveLevels: live.length > LIVE_WINDOW ? live.slice(live.length - LIVE_WINDOW) : live,
  };
  emit();
}

function pushPcm(channel: Float32Array): void {
  if (!snapshot.recording) return;
  pcmChunks.push(new Float32Array(channel));
  noteLevel(levelFromPcm(channel));
}

function tickElapsed(): void {
  if (!startedAt) return;
  snapshot = { ...snapshot, elapsed: (performance.now() - startedAt) / 1000 };
  if (pcmChunks.length === 0 && analyser) {
    const buf = new Uint8Array(new ArrayBuffer(analyser.fftSize));
    analyser.getByteTimeDomainData(buf);
    let sum = 0;
    for (let i = 0; i < buf.length; i++) {
      const n = (buf[i] - 128) / 128;
      sum += n * n;
    }
    const rms = Math.sqrt(sum / Math.max(1, buf.length));
    noteLevel(Math.min(1, Math.pow(Math.min(1, rms * 4), 0.55)));
    return;
  }
  emit();
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
    startedAt = performance.now();
    snapshot = { recording: true, elapsed: 0, liveLevels: [] };
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

    meterTimer = window.setInterval(tickElapsed, METER_MS);
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
  pcmChunks = [];
  envelope = [];
  recorderChunks = [];
  startedAt = 0;
  snapshot = { recording: false, elapsed: 0, liveLevels: [] };
  emit();
}

export async function finishVoiceRecord(): Promise<VoiceTake | null> {
  const duration = snapshot.elapsed;
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
  snapshot = { recording: false, elapsed: 0, liveLevels: [] };
  emit();

  if (duration < VOICE_MIN_DURATION) return null;

  const fromPcm = wavFromPcm(pcm, rate);
  if (fromPcm) {
    return {
      data: fromPcm,
      mime: "audio/wav",
      durationMs: Math.max(1, Math.round(duration * 1000)),
      waveform: captured,
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
      };
    }
  }

  return null;
}
