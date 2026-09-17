import type { CSSProperties } from "react";
import { initials } from "../config";

/** Spread so two people in a row rarely land on the same colour. */
const HUES = [255, 205, 168, 28, 340, 12, 190, 132];

/* FNV-1a plus a finalizer. A plain `h * 31 + c` collapses here: 31 ≡ -1 (mod 8),
   so the low bits of ids that share a long tail — as UUIDs do — all land together. */
function hueFor(seed: string): number {
  let hash = 0x811c9dc5;
  for (let i = 0; i < seed.length; i++) {
    hash ^= seed.charCodeAt(i);
    hash = Math.imul(hash, 0x01000193);
  }
  hash ^= hash >>> 13;
  hash = Math.imul(hash, 0x5bd1e995);
  hash ^= hash >>> 15;
  return HUES[(hash >>> 0) % HUES.length];
}

export function Avatar({
  name,
  seed,
  size = "md",
  online = false,
}: {
  name: string;
  seed?: string;
  size?: "sm" | "md" | "lg";
  online?: boolean;
}) {
  const style = { "--avatar-h": hueFor(seed ?? name) } as CSSProperties;
  return (
    <span className={`avatar avatar-${size}`} style={style}>
      <span aria-hidden="true">{initials(name)}</span>
      {online ? (
        <>
          <span className="avatar-dot" aria-hidden="true" />
          <span className="sr-only">Online</span>
        </>
      ) : null}
    </span>
  );
}
