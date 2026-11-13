import {
  cleanedTranscript,
  detectionCandidates,
  deviceLanguages,
  history,
  languageForRegion,
  learningWeight,
  normalize,
  record,
  resetMemory,
  setCurrentLocale,
  setPreferredLanguageTags,
  WHISPER_CODES,
} from "./language";

/*
 * The language memory itself, on its in-memory store. With `localStorage` (`npm test` gives
 * every selftest one) it is sealed in the vault, which a locked vault refuses; the vault has its
 * own selftest.
 */
Object.defineProperty(globalThis, "localStorage", { value: undefined, configurable: true });

function check(cond: boolean, message: string): void {
  if (!cond) throw new Error(message);
}

resetMemory();
setPreferredLanguageTags(["en-DE"]);
setCurrentLocale("en-DE");
check(deviceLanguages().join() === "en,de", "en-DE counts English and German");
check(detectionCandidates(["de"]).join() === "de,en", "detection weighs the device's languages and English");
check(detectionCandidates(["en", "de"]).join() === "en,de", "English is not listed twice");
check(detectionCandidates([]).length === 0, "no languages, no preference");
check(normalize("german") === "de", "normalize german");
check(languageForRegion("DE") === "de", "region DE");

const peer = "11111111-1111-4111-8111-111111111111";
for (let i = 0; i < 4; i++) record("tr", peer, 1);
check(deviceLanguages().join() === "en,de", "a chat's memory is not a device language");
check(Object.keys(history(peer)).join() === "tr", "the chat's history holds what it heard");
check(Object.keys(history("22222222-2222-4222-8222-222222222222")).join() === "tr", "a new chat starts from the overall habit");

resetMemory();
for (let i = 0; i < 5; i++) record("de", peer, 1);
for (let i = 0; i < 6; i++) record("en", peer, 1);
const changed = history(peer);
check((changed.en ?? 0) > (changed.de ?? 0), "decay lets a chat change language");

// Only what the audio settled teaches the history.
check(learningWeight(10, 0.5) === 0, "an unsure note teaches nothing");
check(learningWeight(10, 0.3) === 0, "a note the history carried teaches nothing");
check(learningWeight(10, Number.NaN) === 0, "no probability, no lesson");
check(learningWeight(10, 0.95) === 1, "a clear note teaches fully");
check(learningWeight(2, 0.95) < learningWeight(8, 0.95), "short notes teach less");

// A transcript in any script Whisper writes is kept, not only Latin, Cyrillic, Arabic and CJK.
const transcripts: Record<string, string> = {
  ko: "내일 아침에 전화할게요",
  el: "Θα σε πάρω τηλέφωνο αύριο",
  he: "אני אתקשר אליך מחר",
  hi: "मैं कल तुम्हें फ़ोन करूँगा",
  th: "พรุ่งนี้ฉันจะโทรหาคุณ",
};
for (const [code, text] of Object.entries(transcripts)) {
  check(cleanedTranscript(text) === text, `a ${code} transcript is kept`);
}
check(cleanedTranscript("हाँ") === "हाँ", "a one-word Hindi reply counts its marks, as on iOS");
check(cleanedTranscript("जी") === "जी", "a base letter and its vowel sign are two letters");
check(cleanedTranscript("  내일   전화할게요 \n") === "내일 전화할게요", "whitespace is collapsed");
check(cleanedTranscript("... ?! ♪") === "", "punctuation-only decoder output is dropped");
check(cleanedTranscript("") === "", "an empty transcript is dropped");

// Whisper reports a language by name or code; every picker language's name maps to its code.
const names: Record<string, string> = {
  english: "en", german: "de", spanish: "es", french: "fr", italian: "it",
  portuguese: "pt", dutch: "nl", polish: "pl", russian: "ru", ukrainian: "uk",
  turkish: "tr", arabic: "ar", hindi: "hi", japanese: "ja", korean: "ko",
  chinese: "zh", swedish: "sv", danish: "da", norwegian: "nb", finnish: "fi",
  czech: "cs", greek: "el", hebrew: "he", indonesian: "id", thai: "th",
  vietnamese: "vi", romanian: "ro", hungarian: "hu", catalan: "ca", croatian: "hr",
};
check(
  [...WHISPER_CODES].sort().join() === Object.values(names).sort().join(),
  "the table names every picker language",
);
for (const [name, code] of Object.entries(names)) {
  check(normalize(name) === code, `normalize ${name} is ${code}, got ${normalize(name)}`);
}

resetMemory();
console.log("language selftest ok");
