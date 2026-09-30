/**
 * Language-token scoring. Run: npx tsx src/voice/transcription/detect.selftest.ts
 */
import {
  languageFromTokenId,
  languageTokensFromMap,
  lastGeneratedId,
  maskToLanguage,
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
check(winner === 5, "german outranks english even when a text token is louder");
check(scores[5] === 0, "the winning language is the only finite score");
check(scores[1] === Number.NEGATIVE_INFINITY, "text tokens are masked");
check(scores[3] === Number.NEGATIVE_INFINITY, "the losing language is masked");

const none = maskToLanguage(new Float32Array(vocab).fill(Number.NEGATIVE_INFINITY), vocab, [
  { code: "en", id: 3 },
]);
check(none === null, "no finite language score is not a detection");

console.log("language detect selftest ok");
