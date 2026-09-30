/**
 * Whisper language ids. The web runtime does not detect a language on its own:
 * with none supplied it transcribes as English. These helpers pick the language
 * token with the highest score, which is the same one-step check Whisper uses.
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
 * Keeps only the strongest language token in each row, so the next sample
 * cannot be a text or timestamp token. Returns that token id, or null when
 * no language score was usable.
 */
export function maskToLanguage(
  data: Float32Array | Float64Array,
  vocab: number,
  tokens: LanguageToken[],
): number | null {
  if (vocab <= 0 || tokens.length === 0 || data.length < vocab) return null;
  const rows = Math.floor(data.length / vocab);
  let chosen: number | null = null;
  for (let row = 0; row < rows; row++) {
    const offset = row * vocab;
    let bestId = -1;
    let best = Number.NEGATIVE_INFINITY;
    for (const token of tokens) {
      if (token.id < 0 || token.id >= vocab) continue;
      const score = data[offset + token.id];
      if (Number.isFinite(score) && score > best) {
        best = score;
        bestId = token.id;
      }
    }
    if (bestId < 0) continue;
    data.fill(Number.NEGATIVE_INFINITY, offset, offset + vocab);
    data[offset + bestId] = 0;
    chosen ??= bestId;
  }
  return chosen;
}
