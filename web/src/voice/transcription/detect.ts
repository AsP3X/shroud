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

/** Whisper's code where it differs from the ISO code the hints use. */
const WHISPER_CODE: Record<string, string> = { nb: "no" };

/**
 * The spoken language from Whisper's language probabilities.
 *
 * Whisper picks from about a hundred languages and confuses close ones on short
 * notes (German heard as Dutch or Afrikaans). `candidates` are the languages this
 * person is known to use. A language outside them wins only when it is
 * OUTSIDE_CANDIDATE_ODDS times likelier than the best candidate, so a note that
 * really is in another language still gets it. With no candidates the most likely
 * language wins.
 */
export function pickSpokenLanguage(
  probabilities: ReadonlyMap<string, number>,
  candidates: readonly string[],
): string | null {
  const allowed = new Set(candidates.map((code) => WHISPER_CODE[code] ?? code));
  let picked: string | null = null;
  let best = Number.NEGATIVE_INFINITY;
  for (const [code, probability] of probabilities) {
    if (!Number.isFinite(probability)) continue;
    const score =
      allowed.size === 0 || allowed.has(code) ? probability : probability / OUTSIDE_CANDIDATE_ODDS;
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

/**
 * Keeps only the picked language token in each row (`pickSpokenLanguage`), so the
 * next sample cannot be a text or timestamp token. Returns that token id, or null
 * when no language score was usable.
 */
export function maskToLanguage(
  data: Float32Array | Float64Array,
  vocab: number,
  tokens: LanguageToken[],
  candidates: readonly string[] = [],
): number | null {
  if (vocab <= 0 || tokens.length === 0 || data.length < vocab) return null;
  const rows = Math.floor(data.length / vocab);
  let chosen: number | null = null;
  for (let row = 0; row < rows; row++) {
    const offset = row * vocab;
    const code = pickSpokenLanguage(languageProbabilities(data, offset, vocab, tokens), candidates);
    const id = tokens.find((token) => token.code === code)?.id;
    if (id == null) continue;
    data.fill(Number.NEGATIVE_INFINITY, offset, offset + vocab);
    data[offset + id] = 0;
    chosen ??= id;
  }
  return chosen;
}
