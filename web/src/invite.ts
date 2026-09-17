const UUID_RE =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

export type Invite =
  | { kind: "userId"; value: string }
  | { kind: "shareCode"; value: string }
  | { kind: "username"; value: string };

export function normalizeShareCode(raw: string): string {
  return raw
    .trim()
    .replace(/^@/, "")
    .replace(/[-\s]/g, "")
    .toUpperCase();
}

function isShareCode(code: string): boolean {
  return code.length >= 8 && code.length <= 16 && /^[A-Z0-9]+$/.test(code);
}

function isUsername(name: string): boolean {
  return name.length >= 3 && name.length <= 32 && /^[a-z0-9_]+$/.test(name);
}

function parseUrl(raw: string): Invite | null {
  try {
    const url = new URL(raw.includes("://") ? raw : `https://${raw}`);
    const parts = url.pathname.split("/").filter(Boolean);
    const uIndex = parts.findIndex((p) => p === "u" || p === "user");
    if (uIndex >= 0 && parts[uIndex + 1]) {
      const code = normalizeShareCode(parts[uIndex + 1]);
      if (isShareCode(code)) return { kind: "shareCode", value: code };
    }
    if (parts.length === 1 && isShareCode(normalizeShareCode(parts[0]))) {
      return { kind: "shareCode", value: normalizeShareCode(parts[0]) };
    }
  } catch {
    return null;
  }
  return null;
}

export function parseInvite(raw: string): Invite | null {
  const trimmed = raw.trim();
  if (!trimmed) return null;
  if (UUID_RE.test(trimmed)) return { kind: "userId", value: trimmed.toLowerCase() };
  const fromUrl = parseUrl(trimmed);
  if (fromUrl) return fromUrl;
  const code = normalizeShareCode(trimmed);
  if (isShareCode(code)) return { kind: "shareCode", value: code };
  const username = trimmed.replace(/^@/, "").toLowerCase();
  if (isUsername(username)) return { kind: "username", value: username };
  return null;
}
