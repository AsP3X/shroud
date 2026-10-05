import { File as FileIcon, ImageIcon, Mic, Video } from "lucide-react";
import type { ChatMessage } from "../messaging";
import { peekImage } from "../media/images";
import { replyKindLabel, type ReplyRef } from "../reply";
import { thumbnailUrl } from "./ImageBubble";

/** What a reply header draws, once the reference has been resolved against the thread. */
export type QuotePreview = {
  author: string;
  text: string;
  /** True when `text` is a stand-in ("Photo", "Message deleted") rather than typed words. */
  isStandIn: boolean;
  thumbnail: string | null;
  icon: "photo" | "video" | "voice" | "file" | null;
};

/**
 * Resolves a quote for display.
 *
 * Prefers the live message when it is still in the thread, so quoting something that is later
 * deleted for everyone reads "Message deleted" instead of keeping a copy of withdrawn text.
 * Falls back to the snippet sealed with the reply when the original is not on this device.
 */
export function resolveQuote(
  reference: ReplyRef,
  original: ChatMessage | undefined,
  peerName: string,
  myId: string,
): QuotePreview {
  if (!original) {
    const mine = reference.senderUserId.toLowerCase() === myId.toLowerCase();
    return {
      author: mine ? "You" : peerName,
      text: reference.snippet || replyKindLabel(reference.kind),
      isStandIn: !reference.snippet,
      thumbnail: null,
      icon:
        reference.kind === "text"
          ? null
          : reference.kind === "image"
            ? "photo"
            : reference.kind === "video" || reference.kind === "voice" || reference.kind === "file"
              ? reference.kind
              : null,
    };
  }
  return quoteOf(original, peerName);
}

/** The header for a message that is on this device — bubbles and the composer bar share it. */
export function quoteOf(message: ChatMessage, peerName: string): QuotePreview {
  const author = message.isMine ? "You" : peerName;
  if (message.deleted) {
    return { author, text: "Message deleted", isStandIn: true, thumbnail: null, icon: null };
  }
  const caption = message.caption?.trim() ?? "";
  switch (message.kind) {
    case "image":
      return {
        author,
        text: caption || "Photo",
        isStandIn: !caption,
        thumbnail: peekImage(message.id)?.url ?? thumbnailUrl(message),
        icon: "photo",
      };
    case "video":
      return {
        author,
        text: caption || "Video",
        isStandIn: !caption,
        thumbnail: thumbnailUrl(message),
        icon: "video",
      };
    case "voice":
      // Telegram quotes a voice note by name, not by its transcript.
      return { author, text: "Voice message", isStandIn: true, thumbnail: null, icon: "voice" };
    case "file":
      // Quoted by its name (docs/file-sharing.md §1), whatever the caption says.
      return {
        author,
        text: message.fileName || "File",
        isStandIn: !message.fileName,
        thumbnail: thumbnailUrl(message),
        icon: "file",
      };
    default:
      return {
        author,
        text: message.text,
        isStandIn: !message.text.trim(),
        thumbnail: null,
        icon: null,
      };
  }
}

/**
 * Telegram's reply header: accent stripe, author, one line of the quoted message. The same
 * block is used inside a bubble and above the composer, so the thing being answered looks
 * identical before and after sending.
 */
export function ReplyQuote({
  quote,
  variant = "bubble",
  onClick,
}: {
  quote: QuotePreview;
  /** "bar" is the composer strip: no fill, one point larger. */
  variant?: "bubble" | "bar";
  onClick?: () => void;
}) {
  const Icon = quote.icon === "photo" ? ImageIcon : quote.icon === "video" ? Video : quote.icon === "file" ? FileIcon : Mic;
  const body = (
    <>
      {quote.thumbnail ? (
        <img className="reply-quote-thumb" src={quote.thumbnail} alt="" />
      ) : quote.icon ? (
        <Icon className="reply-quote-icon" size={13} aria-hidden="true" />
      ) : null}
      <span className="reply-quote-lines">
        <strong>{quote.author}</strong>
        <span className={quote.isStandIn ? "reply-quote-text muted" : "reply-quote-text"}>
          {quote.text}
        </span>
      </span>
    </>
  );

  const className = `reply-quote reply-quote-${variant}`;
  if (!onClick) return <span className={className}>{body}</span>;
  return (
    <button
      type="button"
      className={className}
      onClick={(event) => {
        event.stopPropagation();
        onClick();
      }}
      title="Go to the quoted message"
    >
      {body}
    </button>
  );
}
