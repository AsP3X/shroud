import {
  AudioWaveform,
  Bell,
  Bookmark,
  Database,
  Folder,
  Globe,
  Info,
  Key,
  Languages,
  Laptop,
  Link2,
  Lock,
  Palette,
  Phone,
  QrCode,
  Server,
  User,
} from "lucide-react";
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
      <header className="set-hero">
        <Avatar name={displayName(session.user.username)} seed={session.user.id} size="lg" />
        <h1>{displayName(session.user.username)}</h1>
        <p>{handle}</p>
      </header>

      <SettingsCard>
        <div className="set-row">
          <span className="set-tile" style={{ background: "#ff6b6b" }} aria-hidden="true">
            <User size={15} />
          </span>
          <span className="set-row-copy">
            <strong>Signed in as {handle}</strong>
            <code>{session.user.id}</code>
          </span>
        </div>
      </SettingsCard>

      <SettingsGroup title="Invite">
        <div className="set-row">
          <span className="set-tile" style={{ background: "#2e8fe0" }} aria-hidden="true">
            <Key size={15} />
          </span>
          <span className="set-row-copy">
            <strong>Share code</strong>
            <code>{session.user.share_code}</code>
          </span>
          <CopyButton value={session.user.share_code} label="share code" />
        </div>
        <div className="set-row">
          <span className="set-tile" style={{ background: "#4ac7fa" }} aria-hidden="true">
            <Link2 size={15} />
          </span>
          <span className="set-row-copy">
            <strong>Invite link</strong>
            <code>{shareLink}</code>
          </span>
          <CopyButton value={shareLink} label="invite link" />
        </div>
        <SettingsRow title="Show QR code" Icon={QrCode} tint="#6b6bf2" onClick={onShowQr} />
      </SettingsGroup>

      <SettingsCard>
        <SettingsRow title="Saved Messages" Icon={Bookmark} tint="#2e8fe0" soon />
        <SettingsRow title="Recent Calls" Icon={Phone} tint="#2fa85b" soon />
        <SettingsRow
          title="Devices"
          value={deviceCount === null ? undefined : `${deviceCount}`}
          Icon={Laptop}
          tint="#f76b1c"
          onClick={() => onNavigate("devices")}
        />
        <SettingsRow title="Chat Folders" Icon={Folder} tint="#4ac7fa" soon />
      </SettingsCard>

      <SettingsCard>
        <SettingsRow
          title="Notifications and Sounds"
          value={notificationsSummary()}
          Icon={Bell}
          tint="#e64a72"
          onClick={() => onNavigate("notifications")}
        />
        <SettingsRow
          title="Privacy and Security"
          Icon={Lock}
          tint="var(--text-tertiary)"
          onClick={() => onNavigate("privacy")}
        />
        <SettingsRow
          title="Data and Storage"
          Icon={Database}
          tint="#2fa85b"
          onClick={() => onNavigate("data")}
        />
        <SettingsRow
          title="Appearance"
          value={themeLabel(pref)}
          Icon={Palette}
          tint="var(--accent)"
          onClick={() => onNavigate("appearance")}
        />
        <SettingsRow title="Language" value="English" Icon={Languages} tint="#9b4ae6" soon />
        <SettingsRow title="Transcription" Icon={AudioWaveform} tint="#2e8fe0" soon />
        <SettingsRow
          title="Server"
          subtitle={window.location.host}
          Icon={Server}
          tint="var(--accent)"
          onClick={() => onNavigate("server")}
        />
      </SettingsCard>

      <SettingsGroup title="Encryption">
        <div className="set-row">
          <span className="set-tile" style={{ background: "#2fa85b" }} aria-hidden="true">
            <Globe size={15} />
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
          title="About Shroud"
          value={webVersion()}
          dot={update.status === "available" ? "Update available" : undefined}
          Icon={Info}
          tint="#2e8fe0"
          onClick={() => onNavigate("about")}
        />
      </SettingsCard>

      <div className="set-group">
        <button type="button" className="set-logout" onClick={onLogout}>
          Log Out
        </button>
      </div>
    </>
  );
}
