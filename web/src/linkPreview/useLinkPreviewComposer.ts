import { useCallback, useEffect, useRef, useState } from "react";
import { linkRelayUrl } from "../config";
import { acceptLanguageHeader, buildLinkPreview } from "./builder";
import { LinkPreviewComposer, type LinkPreviewAttachment } from "./composer";
import { generatesLinkPreviews } from "./settings";
import { loadTls } from "./tls";

/** What the composer's link bar shows (mirrors iOS `ChatLinkBarState`). */
export type LinkBarState =
  | { kind: "loading"; url: string }
  | { kind: "ready"; title: string; snippet: string; snippetIsLink: boolean };

export type LinkPreviewComposerApi = {
  bar: LinkBarState | null;
  showsAboveText: boolean;
  canToggleImageSize: boolean;
  usesLargeImage: boolean;
  toggleShowsAboveText: () => void;
  toggleImageSize: () => void;
  dismiss: () => void;
  /** The preview to seal with `text`, if one finished loading for its link. Resets the bar. */
  takeAttachment: (text: string) => LinkPreviewAttachment | null;
};

function hostOf(url: string): string {
  try {
    const host = new URL(url).hostname;
    return host.startsWith("www.") ? host.slice(4) : host;
  } catch {
    return url;
  }
}

/**
 * The link preview for the draft of one chat: builds it in this browser through the link relay
 * (see `relayFetch.ts`) while the user types, and hands it over at send.
 *
 * `conversationId` resets everything when the chat changes; `token` authenticates the relay.
 */
export function useLinkPreviewComposer(opts: {
  draft: string;
  token: string;
  conversationId: string | null;
  canSend: boolean;
}): LinkPreviewComposerApi {
  const [, setVersion] = useState(0);
  const tokenRef = useRef(opts.token);
  tokenRef.current = opts.token;
  const composerRef = useRef<LinkPreviewComposer | null>(null);
  if (!composerRef.current) {
    composerRef.current = new LinkPreviewComposer(
      async (url, signal) => {
        const tls = await loadTls();
        if (!tls) throw new Error("Link previews are not available in this build.");
        return buildLinkPreview(url, {
          token: tokenRef.current,
          relayUrl: linkRelayUrl(),
          tls,
          acceptLanguage: acceptLanguageHeader(navigator.languages ?? []),
          signal,
        });
      },
      () => setVersion((n) => n + 1),
    );
  }
  const composer = composerRef.current;

  useEffect(() => {
    composer.draftChanged(opts.draft, opts.canSend && generatesLinkPreviews());
  }, [composer, opts.draft, opts.canSend]);

  useEffect(() => () => composer.reset(), [composer, opts.conversationId]);

  const takeAttachment = useCallback((text: string) => composer.takeAttachment(text), [composer]);

  let bar: LinkBarState | null = null;
  if (composer.phase.kind === "loading") {
    bar = { kind: "loading", url: composer.phase.url.replace(/^https?:\/\//i, "") };
  } else if (composer.phase.kind === "ready") {
    const preview = composer.phase.draft.preview;
    const title = preview.title ?? preview.siteName ?? hostOf(preview.url);
    bar = preview.summary
      ? { kind: "ready", title, snippet: preview.summary, snippetIsLink: false }
      : { kind: "ready", title, snippet: preview.url, snippetIsLink: true };
  }

  return {
    bar,
    showsAboveText: composer.showsAboveText,
    canToggleImageSize: composer.canToggleImageSize,
    usesLargeImage: composer.usesLargeImage,
    toggleShowsAboveText: () => composer.toggleShowsAboveText(),
    toggleImageSize: () => composer.toggleImageSize(),
    dismiss: () => composer.dismiss(),
    takeAttachment,
  };
}
