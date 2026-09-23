/**
 * Loads the TLS client (rustls compiled to WebAssembly, `web/tls/`) on first use.
 *
 * The module is generated at build time (`npm run build:tls`, or the web Docker image's Rust
 * stage) into `./tls/`, which is not committed. When it is missing — a checkout that never
 * built it — the web client simply builds no link previews; nothing else depends on it.
 */

/** The `TlsSession` class exported by the generated module. */
export interface TlsSession {
  /** Queues application data (the HTTP request). */
  write(data: Uint8Array): void;
  /** Feeds bytes received from the relay. Throws on a TLS failure (bad certificate, alert). */
  feed(data: Uint8Array): void;
  /** The relay closed: decrypts whatever is left. */
  finish(): void;
  /** TLS records to send to the relay now. */
  takeOutgoing(): Uint8Array;
  /** Decrypted bytes since the last call. */
  takePlaintext(): Uint8Array;
  /** Queues close_notify. */
  close(): void;
  /** Releases the WebAssembly memory. */
  free(): void;
  readonly closed: boolean;
  readonly isHandshaking: boolean;
}

export interface TlsModule {
  TlsSession: new (host: string) => TlsSession;
}

interface GeneratedModule extends TlsModule {
  default: (input?: unknown) => Promise<unknown>;
}

let loading: Promise<TlsModule | null> | null = null;

/** The TLS module, or null when this build has none (or it failed to load). */
export function loadTls(): Promise<TlsModule | null> {
  loading ??= (async () => {
    // A glob, not an import: a checkout without the generated module still builds.
    const loaders = import.meta.glob("./tls/link_tls.js");
    const load = loaders["./tls/link_tls.js"];
    if (!load) return null;
    const module = (await load()) as GeneratedModule;
    await module.default();
    return module;
  })().catch(() => null);
  return loading;
}
