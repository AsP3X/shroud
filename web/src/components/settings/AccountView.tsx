import { useEffect, useState } from "react";
import { Key, Link2, LogOut, QrCode, RotateCcw, Trash2, User } from "lucide-react";
import { api, ApiError, type Session } from "../../api/client";
import { saveShareCode } from "../../session";
import { CopyButton } from "../CopyButton";
import { ConfirmDialog } from "./ConfirmDialog";
import { DELETE_ACCOUNT_COPY } from "./deleteAccount";
import { SettingsCard, SettingsGroup, SettingsNote, SettingsRow } from "./SettingsRow";

/**
 * Account: who is signed in, how people add you (share code, invite link, QR code and its
 * reset), and the two ways out — logging this browser out, or deleting the account.
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
  const handle = `@${session.user.username}`;
  const [confirmingReset, setConfirmingReset] = useState(false);
  const [resetting, setResetting] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

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
      <SettingsCard>
        <div className="set-row">
          <span className="set-ico" aria-hidden="true">
            <User size={20} />
          </span>
          <span className="set-row-copy">
            <strong>Signed in as {handle}</strong>
            <code>{session.user.id}</code>
          </span>
        </div>
      </SettingsCard>
      <SettingsNote>
        Your username is shared only with people you have both added. The server never sees it.
      </SettingsNote>

      <SettingsGroup title="How people add you">
        <SettingsRow title="Show QR code" Icon={QrCode} onClick={onShowQr} />
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
          subtitle="Makes a new QR code, share code and invite link. The old ones stop working."
          Icon={RotateCcw}
          danger
          wrapSubtitle
          disabled={resetting}
          onClick={() => setConfirmingReset(true)}
        />
      </SettingsGroup>
      <SettingsNote>
        {notice ??
          "All three open the same door: anyone with one of them can send you a contact request. Your contacts keep working after a reset."}
      </SettingsNote>

      <SettingsCard>
        <SettingsRow
          title="Log out"
          subtitle="Keys and messages on this browser are erased. Your account and other devices stay."
          Icon={LogOut}
          wrapSubtitle
          onClick={onLogout}
        />
      </SettingsCard>

      <SettingsCard>
        <SettingsRow
          title={DELETE_ACCOUNT_COPY.rowTitle}
          subtitle={DELETE_ACCOUNT_COPY.rowSubtitle}
          Icon={Trash2}
          danger
          wrapSubtitle
          onClick={onDeleteAccount}
        />
      </SettingsCard>

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
