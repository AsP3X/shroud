import {
  challenger,
  choose,
  decodeHints,
  detectionCandidates,
  languageForRegion,
  languageProbability,
  normalize,
  prior,
  record,
  resetMemory,
  setCurrentLocale,
  setPreferredLanguageTags,
  shouldForceLanguage,
  TRUSTED_PRIOR,
} from "./language";

resetMemory();
setPreferredLanguageTags(["en-DE"]);
setCurrentLocale("en-DE");
const hints = decodeHints(null);
if (!hints.includes("de")) throw new Error("en-DE must hint German");
if (challenger("en", hints) !== "de") throw new Error("English auto-detect must be challenged with German");
if (challenger(null, ["en", "de"]) !== "de") throw new Error("unknown detection must skip a wasted English pass");
if (challenger("de", ["de", "en"]) !== null) throw new Error("matching German detection must not spend a second pass");
if (challenger("fr", ["en", "de"]) !== null) throw new Error("a detected language must not be replaced by the region");
if (detectionCandidates(["de"]).join() !== "de,en") throw new Error("detection prefers the hints and English");
if (detectionCandidates(["en", "de"]).join() !== "en,de") throw new Error("English is not listed twice");
if (detectionCandidates([]).length !== 0) throw new Error("no hints, no preference");
if (normalize("german") !== "de") throw new Error("normalize german");
if (languageForRegion("DE") !== "de") throw new Error("region DE");

const deOnGerman = languageProbability(
  "de",
  "Guten Morgen, ich wollte dir nur schnell Bescheid geben dass es später wird",
);
const enOnGerman = languageProbability(
  "en",
  "Guten Morgen, ich wollte dir nur schnell Bescheid geben dass es später wird",
);
if (deOnGerman <= enOnGerman) throw new Error("German text should score as German");

const chosen = choose(
  { text: "House goes to the deer tonight", language: "en", confidence: 0.72 },
  { text: "Haus, ich gehe später noch zu dir", language: "de", confidence: 0.68 },
  null,
  6,
);
if (chosen.language !== "de") throw new Error("challenger must win against English-biased auto-detect");

resetMemory();
const peer = "11111111-1111-4111-8111-111111111111";
for (let i = 0; i < 4; i++) record("de", peer, 1);
setPreferredLanguageTags(["en-US"]);
setCurrentLocale("en-US");
if (decodeHints(peer)[0] !== "de") throw new Error("conversation memory must outrank the UI language");

resetMemory();
if (shouldForceLanguage("en", 0.99)) throw new Error("must not force English from memory");
if (!shouldForceLanguage("de", 0.8)) throw new Error("must force German once memory is trusted");
if (shouldForceLanguage("de", 0.5)) throw new Error("must not force German from a weak prior");
for (let i = 0; i < 5; i++) record("en", peer, 1);
if (prior("en", peer) < TRUSTED_PRIOR) throw new Error("english memory should saturate");
if (shouldForceLanguage("en", prior("en", peer))) throw new Error("saturated english must still allow a German challenger");
setPreferredLanguageTags(["en-DE"]);
setCurrentLocale("en-DE");
if (challenger("en", decodeHints(peer)) !== "de") throw new Error("poisoned english memory must still challenge with German");

resetMemory();
console.log("language selftest ok");
