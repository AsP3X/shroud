import { File as FileIcon, Mic, Music, Play, Reply, X } from "lucide-react";
import { clockTime } from "../format";
import type { ChatMessage } from "../messaging";
import { Avatar } from "./Avatar";
import type { QuotePreview } from "./ReplyQuote";

/**
 * The message being answered, above the composer: a "Replying to" line, then the message
 * itself in miniature — the sender's small face and a scaled-down bubble in that message's own
 * style. A photo or video shows its frame; a voice note, file or audio its glyph and name. The
 * arrow sits over the attach buttons, the miniature over the field, the close button over the mic.
 */
export function ReplyPreview({
  quote,
  message,
  peer,
  me,
  onJump,
  onCancel,
}: {
  quote: QuotePreview;
  message: ChatMessage;
  peer: { id: string; name: string };
  me: { id: string; name: string };
  onJump: () => void;
  onCancel: () => void;
}) {
  const mine = message.isMine;
  const media = quote.icon === "photo" || quote.icon === "video";
  const Glyph = quote.icon === "voice" ? Mic : quote.icon === "audio" ? Music : quote.icon === "file" ? FileIcon : null;
  return (
    <div className="reply-bar" role="status">
      <span className="reply-bar-icon" aria-hidden="true">
        <Reply size={18} />
      </span>
      <div className="reply-bar-body">
        <span className="reply-bar-label">
          Replying to <b>{mine ? "yourself" : peer.name}</b>
          <time dateTime={message.createdAt}>{clockTime(message.createdAt)}</time>
        </span>
        <button type="button" className="reply-bar-msg" onClick={onJump} title="Go to the quoted message">
          <Avatar name={mine ? me.name : peer.name} seed={mine ? me.id : peer.id} size="xs" />
          <span className={`reply-mini ${mine ? "out" : "in"}${media ? " media" : ""}`}>
            {media ? (
              <span className="reply-mini-pic">
                {quote.thumbnail ? <img src={quote.thumbnail} alt="" /> : null}
                {quote.icon === "video" ? (
                  <span className="media-play" aria-hidden="true">
                    <Play size={11} fill="currentColor" />
                  </span>
                ) : null}
              </span>
            ) : (
              <>
                {Glyph ? <Glyph size={13} aria-hidden="true" /> : null}
                <span className={quote.isStandIn ? "muted" : undefined}>{quote.text}</span>
              </>
            )}
          </span>
          {media ? <span className="reply-mini-cap">{quote.text}</span> : null}
        </button>
      </div>
      <button className="icon-btn reply-bar-close" type="button" aria-label="Cancel reply" title="Cancel reply (Esc)" onClick={onCancel}>
        <X size={16} />
      </button>
    </div>
  );
}
