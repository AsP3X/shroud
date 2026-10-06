/**
 * Rolling readouts: slots keyed from the end, words kept whole, and the readout pace.
 * Run: npx tsx src/rolling.selftest.ts
 */
import { paceDelay, READOUT_PACE_MS, rollRuns, type RollRun } from "./rolling";

function check(ok: boolean, what: string): void {
  if (!ok) throw new Error(`rolling selftest: ${what}`);
}

/** Each slot's key → char, across the whole line. */
function slots(text: string): Map<number, string> {
  const all = new Map<number, string>();
  for (const run of rollRuns(text)) {
    if ("slots" in run) for (const slot of run.slots) all.set(slot.key, slot.char);
  }
  return all;
}

function wordKey(runs: RollRun[], index: number): number {
  return runs.filter((run) => "slots" in run)[index].key;
}

// "0:41" → "0:42": only the last slot holds a different character.
const before = slots("0:41");
const after = slots("0:42");
check([...after].filter(([key, char]) => before.get(key) !== char).length === 1, "one digit changes");

// "9:59" → "10:00": the seconds keep their slots and their word keeps its key, so they roll in place.
check(slots("9:59").get(1) === "9" && slots("10:00").get(1) === "0", "last slot is the last digit");
check(wordKey(rollRuns("9:59"), 0) === wordKey(rollRuns("10:00"), 0), "a growing time keeps its word");

// "1.2 MB of 4.8 MB": words and the spaces between them, which the line may wrap at.
const runs = rollRuns("1.2 MB of 4.8 MB");
check(runs.length === 9, "five words and four gaps");
check(runs.filter((run) => "gap" in run).every((run) => "gap" in run && run.gap === " "), "single-space gaps");
const keys = runs.map((run) => `${"gap" in run ? "gap" : "word"}${run.key}`);
check(new Set(keys).size === keys.length, "run keys are unique");

// Double spaces around the compose line's middle dots survive as one gap each.
const compose = rollRuns("0:08  ·  720p");
check(compose.some((run) => "gap" in run && run.gap === "  "), "double space kept");
check(rollRuns("").length === 0, "empty line");

// The pace: the first change and an unpaced one show now; others wait out the pace since the last.
check(paceDelay(null, 1_000, true) === 0, "first change at once");
check(paceDelay(1_000, 1_200, true) === READOUT_PACE_MS - 200, "waits out the pace");
check(paceDelay(1_000, 1_000 + READOUT_PACE_MS + 1, true) === 0, "a late change at once");
check(paceDelay(1_000, 1_100, false) === 0, "unpaced at once");

console.log("rolling selftest: ok");
