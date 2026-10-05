/** 16-bit mono PCM WAV. iOS AVAudioPlayer and HTMLAudioElement both play this. */

/** Sinc zero crossings on each side of an output sample. */
const ZERO_CROSSINGS = 16;
/** Cutoff as a share of the lower Nyquist rate, so the filter has stopped before it. */
const CUTOFF = 0.9;
/** Kernel table steps per zero crossing; read with linear interpolation. */
const TABLE_STEPS = 128;

/** Blackman-windowed sinc over [0, ZERO_CROSSINGS], one entry per step and one spare. */
const KERNEL = (() => {
  const table = new Float32Array(ZERO_CROSSINGS * TABLE_STEPS + 2);
  for (let i = 0; i <= ZERO_CROSSINGS * TABLE_STEPS; i++) {
    const x = i / TABLE_STEPS;
    const sinc = i === 0 ? 1 : Math.sin(Math.PI * x) / (Math.PI * x);
    const u = x / ZERO_CROSSINGS;
    table[i] = sinc * (0.42 + 0.5 * Math.cos(Math.PI * u) + 0.08 * Math.cos(2 * Math.PI * u));
  }
  return table;
})();

/**
 * Band-limited resampling with a windowed-sinc filter. Plain interpolation from
 * 48 kHz to 16 kHz folds everything above 8 kHz (the hiss of s, sh, f) back into
 * the band Whisper hears; this filter removes it first (80 dB down). Each output is
 * divided by its weight sum, so the edges keep their level. Android runs the same
 * filter (`AudioPcmDecoder.resample`); iOS converts with AVAudioConverter.
 */
export function resample(input: Float32Array, fromRate: number, toRate: number): Float32Array {
  if (fromRate === toRate || input.length === 0) return input;
  const step = fromRate / toRate;
  const outLen = Math.max(1, Math.floor(input.length / step));
  const out = new Float32Array(outLen);
  // Kernel units per input sample: below one when downsampling, which widens the
  // kernel and lowers its cutoff to the output's Nyquist rate.
  const scale = Math.min(1, toRate / fromRate) * CUTOFF;
  const reach = ZERO_CROSSINGS / scale;
  const last = input.length - 1;
  for (let i = 0; i < outLen; i++) {
    // Upsampling ends up to one input sample past the last one; hold it there.
    const center = Math.min(i * step, last);
    const from = Math.max(0, Math.ceil(center - reach));
    const to = Math.min(last, Math.floor(center + reach));
    let acc = 0;
    let weights = 0;
    for (let k = from; k <= to; k++) {
      const at = Math.abs(k - center) * scale * TABLE_STEPS;
      const j = Math.floor(at);
      const weight = KERNEL[j] + (KERNEL[j + 1] - KERNEL[j]) * (at - j);
      acc += input[k] * weight;
      weights += weight;
    }
    out[i] = weights !== 0 ? acc / weights : 0;
  }
  return out;
}

export function encodeWav(samples: Float32Array, sampleRate: number): Uint8Array {
  const n = samples.length;
  const buffer = new ArrayBuffer(44 + n * 2);
  const view = new DataView(buffer);
  writeAscii(view, 0, "RIFF");
  view.setUint32(4, 36 + n * 2, true);
  writeAscii(view, 8, "WAVE");
  writeAscii(view, 12, "fmt ");
  view.setUint32(16, 16, true);
  view.setUint16(20, 1, true);
  view.setUint16(22, 1, true);
  view.setUint32(24, sampleRate, true);
  view.setUint32(28, sampleRate * 2, true);
  view.setUint16(32, 2, true);
  view.setUint16(34, 16, true);
  writeAscii(view, 36, "data");
  view.setUint32(40, n * 2, true);
  let offset = 44;
  for (let i = 0; i < n; i++) {
    const s = Math.max(-1, Math.min(1, samples[i]));
    view.setInt16(offset, s < 0 ? s * 0x8000 : s * 0x7fff, true);
    offset += 2;
  }
  return new Uint8Array(buffer);
}

function writeAscii(view: DataView, offset: number, text: string): void {
  for (let i = 0; i < text.length; i++) view.setUint8(offset + i, text.charCodeAt(i));
}

function readAscii(view: DataView, offset: number, n: number): string {
  let text = "";
  for (let i = 0; i < n; i++) text += String.fromCharCode(view.getUint8(offset + i));
  return text;
}

/** 16-bit mono PCM only — what `encodeWav` writes. */
export function pcmFromWav(bytes: Uint8Array): { samples: Float32Array; sampleRate: number } | null {
  if (bytes.length < 44) return null;
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  if (readAscii(view, 0, 4) !== "RIFF" || readAscii(view, 8, 4) !== "WAVE") return null;
  let offset = 12;
  let sampleRate = 0;
  let bits = 0;
  let channels = 0;
  let dataOff = -1;
  let dataLen = 0;
  while (offset + 8 <= bytes.length) {
    const id = readAscii(view, offset, 4);
    const size = view.getUint32(offset + 4, true);
    const start = offset + 8;
    if (id === "fmt ") {
      if (size < 16) return null;
      if (view.getUint16(start, true) !== 1) return null;
      channels = view.getUint16(start + 2, true);
      sampleRate = view.getUint32(start + 4, true);
      bits = view.getUint16(start + 14, true);
    } else if (id === "data") {
      dataOff = start;
      dataLen = size;
      break;
    }
    offset = start + size + (size % 2);
  }
  if (dataOff < 0 || bits !== 16 || channels !== 1 || sampleRate <= 0) return null;
  const n = Math.min(Math.floor(dataLen / 2), Math.floor((bytes.length - dataOff) / 2));
  if (n <= 0) return null;
  const samples = new Float32Array(n);
  for (let i = 0; i < n; i++) {
    const s = view.getInt16(dataOff + i * 2, true);
    samples[i] = s < 0 ? s / 0x8000 : s / 0x7fff;
  }
  return { samples, sampleRate };
}
