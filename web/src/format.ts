export const MINUTE = 60_000;

function dayNumber(date: Date): number {
  return Math.floor(
    (date.getTime() - date.getTimezoneOffset() * MINUTE) / (24 * 60 * MINUTE),
  );
}

/** Calendar days between `iso` and today. 0 = today, 1 = yesterday. */
function daysAgo(iso: string): number | null {
  const at = new Date(iso);
  if (Number.isNaN(at.getTime())) return null;
  return dayNumber(new Date()) - dayNumber(at);
}

export function sameDay(a: string, b: string): boolean {
  const left = daysAgo(a);
  const right = daysAgo(b);
  return left != null && left === right;
}

export function clockTime(iso: string): string {
  const at = new Date(iso);
  if (Number.isNaN(at.getTime())) return "";
  return at.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
}

/** Separator above the first message of each day. */
export function dayLabel(iso: string): string {
  const ago = daysAgo(iso);
  if (ago == null) return "";
  if (ago === 0) return "Today";
  if (ago === 1) return "Yesterday";
  const at = new Date(iso);
  if (ago < 7) return at.toLocaleDateString([], { weekday: "long" });
  return at.toLocaleDateString([], {
    day: "numeric",
    month: "long",
    ...(ago > 300 ? { year: "numeric" as const } : {}),
  });
}

/** Trailing column of a chat row: tight, and never ambiguous about the day. */
export function listTimestamp(iso: string | null): string {
  if (!iso) return "";
  const ago = daysAgo(iso);
  if (ago == null) return "";
  if (ago === 0) return clockTime(iso);
  const at = new Date(iso);
  if (ago === 1) return "Yesterday";
  if (ago < 7) return at.toLocaleDateString([], { weekday: "short" });
  return at.toLocaleDateString([], { day: "2-digit", month: "2-digit" });
}

/** Full date + time, for the `title` tooltip on a bubble. */
export function fullTimestamp(iso: string): string {
  const at = new Date(iso);
  if (Number.isNaN(at.getTime())) return "";
  return at.toLocaleString([], { dateStyle: "long", timeStyle: "short" });
}

/** Date only, long style ("12 March 2025"): when a device was linked. */
export function longDate(iso: string): string {
  const at = new Date(iso);
  if (Number.isNaN(at.getTime())) return "";
  return at.toLocaleDateString([], { dateStyle: "long" });
}

export type Presence = { online: boolean; lastSeenAt: string | null };

/** A deleted account has no presence: no online dot and no last seen. */
export function visiblePresence(deleted: boolean | undefined, presence: Presence | undefined): Presence | undefined {
  return deleted ? undefined : presence;
}

export function presenceLabel(presence: Presence | undefined): string {
  if (!presence) return "";
  if (presence.online) return "online";
  if (!presence.lastSeenAt) return "offline";
  const ago = daysAgo(presence.lastSeenAt);
  if (ago == null) return "offline";
  if (ago === 0) return `last seen ${clockTime(presence.lastSeenAt)}`;
  if (ago === 1) return "last seen yesterday";
  return `last seen ${new Date(presence.lastSeenAt).toLocaleDateString()}`;
}

/** `812 KB`, `2.4 MB` — storage sizes and photo download chips. */
export function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(0)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

/** iOS renders `niklas_v` as "Niklas V" in the hero while the handle stays raw. */
export function displayName(username: string): string {
  const pretty = username
    .replace(/_/g, " ")
    .split(/\s+/)
    .filter(Boolean)
    .map((part) => part.slice(0, 1).toUpperCase() + part.slice(1).toLowerCase())
    .join(" ");
  return pretty || "Shroud User";
}
