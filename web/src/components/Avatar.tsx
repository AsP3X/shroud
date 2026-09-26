import type { CSSProperties } from "react";
import { initials } from "../config";

/* The same eight top-to-bottom pairs as iOS `AvatarView.gradient(for:)`. */
const PALETTE: readonly [string, string][] = [
  ["#7c7aff", "#5e5ce6"],
  ["#ff9f5a", "#f76b1c"],
  ["#ff7a9e", "#e64a72"],
  ["#4ac7fa", "#2e8fe0"],
  ["#5ad97c", "#2fa85b"],
  ["#c77cff", "#9b4ae6"],
  ["#ffc65a", "#e69a1c"],
  ["#8e8e93", "#5f5f66"],
];

/* FNV-1a plus a finalizer, over UTF-16 code units. The same function as iOS
   `AvatarView.paletteIndex`: a contact keeps one colour on every launch and the
   same colour in both clients. A plain `h * 31 + c` collapses here: 31 ≡ -1 (mod 8),
   so the low bits of ids that share a long tail — as UUIDs do — all land together. */
export function avatarPalette(seed: string): readonly [string, string] {
  let hash = 0x811c9dc5;
  for (let i = 0; i < seed.length; i++) {
    hash ^= seed.charCodeAt(i);
    hash = Math.imul(hash, 0x01000193);
  }
  hash ^= hash >>> 13;
  hash = Math.imul(hash, 0x5bd1e995);
  hash ^= hash >>> 15;
  return PALETTE[(hash >>> 0) % PALETTE.length];
}

export function Avatar({
  name,
  seed,
  size = "md",
  online = false,
}: {
  name: string;
  seed?: string;
  size?: "xs" | "sm" | "md" | "lg";
  online?: boolean;
}) {
  const [top, bottom] = avatarPalette(seed ?? name);
  /* Set on the element that paints: a var() inside a custom property declared on
     :root resolves there, so a per-avatar value set here would never reach it. */
  const style = { "--avatar-top": top, "--avatar-bottom": bottom } as CSSProperties;
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
