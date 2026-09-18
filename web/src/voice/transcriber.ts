/*
 * On-device transcription for voice notes recorded in the browser — the web
 * counterpart of iOS `VoiceTranscriber`. Whisper runs in a worker on the
 * finished PCM (not a cloud API), so the text can be sealed into the payload
 * like an iPhone transcript.
 *
 * The first note may go out without a transcript while the model downloads
 * (same rule as iOS: never hold Send for hundreds of megabytes). Later notes
 * transcribe locally. Recipients can still transcribe on their device and
 * share the text back as an annotation. If this device finishes after send,
 * we share the same annotation so the other side still sees it.
 */

import { clampTranscript } from "../crypto/mediaPayload";
import { concatPcm, transcriptionSession } from "./transcription/session";

/** How long Send will wait for Whisper when the model is already on disk. */
export const TRANSCRIBE_TIMEOUT_MS = 15_000;

export function prepareTranscription(): void {
  void transcriptionSession.prepare().catch((err) => {
    console.warn("Whisper prepare failed:", err);
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
 * Transcribe a recorded take. Resolves null when the clip is too short or
 * Whisper fails. Does not time out: the send path races this against
 * `TRANSCRIBE_TIMEOUT_MS` so a first-time download never blocks Send.
 */
export async function transcribeVoiceNote(
  chunks: Float32Array[],
  sampleRate: number,
): Promise<string | null> {
  if (chunks.length === 0 || sampleRate <= 0) return null;
  const samples = concatPcm(chunks);
  if (samples.length < sampleRate * 0.3) return null;

  try {
    await transcriptionSession.prepare();
    const out = await transcriptionSession.transcribe(samples, sampleRate);
    return cleanedTranscript(out.text) || null;
  } catch (err) {
    console.warn("Whisper transcription failed:", err);
    return null;
  }
}

/** Resolves `work` or `fallback` after `ms`. Does not cancel `work`. */
export function raceTimeout<T>(work: Promise<T>, ms: number, fallback: T): Promise<T> {
  return new Promise((resolve) => {
    const timer = window.setTimeout(() => resolve(fallback), ms);
    void work.then(
      (value) => {
        window.clearTimeout(timer);
        resolve(value);
      },
      () => {
        window.clearTimeout(timer);
        resolve(fallback);
      },
    );
  });
}
