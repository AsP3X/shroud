/*
 * On-device transcription for voice notes recorded in the browser — the web
 * counterpart of iOS `VoiceTranscriber`. It runs live on the microphone track
 * while the user speaks, so the text is ready by the time they press Send and is
 * sealed into the payload like an iPhone transcript.
 *
 * Audio must never leave the device: this only runs where the Web Speech API
 * can promise local processing (`processLocally`, Chrome). Anywhere else there
 * is simply no transcript, and the recipient can still transcribe on their
 * device and share it back as an annotation.
 */

import { clampTranscript } from "../crypto/mediaPayload";

type RecognitionOptions = { langs: string[]; processLocally: boolean };
type Availability = "unavailable" | "downloadable" | "downloading" | "available";

type RecognitionResultList = ArrayLike<{ isFinal: boolean; 0?: { transcript: string } }>;

interface Recognition {
  lang: string;
  continuous: boolean;
  interimResults: boolean;
  processLocally: boolean;
  onresult: ((event: { resultIndex: number; results: RecognitionResultList }) => void) | null;
  onerror: ((event: { error: string }) => void) | null;
  onend: (() => void) | null;
  start(track?: MediaStreamTrack): void;
  stop(): void;
  abort(): void;
}

interface RecognitionConstructor {
  new (): Recognition;
  prototype: Recognition;
  available(options: RecognitionOptions): Promise<Availability>;
  install?(options: RecognitionOptions): Promise<boolean>;
}

/** How long Send waits for the recogniser to hand over its last words. */
const FINISH_TIMEOUT_MS = 2000;
/** Errors after which restarting the session cannot help. */
const FATAL_ERRORS = new Set([
  "not-allowed",
  "service-not-allowed",
  "language-not-supported",
  "audio-capture",
]);
/**
 * Chrome ends a session after a stretch of silence, so a long note with pauses needs
 * many restarts. Only sessions that die almost immediately count against this — that
 * is the loop we guard against, not a talkative user.
 */
const MAX_QUICK_FAILURES = 3;
const QUICK_FAILURE_MS = 1000;

function onDeviceRecognition(): RecognitionConstructor | null {
  const scope = window as unknown as {
    SpeechRecognition?: RecognitionConstructor;
    webkitSpeechRecognition?: RecognitionConstructor;
  };
  const Ctor = scope.SpeechRecognition ?? scope.webkitSpeechRecognition;
  if (!Ctor || typeof Ctor.available !== "function") return null;
  if (!("processLocally" in Ctor.prototype)) return null;
  return Ctor;
}

function options(): RecognitionOptions {
  return { langs: [navigator.language || "en-US"], processLocally: true };
}

/**
 * Call from the record press. Recording is a clear signal the user wants a
 * transcript, so a missing language pack starts downloading while they speak
 * (the press also supplies the user activation the download needs).
 */
export function prepareTranscription(): void {
  const Ctor = onDeviceRecognition();
  if (!Ctor) return;
  const opts = options();
  void Ctor.available(opts)
    .then((status) => {
      if (status === "downloadable") return Ctor.install?.(opts);
    })
    .catch(() => {
      /* best effort */
    });
}

export type LiveTranscript = {
  /** Stops listening and resolves with everything heard, or null. */
  finish(): Promise<string | null>;
  cancel(): void;
};

export async function startLiveTranscript(track: MediaStreamTrack): Promise<LiveTranscript | null> {
  const Ctor = onDeviceRecognition();
  if (!Ctor) return null;
  const opts = options();
  try {
    if ((await Ctor.available(opts)) !== "available") return null;
  } catch {
    return null;
  }

  const recognition = new Ctor();
  recognition.lang = opts.langs[0];
  recognition.continuous = true;
  recognition.interimResults = false;
  recognition.processLocally = true;
  if (recognition.processLocally !== true) return null;

  // Our own handle on the mic, so the recorder stopping its track can't cut off the last words.
  const input = track.clone();
  /** Final text from sessions that already ended (Chrome can end a long session early). */
  const committed: string[] = [];
  let session: string[] = [];
  let done = false;
  let fatal = false;
  let quickFailures = 0;
  let sessionStartedAt = 0;
  let settle: () => void = () => {};
  const ended = new Promise<void>((resolve) => {
    settle = resolve;
  });

  const close = () => {
    committed.push(...session.filter(Boolean));
    session = [];
    input.stop();
    settle();
  };

  recognition.onresult = (event) => {
    for (let i = event.resultIndex; i < event.results.length; i++) {
      const result = event.results[i];
      if (result.isFinal) session[i] = result[0]?.transcript ?? "";
    }
  };
  recognition.onerror = (event) => {
    if (FATAL_ERRORS.has(event.error)) fatal = true;
  };
  recognition.onend = () => {
    if (performance.now() - sessionStartedAt < QUICK_FAILURE_MS) quickFailures += 1;
    if (!done && !fatal && quickFailures < MAX_QUICK_FAILURES && input.readyState === "live") {
      committed.push(...session.filter(Boolean));
      session = [];
      try {
        sessionStartedAt = performance.now();
        recognition.start(input);
        return;
      } catch {
        /* fall through: keep what we have */
      }
    }
    close();
  };

  try {
    sessionStartedAt = performance.now();
    recognition.start(input);
  } catch {
    input.stop();
    return null;
  }

  return {
    async finish() {
      if (!done) {
        done = true;
        try {
          recognition.stop();
        } catch {
          close();
        }
      }
      let timer = 0;
      await Promise.race([
        ended,
        new Promise<void>((resolve) => {
          timer = window.setTimeout(resolve, FINISH_TIMEOUT_MS);
        }),
      ]);
      window.clearTimeout(timer);
      if (input.readyState !== "ended") {
        try {
          recognition.abort();
        } catch {
          /* already ended */
        }
        close();
      }
      const text = clampTranscript(committed.join(" ").replace(/\s+/g, " "));
      return text || null;
    },
    cancel() {
      done = true;
      try {
        recognition.abort();
      } catch {
        /* already ended */
      }
      input.stop();
      settle();
    },
  };
}
