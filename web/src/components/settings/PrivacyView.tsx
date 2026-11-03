import { useCallback, useEffect, useState } from "react";
import { KeyRound, Lock, ShieldCheck } from "lucide-react";
import { api, ApiError, type BlockItem, type PrivacySettings, type Session } from "../../api/client";
import { bytesToB64 } from "../../crypto/bytes";
import type { IdentityMaterial } from "../../crypto/identity";
import { setPrivacySettings, usePrivacySettings } from "../../privacy";
import { lockOnHidden, setLockOnHidden } from "../../session";
import { generatesLinkPreviews, setGeneratesLinkPreviews } from "../../linkPreview/settings";
import { alwaysRelaysCalls, setAlwaysRelaysCalls } from "../../calls/relay";
import { CONTACT_PLACEHOLDER, displayContactName } from "../../contactNames";
import { Avatar } from "../Avatar";
import { SettingsGroup, SettingsNote, SettingsRow, Switch } from "./SettingsRow";

/**
 * Privacy and security, top to bottom: locking this browser, what contacts see of you, what
 * leaves this browser for links and calls, what contacts may do to your chats, who is blocked,
 * and the key everything is sealed with.
 */
export function PrivacyView({
  session,
  identity,
  onLockNow,
  onUnauthorized,
}: {
  session: Session;
  identity: IdentityMaterial | null;
  onLockNow: () => void;
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
          description="Leaving this tab for a few seconds locks Shroud until you enter your PIN. Five idle minutes lock it either way."
        />
        <SettingsRow
          title="Lock chats now"
          subtitle="Leave chats until you enter your PIN again."
          Icon={Lock}
          onClick={onLockNow}
        />
      </SettingsGroup>

      <SettingsGroup title="What contacts see">
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

      <SettingsGroup title="Links and calls">
        <Switch
          checked={linkPreviews}
          onChange={(next) => {
            setLinkPreviews(next);
            setGeneratesLinkPreviews(next);
          }}
          label="Link previews"
          description="A preview of each link you send, sealed into the message. The page is fetched through the Shroud server, which sees only the website’s name."
        />
        <Switch
          checked={relayCalls}
          onChange={(next) => {
            setRelayCalls(next);
            setAlwaysRelaysCalls(next);
          }}
          label="Always relay calls"
          description="Calls go through the server’s relay, so the other side never sees your IP address. They may lag slightly."
        />
      </SettingsGroup>
      <SettingsNote>
        Previews are fetched end to end encrypted: the website sees the server, not you, and the
        people you send it to never contact the website. Without a relay on the server, calls
        won’t connect until relaying is off.
      </SettingsNote>

      <SettingsGroup title="Chats">
        {serverSwitch(
          "allow_peer_chat_delete",
          "Let contacts clear chats for me",
          "When a contact deletes a chat for both of you, your copy goes too. Off, you keep your own messages; theirs read “Message deleted” either way.",
        )}
      </SettingsGroup>
      <SettingsNote>You stay contacts either way.</SettingsNote>

      <SettingsGroup title="Blocked contacts">
        {blocked.length === 0 ? (
          <p className="set-placeholder">No one is blocked.</p>
        ) : (
          blocked.map((item) => (
            <div key={item.user_id} className="set-row">
              <Avatar name={displayContactName(session.user.id, item.user_id)} seed={item.user_id} size="sm" />
              <span className="set-row-copy">
                <strong>{item.username || displayContactName(session.user.id, item.user_id) || CONTACT_PLACEHOLDER}</strong>
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
          ))
        )}
      </SettingsGroup>
      <SettingsNote>
        Block someone from their profile in the iOS app. This list is where you undo it.
      </SettingsNote>

      <SettingsGroup title="Encryption">
        <div className="set-row">
          <span className="set-ico" aria-hidden="true">
            <KeyRound size={20} />
          </span>
          <span className="set-row-copy">
            <strong>{identity ? "This device’s identity key" : "No identity keys here"}</strong>
            <code>
              {identity
                ? bytesToB64(identity.agreementPublic)
                : "Log out and unlock with your 12-word phrase to restore them."}
            </code>
          </span>
        </div>
      </SettingsGroup>
      <div className="set-explainer">
        <strong>
          <ShieldCheck size={15} aria-hidden="true" />
          Encrypted on this device
        </strong>
        <p>
          Your chats are sealed with a key derived from your 12-word phrase, which is never
          stored here. The same identity as on iOS comes from that phrase. The server only ever
          holds ciphertext.
        </p>
      </div>

      {error ? <p className="set-error">{error}</p> : null}
    </>
  );
}
