# Voice transcription — improvement plan

**Status:** proposed 2026-10-06. Nothing here is built yet.
**Scope:** voice-note transcription on iOS/iPad (WhisperKit), web (transformers.js) and Android
(whisper.cpp). Live call captions are out of scope.

## Goal

Make transcripts more accurate, faster and consistent across the three clients. A change counts only
if it keeps three things:

- **Accuracy:** no language gets a worse error rate and language detection gets no worse (see the
  gate below).
- **Functionality:** everything a client does today keeps working, and the clients come closer to
  each other.
- **Languages:** all 30 picker languages keep working on every client: en de es fr it pt nl pl ru
  uk tr ar hi ja ko zh sv da nb fi cs el he id th vi ro hu ca hr.

## Rules every task keeps

| # | Rule | Source |
| - | ---- | ------ |
| R1 | Whisper detects the language once per note, and the whole note is decoded in it. Never add a pass that forces a language Whisper didn't pick. | `docs/architecture.md:284`, English → Turkish bug |
| R2 | The language history learns only from what the audio itself settled (`learningWeight`). A pick that the history carried never teaches the history. | same |
| R3 | Audio and transcripts stay on the device. Only model weights are downloaded. Anything stored is sealed. | `docs/architecture.md:284`, no-plaintext rule |
| R4 | No deprecated APIs. | `docs/agent-rules/no-deprecated-apis.md` |
| R5 | Every visible UI change also lands in the matching `design/*.pen` file. | `docs/agent-rules/design-sync.md` |
| R6 | Work in worktrees based on an up-to-date `dev`, and catch up again before testing and merging. | `docs/agent-rules/worktrees-from-dev.md` |
| R7 | A task marked **[gated]** merges only after it passes the quality gate (G) against the baseline. | this plan |

## Quality gate (G)

Every **[gated]** task runs the evaluation from phase 0 on the client it changes, compares the
result with the stored baseline, and attaches the comparison to its merge.

| Metric | Measured on | Passes when |
| ------ | ----------- | ----------- |
| Error rate per language: word error rate, or character error rate for zh, ja, ko and th | FLEURS test set, 50 clips per language × 30 languages | No language is worse with 95 % confidence (paired bootstrap, 1,000 resamples), and the error rate over all clips is not higher |
| Language detection accuracy, from 2 s of speech and from the whole clip | MINDS-14 (14 languages) and FLEURS | Accuracy over all clips does not drop. No language drops by more than 2 points. |
| Made-up text on silence | 60 clips of room tone, fan noise, music and silence (Non-speech set) | Number of clips that produce text ≤ baseline (target: 0) |
| Made-up text after the last word | 40 text-to-speech sentences (macOS `say`) followed by 1–3 s of pink noise (Tail set) | Words after the voice ends ≤ baseline (target: 0) |
| Speed (real-time factor, RTF = processing time ÷ audio length) and peak memory | Same clips, on the reference device of each class (T0.6) | Within the budget of that device class |

Text is normalised before scoring the way Whisper's own evaluation does it: `EnglishTextNormalizer`
for English, `BasicTextNormalizer` for every other language.

---

## Phase 0 — Measure first

Phase 0 blocks every **[gated]** task. It can run alongside phase 1's bug fixes (T1.1, T1.2, T1.7).

| ID | Todo | Order | Done when |
| -- | ---- | ----- | --------- |
| T0.1 | **Decide where the harness lives.** The 2026-10-05 harness was in a session scratchpad that no longer exists. Proposal: `tools/transcription-eval/` in the repo, holding code, the clip lists and their checksums. Audio is never committed; the harness downloads it. | first | User decision recorded |
| T0.2 | **Corpus fetcher.** <br>• FLEURS: download the parquet files directly from `google/fleurs`, because the datasets-server `first-rows` request fails. Take 50 test clips for each of the 30 languages; Chinese is `cmn_hans_cn`. <br>• MINDS-14: use datasets-server with 100 rows per page and a pause between files, because it answers 429 quickly. Cut each clip from the speech onset (RMS > 0.02). <br>• Build the Non-speech set and the Tail set. <br>• Write `manifest.json` with each clip's id, language, reference text and SHA-256. | after T0.1 | The fetcher runs again from scratch and reproduces the same manifest |
| T0.3 | **One runner per client.** Each writes one JSONL line per clip: `{id, lang, ref, hyp, detected, probs, seconds, rtf}`. <br>• **Android:** CMake tool over `android/app/src/main/cpp/whisper.cpp`. Build ggml statically, with Metal off and `GGML_CCACHE OFF`. It calls the same parameters as `whisper_jni.cpp`. Models are the pinned files from `WhisperModelStore`. <br>• **iOS:** SwiftPM command-line tool depending on the WhisperKit checkout by `path:`. It uses the real `WhisperKitEngine.swift`, `TranscriptionTypes.swift` and `TranscriptionEngine.swift` through symlinks. <br>• **Web:** node + tsx script (`.mts`) importing `web/src/voice/transcription/*` with the same `pipeline(...)` options as `whisper-worker.ts`. | parallel ×3, after T0.2 | Each runner reproduces the app's transcript for 5 clips recorded on a real device |
| T0.4 | **Scorer.** `score.py` computes everything in gate G from the JSONL files, with the bootstrap test, and writes a markdown table that compares two runs. | parallel with T0.3 | Comparing a run with itself shows zero difference |
| T0.5 | **Baseline.** Run the current `dev` on all three clients with each client's shipped model (iOS small, web base, Android base). Also run iOS base and Android small, so phase 3 has numbers for every size. Store the results in `tools/transcription-eval/baseline/` (results only, no audio). | after T0.3 + T0.4 | Baseline table committed |
| T0.6 | **Reference devices and speed budgets.** Agree one device per class and the time a note may take. <br>• iOS: an A15-class iPhone, an A17 Pro-or-newer iPhone, an M-series iPad. <br>• Android: the Pixel 10 Pro XL and one mid-range phone with 6 GB or less RAM. <br>• Web: Chrome and Safari on this Mac. <br>Proposed budget: a 60 s note is transcribed in ≤ 10 s on the slowest device of each class. | parallel with T0.2 | Device list and budgets recorded. The phone measurements need the user. |

---

## Phase 1 — Bug fixes and filling gaps (no model change)

Phase 1 has four parallel lanes: **A** web, **B** Android, **C** iOS, **D** shared. Within a lane,
tasks go top to bottom.

### Lane A — Web

| ID | Todo | Order | Done when |
| -- | ---- | ----- | --------- |
| T1.1 | **Stop dropping five languages.** `cleanedTranscript` (`web/src/voice/transcriber.ts:28`) accepts only some writing systems, so Korean, Greek, Hebrew, Hindi and Thai transcripts are thrown away. Replace the check with the same `\p{L}` letter count that `cleaned()` uses (`web/src/voice/language.ts:226`), sharing one function, and keep `clampTranscript`. Add selftest cases with a ko, el, he, hi and th sentence each. | start now | Selftests pass. A Korean FLEURS clip produces a transcript in the web harness. |
| T1.2 | **Complete the language names.** `LANGUAGE_NAMES` (`web/src/voice/language.ts:25`) maps 20 of the 30 languages. Add the other 10: czech, greek, hebrew, indonesian, thai, vietnamese, romanian, hungarian, catalan, croatian. | start now | Every picker language's name maps to its code (selftest) |
| T1.3 | **[gated] Trim at the last word.** Port iOS `VoiceActivity`: 20 ms frames, noise floor = 10th percentile, speech level = 99th percentile, threshold = `max(floor×4, speech×0.03, 0.0015)`, keep 0.3 s after the voice ends with a fade to zero. Put it in `web/src/voice/transcription/decode.ts` and apply it in `whisper-worker.ts` before the window loop. Also drop chunks that start more than 0.15 s after the voice ends. | after T0.5 | Tail-set made-up words → 0. G passes. |
| T1.4 | **[gated] Block non-speech tokens.** Pass `suppress_tokens` with OpenAI's non-speech list (port iOS `NonSpeechTokens.ids`) through the pipeline's generate options. Check in the harness that the list actually reaches the logits processor. | after T1.3 | Non-speech set ≤ baseline. G passes. |
| T1.5 | **[gated] Retry a weak window.** Today web has no quality checks at all. Decode each window through `model.generate` directly, the way `spoken.ts` already does, with `output_scores` turned on. Compute each window's average log probability and its compression ratio (gzip through `CompressionStream`). Retry at temperatures 0.2, 0.4 … 1.0 when the ratio is above 2.2 or the log probability is below −0.6. Treat a window as silent when the no-speech probability is above 0.5 and the log probability is below −0.6. These are the same thresholds as `TranscriptionProfile.voiceNote`. | after T1.4 | Selftests cover the retry logic. G passes. |

### Lane B — Android

| ID | Todo | Order | Done when |
| -- | ---- | ----- | --------- |
| T1.6 | **[gated] Block non-speech tokens.** Set `params.suppress_nst = true` in `whisper_jni.cpp` next to `suppress_blank` (line 248). whisper.cpp's default is `false`. | after T0.5 | Non-speech set ≤ baseline. G passes. |
| T1.7 | **Fix the threshold mapping.** `entropy_thold` gets `compressionRatioThreshold` (2.2) (`WhisperCppEngine.kt:179`). Entropy is a different measure, and whisper.cpp's own default is 2.4. Add `entropyThreshold` to the Kotlin `TranscriptionProfile` (`TranscriptionTypes.kt:25`). The harness then picks between 2.2 and 2.4. | start now (code); value after T0.5 | Field added and unit-tested. The value is chosen from harness data. |
| T1.8 | **[gated] Trim at the last word.** Port `VoiceActivity` to Kotlin in `TranscriptionTypes.kt`, with the same unit tests as iOS `TranscriptionEngineTests`. Apply it in `WhisperCppEngine.transcribe` before `live.transcribe`. Drop `NativeSegment`s whose `t0Ms` is more than 150 ms past the voice end. | after T1.6 | Tail set → 0. Unit tests match iOS. G passes. |

### Lane C — iOS / iPad

| ID | Todo | Order | Done when |
| -- | ---- | ----- | --------- |
| T1.9 | **Remember "no speech".** Today the result lives only in the bubble's `@State`, so every tap decodes the note again. Store it on the message in `LocalMessageStore`, sealed like the transcript, and have the bubble read it back. Do the same on Android if it has the same gap; check `BubbleServices.transcribe`. | parallel | Tapping a silent note a second time doesn't run Whisper (unit test) |
| T1.10 | **No plaintext temp file.** `VoiceTranscriber.transcribe(audioData:)` writes a plaintext `.m4a` to disk. Decode the audio in memory instead: read it with `AVAudioFile` from a sealed or memory-backed source, or decode the AAC frames with `AVAudioConverter`. | parallel | No file is written under tmp during a transcription (test) |

### Lane D — Shared

| ID | Todo | Order | Done when |
| -- | ---- | ----- | --------- |
| T1.11 | **Run the web selftests in a script.** Add an npm script `test:transcription` (tsx) that runs `decode.selftest.ts`, `detect.selftest.ts`, `voice/language.selftest.ts` and `voice/wav.selftest.ts`. Whether CI runs it is a decision (D5). | start now | `npm run test:transcription` exits 0 |

---

## Phase 2 — Speed (this pays for the bigger models in phase 3)

The tracks run in parallel, and each is **[gated]**. A speed change must not change the transcripts
beyond what the gate allows.

| ID | Todo | Order | Done when |
| -- | ---- | ----- | --------- |
| T2.1 | **iOS: use the Neural Engine.** Every model stage is `.cpuAndGPU` (`WhisperKitEngine.swift:220-226`), unchanged since the first Whisper commit (`c8d89cec`) with no reason given. <br>• Test `audioEncoderCompute = .cpuAndNeuralEngine` and `textDecoderCompute = .cpuAndNeuralEngine`; keep mel on CPU+GPU. <br>• Measure on the T0.6 devices: RTF, energy (Instruments Energy Log), and the time of the first load, when the model is compiled for the Neural Engine. <br>• If the first load is long, run it at download time with the existing "preparing" state, so the first note doesn't pay for it. | after T0.5 | RTF better on all reference devices. G passes. First-load time recorded. |
| T2.2 | **Encode the opening only once.** Language detection encodes the first 30 s, then decoding encodes the same 30 s again. Encoding is the expensive step. One subtask per client: | after T0.5 | Transcripts and language picks match the current code on the whole corpus |
| T2.2a | • **Web:** transformers.js `generate` skips the encoder when `encoder_outputs` is passed (`transformers.web.js:21908`). Encode once in `spoken.ts` and pass the result into window 1's decode, which T1.5 already moves to `model.generate`. | after T1.5 | as above |
| T2.2b | • **Android:** `whisper_full` encodes again even after `whisper_lang_auto_detect` has encoded (`whisper.cpp:6948`, then the main loop). Doing it once means a small patch in the vendored whisper.cpp: let `whisper_full_with_state` reuse the state's encoder output for seek 0. **Decision D2**, because `cpp/VENDORED.md` currently says the copy is unmodified. | after D2 | as above |
| T2.2c | • **iOS:** check whether WhisperKit 0.18 can decode the first window from an encoder output we already have (`TranscribeTask` / `TextDecoder`), or whether `SpokenLanguageSampler` can run inside window 1's decode. If neither works without forking WhisperKit, close this subtask and record why. | spike first | Spike result recorded, then as above |
| T2.3 | **Web: use several threads.** Today the worker runs on one thread (`whisper-worker.ts:1-10`); the comment there says cross-origin isolation would block the Hugging Face download. That is likely wrong: a `fetch` in CORS mode works under COEP `require-corp`, and every image source is already same-origin (`img-src 'self' data: blob:`). <br>• **Spike:** add `Cross-Origin-Opener-Policy: same-origin` and `Cross-Origin-Embedder-Policy: require-corp` to `web/security-headers.conf` and the Vite dev server. Check the HF download (including the `*.xethub.hf.co` redirect), the link-preview relay, PDF.js, media blobs, the call UI, and Safari and Firefox. <br>• If the spike passes: `wasm.numThreads = Math.min(4, navigator.hardwareConcurrency)`, and fix the comment. | after T0.5 | `crossOriginIsolated === true` in all three browsers. Nothing regresses. RTF better. G passes. |
| T2.4 | **Web: WebGPU.** When `navigator.gpu.requestAdapter()` returns an adapter, load with `device: "webgpu"`: encoder `fp16`, decoder `q4f16` or `q8`, whichever passes G. Otherwise use WASM. Any failure falls back to WASM once, then stays on WASM for that session. | after T2.3 | G passes on WebGPU. Falls back cleanly when WebGPU is missing. |
| T2.5 | **Android: measure on a real phone.** The P7 benchmark on a physical phone is still owed (`android/README.md:143-175`); emulators fail it. Run `TranscriptionBenchmarkDeviceTest` for base and small on the T0.6 phones. | parallel; needs the user's phone | Numbers recorded in the README table |

---

## Phase 3 — Accuracy: the right model for each device

Phase 3 depends on phase 0, on T2.1 (iOS), T2.3/T2.4 (web) and T2.5 (Android). Every choice is
**[gated]** per language: a model that is worse in any picker language doesn't become the default
for the device class where it is worse.

| ID | Todo | Order | Done when |
| -- | ---- | ----- | --------- |
| T3.1 | **Shared model policy.** Write the rule in `docs/architecture.md`: device class → default model, fallback model, download size, and the speed and memory budget. One table for all three clients. | first in phase 3 | Doc merged |
| T3.2 | **iOS candidates.** Run on the T0.6 iPhones and iPad: `openai_whisper-small` (current), `small_216MB` (compressed), `large-v3-v20240930_626MB` and `large-v3-v20240930_turbo_632MB`, the variants WhisperKit lists in `Models.swift:1594-1603`. OpenAI notes that turbo is weaker in a few languages, such as Thai and Cantonese, so check th and zh closely. | parallel ×3 | Per-language table. One default chosen per device class. |
| T3.3 | **Android candidates.** `ggml-small-q5_1` (already pinned) and `ggml-large-v3-turbo-q5_0` (around 550 MB; pin its SHA-256 in `WhisperModelStore`). Run on the T0.6 phones. | parallel ×3 | as above |
| T3.4 | **Web candidates.** `whisper-small` at `q8` on WASM with threads (T2.3) and on WebGPU (T2.4), compared with base. Check the size of the browser cache. | parallel ×3 | as above |
| T3.5 | **Choose the model at runtime.** The pick follows T3.1, from the chip, RAM and a short benchmark of the installed model: <br>• iOS: `TranscriptionSession.selectedModel`, using `WhisperKit.modelSupport(for:)`. <br>• Android: call `TranscriptionBenchmark`'s P7 gate at runtime. Today only a test calls it. <br>• Web: `session.ts`, using `navigator.deviceMemory`, the thread count and WebGPU. <br>An upgrade downloads in the background; whether only on Wi-Fi is **decision D4**. The old model stays in use until the new one is verified, then it is deleted. | after T3.1–T3.4 | Unit tests per client. Upgrade, interrupted download and rollback all tested. |
| T3.6 | **Pin the iOS and web downloads.** <br>• Web: pass a Hugging Face `revision` (commit hash) to `pipeline(...)`. transformers.js supports it (`remotePathTemplate {revision}`). <br>• iOS: download with a pinned revision through WhisperKit's `HubApi`, and check a SHA-256 manifest after the download, the way Android's `WhisperModelStore` does. | parallel | A changed file fails the check (test) |
| T3.7 | **Settings: show the model** (iOS, iPad, Android, web). Show the model name and size in Settings › Transcription, plus an option for a more accurate model with a larger download where the device can run it. Update `iOS-App.pen`, `iPad-App.pen`, `Android-App.pen` and `webclient.pen` (rule R5). | after T3.5 | UI and designs match. The user saves the `.pen` files with ⌘S. |

---

## Phase 4 — Decoding refinements (on the final models)

Phase 4 depends on phase 3, so the tuning happens on the models that actually ship. Every task is
**[gated]**.

| ID | Todo | Order | Done when |
| -- | ---- | ----- | --------- |
| T4.1 | **Contact names as a prompt.** iOS already collects the chat's usernames (`request.hints`), but nothing uses them. <br>• Build a prompt of names only, at most 30 tokens, and only when the voice detector finds speech. <br>• Pass it per client: iOS `DecodingOptions.promptTokens`; Android `initial_prompt` with `carry_initial_prompt = false`; web `<\|startofprev\|>` + prompt + the start tokens in `decoder_input_ids`, because transformers.js's Whisper `generate` has `prompt_ids` commented out (`transformers.web.js:27601`). <br>• Add a test set of about 40 text-to-speech sentences that contain real-looking names. Measure name accuracy and the Non-speech set, since a prompt can be repeated over silence. **Decision D3:** usernames or display names. | parallel ×3 | Names improve. Non-speech set unchanged. G passes. Otherwise the task is dropped and the reason recorded. |
| T4.2 | **Android: beam search where there is time.** Use `WHISPER_SAMPLING_BEAM_SEARCH` with a beam size of 5 only for device classes that stay within budget at that size. On iOS, WhisperKit's transcription path is greedy only, and transformers.js's `BeamSearchSampler` isn't a full beam search, so there is no task for either. | parallel | G shows a measurable gain within the speed budget, otherwise dropped |
| T4.3 | **Check the thresholds on the final models.** Re-tune the four thresholds (compression ratio, log probability, first-token log probability, no-speech) on the shipped models with the harness, and keep the iOS, Android and web values identical. | after T4.1/T4.2 | One set of values on all clients. G passes. |

---

## Phase 5 — Features

| ID | Todo | Order | Done when |
| -- | ---- | ----- | --------- |
| T5.1 | **Web: match iOS and Android.** <br>• Settings › Transcription is marked `soon` (`SettingsHome.tsx:143`). Build it: automatic transcription toggle (off by default, honoured in `recorder.ts:407/421`), download card, language pin. <br>• Let received notes be transcribed on demand from `VoiceBubble.tsx`, shared through `shareTranscript`. <br>• Update `webclient.pen`. | anytime, parallel | Web behaves like iOS and Android in a fake-server harness run |
| T5.2 | **Word highlight during playback** (optional). <br>• Turn on word timestamps: iOS `wordTimestamps`, Android `token_timestamps`, web `return_timestamps: "word"`. <br>• Add optional word timings `"w"` to the transcript annotation. Older clients ignore the field. The annotation size limit is 16 KB. <br>• Highlight the current word during playback. <br>• Requires **decision D6**; a protocol change needs `docs/architecture.md:69` updated, plus the designs. | after phase 3 | Timings match the audio within 100 ms on the Tail set. Older clients unaffected (test). |
| T5.3 | **Transcribe while recording** (optional). Decode each finished 30 s window while the recording continues, so a long note's transcript is ready at send. Detection still runs once, on the first 30 s, as now. This only helps notes over 30 s. | after phase 3 | The text is identical to transcribing after recording on the whole corpus |

---

## Phase 6 — Close-out (after each phase merges)

- **T6.1** Update `docs/architecture.md:284`: the model policy, the protections against made-up
  text on all three clients, the cross-origin isolation headers.
- **T6.2** Save the harness location and the latest results to memory.
- **T6.3** Merge each phase to `dev` (rule R6). The owner merges `master`.

---

## Order at a glance

```
Now:      T0.1 ─ T0.2 ─┬─ T0.3 (iOS ║ web ║ Android) ─┐
                       └─ T0.4 ─────────────────────── T0.5 baseline
          T0.6 devices (with the user)
          T1.1  T1.2  T1.7(code)  T1.9  T1.10  T1.11      ← no baseline needed, start now

After T0.5:
  Web     T1.3 → T1.4 → T1.5 → T2.2a ; T2.3 → T2.4
  Android T1.6 → T1.8 ; T1.7(value) ; T2.5 (phone) ; D2 → T2.2b
  iOS     T2.1 ; T2.2c spike

After phase 2:  T3.1 → (T3.2 ║ T3.3 ║ T3.4) → T3.5 → T3.7 ; T3.6 anytime
After phase 3:  T4.1 ║ T4.2 → T4.3 ; T5.2, T5.3 optional
Anytime:        T5.1
```

## Decisions for the user

| ID | Question | Recommendation |
| -- | -------- | -------------- |
| D1 | Should the eval harness live in the repo (`tools/transcription-eval/`) or stay outside it? | In the repo: the gate has to be repeatable. |
| D2 | May we patch the vendored whisper.cpp to encode the opening once (T2.2b)? | Yes, as a small, documented patch listed in `VENDORED.md`. |
| D3 | Prompt with usernames or with display names (T4.1)? | Display names, if the client has them; usernames are often not words. |
| D4 | Should model upgrades download only on Wi-Fi? | Wi-Fi only, with a button to download now. |
| D5 | Should CI run the web transcription selftests? There is no web workflow yet; only `android.yml` and `server.yml`. | Yes: add `web.yml` running `tsc -b` and `test:transcription`. |
| D6 | Should word timings be added to the transcript annotation (T5.2)? | Later, only after phase 3 is done. |
| D7 | If a bigger model wins overall but is worse in one language, what happens? | It doesn't become the default for that device class. A language pin to the affected language keeps the smaller model. |

## Considered and rejected

- **Distil-Whisper:** its strong models are English-only, which would break the language rule.
- **Feeding each window's text into the next** (`condition_on_previous_text`): it's known to cause
  repetition loops. `no_context = true` stays.
- **Any second pass in a forced language:** breaks rule R1.
- **A separate punctuation or capitalisation model:** Whisper already punctuates, and an extra model
  costs download size for every language.
- **Web beam search and iOS beam search:** neither library has a full beam search on its decode path.
