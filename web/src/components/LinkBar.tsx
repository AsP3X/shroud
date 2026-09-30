import { useState } from "react";
import { ArrowDownToLine, ArrowUpToLine, Link, Maximize2, Minimize2, Trash2, X } from "lucide-react";
import { ContextMenu, type MenuAnchor, type MenuItem } from "./ContextMenu";
import type { LinkBarState } from "../linkPreview/useLinkPreviewComposer";

type LinkOption = "above" | "size" | "remove";

/**
 * The link preview strip above the composer (`LinkBar` / `LinkBar · loading` in
 * design/webclient.pen) — the reply bar's sibling: same glyph column, stripe and ✕, so a quote
 * and a preview read as the same kind of attachment. Clicking the preview opens Telegram's
 * options (`LinkOptionsMenu`): show it above or below the text, a larger or smaller picture, or
 * no preview. ✕ drops the preview for this link; the text is left alone.
 */
export function LinkBar({
  state,
  showsAboveText,
  canToggleImageSize,
  usesLargeImage,
  onToggleAboveText,
  onToggleImageSize,
  onRemove,
}: {
  state: LinkBarState;
  showsAboveText: boolean;
  canToggleImageSize: boolean;
  usesLargeImage: boolean;
  onToggleAboveText: () => void;
  onToggleImageSize: () => void;
  onRemove: () => void;
}) {
  const [menu, setMenu] = useState<MenuAnchor | null>(null);
  const ready = state.kind === "ready";
  const title = ready ? state.title : "Loading preview…";
  const snippet = ready ? state.snippet : state.url;
  const muted = !ready || state.snippetIsLink;

  const items: MenuItem<LinkOption>[] = [
    showsAboveText
      ? { id: "above", label: "Show below text", Icon: ArrowDownToLine }
      : { id: "above", label: "Show above text", Icon: ArrowUpToLine },
    ...(canToggleImageSize
      ? [
          usesLargeImage
            ? { id: "size" as const, label: "Smaller image", Icon: Minimize2 }
            : { id: "size" as const, label: "Larger image", Icon: Maximize2 },
        ]
      : []),
    { id: "remove", label: "Remove preview", Icon: Trash2, danger: true, separatorBefore: true },
  ];

  return (
    <div className="reply-bar link-bar">
      <Link className="reply-bar-glyph" size={18} aria-hidden="true" />
      <button
        type="button"
        className="reply-quote reply-quote-bar"
        disabled={!ready}
        aria-haspopup="menu"
        aria-label={ready ? `Link preview: ${title}, ${snippet}. Options` : `Loading link preview for ${snippet}`}
        onClick={(event) => {
          const box = event.currentTarget.getBoundingClientRect();
          setMenu({ x: box.left, y: box.top });
        }}
      >
        <span className="reply-quote-lines">
          <strong>{title}</strong>
          <span className={muted ? "reply-quote-text muted" : "reply-quote-text"}>{snippet}</span>
        </span>
      </button>
      <button className="icon-btn reply-bar-close" type="button" aria-label="Remove link preview" onClick={onRemove}>
        <X size={18} />
      </button>
      {menu ? (
        <ContextMenu
          anchor={menu}
          items={items}
          label="Link preview options"
          onSelect={(option) => {
            setMenu(null);
            if (option === "above") onToggleAboveText();
            else if (option === "size") onToggleImageSize();
            else onRemove();
          }}
          onClose={() => setMenu(null)}
        />
      ) : null}
    </div>
  );
}
