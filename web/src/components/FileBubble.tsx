import type { ReactNode } from "react";
import {
  ArrowDown,
  BookOpen,
  FileImage,
  FileQuestionMark,
  FileSpreadsheet,
  FileText,
  FileType,
  FileVideoCamera,
  Presentation,
  RotateCw,
  Smartphone,
  Square,
  TriangleAlert,
  type LucideIcon,
} from "lucide-react";
import { formatBytes } from "../format";
import {
  fileAccessibilityLabel,
  fileMetaLine,
  fileTypeOf,
  middleTruncationParts,
  NOT_SENT,
  UNSUPPORTED_FILE,
  warningLine,
  type FileCategory,
} from "../files";
import type { ChatMessage } from "../messaging";
import { useFileOnDevice } from "../media/fileTransfer";
import { ringFraction, useTransfer } from "../media/transfers";
import { LinkedText } from "./LinkedText";
import { ProgressRing } from "./ProgressRing";
import { thumbnailUrl } from "./ImageBubble";

const CATEGORY_ICONS: Record<FileCategory, LucideIcon> = {
  text: FileText,
  pdf: BookOpen,
  word: FileType,
  excel: FileSpreadsheet,
  powerpoint: Presentation,
  image: FileImage,
  video: FileVideoCamera,
  app: Smartphone,
};

/** The glyph a file's tile shows once it is on this device (bubble and composer alike). */
export function FileCategoryIcon({ category, size = 20 }: { category: FileCategory; size?: number }) {
  const Icon = CATEGORY_ICONS[category];
  return <Icon size={size} aria-hidden="true" />;
}

/** One line, cut in the middle: the stem ellipsizes, the extension never does (§5). */
export function FileName({ name }: { name: string }) {
  const { head, tail } = middleTruncationParts(name);
  return (
    <span className="file-name" title={name}>
      {head ? <span className="file-name-head">{head}</span> : null}
      <span className="file-name-tail">{tail}</span>
    </span>
  );
}

/**
 * A shared file (docs/file-sharing.md §7): a 44 px tile whose glyph says where the file is —
 * download arrow, progress ring with a stop glyph, its category, retry, or a question mark when
 * the type isn't supported — then the name, `{size} · {TYPE}` and the §6 warning, the caption,
 * and the time. Tapping downloads when needed and opens; the thread does the opening.
 */
export function FileBubble({
  message,
  className,
  query,
  quote,
  meta,
  footer,
  onOpen,
  onCancelDownload,
  onRetry,
}: {
  message: ChatMessage;
  className: string;
  query: string;
  quote?: ReactNode;
  /** Time and ticks, the bubble's last line (unless the reactions take it). */
  meta: ReactNode;
  /** The reaction chips. */
  footer?: ReactNode;
  onOpen: (message: ChatMessage) => void;
  onCancelDownload: (id: string) => void;
  /** Sends a failed file again; absent where there is nothing to resend. */
  onRetry?: (message: ChatMessage) => void;
}) {
  const transfer = useTransfer(message.id);
  const onDevice = useFileOnDevice(message.id);
  const name = message.fileName || "file";
  const type = fileTypeOf(name);
  const size = message.mediaBytes ?? null;
  const caption = message.caption?.trim() || "";
  const thumb = type ? thumbnailUrl(message) : null;

  const notSent = message.isMine && message.failed;
  const uploading = message.isMine && (message.pending || transfer?.direction === "up");
  const downloading = transfer?.direction === "down";
  const canTap = Boolean(type) && !(uploading && !downloading) && (!notSent || Boolean(onRetry));

  let glyph: ReactNode;
  let metaLine: string;
  if (!type) {
    glyph = <FileQuestionMark size={20} aria-hidden="true" />;
    metaLine = UNSUPPORTED_FILE;
  } else if (notSent) {
    glyph = <RotateCw size={20} aria-hidden="true" />;
    metaLine = NOT_SENT;
  } else if (uploading || downloading) {
    glyph = (
      <>
        <ProgressRing progress={ringFraction(transfer)} size={38} stroke={2.5} />
        <Square className="file-stop" size={11} fill="currentColor" strokeWidth={0} aria-hidden="true" />
      </>
    );
    // Bytes of the file itself, whichever way they move: the blob's header and tags are noise.
    const total = size ?? transfer?.total ?? 0;
    const moved =
      transfer?.phase === "preparing"
        ? 0
        : transfer?.phase === "finishing"
          ? total
          : transfer?.total
            ? Math.round((transfer.loaded / transfer.total) * total)
            : 0;
    metaLine = `${formatBytes(Math.min(total, moved))} of ${formatBytes(total)}`;
  } else {
    glyph = onDevice ? <FileCategoryIcon category={type.category} /> : <ArrowDown size={20} aria-hidden="true" />;
    metaLine = fileMetaLine(size, name);
  }

  const tileClass = [
    "file-tile",
    type ? "" : "is-unsupported",
    thumb ? "has-thumb" : "",
    uploading || downloading ? "is-busy" : "",
  ]
    .filter(Boolean)
    .join(" ");

  const body = (
    <>
      <span className={tileClass} aria-hidden="true">
        {thumb ? <img className="file-thumb" src={thumb} alt="" draggable={false} /> : null}
        <span className="file-glyph">{glyph}</span>
      </span>
      <span className="file-text">
        <FileName name={name} />
        <span className="file-meta">{metaLine}</span>
        {type?.warning ? (
          <span className="file-warning">
            <TriangleAlert size={12} aria-hidden="true" />
            {warningLine(type.warning)}
          </span>
        ) : null}
      </span>
    </>
  );

  return (
    <div className={`${className} file-msg${caption ? " has-caption" : ""}`}>
      {quote}
      {canTap ? (
        <button
          type="button"
          className="file-row"
          aria-label={fileAccessibilityLabel(name, size)}
          onClick={() => {
            if (notSent) onRetry?.(message);
            else if (downloading) onCancelDownload(message.id);
            else onOpen(message);
          }}
        >
          {body}
        </button>
      ) : (
        <div className="file-row" role="group" aria-label={fileAccessibilityLabel(name, size)}>
          {body}
        </div>
      )}
      {caption ? (
        <p className="bubble-text file-caption">
          <LinkedText text={caption} query={query} />
        </p>
      ) : null}
      {meta ? <div className="bubble-meta-row">{meta}</div> : null}
      {footer}
    </div>
  );
}
