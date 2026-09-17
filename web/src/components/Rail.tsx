import { MessageCircle, Settings, Shield, Users } from "lucide-react";
import { Avatar } from "./Avatar";

export type Tab = "chats" | "contacts" | "settings";

export const TABS: { id: Tab; label: string; Icon: typeof Shield }[] = [
  { id: "chats", label: "Chats", Icon: MessageCircle },
  { id: "contacts", label: "Contacts", Icon: Users },
  { id: "settings", label: "Settings", Icon: Settings },
];

type NavProps = {
  tab: Tab;
  onSelect: (tab: Tab) => void;
  requestCount: number;
};

function Badge({ count }: { count: number }) {
  if (count <= 0) return null;
  return (
    <span className="rail-badge">
      {count}
      <span className="sr-only"> pending requests</span>
    </span>
  );
}

export function Rail({
  tab,
  onSelect,
  requestCount,
  user,
}: NavProps & { user: { id: string; username: string } }) {
  return (
    <nav className="rail" aria-label="Main">
      <div className="rail-mark" aria-hidden="true">
        <Shield size={18} />
      </div>
      <div className="rail-tabs">
        {TABS.map(({ id, label, Icon }) => (
          <button
            key={id}
            type="button"
            className={tab === id ? "rail-btn active" : "rail-btn"}
            onClick={() => onSelect(id)}
            aria-label={label}
            aria-current={tab === id ? "page" : undefined}
            title={label}
          >
            <Icon size={20} />
            {id === "contacts" ? <Badge count={requestCount} /> : null}
          </button>
        ))}
      </div>
      <button
        type="button"
        className="rail-me"
        onClick={() => onSelect("settings")}
        aria-label={`${user.username} — settings`}
        title={user.username}
      >
        <Avatar name={user.username} seed={user.id} size="sm" />
      </button>
    </nav>
  );
}

export function TabBar({ tab, onSelect, requestCount }: NavProps) {
  return (
    <nav className="tab-bar" aria-label="Sections">
      {TABS.map(({ id, label, Icon }) => (
        <button
          key={id}
          type="button"
          className={tab === id ? "tab active" : "tab"}
          onClick={() => onSelect(id)}
          aria-current={tab === id ? "page" : undefined}
        >
          <span className="tab-icon">
            <Icon size={21} />
            {id === "contacts" ? <Badge count={requestCount} /> : null}
          </span>
          {label}
        </button>
      ))}
    </nav>
  );
}
