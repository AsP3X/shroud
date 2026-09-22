import { useEffect, useState, type CSSProperties } from "react";
import { Play } from "lucide-react";
import type { ChatMessage } from "../messaging";
import { peekImage, type LoadedImage } from "../media/images";
import { displaySiteName, type LinkPreview } from "../links";

/**
 * Telegram's link preview block (`LinkPreviewIn` / `LinkPreviewOut` / `LinkPreviewIn · thumb` in
 * design/webclient.pen): accent stripe, tinted card, site name, title, description, and either
 * a small thumbnail the text flows around or a large picture underneath.
 *
 * Everything shown was sealed into the message by the sender's phone. The small thumbnail is
 * inline (`lp.th`); a large picture is the message's own encrypted blob, fetched from Shroud's
 * server and decrypted like a photo. Nothing is ever loaded from the linked website.
 */
export function LinkPreviewCard({
  message,
  preview,
  loadImage,
}: {
  message: ChatMessage;
  preview: LinkPreview;
  /** Downloads + decrypts the blob (the same path photos use). */
  loadImage: (message: ChatMessage) => Promise<LoadedImage | null>;
}) {
  const large = Boolean(message.mediaObjectId && message.mediaKey);
  const [picture, setPicture] = useState<string | null>(() =>
    large ? (peekImage(message.id)?.url ?? null) : null,
  );

  useEffect(() => {
    if (!large || picture) return;
    let live = true;
    void loadImage(message).then((loaded) => {
      if (live && loaded) setPicture(loaded.url);
    });
    return () => {
      live = false;
    };
    // `ensureImage` dedupes per message id, so a re-run for a fresh copy of the same message
    // joins the download already in flight.
  }, [large, message, picture, loadImage]);

  const thumb = !large && preview.thumbnail ? `data:image/jpeg;base64,${preview.thumbnail}` : null;
  const placeholder = large && message.thumbnail ? `data:image/jpeg;base64,${message.thumbnail}` : null;
  const width = message.imageWidth ?? preview.imageWidth;
  const height = message.imageHeight ?? preview.imageHeight;
  // Telegram crops very tall or very wide pictures into a sane band.
  const aspect = width && height ? Math.min(Math.max(width / height, 0.75), 2.4) : 1.91;
  const mediaStyle = { "--lp-aspect": String(aspect) } as CSSProperties;

  return (
    <a
      className={`link-preview${thumb ? " has-thumb" : ""}${large ? " has-media" : ""}`}
      href={preview.url}
      data-link={preview.url}
      target="_blank"
      rel="noopener noreferrer nofollow"
      onClick={(event) => event.stopPropagation()}
      aria-label={`Link preview: ${[displaySiteName(preview), preview.title].filter(Boolean).join(", ")}`}
    >
      {thumb ? <img className="link-preview-thumb" src={thumb} alt="" /> : null}
      <span className="link-preview-site">{displaySiteName(preview)}</span>
      {preview.title ? <span className="link-preview-title">{preview.title}</span> : null}
      {preview.summary ? <span className="link-preview-desc">{preview.summary}</span> : null}
      {large ? (
        <span className="link-preview-media" style={mediaStyle}>
          {picture ? (
            <img src={picture} alt="" />
          ) : placeholder ? (
            <img className="is-placeholder" src={placeholder} alt="" />
          ) : null}
          {preview.isVideo ? (
            <span className="link-preview-play" aria-hidden="true">
              <Play size={20} fill="currentColor" />
            </span>
          ) : null}
        </span>
      ) : null}
    </a>
  );
}
