/**
 * Builds a link preview in the browser — the web client's counterpart of iOS
 * `LinkPreviewFetcher.fetchPreview`, with the same rules and the same result, so a preview
 * looks the same whichever device sent it.
 *
 * Only the sender contacts the website, and only through the link relay (`relayFetch.ts`):
 * the Shroud server sees the host name, the website sees the server. The result is sealed into
 * the message; recipients never load anything from the link.
 */

import {
  cleanPreviewText,
  MAX_SITE_NAME,
  MAX_SUMMARY,
  MAX_TITLE,
  type LinkPreview,
} from "../links";
import { headEnded } from "./http";
import { prepareLinkImages, type PreparedLinkImages } from "./images";
import { isEmptyMetadata, parsePageMetadata, type PageMetadata } from "./pageMetadata";
import { relayFetch, type RelayFetchOptions } from "./relayFetch";
import type { TlsModule } from "./tls";

/** Only the head of a page is read. */
export const MAX_HEAD_BYTES = 512 * 1024;
/** The page's image. */
export const MAX_IMAGE_BYTES = 5 * 1024 * 1024;

const PAGE_ACCEPT = "text/html,application/xhtml+xml;q=0.9,*/*;q=0.5";
const IMAGE_ACCEPT = "image/avif,image/webp,image/png,image/jpeg,image/*;q=0.8";

/** A preview the composer holds for the link in the draft (iOS `LinkPreviewDraft`). */
export type LinkPreviewDraft = {
  /** Metadata; `thumbnail` is the square inline thumb when the page has a usable image. */
  preview: LinkPreview;
  /** JPEG for the large layout; null when the page has no picture worth showing big. */
  largeImage: Uint8Array | null;
  largeImageWidth: number | null;
  largeImageHeight: number | null;
  /** Tiny base64 JPEG, sealed with the large picture and drawn blurred until it downloads. */
  placeholder: string | null;
  /** Telegram's pick: a big picture for videos and wide card images, else the small square. */
  prefersLargeImage: boolean;
};

export type BuildOptions = {
  token: string;
  relayUrl: string;
  tls: TlsModule;
  acceptLanguage?: string;
  signal?: AbortSignal;
  /** Injected in tests (canvas is browser-only). */
  prepareImages?: (bytes: Uint8Array) => Promise<PreparedLinkImages | null>;
};

/** `de-DE,de;q=0.9,en;q=0.8` — the page in the sender's language when it has one. */
export function acceptLanguageHeader(languages: readonly string[]): string {
  const picked = languages.slice(0, 3);
  if (picked.length === 0) return "en";
  return picked.map((language, index) => (index === 0 ? language : `${language};q=${(1 - index * 0.1).toFixed(1)}`)).join(",");
}

/** The preview for `url`, or an error when the page has nothing to show. */
export async function buildLinkPreview(url: string, options: BuildOptions): Promise<LinkPreviewDraft> {
  const common: Omit<RelayFetchOptions, "accept" | "maxBytes"> = {
    token: options.token,
    relayUrl: options.relayUrl,
    tls: options.tls,
    acceptLanguage: options.acceptLanguage,
    signal: options.signal,
  };
  const page = await relayFetch(url, {
    ...common,
    accept: PAGE_ACCEPT,
    maxBytes: MAX_HEAD_BYTES,
    stopWhen: headEnded,
  });
  if (page.status < 200 || page.status >= 300) throw new Error(`The website answered ${page.status}.`);
  if ((page.headers.get("content-encoding") ?? "identity").toLowerCase() !== "identity") {
    throw new Error("The website sent a compressed page.");
  }

  const contentType = (page.headers.get("content-type") ?? "").toLowerCase();
  let metadata: PageMetadata;
  let imageBytes: Uint8Array | null = null;
  if (contentType.startsWith("image/")) {
    // A direct link to a picture: the picture is the preview.
    metadata = {
      siteName: null,
      title: null,
      summary: null,
      imageUrl: page.url,
      imageWidth: null,
      imageHeight: null,
      isVideo: false,
    };
    if (page.complete && !page.truncated) imageBytes = page.body;
  } else {
    if (contentType && !contentType.includes("html") && !contentType.includes("xml")) {
      throw new Error("Not a web page.");
    }
    metadata = parsePageMetadata(page.body, page.url, contentType || null);
  }
  if (isEmptyMetadata(metadata)) throw new Error("Nothing to preview.");

  let images: PreparedLinkImages | null = null;
  if (metadata.imageUrl) {
    try {
      if (!imageBytes) {
        const image = await relayFetch(metadata.imageUrl, {
          ...common,
          accept: IMAGE_ACCEPT,
          maxBytes: MAX_IMAGE_BYTES,
        });
        const ok = image.status >= 200 && image.status < 300 && image.complete && !image.truncated;
        if (ok) imageBytes = image.body;
      }
      if (imageBytes) images = await (options.prepareImages ?? prepareLinkImages)(imageBytes);
    } catch (error) {
      if (options.signal?.aborted) throw error;
      // A broken image must not cost the whole preview — Telegram shows the text alone.
    }
  }
  const title = cleanPreviewText(metadata.title, MAX_TITLE);
  const summary = cleanPreviewText(metadata.summary, MAX_SUMMARY);
  if (!title && !summary && !images) throw new Error("Nothing to preview.");

  const preview: LinkPreview = {
    url,
    siteName: cleanPreviewText(metadata.siteName, MAX_SITE_NAME),
    title,
    summary,
    thumbnail: images?.thumbnail ?? null,
    imageWidth: images?.width ?? null,
    imageHeight: images?.height ?? null,
    isVideo: metadata.isVideo,
    showsAboveText: false,
  };
  const prefersLargeImage =
    images !== null && (metadata.isVideo || (images.width >= 400 && images.width / Math.max(images.height, 1) >= 1.2));
  return {
    preview,
    largeImage: images?.large ?? null,
    largeImageWidth: images?.width ?? null,
    largeImageHeight: images?.height ?? null,
    placeholder: images?.placeholder ?? null,
    prefersLargeImage,
  };
}
