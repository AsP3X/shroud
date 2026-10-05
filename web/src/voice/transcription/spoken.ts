/**
 * One language for a whole voice note.
 *
 * transformers.js leaves language detection as a to-do and, when no language
 * is passed, transcribes as English. Whisper's own check is a single decoder
 * step from the start-of-transcript token, with every non-language token
 * masked off. That code is then used for every window of the note, so a quiet
 * tail cannot switch the language.
 */

import { LogitsProcessor, LogitsProcessorList, Tensor } from "@huggingface/transformers";
import { WHISPER_WINDOW_SECONDS } from "./decode";
import {
  languageFromTokenId,
  languageTokensFromMap,
  lastGeneratedId,
  maskToLanguage,
  type LanguageHistory,
  type LanguagePick,
  type LanguageToken,
} from "./detect";

const SAMPLE_RATE = 16_000;

type GenerationConfig = {
  decoder_start_token_id?: number | null;
  lang_to_id?: Record<string, number> | null;
  is_multilingual?: boolean | null;
};

type Detectable = {
  model?: {
    generation_config?: GenerationConfig | null;
    generate?: (args: Record<string, unknown>) => Promise<{ tolist?: () => unknown }>;
  };
  processor?: (audio: Float32Array) => Promise<{ input_features?: unknown }>;
};

function languageMask(
  tokens: LanguageToken[],
  candidates: readonly string[],
  history: LanguageHistory,
  picked: (pick: LanguagePick | null) => void,
): LogitsProcessor {
  const proc = new LogitsProcessor();
  const call = (_inputIds: bigint[][], logits: Tensor): Tensor => {
    const vocab = logits.dims[logits.dims.length - 1] ?? 0;
    const data = logits.data;
    if (vocab > 0 && (data instanceof Float32Array || data instanceof Float64Array)) {
      picked(maskToLanguage(data, vocab, tokens, candidates, history));
    }
    return logits;
  };
  (proc as unknown as { _call: typeof call })._call = call;
  return proc;
}

/** The language a note is decoded in, and the probability the audio alone gave it. */
export type SpokenLanguage = { code: string; probability: number };

/**
 * ISO 639-1 code from the first 30 seconds, or null when the model cannot say.
 * `candidates` are the device's languages and `history` the chat's; they weigh
 * Whisper's probabilities (`pickSpokenLanguage`).
 */
export async function detectSpokenLanguage(
  transcriber: unknown,
  samples: Float32Array,
  candidates: readonly string[],
  history: LanguageHistory = {},
): Promise<SpokenLanguage | null> {
  const pipe = transcriber as Detectable;
  const model = pipe.model;
  const processor = pipe.processor;
  const config = model?.generation_config;
  if (!model?.generate || !processor || !config || config.is_multilingual === false) return null;
  const tokens = languageTokensFromMap(config.lang_to_id);
  const sot = config.decoder_start_token_id;
  if (tokens.length === 0 || sot == null || samples.length < SAMPLE_RATE * 0.25) return null;

  const heard = Math.min(samples.length, WHISPER_WINDOW_SECONDS * SAMPLE_RATE);
  const opening = heard === samples.length ? samples : samples.subarray(0, heard);
  try {
    const features = await processor(opening);
    if (!features.input_features) return null;
    // The mask sees the probabilities; the first row's pick is the one generated.
    const seen: { pick: LanguagePick | null } = { pick: null };
    const list = new LogitsProcessorList();
    list.push(languageMask(tokens, candidates, history, (picked) => {
      seen.pick ??= picked;
    }));
    const output = await model.generate({
      inputs: features.input_features,
      decoder_input_ids: new Tensor("int64", [BigInt(sot)], [1, 1]),
      max_new_tokens: 1,
      do_sample: false,
      temperature: 0,
      return_timestamps: false,
      logits_processor: list,
    });
    const listed = output.tolist?.();
    const code = languageFromTokenId(lastGeneratedId(listed), tokens);
    if (!code) return null;
    return { code, probability: seen.pick?.code === code ? seen.pick.probability : 0 };
  } catch (err) {
    console.warn("Whisper language detection failed:", err instanceof Error ? err.message : err);
    return null;
  }
}
