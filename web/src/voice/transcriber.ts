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
import { detectionCandidates, deviceLanguages, history, learningWeight, override, record } from "./language";
import { concatPcm, transcriptionSession } from "./transcription/session";

export function prepareTranscription(): void {
  void transcriptionSession.prepare().catch((err) => {
    console.warn("Whisper prepare failed:", err);
  });
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
 * Whisper fails. Does not time out; Send never waits on it — the transcript
 * follows the note as an annotation.
 */
export async function transcribeVoiceNote(
  chunks: Float32Array[],
  sampleRate: number,
  opts?: { conversationId?: string },
): Promise<string | null> {
  if (chunks.length === 0 || sampleRate <= 0) return null;
  const samples = concatPcm(chunks);
  if (samples.length < sampleRate * 0.3) return null;
  const audioSeconds = samples.length / sampleRate;
  const peerId = opts?.conversationId ?? null;

  try {
    await transcriptionSession.prepare();
    // The pin, else one decode in the language Whisper detects, weighed with the
    // device's languages and this chat's history. A note is never decoded again in
    // a language Whisper did not hear: forced, it translates.
    const out = await transcriptionSession.transcribe(samples, sampleRate, {
      language: override(),
      candidates: detectionCandidates(deviceLanguages()),
      history: history(peerId),
    });
    const text = cleanedTranscript(out.text);
    if (text && out.language && out.languageProbability != null) {
      const weight = learningWeight(audioSeconds, out.languageProbability);
      if (weight > 0) record(out.language, peerId, weight);
    }
    return text || null;
  } catch (err) {
    console.warn("Whisper transcription failed:", err);
    return null;
  }
}
