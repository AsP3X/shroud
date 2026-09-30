/**
 * A voice note is decoded to the end. Run: npx tsx src/voice/transcription/decode.selftest.ts
 */
import {
  contiguousVoice,
  joinVoicePieces,
  nextVoiceSample,
  voiceNoteDecodeOptions,
} from "./decode";

function check(cond: boolean, message: string): void {
  if (!cond) throw new Error(message);
}

const options = voiceNoteDecodeOptions();
check(options.return_timestamps === true, "timestamps are what let a pause not end the note");
check(options.task === "transcribe", "a voice note is transcribed, not translated");

const rate = 16_000;
const early = contiguousVoice(
  [
    { timestamp: [0, 4], text: "hello " },
    { timestamp: [4.1, 8], text: "there" },
    { timestamp: [14, 18], text: "skipped" },
  ],
  20,
);
check(early?.text === "hello there", "text stops at the gap instead of keeping the later chunk");
check(early?.coveredUntil === 8, "the next pass starts where the words stopped");

const pause = contiguousVoice(
  [
    { timestamp: [0, 3.2], text: "hello" },
    { timestamp: [3.8, 9], text: " there" },
  ],
  12,
);
check(pause?.text === "hello there", "a breath between sentences stays in the same pass");
check(pause?.coveredUntil === 9, "coverage includes both sides of a pause");

const whole = contiguousVoice([{ timestamp: [0, 11.5], text: "all of it" }], 12);
check(whole?.coveredUntil === 11.5, "a finished slice reports how far it heard");

check(contiguousVoice([{ text: "no times" }], 12) == null, "missing timestamps are not a coverage claim");

check(
  nextVoiceSample({ totalSamples: 12 * rate, offset: 0, sampleRate: rate, coveredUntil: 4 }) === 4 * rate,
  "an early stop resumes at the last timestamp",
);
check(
  nextVoiceSample({ totalSamples: 12 * rate, offset: 0, sampleRate: rate, coveredUntil: 12 }) == null,
  "a finished note is not decoded again",
);
check(
  nextVoiceSample({ totalSamples: 45 * rate, offset: 0, sampleRate: rate, coveredUntil: 30 }) === 30 * rate,
  "audio past one window continues",
);
check(
  nextVoiceSample({ totalSamples: 45 * rate, offset: 0, sampleRate: rate, coveredUntil: null }) === 30 * rate,
  "a long note with no timestamps still continues after the first window",
);
check(
  nextVoiceSample({ totalSamples: 12 * rate, offset: 0, sampleRate: rate, coveredUntil: null }) == null,
  "a short note with no timestamps is a single pass",
);
check(
  nextVoiceSample({ totalSamples: 45 * rate, offset: 30 * rate, sampleRate: rate, coveredUntil: 15 }) == null,
  "the tail of a long note ends the walk",
);

check(joinVoicePieces(["hello", "there"]) === "hello there", "latin passes keep a space");
check(joinVoicePieces(["今日は", "いい天気"]) === "今日はいい天気", "japanese passes are not split by a space");
check(joinVoicePieces(["你好", "世界"]) === "你好世界", "chinese passes are not split by a space");
check(joinVoicePieces(["  hello  ", ""]) === "hello", "empty passes are dropped");

console.log("voice decode selftest ok");
