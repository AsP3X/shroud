import { argon2idAsync } from "@noble/hashes/argon2.js";
import { sha256 } from "@noble/hashes/sha2.js";
import { apiBase, CLIENT_HEADER, clientName } from "../config";
import { b64ToBytes, bytesToB64, utf8 } from "./bytes";

/**
 * The account name, checked here and sent as a slow salted digest. The server stores that
 * digest and never receives the name. Same rules as `server/.../auth/username.rs`.
 *
 * `usernameHashB64` stays SHA-256: it is the legacy login value and the local fingerprint of
 * `username + "." + public key` (`contactNames.ts`).
 */

/** Pinned with the server and the other clients. Salt `ABEiM0RVZneImaq7zN3u/w==`, not a live salt. */
export const USERNAME_ARGON2_ALICE = "4b4IohXsRZvTapVS+ZJWMkcBP5keMgo8hGWaLN+boI4=";

const RESERVED_EXACT = new Set([
  "admin",
  "administrator",
  "support",
  "help",
  "shroud",
  "system",
  "root",
  "security",
  "null",
  "undefined",
  "api",
  "www",
  "mail",
  "email",
  "mod",
  "moderator",
  "staff",
  "official",
  "everyone",
  "all",
  "me",
  "self",
  "owner",
]);

const RESERVED_PREFIXES = ["shroud_", "system_", "admin_", "support_"];

export class UsernameError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "UsernameError";
  }
}

/** Lowercase `[a-z0-9_]`, 3–32, and not a reserved name. */
export function normalizeUsername(raw: string): string {
  const trimmed = raw.trim();
  if (trimmed.length < 3 || trimmed.length > 32) {
    throw new UsernameError("Username must be between 3 and 32 characters.");
  }
  if (![...trimmed].every((c) => /[a-zA-Z0-9_]/.test(c))) {
    throw new UsernameError("Username may only contain letters, digits, and underscores.");
  }
  const folded = trimmed.toLowerCase();
  if (RESERVED_EXACT.has(folded) || RESERVED_PREFIXES.some((prefix) => folded.startsWith(prefix))) {
    throw new UsernameError("That username is reserved.");
  }
  return folded;
}

/** Standard Base64 of SHA-256 over the UTF-8 normalized name. */
export function usernameHashB64(normalized: string): string {
  return bytesToB64(sha256(utf8(normalized)));
}

const MIGRATION_KEY = "shroud.username-kdf";
const WEAK = "This server's username protection is too weak to sign in.";

/** Argon2id parameters from `GET /auth/username-kdf`. */
export interface UsernameKdfParams {
  algorithm: string;
  version: number;
  salt: string;
  memory_kib: number;
  iterations: number;
  parallelism: number;
  output_bytes: number;
}

type KdfCache = { kind: "argon2id"; params: UsernameKdfParams } | { kind: "legacy" };

let kdfCache: KdfCache | null = null;
const digestCache = new Map<string, string>();

function migrationStore(): Storage | null {
  try {
    const store = globalThis.localStorage;
    if (!store || typeof store.getItem !== "function") return null;
    return store;
  } catch {
    return null;
  }
}

function rememberedDigests(): Set<string> {
  const store = migrationStore();
  if (!store) return new Set();
  try {
    const parsed = JSON.parse(store.getItem(MIGRATION_KEY) ?? "[]") as unknown;
    if (!Array.isArray(parsed)) return new Set();
    return new Set(parsed.filter((item): item is string => typeof item === "string"));
  } catch {
    return new Set();
  }
}

/** After a successful login or register, stop sending the fast digest. A wipe clears this key. */
export function rememberUsernameKdf(digest: string): void {
  const store = migrationStore();
  if (!store) return;
  const digests = rememberedDigests();
  digests.add(digest);
  store.setItem(MIGRATION_KEY, JSON.stringify([...digests]));
}

/** Refuse a config that would make a guess cheap. Higher memory or iterations are allowed. */
export function usernameKdfParams(raw: unknown): UsernameKdfParams {
  if (!raw || typeof raw !== "object") throw new UsernameError(WEAK);
  const body = raw as Partial<UsernameKdfParams>;
  let salt = new Uint8Array();
  if (typeof body.salt === "string") {
    try {
      salt = b64ToBytes(body.salt);
    } catch {
      throw new UsernameError(WEAK);
    }
  }
  if (
    body.algorithm !== "argon2id" ||
    body.version !== 19 ||
    body.parallelism !== 1 ||
    body.output_bytes !== 32 ||
    typeof body.memory_kib !== "number" ||
    typeof body.iterations !== "number" ||
    body.memory_kib < 65536 ||
    body.iterations < 8 ||
    salt.length < 16 ||
    salt.length > 64
  ) {
    throw new UsernameError(WEAK);
  }
  return {
    algorithm: "argon2id",
    version: 19,
    salt: body.salt as string,
    memory_kib: body.memory_kib,
    iterations: body.iterations,
    parallelism: 1,
    output_bytes: 32,
  };
}

/** Argon2id of a normalized name. Cached for this page so a device-limit retry does not hash twice. */
export async function argon2UsernameB64(normalized: string, params: UsernameKdfParams): Promise<string> {
  const key = `${normalized}\n${params.salt}\n${params.memory_kib}\n${params.iterations}`;
  const hit = digestCache.get(key);
  if (hit) return hit;
  const out = await argon2idAsync(utf8(normalized), b64ToBytes(params.salt), {
    t: params.iterations,
    m: params.memory_kib,
    p: params.parallelism,
    dkLen: params.output_bytes,
    version: 0x13,
    asyncTick: 50,
  });
  const digest = bytesToB64(out);
  digestCache.set(key, digest);
  return digest;
}

async function loadKdf(): Promise<KdfCache> {
  if (kdfCache) return kdfCache;
  let response: Response;
  try {
    response = await fetch(`${apiBase()}/auth/username-kdf`, {
      headers: { Accept: "application/json", [CLIENT_HEADER]: clientName() },
    });
  } catch {
    throw new UsernameError("Could not read this server's username protection.");
  }
  if (response.status === 404) {
    kdfCache = { kind: "legacy" };
    return kdfCache;
  }
  if (!response.ok) {
    throw new UsernameError("Could not read this server's username protection.");
  }
  const params = usernameKdfParams(await response.json());
  kdfCache = { kind: "argon2id", params };
  return kdfCache;
}

/**
 * What register and login send. `includeLegacy` is true for login until this browser has
 * signed in with the slow digest. A 404 from an old server keeps the SHA-256 login.
 */
export async function usernameAuthFields(
  normalized: string,
  includeLegacy: boolean,
): Promise<{ username_hash: string; legacy_username_hash?: string }> {
  const kdf = await loadKdf();
  if (kdf.kind === "legacy") {
    return { username_hash: usernameHashB64(normalized) };
  }
  const username_hash = await argon2UsernameB64(normalized, kdf.params);
  const legacy =
    includeLegacy && !rememberedDigests().has(username_hash) ? usernameHashB64(normalized) : undefined;
  return legacy ? { username_hash, legacy_username_hash: legacy } : { username_hash };
}
