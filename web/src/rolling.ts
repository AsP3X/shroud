/**
 * The rules behind `RollingText`: which character slot is which, and how long a fast readout waits.
 * Web twin of iOS `rollingDigits` / `PacedRollingText` (`ShroudUI/Theme/Motion.swift`) and Android
 * `RollingText` / `rememberPaced`.
 */

/**
 * Shortest gap between two changes of a fast readout (bytes moved, a download's percent), so each roll
 * finishes before the next begins — iOS `Motion.readoutPace`.
 */
export const READOUT_PACE_MS = 500;

/**
 * How long a paced readout holds a new value back: until a pace has passed since the last change it
 * showed (`shownAt`, null before the first), or 0 to show it now. The deadline hangs off that change,
 * so a steady stream of values still lands once per pace.
 */
export function paceDelay(shownAt: number | null, now: number, pacing: boolean): number {
  if (!pacing || shownAt == null) return 0;
  return Math.max(0, shownAt + READOUT_PACE_MS - now);
}

/** One character of a rolling line, keyed by its distance from the end. */
export interface RollSlot {
  key: number;
  char: string;
}

/**
 * A run of the line: a word of slots (never broken across lines), or the spaces between words. Keyed by
 * the distance from its end to the line's end.
 */
export type RollRun = { key: number; slots: RollSlot[] } | { key: number; gap: string };

/**
 * `text` as words of character slots and the spaces between them. Everything is keyed from the end,
 * so "9:59" → "10:00" keeps the seconds' slots and rolls only what changed; a long line may still
 * wrap, but only at its spaces.
 */
export function rollRuns(text: string): RollRun[] {
  const runs: RollRun[] = [];
  const length = text.length;
  for (const match of text.matchAll(/ +|[^ ]+/g)) {
    const run = match[0];
    const start = match.index ?? 0;
    const key = length - start - run.length;
    if (run[0] === " ") {
      runs.push({ key, gap: run });
    } else {
      runs.push({ key, slots: Array.from(run, (char, offset) => ({ key: length - start - offset, char })) });
    }
  }
  return runs;
}
