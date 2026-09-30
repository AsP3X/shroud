import { QrCode } from "lucide-react";
import type { Session } from "../api/client";
import { Avatar } from "./Avatar";
import { CopyButton } from "./CopyButton";
import { Modal } from "./Modal";
import { displayName } from "./settings/SettingsHome";

/**
 * Your own profile, opened from the account menu on the rail: who you are to other people and
 * how they add you. Laid out like the "Contact info" sheet a peer gets, so both read as the
 * same kind of card.
 */
export function ProfileSheet({
  session,
  shareLink,
  onShowQr,
  onClose,
}: {
  session: Session;
  shareLink: string;
  /** Hands over to the QR sheet (this one closes first). */
  onShowQr: () => void;
  onClose: () => void;
}) {
  const name = displayName(session.user.username);
  return (
    <Modal title="Profile" onClose={onClose}>
      <div className="info-sheet">
        <Avatar name={name} seed={session.user.id} size="lg" />
        <strong>{name}</strong>
        <span>@{session.user.username}</span>
      </div>
      <div className="set-card">
        <div className="set-row">
          <span className="set-row-copy">
            <strong>User ID</strong>
            <code>{session.user.id}</code>
          </span>
        </div>
        <div className="set-row">
          <span className="set-row-copy">
            <strong>Share code</strong>
            <code>{session.user.share_code}</code>
          </span>
          <CopyButton value={session.user.share_code} label="share code" />
        </div>
        <div className="set-row">
          <span className="set-row-copy">
            <strong>Invite link</strong>
            <code>{shareLink}</code>
          </span>
          <CopyButton value={shareLink} label="invite link" />
        </div>
      </div>
      <button type="button" className="btn btn-secondary profile-qr" onClick={onShowQr}>
        <QrCode size={16} aria-hidden="true" />
        Show QR code
      </button>
    </Modal>
  );
}
