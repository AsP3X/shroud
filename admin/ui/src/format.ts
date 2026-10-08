// Formatting the frames use: "1,284", "12 d 4 h", "12 Mar 2026", "Today", "412 GB".

const integer = new Intl.NumberFormat("en-US");
const dayFormat = new Intl.DateTimeFormat("en-GB", { day: "numeric", month: "short", year: "numeric" });
const timeFormat = new Intl.DateTimeFormat("en-GB", { hour: "2-digit", minute: "2-digit", second: "2-digit" });

export function count(value: number): string {
  return integer.format(value);
}

export function percent(part: number, whole: number): string {
  if (whole <= 0) return "0 %";
  const value = (part / whole) * 100;
  return `${value < 0.1 && value > 0 ? value.toFixed(2) : value.toFixed(value < 10 ? 2 : 1)} %`;
}

/** Decimal units, as the fixtures' README says: 412 GB is 412000000000. */
export function bytes(value: number): string {
  const units = ["B", "kB", "MB", "GB", "TB"];
  let size = value;
  let unit = 0;
  while (size >= 1000 && unit < units.length - 1) {
    size /= 1000;
    unit += 1;
  }
  return `${unit === 0 ? size : size.toFixed(size < 10 ? 1 : 0)} ${units[unit]}`;
}

/** "12 d 4 h", "4 h 2 min", "9 min". */
export function uptime(startedAt: string, now = Date.now()): string {
  const minutes = Math.max(0, Math.floor((now - new Date(startedAt).getTime()) / 60_000));
  const days = Math.floor(minutes / 1440);
  const hours = Math.floor((minutes % 1440) / 60);
  if (days > 0) return `${days} d ${hours} h`;
  if (hours > 0) return `${hours} h ${minutes % 60} min`;
  return `${minutes} min`;
}

export function clock(at: string): string {
  return timeFormat.format(new Date(at));
}

export function day(date: string): string {
  return dayFormat.format(new Date(`${date}T00:00:00Z`));
}

/** "Today", "Yesterday", "41 days ago", or the date past 60 days. */
export function relativeDay(date: string | null, today = new Date()): string {
  if (!date) return "—";
  const then = Date.UTC(
    Number(date.slice(0, 4)),
    Number(date.slice(5, 7)) - 1,
    Number(date.slice(8, 10)),
  );
  const todayUtc = Date.UTC(today.getFullYear(), today.getMonth(), today.getDate());
  const days = Math.round((todayUtc - then) / 86_400_000);
  if (days <= 0) return "Today";
  if (days === 1) return "Yesterday";
  if (days <= 60) return `${days} days ago`;
  return day(date);
}

/** "12 h", "60 min", "45 s", "30 days". */
export function duration(secs: number): string {
  if (secs % 86_400 === 0 && secs >= 86_400) return `${secs / 86_400} day${secs === 86_400 ? "" : "s"}`;
  if (secs % 3600 === 0 && secs >= 3600) return `${secs / 3600} h`;
  if (secs % 60 === 0 && secs >= 60) return `${secs / 60} min`;
  return `${secs} s`;
}

export function shortId(id: string): string {
  return id.length > 9 ? `${id.slice(0, 4)}…${id.slice(-4)}` : id;
}
