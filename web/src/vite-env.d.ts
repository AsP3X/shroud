/// <reference types="vite/client" />

interface ShroudConfig {
  apiBase: string;
}

interface Window {
  __SHROUD_CONFIG__?: ShroudConfig;
}
