import { useCallback, useEffect, useState, type KeyboardEvent } from "react";
import { BellRing, Check, Send } from "lucide-react";
import { api, ApiError, type ChatMute, type Session } from "../../api/client";
import { muteLabel } from "../../notifications/mute";
import {
  loadNotificationPrefs,
  updateNotificationPrefs,
  useNotificationPrefs,
  type NotificationPrefs,
  type SoundId,
} from "../../notifications/prefs";
import {
  disableNotifications,
  enableNotifications,
  hasPushSubscription,
  notificationPermission,
  pushSettingsToServer,
  pushSupported,
  type Permission,
} from "../../notifications/push";
import { playSound, SOUND_CHOICES } from "../../notifications/sounds";
import { Avatar } from "../Avatar";
import { SettingsGroup, SettingsNote, Switch } from "./SettingsRow";

export type MutedChat = { peerId: string; username: string; mute: ChatMute };

/** Preferences the server acts on while this browser is closed: saved there as they change. */
const SERVER_KEYS: readonly (keyof NotificationPrefs)[] = [
  "showSender",
  "reactions",
  "contactRequests",
  "sound",
  "badge",
  "badgeIncludesMuted",
];

/** iPhone and iPad (iPadOS reports a Mac): push only reaches Shroud added to the Home Screen. */
function appleMobile(): boolean {
  if (typeof navigator === "undefined") return false;
  return /iPhone|iPad|iPod/.test(navigator.userAgent) || (navigator.platform === "MacIntel" && navigator.maxTouchPoints > 1);
}

const TEST_RESULTS: Record<string, string> = {
  sent: "Sent through your browser's push service — it should appear in a moment.",
  not_registered: "This browser is not registered for pushes yet. Turn notifications off and on again.",
  not_configured: "This server cannot send Web Push right now.",
  misconfigured:
    "The push service refused this server's key. Whoever runs the server needs to check its Web Push settings.",
  rejected: "The push service no longer accepts this browser's subscription. Turn notifications off and on again.",
  failed: "The push service could not be reached. Try again in a moment.",
};

export function NotificationsView({
  session,
  mutedChats,
  onUnmute,
  onUnauthorized,
}: {
  session: Session;
  mutedChats: MutedChat[];
  onUnmute: (peerId: string) => Promise<void>;
  /** `err` tells a removed device (`DEVICE_REMOVED`) from a session that merely ended. */
  onUnauthorized: (err?: unknown) => void;
}) {
  const prefs = useNotificationPrefs();
  const [permission, setPermission] = useState<Permission>(() => notificationPermission());
  const [busy, setBusy] = useState(false);
  const [openOnly, setOpenOnly] = useState(false);
  const [testing, setTesting] = useState(false);
  const [testResult, setTestResult] = useState<string | null>(null);
  const [unmuting, setUnmuting] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const on = prefs.enabled && permission === "granted";

  // On, but without a push subscription: this browser has no push service (or refused one),
  // so notifications only come while Shroud is open. Not while turning on: the subscription
  // is still on its way.
  useEffect(() => {
    if (!on || busy) return;
    let cancelled = false;
    void hasPushSubscription().then((subscribed) => {
      if (!cancelled) setOpenOnly(!subscribed);
    });
    return () => {
      cancelled = true;
    };
  }, [on, busy]);

  // Permission can change in the browser's own settings while this page is open.
  useEffect(() => {
    const refresh = () => setPermission(notificationPermission());
    window.addEventListener("focus", refresh);
    document.addEventListener("visibilitychange", refresh);
    return () => {
      window.removeEventListener("focus", refresh);
      document.removeEventListener("visibilitychange", refresh);
    };
  }, []);

  const failed = useCallback(
    (err: unknown, fallback: string) => {
      if (err instanceof ApiError && err.isAuthFailure) {
        onUnauthorized(err);
        return;
      }
      setError(err instanceof ApiError ? err.message : fallback);
    },
    [onUnauthorized],
  );

  async function toggleEnabled(next: boolean) {
    setBusy(true);
    setError(null);
    setTestResult(null);
    try {
      if (next) {
        const outcome = await enableNotifications(session.token);
        setPermission(notificationPermission());
        setOpenOnly(outcome === "open-only");
        if (outcome === "denied") {
          setError("Your browser blocked notifications for Shroud. Allow them in the site settings, then turn this on again.");
        } else if (outcome === "dismissed") {
          setError("The browser's question was closed without an answer. Turn this on again to answer it.");
        } else if (outcome === "unsupported") {
          setError("This browser cannot show notifications.");
        }
      } else {
        await disableNotifications(session.token);
        setOpenOnly(false);
      }
    } catch (err) {
      failed(err, "Could not change notifications.");
    } finally {
      setBusy(false);
    }
  }

  function change<K extends keyof NotificationPrefs>(key: K, value: NotificationPrefs[K]) {
    updateNotificationPrefs({ [key]: value } as Partial<NotificationPrefs>);
    setError(null);
    if (!SERVER_KEYS.includes(key)) return;
    void pushSettingsToServer(session.token).catch((err: unknown) =>
      failed(err, "Saved here, but the server did not get the change. It retries next time you unlock."),
    );
  }

  function chooseSound(id: SoundId) {
    change("sound", id);
    playSound(id, { preview: true });
  }

  /** Arrow keys move through the sounds, as in any radio group; Tab leaves it. */
  function onSoundKey(event: KeyboardEvent<HTMLDivElement>) {
    const step =
      event.key === "ArrowDown" || event.key === "ArrowRight"
        ? 1
        : event.key === "ArrowUp" || event.key === "ArrowLeft"
          ? -1
          : 0;
    if (!step) return;
    event.preventDefault();
    const count = SOUND_CHOICES.length;
    const index = (SOUND_CHOICES.findIndex((choice) => choice.id === prefs.sound) + step + count) % count;
    chooseSound(SOUND_CHOICES[index].id);
    event.currentTarget.querySelectorAll<HTMLButtonElement>('[role="radio"]')[index]?.focus();
  }

  async function sendTest() {
    setTesting(true);
    setTestResult(null);
    setError(null);
    try {
      const outcome = await api.testPush(session.token);
      const text = TEST_RESULTS[outcome.status] ?? TEST_RESULTS.failed;
      // The push service's own words help whoever fixes the server.
      setTestResult(outcome.status === "misconfigured" && outcome.detail ? `${text} (${outcome.detail})` : text);
    } catch (err) {
      failed(err, "Could not send a test notification.");
    } finally {
      setTesting(false);
    }
  }

  async function unmute(peerId: string) {
    setUnmuting(peerId);
    setError(null);
    try {
      await onUnmute(peerId);
    } catch (err) {
      failed(err, "Could not unmute that chat.");
    } finally {
      setUnmuting(null);
    }
  }

  const blocked = permission === "denied";
  const unsupported = permission === "unsupported";
  const pushMissing = !pushSupported();

  return (
    <>
      <SettingsGroup title="This browser">
        <Switch
          checked={on}
          disabled={busy || unsupported || (blocked && !on)}
          onChange={(next) => void toggleEnabled(next)}
          label="Show notifications"
          description={
            unsupported
              ? appleMobile()
                ? "Safari shows notifications only for Shroud on your Home Screen: tap Share, then Add to Home Screen, and open it from there."
                : "This browser cannot show notifications."
              : blocked
                ? "Blocked for this site. Allow notifications in your browser's site settings, then turn this on."
                : "New messages notify you while Shroud is open and, through your browser's push service, while it is closed or locked. The push service only sees that something arrived."
          }
        />
        <button
          type="button"
          className="set-row set-row-button"
          disabled={!on || testing || openOnly || pushMissing}
          onClick={() => void sendTest()}
        >
          <span className="set-tile" style={{ background: "#2e8fe0" }} aria-hidden="true">
            <Send size={15} />
          </span>
          <span className="set-row-copy">
            <strong>{testing ? "Sending…" : "Send a test notification"}</strong>
          </span>
        </button>
      </SettingsGroup>
      {testResult ? <SettingsNote>{testResult}</SettingsNote> : null}
      {on && (openOnly || pushMissing) ? (
        <SettingsNote>
          This browser cannot receive notifications while Shroud is closed. On iPhone and iPad, add
          Shroud to your Home Screen and turn notifications on there.
        </SettingsNote>
      ) : null}

      <SettingsGroup title="Message notifications">
        <Switch
          checked={prefs.showSender}
          disabled={!on}
          onChange={(next) => change("showSender", next)}
          label="Show who it's from"
          description="Off, a notification only says that a message arrived."
        />
        <Switch
          checked={prefs.showPreview}
          disabled={!on}
          onChange={(next) => change("showPreview", next)}
          label="Show message text"
          description="Only in notifications this tab shows while Shroud is unlocked: your system may keep them in its notification centre, outside Shroud's encrypted storage. Notifications that arrive while Shroud is closed never contain text — the server cannot read it."
        />
      </SettingsGroup>

      <SettingsGroup title="Also notify me about">
        <Switch
          checked={prefs.reactions}
          disabled={!on}
          onChange={(next) => change("reactions", next)}
          label="Reactions to my messages"
        />
        <Switch
          checked={prefs.contactRequests}
          disabled={!on}
          onChange={(next) => change("contactRequests", next)}
          label="Contact requests"
        />
      </SettingsGroup>

      <SettingsGroup title="Sound">
        <div className="set-sounds" role="radiogroup" aria-label="Notification sound" onKeyDown={onSoundKey}>
          {SOUND_CHOICES.map(({ id, label }) => (
            <button
              key={id}
              type="button"
              role="radio"
              aria-checked={prefs.sound === id}
              tabIndex={prefs.sound === id ? 0 : -1}
              className="set-row set-row-button"
              onClick={() => chooseSound(id)}
            >
              <span className="set-row-copy">
                <strong>{label}</strong>
              </span>
              {prefs.sound === id ? (
                <Check size={17} className="set-check" aria-hidden="true" />
              ) : null}
            </button>
          ))}
        </div>
      </SettingsGroup>
      <SettingsNote>
        Plays while Shroud is open. Notifications from your system use its own sound; None keeps
        them silent too.
      </SettingsNote>

      <SettingsGroup title="Badge">
        <Switch
          checked={prefs.badge}
          onChange={(next) => change("badge", next)}
          label="Unread count"
          description="In the tab's title and icon, and on Shroud's app icon once it is installed."
        />
        <Switch
          checked={prefs.badgeIncludesMuted}
          onChange={(next) => change("badgeIncludesMuted", next)}
          label="Count muted chats"
          description="Also in the count on the Chats tab."
        />
      </SettingsGroup>

      <SettingsGroup title="Muted chats">
        {mutedChats.length === 0 ? (
          <p className="set-placeholder">
            No chats are muted. Right-click a chat, or open its info, to mute it.
          </p>
        ) : (
          mutedChats.map((chat) => (
            <div key={chat.peerId} className="set-row">
              <Avatar name={chat.username} seed={chat.peerId} size="sm" />
              <span className="set-row-copy">
                <strong>{chat.username}</strong>
                <span>{muteLabel(chat.mute)}</span>
              </span>
              <button
                type="button"
                className="mini-btn ghost"
                disabled={unmuting === chat.peerId}
                aria-label={`Unmute ${chat.username}`}
                onClick={() => void unmute(chat.peerId)}
              >
                {unmuting === chat.peerId ? "…" : "Unmute"}
              </button>
            </div>
          ))
        )}
      </SettingsGroup>
      <SettingsNote>A muted chat stays silent on all your devices, and its messages leave the badge.</SettingsNote>

      {error ? <p className="set-error">{error}</p> : null}

      <div className="set-explainer">
        <strong>
          <BellRing size={15} aria-hidden="true" />
          What a notification can say
        </strong>
        <p>
          Messages are end-to-end encrypted, so the server knows who wrote to you and when — never
          what. That is all a push can carry, and it travels encrypted to this browser.
        </p>
      </div>
    </>
  );
}

/** "On", "Off" or "Blocked" for the Settings list. */
export function notificationsSummary(): string {
  const permission = notificationPermission();
  if (permission === "denied") return "Blocked";
  return loadNotificationPrefs().enabled && permission === "granted" ? "On" : "Off";
}
