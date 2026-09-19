/*
 * Video work off the main thread: reading a picked clip (size, length, poster,
 * filmstrip) and converting it for sending. mediabunny drives WebCodecs, so the
 * pixels go through the browser's own — usually hardware — codecs; demuxing,
 * scaling and muxing happen here, where they can't stutter the chat. The worker
 * is only loaded once a video is picked or sent.
 *
 * The output is what iOS sends and plays: H.264 + AAC in an MP4 with its index
 * up front, and none of the source's metadata tags (location included).
 */
import {
  ALL_FORMATS,
  BlobSource,
  BufferSource,
  BufferTarget,
  CanvasSink,
  Conversion,
  ConversionCanceledError,
  Input,
  MP4,
  Mp4OutputFormat,
  Output,
  Quality,
  UnsupportedInputFormatError,
  canEncodeAudio,
  canEncodeVideo,
  type ConversionAudioOptions,
  type ConversionVideoOptions,
  type InputVideoTrack,
} from "mediabunny";
import { envelopePreview, fitEdge } from "./envelopePreview";
import { MAX_VIDEO_BYTES, planVideo, VideoTooLongError, type VideoProbe, type VideoTrim } from "./videoPlan";

export type VideoRequest =
  | { id: number; type: "inspect"; file: File; posterEdge: number }
  | { id: number; type: "filmstrip"; file: File; count: number; edge: number }
  | { id: number; type: "encode"; file: File; trim: VideoTrim | null; mute: boolean; posterEdge: number }
  /** Stops the request with this id: an encode throws, a filmstrip ends early. */
  | { id: number; type: "cancel" };

export type EncodedParts = {
  bytes: ArrayBuffer;
  width: number;
  height: number;
  durationMs: number;
  /** Sharp first frame for the bubble. */
  poster: Blob | null;
  /** ≤ 6 KB preview for the envelope. */
  thumb: ArrayBuffer | null;
};

export type VideoReply =
  | { id: number; type: "inspected"; probe: VideoProbe; poster: Blob | null }
  | { id: number; type: "tile"; index: number; tile: Blob }
  | { id: number; type: "progress"; value: number }
  | { id: number; type: "encoded"; video: EncodedParts }
  | { id: number; type: "done" }
  | { id: number; type: "error"; message: string; canceled?: boolean; maxSeconds?: number };

/** An error whose message is fit for the person sending. */
class Refusal extends Error {}

const cancels = new Map<number, () => void>();
const canceled = new Set<number>();

function reply(message: VideoReply, transfer: Transferable[] = []): void {
  (self as unknown as Worker).postMessage(message, transfer);
}

function open(file: Blob): Input {
  return new Input({ source: new BlobSource(file), formats: ALL_FORMATS });
}

function codecName(codec: string | null): string {
  switch (codec) {
    case "hevc":
      return "HEVC";
    case "av1":
      return "AV1";
    case "vp9":
      return "VP9";
    case "vp8":
      return "VP8";
    case "avc":
      return "H.264";
    default:
      return codec ? codec.toUpperCase() : "this format";
  }
}

async function readProbe(input: Input, file: Blob): Promise<{ probe: VideoProbe; track: InputVideoTrack }> {
  let track: InputVideoTrack | null;
  try {
    track = await input.getPrimaryVideoTrack();
  } catch (err) {
    if (err instanceof UnsupportedInputFormatError) {
      throw new Refusal("This video format isn’t supported. Try an MP4, MOV or WebM file.");
    }
    throw err;
  }
  if (!track) throw new Refusal("There’s no video in this file.");
  const audio = await input.getPrimaryAudioTrack();
  const [duration, width, height, videoCodec] = await Promise.all([
    input.computeDuration(),
    track.getDisplayWidth(),
    track.getDisplayHeight(),
    track.getCodec(),
  ]);
  if (!(duration > 0) || !(width > 0) || !(height > 0)) throw new Refusal("This video has nothing to play.");
  let fps = 0;
  try {
    fps = (await track.computePacketStats(120)).averagePacketRate;
  } catch {
    /* unknown: the plan assumes 30 */
  }
  let audioCodec: string | null = null;
  let audioChannels = 0;
  let audioSampleRate = 0;
  let audioBitrate: number | null = null;
  let audioDecodable = false;
  if (audio) {
    [audioCodec, audioChannels, audioSampleRate, audioDecodable] = await Promise.all([
      audio.getCodec(),
      audio.getNumberOfChannels(),
      audio.getSampleRate(),
      audio.canDecode(),
    ]);
    audioCodec ??= "unknown";
    try {
      audioBitrate = (await audio.computePacketStats(200)).averageBitrate || null;
    } catch {
      audioBitrate = null;
    }
  }
  return {
    track,
    probe: {
      bytes: file.size,
      duration,
      width,
      height,
      fps: Number.isFinite(fps) ? fps : 0,
      videoCodec,
      audioCodec,
      audioChannels,
      audioSampleRate,
      audioBitrate,
      audioDecodable,
    },
  };
}

/** The frame at `at` seconds (or the first one), drawn with its longest edge at `edge`. */
async function frame(track: InputVideoTrack, at: number, edge: number): Promise<OffscreenCanvas | null> {
  const size = fitEdge(await track.getDisplayWidth(), await track.getDisplayHeight(), edge);
  const sink = new CanvasSink(track, { width: size.width, height: size.height, fit: "fill" });
  const first = await track.getFirstTimestamp();
  const wrapped = await sink.getCanvas(Math.max(first, at));
  return (wrapped?.canvas as OffscreenCanvas | undefined) ?? null;
}

function jpeg(canvas: OffscreenCanvas, quality: number): Promise<Blob> {
  return canvas.convertToBlob({ type: "image/jpeg", quality });
}

async function thumbnail(canvas: OffscreenCanvas): Promise<ArrayBuffer | null> {
  const preview = await envelopePreview(async (edge, quality) => {
    const size = fitEdge(canvas.width, canvas.height, edge);
    const small = new OffscreenCanvas(size.width, size.height);
    const context = small.getContext("2d");
    if (!context) throw new Error("No 2D canvas in this worker.");
    context.imageSmoothingQuality = "high";
    context.drawImage(canvas, 0, 0, size.width, size.height);
    return new Uint8Array(await (await jpeg(small, quality)).arrayBuffer());
  });
  return preview ? (preview.buffer.slice(preview.byteOffset, preview.byteOffset + preview.byteLength) as ArrayBuffer) : null;
}

async function inspect(request: Extract<VideoRequest, { type: "inspect" }>): Promise<void> {
  const input = open(request.file);
  try {
    if (canceled.has(request.id)) throw new ConversionCanceledError();
    const { probe, track } = await readProbe(input, request.file);
    if (canceled.has(request.id)) throw new ConversionCanceledError();
    let poster: Blob | null = null;
    try {
      const canvas = await frame(track, 0, request.posterEdge);
      if (canvas) poster = await jpeg(canvas, 0.82);
    } catch {
      /* this browser can't decode it; the sheet says so when it matters */
    }
    reply({ id: request.id, type: "inspected", probe, poster });
  } finally {
    input.dispose();
  }
}

async function filmstrip(request: Extract<VideoRequest, { type: "filmstrip" }>): Promise<void> {
  const input = open(request.file);
  try {
    if (canceled.has(request.id)) throw new ConversionCanceledError();
    const track = await input.getPrimaryVideoTrack();
    if (!track) throw new Refusal("There’s no video in this file.");
    const [duration, first, width, height] = await Promise.all([
      input.computeDuration(),
      track.getFirstTimestamp(),
      track.getDisplayWidth(),
      track.getDisplayHeight(),
    ]);
    const size = fitEdge(width, height, request.edge);
    const sink = new CanvasSink(track, { width: size.width, height: size.height, fit: "fill" });
    const span = Math.max(0, duration - first);
    const times = Array.from({ length: request.count }, (_, index) => first + (span * (index + 0.5)) / request.count);
    let index = 0;
    for await (const wrapped of sink.canvasesAtTimestamps(times)) {
      if (canceled.has(request.id)) break;
      if (wrapped) {
        reply({ id: request.id, type: "tile", index, tile: await jpeg(wrapped.canvas as OffscreenCanvas, 0.7) });
      }
      index += 1;
    }
    reply({ id: request.id, type: "done" });
  } finally {
    input.dispose();
  }
}

const AAC_RATES = [96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000];
let aacFallback: Promise<void> | null = null;

/**
 * Firefox (and older Safari) can't encode AAC through WebCodecs. The fallback is
 * FFmpeg's AAC encoder as WASM (~1 MB, LGPL), fetched only when a clip's sound
 * actually has to be re-encoded here.
 */
async function ensureAacEncoder(channels: number, sampleRate: number, bitrate: number): Promise<void> {
  if (await canEncodeAudio("aac", { numberOfChannels: channels, sampleRate, bitrate })) return;
  aacFallback ??= import("@mediabunny/aac-encoder").then(({ registerAacEncoder }) => registerAacEncoder());
  await aacFallback;
}

async function encode(request: Extract<VideoRequest, { type: "encode" }>): Promise<void> {
  const input = open(request.file);
  let lastProgress = 0;
  let lastSent = 0;
  const progress = (value: number) => {
    const now = performance.now();
    if (value < 1 && now - lastSent < 90 && value - lastProgress < 0.02) return;
    lastSent = now;
    lastProgress = value;
    reply({ id: request.id, type: "progress", value });
  };
  try {
    const { probe, track } = await readProbe(input, request.file);
    let squeeze = 1;
    for (let attempt = 0; attempt < 3; attempt++) {
      const plan = planVideo(probe, { trim: request.trim, mute: request.mute, squeeze });
      if (plan.video === "encode") {
        if (!(await track.canDecode())) {
          throw new Refusal(
            `This browser can’t read ${codecName(probe.videoCodec)} video. Try Safari or Chrome, or send it from your phone.`,
          );
        }
        const encodable = await canEncodeVideo("avc", {
          width: plan.width,
          height: plan.height,
          bitrate: plan.videoBitrate,
        });
        if (!encodable) throw new Refusal("This browser can’t convert videos. Try Chrome, Edge or Safari.");
      }
      const channels = Math.min(2, probe.audioChannels || 2);
      const sampleRate = AAC_RATES.includes(probe.audioSampleRate) ? probe.audioSampleRate : 48000;
      if (plan.audio === "encode") await ensureAacEncoder(channels, sampleRate, plan.audioBitrate);

      const video: ConversionVideoOptions =
        plan.video === "copy"
          ? { codec: "avc" }
          : {
              codec: "avc",
              width: plan.width,
              height: plan.height,
              fit: "fill",
              quality: new Quality({ bitrate: plan.videoBitrate }),
              // Seeking lands on a key frame; every 2 s keeps the scrubber responsive.
              keyFrameInterval: 2,
              // Bake rotation into the pixels: players that ignore the matrix still get it right.
              allowTransformationMetadata: false,
              forceTranscode: true,
              ...(plan.frameRate ? { frameRate: plan.frameRate } : {}),
            };
      const audio: ConversionAudioOptions =
        plan.audio === "none"
          ? { discard: true }
          : plan.audio === "copy"
            ? { codec: "aac" }
            : {
                codec: "aac",
                quality: new Quality({ bitrate: Math.round(plan.audioBitrate) }),
                numberOfChannels: channels,
                sampleRate,
                forceTranscode: true,
              };
      const target = new BufferTarget();
      const output = new Output({ format: new Mp4OutputFormat({ fastStart: "in-memory" }), target });
      const conversion = await Conversion.init({
        input,
        output,
        tracks: "primary",
        video,
        audio,
        trim: plan.trim ?? undefined,
        // Nothing of the source's own tags goes along: no location, no device, no dates.
        tags: {},
        showWarnings: false,
      });
      const keptVideo = conversion.utilizedTracks.some((used) => used.isVideoTrack());
      if (!conversion.isValid || !keptVideo) {
        const reason = conversion.discardedTracks.find((dropped) => dropped.track.isVideoTrack())?.reason;
        if (reason === "undecodable_source_codec") {
          throw new Refusal(`This browser can’t read ${codecName(probe.videoCodec)} video. Try Safari or Chrome.`);
        }
        if (reason === "no_encodable_target_codec") {
          throw new Refusal("This browser can’t convert videos. Try Chrome, Edge or Safari.");
        }
        throw new Refusal("This video can’t be converted in this browser.");
      }
      cancels.set(request.id, () => void conversion.cancel());
      if (canceled.has(request.id)) await conversion.cancel();
      // A second pass starts over, and the ring honestly does too.
      lastProgress = 0;
      conversion.onProgress = (value) => progress(Math.min(0.999, value));
      await conversion.execute();
      cancels.delete(request.id);
      const buffer = target.buffer;
      if (!buffer) throw new Error("The encoder produced nothing.");
      if (buffer.byteLength <= MAX_VIDEO_BYTES) {
        const parts = await describe(buffer, request.posterEdge);
        progress(1);
        reply({ id: request.id, type: "encoded", video: parts }, [parts.bytes]);
        return;
      }
      // The encoder overshot its target: aim lower, in proportion.
      squeeze *= Math.min(0.85, (MAX_VIDEO_BYTES * 0.9) / buffer.byteLength);
    }
    throw new Refusal("This video is too large to send. Trim it and try again.");
  } finally {
    cancels.delete(request.id);
    input.dispose();
  }
}

/** Reads back what was made: the size and length iOS will see, and the poster the bubble shows. */
async function describe(bytes: ArrayBuffer, posterEdge: number): Promise<EncodedParts> {
  const input = new Input({ source: new BufferSource(bytes), formats: [MP4] });
  try {
    const track = await input.getPrimaryVideoTrack();
    if (!track) throw new Error("The converted video has no picture.");
    const [duration, width, height] = await Promise.all([
      input.computeDuration(),
      track.getDisplayWidth(),
      track.getDisplayHeight(),
    ]);
    let poster: Blob | null = null;
    let thumb: ArrayBuffer | null = null;
    try {
      const canvas = await frame(track, 0, posterEdge);
      if (canvas) {
        poster = await jpeg(canvas, 0.82);
        thumb = await thumbnail(canvas);
      }
    } catch {
      /* no poster: the bubble falls back to a plain plate */
    }
    return { bytes, width, height, durationMs: Math.round(duration * 1000), poster, thumb };
  } finally {
    input.dispose();
  }
}

self.onmessage = async (event: MessageEvent<VideoRequest>) => {
  const request = event.data;
  if (request.type === "cancel") {
    canceled.add(request.id);
    cancels.get(request.id)?.();
    return;
  }
  try {
    if (request.type === "inspect") await inspect(request);
    else if (request.type === "filmstrip") await filmstrip(request);
    else await encode(request);
  } catch (err) {
    const wasCanceled = err instanceof ConversionCanceledError || canceled.has(request.id);
    let message = "Couldn’t prepare this video.";
    if (err instanceof Refusal || err instanceof VideoTooLongError) message = err.message;
    else if (err instanceof UnsupportedInputFormatError) {
      message = "This video format isn’t supported. Try an MP4, MOV or WebM file.";
    } else console.warn("Video worker:", err);
    reply({
      id: request.id,
      type: "error",
      message,
      ...(wasCanceled ? { canceled: true } : {}),
      ...(err instanceof VideoTooLongError ? { maxSeconds: err.maxSeconds } : {}),
    });
  } finally {
    canceled.delete(request.id);
  }
};
