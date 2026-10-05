/** Named Whisper weights. Same idea as iOS `TranscriptionModelID`. */
export type TranscriptionModelId = "tiny" | "base" | "small";

export const DEFAULT_TRANSCRIPTION_MODEL: TranscriptionModelId = "base";

export const WHISPER_MODELS: Record<TranscriptionModelId, string> = {
  tiny: "onnx-community/whisper-tiny",
  base: "onnx-community/whisper-base",
  small: "onnx-community/whisper-small",
};

export type TranscriptionRequest = {
  /** ISO 639-1 (`en`, `de`). Omit to let Whisper detect. */
  language?: string | null;
  /** Languages this person uses; detection prefers them (`pickSpokenLanguage`). */
  candidates?: readonly string[];
};

export type TranscriptionOutput = {
  text: string;
  language: string | null;
};

export type TranscriptionEngine = {
  readonly id: string;
  prepare(
    model: TranscriptionModelId,
    progress?: (fraction: number) => void,
  ): Promise<void>;
  /** Mono PCM at `sampleRate`; the engine resamples it for the model. */
  transcribe(samples: Float32Array, sampleRate: number, request: TranscriptionRequest): Promise<TranscriptionOutput>;
};
