/**
 * Band-limited resampling. Run: npx tsx src/voice/wav.selftest.ts
 */
import { resample } from "./wav";

function check(cond: boolean, message: string): void {
  if (!cond) throw new Error(message);
}

function tone(hz: number, rate: number, seconds = 1): Float32Array {
  const out = new Float32Array(Math.round(rate * seconds));
  for (let i = 0; i < out.length; i++) out[i] = Math.sin((2 * Math.PI * hz * i) / rate);
  return out;
}

/** Level against a full-scale sine, in dB, over the middle of the signal. */
function levelDb(samples: Float32Array): number {
  const from = Math.floor(samples.length * 0.2);
  const to = Math.floor(samples.length * 0.8);
  let power = 0;
  for (let i = from; i < to; i++) power += samples[i] * samples[i];
  return 10 * Math.log10(power / (to - from) / 0.5 + 1e-30);
}

const level = (hz: number, from: number, to: number) => levelDb(resample(tone(hz, from), from, to));

check(resample(tone(1000, 48_000), 48_000, 16_000).length === 16_000, "one second stays one second");
check(Math.abs(level(1000, 48_000, 16_000)) < 0.05, "speech frequencies pass unchanged");
check(Math.abs(level(6000, 48_000, 16_000)) < 0.5, "the band up to 6 kHz is flat");
check(Math.abs(level(6000, 44_100, 16_000)) < 0.5, "44.1 kHz input is flat up to 6 kHz");
// Linear interpolation passed these at 0 dB, folded to 6, 4 and 1.6 kHz.
check(level(10_000, 48_000, 16_000) < -70, "10 kHz does not fold into the speech band");
check(level(12_000, 48_000, 16_000) < -70, "12 kHz does not fold into the speech band");
check(level(14_400, 44_100, 16_000) < -70, "44.1 kHz input is filtered too");
check(level(12_000, 48_000, 22_050) < -70, "the 22.05 kHz voice-note WAV is filtered too");
check(Math.abs(level(1000, 8000, 16_000)) < 0.05, "upsampling keeps the level");

const ones = resample(new Float32Array(4800).fill(1), 48_000, 16_000);
check(
  ones.every((v) => Math.abs(v - 1) < 1e-4),
  "a constant stays constant up to both edges",
);
const same = new Float32Array([0.1, 0.2]);
check(resample(same, 16_000, 16_000) === same, "same rate is a no-op");
check(resample(new Float32Array(0), 48_000, 16_000).length === 0, "empty stays empty");
check(resample(new Float32Array([0.5]), 48_000, 16_000).length === 1, "a single sample still yields one");

console.log("wav resample selftest ok");
