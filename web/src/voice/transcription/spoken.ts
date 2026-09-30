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

function languageMask(tokens: LanguageToken[]): LogitsProcessor {
  const proc = new LogitsProcessor();
  const call = (_inputIds: bigint[][], logits: Tensor): Tensor => {
    const vocab = logits.dims[logits.dims.length - 1] ?? 0;
    const data = logits.data;
    if (vocab > 0 && (data instanceof Float32Array || data instanceof Float64Array)) {
      maskToLanguage(data, vocab, tokens);
    }
    return logits;
  };
  (proc as unknown as { _call: typeof call })._call = call;
  return proc;
}

/** ISO 639-1 code from the first 30 seconds, or null when the model cannot say. */
export async function detectSpokenLanguage(
  transcriber: unknown,
  samples: Float32Array,
): Promise<string | null> {
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
    const list = new LogitsProcessorList();
    list.push(languageMask(tokens));
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
    return languageFromTokenId(lastGeneratedId(listed), tokens);
  } catch (err) {
    console.warn("Whisper language detection failed:", err instanceof Error ? err.message : err);
    return null;
  }
}
