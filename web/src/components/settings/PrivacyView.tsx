import { useCallback, useEffect, useState } from "react";
import { Lock, QrCode, ShieldCheck } from "lucide-react";
import { api, ApiError, type BlockItem, type PrivacySettings, type Session } from "../../api/client";
import { setPrivacySettings, usePrivacySettings } from "../../privacy";
import { lockOnHidden, saveShareCode, setLockOnHidden } from "../../session";
import { generatesLinkPreviews, setGeneratesLinkPreviews } from "../../linkPreview/settings";
import { alwaysRelaysCalls, setAlwaysRelaysCalls } from "../../calls/relay";
import { Avatar } from "../Avatar";
import { ConfirmDialog } from "./ConfirmDialog";
import { SettingsCard, SettingsGroup, SettingsNote, SettingsRow, Switch } from "./SettingsRow";

export function PrivacyView({
  session,
  onLockNow,
  onShareCodeChanged,
  onUnauthorized,
}: {
  session: Session;
  onLockNow: () => void;
  onShareCodeChanged: (shareCode: string) => void;
  /** `err` tells a removed device (`DEVICE_REMOVED`) from a session that merely ended. */
  onUnauthorized: (err?: unknown) => void;
}) {
  const [background, setBackground] = useState(() => lockOnHidden());
  const [linkPreviews, setLinkPreviews] = useState(() => generatesLinkPreviews());
  const [relayCalls, setRelayCalls] = useState(() => alwaysRelaysCalls());
  const settings = usePrivacySettings();
  const [saving, setSaving] = useState<keyof PrivacySettings | null>(null);
  const [blocked, setBlocked] = useState<BlockItem[]>([]);
  const [unblocking, setUnblocking] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [confirmingReset, setConfirmingReset] = useState(false);
  const [resetting, setResetting] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);

  const unauthorized = useCallback(
    (err: unknown) => err instanceof ApiError && err.isAuthFailure,
    [],
  );

  const load = useCallback(async () => {
    const [privacy, blocks] = await Promise.allSettled([
      api.privacySettings(session.token),
      api.blocks(session.token),
    ]);
    if (privacy.status === "rejected" && unauthorized(privacy.reason)) {
      onUnauthorized(privacy.reason);
      return;
    }
    if (blocks.status === "rejected" && unauthorized(blocks.reason)) {
      onUnauthorized(blocks.reason);
      return;
    }
    const messages: string[] = [];
    if (privacy.status === "fulfilled") setPrivacySettings(privacy.value);
    else messages.push("Could not load privacy settings.");
    if (blocks.status === "fulfilled") setBlocked(blocks.value.blocks);
    else messages.push("Could not load blocked contacts.");
    setError(messages.length ? messages.join(" ") : null);
  }, [session.token, onUnauthorized, unauthorized]);

  useEffect(() => {
    void load();
  }, [load]);

  /** Writes one switch; the shown value only moves once the server confirms it. */
  async function save(key: keyof PrivacySettings, next: boolean) {
    setSaving(key);
    setError(null);
    try {
      setPrivacySettings(await api.updatePrivacySettings(session.token, { [key]: next }));
    } catch (err) {
      if (unauthorized(err)) {
        onUnauthorized(err);
        return;
      }
      setError(err instanceof ApiError ? err.message : "Could not save that setting.");
    } finally {
      setSaving(null);
    }
  }

  useEffect(() => {
    if (!confirmingReset) return;
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") setConfirmingReset(false);
    };
    document.addEventListener("keydown", onKeyDown);
    return () => document.removeEventListener("keydown", onKeyDown);
  }, [confirmingReset]);

  async function resetShareCode() {
    setConfirmingReset(false);
    setResetting(true);
    setError(null);
    setNotice(null);
    try {
      const { share_code: shareCode } = await api.rotateShareCode(session.token);
      saveShareCode(shareCode);
      onShareCodeChanged(shareCode);
      setNotice("New QR code ready. The old one no longer works.");
    } catch (err) {
      if (unauthorized(err)) {
        onUnauthorized(err);
        return;
      }
      setError(err instanceof ApiError ? err.message : "Could not reset your QR code.");
    } finally {
      setResetting(false);
    }
  }

  const serverSwitch = (key: keyof PrivacySettings, label: string, description: string) => (
    <Switch
      checked={settings?.[key] ?? false}
      disabled={settings === null || saving !== null}
      onChange={(next) => void save(key, next)}
      label={label}
      description={description}
    />
  );

  async function unblock(item: BlockItem) {
    setUnblocking(item.user_id);
    setError(null);
    try {
      await api.unblock(session.token, item.user_id);
      setBlocked((prev) => prev.filter((b) => b.user_id !== item.user_id));
    } catch (err) {
      if (unauthorized(err)) {
        onUnauthorized(err);
        return;
      }
      setError(err instanceof ApiError ? err.message : "Could not unblock.");
    } finally {
      setUnblocking(null);
    }
  }

  return (
    <>
      <SettingsGroup title="Locking">
        <Switch
          checked={background}
          onChange={(next) => {
            setBackground(next);
            setLockOnHidden(next);
          }}
          label="Lock chats in background"
          description="Leaving this tab for more than a few seconds locks Shroud and asks for your PIN when you come back. Reloading the page does not. Five minutes of inactivity still locks even if this is off."
        />
      </SettingsGroup>

      <SettingsCard>
        <SettingsRow
          title="Lock chats now"
          subtitle="Leave chats until you enter your PIN again."
          Icon={Lock}
          tint="var(--danger-bg)"
          onClick={onLockNow}
        />
      </SettingsCard>

      <SettingsGroup title="Visibility">
        {serverSwitch(
          "send_read_receipts",
          "Read receipts",
          "Contacts see when you've read their messages.",
        )}
        {serverSwitch(
          "send_typing",
          "Typing indicators",
          "Contacts see when you're typing or recording a voice message.",
        )}
        {serverSwitch(
          "share_presence",
          "Online and last seen",
          "Contacts see when you're online and when you were last here.",
        )}
      </SettingsGroup>
      <SettingsNote>
        These work both ways: when you hide yours, you won't see your contacts' either.
      </SettingsNote>

      <SettingsGroup title="Finding you">
        {serverSwitch(
          "discoverable_by_username",
          "Find me by username",
          "People who know your username can find you and send a request. Off, they need your QR code or share code; your contacts can still find you.",
        )}
        <SettingsRow
          title="Reset QR code"
          subtitle="Makes a new QR code and invite link. The old ones stop working."
          Icon={QrCode}
          tint="var(--danger-bg)"
          danger
          onClick={resetting ? undefined : () => setConfirmingReset(true)}
        />
      </SettingsGroup>
      {notice ? <SettingsNote>{notice}</SettingsNote> : null}

      <SettingsGroup title="Link previews">
        <Switch
          checked={linkPreviews}
          onChange={(next) => {
            setLinkPreviews(next);
            setGeneratesLinkPreviews(next);
          }}
          label="Link previews"
          description="When you send a link, this browser loads the page to build a preview and seals it into the message. The page is fetched end to end encrypted through the Shroud server, which sees only the website's name — not the link or the page; the website sees the server, not you. People you send it to never contact the website."
        />
      </SettingsGroup>

      <SettingsGroup title="Calls">
        <Switch
          checked={relayCalls}
          onChange={(next) => {
            setRelayCalls(next);
            setAlwaysRelaysCalls(next);
          }}
          label="Always relay calls"
          description="Calls from this browser go through the Shroud server's relay, so the person you call never sees your IP address. Calls may lag slightly. If the server has no relay, calls won't connect until you turn this off."
        />
      </SettingsGroup>

      <SettingsGroup title="Chat deletion">
        {serverSwitch(
          "allow_peer_chat_delete",
          "Let contacts clear chats for me",
          "When a contact deletes a chat for both of you, your copy is deleted too. Leave this off to keep your own messages — theirs are replaced with “Message deleted” either way. You stay contacts.",
        )}
      </SettingsGroup>

      {blocked.length > 0 ? (
        <SettingsGroup title="Blocked">
          {blocked.map((item) => (
            <div key={item.user_id} className="set-row">
              <Avatar name={item.username} seed={item.user_id} size="sm" />
              <span className="set-row-copy">
                <strong>{item.username}</strong>
                <span>Blocked</span>
              </span>
              <button
                type="button"
                className="mini-btn ghost"
                disabled={unblocking === item.user_id}
                onClick={() => void unblock(item)}
              >
                {unblocking === item.user_id ? "…" : "Unblock"}
              </button>
            </div>
          ))}
        </SettingsGroup>
      ) : null}

      {error ? <p className="set-error">{error}</p> : null}

      {confirmingReset ? (
        <ConfirmDialog
          title="Reset your QR code?"
          body="Your current QR code and invite link stop working. Anyone who wants to add you will need the new one. Your contacts aren't affected."
          action="Reset"
          onCancel={() => setConfirmingReset(false)}
          onConfirm={() => void resetShareCode()}
        />
      ) : null}

      <div className="set-explainer">
        <strong>
          <ShieldCheck size={15} aria-hidden="true" />
          Encrypted on this device
        </strong>
        <p>
          Chat history is sealed with a key derived from your 12-word encryption phrase. The phrase
          itself is never stored here, and the server only ever holds ciphertext.
        </p>
      </div>

      <SettingsNote>
        Blocking someone is done from their profile in the iOS app; this list is where you undo it.
      </SettingsNote>
    </>
  );
}
