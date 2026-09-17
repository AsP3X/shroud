import { useCallback, useEffect, useRef, useState } from "react";
import { ChevronLeft } from "lucide-react";
import { api, ApiError, type Session } from "../api/client";
import type { IdentityMaterial } from "../crypto/identity";
import { clearCache } from "../crypto/plaintextCache";
import { DevicesView } from "./settings/DevicesView";
import { AppearanceView, DataStorageView, ServerView } from "./settings/PreferencesViews";
import { PrivacyView } from "./settings/PrivacyView";
import { SettingsHome } from "./settings/SettingsHome";
import { SETTINGS_TITLES, type SettingsRoute } from "./settings/routes";

export function SettingsPane({
  session,
  identity,
  shareLink,
  onLogout,
  onLockNow,
  onCacheCleared,
}: {
  session: Session;
  identity: IdentityMaterial | null;
  shareLink: string;
  onLogout: () => void;
  onLockNow: () => void;
  onCacheCleared: () => void;
}) {
  const [route, setRoute] = useState<SettingsRoute | null>(null);
  const [deviceCount, setDeviceCount] = useState<number | null>(null);
  const [confirmLogout, setConfirmLogout] = useState(false);

  const forceLogout = useCallback(() => {
    clearCache();
    onLogout();
  }, [onLogout]);
  const forceLogoutRef = useRef(forceLogout);
  forceLogoutRef.current = forceLogout;

  useEffect(() => {
    if (route !== null) return;
    let cancelled = false;
    api
      .devices(session.token)
      .then((res) => {
        if (!cancelled) setDeviceCount(res.devices.length);
      })
      .catch((err: unknown) => {
        if (cancelled) return;
        if (err instanceof ApiError && err.isAuthFailure) forceLogoutRef.current();
      });
    return () => {
      cancelled = true;
    };
  }, [session.token, route]);

  const back = useCallback(() => setRoute(null), []);

  useEffect(() => {
    function onKeyDown(event: KeyboardEvent) {
      if (event.key !== "Escape") return;
      if (confirmLogout) {
        setConfirmLogout(false);
        return;
      }
      if (route) back();
    }
    document.addEventListener("keydown", onKeyDown);
    return () => document.removeEventListener("keydown", onKeyDown);
  }, [route, back, confirmLogout]);

  return (
    <main className="settings" aria-label="Settings">
      {route ? (
        <>
          <header className="set-nav">
            <button type="button" className="icon-btn" onClick={back} aria-label="Back to settings">
              <ChevronLeft size={20} />
            </button>
            <h1>{SETTINGS_TITLES[route]}</h1>
          </header>
          <div className="set-body">
            {route === "devices" ? (
              <DevicesView
                session={session}
                onUnauthorized={forceLogout}
                onCount={setDeviceCount}
              />
            ) : null}
            {route === "privacy" ? (
              <PrivacyView session={session} onLockNow={onLockNow} onUnauthorized={forceLogout} />
            ) : null}
            {route === "data" ? <DataStorageView onCleared={onCacheCleared} /> : null}
            {route === "appearance" ? <AppearanceView /> : null}
            {route === "server" ? <ServerView /> : null}
          </div>
        </>
      ) : (
        <div className="set-body">
          <SettingsHome
            session={session}
            identity={identity}
            shareLink={shareLink}
            deviceCount={deviceCount}
            onNavigate={setRoute}
            onLogout={() => setConfirmLogout(true)}
          />
        </div>
      )}

      {confirmLogout ? (
        <div className="modal-scrim" onMouseDown={() => setConfirmLogout(false)}>
          <div
            className="modal"
            role="alertdialog"
            aria-modal="true"
            aria-labelledby="logout-title"
            onMouseDown={(event) => event.stopPropagation()}
          >
            <header>
              <h2 id="logout-title">Log out of Shroud?</h2>
            </header>
            <p>
              This ends your session on this browser and clears cached messages from local storage.
              You’ll need your password and encryption phrase to sign in again.
            </p>
            <div className="modal-actions">
              <button type="button" className="btn btn-secondary" onClick={() => setConfirmLogout(false)}>
                Cancel
              </button>
              <button type="button" className="btn btn-destructive" onClick={forceLogout}>
                Log Out
              </button>
            </div>
          </div>
        </div>
      ) : null}
    </main>
  );
}
