import { useSyncExternalStore } from "react";

/**
 * What the web remembers about PDFs it has opened (docs/file-sharing.md §10), in memory only and
 * dropped when the chats lock: the bubble's local render of page 1 (§10.1), the page count that
 * render read, and the page the viewer was left on (§10.2, session memory). Nothing here imports
 * pdf.js, so the chat's bundle stays free of it; the viewer fills these in.
 */

/** A local render of the card: page 1 at the card's pixel size, cropped 2:1 from the top. */
export type PdfCardRender = { url: string; width: number; height: number };

/** Budget of the renders, in decoded pixels (width × height × 4), least recently used out first. */
export const PDF_CARD_BUDGET_BYTES = 24 * 1024 * 1024;

const cards = new Map<string, PdfCardRender>();
let cardBytes = 0;
const pageCounts = new Map<string, number>();
const lastPages = new Map<string, number>();
const listeners = new Set<() => void>();

function key(id: string): string {
  return id.toLowerCase();
}

function emit(): void {
  for (const listener of [...listeners]) listener();
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

function bytesOf(card: PdfCardRender): number {
  return card.width * card.height * 4;
}

function dropCard(id: string): boolean {
  const card = cards.get(id);
  if (!card) return false;
  cards.delete(id);
  cardBytes -= bytesOf(card);
  URL.revokeObjectURL(card.url);
  return true;
}

/** Keeps a render (taking ownership of its object URL), evicting the least recently used. */
export function rememberPdfCard(messageId: string, card: PdfCardRender): void {
  const id = key(messageId);
  dropCard(id);
  if (bytesOf(card) > PDF_CARD_BUDGET_BYTES) {
    URL.revokeObjectURL(card.url);
    return;
  }
  cards.set(id, card);
  cardBytes += bytesOf(card);
  for (const oldest of cards.keys()) {
    if (cardBytes <= PDF_CARD_BUDGET_BYTES) break;
    dropCard(oldest);
  }
  emit();
}

/** The render for a message, refreshed as most recently used. */
export function peekPdfCard(messageId: string): PdfCardRender | null {
  return cards.get(key(messageId)) ?? null;
}

export function hasPdfCard(messageId: string): boolean {
  return cards.has(key(messageId));
}

/** Marks a render as just used (the bubble showed it), so it is evicted last. */
function touch(id: string): void {
  const card = cards.get(id);
  if (!card) return;
  cards.delete(id);
  cards.set(id, card);
}

export function usePdfCard(messageId: string): PdfCardRender | null {
  const id = key(messageId);
  const card = useSyncExternalStore(
    subscribe,
    () => cards.get(id) ?? null,
    () => null,
  );
  if (card) touch(id);
  return card;
}

/** The page count the viewer read, for payloads without `pg`. */
export function rememberPdfPageCount(messageId: string, pages: number): void {
  if (!(Number.isInteger(pages) && pages >= 1)) return;
  const id = key(messageId);
  if (pageCounts.get(id) === pages) return;
  pageCounts.set(id, pages);
  emit();
}

export function usePdfPageCount(messageId: string): number | null {
  const id = key(messageId);
  return useSyncExternalStore(
    subscribe,
    () => pageCounts.get(id) ?? null,
    () => null,
  );
}

/** Where the viewer was left, per message, for this session. */
export function rememberPdfPage(messageId: string, page: number): void {
  lastPages.set(key(messageId), page);
}

export function lastPdfPage(messageId: string): number | null {
  return lastPages.get(key(messageId)) ?? null;
}

/**
 * Whether the reader wants the viewer's pages sidebar (wide windows) open: their last toggle,
 * held for the tab's session; null until they toggle it (then multi-page PDFs open with it).
 * A layout preference, not anything about a file, so locking keeps it.
 */
let sidebarChoice: boolean | null = null;

export function pdfSidebarChoice(): boolean | null {
  return sidebarChoice;
}

export function rememberPdfSidebar(open: boolean): void {
  sidebarChoice = open;
}

/** A sent message got its server id: what we know about it moves along. */
export function rekeyPdfMemory(fromId: string, toId: string): void {
  const from = key(fromId);
  const to = key(toId);
  const card = cards.get(from);
  if (card) {
    cards.delete(from);
    dropCard(to);
    cards.set(to, card);
  }
  const pages = pageCounts.get(from);
  if (pages != null) {
    pageCounts.delete(from);
    pageCounts.set(to, pages);
  }
  const page = lastPages.get(from);
  if (page != null) {
    lastPages.delete(from);
    lastPages.set(to, page);
  }
  if (card || pages != null) emit();
}

/** One message is gone (deleted): drop its render and its page. */
export function releasePdfMemory(messageId: string): void {
  const id = key(messageId);
  const dropped = dropCard(id);
  const counted = pageCounts.delete(id);
  lastPages.delete(id);
  if (dropped || counted) emit();
}

/** The chats locked (or the cache was cleared): everything goes, object URLs revoked. */
export function forgetPdfMemory(): void {
  for (const card of cards.values()) URL.revokeObjectURL(card.url);
  cards.clear();
  cardBytes = 0;
  pageCounts.clear();
  lastPages.clear();
  emit();
}
