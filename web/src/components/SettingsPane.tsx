import { useCallback, useEffect, useRef, useState } from "react";
import { ChevronLeft, ChevronRight } from "lucide-react";
import { api, ApiError, sessionEndOf, type Session } from "../api/client";
import { webVersion } from "../appVersion";
import type { WipeReason } from "./DeviceWipeDialog";
import type { IdentityMaterial } from "../crypto/identity";
import { DEVICE_LIMIT } from "../deviceLimit";
import { displayName } from "../format";
import { useNotificationPrefs } from "../notifications/prefs";
import { useThemePref } from "../theme";
import { Avatar } from "./Avatar";
import { LogoutDialog } from "./LogoutDialog";
import { AboutView, LicensesView, serverHost, useUpdateSnapshot } from "./settings/AboutView";
import { AccountView } from "./settings/AccountView";
import { DeleteAccountView } from "./settings/DeleteAccountView";
import { DevicesView } from "./settings/DevicesView";
import { notificationsSummary, NotificationsView, type MutedChat } from "./settings/NotificationsView";
import { AppearanceView, DataStorageView, themeLabel } from "./settings/PreferencesViews";
import { PrivacyView } from "./settings/PrivacyView";
import {
  SETTINGS_INTROS,
  SETTINGS_PARENTS,
  SETTINGS_SECTIONS,
  SETTINGS_TITLES,
  sectionOf,
  type SettingsRoute,
  type SettingsSection,
} from "./settings/routes";

const PHONE = "(max-width: 900px)";

/**
 * Settings as a list and a page, like the chats: the sections down the pane, the open one on
 * the right. On a phone the list comes first and a section opens over it with Back.
 */
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
  /** Confirmed "Log out": the shell clears this browser. */
  onLogout: () => void;
  /** The server no longer accepts the session: the same clearing, without asking. */
  onSessionEnded: (reason?: WipeReason) => void;
  onLockNow: () => void;
  onShowQr: () => void;
  onCacheCleared: () => void;
  /** Settings → Account made a new share code: the QR code and invite link change with it. */
  onShareCodeChanged: (shareCode: string) => void;
  /** For Notifications and sounds: the chats muted now, and a way to unmute one. */
  mutedChats: MutedChat[];
  onUnmute: (peerId: string) => Promise<void>;
  /** Delete account is running: the shell must not switch away from settings. */
  onDismissLocked: (locked: boolean) => void;
}) {
  const [route, setRoute] = useState<SettingsRoute>("account");
  /* A phone shows the list until a section is picked, and again after Back. */
  const [listShown, setListShown] = useState(() => window.matchMedia(PHONE).matches);
  const [deviceCount, setDeviceCount] = useState<number | null>(null);
  const [confirmLogout, setConfirmLogout] = useState(false);
  /** Delete account's request is in flight: Back and Escape must not leave the screen.
   * The ref updates in the same turn as the request, before React disables the button. */
  const [dismissLocked, setDismissLocked] = useState(false);
  const dismissLockedRef = useRef(false);
  const pref = useThemePref();
  // Re-renders the Notifications row's line when it changes.
  useNotificationPrefs();
  const { status: updateStatus } = useUpdateSnapshot();

  const forceLogout = useCallback(
    (err?: unknown) => onSessionEnded(err instanceof ApiError ? sessionEndOf(err) : "ended"),
    [onSessionEnded],
  );
  const forceLogoutRef = useRef(forceLogout);
  forceLogoutRef.current = forceLogout;

  /* The device count for the Devices row's line, while the Devices page is not open to count them. */
  useEffect(() => {
    if (route === "devices") return;
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

  const open = useCallback((section: SettingsSection) => {
    setRoute(section);
    setListShown(false);
  }, []);
  const parent = SETTINGS_PARENTS[route];
  /* Back: up to the section a page sits under, else (on a phone) to the list. */
  const back = useCallback(() => {
    if (dismissLockedRef.current) return;
    if (parent) setRoute(parent);
    else setListShown(true);
  }, [parent]);

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
      // Delete account swallows Escape while its request runs.
      if (confirmLogout || dismissLockedRef.current) return;
      if (parent || window.matchMedia(PHONE).matches) back();
    }
    document.addEventListener("keydown", onKeyDown);
    return () => document.removeEventListener("keydown", onKeyDown);
  }, [parent, back, confirmLogout]);

  const lines: Record<SettingsSection, string> = {
    account: `@${session.user.username}`,
    devices: deviceCount === null ? "Signed in to your account" : `${deviceCount} of ${DEVICE_LIMIT} in use`,
    notifications: notificationsSummary(),
    appearance: `${themeLabel(pref)} theme`,
    privacy: "Locking, what contacts see, blocked",
    data: "What this browser keeps",
    about: updateStatus === "available" ? "Update available" : `Shroud ${webVersion()} · ${serverHost()}`,
  };
  const active = sectionOf(route);
  const intro = SETTINGS_INTROS[route];

  return (
    <>
      <section className={`pane${listShown ? "" : " hidden-mobile"}`} aria-label="Settings">
        <header className="pane-head">
          <div className="pane-title-row">
            <h1>Settings</h1>
          </div>
        </header>
        <div className="rows" role="list">
          <div className="row set-list-me" role="listitem">
            <Avatar name={displayName(session.user.username)} seed={session.user.id} size="md" />
            <span className="row-copy">
              <strong>
                <span>{displayName(session.user.username)}</span>
              </strong>
              <span>@{session.user.username}</span>
            </span>
          </div>
          {SETTINGS_SECTIONS.map(({ id, Icon }) => (
            <button
              key={id}
              type="button"
              role="listitem"
              className={`row row-button${active === id && !listShown ? " active" : ""}`}
              aria-current={active === id ? "page" : undefined}
              onClick={() => open(id)}
            >
              <span className="set-list-ico" aria-hidden="true">
                <Icon size={18} />
              </span>
              <span className="row-copy">
                <strong>
                  <span>{SETTINGS_TITLES[id]}</span>
                </strong>
                <span>{lines[id]}</span>
              </span>
              <ChevronRight size={16} className="set-chevron" aria-hidden="true" />
            </button>
          ))}
        </div>
      </section>

      <main className={`set-view${listShown ? " hidden-mobile" : ""}`} aria-label={SETTINGS_TITLES[route]}>
        <header className="thread-head">
          <button
            type="button"
            className={parent ? "icon-btn" : "icon-btn back-mobile"}
            onClick={back}
            disabled={dismissLocked}
            aria-label={parent ? `Back to ${SETTINGS_TITLES[parent]}` : "Back to settings"}
          >
            <ChevronLeft size={20} />
          </button>
          <div className="thread-peer">
            <strong>
              <span>Settings</span>
            </strong>
          </div>
        </header>
        {/* Keyed: a page opened from another one starts at its top. */}
        <div className="set-body" key={route}>
          {intro ? (
            <div className="set-head">
              <h2>{SETTINGS_TITLES[route]}</h2>
              <p>{intro}</p>
            </div>
          ) : null}
          {route === "account" ? (
            <AccountView
              session={session}
              shareLink={shareLink}
              onShowQr={onShowQr}
              onShareCodeChanged={onShareCodeChanged}
              onLogout={() => setConfirmLogout(true)}
              onDeleteAccount={() => setRoute("delete-account")}
              onUnauthorized={forceLogout}
            />
          ) : null}
          {route === "devices" ? (
            <DevicesView session={session} onUnauthorized={forceLogout} onCount={setDeviceCount} />
          ) : null}
          {route === "notifications" ? (
            <NotificationsView
              session={session}
              mutedChats={mutedChats}
              onUnmute={onUnmute}
              onUnauthorized={forceLogout}
            />
          ) : null}
          {route === "appearance" ? <AppearanceView /> : null}
          {route === "privacy" ? (
            <PrivacyView session={session} identity={identity} onLockNow={onLockNow} onUnauthorized={forceLogout} />
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
          {route === "about" ? <AboutView onNavigate={setRoute} /> : null}
          {route === "licenses" ? <LicensesView /> : null}
        </div>
      </main>

      {confirmLogout ? (
        <LogoutDialog
          onCancel={() => setConfirmLogout(false)}
          onConfirm={() => {
            setConfirmLogout(false);
            onLogout();
          }}
        />
      ) : null}
    </>
  );
}
