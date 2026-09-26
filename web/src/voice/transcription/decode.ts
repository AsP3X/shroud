/**
 * How a finished voice note is decoded.
 *
 * Whisper reads about 30 seconds at a time. Without timestamps, an early stop
 * (a pause, or the token budget on a dense script) drops the rest of that window.
 * Timestamps say how far the words reached. The worker then decodes whatever is
 * still left, including audio past the first 30 seconds. Language is the caller's.
 */

export const WHISPER_WINDOW_SECONDS = 30;
/** A shorter remainder than this stays with the pass that already heard it. */
export const VOICE_NOTE_TAIL_SECONDS = 0.3;
/** A breath between sentences. A longer hole is audio the model skipped. */
const COVERAGE_GAP_SECONDS = 2;

export function voiceNoteDecodeOptions(): {
  task: "transcribe";
  return_timestamps: true;
} {
  return {
    task: "transcribe",
    return_timestamps: true,
  };
}

export type VoiceChunk = {
  timestamp?: [number | null, number | null];
  text?: string;
};

/**
 * Text from the start of this pass, stopping at the first gap.
 * Chunks after a gap are left out so the next pass can cover that audio once.
 * Returns null when the model did not report timestamps.
 */
export function contiguousVoice(
  chunks: VoiceChunk[] | undefined,
  audioSeconds: number,
): { text: string; coveredUntil: number } | null {
  if (!chunks || chunks.length === 0 || audioSeconds <= 0) return null;
  const spans: { start: number; end: number; text: string }[] = [];
  for (const chunk of chunks) {
    const start = chunk.timestamp?.[0];
    if (start == null || !Number.isFinite(start)) continue;
    const endStamp = chunk.timestamp?.[1];
    const end = endStamp != null && Number.isFinite(endStamp) ? endStamp : start;
    spans.push({
      start: Math.max(0, start),
      end: Math.min(audioSeconds, Math.max(start, end)),
      text: chunk.text ?? "",
    });
  }
  if (spans.length === 0) return null;
  spans.sort((a, b) => a.start - b.start || a.end - b.end);

  let cursor = 0;
  let text = "";
  let opened = false;
  for (const span of spans) {
    if (opened && span.start > cursor + COVERAGE_GAP_SECONDS) break;
    text += span.text;
    cursor = Math.max(cursor, span.end);
    opened = true;
  }
  return { text, coveredUntil: Math.min(cursor, audioSeconds) };
}

/** Joins passes. Latin words keep a space; CJK and similar scripts do not gain one. */
export function joinVoicePieces(pieces: string[]): string {
  const cleaned = pieces.map((piece) => piece.replace(/\s+/g, " ").trim()).filter(Boolean);
  if (cleaned.length === 0) return "";
  let out = cleaned[0];
  for (let i = 1; i < cleaned.length; i++) {
    const next = cleaned[i];
    const boundary = `${out.slice(-1)}${next.slice(0, 1)}`;
    const unspaced = /[\u0600-\u06FF\u3040-\u30FF\u3400-\u9FFF\uAC00-\uD7AF\u0E00-\u0E7F]/.test(boundary);
    out += unspaced ? next : ` ${next}`;
  }
  return out;
}

/**
 * Sample index where the next pass starts, or null when this note is covered.
 * `coveredUntil` is seconds heard in the current slice (null when unknown).
 * Audio longer than one window is continued even without timestamps, because the
 * model is only given the first 30 seconds of a slice.
 */
export function nextVoiceSample(args: {
  totalSamples: number;
  offset: number;
  sampleRate: number;
  coveredUntil: number | null;
}): number | null {
  const { totalSamples, offset, sampleRate, coveredUntil } = args;
  if (sampleRate <= 0 || offset < 0 || offset >= totalSamples) return null;
  const remaining = (totalSamples - offset) / sampleRate;
  if (remaining <= VOICE_NOTE_TAIL_SECONDS) return null;
  const heard = Math.min(remaining, WHISPER_WINDOW_SECONDS);

  let advance: number;
  if (coveredUntil == null) {
    if (remaining <= WHISPER_WINDOW_SECONDS + VOICE_NOTE_TAIL_SECONDS) return null;
    advance = WHISPER_WINDOW_SECONDS;
  } else {
    const covered = Math.min(Math.max(coveredUntil, 0), heard);
    if (covered < VOICE_NOTE_TAIL_SECONDS) return null;
    if (heard - covered > VOICE_NOTE_TAIL_SECONDS) {
      advance = covered;
    } else if (remaining <= WHISPER_WINDOW_SECONDS + VOICE_NOTE_TAIL_SECONDS) {
      return null;
    } else {
      advance = heard;
    }
  }

  const next = offset + Math.round(advance * sampleRate);
  if (next <= offset) return null;
  if (next >= totalSamples - VOICE_NOTE_TAIL_SECONDS * sampleRate) return null;
  return next;
}
