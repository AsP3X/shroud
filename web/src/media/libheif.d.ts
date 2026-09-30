/* The slice of libheif-js's Emscripten API the HEIC worker uses. */
declare module "libheif-js/libheif-wasm/libheif-bundle.mjs" {
  export type HeifImage = {
    get_width(): number;
    get_height(): number;
    /** Writes RGB into `target` (alpha is left as-is) and calls back with it, or null. */
    display(target: ImageData, done: (result: ImageData | null) => void): void;
    free(): void;
  };
  export type HeifDecoder = {
    decoder: unknown;
    decode(data: ArrayBuffer | Uint8Array): HeifImage[];
  };
  export type LibHeif = {
    HeifDecoder: new () => HeifDecoder;
    heif_context_free(context: unknown): void;
  };
  /** Instantiates synchronously from the inlined WASM — only allowed off the main thread. */
  const factory: (options?: Record<string, unknown>) => LibHeif;
  export default factory;
}
