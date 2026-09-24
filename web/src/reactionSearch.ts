import { ALL_REACTIONS } from "./reactions";

/*
 * Fuzzy search over the reaction set: what the expanded picker's search box runs. Every emoji
 * has its Unicode name and the words people actually type for it ("lol", "thanks", "wow"). A
 * query matches a keyword by prefix, word prefix, substring, subsequence ("hndrd" → hundred) or
 * a typo or two ("thnaks" → thanks); several words all have to match. The same table and rules
 * live in `ios/shroud/Services/Messaging/ReactionSearch.swift`, and `reactionSearch.selftest.ts`
 * holds the vectors both are checked against.
 */

/** The words each emoji answers to: its Unicode name first, then what people type for it. */
export const REACTION_KEYWORDS: Record<string, string[]> = {
  "❤️": ["red heart", "heart", "love", "like"],
  "🔥": ["fire", "lit", "hot", "flame"],
  "👍": ["thumbs up", "like", "yes", "ok", "approve", "agree", "good"],
  "😢": ["crying face", "sad", "tear", "cry"],
  "🙏": ["folded hands", "please", "thanks", "thank you", "pray", "high five"],
  "😮": ["face with open mouth", "wow", "surprised", "shocked", "omg"],
  "👎": ["thumbs down", "dislike", "no", "disagree", "bad"],
  "🥰": ["smiling face with hearts", "adore", "love", "in love", "crush"],
  "👏": ["clapping hands", "applause", "bravo", "clap", "well done"],
  "😁": ["beaming face with smiling eyes", "grin", "happy", "smile", "teeth"],
  "🤔": ["thinking face", "hmm", "think", "wonder"],
  "🤯": ["exploding head", "mind blown", "wow"],
  "😱": ["face screaming in fear", "scream", "shocked", "horror"],
  "🤬": ["face with symbols on mouth", "cursing", "swearing", "angry", "rage"],
  "🎉": ["party popper", "celebrate", "congratulations", "tada", "party"],
  "🤩": ["star-struck", "starstruck", "excited", "amazing", "wow"],
  "🤮": ["face vomiting", "vomit", "puke", "sick", "gross"],
  "💩": ["pile of poo", "poop", "shit", "crap"],
  "👌": ["ok hand", "okay", "perfect", "nice"],
  "🕊️": ["dove", "peace", "bird"],
  "🤡": ["clown face", "clown", "joke", "fool"],
  "🥱": ["yawning face", "yawn", "bored", "tired", "sleepy"],
  "🥴": ["woozy face", "drunk", "dizzy", "tipsy"],
  "😍": ["smiling face with heart-eyes", "heart eyes", "love", "adore"],
  "🐳": ["spouting whale", "whale", "sea"],
  "❤️‍🔥": ["heart on fire", "burning heart", "passion", "love"],
  "🌚": ["new moon face", "moon", "dark", "creepy"],
  "🌭": ["hot dog", "sausage", "food"],
  "💯": ["hundred points", "100", "perfect score", "keep it 100"],
  "🤣": ["rolling on the floor laughing", "rofl", "lol", "laugh", "haha", "hilarious"],
  "⚡": ["high voltage", "lightning", "zap", "electric", "thunder"],
  "🍌": ["banana", "fruit"],
  "🏆": ["trophy", "winner", "champion", "award", "cup"],
  "💔": ["broken heart", "heartbreak", "sad"],
  "🤨": ["face with raised eyebrow", "suspicious", "skeptical", "doubt", "hmm"],
  "😐": ["neutral face", "meh", "blank", "straight face"],
  "🍓": ["strawberry", "fruit", "berry"],
  "🍾": ["bottle with popping cork", "champagne", "celebrate", "cheers"],
  "💋": ["kiss mark", "kiss", "lips"],
  "🖕": ["middle finger", "fuck you", "flip off", "rude"],
  "😈": ["smiling face with horns", "devil", "evil", "naughty"],
  "😴": ["sleeping face", "sleep", "zzz", "tired", "snore"],
  "😭": ["loudly crying face", "sob", "cry", "bawling", "sad", "tears"],
  "🤓": ["nerd face", "nerd", "geek", "glasses"],
  "👻": ["ghost", "boo", "spooky", "halloween"],
  "👨‍💻": ["man technologist", "coder", "developer", "programmer", "hacker", "computer"],
  "👀": ["eyes", "look", "watching", "see", "side eye"],
  "🎃": ["jack-o-lantern", "pumpkin", "halloween"],
  "🙈": ["see-no-evil monkey", "monkey", "hide", "embarrassed", "cover eyes"],
  "😇": ["smiling face with halo", "angel", "innocent", "holy"],
  "😨": ["fearful face", "scared", "afraid", "fear", "anxious"],
  "🤝": ["handshake", "deal", "agreement", "thanks", "partners"],
  "✍️": ["writing hand", "write", "note", "pen"],
  "🤗": ["smiling face with open hands", "hug", "warm"],
  "🫡": ["saluting face", "salute", "yes sir", "respect", "aye"],
  "🎅": ["santa claus", "christmas", "xmas"],
  "🎄": ["christmas tree", "xmas", "holiday"],
  "☃️": ["snowman", "winter", "snow", "cold"],
  "💅": ["nail polish", "nails", "sassy", "slay", "manicure"],
  "🤪": ["zany face", "crazy", "goofy", "silly", "wild"],
  "🗿": ["moai", "stone face", "statue", "deadpan", "easter island"],
  "🆒": ["cool button", "cool"],
  "💘": ["heart with arrow", "cupid", "love", "crush"],
  "🙉": ["hear-no-evil monkey", "monkey", "ears", "not listening"],
  "🦄": ["unicorn", "magic", "fantasy"],
  "😘": ["face blowing a kiss", "kiss", "love", "xoxo"],
  "💊": ["pill", "medicine", "drug", "capsule"],
  "🙊": ["speak-no-evil monkey", "monkey", "oops", "secret", "quiet"],
  "😎": ["smiling face with sunglasses", "cool", "sunglasses", "chill"],
  "👾": ["alien monster", "space invader", "game", "retro"],
  "🤷": ["person shrugging", "shrug", "dunno", "whatever", "idk"],
  "😡": ["enraged face", "angry", "mad", "furious", "red"],
};

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
  if (word.length >= 3 && parts.some((part) => abbreviates(word, part))) return 50;
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
