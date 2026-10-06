/**
 * How sharp our camera goes out, step by step as the link allows (docs/calls.md, "Camera
 * quality"). The camera is opened at up to 1080p; the encoder sends one rung of the ladder below,
 * picked every two seconds from the sender's stats. An encoder short of bits on a link whose
 * bandwidth estimate is below the rung steps down after two readings in a row, as far as the
 * estimate needs at once; so does loss, one rung. Clean readings step up one rung at a time after
 * a stretch of them, a longer one each time an upgrade did not hold. WebRTC keeps adapting within
 * a rung on its own.
 *
 * Going up does not wait for the estimate to show room: while the camera sends less than the link
 * could carry, the estimate only grows as far as what is sent, so it would never show it. A higher
 * cap makes WebRTC probe the link instead, and a try the link cannot carry steps back down.
 *
 * The iPhone (`CallVideoQuality.swift`) and Android (`CallVideoQuality.kt`) run the same ladder
 * with the same numbers; each side decides only what it sends, so nothing goes on the wire.
 */

export type CameraRung = {
  name: string;
  /** The picture's longer side, in pixels (1920 for 1080p, portrait or landscape). */
  long: number;
  fps: number;
  /** The most the encoder may use, bits per second. */
  maxBitrate: number;
  /** The least the link must have room for to keep this rung. */
  minBitrate: number;
};

export const CAMERA_LADDER: readonly CameraRung[] = [
  { name: "180p", long: 320, fps: 15, maxBitrate: 150_000, minBitrate: 0 },
  { name: "270p", long: 480, fps: 20, maxBitrate: 300_000, minBitrate: 150_000 },
  { name: "360p", long: 640, fps: 30, maxBitrate: 600_000, minBitrate: 300_000 },
  { name: "540p", long: 960, fps: 30, maxBitrate: 1_200_000, minBitrate: 600_000 },
  { name: "720p", long: 1280, fps: 30, maxBitrate: 2_200_000, minBitrate: 1_100_000 },
  { name: "1080p", long: 1920, fps: 30, maxBitrate: 3_800_000, minBitrate: 2_000_000 },
];

/** Where a call starts: 720p, until the link has shown what it can carry. */
export const CAMERA_START = 4;

/** Our camera while our screen is shared: they show it as a small tile, so a thumbnail's worth. */
export const CAMERA_TILE: CameraRung = { name: "tile", long: 640, fps: 15, maxBitrate: 350_000, minBitrate: 0 };

/**
 * The camera as a tile while our screen is shared: a thumbnail's worth, and never more than its
 * rung, so a camera the link had pushed below the tile stays there while the screen competes.
 */
export function tileOf(rung: CameraRung): CameraRung {
  return {
    name: CAMERA_TILE.name,
    long: Math.min(CAMERA_TILE.long, rung.long),
    fps: Math.min(CAMERA_TILE.fps, rung.fps),
    maxBitrate: Math.min(CAMERA_TILE.maxBitrate, rung.maxBitrate),
    minBitrate: 0,
  };
}

/** How often the stats are read. */
export const QUALITY_SAMPLE_MS = 2_000;
/** Kept off the camera's share of the link: speech, and RTCP. */
const AUDIO_RESERVE_BPS = 50_000;
/** Readings in a row that step down. */
const DOWN_AFTER = 2;
/** Readings in a row that step up, at first and after an upgrade held. */
const UP_AFTER = 4;
/** The most an upgrade that keeps failing waits (about a minute). */
const UP_AFTER_MAX = 32;
/** An upgrade held this many readings: the next one waits `UP_AFTER` again. */
const UP_PROVEN = 15;
/** Readings ignored at the start, while the estimate ramps up from WebRTC's 300 kbps. */
const START_SETTLE = 3;
/** Readings ignored after a change or a pause, while the link and the encoder settle. */
const SETTLE = 2;
/** Loss that steps down, and the most that lets a step up. */
const LOSS_DOWN = 0.1;
const LOSS_UP = 0.03;

export type QualityLimitation = "none" | "cpu" | "bandwidth" | "other";

/** One reading of the camera's sender. Null where this browser has no such stat. */
export type CameraSample = {
  /** The bandwidth estimate for the whole link (`availableOutgoingBitrate`), bits per second. */
  estimate: number | null;
  /** What the encoder says holds it back (`qualityLimitationReason`). */
  limitation: QualityLimitation | null;
  /** The share of our packets the other side lost lately (`fractionLost`), 0…1. */
  loss: number | null;
};

/** The rung index for a capture whose longer side is `long`: the highest that needs no upscaling. */
export function ceilingFor(long: number | null | undefined): number {
  if (!long || long <= 0) return CAMERA_START;
  let top = 0;
  CAMERA_LADDER.forEach((rung, index) => {
    if (rung.long <= long * 1.05) top = index;
  });
  return top;
}

/** The highest rung whose minimum fits `budget`. */
function fitting(budget: number): number {
  let top = 0;
  CAMERA_LADDER.forEach((rung, index) => {
    if (rung.minBitrate <= budget) top = index;
  });
  return top;
}

/** The camera's rung for one call; `sample` moves it. */
export class CameraQuality {
  private index: number;
  private ceiling: number;
  private low = 0;
  private high = 0;
  private settle = START_SETTLE;
  private upAfter = UP_AFTER;
  /** Readings since the last step up, until it has held. */
  private sinceUp: number | null = null;

  constructor(captureLong?: number | null) {
    this.ceiling = ceilingFor(captureLong);
    this.index = Math.min(CAMERA_START, this.ceiling);
  }

  get rung(): CameraRung {
    return CAMERA_LADDER[this.index];
  }

  get rungIndex(): number {
    return this.index;
  }

  /** The camera opened at a new size (another camera, or the first): no rung above it. */
  setCapture(long: number | null | undefined): boolean {
    if (!long) return false;
    this.ceiling = ceilingFor(long);
    if (this.index <= this.ceiling) return false;
    this.index = this.ceiling;
    return true;
  }

  /**
   * The camera went off, out as a tile, or the link is reconnecting: the next readings start
   * counting afresh. A call that has not been read yet keeps its longer opening settle.
   */
  pause(): void {
    this.low = 0;
    this.high = 0;
    this.settle = Math.max(this.settle, SETTLE);
  }

  /** One reading; true when the rung changed. */
  sample(sample: CameraSample): boolean {
    if (this.sinceUp !== null && ++this.sinceUp > UP_PROVEN) {
      this.sinceUp = null;
      this.upAfter = UP_AFTER;
    }
    if (this.settle > 0) {
      this.settle -= 1;
      return false;
    }
    const budget = sample.estimate === null ? null : sample.estimate - AUDIO_RESERVE_BPS;
    const starved = sample.limitation === "bandwidth" && budget !== null && budget < this.rung.minBitrate;
    const lossy = sample.loss !== null && sample.loss >= LOSS_DOWN;

    let down: number | null = null;
    // As far down as the estimate needs, at once; loss steps down one.
    if (starved && budget !== null) down = fitting(budget);
    if (lossy) down = Math.max(0, Math.min(down ?? this.index, this.index - 1));
    if (down !== null && down < this.index) {
      this.high = 0;
      this.low += 1;
      if (this.low < DOWN_AFTER) return false;
      // An upgrade that did not hold: the next one waits twice as long.
      if (this.sinceUp !== null) this.upAfter = Math.min(this.upAfter * 2, UP_AFTER_MAX);
      this.sinceUp = null;
      this.move(down);
      return true;
    }
    this.low = 0;

    const clean =
      this.index < this.ceiling &&
      sample.limitation !== "cpu" &&
      sample.limitation !== "bandwidth" &&
      (sample.loss === null || sample.loss < LOSS_UP);
    if (!clean) {
      this.high = 0;
      return false;
    }
    this.high += 1;
    if (this.high < this.upAfter) return false;
    this.move(this.index + 1);
    this.sinceUp = 0;
    return true;
  }

  private move(index: number): void {
    this.index = index;
    this.low = 0;
    this.high = 0;
    this.settle = SETTLE;
  }
}

/** What the encoder is told for a rung: its bitrate, its frame rate, and how far to shrink. */
export function cameraEncoding(
  rung: CameraRung,
  capture: { width?: number; height?: number },
): { maxBitrate: number; maxFramerate: number; scaleResolutionDownBy: number } {
  const long = Math.max(capture.width ?? 0, capture.height ?? 0);
  return {
    maxBitrate: rung.maxBitrate,
    maxFramerate: rung.fps,
    scaleResolutionDownBy: long > 0 ? Math.max(1, long / rung.long) : 1,
  };
}

type Stat = Record<string, unknown> & { type?: unknown; id?: unknown };

function num(value: unknown): number | null {
  return typeof value === "number" && Number.isFinite(value) ? value : null;
}

/**
 * The camera's reading from its sender's stats (`getStats(sender)`): its `outbound-rtp`, the
 * `remote-inbound-rtp` the other side reports for it, and the link's selected candidate pair.
 * Null while nothing goes out yet (no `outbound-rtp`).
 */
export function readCameraSample(stats: Iterable<Stat>): CameraSample | null {
  const all = [...stats];
  const byId = new Map<unknown, Stat>(all.map((stat) => [stat.id, stat]));
  const outbound = all.find((stat) => stat.type === "outbound-rtp" && stat.kind !== "audio");
  if (!outbound) return null;
  const remote = all.find((stat) => stat.type === "remote-inbound-rtp" && stat.kind !== "audio");
  const transport = all.find((stat) => stat.type === "transport");
  const selected = transport ? byId.get(transport.selectedCandidatePairId) : undefined;
  const pair =
    selected ??
    all.find((stat) => stat.type === "candidate-pair" && stat.nominated === true && stat.state === "succeeded") ??
    all.find((stat) => stat.type === "candidate-pair" && stat.selected === true);
  const reason = outbound.qualityLimitationReason;
  return {
    estimate: num(pair?.availableOutgoingBitrate),
    limitation: reason === "none" || reason === "cpu" || reason === "bandwidth" || reason === "other" ? reason : null,
    loss: num(remote?.fractionLost),
  };
}
