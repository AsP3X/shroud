/// <reference types="vite/client" />

declare module "onnxruntime-web/ort-wasm-simd-threaded.asyncify.wasm?url" {
  const src: string;
  export default src;
}

declare module "onnxruntime-web/ort-wasm-simd-threaded.asyncify.mjs?url" {
  const src: string;
  export default src;
}

interface ShroudConfig {
  apiBase: string;
}

interface Window {
  __SHROUD_CONFIG__?: ShroudConfig;
}
