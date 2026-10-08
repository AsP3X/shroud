import { Menu } from "lucide-react";
import { createContext, useCallback, useContext, useEffect, useRef, useState, type ReactNode } from "react";
import { Outlet, useLocation, useNavigate } from "react-router-dom";
import { ApiError, api } from "../api/client";
import type { Session } from "../api/types";
import { NAV } from "../nav";
import { Mark } from "./Mark";
import { Sidebar } from "./Sidebar";

interface SessionState {
  session: Session | null;
  refresh: () => Promise<void>;
}

const SessionContext = createContext<SessionState>({ session: null, refresh: async () => {} });

export function useSession(): SessionState {
  return useContext(SessionContext);
}

/** The sidebar, the phone top bar with its drawer, and the signed-in operator for every page. */
export function Shell() {
  const navigate = useNavigate();
  const location = useLocation();
  const [session, setSession] = useState<Session | null>(null);
  const [drawerOpen, setDrawerOpen] = useState(false);

  const refresh = useCallback(async () => {
    try {
      setSession(await api<Session>("/session"));
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) {
        navigate("/sign-in", { replace: true, state: { from: location.pathname } });
        return;
      }
      // The page itself reports an unreachable backend; the sidebar just has no operator to show.
      setSession(null);
    }
  }, [navigate, location.pathname]);

  useEffect(() => {
    void refresh();
    // Only on mount: pages refresh the session themselves after a re-authentication.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => setDrawerOpen(false), [location.pathname]);

  const title = NAV.find((entry) => (entry.to === "/" ? location.pathname === "/" : location.pathname.startsWith(entry.to)))?.label ?? "Shroud Admin";

  // Screen readers announce the document title on navigation; keep it current.
  useEffect(() => {
    document.title = `${title} · Shroud Admin`;
  }, [title]);

  // The drawer is modal: Escape closes it, focus starts on its first link and stays inside.
  const drawerRef = useRef<HTMLDivElement>(null);
  const menuButtonRef = useRef<HTMLButtonElement>(null);
  useEffect(() => {
    if (!drawerOpen) return;
    const drawer = drawerRef.current;
    const focusable = () => Array.from(drawer?.querySelectorAll<HTMLElement>("a[href], button:not([disabled])") ?? []);
    focusable()[0]?.focus();
    const onKey = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        setDrawerOpen(false);
        menuButtonRef.current?.focus();
        return;
      }
      if (event.key !== "Tab") return;
      const items = focusable();
      const first = items[0];
      const last = items[items.length - 1];
      if (!first || !last) return;
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first.focus();
      }
    };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [drawerOpen]);

  const signOut = async () => {
    await api<void>("/session", { method: "DELETE" });
    navigate("/sign-in", { replace: true });
  };

  const host = window.location.hostname;

  return (
    <SessionContext.Provider value={{ session, refresh }}>
      <a className="skip-link" href="#content">
        Skip to content
      </a>
      <div className="shell">
        <Sidebar host={host} session={session} onSignOut={signOut} />
        <header className="topbar">
          <Mark size={28} />
          <span className="topbar__title">{title}</span>
          <span className="admin-tag">ADMIN</span>
          <button ref={menuButtonRef} type="button" className="topbar__menu" aria-label="Menu" aria-expanded={drawerOpen} aria-controls="drawer" onClick={() => setDrawerOpen(true)}>
            <Menu aria-hidden="true" />
          </button>
        </header>
        <main className="main" id="content" tabIndex={-1}>
          <Outlet />
        </main>
        {drawerOpen ? (
          <div className="drawer" role="dialog" aria-modal="true" aria-label="Pages" id="drawer" ref={drawerRef}>
            <Sidebar host={host} session={session} onSignOut={signOut} onNavigate={() => setDrawerOpen(false)} />
            <button type="button" className="drawer__close" aria-label="Close menu" onClick={() => setDrawerOpen(false)} />
          </div>
        ) : null}
      </div>
    </SessionContext.Provider>
  );
}

export function Page({ children }: { children: ReactNode }) {
  return <>{children}</>;
}
