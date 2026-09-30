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
import {
  challenger,
  choose,
  cleaned,
  decodeHints,
  learningWeight,
  override,
  prior,
  record,
  score as scoreTranscript,
  MINIMUM_TRUSTED_SCORE,
  shouldForceLanguage,
  type Candidate,
} from "./language";
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
    const winner = await decodeVoiceNote(samples, sampleRate, peerId, audioSeconds);
    const text = cleanedTranscript(winner.text);
    if (text && winner.language) {
      const scored = scoreTranscript({
        text,
        modelConfidence: winner.confidence,
        audioSeconds,
        prior: prior(winner.language, peerId),
      });
      const weight = learningWeight(audioSeconds, scored);
      if (weight > 0) record(winner.language, peerId, weight);
    }
    return text || null;
  } catch (err) {
    console.warn("Whisper transcription failed:", err);
    return null;
  }
}

async function decodeVoiceNote(
  samples: Float32Array,
  sampleRate: number,
  peerId: string | null,
  audioSeconds: number,
): Promise<Candidate> {
  const hints = decodeHints(peerId);
  const run = (language: string | null) =>
    transcriptionSession.transcribe(samples, sampleRate, { language });

  if (override() && hints[0]) {
    const out = await run(hints[0]);
    return { text: out.text, language: hints[0], confidence: 0.7 };
  }

  const trusted =
    hints[0] && shouldForceLanguage(hints[0], prior(hints[0], peerId)) ? hints[0] : null;
  if (trusted) {
    const forced = await run(trusted);
    const forcedCandidate: Candidate = { text: forced.text, language: trusted, confidence: 0.7 };
    const forcedScore = scoreTranscript({
      text: cleaned(forced.text),
      modelConfidence: 0.7,
      audioSeconds,
      prior: prior(trusted, peerId),
    });
    if (forcedScore >= MINIMUM_TRUSTED_SCORE) return forcedCandidate;
    const auto = await run(null);
    return choose(
      { text: auto.text, language: auto.language, confidence: 0.7 },
      forcedCandidate,
      peerId,
      audioSeconds,
    );
  }

  const auto = await run(null);
  const challengeLang = challenger(auto.language, hints);
  if (!challengeLang) {
    return { text: auto.text, language: auto.language, confidence: 0.7 };
  }
  const alt = await run(challengeLang);
  return choose(
    { text: auto.text, language: auto.language, confidence: 0.7 },
    { text: alt.text, language: challengeLang, confidence: 0.7 },
    peerId,
    audioSeconds,
  );
}
