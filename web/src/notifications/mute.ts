import type { ChatMute } from "../api/client";

/** The durations a chat can be muted for, as Telegram offers them. `null` = until unmuted. */
export const MUTE_CHOICES: readonly { label: string; seconds: number | null }[] = [
  { label: "For 1 hour", seconds: 60 * 60 },
  { label: "For 8 hours", seconds: 8 * 60 * 60 },
  { label: "For 1 day", seconds: 24 * 60 * 60 },
  { label: "For 7 days", seconds: 7 * 24 * 60 * 60 },
  { label: "Until I turn it back on", seconds: null },
];

/** A mute is over once its time has passed, before the next chat list says so. */
export function isMuted(mute: ChatMute | null | undefined, now = Date.now()): boolean {
  if (!mute) return false;
  if (mute.until === null) return true;
  const until = Date.parse(mute.until);
  return Number.isFinite(until) && until > now;
}

/** "Muted", "Muted until 14:30", "Muted until Fri 14:30", "Muted until 3 Oct". */
export function muteLabel(mute: ChatMute | null | undefined, now = Date.now()): string {
  if (!isMuted(mute, now)) return "On";
  if (!mute || mute.until === null) return "Muted";
  const until = new Date(mute.until);
  const hours = (until.getTime() - now) / 3_600_000;
  const time = until.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
  const sameDay = until.toDateString() === new Date(now).toDateString();
  if (sameDay) return `Muted until ${time}`;
  if (hours < 24 * 6) {
    return `Muted until ${until.toLocaleDateString([], { weekday: "short" })} ${time}`;
  }
  return `Muted until ${until.toLocaleDateString([], { day: "numeric", month: "short" })}`;
}
