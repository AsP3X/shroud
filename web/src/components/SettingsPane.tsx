import { Monitor, Moon, Sun } from "lucide-react";
import type { Session } from "../api/client";
import { bytesToB64 } from "../crypto/bytes";
import type { IdentityMaterial } from "../crypto/identity";
import { setTheme, useThemePref, type ThemePref } from "../theme";
import { Avatar } from "./Avatar";
import { CopyButton } from "./CopyButton";

const THEMES: { value: ThemePref; label: string; Icon: typeof Sun }[] = [
  { value: "system", label: "System", Icon: Monitor },
  { value: "light", label: "Light", Icon: Sun },
  { value: "dark", label: "Dark", Icon: Moon },
];

export function SettingsPane({
  session,
  identity,
  shareLink,
  onLogout,
}: {
  session: Session;
  identity: IdentityMaterial | null;
  shareLink: string;
  onLogout: () => void;
}) {
  const pref = useThemePref();

  return (
    <main className="settings" aria-label="Settings">
      <header className="settings-head">
        <Avatar name={session.user.username} seed={session.user.id} size="lg" />
        <div>
          <h1>{session.user.username}</h1>
          <p>{session.device.name ?? "This browser"} · active now</p>
        </div>
      </header>

      <section className="settings-section">
        <h2>Appearance</h2>
        <div className="segmented" role="radiogroup" aria-label="Colour theme">
          {THEMES.map(({ value, label, Icon }) => (
            <button
              key={value}
              type="button"
              role="radio"
              aria-checked={pref === value}
              className={pref === value ? "segment active" : "segment"}
              onClick={() => setTheme(value)}
            >
              <Icon size={15} aria-hidden="true" />
              {label}
            </button>
          ))}
        </div>
        <p className="settings-note">System follows your operating system’s light or dark setting.</p>
      </section>

      <section className="settings-section">
        <h2>Your share code</h2>
        <div className="settings-card">
          <div className="settings-row">
            <div className="settings-row-copy">
              <strong>Share code</strong>
              <code>{session.user.share_code}</code>
            </div>
            <CopyButton value={session.user.share_code} label="share code" />
          </div>
          <div className="settings-row">
            <div className="settings-row-copy">
              <strong>Invite link</strong>
              <code>{shareLink}</code>
            </div>
            <CopyButton value={shareLink} label="invite link" />
          </div>
        </div>
        <p className="settings-note">
          Anyone with this can send you a contact request. It reveals nothing else about you.
        </p>
      </section>

      <section className="settings-section">
        <h2>Devices</h2>
        <div className="settings-card">
          <div className="settings-row">
            <div className="settings-row-copy">
              <strong>This browser · {session.device.name ?? "Web"}</strong>
              <span className="now">Active now</span>
            </div>
          </div>
        </div>
        <p className="settings-note">
          This browser counts toward your five-device limit. Revoking other devices is coming soon.
        </p>
      </section>

      <section className="settings-section">
        <h2>Encryption</h2>
        {identity ? (
          <div className="settings-card">
            <div className="settings-row">
              <div className="settings-row-copy">
                <strong>This device’s identity key</strong>
                <code>{bytesToB64(identity.agreementPublic)}</code>
              </div>
            </div>
            <div className="settings-row">
              <div className="settings-row-copy">
                <strong>Registration ID</strong>
                <span>{identity.registrationId}</span>
              </div>
            </div>
          </div>
        ) : (
          <div className="settings-card settings-card-warn">
            <div className="settings-row">
              <div className="settings-row-copy">
                <strong>No identity keys on this browser</strong>
                <span>Log out and unlock with your 12-word phrase to restore them.</span>
              </div>
            </div>
          </div>
        )}
        <p className="settings-note">
          Same X25519 identity as iOS, derived from your 12-word phrase. The phrase itself is never
          stored here.
        </p>
      </section>

      <section className="settings-section">
        <button className="btn btn-danger" type="button" onClick={onLogout}>
          Log out
        </button>
      </section>
    </main>
  );
}
