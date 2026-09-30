/**
 * Composer-side link previews: watches the draft, builds a preview for its first link, and
 * hands it to the send — a port of iOS `LinkPreviewComposer`, behaving the same way.
 *
 * Typing or pasting a link shows "Loading preview…", then the page's title and description.
 * ✕ drops the preview for that link (typing a *different* link brings one back); the bar's
 * menu offers "Show above text", "Larger / Smaller image" and "Remove preview". A preview that
 * has not finished loading when Send is pressed is simply left off — sending never waits on a
 * website. Pure logic with an injected fetcher and clock, so it runs in Node tests.
 */

import { firstPreviewableUrl, type LinkPreview } from "../links";
import type { LinkPreviewDraft } from "./builder";

export type ComposerPhase =
  | { kind: "idle" }
  | { kind: "loading"; url: string }
  | { kind: "ready"; draft: LinkPreviewDraft };

/** What is sealed with an outgoing message (iOS `LinkPreviewAttachment`). */
export type LinkPreviewAttachment = {
  /** Metadata plus the square thumbnail (the small layout, and the large one's fallback). */
  preview: LinkPreview;
  /** Present when the large layout was chosen: uploaded as the message's media blob. */
  largeImage: Uint8Array | null;
  largeImageWidth: number | null;
  largeImageHeight: number | null;
  placeholder: string | null;
};

export type PreviewFetcher = (url: string, signal: AbortSignal) => Promise<LinkPreviewDraft>;

/**
 * Identity of a link: scheme and host lowercased (the URL parser does that), path, query and
 * fragment as typed — `youtu.be/dQw4w9WgXcQ` and `youtu.be/dqw4w9wgxcq` are different videos.
 */
export function linkKey(url: string): string {
  try {
    return new URL(url).href;
  } catch {
    return url;
  }
}

const CACHE_LIMIT = 8;

export class LinkPreviewComposer {
  phase: ComposerPhase = { kind: "idle" };
  /** Telegram's "Show above message". */
  showsAboveText = false;
  /** The user's size choice; null follows the page's default. */
  largeImageOverride: boolean | null = null;

  private dismissed = new Set<string>();
  /** Recent results, so deleting and retyping a character never refetches. */
  private cache = new Map<string, LinkPreviewDraft>();
  private fetchAbort: AbortController | null = null;
  private debounceTimer: ReturnType<typeof setTimeout> | null = null;

  constructor(
    private readonly fetcher: PreviewFetcher,
    private readonly onChange: () => void,
    private readonly debounceMs = 450,
  ) {}

  /** The loaded preview, if any. */
  get draft(): LinkPreviewDraft | null {
    return this.phase.kind === "ready" ? this.phase.draft : null;
  }

  /** Whether the large layout is in effect for the loaded preview. */
  get usesLargeImage(): boolean {
    const draft = this.draft;
    if (!draft?.largeImage) return false;
    return this.largeImageOverride ?? draft.prefersLargeImage;
  }

  /** "Larger / Smaller image" only makes sense when both layouts exist. */
  get canToggleImageSize(): boolean {
    const draft = this.draft;
    return Boolean(draft?.largeImage && draft.preview.thumbnail);
  }

  /** Call on every draft change. */
  draftChanged(text: string, enabled: boolean): void {
    this.clearDebounce();
    const url = enabled ? firstPreviewableUrl(text) : null;
    if (!url) {
      this.clear(text.length > 0);
      return;
    }
    const key = linkKey(url);
    if (this.dismissed.has(key)) {
      this.cancelFetch();
      this.setPhase({ kind: "idle" });
      return;
    }
    // Already showing (or fetching) this link: nothing to do.
    if (this.phase.kind === "loading" && linkKey(this.phase.url) === key) return;
    if (this.phase.kind === "ready" && linkKey(this.phase.draft.preview.url) === key) return;
    const cached = this.cache.get(key);
    if (cached) {
      this.cancelFetch();
      this.resetOptions();
      this.setPhase({ kind: "ready", draft: cached });
      return;
    }
    this.debounceTimer = setTimeout(() => {
      this.debounceTimer = null;
      this.startFetch(url);
    }, this.debounceMs);
  }

  /** ✕ / "Remove preview": no preview for this link for the rest of the draft. */
  dismiss(): void {
    if (this.phase.kind === "loading") this.dismissed.add(linkKey(this.phase.url));
    if (this.phase.kind === "ready") this.dismissed.add(linkKey(this.phase.draft.preview.url));
    this.cancelFetch();
    this.setPhase({ kind: "idle" });
  }

  toggleShowsAboveText(): void {
    this.showsAboveText = !this.showsAboveText;
    this.onChange();
  }

  toggleImageSize(): void {
    if (!this.canToggleImageSize) return;
    this.largeImageOverride = !this.usesLargeImage;
    this.onChange();
  }

  /** What to seal with the message about to be sent, then back to idle for the next draft. */
  takeAttachment(text: string): LinkPreviewAttachment | null {
    const draft = this.draft;
    const url = firstPreviewableUrl(text);
    const large = this.usesLargeImage;
    const showsAboveText = this.showsAboveText;
    this.reset();
    if (!draft || !url || linkKey(url) !== linkKey(draft.preview.url)) return null;
    return {
      preview: { ...draft.preview, showsAboveText },
      largeImage: large ? draft.largeImage : null,
      largeImageWidth: large ? draft.largeImageWidth : null,
      largeImageHeight: large ? draft.largeImageHeight : null,
      placeholder: large ? draft.placeholder : null,
    };
  }

  /** Clears everything, including dismissed links (after a send, or leaving the chat). */
  reset(): void {
    this.clearDebounce();
    this.clear(false);
  }

  private startFetch(url: string): void {
    this.cancelFetch();
    this.resetOptions();
    this.setPhase({ kind: "loading", url });
    const key = linkKey(url);
    const abort = new AbortController();
    this.fetchAbort = abort;
    this.fetcher(url, abort.signal).then(
      (draft) => {
        if (abort.signal.aborted) return;
        if (this.phase.kind !== "loading" || linkKey(this.phase.url) !== key) return;
        this.remember(key, draft);
        this.setPhase({ kind: "ready", draft });
      },
      () => {
        if (abort.signal.aborted) return;
        if (this.phase.kind !== "loading" || linkKey(this.phase.url) !== key) return;
        // Telegram shows nothing when a page has no preview.
        this.setPhase({ kind: "idle" });
      },
    );
  }

  private clear(keepDismissed: boolean): void {
    this.cancelFetch();
    if (!keepDismissed) this.dismissed.clear();
    this.resetOptions();
    this.setPhase({ kind: "idle" });
  }

  private clearDebounce(): void {
    if (this.debounceTimer !== null) clearTimeout(this.debounceTimer);
    this.debounceTimer = null;
  }

  private cancelFetch(): void {
    this.fetchAbort?.abort();
    this.fetchAbort = null;
  }

  private resetOptions(): void {
    this.showsAboveText = false;
    this.largeImageOverride = null;
  }

  private setPhase(next: ComposerPhase): void {
    if (this.phase.kind === "idle" && next.kind === "idle") return;
    this.phase = next;
    this.onChange();
  }

  private remember(key: string, draft: LinkPreviewDraft): void {
    this.cache.delete(key);
    this.cache.set(key, draft);
    for (const oldest of this.cache.keys()) {
      if (this.cache.size <= CACHE_LIMIT) break;
      this.cache.delete(oldest);
    }
  }
}
