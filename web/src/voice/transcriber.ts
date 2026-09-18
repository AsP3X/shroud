/*
 * On-device transcription for voice notes recorded in the browser — the web
 * counterpart of iOS `VoiceTranscriber`. Whisper runs in a worker on the
 * finished PCM (not a cloud API), so the text can be sealed into the payload
 * like an iPhone transcript.
 *
 * The first note may go out without a transcript while the model downloads
 * (same rule as iOS: never hold Send for hundreds of megabytes). Later notes
 * transcribe locally. Recipients can still transcribe on their device and
 * share the text back as an annotation.
 */

import { clampTranscript } from "../crypto/mediaPayload";
import { concatPcm, transcriptionSession } from "./transcription/session";

/** How long Send will wait for Whisper. A first-time download is skipped instead. */
const TRANSCRIBE_TIMEOUT_MS = 15_000;

export function prepareTranscription(): void {
  void transcriptionSession.prepare().catch(() => {
    /* best effort — the next note retries */
  });
}

export function isTranscriptionReady(): boolean {
  return transcriptionSession.isPrepared;
}

/** Drops punctuation-only / empty decoder junk. Same idea as iOS `VoiceTranscript.cleaned`. */
export function cleanedTranscript(text: string): string {
  const t = text.replace(/\s+/g, " ").trim();
  if (!t) return "";
  if (!/[A-Za-z\u00C0-\u024F\u0400-\u04FF\u0600-\u06FF\u3040-\u30FF\u4E00-\u9FFF]/.test(t)) return "";
  return clampTranscript(t);
}

/**
 * Transcribe a recorded take. Resolves null when the model is not ready in time,
 * the clip is too short, or Whisper fails — Send still goes out.
 */
export async function transcribeVoiceNote(
  chunks: Float32Array[],
  sampleRate: number,
): Promise<string | null> {
  if (chunks.length === 0 || sampleRate <= 0) return null;
  const samples = concatPcm(chunks);
  if (samples.length < sampleRate * 0.3) return null;

  const work = (async () => {
    try {
      await transcriptionSession.prepare();
      const out = await transcriptionSession.transcribe(samples, sampleRate);
      return cleanedTranscript(out.text) || null;
    } catch {
      return null;
    }
  })();

  return new Promise((resolve) => {
    const timer = window.setTimeout(() => resolve(null), TRANSCRIBE_TIMEOUT_MS);
    void work.then((text) => {
      window.clearTimeout(timer);
      resolve(text);
    });
  });
}
