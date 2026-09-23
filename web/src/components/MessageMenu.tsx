import { useEffect, useRef, type ReactNode } from "react";
import { Copy, ExternalLink, Link, Reply, Trash2 } from "lucide-react";
import { isUnsent, type ChatMessage } from "../messaging";
import { ContextMenu, type MenuAnchor, type MenuItem } from "./ContextMenu";
export { suppressClickAfterLongPress } from "./ContextMenu";

export type MessageMenuAction = "openLink" | "copyLink" | "reply" | "copy" | "delete";

/** Where the menu should appear: the pointer, or the bubble when opened from the keyboard. */
export type MessageMenuAnchor = MenuAnchor;

/**
 * Right-click (or long-press, or Shift+F10) menu for one message — the web's counterpart to the
 * iOS long-press menu, with the actions the web client can actually perform.
 */
export function MessageMenu({
  anchor,
  actions,
  copyLabel,
  settle = false,
  header,
  onAction,
  onClose,
}: {
  anchor: MessageMenuAnchor;
  /** Above the actions: the reaction row. */
  header?: ReactNode;
  actions: MessageMenuAction[];
  /** "Copy text", or "Copy selection" when part of the bubble is selected. */
  copyLabel?: string;
  /** Opened by a finger that is still down — the release must not activate an item. */
  settle?: boolean;
  onAction: (action: MessageMenuAction) => void;
  onClose: () => void;
}) {
  // Link actions come first when the message has a link (`MessageMenu · link` in the design),
  // set apart from the message's own actions.
  const firstMessageAction = actions.find((action) => action !== "openLink" && action !== "copyLink");
  const afterLinks = actions.includes("copyLink");
  const all: Record<MessageMenuAction, MenuItem<MessageMenuAction>> = {
    openLink: { id: "openLink", label: "Open link", Icon: ExternalLink },
    copyLink: { id: "copyLink", label: "Copy link", Icon: Link },
    reply: { id: "reply", label: "Reply", Icon: Reply, separatorBefore: afterLinks && firstMessageAction === "reply" },
    copy: {
      id: "copy",
      label: copyLabel ?? "Copy text",
      Icon: Copy,
      separatorBefore: afterLinks && firstMessageAction === "copy",
    },
    // Set apart, and red, like every destructive menu action.
    delete: { id: "delete", label: "Delete", Icon: Trash2, danger: true, separatorBefore: actions.length > 1 },
  };
  return (
    <ContextMenu
      anchor={anchor}
      items={actions.map((action) => all[action])}
      label="Message actions"
      header={header}
      settle={settle}
      onSelect={onAction}
      onClose={onClose}
    />
  );
}

/** How the dialog names the message: its words (trimmed), or what kind of message it is. */
function describe(message: ChatMessage, preview: string): string {
  const words = preview.replace(/\s+/g, " ").trim();
  if (words) return `“${words.length > 60 ? `${words.slice(0, 59).trimEnd()}…` : words}”`;
  switch (message.kind) {
    case "image":
      return "This photo";
    case "video":
      return "This video";
    case "voice":
      return "This voice message";
    default:
      return "This message";
  }
}

/**
 * Asks which way to delete, the way iOS does: "for everyone" only for our own messages that
 * reached the server (the server refuses anything else), "for me" always. A message that never
 * left this browser just goes, with one button.
 */
export function DeleteMessageDialog({
  message,
  peerName,
  preview,
  onCancel,
  onDelete,
}: {
  message: ChatMessage;
  peerName: string;
  /** The message's own words, if it has any. */
  preview: string;
  onCancel: () => void;
  onDelete: (scope: "me" | "everyone") => void;
}) {
  const cancel = useRef<HTMLButtonElement>(null);
  const close = useRef(onCancel);
  close.current = onCancel;

  /* The safe choice has focus; Escape backs out without also dropping a reply in progress. */
  useEffect(() => {
    cancel.current?.focus();
    const onKeyDown = (event: globalThis.KeyboardEvent) => {
      if (event.key !== "Escape") return;
      event.preventDefault();
      // Immediate: the thread's own Escape (cancel reply) listens on the same window.
      event.stopImmediatePropagation();
      close.current();
    };
    window.addEventListener("keydown", onKeyDown, true);
    return () => window.removeEventListener("keydown", onKeyDown, true);
  }, []);

  const localOnly = isUnsent(message);
  const forEveryone = message.isMine && !message.deleted && !localOnly;
  const subject = describe(message, preview);
  const body = localOnly
    ? `${subject} never reached ${peerName}, so it is only removed from this browser.`
    : forEveryone
      ? `${subject} will be removed from this chat. Deleting for everyone also removes it for ${peerName}.`
      : `${subject} will be removed from your chats. ${peerName} keeps their copy.`;

  return (
    <div className="modal-scrim" onMouseDown={() => close.current()}>
      <div
        className="modal"
        role="alertdialog"
        aria-modal="true"
        aria-labelledby="delete-message-title"
        aria-describedby="delete-message-body"
        onMouseDown={(event) => event.stopPropagation()}
      >
        <header>
          <h2 id="delete-message-title">Delete message?</h2>
        </header>
        <p id="delete-message-body">{body}</p>
        <div className="modal-actions stacked">
          {forEveryone ? (
            <button type="button" className="btn btn-destructive" onClick={() => onDelete("everyone")}>
              Delete for everyone
            </button>
          ) : null}
          <button
            type="button"
            className={forEveryone ? "btn btn-secondary danger-text" : "btn btn-destructive"}
            onClick={() => onDelete("me")}
          >
            {localOnly ? "Delete" : "Delete for me"}
          </button>
          <button ref={cancel} type="button" className="btn btn-secondary" onClick={() => close.current()}>
            Cancel
          </button>
        </div>
      </div>
    </div>
  );
}
