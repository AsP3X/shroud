/**
 * Language-token scoring. Run: npx tsx src/voice/transcription/detect.selftest.ts
 */
import {
  languageFromTokenId,
  languageProbabilities,
  languageTokensFromMap,
  lastGeneratedId,
  maskToLanguage,
  OUTSIDE_CANDIDATE_ODDS,
  pickSpokenLanguage,
} from "./detect";

function check(cond: boolean, message: string): void {
  if (!cond) throw new Error(message);
}

const tokens = languageTokensFromMap({
  "<|en|>": 50259,
  "<|de|>": 50261,
  "<|fr|>": 50265,
  "<|transcribe|>": 50359,
  "<|0.00|>": 50364,
  nope: 1.5,
});
check(tokens.length === 3, "only two-letter language tokens are candidates");
check(languageFromTokenId(50265, tokens) === "fr", "a french token id maps back to fr");
check(languageFromTokenId(50359, tokens) === null, "the transcribe token is not a language");
check(lastGeneratedId([[50258n, 50261n]]) === 50261, "the generated language id is the last token");
check(lastGeneratedId([50261]) === null, "a flat id list is not a batch");

const vocab = 8;
const scores = new Float32Array(vocab);
scores[1] = 4; // a text token, louder than every language
scores[3] = 1; // en
scores[5] = 3; // de
const winner = maskToLanguage(scores, vocab, [
  { code: "en", id: 3 },
  { code: "de", id: 5 },
]);
check(winner?.id === 5 && winner.code === "de", "german outranks english even when a text token is louder");
check(Math.abs((winner?.probability ?? 0) - 1 / (1 + Math.exp(-2))) < 1e-6, "the pick reports the audio's probability");
check(scores[5] === 0, "the winning language is the only finite score");
check(scores[1] === Number.NEGATIVE_INFINITY, "text tokens are masked");
check(scores[3] === Number.NEGATIVE_INFINITY, "the losing language is masked");

const none = maskToLanguage(new Float32Array(vocab).fill(Number.NEGATIVE_INFINITY), vocab, [
  { code: "en", id: 3 },
]);
check(none === null, "no finite language score is not a detection");

const odds = (entries: [string, number][]) => new Map(entries);
check(
  pickSpokenLanguage(odds([["nl", 0.45], ["de", 0.35], ["en", 0.1], ["af", 0.1]]), ["de", "en"]) === "de",
  "a close neighbour of a candidate does not win",
);
check(
  pickSpokenLanguage(odds([["es", 0.92], ["pt", 0.04], ["de", 0.02], ["en", 0.02]]), ["de", "en"]) === "es",
  "a clear other language still wins",
);
check(
  pickSpokenLanguage(odds([["fr", 0.5], ["de", 0.5 / OUTSIDE_CANDIDATE_ODDS - 0.001]]), ["de"]) === "fr",
  "an outsider wins once it is more than the odds likelier",
);
check(
  pickSpokenLanguage(odds([["nl", 0.6], ["de", 0.3]]), []) === "nl",
  "without candidates the most likely language wins",
);
check(
  pickSpokenLanguage(odds([["da", 0.5], ["no", 0.4]]), ["nb", "en"]) === "no",
  "Norwegian hints (nb) match Whisper's no",
);
check(pickSpokenLanguage(odds([]), ["de"]) === null, "no probabilities, no language");

const logits = new Float32Array([0, 2, 0, 1]);
const probabilities = languageProbabilities(logits, 0, 4, [
  { code: "de", id: 1 },
  { code: "en", id: 3 },
  { code: "xx", id: 9 },
]);
check(probabilities.size === 2, "only tokens inside the vocabulary are scored");
check(Math.abs((probabilities.get("de") ?? 0) - 1 / (1 + Math.exp(-1))) < 1e-9, "scores become softmax probabilities");

const close = new Float32Array(vocab);
close[3] = 2; // en
close[5] = 1; // de: about 2.7 times less likely than English
const restricted = maskToLanguage(close, vocab, [
  { code: "en", id: 3 },
  { code: "de", id: 5 },
  { code: "nl", id: 6 },
], ["de"]);
check(restricted?.id === 5, "a candidate beats a likelier outsider within the odds");

// Same vectors as the iOS and Android tests.
const german = { de: 40 };
const unsure = odds([["en", 0.5], ["de", 0.35], ["nl", 0.15]]);
check(pickSpokenLanguage(unsure, ["en", "de"]) === "en", "without history an unsure note keeps Whisper's pick");
check(pickSpokenLanguage(unsure, ["en", "de"], german) === "de", "a German chat settles an unsure note");
check(
  pickSpokenLanguage(odds([["en", 0.9], ["de", 0.08], ["nl", 0.02]]), ["en", "de"], german) === "en",
  "a clear English note in a German chat stays English",
);
check(
  pickSpokenLanguage(odds([["en", 0.93], ["tr", 0.01], ["de", 0.06]]), ["en", "de"], { tr: 10 }) === "en",
  "a wrong language in the history cannot override clear audio",
);
const leaning = odds([["en", 0.5], ["de", 0.3]]);
check(pickSpokenLanguage(leaning, ["en", "de"], { de: 0.5 }) === "en", "one note of history only nudges");
check(pickSpokenLanguage(leaning, ["en", "de"], { de: 3 }) === "de", "a few notes of history decide an unsure note");

const weighed = new Float32Array(vocab);
weighed[3] = Math.log(0.55); // en
weighed[5] = Math.log(0.4); // de
weighed[6] = Math.log(0.05); // nl
const settled = maskToLanguage(weighed, vocab, [
  { code: "en", id: 3 },
  { code: "de", id: 5 },
  { code: "nl", id: 6 },
], ["en", "de"], { de: 5 });
check(settled?.code === "de", "history picks the language");
check(Math.abs((settled?.probability ?? 0) - 0.4) < 1e-6, "the reported probability is the audio's, before weighing");

console.log("language detect selftest ok");
