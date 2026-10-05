/**
 * Whisper language ids. The web runtime does not detect a language on its own:
 * with none supplied it transcribes as English. These helpers score the language
 * tokens after one decoder step, which is the check Whisper uses, and pick one.
 */

const LANGUAGE_TOKEN = /^<\|([a-z]{2})\|>$/;

export type LanguageToken = { code: string; id: number };

export function languageTokensFromMap(
  langToId: Record<string, number> | null | undefined,
): LanguageToken[] {
  if (!langToId) return [];
  const tokens: LanguageToken[] = [];
  for (const [token, id] of Object.entries(langToId)) {
    const match = LANGUAGE_TOKEN.exec(token);
    if (!match || !Number.isInteger(id) || id < 0) continue;
    tokens.push({ code: match[1], id });
  }
  return tokens;
}

export function languageFromTokenId(
  id: number | null | undefined,
  tokens: LanguageToken[],
): string | null {
  if (id == null || !Number.isInteger(id)) return null;
  return tokens.find((token) => token.id === id)?.code ?? null;
}

/** Last id in a decoded batch of shape [1, tokens]. Accepts number or bigint ids. */
export function lastGeneratedId(listed: unknown): number | null {
  if (!Array.isArray(listed) || !Array.isArray(listed[0]) || listed[0].length === 0) return null;
  const last: unknown = listed[0][listed[0].length - 1];
  if (typeof last === "bigint") return Number(last);
  if (typeof last === "number" && Number.isInteger(last)) return last;
  return null;
}

/**
 * How many times likelier a language outside the candidates must be than the best
 * candidate before it wins. Same value as iOS `SpokenLanguagePick` and Android.
 */
export const OUTSIDE_CANDIDATE_ODDS = 5;

/** A chat's history makes its language up to 1 + HISTORY_ODDS times likelier. */
export const HISTORY_ODDS = 2;

/** Notes' worth of history at which it weighs in fully. */
export const HISTORY_SATURATION = 3;

/** Whisper's code where it differs from the ISO code the hints use. */
const WHISPER_CODE: Record<string, string> = { nb: "no" };

/** The languages heard in a chat, by weight (`history` in `../language`). */
export type LanguageHistory = Readonly<Record<string, number>>;

/**
 * The spoken language from Whisper's language probabilities.
 *
 * The audio decides; what is known about this person only weighs it. `candidates`
 * are the device's languages and English: a language outside them needs
 * OUTSIDE_CANDIDATE_ODDS times the probability. A chat's `history` makes the
 * language it is spoken in up to 1 + HISTORY_ODDS times likelier, at full strength
 * once HISTORY_SATURATION notes' worth has been heard. That settles a short note
 * Whisper is unsure about, and a clear note in another language still wins. With
 * no candidates and no history the most likely language wins.
 */
export function pickSpokenLanguage(
  probabilities: ReadonlyMap<string, number>,
  candidates: readonly string[],
  history: LanguageHistory = {},
): string | null {
  const allowed = new Set(candidates.map((code) => WHISPER_CODE[code] ?? code));
  const heard = new Map<string, number>();
  let total = 0;
  for (const [code, weight] of Object.entries(history)) {
    if (!(weight > 0)) continue;
    const whisper = WHISPER_CODE[code] ?? code;
    heard.set(whisper, (heard.get(whisper) ?? 0) + weight);
    total += weight;
  }
  const strength = HISTORY_ODDS * Math.min(1, total / HISTORY_SATURATION);
  let picked: string | null = null;
  let best = Number.NEGATIVE_INFINITY;
  for (const [code, probability] of probabilities) {
    if (!Number.isFinite(probability)) continue;
    const known = allowed.size === 0 || allowed.has(code) ? 1 : 1 / OUTSIDE_CANDIDATE_ODDS;
    const share = total > 0 ? (heard.get(code) ?? 0) / total : 0;
    const score = probability * known * (1 + strength * share);
    // Ties go to the smaller code, so the pick never depends on token order.
    if (score > best || (score === best && picked !== null && code < picked)) {
      best = score;
      picked = code;
    }
  }
  return picked;
}

/** Softmax over the language tokens of one row of logits. Empty when no score is finite. */
export function languageProbabilities(
  data: Float32Array | Float64Array,
  offset: number,
  vocab: number,
  tokens: LanguageToken[],
): Map<string, number> {
  const scores: [string, number][] = [];
  let max = Number.NEGATIVE_INFINITY;
  for (const token of tokens) {
    if (token.id < 0 || token.id >= vocab) continue;
    const score = data[offset + token.id];
    if (!Number.isFinite(score)) continue;
    scores.push([token.code, score]);
    if (score > max) max = score;
  }
  let total = 0;
  for (const entry of scores) {
    entry[1] = Math.exp(entry[1] - max);
    total += entry[1];
  }
  return new Map(scores.map(([code, weight]) => [code, weight / total]));
}

/** The language token kept by `maskToLanguage`, and what the audio alone gave it. */
export type LanguagePick = { id: number; code: string; probability: number };

/**
 * Keeps only the picked language token in each row (`pickSpokenLanguage`), so the
 * next sample cannot be a text or timestamp token. Returns the first row's pick,
 * or null when no language score was usable.
 */
export function maskToLanguage(
  data: Float32Array | Float64Array,
  vocab: number,
  tokens: LanguageToken[],
  candidates: readonly string[] = [],
  history: LanguageHistory = {},
): LanguagePick | null {
  if (vocab <= 0 || tokens.length === 0 || data.length < vocab) return null;
  const rows = Math.floor(data.length / vocab);
  let chosen: LanguagePick | null = null;
  for (let row = 0; row < rows; row++) {
    const offset = row * vocab;
    const probabilities = languageProbabilities(data, offset, vocab, tokens);
    const code = pickSpokenLanguage(probabilities, candidates, history);
    const id = tokens.find((token) => token.code === code)?.id;
    if (id == null || code == null) continue;
    data.fill(Number.NEGATIVE_INFINITY, offset, offset + vocab);
    data[offset + id] = 0;
    chosen ??= { id, code, probability: probabilities.get(code) ?? 0 };
  }
  return chosen;
}
