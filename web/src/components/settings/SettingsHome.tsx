import { Bell, Database, Globe, Info, Key, Laptop, Link2, Lock, LogOut, Palette, QrCode, Server, User } from "lucide-react";
import type { Session } from "../../api/client";
import { webVersion } from "../../appVersion";
import { bytesToB64 } from "../../crypto/bytes";
import type { IdentityMaterial } from "../../crypto/identity";
import { displayName } from "../../format";
import { useNotificationPrefs } from "../../notifications/prefs";
import { useThemePref } from "../../theme";
import { Avatar } from "../Avatar";
import { CopyButton } from "../CopyButton";
import { useUpdateSnapshot } from "./AboutView";
import { notificationsSummary } from "./NotificationsView";
import { themeLabel } from "./PreferencesViews";
import { SettingsCard, SettingsGroup, SettingsNote, SettingsRow } from "./SettingsRow";
import type { SettingsRoute } from "./routes";

/**
 * The settings list: who is signed in, the account (code, link, QR code, devices), the pages,
 * the identity key, and the way out. Each page says what it is for at its top (SettingsPane).
 */
export function SettingsHome({
  session,
  identity,
  shareLink,
  deviceCount,
  onNavigate,
  onLogout,
  onShowQr,
}: {
  session: Session;
  identity: IdentityMaterial | null;
  shareLink: string;
  deviceCount: number | null;
  onNavigate: (route: SettingsRoute) => void;
  onLogout: () => void;
  onShowQr: () => void;
}) {
  const pref = useThemePref();
  // Re-renders the Notifications row's On/Off when it changes.
  useNotificationPrefs();
  const update = useUpdateSnapshot();
  const handle = `@${session.user.username}`;

  return (
    <>
      <SettingsCard>
        <div className="set-row set-me">
          <Avatar name={displayName(session.user.username)} seed={session.user.id} size="md" />
          <span className="set-row-copy">
            <strong>{displayName(session.user.username)}</strong>
            <span>{handle}</span>
          </span>
        </div>
      </SettingsCard>

      <SettingsGroup title="Account">
        <div className="set-row">
          <span className="set-ico" aria-hidden="true">
            <User size={20} />
          </span>
          <span className="set-row-copy">
            <strong>Signed in as {handle}</strong>
            <code>{session.user.id}</code>
          </span>
        </div>
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
        <SettingsRow
          title="Devices"
          subtitle={deviceCount === null ? undefined : `${deviceCount} of 5 signed in`}
          Icon={Laptop}
          onClick={() => onNavigate("devices")}
        />
      </SettingsGroup>

      <SettingsCard>
        <SettingsRow
          title="Notifications and sounds"
          subtitle={notificationsSummary()}
          Icon={Bell}
          onClick={() => onNavigate("notifications")}
        />
        <SettingsRow
          title="Appearance"
          subtitle={`${themeLabel(pref)} theme`}
          Icon={Palette}
          onClick={() => onNavigate("appearance")}
        />
        <SettingsRow
          title="Privacy and security"
          subtitle="Keys, safety numbers, who sees you"
          Icon={Lock}
          onClick={() => onNavigate("privacy")}
        />
        <SettingsRow
          title="Data and storage"
          subtitle="What this browser keeps"
          Icon={Database}
          onClick={() => onNavigate("data")}
        />
        <SettingsRow title="Server" subtitle={window.location.host} Icon={Server} onClick={() => onNavigate("server")} />
        <SettingsRow
          title="About Shroud"
          value={webVersion()}
          dot={update.status === "available" ? "Update available" : undefined}
          Icon={Info}
          onClick={() => onNavigate("about")}
        />
      </SettingsCard>

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
