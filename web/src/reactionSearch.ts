import { ALL_REACTIONS, REACTION_KEYWORDS } from "./reactionSet";

export { REACTION_KEYWORDS };

/*
 * Fuzzy search over the reaction set: what the expanded picker's search box runs. Every emoji
 * has its name and the words people actually type for it ("lol", "thanks", "wow") in
 * `reactionSet.ts`. A query matches a keyword by prefix, word prefix, substring, abbreviation
 * ("hndrd" → hundred) or a typo or two ("thnaks" → thanks); several words all have to match.
 * The same rules live in `ios/shroud/Services/Messaging/ReactionSearch.swift`, and
 * `reactionSearch.selftest.ts` holds the vectors both are checked against.
 */

/** Lower case, hyphens as spaces, one space between words. */
export function normalizeQuery(query: string): string {
  return query.toLowerCase().replace(/[-_]+/g, " ").replace(/\s+/g, " ").trim();
}

/* The table with each keyword in query form, so "star-struck" and "star struck" both match. */
const NORMALIZED_KEYWORDS: Record<string, string[]> = Object.fromEntries(
  Object.entries(REACTION_KEYWORDS).map(([emoji, words]) => [emoji, words.map(normalizeQuery)]),
);

/**
 * `word` abbreviates `text`: same first letter, and the rest appear in `text` in order
 * ("hndrd" → hundred). Anchored at the start, or "fire" would find "face with raised eyebrow".
 */
function abbreviates(word: string, text: string): boolean {
  const letters = [...word];
  const chars = [...text];
  if (letters.length === 0 || chars[0] !== letters[0]) return false;
  let at = 1;
  for (const char of chars.slice(1)) {
    if (at < letters.length && char === letters[at]) at += 1;
  }
  return at === letters.length;
}

/** Damerau–Levenshtein (adjacent swaps count one), capped: returns `limit + 1` past `limit`. */
export function editDistance(a: string, b: string, limit: number): number {
  const x = [...a];
  const y = [...b];
  if (Math.abs(x.length - y.length) > limit) return limit + 1;
  let previous2: number[] = [];
  let previous = Array.from({ length: y.length + 1 }, (_, j) => j);
  for (let i = 1; i <= x.length; i++) {
    const current = [i];
    let rowMin = i;
    for (let j = 1; j <= y.length; j++) {
      const same = x[i - 1] === y[j - 1] ? 0 : 1;
      let cost = Math.min(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + same);
      if (i > 1 && j > 1 && x[i - 1] === y[j - 2] && x[i - 2] === y[j - 1]) {
        cost = Math.min(cost, previous2[j - 2] + 1);
      }
      current.push(cost);
      rowMin = Math.min(rowMin, cost);
    }
    if (rowMin > limit) return limit + 1;
    previous2 = previous;
    previous = current;
  }
  return previous[y.length];
}

/** How many typos a query word may have: none under five letters, two from eight. */
function typoBudget(length: number): number {
  if (length < 5) return 0;
  return length >= 8 ? 2 : 1;
}

/** How well one word of a query matches one keyword; 0 is no match, 100 the keyword itself. */
export function keywordScore(word: string, keyword: string): number {
  if (keyword === word) return 100;
  if (keyword.startsWith(word)) return 90;
  const parts = keyword.split(" ");
  if (parts.some((part) => part.startsWith(word))) return 80;
  if (word.length >= 2 && keyword.includes(word)) return 70;
  // From four letters: "cat" abbreviating "celebrate" and "caution" was noise, "hndrd" is not.
  if (word.length >= 4 && parts.some((part) => abbreviates(word, part))) return 50;
  const budget = typoBudget(word.length);
  if (budget === 0) return 0;
  let best = budget + 1;
  for (const candidate of [keyword, ...parts]) {
    best = Math.min(best, editDistance(word, candidate, budget));
    // A typo in the start of a longer word: "thnaks" against "thanks" from "thank you".
    if (candidate.length > word.length) {
      best = Math.min(best, editDistance(word, [...candidate].slice(0, word.length).join(""), budget) + 0.5);
    }
  }
  return best <= budget ? 40 - best * 10 : 0;
}

/** How well the whole query matches `emoji`: every word has to match, the weakest one counts. */
export function reactionScore(query: string, emoji: string): number {
  const normalized = normalizeQuery(query);
  if (!normalized) return 0;
  if (normalized === emoji) return 100;
  const keywords = NORMALIZED_KEYWORDS[emoji] ?? [];
  let weakest = Infinity;
  for (const word of normalized.split(" ")) {
    const score = keywords.reduce((best, keyword) => Math.max(best, keywordScore(word, keyword)), 0);
    if (score === 0) return 0;
    weakest = Math.min(weakest, score);
  }
  return weakest === Infinity ? 0 : weakest;
}

/**
 * The emoji of `list` that match `query`, best first (ties keep the list's order). An empty
 * query is the whole list as it is.
 */
export function searchReactions(query: string, list: readonly string[] = ALL_REACTIONS): string[] {
  if (!normalizeQuery(query)) return [...list];
  return list
    .map((emoji, index) => ({ emoji, index, score: reactionScore(query, emoji) }))
    .filter((entry) => entry.score > 0)
    .sort((a, b) => b.score - a.score || a.index - b.index)
    .map((entry) => entry.emoji);
}
