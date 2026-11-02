import { Globe, Key, Link2, LogOut, QrCode, User } from "lucide-react";
import type { Session } from "../../api/client";
import { bytesToB64 } from "../../crypto/bytes";
import type { IdentityMaterial } from "../../crypto/identity";
import { CopyButton } from "../CopyButton";
import { DevicesView } from "./DevicesView";
import { SettingsCard, SettingsGroup, SettingsNote, SettingsRow } from "./SettingsRow";

/**
 * Account: who is signed in, the devices that share the account, the invite, the identity key,
 * and the way out.
 */
export function AccountView({
  session,
  identity,
  shareLink,
  onShowQr,
  onLogout,
  onUnauthorized,
  onDeviceCount,
}: {
  session: Session;
  identity: IdentityMaterial | null;
  shareLink: string;
  onShowQr: () => void;
  onLogout: () => void;
  onUnauthorized: (err?: unknown) => void;
  onDeviceCount: (count: number) => void;
}) {
  const handle = `@${session.user.username}`;
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

      <DevicesView session={session} onUnauthorized={onUnauthorized} onCount={onDeviceCount} />

      <SettingsGroup title="Invite">
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
        <SettingsRow title="Show QR code" Icon={QrCode} onClick={onShowQr} />
      </SettingsGroup>

      <SettingsGroup title="Encryption">
        <div className="set-row">
          <span className="set-ico" aria-hidden="true">
            <Globe size={20} />
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
      <SettingsNote>
        Same X25519 identity as iOS, derived from your 12-word phrase. The phrase itself is never
        stored here.
      </SettingsNote>

      <SettingsCard>
        <SettingsRow
          title="Log out"
          subtitle="Keys and messages on this browser are erased"
          Icon={LogOut}
          danger
          onClick={onLogout}
        />
      </SettingsCard>
    </>
  );
}
