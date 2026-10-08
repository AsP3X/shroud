import { useState } from "react";
import { Monitor, Moon, Sun, Trash2 } from "lucide-react";
import { apiBase } from "../../config";
import { cacheStats, clearCache } from "../../crypto/plaintextCache";
import { forgetFiles } from "../../media/fileTransfer";
import { forgetImages } from "../../media/images";
import { resetVideoWorker } from "../../media/prepareVideo";
import { forgetVideos } from "../../media/videos";
import { formatBytes } from "../../format";
import { setLogoStyle, useLogoStyle, type LogoStyle } from "../../logo";
import { setTheme, useThemePref, type ThemePref } from "../../theme";
import { BrandMark } from "../BrandMark";
import { SettingsCard, SettingsGroup, SettingsNote, SettingsRow } from "./SettingsRow";

const THEMES: { value: ThemePref; label: string; Icon: typeof Sun }[] = [
  { value: "system", label: "System", Icon: Monitor },
  { value: "light", label: "Light", Icon: Sun },
  { value: "dark", label: "Dark", Icon: Moon },
];

const LOGOS: { value: LogoStyle; label: string }[] = [
  { value: "detailed", label: "Detailed" },
  { value: "simple", label: "Simple" },
];

export function themeLabel(pref: ThemePref): string {
  return THEMES.find((t) => t.value === pref)?.label ?? "System";
}

export function AppearanceView() {
  const pref = useThemePref();
  const logo = useLogoStyle();
  return (
    <>
      <SettingsGroup title="Colour theme">
        <div className="set-row">
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
        </div>
      </SettingsGroup>
      <SettingsNote>
        System follows your operating system’s light or dark setting. The choice is remembered on
        this browser only.
      </SettingsNote>
      <SettingsGroup title="Logo">
        <div className="set-row">
          <div className="logo-picker" role="radiogroup" aria-label="Logo">
            {LOGOS.map(({ value, label }) => (
              <button
                key={value}
                type="button"
                role="radio"
                aria-checked={logo === value}
                className={logo === value ? "logo-option active" : "logo-option"}
                onClick={() => setLogoStyle(value)}
              >
                <BrandMark size={56} variant={value} />
                {label}
              </button>
            ))}
          </div>
        </div>
      </SettingsGroup>
      <SettingsNote>
        The mark in the app and the tab icon. Installed-app icons stay detailed.
      </SettingsNote>
    </>
  );
}

export function DataStorageView({ onCleared }: { onCleared?: () => void }) {
  const [stats, setStats] = useState(() => cacheStats());
  const [cleared, setCleared] = useState(false);

  function clear() {
    clearCache();
    forgetImages();
    forgetVideos();
    forgetFiles();
    resetVideoWorker();
    setStats(cacheStats());
    setCleared(true);
    onCleared?.();
  }

  return (
    <>
      <SettingsGroup title="On this browser">
        <div className="set-row">
          <span className="set-row-copy">
            <strong>Saved messages</strong>
            <span>{stats.messages} kept encrypted under your PIN</span>
          </span>
          <span className="set-row-value">{formatBytes(stats.bytes)}</span>
        </div>
        <div className="set-row">
          <span className="set-row-copy">
            <strong>Chat previews</strong>
            <span>{stats.previews} conversations</span>
          </span>
        </div>
      </SettingsGroup>

      <SettingsCard>
        <SettingsRow
          title={cleared ? "Local cache cleared" : "Clear local cache"}
          subtitle="Removes saved messages and previews from this browser. Messages stay on the server."
          Icon={Trash2}
          tint="var(--danger-bg)"
          onClick={stats.messages + stats.previews > 0 ? clear : undefined}
        />
      </SettingsCard>

      <SettingsNote>
        Newer messages are fetched and decrypted again the next time you open a chat. Messages from
        before 23 September 2026 can't be read again after clearing: they were sent in an older
        format this app no longer trusts. Chat list previews will look empty until then.
      </SettingsNote>
    </>
  );
}

export function ServerView() {
  const base = apiBase();
  const sameOrigin = base.startsWith("/");
  return (
    <>
      <SettingsGroup title="Connection">
        <div className="set-row">
          <span className="set-row-copy">
            <strong>API endpoint</strong>
            <code>{sameOrigin ? `${window.location.origin}${base}` : base}</code>
          </span>
        </div>
        <div className="set-row">
          <span className="set-row-copy">
            <strong>Mode</strong>
            <span>{sameOrigin ? "Same-origin (reverse proxied)" : "Configured origin"}</span>
          </span>
        </div>
      </SettingsGroup>
      <SettingsNote>
        Unlike iOS, the web client cannot switch servers from here — it talks to whichever host
        served this page. Point it elsewhere by deploying against a different API.
      </SettingsNote>
    </>
  );
}
