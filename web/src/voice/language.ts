/*
 * Language hints for Whisper. Same contract as iOS `TranscriptionLanguage` /
 * `VoiceTranscript`: auto-detect is English-biased, so we challenge it with
 * conversation history and the device region (English UI in Germany is common)
 * and keep the better transcript. A detection that is already another language
 * is kept; only an English result is challenged.
 */

import { vaultGet, vaultSet } from "../crypto/vault";
import { storageSealed } from "../storageSeal";

export const STATS_KEY = "transcription.languageStats";
const OVERRIDE_KEY = "transcription.locale";
const GLOBAL_SCOPE = "*";
const DECAY = 0.9;
const SATURATION = 3;
export const TRUSTED_PRIOR = 0.75;

/** English is Whisper's default; forcing it from a poisoned memory would hide German forever. */
export function shouldForceLanguage(code: string, languagePrior: number): boolean {
  const normalized = normalize(code);
  if (!WHISPER_SET.has(normalized) || normalized === "en") return false;
  return languagePrior >= TRUSTED_PRIOR;
}
export const ENGLISH_CHALLENGE_MARGIN = 1.2;
export const MINIMUM_TRUSTED_SCORE = 0.12;

export const WHISPER_CODES = [
  "en", "de", "es", "fr", "it", "pt", "nl", "pl", "ru", "uk",
  "tr", "ar", "hi", "ja", "ko", "zh", "sv", "da", "nb", "fi",
  "cs", "el", "he", "id", "th", "vi", "ro", "hu", "ca", "hr",
] as const;

const WHISPER_SET = new Set<string>(WHISPER_CODES);

const LANGUAGE_NAMES: Record<string, string> = {
  german: "de", english: "en", spanish: "es", french: "fr", italian: "it",
  portuguese: "pt", dutch: "nl", polish: "pl", russian: "ru", ukrainian: "uk",
  turkish: "tr", arabic: "ar", hindi: "hi", japanese: "ja", korean: "ko",
  chinese: "zh", swedish: "sv", danish: "da", norwegian: "nb", finnish: "fi",
};

const REGION_LANGUAGE: Record<string, string> = {
  DE: "de", AT: "de", LI: "de",
  FR: "fr", MC: "fr",
  ES: "es", MX: "es", AR: "es", CO: "es", CL: "es", PE: "es",
  IT: "it", NL: "nl", PL: "pl", PT: "pt", BR: "pt",
  RU: "ru", UA: "uk", TR: "tr", JP: "ja", KR: "ko",
  CN: "zh", TW: "zh", SE: "sv", DK: "da", NO: "nb", FI: "fi",
};

const STOPWORDS: Record<string, string[]> = {
  de: ["und", "ich", "nicht", "das", "die", "der", "ist", "ein", "zu", "den", "mit", "auf", "für", "es", "auch", "wie", "dass", "sich", "von", "dem"],
  en: ["the", "and", "you", "that", "was", "for", "are", "with", "this", "have", "not", "but", "they", "from", "what", "your"],
  fr: ["je", "les", "une", "des", "que", "est", "pas", "le", "la", "et", "dans", "pour"],
  es: ["que", "los", "las", "una", "por", "con", "para", "está", "como"],
  it: ["che", "non", "una", "per", "con", "come", "sono"],
  nl: ["het", "van", "een", "dat", "niet", "voor", "met"],
};

/** Test seams. */
let preferredLanguageTagsOverride: string[] | null = null;
let currentLocaleOverride: string | null = null;

export function setPreferredLanguageTags(tags: string[] | null): void {
  preferredLanguageTagsOverride = tags;
}

export function setCurrentLocale(tag: string | null): void {
  currentLocaleOverride = tag;
}

const memoryFallback = new Map<string, string>();

function hasLocalStorage(): boolean {
  try {
    return typeof localStorage !== "undefined";
  } catch {
    return false;
  }
}

function storage(): { getItem(k: string): string | null; setItem(k: string, v: string): void; removeItem(k: string): void } {
  try {
    if (typeof localStorage !== "undefined") return localStorage;
  } catch {
    /* private mode */
  }
  return {
    getItem: (k) => memoryFallback.get(k) ?? null,
    setItem: (k, v) => {
      memoryFallback.set(k, v);
    },
    removeItem: (k) => {
      memoryFallback.delete(k);
    },
  };
}

export function normalize(code: string): string {
  const raw = code.trim().toLowerCase();
  if (!raw) return "";
  if (raw.length === 2 && WHISPER_SET.has(raw)) return raw;
  const named = LANGUAGE_NAMES[raw];
  if (named) return named;
  const prefix = raw.slice(0, 2);
  return WHISPER_SET.has(prefix) ? prefix : raw;
}

export function languageForRegion(region: string): string | undefined {
  return REGION_LANGUAGE[region.toUpperCase()];
}

function preferredLanguageTags(): string[] {
  if (preferredLanguageTagsOverride) return preferredLanguageTagsOverride;
  if (typeof navigator === "undefined") return [];
  const tags = navigator.languages?.length ? [...navigator.languages] : [];
  if (navigator.language && !tags.includes(navigator.language)) tags.push(navigator.language);
  return tags;
}

function currentLocaleTag(): string {
  if (currentLocaleOverride) return currentLocaleOverride;
  return preferredLanguageTags()[0] ?? (typeof navigator !== "undefined" ? navigator.language : "en");
}

function regionHints(): string[] {
  const tags = [...preferredLanguageTags(), currentLocaleTag()];
  const codes: string[] = [];
  for (const tag of tags) {
    try {
      const region = new Intl.Locale(tag).region;
      if (!region) continue;
      const language = languageForRegion(region);
      if (language && !codes.includes(language)) codes.push(language);
    } catch {
      const parts = tag.replace("_", "-").split("-");
      const region = parts.length >= 2 ? parts[parts.length - 1] : "";
      const language = languageForRegion(region);
      if (language && !codes.includes(language)) codes.push(language);
    }
  }
  return codes;
}

export function override(): string | null {
  const raw = storage().getItem(OVERRIDE_KEY);
  if (!raw) return null;
  const code = normalize(raw);
  return WHISPER_SET.has(code) ? code : null;
}

export function decodeHints(peerId?: string | null): string[] {
  const pinned = override();
  if (pinned) return [pinned];
  const ordered: string[] = [];
  const add = (raw: string | null | undefined) => {
    if (!raw) return;
    const code = normalize(raw);
    if (!WHISPER_SET.has(code) || ordered.includes(code)) return;
    ordered.push(code);
  };
  add(expectedLanguage(peerId));
  if (peerId) add(expectedLanguage(null));
  for (const tag of preferredLanguageTags()) {
    try {
      add(new Intl.Locale(tag).language);
    } catch {
      add(tag.split(/[-_]/)[0]);
    }
  }
  for (const code of regionHints()) add(code);
  return ordered;
}

export function challenger(detected: string | null | undefined, hints: string[]): string | null {
  const got = detected ? normalize(detected) : "";
  // Only English, or a detection that failed, gets a second pass. A French or
  // German result is the language of the note. English is the biased default,
  // so a hint may challenge that and nothing else. The hint is never English.
  if (got && got !== "en") return null;
  for (const hint of hints) {
    const code = normalize(hint);
    if (!code || code === got || code === "en") continue;
    return code;
  }
  return null;
}

type Stats = Record<string, Record<string, number>>;

function loadStats(): Stats {
  try {
    const raw = hasLocalStorage() ? vaultGet(STATS_KEY) : storage().getItem(STATS_KEY);
    if (!raw) return {};
    const parsed = JSON.parse(raw) as unknown;
    if (!parsed || typeof parsed !== "object") return {};
    return parsed as Stats;
  } catch {
    return {};
  }
}

function saveStats(stats: Stats): void {
  // Which languages someone speaks, per chat — account data like the messages themselves.
  // Sealed in the vault like the messages; locked, nothing is recorded.
  if (storageSealed()) return;
  if (hasLocalStorage()) vaultSet(STATS_KEY, JSON.stringify(stats));
  else storage().setItem(STATS_KEY, JSON.stringify(stats));
}

export function prior(languageCode: string, peerId?: string | null): number {
  const all = loadStats();
  const peer = peerId ? (all[peerId] ?? {}) : {};
  const global = all[GLOBAL_SCOPE] ?? {};
  const peerTotal = Object.values(peer).reduce((a, b) => a + b, 0);
  const globalTotal = Object.values(global).reduce((a, b) => a + b, 0);
  if (peerTotal + globalTotal <= 0) return 0.5;
  const share = (counts: Record<string, number>, total: number) =>
    total > 0 ? (counts[languageCode] ?? 0) / total : 0.5;
  const combined =
    peerTotal > 0 ? 0.75 * share(peer, peerTotal) + 0.25 * share(global, globalTotal) : share(global, globalTotal);
  const evidenceTotal = peerTotal > 0 ? peerTotal : globalTotal;
  const evidence = Math.min(1, evidenceTotal / SATURATION);
  return 0.5 + (combined - 0.5) * evidence;
}

export function expectedLanguage(peerId?: string | null): string | null {
  const all = loadStats();
  const counts = (peerId && all[peerId]) || all[GLOBAL_SCOPE] || {};
  const total = Object.values(counts).reduce((a, b) => a + b, 0);
  if (total < 1) return null;
  let best: string | null = null;
  let bestWeight = 0;
  for (const [code, weight] of Object.entries(counts)) {
    if (weight > bestWeight) {
      best = code;
      bestWeight = weight;
    }
  }
  return best;
}

export function record(languageCode: string, peerId: string | null | undefined, weight: number): void {
  if (weight <= 0) return;
  const all = loadStats();
  const scopes = [peerId, GLOBAL_SCOPE].filter((s): s is string => Boolean(s));
  for (const scope of scopes) {
    const counts = { ...(all[scope] ?? {}) };
    for (const key of Object.keys(counts)) counts[key] *= DECAY;
    counts[languageCode] = (counts[languageCode] ?? 0) + weight;
    all[scope] = Object.fromEntries(Object.entries(counts).filter(([, v]) => v >= 0.05));
  }
  saveStats(all);
}

export function resetMemory(): void {
  storage().removeItem(STATS_KEY);
  setPreferredLanguageTags(null);
  setCurrentLocale(null);
}

export function letterCount(text: string): number {
  let n = 0;
  for (const ch of text) {
    if (/\p{L}/u.test(ch)) n += 1;
  }
  return n;
}

export function cleaned(text: string): string {
  const trimmed = text.replace(/\s+/g, " ").trim();
  if (letterCount(trimmed) < 2) return "";
  return trimmed;
}

export function languageProbability(code: string, text: string): number {
  const language = normalize(code);
  const letters = letterCount(text);
  if (!language || letters < 8) return 0.5;
  const tokens = text.toLowerCase().split(/[^\p{L}]+/u).filter(Boolean);
  const stops = STOPWORDS[language] ?? [];
  let hits = 0;
  for (const token of tokens) if (stops.includes(token)) hits += 1;
  const stopScore = tokens.length ? hits / Math.min(tokens.length, 20) : 0;
  let script = 0;
  if (language === "de" && /[äöüßÄÖÜ]/.test(text)) script = 0.3;
  if (language === "en" && /[äöüßÄÖÜ]/.test(text)) return Math.min(0.25, stopScore);
  return Math.min(1, Math.max(0.05, 0.15 + stopScore * 1.6 + script));
}

export function score(opts: {
  text: string;
  modelConfidence: number;
  languageProbability?: number;
  prior?: number;
  audioSeconds?: number;
}): number {
  if (letterCount(opts.text) < 2) return 0;
  const letters = letterCount(opts.text);
  const substance = Math.min(1, letters / 12);
  const textTrust = Math.min(1, letters / 40);
  const languageTerm = 0.5 + ((opts.languageProbability ?? 0.5) - 0.5) * textTrust;
  const audioSeconds = opts.audioSeconds ?? Number.POSITIVE_INFINITY;
  const priorTrust = 1 - Math.min(1, Math.max(0, audioSeconds - 2) / 6);
  const priorTerm = 0.5 + ((opts.prior ?? 0.5) - 0.5) * priorTrust;
  return opts.modelConfidence * substance * (0.5 + languageTerm) * (0.5 + priorTerm);
}

export type Candidate = { text: string; language: string | null; confidence: number };

export function choose(
  auto: Candidate,
  challenge: Candidate | null,
  peerId: string | null | undefined,
  audioSeconds: number,
): Candidate {
  const autoClean: Candidate = {
    text: cleaned(auto.text),
    language: auto.language ? normalize(auto.language) : null,
    confidence: auto.confidence,
  };
  const autoScore = scoreCandidate(autoClean, peerId, audioSeconds);
  if (!challenge) return autoClean;
  const alt: Candidate = {
    text: cleaned(challenge.text),
    language: challenge.language ? normalize(challenge.language) : null,
    confidence: challenge.confidence,
  };
  const altScore = scoreCandidate(alt, peerId, audioSeconds);
  let autoEffective = autoScore;
  const autoLooksEnglish = autoClean.language == null || autoClean.language === "en";
  if (autoLooksEnglish && alt.language !== "en") {
    autoEffective = autoScore / ENGLISH_CHALLENGE_MARGIN;
  }
  return altScore > autoEffective ? alt : autoClean;
}

function scoreCandidate(candidate: Candidate, peerId: string | null | undefined, audioSeconds: number): number {
  const language = candidate.language ?? "";
  return score({
    text: candidate.text,
    modelConfidence: candidate.confidence,
    languageProbability: languageProbability(language, candidate.text),
    prior: language ? prior(language, peerId) : 0.5,
    audioSeconds,
  });
}

export function learningWeight(audioSeconds: number, scored: number): number {
  if (scored < MINIMUM_TRUSTED_SCORE) return 0;
  const duration = Math.min(1, audioSeconds / 8);
  const strength = Math.min(1, scored / 0.4);
  return duration * strength;
}
