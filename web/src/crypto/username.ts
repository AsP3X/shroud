import { sha256 } from "@noble/hashes/sha2.js";
import { bytesToB64, utf8 } from "./bytes";

/**
 * The account name, checked here and sent only as SHA-256. The server stores that digest.
 * It never receives the name. Same rules as `server/.../auth/username.rs`.
 */

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

/** Standard Base64 of SHA-256 over the UTF-8 normalized name. This is what login sends. */
export function usernameHashB64(normalized: string): string {
  return bytesToB64(sha256(utf8(normalized)));
}
