import { useRef, useState } from "react";
import { LogOut, MessageCircle, Settings, User, Users, type LucideIcon } from "lucide-react";
import { Avatar } from "./Avatar";
import { BrandMark } from "./BrandMark";
import { ContextMenu, type MenuAnchor, type MenuItem } from "./ContextMenu";
import { displayName } from "./settings/SettingsHome";

export type Tab = "chats" | "contacts" | "settings";

export const TABS: { id: Tab; label: string; Icon: LucideIcon }[] = [
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

type AccountAction = "profile" | "settings" | "logout";

const ACCOUNT_ITEMS: MenuItem<AccountAction>[] = [
  { id: "profile", label: "Profile", Icon: User },
  { id: "settings", label: "Settings", Icon: Settings },
  { id: "logout", label: "Log out", Icon: LogOut, danger: true, separatorBefore: true },
];

export function Rail({
  tab,
  onSelect,
  requestCount,
  user,
  onProfile,
  onLogout,
}: NavProps & {
  user: { id: string; username: string };
  /** Opens the profile sheet. */
  onProfile: () => void;
  /** Asks to log out (the caller confirms first). */
  onLogout: () => void;
}) {
  const me = useRef<HTMLButtonElement>(null);
  /** Where the account menu opens: beside the avatar, bottom edges aligned. */
  const [accountMenu, setAccountMenu] = useState<MenuAnchor | null>(null);

  function toggleAccountMenu() {
    if (accountMenu) {
      setAccountMenu(null);
      return;
    }
    const rect = me.current?.getBoundingClientRect();
    if (!rect) return;
    // Starts at the avatar's bottom edge; with no room below, the menu flips up from there.
    setAccountMenu({ x: rect.right + 10, y: rect.bottom });
  }

  function runAccountAction(action: AccountAction) {
    setAccountMenu(null);
    if (action === "profile") onProfile();
    else if (action === "settings") onSelect("settings");
    else onLogout();
  }

  return (
    <nav className="rail" aria-label="Main">
      <div className="rail-mark" aria-hidden="true">
        <BrandMark size={22} />
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
        ref={me}
        type="button"
        className="rail-me"
        onClick={toggleAccountMenu}
        aria-label={`Account — ${user.username}`}
        aria-haspopup="menu"
        aria-expanded={accountMenu !== null}
        title={user.username}
      >
        <Avatar name={user.username} seed={user.id} size="sm" />
      </button>
      {accountMenu ? (
        <ContextMenu
          anchor={accountMenu}
          items={ACCOUNT_ITEMS}
          label="Account"
          header={
            <>
              <Avatar name={displayName(user.username)} seed={user.id} size="sm" />
              <span className="ctx-menu-who">
                <strong>{displayName(user.username)}</strong>
                <span>@{user.username}</span>
              </span>
            </>
          }
          onSelect={runAccountAction}
          onClose={() => setAccountMenu(null)}
          trigger={me.current}
        />
      ) : null}
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
