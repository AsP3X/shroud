import { useState } from "react";
import { Monitor, Moon, Sun, Trash2 } from "lucide-react";
import { apiBase } from "../../config";
import { cacheStats, clearCache } from "../../crypto/plaintextCache";
import { setTheme, useThemePref, type ThemePref } from "../../theme";
import { SettingsCard, SettingsGroup, SettingsNote, SettingsRow } from "./SettingsRow";

const THEMES: { value: ThemePref; label: string; Icon: typeof Sun }[] = [
  { value: "system", label: "System", Icon: Monitor },
  { value: "light", label: "Light", Icon: Sun },
  { value: "dark", label: "Dark", Icon: Moon },
];

export function themeLabel(pref: ThemePref): string {
  return THEMES.find((t) => t.value === pref)?.label ?? "System";
}

export function AppearanceView() {
  const pref = useThemePref();
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
    </>
  );
}

function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(0)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

export function DataStorageView({ onCleared }: { onCleared?: () => void }) {
  const [stats, setStats] = useState(() => cacheStats());
  const [cleared, setCleared] = useState(false);

  function clear() {
    clearCache();
    setStats(cacheStats());
    setCleared(true);
    onCleared?.();
  }

  return (
    <>
      <SettingsGroup title="On this browser">
        <div className="set-row">
          <span className="set-row-copy">
            <strong>Decrypted messages</strong>
            <span>{stats.messages} cached for instant reload</span>
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
          subtitle="Removes decrypted text from this browser. Messages stay on the server."
          Icon={Trash2}
          tint="var(--danger-bg)"
          onClick={stats.messages + stats.previews > 0 ? clear : undefined}
        />
      </SettingsCard>

      <SettingsNote>
        Clearing is safe: history is re-fetched and re-decrypted from the server the next time you
        open a chat. Chat list previews will look empty until then.
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
