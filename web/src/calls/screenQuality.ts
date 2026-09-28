import { storageSealed } from "../storageSeal";

/**
 * How our shared screen goes out: its resolution and its frame rate, as Discord offers them. Chosen
 * in the call (the arrow on Share), before sharing or while it runs, where it applies at once.
 * Per browser, in localStorage like `relay.ts`: a preference, not a secret. docs/calls.md,
 * "Screen sharing".
 */

export type ScreenResolution = "720p" | "1080p" | "source";
export type ScreenFrameRate = 15 | 30 | 60;
export type ScreenQuality = { resolution: ScreenResolution; frameRate: ScreenFrameRate };

export const SCREEN_RESOLUTIONS: readonly { value: ScreenResolution; label: string }[] = [
  { value: "720p", label: "720p" },
  { value: "1080p", label: "1080p" },
  { value: "source", label: "Source" },
];
export const SCREEN_FRAME_RATES: readonly ScreenFrameRate[] = [15, 30, 60];

/** Sharp text at a video's pace: what sharing did before there was a choice. */
export const DEFAULT_SCREEN_QUALITY: ScreenQuality = { resolution: "1080p", frameRate: 30 };

const KEY = "shroud.screenQuality";

function isResolution(value: unknown): value is ScreenResolution {
  return value === "720p" || value === "1080p" || value === "source";
}

function isFrameRate(value: unknown): value is ScreenFrameRate {
  return value === 15 || value === 30 || value === 60;
}

/** Reads a stored choice; anything else is the default. */
export function parseScreenQuality(raw: string | null): ScreenQuality {
  if (!raw) return DEFAULT_SCREEN_QUALITY;
  try {
    const value = JSON.parse(raw) as Partial<ScreenQuality> | null;
    return {
      resolution: isResolution(value?.resolution) ? value.resolution : DEFAULT_SCREEN_QUALITY.resolution,
      frameRate: isFrameRate(value?.frameRate) ? value.frameRate : DEFAULT_SCREEN_QUALITY.frameRate,
    };
  } catch {
    return DEFAULT_SCREEN_QUALITY;
  }
}

export function loadScreenQuality(): ScreenQuality {
  try {
    return parseScreenQuality(localStorage.getItem(KEY));
  } catch {
    return DEFAULT_SCREEN_QUALITY;
  }
}

export function saveScreenQuality(quality: ScreenQuality): void {
  try {
    if (!storageSealed()) localStorage.setItem(KEY, JSON.stringify(quality));
  } catch {
    // Storage blocked (private mode): the choice holds for this tab only.
  }
}

/** "1080p · 30 fps", for the Share button's arrow. */
export function screenQualityText(quality: ScreenQuality): string {
  const resolution = SCREEN_RESOLUTIONS.find((r) => r.value === quality.resolution)?.label ?? quality.resolution;
  return `${resolution} · ${quality.frameRate} fps`;
}

/**
 * What the picker asks for: the most any choice can use, the screen's own pixels at up to 60 fps.
 * A browser may fix a capture's size and rate at what it was opened with (Chrome does), so a
 * capture opened at 720p and 15 fps could never be raised to Source or 60 fps mid-share. It is
 * narrowed to the choice at once with `screenVideoConstraints` instead.
 */
export const SCREEN_CAPTURE_CEILING: MediaTrackConstraints = { frameRate: { max: 60 } };

/** The largest box a resolution allows, or null for the screen's own pixels. */
function screenBox(resolution: ScreenResolution): { width: number; height: number } | null {
  switch (resolution) {
    case "720p":
      return { width: 1280, height: 720 };
    case "1080p":
      return { width: 1920, height: 1080 };
    case "source":
      return null;
  }
}

/**
 * The capture narrowed to the choice with `applyConstraints`: fitted inside a 16:9 box of that
 * size (720p, 1080p), never enlarged, or the screen's own pixels ("source"), at up to the frame
 * rate. `applyConstraints` replaces every earlier constraint, so "source" lifts the size limit.
 */
export function screenVideoConstraints(quality: ScreenQuality): MediaTrackConstraints {
  const frameRate = { ideal: quality.frameRate, max: quality.frameRate };
  const box = screenBox(quality.resolution);
  return box ? { width: { max: box.width }, height: { max: box.height }, frameRate } : { frameRate };
}

/**
 * How much the encoder shrinks the picture to fit the choice. 1 once the capture was narrowed
 * (it already fits); more when a browser refused to narrow it and it still comes at full size.
 */
export function screenScaleDown(size: { width?: number; height?: number }, quality: ScreenQuality): number {
  const box = screenBox(quality.resolution);
  if (!box || !size.width || !size.height) return 1;
  return Math.max(1, size.width / box.width, size.height / box.height);
}

/**
 * The most the screen may use, in bits per second: more pixels and more frames need more to stay
 * sharp. 1080p at 30 fps keeps the 2.5 Mbps it always had.
 */
export function screenBitrate(quality: ScreenQuality): number {
  const table: Record<ScreenResolution, Record<ScreenFrameRate, number>> = {
    "720p": { 15: 1_200_000, 30: 1_800_000, 60: 2_800_000 },
    "1080p": { 15: 1_800_000, 30: 2_500_000, 60: 4_000_000 },
    source: { 15: 3_000_000, 30: 4_500_000, 60: 6_500_000 },
  };
  return table[quality.resolution][quality.frameRate];
}

/**
 * What the encoder should keep when the link is tight. Up to 30 fps a screen is text and edges:
 * sharp, giving up frames (`detail`, `maintain-resolution`). 60 fps was asked for motion (a
 * game, a video): it is encoded as such and gives up detail and frames in turn.
 */
export function screenMotion(quality: ScreenQuality): { hint: "detail" | "motion"; degradation: RTCDegradationPreference } {
  return quality.frameRate >= 60
    ? { hint: "motion", degradation: "balanced" }
    : { hint: "detail", degradation: "maintain-resolution" };
}
