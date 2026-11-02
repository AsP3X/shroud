# Unresolved open speech models (Omnilingual ASR, OWSM v4, Gemma 3n)

Checked 2026-10-27 against a fixed 30-language voice-note set: en, de, es, fr, it, pt, nl, pl, ru, uk, tr, ar, hi, ja, ko, zh, sv, da, nb, fi, cs, el, he, id, th, vi, ro, hu, ca, hr. A model survives only if it has downloadable weights, a licence a mobile app can ship, documented support for every code (or an explicit miss), a published accuracy comparison to Whisper, and a plausible phone runtime (ONNX, ExecuTorch, Core ML, GGML, sherpa-onnx, or a documented iOS/Android demo). Cloud APIs are out of scope.

## Exact model IDs, sizes, and licence

### Takeaway
Omnilingual ASR and OWSM v4 both have public weights a mobile app can ship (Apache-2.0 and CC-BY-4.0). Gemma 3n also has public weights, but they are under the Gemma Terms of Use, which is a use restriction rather than Apache-2.0. The smallest real ASR checkpoint is Omnilingual CTC 300M (1.3 GiB FP32, about 348 MiB int8 ONNX). OWSM’s smallest is the 102M base. Gemma 3n’s smallest is E2B, a multimodal model with a 2 GB-class memory claim, not a speech-only graph.

### Cited Findings
- Omnilingual ASR was announced 10 November 2025. The GitHub `LICENSE` is Apache License 2.0, copyright 2025 Meta Platforms, Inc. and affiliates. The Hugging Face card for `facebook/omniASR-LLM-300M` sets `license: apache-2.0` and says the licence is Apache-2.0 for the model and code, CC-BY-4.0 for the corpus. — [Meta blog, 10 Nov 2025](https://ai.meta.com/blog/omnilingual-asr-advancing-automatic-speech-recognition/); [GitHub LICENSE](https://github.com/facebookresearch/omnilingual-asr/blob/main/LICENSE); [omniASR-LLM-300M card](https://huggingface.co/facebook/omniASR-LLM-300M/raw/main/README.md)
- The same card’s checkpoint table, also repeated on the GitHub README after the December 2025 v2 update, gives these ASR sizes. SSL encoders are not ASR models. Inference figures are batch 1, 30 s audio, BF16, A100. — [model card](https://huggingface.co/facebook/omniASR-LLM-300M/raw/main/README.md); [GitHub README](https://github.com/facebookresearch/omnilingual-asr/blob/main/README.md)

  | Model card ID | Params | FP32 download | Inference VRAM | RTF (relative speed vs LLM 7B) |
  | --- | ---: | ---: | ---: | --- |
  | `omniASR_CTC_300M` and `_v2` | 325,494,996 | 1.3 GiB | ~2 GiB | 0.001 (96x) |
  | `omniASR_CTC_1B` and `_v2` | 975,065,300 | 3.7 GiB | ~3 GiB | 0.002 (48x) |
  | `omniASR_LLM_300M` | 1,627,603,584 | 6.1 GiB | ~5 GiB | 0.090 (~1x) |
  | `omniASR_LLM_7B` | 7,801,041,536 | 30.0 GiB | ~17 GiB | 0.092 (~1x) |
  | `omniASR_LLM_7B_ZS` | 7,810,900,608 | 30.0 GiB | ~20 GiB | 0.194 (~0.5x) |

- `omniASR_LLM_300M` is not a 300M model. The card counts 1.63B parameters because the LLM-ASR decoder is a separate 1.2B transformer. The 300M label is the encoder size. — [model card](https://huggingface.co/facebook/omniASR-LLM-300M/raw/main/README.md); [alphaXiv summary of the paper](https://www.alphaxiv.org/abs/2511.09690)
- December 2025 update on the official README: improved-CER checkpoints `omniASR_{CTC,LLM}_{300M,1B,3B,7B}_v2`, plus `omniASR_LLM_Unlimited_{300M,1B,3B,7B}_v2` for audio of any length. The v2 CTC rows keep the same parameter count and 1.3 GiB FP32 size as v1. — [GitHub README](https://github.com/facebookresearch/omnilingual-asr/blob/main/README.md)
- `lang_ids.py` in that repo opens with a header that says the source is under “the BSD-style license found in the LICENSE file.” The LICENSE file itself is Apache-2.0. The Hugging Face YAML is `apache-2.0`. — [lang_ids.py](https://raw.githubusercontent.com/facebookresearch/omnilingual-asr/main/src/omnilingual_asr/models/wav2vec2_llama/lang_ids.py); [LICENSE](https://github.com/facebookresearch/omnilingual-asr/blob/main/LICENSE)
- sherpa-onnx ships `omniASR_CTC_300M` int8 as `model.int8.onnx` at 348M in the project’s own `ls -lh` (file dated 12 Nov 2025). A Hugging Face LFS pointer for the same pack records 365,351,918 bytes, which is the same file in decimal bytes (about 348 MiB). — [sherpa Omnilingual models](https://k2-fsa.github.io/sherpa/onnx/omnilingual-asr/models.html); [HF commit adding the file](https://huggingface.co/csukuangfj/sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-int8-2025-11-12/commit/11d56ef87c76a4778280336cb16c3cd7c491a1e2)
- OWSM v4 encoder-decoder IDs on the small-model card: `espnet/owsm_v4_base_102M` (102M), `espnet/owsm_v4_small_370M` (370M), `espnet/owsm_v4_medium_1B` (1.02B). CTC sibling: `espnet/owsm_ctc_v4_1B` (1.01B). YAML `license: cc-by-4.0`. Trained on 320k hours of public speech. — [owsm_v4_small_370M README](https://huggingface.co/espnet/owsm_v4_small_370M/blob/main/README.md); [medium repo listing, 4.09 GB](https://huggingface.co/espnet/owsm_v4_medium_1B/tree/main)
- The v4 paper (arXiv:2506.00338, submitted 31 May 2025, Interspeech 2025) repeats those sizes and a 151-language column for base, small, medium, and CTC. It does not print a byte size for the 102M or 370M checkpoints. — [OWSM v4 HTML](https://arxiv.org/html/2506.00338)
- CC-BY-4.0 on the OWSM card is an attribution licence. No prohibited-use policy was on the card. That is not the same shape as the Gemma terms. — [small-model README](https://huggingface.co/espnet/owsm_v4_small_370M/blob/main/README.md)
- Gemma 3n model IDs: `google/gemma-3n-E2B-it` and `google/gemma-3n-E4B-it` (plus non-instruct `google/gemma-3n-E4B`). Hugging Face licence tag is `gemma`, and the card points at the Gemma Terms of Use. Access requires agreeing to that licence. The E2B-it card says the raw parameter count of that checkpoint is 6B, with a memory footprint comparable to a traditional 2B model. — [google/gemma-3n-E2B-it](https://huggingface.co/google/gemma-3n-E2B-it); [google/gemma-3n-E4B](https://huggingface.co/google/gemma-3n-E4B)
- The 26 June 2025 developer guide conflicts with that card on the raw count: “raw parameter count is 5B and 8B respectively” for E2B and E4B, “operating with as little as 2GB (E2B) and 3GB (E4B) of memory.” Both sources agree on effective 2B and 4B. — [Gemma 3n developer guide, 26 Jun 2025](https://developers.googleblog.com/introducing-gemma-3n-developer-guide/); contradicted on the raw E2B count by [google/gemma-3n-E2B-it](https://huggingface.co/google/gemma-3n-E2B-it)
- Gemma Terms section 3.1 allows distribution only if the distributor makes section 3.2’s use restrictions an enforceable term for later users, gives them the Agreement, and ships a notice naming `ai.google.dev/gemma/terms`. Section 3.2 forbids uses in the Prohibited Use Policy and lets Google restrict usage it believes violates the agreement. That is a use restriction, not Apache-2.0. — [Gemma Terms of Use](https://ai.google.dev/gemma/terms)

### Inferences
- The only Omnilingual checkpoint that is both an ASR model and in Whisper-small territory is CTC 300M. Calling the LLM-300M “300M” would hide a 6.1 GiB, 1.63B-parameter model.
- OWSM’s CC-BY-4.0 tag is shippable in a commercial app if attribution is preserved. Gemma’s terms are not a drop-in permissive licence: the restriction has to be passed on to users.
- The 5B versus 6B raw count for Gemma 3n E2B is unresolved. The memory claim that matters for a phone is the 2 GB / 3 GB figure from the developer guide, not the raw parameter count.

### Gaps
- Byte size of the OWSM v4 102M and 370M weight files was not on the pages read. Only the medium repo was listed at 4.09 GB.
- No official int8 or int4 byte size was published by Meta for CTC 300M. The 348 MiB figure is the sherpa-onnx export, not the fairseq2 checkpoint.
- Which of 5B (blog) or 6B (E2B-it card) is the current raw count was not reconciled.

## Does the language list explicitly include uk, nb, ca, hr, he, th, ar, hi, ja, ko, zh?

### Takeaway
Omnilingual ASR’s `supported_langs` list contains every required language, including the eleven codes that usually get dropped. OWSM v4’s 151-language set also contains all thirty, but the inventory is the OWSM v3.2 training-hour table that v4 keeps, plus v4’s own FLEURS and MLS evals, not one file named “supported languages.” Gemma 3n does not publish the 35 multimodal languages, so none of those eleven codes is confirmed.

### Cited Findings
- The Omnilingual card does not print the 1,600 names. It says to load `omnilingual_asr.models.wav2vec2_llama.lang_ids.supported_langs`, with codes in `{language}_{script}` form, and gives `eng_Latn` and `cmn_Hans` as examples. — [model card](https://huggingface.co/facebook/omniASR-LLM-300M/raw/main/README.md)
- The file at that path, read in full on 2026-10-27, includes these exact strings for the required set. — [lang_ids.py](https://raw.githubusercontent.com/facebookresearch/omnilingual-asr/main/src/omnilingual_asr/models/wav2vec2_llama/lang_ids.py)

  | Required | Token present |
  | --- | --- |
  | en | `eng_Latn` |
  | de | `deu_Latn` |
  | es | `spa_Latn` |
  | fr | `fra_Latn` |
  | it | `ita_Latn` |
  | pt | `por_Latn` |
  | nl | `nld_Latn` |
  | pl | `pol_Latn` |
  | ru | `rus_Cyrl` |
  | uk | `ukr_Cyrl` |
  | tr | `tur_Latn` |
  | ar | `arb_Arab` (Modern Standard Arabic; dialect codes such as `arz_Arab` and `ary_Arab` are also in the file) |
  | hi | `hin_Deva` |
  | ja | `jpn_Jpan` |
  | ko | `kor_Hang` |
  | zh | `cmn_Hans` and `cmn_Hant` (Mandarin). `yue_Hans` and `yue_Hant` are separate. There is no bare `zh`. |
  | sv | `swe_Latn` |
  | da | `dan_Latn` |
  | nb | `nob_Latn` (Bokmål). `nno` was not in the file. |
  | fi | `fin_Latn` |
  | cs | `ces_Latn` |
  | el | `ell_Grek` |
  | he | `heb_Hebr` |
  | id | `ind_Latn` |
  | th | `tha_Thai` |
  | vi | `vie_Latn` |
  | ro | `ron_Latn` |
  | hu | `hun_Latn` |
  | ca | `cat_Latn` |
  | hr | `hrv_Latn` |

- “1,600+ languages” on the Meta blog is the headline, not the inventory. The inventory is the file above. The total length of that list was not counted here. — [Meta blog](https://ai.meta.com/blog/omnilingual-asr-advancing-automatic-speech-recognition/); [lang_ids.py](https://raw.githubusercontent.com/facebookresearch/omnilingual-asr/main/src/omnilingual_asr/models/wav2vec2_llama/lang_ids.py)
- OWSM v4’s paper table lists 151 languages for v4 base, small, medium, and CTC, the same count as v3.1. v4 is “trained on this curated dataset alongside existing OWSM data.” The new YODAS slice is 166,000 hours across 75 languages, not a replacement of the 151. — [OWSM v4 HTML](https://arxiv.org/html/2506.00338)
- The OWLS paper (arXiv:2502.10373) uses the OWSM v3.2 public mix and prints per-language hours. Its own text says both “151 languages” and, elsewhere, “150 unique languages.” Rows that match the required set, with ISO 639-3 codes as printed: — [OWLS HTML](https://arxiv.org/html/2502.10373v1)

  | Language as printed | Code | OWSM v3.2 hours |
  | --- | --- | ---: |
  | English | eng | 73000 |
  | Russian | rus | 20183 |
  | Japanese | jpn | 18900 |
  | Chinese | cmn | 16000 |
  | German | deu | 3700 |
  | French | fra | 2500 |
  | Spanish | spa | 2000 |
  | Catalan | cat | 1996.7 |
  | Dutch | nld | 1700 |
  | Korean | kor | 1000 |
  | Italian | ita | 700 |
  | Polish | pol | 300 |
  | Turkish | tur | 199 |
  | Thai | tha | 139 |
  | Vietnamese | vie | 139 |
  | Czech | ces | 117 |
  | Hungarian | hun | 97.3 |
  | Arabic | ara | 74.6 |
  | Ukrainian | ukr | 69 |
  | Croatian | hrv | 46.6 |
  | Finnish | fin | 38.5 |
  | Swedish | swe | 35.5 |
  | Indonesian | ind | 25.3 |
  | Greek | ell | 24 |
  | Romanian | ron | 22 |
  | Hindi | hin | 16 |
  | Danish | dan | 14.7 |
  | Norwegian | nob | 10 |
  | Hebrew | heb | 9.4 |

- Portuguese is not in the hour rows captured above. It is in the v4 paper’s own MLS table as `por`, so the v4 model is evaluated on it. — [OWSM v4 HTML, Table 6](https://arxiv.org/html/2506.00338)
- v4 FLEURS Table 1, limited to languages where OWSM-CTC v4 error is below 20%, explicitly includes eng, spa, fra, deu, ita, por, nld, cat, jpn, kor, zho, rus, tha, vie, tur, hin, ind, ukr. The paper says CTC v4 beats v3.1 “across all 102 languages,” but does not print the other FLEURS rows. Hebrew, Norwegian, Czech, Hungarian, Greek, Danish, Finnish, Swedish, Croatian, Romanian, and Polish are therefore in the 151-language training inventory (Polish, Czech, Hungarian, Greek, Danish, Finnish, Swedish, Croatian, Romanian, Hebrew, Norwegian from the hour table) without a printed v4 error rate. — [OWSM v4 HTML](https://arxiv.org/html/2506.00338); [OWLS HTML](https://arxiv.org/html/2502.10373v1)
- Gemma 3n, 26 June 2025: “supporting 140 languages for text and multimodal understanding of 35 languages.” The audio section says the USM encoder supports on-device ASR and AST, and that AST looked strong between English and Spanish, French, Italian, and Portuguese. It does not name the other 31 multimodal languages. — [developer guide](https://developers.googleblog.com/introducing-gemma-3n-developer-guide/)
- The E2B-it card says the models “were trained with data in over 140 spoken languages.” That sentence is not a speech-language inventory, and it does not list uk, nb, ca, hr, he, or th. — [google/gemma-3n-E2B-it](https://huggingface.co/google/gemma-3n-E2B-it)
- As fetched on 2026-10-27, `ai.google.dev/gemma/docs/capabilities/audio` is marked deprecated and the speech-to-text examples are for Gemma 4 (`google/gemma-4-E2B-it` and siblings), not a Gemma 3n language list. — [Audio understanding](https://ai.google.dev/gemma/docs/capabilities/audio)

### Inferences
- For Omnilingual, Mandarin is the Chinese coverage (`cmn_Hans` / `cmn_Hant`). Norwegian coverage is Bokmål only. Arabic coverage includes MSA (`arb_Arab`), which is the right match for `ar`.
- OWSM’s Arabic code is `ara`, not `arb`, and its Chinese code in the hour table is `cmn` while the FLEURS row is `zho`. Those are still Arabic and Chinese. The 151-count is inherited; v4 did not publish a fresh code list.
- Hebrew at 9.4 hours and Norwegian Bokmål at 10 hours in the v3.2 mix are present but low-resource. Their absence from the “error below 20%” FLEURS excerpt is a quality warning, not proof they were dropped.
- Gemma 3n’s “35 languages” cannot be audited. A claim that it covers the thirty would be an invention.

### Gaps
- The exact integer length of `supported_langs` was not counted.
- OWSM v4 never published one canonical 151-code file in the pages read. Coverage of the thirty is reconstructed from the v3.2 hour table plus the v4 eval tables. Portuguese’s hour count was not in the captured rows.
- No source named Gemma 3n’s 35 multimodal languages. Whether uk, nb, ca, hr, he, or th are among them is unpublished, not an explicit miss.

## Published WER or CER versus Whisper

### Takeaway
Omnilingual’s November 2025 paper compares every size, including CTC 300M, to Whisper small, medium, and large-v3 on average character error rate. The average is flattering because it mixes in languages where Whisper is weak; on the eight-language MLS set, Whisper large-v3 still has the lower CER. OWSM v4 publishes a word-error comparison to Whisper on MLS (eight languages) and an English leaderboard row, not a FLEURS-versus-Whisper table. Gemma 3n has no published WER or CER against Whisper.

### Cited Findings
- Omnilingual paper, arXiv:2511.09690, submitted 12 November 2025. Table 5 is “Comparison against Whisper v3,” average CER on languages Whisper covers. Test-split figures as printed: — [arXiv HTML, section 5.2.1](https://arxiv.org/html/2511.09690)

  | Model | FLEURS-81 test CER | MLS-8 test CER | CV22-76 test CER |
  | --- | ---: | ---: | ---: |
  | Whisper small | 50.8 | 4.9 | 111.7 |
  | Whisper medium | 47.8 | 4.6 | 87.9 |
  | Whisper large-v3 | 22.6 | 2.0 | 55.6 |
  | 300M CTC | 11.8 | 4.1 | 17.6 |
  | 7B LLM-ASR | 5.6 | 2.4 | 6.4 |

- The paper’s prose: the comparison “only considers languages that Whisper covers.” “Even our smallest model outperforms Whisper large-v3 on most evaluation sets, as measured by average CER.” 300M CTC “surpasses Whisper-large on MMS-Lab-63, FLEURS-82, and CV22-76, and falls behind only on MLS-8.” Against Whisper small and medium, 300M CTC “outperforms them on all four benchmarks.” — [arXiv HTML](https://arxiv.org/html/2511.09690)
- The metric is average CER, not WER, and it is not a per-language table for the thirty voice-note languages. FLEURS-81 is the Whisper-overlap subset, not all 102 FLEURS languages. The paper date is 12 November 2025, before the December 2025 v2 checkpoints. No v2-versus-Whisper CER table was in the README. — [arXiv HTML](https://arxiv.org/html/2511.09690); [GitHub README](https://github.com/facebookresearch/omnilingual-asr/blob/main/README.md)
- A community Core ML card, not Meta, reports CTC-300M fp32 WER on 30 FLEURS utterances per language: English 20.0%, French 23.2%, German 16.5%, Arabic 19.5%, Hindi 22.5%. It does not compare those runs to Whisper. — [aufklarer Core ML card](https://huggingface.co/aufklarer/Omnilingual-ASR-CTC-300M-CoreML-INT8)
- OWSM v4 Table 6, MLS word error rate, 31 May 2025 paper. Average across eng, spa, fra, deu, nld, ita, por, pol: — [OWSM v4 HTML](https://arxiv.org/html/2506.00338)

  | Model | eng | spa | fra | deu | nld | ita | por | pol | Average | Relative speed |
  | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
  | Whisper-small | 9.1 | 9.1 | 13.6 | 11.5 | 18.2 | 21.3 | 13.8 | 12.5 | 13.6 | 2.3× |
  | Whisper-medium | 10.2 | 6.1 | 9.7 | 8.1 | 12.2 | 15.6 | 8.9 | 6.8 | 9.7 | 1.2× |
  | Whisper-large-v3 | 5.1 | 4.1 | 4.8 | 5.6 | 10.2 | 9.2 | 7.4 | 4.4 | 6.4 | 1.0× |
  | OWSM v4 base | 11.6 | 11.6 | 17.6 | 15.9 | 23.1 | 23.3 | 18.9 | 31.5 | 19.2 | 3.0× |
  | OWSM v4 small | 7.6 | 7.1 | 10.2 | 10.3 | 15.7 | 15.7 | 11.5 | 16.0 | 11.8 | 2.2× |
  | OWSM v4 medium | 6.4 | 5.7 | 7.8 | 8.2 | 13.4 | 13.1 | 9.0 | 11.5 | 9.4 | 1.1× |

- That MLS set is eight European languages. It does not include uk, ar, hi, ja, ko, zh, he, th, nb, ca, or hr. The FLEURS numbers in the same paper are against MMS, not Whisper. The authors say CTC v4 “outperforms v3.1 in all 102 languages” and is close to MMS-all (10.7% vs 10.6%) while “being 20% faster,” and they warn that the FLEURS transcription field “may result in higher error rates for certain languages, such as Chinese.” — [OWSM v4 HTML](https://arxiv.org/html/2506.00338)
- English-only, same paper, Table 7: OWSM-CTC v4 average WER 7.44 and RTFx 791.18 versus Whisper-large-v3 7.47 and RTFx 235.61 on the Hugging Face Open ASR Leaderboard. That row is English, and it is the 1B CTC model, not the 102M or 370M encoder-decoder. — [OWSM v4 HTML](https://arxiv.org/html/2506.00338)
- LID on FLEURS, same paper: OWSM v4 medium “reaching the highest accuracy of 95.6%,” described as higher than Whisper and MMS-lid. The printed Whisper cells next to that discussion are Whisper-medium 54.8 and Whisper-large-v3 58.9, with the footnote “Whisper supports only a subset of languages in FLEURS.” The 95.6 and the Whisper 50s are not the same language denominator. — [OWSM v4 HTML](https://arxiv.org/html/2506.00338)
- Gemma 3n’s developer guide gives an LMArena text score for E4B (“over 1300”) and no ASR WER or CER, and no comparison to Whisper large-v3 or large-v3-turbo. — [developer guide](https://developers.googleblog.com/introducing-gemma-3n-developer-guide/)
- A community Unsloth notebook reports a Gemma 3n baseline German WER of 24.32% on an Emilia-DE sample, reduced to 16.22% after LoRA on 3,000 samples. That is not a Whisper comparison and not an official Google number. — [Unsloth Gemma 3n audio notebook](https://colab.research.google.com/github/unslothai/notebooks/blob/main/nb/Gemma3N_%284B%29-Audio.ipynb)

### Inferences
- On the paper’s own average CER, Omnilingual CTC 300M beats Whisper small on FLEURS-81, MLS-8, and Common Voice, and beats Whisper large-v3 on FLEURS-81 and Common Voice. It does not beat Whisper large-v3 on MLS-8 (4.1 vs 2.0 CER). The FLEURS-81 average of 22.6 for Whisper large-v3 is pulled up by languages outside the high-resource set; it should not be read as “Whisper large-v3 has 22.6% error on English.”
- OWSM v4 small beats Whisper small on the eight-language MLS average (11.8 vs 13.6) and loses to Whisper large-v3 (6.4). The medium model is closer to Whisper medium (9.4 vs 9.7) than to large-v3. Neither comparison covers the thirty languages.
- Gemma 3n cannot be ranked against Whisper from Google’s publications. The community German WER is a single unofficial baseline.

### Gaps
- No per-language FLEURS WER for Omnilingual on uk, nb, ca, hr, he, th, ar, hi, ja, ko, or zh was in Table 5. The community five-language WER is 30 utterances and is not a paper result.
- No v2 Omnilingual CER versus Whisper was found. The December 2025 README claims improved CER without a Whisper table.
- OWSM v4 FLEURS scores versus Whisper were not published. Scores for Hebrew, Norwegian, and the other languages left out of the “below 20%” table were not printed.
- No Gemma 3n WER or CER on FLEURS, MLS, or the Open ASR Leaderboard was found.

## Inference speed, RAM, and phone runtime

### Takeaway
Omnilingual CTC 300M has a real sherpa-onnx int8 build around 348 MiB, with Android bindings in use by November 2025. Its official speed number is an A100 figure, not a phone figure. OWSM v4’s published speeds are ESPnet versus Whisper on MLS, and no ONNX, Core ML, ExecuTorch, GGML, or sherpa-onnx build was found. Gemma 3n is the one model Google documents for phones, but as a multimodal LLM that accepts audio, with a 30-second clip limit at launch.

### Cited Findings
- Omnilingual official runtime is fairseq2: `pip install omnilingual-asr`, then `ASRInferencePipeline`. The card says the reference pipeline is cross-platform and needs libsndfile. The demo link is a Hugging Face Space (`facebook/omniasr-transcriptions`), which is a server demo. RTF and VRAM in the table are A100, BF16, batch 1, 30 s. CTC 300M is listed at ~2 GiB and RTF 0.001. LLM 300M is ~5 GiB and about 1× the 7B LLM, not the CTC. — [model card](https://huggingface.co/facebook/omniASR-LLM-300M/raw/main/README.md)
- sherpa-onnx documents `omniASR_CTC_300M` and `omniASR_CTC_1B`, float32 and int8. The int8 300M pack is `sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-int8-2025-11-12`, `model.int8.onnx` 348M. A C API example downloads that pack. The sherpa-onnx export workflow also names `omniASR_CTC_300M_v2` and an archive `sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-v2-int8-2026-02-05`. — [sherpa models page](https://k2-fsa.github.io/sherpa/onnx/omnilingual-asr/models.html); [C API example](https://github.com/k2-fsa/sherpa-onnx/blob/master/c-api-examples/omnilingual-asr-ctc-c-api.c); [export workflow](https://github.com/k2-fsa/sherpa-onnx/blob/master/.github/workflows/export-omnilingual-asr-to-onnx.yaml)
- GitHub issue 2812, opened 22 November 2025, says the reporter is running `sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-int8-2025-11-12` on Android, “type 44 in getOfflineModelConfig,” and quotes `OfflineOmnilingualAsrCtcModelConfig`. The issue asks for a language hint and does not report a phone RTF or RAM figure. — [sherpa-onnx issue 2812](https://github.com/k2-fsa/sherpa-onnx/issues/2812)
- A community Core ML export (`aufklarer/Omnilingual-ASR-CTC-300M-CoreML-INT8`, Apache-2.0) targets iOS 17+ / macOS 14, CPU plus Neural Engine, INT8 palettized weights, and a max duration of 5 seconds “configurable at export time.” Its RTF of about 0.05 is “CTC-300M fp32 on CPU (Apple M-series),” not the int8 phone package and not an iPhone. — [Core ML card](https://huggingface.co/aufklarer/Omnilingual-ASR-CTC-300M-CoreML-INT8)
- A community HF repo lists a v2 int8 archive `sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-v2-int8-2026-02-05.tar.bz2` at about 235 MB. That is an archive size from a third party, not Meta’s FP32 size and not confirmed on the official sherpa models page. — [michsethowusu v2 ONNX repo](https://huggingface.co/michsethowusu/sherpa-onnx-omnilingual-asr-1600-languages-ctc-v2)
- OWSM v4 inference on the model card is ESPnet: `Speech2Text.from_pretrained(model_tag="espnet/owsm_v4_small_370M", lang_sym="<eng>", task_sym="<asr>", beam_size=5)`. The card points at a Gradio demo. No ONNX, ExecuTorch, Core ML, GGML, or sherpa-onnx export is named there. — [small-model README](https://huggingface.co/espnet/owsm_v4_small_370M/blob/main/README.md)
- Relative speed in the v4 MLS table, with Whisper-large-v3 set to 1.0×: v4 base 3.0×, v4 small 2.2×, v4 medium 1.1×, Whisper-small 2.3×. The paper does not say this was measured on a phone. The medium checkpoint’s Hugging Face repo is listed at 4.09 GB. — [OWSM v4 HTML](https://arxiv.org/html/2506.00338); [medium tree](https://huggingface.co/espnet/owsm_v4_medium_1B/tree/main)
- Searches for an OWSM ONNX or sherpa-onnx package, and the sherpa-onnx model index, did not turn up an OWSM family entry. sherpa’s documented non-Whisper families in the pages read include Zipformer, Paraformer, NeMo, Moonshine, SenseVoice, and Omnilingual CTC, not OWSM. — [sherpa-onnx docs](https://k2-fsa.github.io/sherpa/onnx/)
- Gemma 3n developer guide, 26 June 2025: on-device tools named include Google AI Edge / LiteRT-LLM, llama.cpp, MLX, Ollama, and `transformers.js` via `onnx-community/gemma-3n-E2B-it-ONNX`. Memory “as little as 2GB (E2B) and 3GB (E4B).” Audio at launch is clips up to 30 seconds, about 6 tokens per second (one token per 160 ms). The guide also says the encoder is streaming and that longer audio would need more training. — [developer guide](https://developers.googleblog.com/introducing-gemma-3n-developer-guide/)
- The current Gemma audio guide still states “6.25 tokens for Gemma 3n” and “Audio supports a maximum length of 30 seconds,” while the sample model id on that page is Gemma 4. — [Audio understanding](https://ai.google.dev/gemma/docs/capabilities/audio)

### Inferences
- Omnilingual’s phone path is third-party but concrete: int8 ONNX inside sherpa-onnx, which already targets Android and iOS, plus a November 2025 report of the Android config loading this exact file. The official ~2 GiB number is the unquantized A100 measurement and should not be used as the int8 phone budget. A 348 MiB CTC file is in the same size class as a small on-device ASR model.
- The community Core ML pack’s 5-second cap makes that particular export a poor voice-note build even though the runtime is Core ML. sherpa-onnx is the export that does not advertise a 5-second cap.
- OWSM v4 small is about as fast as Whisper small in the authors’ MLS harness, and the medium model is about as fast as Whisper large-v3. That does not make either a phone runtime. A 4.09 GB medium repo is already a large download before any decoder overhead.
- Gemma 3n is phone-shaped on purpose. The runtime evidence is for the multimodal model (LiteRT, llama.cpp, MLX, ONNX). It is not evidence of a dedicated ASR graph with a language list.

### Gaps
- No iPhone or Android real-time factor or RAM measurement was found for the sherpa-onnx Omnilingual int8 model. The Android issue confirms the binding, not the speed.
- No phone RTF was found for OWSM v4 or for Gemma 3n transcription specifically. Gemma’s 2 GB / 3 GB figures are model memory, not transcription latency.
- Whether sherpa-onnx’s official release page, as opposed to the export workflow, actually hosts the February 2026 v2 int8 archive was not confirmed.

## Timestamps, punctuation, and language identification

### Takeaway
The phone-sized Omnilingual model (CTC) has none of the three: the project says transcripts are spoken form without punctuation or capitalization, CTC does not take a language, and timestamps are not documented. OWSM v4 documents language identification and utterance-level timestamps; punctuation is inherited from the v3.2 data story rather than restated as a v4 feature. Gemma 3n’s ASR path is a prompt that names the language. Timestamps and a language-ID mode were not documented.

### Cited Findings
- Omnilingual inference README, section “Punctuation and Capitalization”: “Our models are trained to output transcripts in spoken form without any punctuation or capitalization.” It recommends a third-party punctuation library and notes that most such libraries cover only a small subset of the 1,600 languages. — [inference README](https://github.com/facebookresearch/omnilingual-asr/blob/main/src/omnilingual_asr/models/inference/README.md)
- Same README: CTC v2 models “don’t support language conditioning or context examples.” LLM v2 models “take an optional language code as argument to guide transcription towards the target language and script.” The card’s example passes `lang=["eng_Latn", "deu_Latn"]` into `pipeline.transcribe`. That is a hint, not a reported LID accuracy. — [inference README](https://github.com/facebookresearch/omnilingual-asr/blob/main/src/omnilingual_asr/models/inference/README.md); [model card](https://huggingface.co/facebook/omniASR-LLM-300M/raw/main/README.md)
- A Transformers conversion of CTC 300M v2 says the checkpoint “transcribes in lowercase without punctuation, and does not take a language hint.” — [bezzam/omniasr-ctc-300m-v2](https://huggingface.co/bezzam/omniasr-ctc-300m-v2)
- The sherpa-onnx Android issue asks for a language hint because the shipped config has only `model`. The CTC head in that build is not doing language selection. — [issue 2812](https://github.com/k2-fsa/sherpa-onnx/issues/2812)
- No Omnilingual page read here describes word timestamps, token timestamps, or utterance timestamps. The December 2025 “Unlimited” models are about audio length, not timestamps. — [GitHub README](https://github.com/facebookresearch/omnilingual-asr/blob/main/README.md)
- OWSM v4 small card, supported tasks: language identification, speech recognition, speech translation, utterance-level timestamp prediction, long-form recognition or translation. The sample call passes `lang_sym="<eng>"` and `task_sym="<asr>"`, so the decoder can be told the language as well as asked to identify it. — [small-model README](https://huggingface.co/espnet/owsm_v4_small_370M/blob/main/README.md)
- The v4 paper’s LID section is the 95.6% FLEURS figure cited above. Encoder time resolution for v4 is 80 ms because of 8× subsampling, which bounds how fine a timestamp can be. The card says “utterance-level,” not word-level. — [OWSM v4 HTML](https://arxiv.org/html/2506.00338); [small-model README](https://huggingface.co/espnet/owsm_v4_small_370M/blob/main/README.md)
- Punctuation: the v3.2 data paper says OWSM v3.2 “outputs text that is more aligned with written language with punctuation and case-sensitivity,” and the v4 paper says the v3.2 work restored punctuation and capitalization with an LLM. The v4 model card does not list punctuation as its own feature. — [arXiv:2406.09282](https://arxiv.org/html/2406.09282v1); [OWSM v4 HTML](https://arxiv.org/html/2506.00338)
- Gemma 3n ASR, as shown in the June 2025 guide, is a prompt: “Transcribe the following speech segment in Spanish, then translate it into English,” wrapped in `<start_of_audio>`. The later audio guide’s ASR prompt is “Transcribe the following speech segment in {LANGUAGE}.” Neither page describes word timestamps or a dedicated language-identification score. — [developer guide](https://developers.googleblog.com/introducing-gemma-3n-developer-guide/); [Audio understanding](https://ai.google.dev/gemma/docs/capabilities/audio)

### Inferences
- A voice note that needs punctuation and “which language was this?” cannot get both from the sherpa-onnx Omnilingual CTC build. The LLM variants can be given a language code, but they are 1.63B parameters and about 5 GiB on the official VRAM row, and they still emit spoken-form text.
- OWSM is the only one of the three that documents both LID and timestamps as first-class tasks. The timestamps are utterance-level at 80 ms encoder resolution, not Whisper-style word timestamps.
- Gemma 3n can transcribe when the prompt names a language. That is not evidence of automatic language identification across the thirty.

### Gaps
- Whether an Omnilingual LLM run with `lang` omitted detects the language, or simply guesses a default script, was not specified.
- No measurement of OWSM v4 punctuation accuracy was found. The v3.2 statement is about that older checkpoint.
- No Gemma 3n document found here says timestamps are present or absent in so many words. They are undocumented, not explicitly unsupported.

## Is the smallest checkpoint too weak, and does the model survive?

### Takeaway
Omnilingual CTC 300M is not too weak to beat Whisper small on the paper’s average CER, including MLS-8, but it loses to Whisper large-v3 on that high-resource set and it drops punctuation, timestamps, and language ID. It still meets the five survival checks. OWSM v4 base is weaker than Whisper small on MLS; the 370M small beats Whisper small there and still loses badly to large-v3, and there is no phone runtime. Gemma 3n is a real prompted speech-to-text model with a phone runtime, and it fails the language list, the Whisper comparison, and the licence test.

### Cited Findings
- Survival checks used below are the ones in the assignment: downloadable weights, a licence a mobile app can ship, documented coverage of all thirty languages or an explicit miss, a published comparison to Whisper, and a plausible phone runtime. — assignment criteria, applied to the sources in the sections above.
- Omnilingual CTC 300M versus Whisper small, test CER, 12 November 2025: FLEURS-81 11.8 vs 50.8, MLS-8 4.1 vs 4.9, CV22-76 17.6 vs 111.7. Versus Whisper large-v3 on MLS-8: 4.1 vs 2.0. The authors say 300M CTC outperforms Whisper small and medium on all four benchmarks in that table. — [arXiv HTML](https://arxiv.org/html/2511.09690)
- OWSM v4 base MLS average WER 19.2 versus Whisper small 13.6 and Whisper large-v3 6.4. OWSM v4 small 11.8 versus those same 13.6 and 6.4. The base model is the 102M checkpoint; the small model is 370M. — [OWSM v4 HTML](https://arxiv.org/html/2506.00338)
- OWLS, a related ESPnet suite on the same public mix, prints FLEURS WER at the 1B scale of 100.4 for Hebrew and 60.0 for Norwegian. Those numbers are OWLS, not OWSM v4. They are cited only as a reason not to treat “in the 151” as “good enough for voice notes.” — [OWLS HTML](https://arxiv.org/html/2502.10373v1)

### Inferences
- The smallest Omnilingual ASR checkpoint is not “too weak to beat Whisper small” on the metric the authors published. It is too weak to replace Whisper large-v3 on high-resource MLS, and the phone build does not produce punctuated, time-aligned, language-tagged notes. Those feature gaps are real. They are not one of the five blocking checks.
- OWSM’s 102M base is too weak versus Whisper small on the only multilingual Whisper table the authors printed. The 370M model is the first size that wins that comparison, and it still does not approach Whisper large-v3 (11.8 vs 6.4). Low hour counts for Hebrew (9.4 h) and Norwegian Bokmål (10 h) make those two languages the likely quality misses even though the codes are present.
- Gemma 3n should be described as a multimodal model that can do speech-to-text, not as a dedicated ASR system. Google’s own words document ASR and AST. The missing language inventory and the missing Whisper comparison are enough to reject it. The Gemma use restriction is a third independent failure.

### Gaps
- No head-to-head of Omnilingual CTC 300M int8 on a phone against Whisper small or large-v3-turbo was found. The CER table is server-side FP/BF16 research, not the sherpa-onnx file.
- OWSM v4 error rates for Hebrew and Norwegian were not printed, so “too weak” for those two is an inference from training hours and from a different model family (OWLS), not a v4 measurement.

Omnilingual ASR CTC-300M: SURVIVES — no blocking gap; the 30 language codes, Apache-2.0 weights, a November 2025 CER comparison to Whisper, and a sherpa-onnx int8 runtime are all documented. Punctuation, timestamps, and automatic language ID are absent on that phone checkpoint.
OWSM v4: DOES NOT SURVIVE — no ONNX, Core ML, ExecuTorch, GGML, sherpa-onnx, or iOS/Android runtime was found; inference stays in ESPnet.
Gemma 3n: DOES NOT SURVIVE — the 35 multimodal languages were never listed, so the 30-language set is unverified, and no WER or CER versus Whisper was published. The Gemma Terms of Use are a use restriction on top of that.
