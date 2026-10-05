import {
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
} from "./language";

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

resetMemory();
console.log("language selftest ok");
