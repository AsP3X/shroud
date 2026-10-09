import { useCallback, useEffect, useRef, useState } from "react";
import { ChevronLeft } from "lucide-react";
import { api, ApiError, sessionEndOf, type Session } from "../api/client";
import type { WipeReason } from "./DeviceWipeDialog";
import type { IdentityMaterial } from "../crypto/identity";
import { LogoutDialog } from "./LogoutDialog";
import { AboutView, LicensesView } from "./settings/AboutView";
import { DeleteAccountView } from "./settings/DeleteAccountView";
import { DevicesView } from "./settings/DevicesView";
import { NotificationsView, type MutedChat } from "./settings/NotificationsView";
import { AppearanceView, DataStorageView, ServerView } from "./settings/PreferencesViews";
import { PrivacyView } from "./settings/PrivacyView";
import { SettingsHome } from "./settings/SettingsHome";
import { SETTINGS_PARENTS, SETTINGS_TITLES, type SettingsRoute } from "./settings/routes";

export function SettingsPane({
  session,
  identity,
  shareLink,
  onLogout,
  onSessionEnded,
  onLockNow,
  onShowQr,
  onCacheCleared,
  onShareCodeChanged,
  mutedChats,
  onUnmute,
  onDismissLocked,
}: {
  session: Session;
  identity: IdentityMaterial | null;
  shareLink: string;
  /** Confirmed "Log Out": the shell clears this browser. */
  onLogout: () => void;
  /** The server no longer accepts the session: the same clearing, without asking. */
  onSessionEnded: (reason?: WipeReason) => void;
  onLockNow: () => void;
  onShowQr: () => void;
  onCacheCleared: () => void;
  /** Settings → Privacy made a new share code: the QR code and invite link change with it. */
  onShareCodeChanged: (shareCode: string) => void;
  /** For Notifications and Sounds: the chats muted now, and a way to unmute one. */
  mutedChats: MutedChat[];
  onUnmute: (peerId: string) => Promise<void>;
  /** Delete Account is running: the shell must not switch away from settings. */
  onDismissLocked: (locked: boolean) => void;
}) {
  const [route, setRoute] = useState<SettingsRoute | null>(null);
  const [deviceCount, setDeviceCount] = useState<number | null>(null);
  const [confirmLogout, setConfirmLogout] = useState(false);
  /** Delete Account's request is in flight: Back and Escape must not leave the screen.
   * The ref updates in the same turn as the request, before React disables the button. */
  const [dismissLocked, setDismissLocked] = useState(false);
  const dismissLockedRef = useRef(false);

  const forceLogout = useCallback(
    (err?: unknown) => onSessionEnded(err instanceof ApiError ? sessionEndOf(err) : "ended"),
    [onSessionEnded],
  );
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
        if (err instanceof ApiError && err.isAuthFailure) forceLogoutRef.current(err);
      });
    return () => {
      cancelled = true;
    };
  }, [session.token, route]);

  const back = useCallback(() => {
    if (dismissLockedRef.current) return;
    setRoute((current) => (current && SETTINGS_PARENTS[current]) ?? null);
  }, []);
  const parent = route ? SETTINGS_PARENTS[route] : undefined;

  const lockDismiss = useCallback(
    (locked: boolean) => {
      dismissLockedRef.current = locked;
      setDismissLocked(locked);
      onDismissLocked(locked);
    },
    [onDismissLocked],
  );

  useEffect(() => {
    if (route === "delete-account") return;
    dismissLockedRef.current = false;
    setDismissLocked(false);
    onDismissLocked(false);
  }, [route, onDismissLocked]);

  useEffect(() => {
    function onKeyDown(event: KeyboardEvent) {
      if (event.key !== "Escape") return;
      // The logout dialog handles its own Escape; never leave the page behind it.
      // Delete Account swallows Escape while its request runs.
      if (confirmLogout || dismissLockedRef.current) return;
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
            <button
              type="button"
              className="icon-btn"
              onClick={back}
              disabled={dismissLocked}
              aria-label={parent ? `Back to ${SETTINGS_TITLES[parent]}` : "Back to settings"}
            >
              <ChevronLeft size={20} />
            </button>
            <h1>{SETTINGS_TITLES[route]}</h1>
          </header>
          {/* Keyed: a page opened from another one starts at its top. */}
          <div className="set-body" key={route}>
            {route === "notifications" ? (
              <NotificationsView
                session={session}
                mutedChats={mutedChats}
                onUnmute={onUnmute}
                onUnauthorized={forceLogout}
              />
            ) : null}
            {route === "devices" ? (
              <DevicesView
                session={session}
                onUnauthorized={forceLogout}
                onCount={setDeviceCount}
              />
            ) : null}
            {route === "privacy" ? (
              <PrivacyView
                session={session}
                onLockNow={onLockNow}
                onShareCodeChanged={onShareCodeChanged}
                onUnauthorized={forceLogout}
                onDeleteAccount={() => setRoute("delete-account")}
              />
            ) : null}
            {route === "delete-account" ? (
              <DeleteAccountView
                session={session}
                onCancel={back}
                onAccountDeleted={() => onSessionEnded("accountDeleted")}
                onUnauthorized={forceLogout}
                onBusyChange={lockDismiss}
              />
            ) : null}
            {route === "data" ? <DataStorageView onCleared={onCacheCleared} /> : null}
            {route === "appearance" ? <AppearanceView /> : null}
            {route === "server" ? <ServerView /> : null}
            {route === "about" ? <AboutView onNavigate={setRoute} /> : null}
            {route === "licenses" ? <LicensesView /> : null}
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
            onShowQr={onShowQr}
          />
        </div>
      )}

      {confirmLogout ? (
        <LogoutDialog
          onCancel={() => setConfirmLogout(false)}
          onConfirm={() => {
            setConfirmLogout(false);
            onLogout();
          }}
        />
      ) : null}
    </main>
  );
}
