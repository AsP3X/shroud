/**
 * How a picked clip leaves the browser, worked out from its probe alone so the
 * send sheet can show the size before anything is encoded. The result matches
 * iOS `VideoMedia`: H.264 + AAC in an MP4, under the server's cap, at the
 * quality the sender picked. A clip that already fits that rung as modest H.264
 * is only re-wrapped: the streams are copied into a fresh MP4, and none of the
 * source's metadata (location, device, dates, title) is written — checked with
 * iPhone `.mov` and ISO `loci` fixtures; the worker passes `tags: {}`.
 *
 * No mediabunny and no DOM in here: the video worker and the send sheet share it.
 */

/**
 * Largest video before encryption. The server accepts a 2 GiB sealed blob;
 * one mebibyte of room covers AES-GCM and a little encoder overshoot
 * (iOS `VideoMedia.maxPlaintextBytes`).
 */
export const MAX_VIDEO_BYTES = 2 * 1024 * 1024 * 1024 - 1024 * 1024;
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
  /** Channels when audio is re-encoded. Null keeps the source's count (at most two). */
  audioChannels: number | null;
  /** Frame-rate cap for a re-encode; null keeps the source's. */
  frameRate: number | null;
  estimatedBytes: number;
};

/**
 * What the sender picks in the compose sheet.
 * `high` is the default: at most 720p, stepping down when the clip would not fit.
 * `original` keeps the picture size (re-encoding above 4K down to 4K). iOS
 * re-encodes top out at 1080p; a file that already fits is sent unchanged on both.
 */
export type VideoQuality = "original" | "high" | "medium" | "small";

export const VIDEO_QUALITIES: readonly { id: VideoQuality; label: string; hint: string }[] = [
  { id: "original", label: "Original", hint: "Full size" },
  { id: "high", label: "High", hint: "720p" },
  { id: "medium", label: "Medium", hint: "540p" },
  { id: "small", label: "Small", hint: "360p" },
];

/** Even at the smallest size this clip can't fit; `maxSeconds` is how long it may be. */
export class VideoTooLongError extends Error {
  constructor(
    readonly maxSeconds: number,
    readonly quality: VideoQuality = "high",
  ) {
    super(
      quality === "original"
        ? `Original quality won’t fit. Trim it to ${clockLabel(maxSeconds)} or choose a lower quality.`
        : `This video is too long to send. Trim it to ${clockLabel(maxSeconds)} or less.`,
    );
    this.name = "VideoTooLongError";
  }
}

/**
 * Output boxes, largest first. Tiers 0–1 keep up to 60 fps, 2–3 drop to 30 fps.
 * Tier 4 is the same 270p frame at 15 fps with quiet audio, for a clip that would
 * still be over the 2 GiB cap at 30 fps. iOS writes that rung itself; its named
 * presets do not go that low. A 4:3 clip at Small is 480p on iOS (preset 640×480)
 * and 360p here.
 */
const LADDER: { long: number; short: number; tier: number }[] = [
  { long: 1280, short: 720, tier: 0 },
  { long: 960, short: 540, tier: 1 },
  { long: 640, short: 360, tier: 2 },
  { long: 480, short: 270, tier: 3 },
  { long: 480, short: 270, tier: 4 },
];
const QUALITY_START: Record<Exclude<VideoQuality, "original">, number> = {
  high: 0,
  medium: 1,
  small: 2,
};
/** Re-encoding "original" stops at 4K. A larger frame is scaled into this box. */
const ORIGINAL_MAX = { long: 3840, short: 2160, tier: 0 };
/** Bits per pixel per frame that H.264 needs to look clean at these sizes. */
const TARGET_BPP = 0.085;
/** Below this a size turns to mush, so the next smaller one is used instead. */
const FLOOR_BPP = 0.028;
const MIN_VIDEO_BITRATE = 120_000;
/** Tier 4 may sit under the normal floor so a very long clip can still land under the cap. */
const LONG_MIN_VIDEO_BITRATE = 40_000;
/** Mono AAC. Copying a stereo track at the source rate would spend the cap before the picture. */
const LONG_AUDIO_BITRATE = 32_000;
const MAX_VIDEO_BITRATE = 3_200_000;
/** Original keeps more of the source's own bitrate than the smaller rungs. */
const ORIGINAL_MAX_VIDEO_BITRATE = 12_000_000;
/** Share of the cap we aim for: MP4 boxes and encoders overshooting their target. */
const HEADROOM = 0.9;
/** Already-modest H.264 inside the chosen rung is re-wrapped. Denser video is encoded. */
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

/** "720p", or "Original" when that choice keeps the source frame. */
export function planResolutionLabel(plan: VideoPlan, probe: VideoProbe, quality: VideoQuality): string {
  const sameFrame =
    Math.abs(Math.max(plan.width, plan.height) - Math.max(probe.width, probe.height)) <= 4 &&
    Math.abs(Math.min(plan.width, plan.height) - Math.min(probe.width, probe.height)) <= 4;
  if (quality === "original" && sameFrame) return "Original";
  return shortEdgeLabel(Math.min(plan.width, plan.height));
}

function shortEdgeLabel(short: number): string {
  const named: readonly [number, string][] = [
    [1080, "1080p"],
    [720, "720p"],
    [540, "540p"],
    [480, "480p"],
    [360, "360p"],
    [270, "270p"],
  ];
  for (const [edge, label] of named) {
    if (Math.abs(short - edge) <= 16) return label;
  }
  return `${Math.round(short)}p`;
}

/** Boxes the chosen quality may use, best first. Original has one box and does not step down. */
function encodeBoxes(quality: VideoQuality, width: number, height: number) {
  if (quality !== "original") return LADDER.slice(QUALITY_START[quality]);
  const long = Math.max(width, height);
  const short = Math.min(width, height);
  if (long <= ORIGINAL_MAX.long && short <= ORIGINAL_MAX.short) {
    return [{ long: even(long), short: even(short), tier: 0 }];
  }
  return [ORIGINAL_MAX];
}

/**
 * Decides sizes and bitrates. `squeeze` < 1 asks for a smaller file than last
 * time (the encoder overshot the cap) and rules out re-wrapping.
 * `quality` picks the largest frame; High, Medium and Small still step down to fit.
 */
export function planVideo(
  probe: VideoProbe,
  options: { trim?: VideoTrim | null; mute?: boolean; squeeze?: number; quality?: VideoQuality } = {},
): VideoPlan {
  const squeeze = options.squeeze ?? 1;
  const quality = options.quality ?? "high";
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
  const copyLimit = quality === "original" ? null : LADDER[QUALITY_START[quality]];
  const canCopy =
    squeeze === 1 &&
    !trim &&
    probe.videoCodec === "avc" &&
    (!hasSound || soundCopies) &&
    fps <= 61 &&
    (copyLimit == null ||
      (long <= copyLimit.long && short <= copyLimit.short && sourceBitrate <= COPY_MAX_BITRATE)) &&
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
      audioChannels: null,
      frameRate: null,
      estimatedBytes: Math.round(Math.max(probe.bytes * 0.5, probe.bytes - dropped)),
    };
  }

  const budgetBits = (MAX_VIDEO_BYTES * 8 * HEADROOM * squeeze) / duration;
  const stereo = probe.audioChannels !== 1;
  const bitrateCap = quality === "original" ? ORIGINAL_MAX_VIDEO_BITRATE : MAX_VIDEO_BITRATE;
  let fallback = { width: 0, height: 0, floor: 0, audio: 0 };
  for (const box of encodeBoxes(quality, probe.width, probe.height)) {
    const longForm = box.tier >= 4;
    const { width, height } = fitBox(probe.width, probe.height, box);
    const rate = Math.min(fps, longForm ? 15 : box.tier >= 2 ? 30 : 60);
    const pixels = width * height * rate;
    const minRate = longForm ? LONG_MIN_VIDEO_BITRATE : MIN_VIDEO_BITRATE;
    const audioBitrate = !hasSound
      ? 0
      : longForm
        ? LONG_AUDIO_BITRATE
        : soundCopies
          ? copyAudioBitrate
          : box.tier >= 2
            ? stereo ? 96_000 : 48_000
            : stereo ? 128_000 : 64_000;
    const target = Math.min(bitrateCap, Math.max(minRate, pixels * TARGET_BPP)) * squeeze;
    const floor = Math.max(minRate, pixels * FLOOR_BPP);
    const budget = budgetBits - audioBitrate;
    fallback = { width, height, floor, audio: audioBitrate };
    if (budget < floor) continue;
    const videoBitrate = Math.round(Math.min(target, budget));
    return {
      video: "encode",
      // The long rung always re-encodes sound. Copying a 128 kbps track would
      // spend the whole cap before the picture gets any.
      audio: !hasSound ? "none" : longForm || !soundCopies ? "encode" : "copy",
      width,
      height,
      duration,
      trim,
      videoBitrate,
      audioBitrate,
      audioChannels: longForm && hasSound ? 1 : null,
      frameRate: fps > rate + 1 ? rate : null,
      estimatedBytes: Math.round(((videoBitrate + audioBitrate) * duration) / 8 * 1.02),
    };
  }
  const maxSeconds = (MAX_VIDEO_BYTES * 8 * HEADROOM) / (fallback.floor + fallback.audio);
  throw new VideoTooLongError(Math.max(MIN_CLIP_SECONDS, Math.floor(maxSeconds)), quality);
}
