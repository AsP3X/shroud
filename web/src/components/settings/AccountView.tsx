import { useEffect, useState } from "react";
import { AtSign, Hash, Key, Link2, LogOut, QrCode, RotateCcw, Trash2 } from "lucide-react";
import { api, ApiError, type Session } from "../../api/client";
import { displayName } from "../../format";
import { saveShareCode } from "../../session";
import { Avatar } from "../Avatar";
import { CopyButton } from "../CopyButton";
import { ConfirmDialog } from "./ConfirmDialog";
import { DELETE_ACCOUNT_COPY } from "./deleteAccount";
import { SettingsGroup, SettingsHero, SettingsNote, SettingsRow } from "./SettingsRow";

/**
 * Account: you, large, with the two things people do with their profile; then what identifies
 * you, how people add you (share code, invite link, QR code and its reset), and the two ways
 * out — logging this browser out, or deleting the account.
 */
export function AccountView({
  session,
  shareLink,
  onShowQr,
  onShareCodeChanged,
  onLogout,
  onDeleteAccount,
  onUnauthorized,
}: {
  session: Session;
  shareLink: string;
  onShowQr: () => void;
  onShareCodeChanged: (shareCode: string) => void;
  onLogout: () => void;
  onDeleteAccount: () => void;
  onUnauthorized: (err?: unknown) => void;
}) {
  const name = displayName(session.user.username);
  const handle = `@${session.user.username}`;
  const [confirmingReset, setConfirmingReset] = useState(false);
  const [resetting, setResetting] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [copiedLink, setCopiedLink] = useState(false);

  useEffect(() => {
    if (!confirmingReset) return;
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") setConfirmingReset(false);
    };
    document.addEventListener("keydown", onKeyDown);
    return () => document.removeEventListener("keydown", onKeyDown);
  }, [confirmingReset]);

  useEffect(() => {
    if (!copiedLink) return;
    const timer = window.setTimeout(() => setCopiedLink(false), 1600);
    return () => window.clearTimeout(timer);
  }, [copiedLink]);

  async function copyLink() {
    try {
      await navigator.clipboard.writeText(shareLink);
      setCopiedLink(true);
    } catch {
      /* clipboard blocked: the link stays selectable in the row below */
    }
  }

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
      if (err instanceof ApiError && err.isAuthFailure) {
        onUnauthorized(err);
        return;
      }
      setError(err instanceof ApiError ? err.message : "Could not reset your QR code.");
    } finally {
      setResetting(false);
    }
  }

  return (
    <>
      <SettingsHero
        figure={<Avatar name={name} seed={session.user.id} size="lg" />}
        title={name}
        lines={[handle, "End-to-end encrypted on every device"]}
        actions={
          <>
            <button type="button" className="set-action primary" onClick={onShowQr}>
              <QrCode size={16} aria-hidden="true" />
              Show QR code
            </button>
            <button type="button" className="set-action" onClick={() => void copyLink()}>
              <Link2 size={16} aria-hidden="true" />
              {copiedLink ? "Link copied" : "Copy invite link"}
            </button>
          </>
        }
      />

      <SettingsGroup title="Identity">
        <SettingsRow title="Username" Icon={AtSign} value={handle} />
        <div className="set-row">
          <span className="set-ico" aria-hidden="true">
            <Hash size={20} />
          </span>
          <span className="set-row-copy">
            <strong>Account ID</strong>
            <code>{session.user.id}</code>
          </span>
          <CopyButton value={session.user.id} label="account ID" />
        </div>
      </SettingsGroup>
      <SettingsNote>
        Your username is shared only with people you have both added. The server never sees it.
      </SettingsNote>

      <SettingsGroup title="How people add you">
        <div className="set-row">
          <span className="set-ico" aria-hidden="true">
            <Key size={20} />
          </span>
          <span className="set-row-copy">
            <strong>Share code</strong>
            <code>{session.user.share_code}</code>
          </span>
          <CopyButton value={session.user.share_code} label="share code" />
        </div>
        <div className="set-row">
          <span className="set-ico" aria-hidden="true">
            <Link2 size={20} />
          </span>
          <span className="set-row-copy">
            <strong>Invite link</strong>
            <code>{shareLink}</code>
          </span>
          <CopyButton value={shareLink} label="invite link" />
        </div>
        <SettingsRow
          title={resetting ? "Resetting…" : "Reset QR code"}
          subtitle="A new QR code, share code and invite link. The old ones stop working."
          Icon={RotateCcw}
          wrapSubtitle
          disabled={resetting}
          onClick={() => setConfirmingReset(true)}
        />
      </SettingsGroup>
      <SettingsNote>
        {notice ??
          "The QR code, share code and link open the same door: anyone holding one can send you a contact request. Existing contacts keep working after a reset."}
      </SettingsNote>

      <SettingsGroup title="Leaving">
        <SettingsRow
          title="Log out"
          subtitle="Erases the keys and messages on this browser. Your account and other devices stay."
          Icon={LogOut}
          wrapSubtitle
          onClick={onLogout}
        />
        <SettingsRow
          title={DELETE_ACCOUNT_COPY.rowTitle}
          subtitle={DELETE_ACCOUNT_COPY.rowSubtitle}
          Icon={Trash2}
          danger
          wrapSubtitle
          onClick={onDeleteAccount}
        />
      </SettingsGroup>

      {error ? <p className="set-error">{error}</p> : null}

      {confirmingReset ? (
        <ConfirmDialog
          title="Reset your QR code?"
          body="Your current QR code, share code and invite link stop working. Anyone who wants to add you will need the new one. Your contacts aren't affected."
          action="Reset"
          onCancel={() => setConfirmingReset(false)}
          onConfirm={() => void resetShareCode()}
        />
      ) : null}
    </>
  );
}
