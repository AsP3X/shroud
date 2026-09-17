import { useCallback, useEffect, useState } from "react";
import { Lock, ShieldCheck } from "lucide-react";
import { api, ApiError, type BlockItem, type Session } from "../../api/client";
import { lockOnHidden, setLockOnHidden } from "../../session";
import { Avatar } from "../Avatar";
import { SettingsCard, SettingsGroup, SettingsNote, SettingsRow, Switch } from "./SettingsRow";

export function PrivacyView({
  session,
  onLockNow,
  onUnauthorized,
}: {
  session: Session;
  onLockNow: () => void;
  onUnauthorized: () => void;
}) {
  const [background, setBackground] = useState(() => lockOnHidden());
  const [peerDelete, setPeerDelete] = useState<boolean | null>(null);
  const [savingPeerDelete, setSavingPeerDelete] = useState(false);
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
    if (
      (privacy.status === "rejected" && unauthorized(privacy.reason)) ||
      (blocks.status === "rejected" && unauthorized(blocks.reason))
    ) {
      onUnauthorized();
      return;
    }
    const messages: string[] = [];
    if (privacy.status === "fulfilled") setPeerDelete(privacy.value.allow_peer_chat_delete);
    else messages.push("Could not load chat deletion setting.");
    if (blocks.status === "fulfilled") setBlocked(blocks.value.blocks);
    else messages.push("Could not load blocked contacts.");
    setError(messages.length ? messages.join(" ") : null);
  }, [session.token, onUnauthorized, unauthorized]);

  useEffect(() => {
    void load();
  }, [load]);

  async function togglePeerDelete(next: boolean) {
    const previous = peerDelete;
    setPeerDelete(next);
    setSavingPeerDelete(true);
    setError(null);
    try {
      const saved = await api.updatePrivacySettings(session.token, next);
      setPeerDelete(saved.allow_peer_chat_delete);
    } catch (err) {
      if (unauthorized(err)) {
        onUnauthorized();
        return;
      }
      setPeerDelete(previous);
      setError(err instanceof ApiError ? err.message : "Could not save that setting.");
    } finally {
      setSavingPeerDelete(false);
    }
  }

  async function unblock(item: BlockItem) {
    setUnblocking(item.user_id);
    setError(null);
    try {
      await api.unblock(session.token, item.user_id);
      setBlocked((prev) => prev.filter((b) => b.user_id !== item.user_id));
    } catch (err) {
      if (unauthorized(err)) {
        onUnauthorized();
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

      <SettingsGroup title="Chat deletion">
        <Switch
          checked={peerDelete ?? false}
          disabled={peerDelete === null || savingPeerDelete}
          onChange={(next) => void togglePeerDelete(next)}
          label="Let contacts clear chats for me"
          description="When a contact deletes a chat for both of you, your copy is deleted too. Leave this off to keep your own messages — theirs are replaced with “Message deleted” either way, and deleting for both always removes the contact."
        />
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
