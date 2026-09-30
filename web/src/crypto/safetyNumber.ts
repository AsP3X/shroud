import { sha256 } from "@noble/hashes/sha2.js";

function precedes(a: Uint8Array, b: Uint8Array): boolean {
  const n = Math.min(a.length, b.length);
  for (let i = 0; i < n; i++) {
    if (a[i] !== b[i]) return a[i] < b[i];
  }
  return a.length < b.length;
}

/** Matches iOS `IdentitySafetyNumber.displayString`. */
export function safetyNumber(localIdentity: Uint8Array, peerIdentity: Uint8Array): string {
  const first = precedes(localIdentity, peerIdentity) ? localIdentity : peerIdentity;
  const second = precedes(localIdentity, peerIdentity) ? peerIdentity : localIdentity;
  const material = new Uint8Array(first.length + second.length);
  material.set(first, 0);
  material.set(second, first.length);
  const digest = sha256(material);
  const groups: string[] = [];
  for (let i = 0; i < 12; i++) {
    const a = digest[(i * 2) % digest.length];
    const b = digest[(i * 2 + 1) % digest.length];
    const c = digest[(i * 3) % digest.length];
    const value = ((a << 16) | (b << 8) | c) >>> 0;
    groups.push(String(value % 100_000).padStart(5, "0"));
  }
  return groups.join(" ");
}
