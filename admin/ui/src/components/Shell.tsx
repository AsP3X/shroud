import { Menu } from "lucide-react";
import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from "react";
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

  const signOut = async () => {
    await api<void>("/session", { method: "DELETE" });
    navigate("/sign-in", { replace: true });
  };

  const title = NAV.find((entry) => (entry.to === "/" ? location.pathname === "/" : location.pathname.startsWith(entry.to)))?.label ?? "Shroud Admin";
  const host = window.location.hostname;

  return (
    <SessionContext.Provider value={{ session, refresh }}>
      <div className="shell">
        <Sidebar host={host} session={session} onSignOut={signOut} />
        <header className="topbar">
          <Mark size={28} />
          <span className="topbar__title">{title}</span>
          <span className="admin-tag">ADMIN</span>
          <button type="button" className="topbar__menu" aria-label="Menu" onClick={() => setDrawerOpen(true)}>
            <Menu aria-hidden="true" />
          </button>
        </header>
        <main className="main">
          <Outlet />
        </main>
        {drawerOpen ? (
          <div className="drawer" role="dialog" aria-label="Pages">
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
