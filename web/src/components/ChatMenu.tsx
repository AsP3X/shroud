import { Bell, BellOff, CheckCheck } from "lucide-react";
import { MUTE_CHOICES } from "../notifications/mute";
import { ContextMenu, type MenuAnchor, type MenuItem } from "./ContextMenu";

/** `read`, `unmute`, or `mute:<seconds>` / `mute:forever`. */
export type ChatMenuAction = "read" | "unmute" | `mute:${string}`;

/** The seconds a `mute:` action asks for; null = until unmuted. */
export function muteSeconds(action: ChatMenuAction): number | null | undefined {
  if (!action.startsWith("mute:")) return undefined;
  const value = action.slice(5);
  return value === "forever" ? null : Number(value);
}

/**
 * A chat's own menu (right-click on its row, or Notifications in its info): mark it read, and
 * mute it for a while or until turned back on — Telegram's choices, on every device.
 */
export function ChatMenu({
  anchor,
  username,
  muted,
  unread,
  trigger,
  settle = false,
  onAction,
  onClose,
}: {
  anchor: MenuAnchor;
  username: string;
  muted: boolean;
  unread: number;
  trigger?: HTMLElement | null;
  settle?: boolean;
  onAction: (action: ChatMenuAction) => void;
  onClose: () => void;
}) {
  const items: MenuItem<ChatMenuAction>[] = [];
  if (unread > 0) items.push({ id: "read", label: "Mark as read", Icon: CheckCheck });
  if (muted) {
    items.push({ id: "unmute", label: "Unmute", Icon: Bell, separatorBefore: items.length > 0 });
  } else {
    MUTE_CHOICES.forEach((choice, index) => {
      items.push({
        id: `mute:${choice.seconds === null ? "forever" : choice.seconds}`,
        label: index === MUTE_CHOICES.length - 1 ? "Mute until I turn it back on" : `Mute ${choice.label.toLowerCase()}`,
        Icon: BellOff,
        separatorBefore: index === 0 && items.length > 0,
      });
    });
  }
  return (
    <ContextMenu
      anchor={anchor}
      items={items}
      label={`Chat with ${username}`}
      trigger={trigger}
      settle={settle}
      onSelect={onAction}
      onClose={onClose}
    />
  );
}
