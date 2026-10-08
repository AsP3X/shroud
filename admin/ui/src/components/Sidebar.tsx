import { Lock, LogOut } from "lucide-react";
import { NavLink } from "react-router-dom";
import type { Session } from "../api/types";
import { NAV } from "../nav";
import { Mark } from "./Mark";

function minutesLeft(expiresAt: string): number {
  return Math.max(0, Math.round((new Date(expiresAt).getTime() - Date.now()) / 60_000));
}

export function Sidebar({
  host,
  session,
  onSignOut,
  onNavigate,
}: {
  host: string;
  session: Session | null;
  onSignOut: () => void;
  onNavigate?: () => void;
}) {
  const name = session?.operator.name ?? "";
  const initials = name.slice(0, 2).toUpperCase() || "OP";
  return (
    <aside className="sidebar">
      <div className="brand">
        <Mark size={32} />
        <div className="brand__word">
          <div className="brand__line">
            <span className="brand__name">Shroud</span>
            <span className="admin-tag">ADMIN</span>
          </div>
          <span className="brand__host">{host}</span>
        </div>
      </div>

      <nav className="nav" aria-label="Pages">
        {NAV.map(({ to, label, icon: Icon }) => (
          <NavLink key={to} to={to} end={to === "/"} className="nav-item" onClick={onNavigate}>
            <Icon aria-hidden="true" />
            <span className="nav-item__label">{label}</span>
          </NavLink>
        ))}
      </nav>

      <div className="sidebar__spacer" />

      <div className="privacy-note">
        <div className="privacy-note__head">
          <Lock aria-hidden="true" />
          End-to-end sealed
        </div>
        <div className="privacy-note__body">
          Messages, media, usernames and device names are sealed on people's devices. Accounts appear here by ID
          only.
        </div>
      </div>

      {session ? (
        <div className="operator">
          <div className="operator__avatar" aria-hidden="true">
            {initials}
          </div>
          <div className="operator__text">
            <div className="operator__name">{name}</div>
            <div className="operator__detail">Session ends in {minutesLeft(session.expires_at)} min</div>
          </div>
          <button type="button" className="icon-button" onClick={onSignOut} aria-label="Sign out">
            <LogOut aria-hidden="true" />
          </button>
        </div>
      ) : null}
    </aside>
  );
}
