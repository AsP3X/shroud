/**
 * Message reactions: the sealed wire payload (shared with iOS `MessageReactionPayload`), and the
 * last-write-wins rules that fold server changes into a thread.
 *
 * A reaction is not a message. The server keeps one sealed record per (message, user) — the
 * person's whole set of emoji, up to the server's `max_per_user` — and learns who reacted to which
 * message, never the emoji (`PUT /messages/{id}/reaction`). `r` ties the record to its message so
 * the server cannot move it onto another; a removal keeps its entry with no emoji, so a late,
 * older change cannot bring the reactions back. See docs/architecture.md.
 */
import { api, type WireReaction } from "./api/client";
import { utf8, utf8decode } from "./crypto/bytes";
import {
  envelopeToWireB64,
  openTaggedEnvelope,
  sealIdentityEnvelope,
  wireB64ToEnvelope,
} from "./crypto/messageCrypto";
import type { IdentityMaterial } from "./crypto/identity";
import type { ChatMessage } from "./messaging";

export type Reaction = {
  userId: string;
  /** Oldest first; empty when the user took their reactions back. */
  emojis: string[];
  /** The server's change cursor; the highest per user wins. */
  seq: number;
  /** Our own change the server has not confirmed yet; it keeps the previous `seq`. */
  pending?: boolean;
};

/**
 * One person's emoji and face — or both faces, when the two picked exactly the same emoji. Several
 * reactions by one person share one chip.
 */
export type ReactionChip = { emojis: string[]; userIds: string[]; includesMe: boolean };

/** How many emoji one person may leave on a message until the server says otherwise. */
export const DEFAULT_REACTION_LIMIT = 5;
/** Most emoji a reader shows from one record, whatever the server's limit is today. */
export const READER_CAP = 20;

/** The bar under a message's menu: Telegram's quick seven. */
export const QUICK_REACTIONS = ["❤️", "🔥", "👍", "😢", "🙏", "😮", "👎"];
/** Telegram's standard set, behind the bar's "more" (quick seven first). */
export const ALL_REACTIONS = [
  ...QUICK_REACTIONS,
  "🥰", "👏", "😁", "🤔", "🤯", "😱", "🤬", "🎉", "🤩", "🤮", "💩", "👌", "🕊️", "🤡",
  "🥱", "🥴", "😍", "🐳", "❤️‍🔥", "🌚", "🌭", "💯", "🤣", "⚡", "🍌", "🏆", "💔", "🤨",
  "😐", "🍓", "🍾", "💋", "🖕", "😈", "😴", "😭", "🤓", "👻", "👨‍💻", "👀", "🎃", "🙈",
  "😇", "😨", "🤝", "✍️", "🤗", "🫡", "🎅", "🎄", "☃️", "💅", "🤪", "🗿", "🆒", "💘",
  "🙉", "🦄", "😘", "💊", "🙊", "😎", "👾", "🤷", "😡",
];

const MAX_EMOJI_BYTES = 32;
const segmenter = new Intl.Segmenter(undefined, { granularity: "grapheme" });

/** One grapheme drawn as an emoji: rejects text, digits and several emoji at once. */
export function isSingleEmoji(text: string): boolean {
  if (!text || utf8(text).length > MAX_EMOJI_BYTES) return false;
  if ([...segmenter.segment(text)].length !== 1) return false;
  if (/^\p{Emoji_Presentation}/u.test(text)) return true;
  // "❤" + VS16, keycaps, flags: an emoji-capable base made an emoji by what follows.
  return /^\p{Emoji}/u.test(text) && [...text].length > 1;
}

export function reactionPayload(emojis: string[], messageId: string): string {
  return JSON.stringify({ t: "reaction", r: messageId.toLowerCase(), e: emojis });
}

/**
 * The emoji in `raw` when it is a reaction to `messageId` — single emoji only, each once, oldest
 * first, at most `READER_CAP` — else null.
 */
export function parseReaction(raw: string, messageId: string): string[] | null {
  try {
    const value = JSON.parse(raw) as { t?: unknown; r?: unknown; e?: unknown } | null;
    if (!value || value.t !== "reaction" || typeof value.r !== "string") return null;
    if (value.r.toLowerCase() !== messageId.toLowerCase()) return null;
    const emojis: string[] = [];
    for (const emoji of Array.isArray(value.e) ? value.e : []) {
      if (typeof emoji !== "string" || !isSingleEmoji(emoji) || emojis.includes(emoji)) continue;
      emojis.push(emoji);
      if (emojis.length === READER_CAP) break;
    }
    return emojis;
  } catch {
    return null;
  }
}

/**
 * Our set after picking `emoji`: taken back when it is there, otherwise added — and past `limit`
 * (the server's `max_per_user`) our oldest goes, so a pick always shows.
 */
export function toggledReactions(emoji: string, current: string[], limit: number): string[] {
  if (current.includes(emoji)) return current.filter((e) => e !== emoji);
  const next = [...current, emoji];
  const cap = Math.max(1, limit);
  return next.length > cap ? next.slice(next.length - cap) : next;
}

function sortKey(reaction: Reaction): number {
  return reaction.pending ? Number.MAX_SAFE_INTEGER : reaction.seq;
}

function sorted(reactions: Reaction[]): Reaction[] {
  return [...reactions].sort((a, b) => sortKey(a) - sortKey(b) || a.userId.localeCompare(b.userId));
}

/** One change (WebSocket, catch-up). Null when it changes nothing. */
export function applyReaction(reactions: Reaction[], change: Reaction): Reaction[] | null {
  const index = reactions.findIndex((r) => r.userId === change.userId);
  if (index < 0) return sorted([...reactions, change]);
  const current = reactions[index];
  // Our unconfirmed tap is newer than anything the server has sent so far.
  if (current.pending && !change.pending) return null;
  if (!change.pending && change.seq <= current.seq) return null;
  const next = [...reactions];
  next[index] = change;
  return sorted(next);
}

/** `userId`'s entry swapped (or dropped), whatever its seq: our own tap, its ack or its undo. */
export function replacingReaction(reactions: Reaction[], userId: string, entry: Reaction | null): Reaction[] {
  const rest = reactions.filter((r) => r.userId !== userId);
  return sorted(entry ? [...rest, entry] : rest);
}

export function emojisOf(userId: string, reactions: Reaction[] | undefined): string[] {
  return reactions?.find((r) => r.userId === userId)?.emojis ?? [];
}

/**
 * One chip per person (both share one when they picked exactly the same emoji), the other side's
 * first and ours after — Telegram's place for them, whoever reacted first.
 */
export function reactionChips(reactions: Reaction[] | undefined, me: string): ReactionChip[] {
  const same = (a: string[], b: string[]) => a.length === b.length && a.every((e) => b.includes(e));
  const chips: ReactionChip[] = [];
  for (const reaction of reactions ?? []) {
    if (reaction.emojis.length === 0) continue;
    const shared = chips.find((chip) => same(chip.emojis, reaction.emojis));
    if (shared) {
      shared.userIds = [...shared.userIds, reaction.userId];
      shared.includesMe = shared.includesMe || reaction.userId === me;
    } else {
      chips.push({ emojis: reaction.emojis, userIds: [reaction.userId], includesMe: reaction.userId === me });
    }
  }
  return chips
    .map((chip, order) => ({ chip, order }))
    .sort((a, b) => Number(a.chip.includesMe) - Number(b.chip.includesMe) || a.order - b.order)
    .map(({ chip }) => chip);
}

/**
 * Records a history page lists under `messageId`, as the page may be trusted with them: only
 * records for that very message, from the two people in the chat, the newest per person. The
 * server could otherwise list a genuine reaction under another message, or a stranger's.
 */
export function pageReactionsFor(
  messageId: string,
  wires: WireReaction[] | undefined,
  allowed: ReadonlySet<string>,
): WireReaction[] {
  const latest = new Map<string, WireReaction>();
  for (const wire of wires ?? []) {
    const user = wire.user_id.toLowerCase();
    if (wire.message_id.toLowerCase() !== messageId.toLowerCase() || !allowed.has(user)) continue;
    const held = latest.get(user);
    if (!held || held.seq < wire.seq) latest.set(user, wire);
  }
  return [...latest.values()];
}

/** Folds opened changes into a thread. Pure: it runs inside `setThread` updaters. */
export function applyReactionChanges(
  thread: ChatMessage[],
  changes: { messageId: string; reaction: Reaction }[],
): ChatMessage[] {
  if (changes.length === 0) return thread;
  const byMessage = new Map<string, Reaction[]>();
  for (const change of changes) {
    const key = change.messageId.toLowerCase();
    byMessage.set(key, [...(byMessage.get(key) ?? []), change.reaction]);
  }
  let changed = false;
  const next = thread.map((message) => {
    const incoming = byMessage.get(message.id.toLowerCase());
    if (!incoming || message.deleted) return message;
    let reactions = message.reactions ?? [];
    for (const reaction of incoming) reactions = applyReaction(reactions, reaction) ?? reactions;
    if (reactions === message.reactions) return message;
    changed = true;
    return { ...message, reactions };
  });
  return changed ? next : thread;
}

/** Our own entry on one message, replaced. Pure. */
export function withMyReaction(
  thread: ChatMessage[],
  messageId: string,
  me: string,
  entry: Reaction | null,
): ChatMessage[] {
  const key = messageId.toLowerCase();
  return thread.map((message) =>
    message.id.toLowerCase() === key && !message.deleted
      ? { ...message, reactions: replacingReaction(message.reactions ?? [], me, entry) }
      : message,
  );
}

/**
 * Opens one sealed record. A record already held at the same seq is reused; one that does not
 * open counts as no reaction (its seq still wins over older ones).
 */
export async function openReaction(
  wire: WireReaction,
  me: string,
  material: IdentityMaterial,
  peerIdentity: (userId: string) => Promise<Uint8Array>,
  held: Reaction[] = [],
): Promise<Reaction> {
  const userId = wire.user_id.toLowerCase();
  const removed: Reaction = { userId, emojis: [], seq: wire.seq };
  if (!wire.ciphertext) return removed;
  const known = held.find((r) => r.userId === userId && r.seq === wire.seq && !r.pending);
  if (known) return known;
  try {
    // Tagged v2 only: every build that writes reactions tags its boxes.
    const mine = userId === me.toLowerCase();
    const plain = await openTaggedEnvelope({
      envelopeData: wireB64ToEnvelope(wire.ciphertext),
      ourPrivate: material.agreementPrivate,
      ourIdentityPublic: material.agreementPublic,
      senderIdentityPublic: mine ? material.agreementPublic : await peerIdentity(userId),
      asSender: mine,
    });
    return { userId, emojis: parseReaction(utf8decode(plain), wire.message_id) ?? [], seq: wire.seq };
  } catch {
    return removed;
  }
}

/**
 * Saves our whole set (empty removes it). Sealed as a v2 envelope, never through the ratchet:
 * the record is overwritten in place, and every device must open it at any time.
 * Resolves to the confirmed entry, or null when there was nothing to remove.
 */
export async function saveReaction(opts: {
  token: string;
  me: string;
  messageId: string;
  emojis: string[];
  material: IdentityMaterial;
  peerIdentityPublic: Uint8Array;
}): Promise<Reaction | null> {
  const me = opts.me.toLowerCase();
  if (opts.emojis.length === 0) {
    const res = await api.deleteReaction(opts.token, opts.messageId);
    return res ? { userId: me, emojis: [], seq: res.seq } : null;
  }
  const envelope = await sealIdentityEnvelope(
    utf8(reactionPayload(opts.emojis, opts.messageId)),
    opts.material.agreementPrivate,
    opts.peerIdentityPublic,
    opts.material.agreementPublic,
  );
  const res = await api.putReaction(opts.token, opts.messageId, envelopeToWireB64(envelope));
  return { userId: me, emojis: opts.emojis, seq: res.seq };
}
