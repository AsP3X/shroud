/**
 * How a picked clip leaves the browser, worked out from its probe alone so the
 * send sheet can show the size before anything is encoded. The result matches
 * iOS `VideoMedia.encode`: H.264 + AAC in an MP4, at most 1280 × 720, under the
 * server's cap. A clip that is already modest H.264 is only re-wrapped instead —
 * which still leaves its metadata (location included) behind.
 *
 * No mediabunny and no DOM in here: the video worker and the send sheet share it.
 */

/** The server caps sealed blobs at 25 MiB and AES-GCM adds 28 bytes (iOS `VideoMedia.maxPlaintextBytes`). */
export const MAX_VIDEO_BYTES = 24 * 1024 * 1024;
/** Shortest clip the trim handles allow (iOS `VideoTrimStrip.minimumDuration`). */
export const MIN_CLIP_SECONDS = 1;

export type VideoProbe = {
  /** Size of the picked file. */
  bytes: number;
  /** Seconds. */
  duration: number;
  /** Display size: after rotation and pixel aspect. */
  width: number;
  height: number;
  /** Average frames per second; 0 when unknown. */
  fps: number;
  videoCodec: string | null;
  /** Null when the clip has no sound. */
  audioCodec: string | null;
  audioChannels: number;
  audioSampleRate: number;
  /** Bits per second of the sound track, when the file says. */
  audioBitrate: number | null;
  /** False when this browser can't decode the sound: the clip goes out muted. */
  audioDecodable: boolean;
};

export type VideoTrim = { start: number; end: number };

export type VideoPlan = {
  /** Re-wrap the H.264 track untouched, or encode it again. */
  video: "copy" | "encode";
  audio: "copy" | "encode" | "none";
  width: number;
  height: number;
  /** Seconds that will be sent. */
  duration: number;
  /** Null when the whole clip goes. */
  trim: VideoTrim | null;
  videoBitrate: number;
  audioBitrate: number;
  /** Frame-rate cap for a re-encode; null keeps the source's. */
  frameRate: number | null;
  estimatedBytes: number;
};

/** Even at the smallest size this clip can't fit; `maxSeconds` is how long it may be. */
export class VideoTooLongError extends Error {
  constructor(readonly maxSeconds: number) {
    super(`This video is too long to send. Trim it to ${clockLabel(maxSeconds)} or less.`);
    this.name = "VideoTooLongError";
  }
}

/** Output boxes, largest first — iOS steps down 1280×720 → 960×540 → 640×480 the same way. */
const BOXES = [
  { long: 1280, short: 720 },
  { long: 960, short: 540 },
  { long: 640, short: 360 },
  { long: 480, short: 270 },
];
/** Bits per pixel per frame that H.264 needs to look clean at these sizes. */
const TARGET_BPP = 0.085;
/** Below this a size turns to mush, so the next smaller one is used instead. */
const FLOOR_BPP = 0.028;
const MIN_VIDEO_BITRATE = 120_000;
const MAX_VIDEO_BITRATE = 3_200_000;
/** Share of the cap we aim for: MP4 boxes and encoders overshooting their target. */
const HEADROOM = 0.9;
/** Already-modest H.264 goes as it is: anything bigger, or denser, is re-encoded. */
const COPY_MAX_LONG = 1920;
const COPY_MAX_SHORT = 1080;
const COPY_MAX_BITRATE = 6_000_000;

/** `0:07`, `12:30` — what the trim strip and the size hint show. */
export function clockLabel(seconds: number): string {
  const total = Math.max(0, Math.floor(Number.isFinite(seconds) ? seconds : 0));
  return `${Math.floor(total / 60)}:${(total % 60).toString().padStart(2, "0")}`;
}

/** The trim to apply, or null when the handles still cover (effectively) the whole clip. */
export function effectiveTrim(trim: VideoTrim | null | undefined, duration: number): VideoTrim | null {
  if (!trim || !(duration > 0)) return null;
  const start = Math.max(0, Math.min(trim.start, duration));
  const end = Math.max(start, Math.min(trim.end, duration));
  if (start <= 0.05 && end >= duration - 0.05) return null;
  return { start, end: Math.max(end, Math.min(duration, start + MIN_CLIP_SECONDS)) };
}

function even(n: number): number {
  return Math.max(2, Math.round(n / 2) * 2);
}

function fitBox(width: number, height: number, box: { long: number; short: number }) {
  const landscape = width >= height;
  const long = landscape ? width : height;
  const short = landscape ? height : width;
  const scale = Math.min(1, box.long / long, box.short / short);
  return { width: even(width * scale), height: even(height * scale) };
}

/**
 * Decides sizes and bitrates. `squeeze` < 1 asks for a smaller file than last
 * time (the encoder overshot the cap) and rules out re-wrapping.
 */
export function planVideo(
  probe: VideoProbe,
  options: { trim?: VideoTrim | null; mute?: boolean; squeeze?: number } = {},
): VideoPlan {
  const squeeze = options.squeeze ?? 1;
  const trim = effectiveTrim(options.trim, probe.duration);
  const duration = Math.max(0.1, trim ? trim.end - trim.start : probe.duration);
  const wantsSound = Boolean(probe.audioCodec) && !options.mute;
  // AAC is re-wrapped as it is, so only other sound has to be decodable here.
  const soundCopies = wantsSound && probe.audioCodec === "aac";
  const hasSound = soundCopies || (wantsSound && probe.audioDecodable);
  const fps = probe.fps > 0 ? probe.fps : 30;
  const long = Math.max(probe.width, probe.height);
  const short = Math.min(probe.width, probe.height);

  const copyAudioBitrate = probe.audioBitrate ?? 128_000;
  const sourceBitrate = (probe.bytes * 8) / Math.max(0.1, probe.duration);
  const canCopy =
    squeeze === 1 &&
    !trim &&
    probe.videoCodec === "avc" &&
    (!hasSound || soundCopies) &&
    long <= COPY_MAX_LONG &&
    short <= COPY_MAX_SHORT &&
    fps <= 61 &&
    sourceBitrate <= COPY_MAX_BITRATE &&
    probe.bytes <= MAX_VIDEO_BYTES * 0.97;
  if (canCopy) {
    const dropped = probe.audioCodec && !hasSound ? (copyAudioBitrate * probe.duration) / 8 : 0;
    return {
      video: "copy",
      audio: hasSound ? "copy" : "none",
      width: even(probe.width),
      height: even(probe.height),
      duration,
      trim: null,
      videoBitrate: Math.max(0, sourceBitrate - (hasSound ? copyAudioBitrate : 0)),
      audioBitrate: hasSound ? copyAudioBitrate : 0,
      frameRate: null,
      estimatedBytes: Math.round(Math.max(probe.bytes * 0.5, probe.bytes - dropped)),
    };
  }

  const budgetBits = (MAX_VIDEO_BYTES * 8 * HEADROOM * squeeze) / duration;
  const stereo = probe.audioChannels !== 1;
  let fallback = { width: 0, height: 0, floor: 0, audio: 0 };
  for (let index = 0; index < BOXES.length; index++) {
    const { width, height } = fitBox(probe.width, probe.height, BOXES[index]);
    const rate = Math.min(fps, index >= 2 ? 30 : 60);
    const pixels = width * height * rate;
    const audioBitrate = !hasSound
      ? 0
      : soundCopies
        ? copyAudioBitrate
        : index >= 2
          ? stereo ? 96_000 : 48_000
          : stereo ? 128_000 : 64_000;
    const target = Math.min(MAX_VIDEO_BITRATE, Math.max(MIN_VIDEO_BITRATE, pixels * TARGET_BPP)) * squeeze;
    const floor = Math.max(MIN_VIDEO_BITRATE, pixels * FLOOR_BPP);
    const budget = budgetBits - audioBitrate;
    fallback = { width, height, floor, audio: audioBitrate };
    if (budget < floor) continue;
    const videoBitrate = Math.round(Math.min(target, budget));
    return {
      video: "encode",
      audio: !hasSound ? "none" : soundCopies ? "copy" : "encode",
      width,
      height,
      duration,
      trim,
      videoBitrate,
      audioBitrate,
      frameRate: fps > rate + 1 ? rate : null,
      estimatedBytes: Math.round(((videoBitrate + audioBitrate) * duration) / 8 * 1.02),
    };
  }
  const maxSeconds = (MAX_VIDEO_BYTES * 8 * HEADROOM) / (fallback.floor + fallback.audio);
  throw new VideoTooLongError(Math.max(MIN_CLIP_SECONDS, Math.floor(maxSeconds)));
}
