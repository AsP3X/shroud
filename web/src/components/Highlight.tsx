import type { ReactNode } from "react";

/** Wraps every case-insensitive hit in <mark> so matches are findable by eye. */
export function Highlight({ text, query }: { text: string; query: string }) {
  const needle = query.trim().toLowerCase();
  if (!needle) return <>{text}</>;
  const parts: ReactNode[] = [];
  const haystack = text.toLowerCase();
  let cursor = 0;
  for (;;) {
    const at = haystack.indexOf(needle, cursor);
    if (at === -1) {
      parts.push(text.slice(cursor));
      break;
    }
    if (at > cursor) parts.push(text.slice(cursor, at));
    parts.push(<mark key={at}>{text.slice(at, at + needle.length)}</mark>);
    cursor = at + needle.length;
  }
  return <>{parts}</>;
}
