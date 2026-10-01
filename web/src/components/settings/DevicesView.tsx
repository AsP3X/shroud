import { useCallback, useEffect, useMemo, useRef, useState, type ComponentType, type FormEvent } from "react";
import {
  Check,
  ChevronRight,
  Globe,
  Hand,
  Laptop,
  Monitor,
  MonitorSmartphone,
  ShieldCheck,
  Smartphone,
  Tablet,
  TriangleAlert,
} from "lucide-react";
import { api, ApiError, type Device, type Session } from "../../api/client";
import { DEVICE_NAME_MAX_BYTES, normalizeDeviceName, openDeviceName, type DeviceLabel } from "../../crypto/deviceName";
import { loadIdentity } from "../../crypto/store";
import { saveDeviceName } from "../../deviceNaming";
import { fullTimestamp, listTimestamp } from "../../format";
import { CopyButton } from "../CopyButton";
import { ConfirmDialog } from "./ConfirmDialog";
import { SettingsGroup, SettingsNote } from "./SettingsRow";

/** Mirrors `MAX_DEVICES_PER_USER` on the server; the list is the only place to get back under it. */
const DEVICE_LIMIT = 5;

/**
 * What a removal does, server side included — shared by both confirmations. Same wording as
 * iOS `DevicesView.revokeConsequences`.
 *
 * The server keeps the removed device's row as history, so the messages and files it sent stay
 * in every chat. It only loses its sessions, keys and push token.
 */
function revokeConsequences(plural: boolean): string {
  return plural
    ? "They are signed out right away and erase everything of your account on them: messages, keys and files, as soon as they are online or next opened. What they already sent stays in your chats. Signing in there again takes your password and 12-word phrase."
    : "It is signed out right away and erases everything of your account on it: messages, keys and files, as soon as it is online or next opened. What it already sent stays in your chats. Signing in there again takes your password and 12-word phrase.";
}

type IconProps = { size?: number };
type DeviceKind = { label: string; Icon: ComponentType<IconProps>; tint: string };

/**
 * What a device is: the kind sealed with its name, else a guess from the name — the same rules
 * as iOS `DeviceKind` (`DevicesView.swift:784-846`). The Android app seals kind 4 (port plan
 * decision P4): "Android app", the phone icon, green. A name that only looks like a phone keeps
 * the guess "Phone" — it may be an Android device renamed by a client that dropped its kind.
 */
function deviceKind(label: DeviceLabel | null): DeviceKind {
  if (label?.kind === "iphone") return { label: "iPhone app", Icon: Smartphone, tint: "#2e8fe0" };
  if (label?.kind === "ipad") return { label: "iPad app", Icon: Tablet, tint: "#2e8fe0" };
  if (label?.kind === "web") return { label: "Web browser", Icon: Globe, tint: "#f76b1c" };
  if (label?.kind === "android") return { label: "Android app", Icon: Smartphone, tint: "#2fa85b" };
  const lower = (label?.name ?? "").toLowerCase();
  if (lower.includes("iphone")) return { label: "iPhone app", Icon: Smartphone, tint: "#2e8fe0" };
  if (lower.includes("ipad")) return { label: "iPad app", Icon: Tablet, tint: "#2e8fe0" };
  if (lower.includes("android") || lower.includes("phone")) {
    return { label: "Phone", Icon: Smartphone, tint: "#2fa85b" };
  }
  if (["chrome", "safari", "firefox", "edge", "browser", " on "].some((w) => lower.includes(w))) {
    return { label: "Web browser", Icon: Globe, tint: "#f76b1c" };
  }
  if (lower.includes("mac")) return { label: "Mac", Icon: Laptop, tint: "#9b4ae6" };
  if (lower.includes("windows") || lower.includes("linux")) {
    return { label: "Computer", Icon: Monitor, tint: "#9b4ae6" };
  }
  return { label: "Unknown", Icon: MonitorSmartphone, tint: "var(--text-secondary)" };
}

function DeviceTile({ label, size = 30 }: { label: DeviceLabel | null; size?: number }) {
  const { Icon, tint } = deviceKind(label);
  return (
    <span
      className="set-tile"
      // Explicit white: `.info-sheet > span` would otherwise grey the icon in the detail sheet.
      style={{ background: tint, color: "#fff", width: size, height: size, borderRadius: size * 0.27 }}
      aria-hidden="true"
    >
      <Icon size={Math.round(size * 0.5)} />
    </span>
  );
}

function displayName(label: DeviceLabel | null): string {
  return label?.name.trim() || "Unnamed device";
}


function lastActiveLabel(device: Device): string {
  return device.last_seen_at
    ? `Last active ${listTimestamp(device.last_seen_at)}`
    : `Linked ${listTimestamp(device.created_at)}`;
}

function lastActivity(device: Device): number {
  return new Date(device.last_seen_at ?? device.created_at).getTime();
}

/** A 404 means the device is already gone (removed elsewhere meanwhile) — the goal is met. */
function isAlreadyRemoved(err: unknown): boolean {
  return err instanceof ApiError && err.status === 404;
}

/**
 * Settings → Devices: every device linked to the account, with a way to remove the others.
 * Same sections, rules and wording as iOS `DevicesView.swift`: this browser can't be removed
 * here (that's Log Out); every other device can, one at a time or all at once.
 */
export function DevicesView({
  session,
  onUnauthorized,
  onCount,
}: {
  session: Session;
  /** `err` tells a removed device (`DEVICE_REMOVED`) from a session that merely ended. */
  onUnauthorized: (err?: unknown) => void;
  onCount?: (count: number) => void;
}) {
  const [devices, setDevices] = useState<Device[] | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  /** Last removal (or refresh) failure, shown under the list; the toast only confirms successes. */
  const [actionError, setActionError] = useState<string | null>(null);
  const [revoking, setRevoking] = useState<Set<string>>(() => new Set());
  const [revokingAll, setRevokingAll] = useState(false);
  const [confirming, setConfirming] = useState<Device | null>(null);
  const [confirmingAll, setConfirmingAll] = useState(false);
  const [detail, setDetail] = useState<Device | null>(null);
  const [toast, setToast] = useState<string | null>(null);
  /** Latest list for async code, without side effects inside state updaters. */
  const devicesRef = useRef<Device[] | null>(null);
  devicesRef.current = devices;
  /** Names are sealed to the account; this browser opens them with its phrase's history key. */
  const historyKey = useMemo(() => loadIdentity(session.user.id)?.historyKey ?? null, [session.user.id]);
  const labelOf = useCallback(
    (device: Device) => (historyKey ? openDeviceName(historyKey, device.id, device.sealed_name) : null),
    [historyKey],
  );
  const nameOf = useCallback((device: Device) => displayName(labelOf(device)), [labelOf]);

  const isCurrent = useCallback(
    (device: Device) => device.is_current || device.id === session.device.id,
    [session.device.id],
  );

  const authFailed = useCallback(
    (err: unknown) => {
      if (err instanceof ApiError && err.isAuthFailure) {
        onUnauthorized(err);
        return true;
      }
      return false;
    },
    [onUnauthorized],
  );

  /** Resolves to the fresh list, or null when it could not be loaded. */
  const load = useCallback(async (): Promise<Device[] | null> => {
    try {
      const res = await api.devices(session.token);
      setDevices(res.devices);
      setLoadError(null);
      onCount?.(res.devices.length);
      return res.devices;
    } catch (err) {
      if (authFailed(err)) return null;
      const message = err instanceof ApiError ? err.message : "Could not load devices.";
      // First load failing gets the retry card; a later refresh keeps the list on screen.
      if (devicesRef.current === null) setLoadError(message);
      else setActionError(message);
      return null;
    }
  }, [session.token, authFailed, onCount]);

  useEffect(() => {
    void load();
  }, [load]);

  // The web stand-in for pull-to-refresh: coming back to the tab re-reads the list.
  useEffect(() => {
    function onVisible() {
      if (document.visibilityState === "visible") void load();
    }
    document.addEventListener("visibilitychange", onVisible);
    return () => document.removeEventListener("visibilitychange", onVisible);
  }, [load]);

  useEffect(() => {
    if (!toast) return;
    const timer = window.setTimeout(() => setToast(null), 1800);
    return () => window.clearTimeout(timer);
  }, [toast]);

  /** Resolves to an error for the rename form, or null once saved. */
  async function rename(device: Device, name: string): Promise<string | null> {
    if (!historyKey) return "Unlock Shroud to rename devices.";
    try {
      // Keeps the device's kind (an Android phone's 4 included); `custom` stops an iPhone or an
      // Android phone putting its own name back.
      const kind = labelOf(device)?.kind ?? (isCurrent(device) ? "web" : "other");
      await saveDeviceName(session.token, device.id, historyKey, { name, kind, custom: true });
    } catch (err) {
      if (authFailed(err)) return null;
      return err instanceof ApiError ? err.message : "Could not save that name.";
    }
    setToast("Name saved");
    // The open dialog holds the device it was opened with; show the new name there too.
    const fresh = (await load())?.find((d) => d.id === device.id);
    if (fresh) setDetail((open) => (open?.id === fresh.id ? fresh : open));
    return null;
  }

  // Escape closes the top dialog only — never the Settings page behind it.
  const dialogOpen = Boolean(confirming || confirmingAll || detail);
  useEffect(() => {
    if (!dialogOpen) return;
    function onKeyDown(event: KeyboardEvent) {
      if (event.key !== "Escape") return;
      event.stopImmediatePropagation();
      if (confirming) setConfirming(null);
      else if (confirmingAll) setConfirmingAll(false);
      else setDetail(null);
    }
    document.addEventListener("keydown", onKeyDown, true);
    return () => document.removeEventListener("keydown", onKeyDown, true);
  }, [dialogOpen, confirming, confirmingAll]);

  const list = useMemo(() => devices ?? [], [devices]);
  const current = list.find(isCurrent) ?? null;
  /** Most recently active first, so a stale device sinks to the bottom. */
  const others = useMemo(
    () => list.filter((d) => !isCurrent(d)).sort((a, b) => lastActivity(b) - lastActivity(a)),
    [list, isCurrent],
  );

  function removeLocally(ids: string[]) {
    const prev = devicesRef.current;
    if (ids.length === 0 || !prev) return;
    const next = prev.filter((d) => !ids.includes(d.id));
    devicesRef.current = next;
    setDevices(next);
    onCount?.(next.length);
  }

  async function revoke(device: Device) {
    if (isCurrent(device)) return;
    setConfirming(null);
    setActionError(null);
    setRevoking((prev) => new Set(prev).add(device.id));
    try {
      await api.revokeDevice(session.token, device.id);
      removeLocally([device.id]);
      setToast(`${nameOf(device)} removed`);
    } catch (err) {
      if (isAlreadyRemoved(err)) {
        removeLocally([device.id]);
        setToast(`${nameOf(device)} was already removed`);
      } else if (!authFailed(err)) {
        setActionError(err instanceof ApiError ? err.message : "Could not remove that device.");
      }
    } finally {
      setRevoking((prev) => {
        const next = new Set(prev);
        next.delete(device.id);
        return next;
      });
    }
    await load();
  }

  /** No bulk endpoint — one DELETE per device, carrying on past failures. */
  async function revokeAllOthers() {
    const targets = others;
    setConfirmingAll(false);
    if (targets.length === 0) return;
    setActionError(null);
    setRevokingAll(true);
    const removed: string[] = [];
    let lastError: unknown = null;
    for (const device of targets) {
      try {
        await api.revokeDevice(session.token, device.id);
        removed.push(device.id);
      } catch (err) {
        if (isAlreadyRemoved(err)) {
          removed.push(device.id);
        } else if (authFailed(err)) {
          setRevokingAll(false);
          return;
        } else {
          lastError = err;
        }
      }
    }
    removeLocally(removed);
    setRevokingAll(false);
    if (lastError) {
      const failed = targets.length - removed.length;
      const reason = lastError instanceof ApiError ? lastError.message : "Try again.";
      const lead =
        targets.length === 1
          ? "The device could not be removed."
          : `${failed} of ${targets.length} devices could not be removed.`;
      setActionError(`${lead} ${reason}`);
    } else {
      setToast(removed.length === 1 ? "1 device removed" : `${removed.length} devices removed`);
    }
    await load();
  }

  function retry() {
    setLoadError(null);
    void load();
  }

  if (devices === null) {
    return (
      <SettingsGroup>
        {loadError ? (
          <div className="dev-state">
            <TriangleAlert size={22} className="dev-state-warn" aria-hidden="true" />
            <p>{loadError}</p>
            <button type="button" className="dev-link" onClick={retry}>
              Try Again
            </button>
          </div>
        ) : (
          <div className="dev-state">
            <span className="dev-spinner" aria-hidden="true" />
            <p>Loading devices…</p>
          </div>
        )}
      </SettingsGroup>
    );
  }

  const count = list.length;
  const full = count >= DEVICE_LIMIT;
  const left = DEVICE_LIMIT - count;

  function deviceRow(device: Device) {
    const mine = isCurrent(device);
    const busy = revoking.has(device.id) || revokingAll;
    return (
      <div key={device.id} className="set-row dev-row">
        <button
          type="button"
          className="dev-row-main"
          onClick={() => setDetail(device)}
          aria-label={`${nameOf(device)}, ${mine ? "this browser, active now" : lastActiveLabel(device)}. Show details`}
        >
          <DeviceTile label={labelOf(device)} />
          <span className="set-row-copy">
            <strong>{nameOf(device)}</strong>
            {mine ? (
              <span className="dev-active">
                <i className="dev-dot" aria-hidden="true" />
                Active now · This browser
              </span>
            ) : (
              <span>{lastActiveLabel(device)}</span>
            )}
          </span>
          {mine ? <ChevronRight size={16} className="set-chevron" aria-hidden="true" /> : null}
        </button>
        {!mine ? (
          busy ? (
            <span className="dev-spinner dev-busy" role="status" aria-label="Removing" />
          ) : (
            <button
              type="button"
              className="dev-link danger"
              onClick={() => setConfirming(device)}
              aria-label={`Remove ${nameOf(device)}`}
            >
              Remove
            </button>
          )
        ) : null}
      </div>
    );
  }

  return (
    <>
      <SettingsGroup title="This device">
        {current ? (
          deviceRow(current)
        ) : (
          // The server always lists the calling device; a miss means the list is stale.
          <p className="set-placeholder">This browser is missing from the list. Reload the page.</p>
        )}
      </SettingsGroup>

      {others.length > 0 ? (
        <>
          <div className="set-card">
            <button
              type="button"
              className="set-row set-row-button"
              onClick={() => setConfirmingAll(true)}
              disabled={revokingAll || revoking.size > 0}
            >
              <span className="set-tile" style={{ background: "var(--danger-bg)" }} aria-hidden="true">
                <Hand size={15} />
              </span>
              <span className="set-row-copy">
                <strong className="danger">
                  {revokingAll ? "Removing other devices…" : "Remove All Other Devices"}
                </strong>
              </span>
              {revokingAll ? <span className="dev-spinner dev-busy" aria-hidden="true" /> : null}
            </button>
          </div>
          <SettingsNote>Signs out every device except this browser.</SettingsNote>
        </>
      ) : null}

      <SettingsGroup title={others.length > 0 ? `Other devices — ${others.length}` : "Other devices"}>
        {others.length === 0 ? (
          <div className="set-row">
            <span className="set-row-copy">
              <strong>No other devices</strong>
              <span className="wrap">
                To add one, sign in on the iPhone app or another browser with your username and
                password, then unlock with your 12-word phrase.
              </span>
            </span>
          </div>
        ) : (
          others.map(deviceRow)
        )}
      </SettingsGroup>

      {actionError ? <p className="set-error">{actionError}</p> : null}

      <SettingsGroup title="Device limit">
        <div className="set-row dev-capacity">
          <span className="dev-capacity-head">
            <strong>Linked devices</strong>
            <span className={full ? "dev-capacity-count full" : "dev-capacity-count"}>
              {count} of {DEVICE_LIMIT}
            </span>
          </span>
          <span className="dev-meter" aria-hidden="true">
            {Array.from({ length: DEVICE_LIMIT }, (_, i) => (
              <i key={i} className={i < count ? (full ? "on full" : "on") : undefined} />
            ))}
          </span>
        </div>
      </SettingsGroup>
      <SettingsNote>
        {/* At the cap the server hands a new sign-in the longest-idle device nobody is signed
            in on, and refuses only when every device is live. */}
        {full
          ? "Your account is at the limit. A new sign-in takes over a device that has been logged out; if every device is still signed in, it is refused until you remove one here."
          : `You can sign in on ${left} more ${left === 1 ? "device" : "devices"}. A logged-out device stays listed until it signs in again or you remove it.`}
      </SettingsNote>

      <div className="set-explainer">
        <strong>
          <ShieldCheck size={16} aria-hidden="true" />
          Your phrase stays on each device
        </strong>
        <p>
          Every device unlocks with your 12-word phrase, which never leaves it. Device names are
          encrypted with it too, so only your own devices can read them. The server records when
          each device was linked and when it was last active — both shown here. A removed device
          erases everything of your account on it as soon as it is online or next opened.
        </p>
      </div>

      {detail ? (
        <DeviceDetail
          device={detail}
          label={labelOf(detail)}
          isCurrent={isCurrent(detail)}
          onRename={historyKey ? (name) => rename(detail, name) : null}
          isRevoking={revoking.has(detail.id) || revokingAll}
          onClose={() => setDetail(null)}
          onRevoke={() => {
            setDetail(null);
            setConfirming(detail);
          }}
        />
      ) : null}

      {confirming ? (
        <ConfirmDialog
          title={`Remove ${nameOf(confirming)}?`}
          body={revokeConsequences(false)}
          action="Remove"
          onCancel={() => setConfirming(null)}
          onConfirm={() => void revoke(confirming)}
        />
      ) : null}

      {confirmingAll ? (
        <ConfirmDialog
          title="Remove all other devices?"
          body={`Only this browser stays signed in. ${revokeConsequences(true)}`}
          action={`Remove ${others.length}`}
          onCancel={() => setConfirmingAll(false)}
          onConfirm={() => void revokeAllOthers()}
        />
      ) : null}

      {toast ? (
        <div className="set-toast" role="status">
          <Check size={16} aria-hidden="true" />
          {toast}
        </div>
      ) : null}
    </>
  );
}

/** Everything the server knows about one device, plus its actions — iOS `DeviceDetailSheet`. */
function DeviceDetail({
  device,
  label,
  isCurrent,
  isRevoking,
  onRename,
  onClose,
  onRevoke,
}: {
  device: Device;
  label: DeviceLabel | null;
  isCurrent: boolean;
  isRevoking: boolean;
  /** Null when this browser cannot seal names (no identity loaded). Resolves to an error, or null once saved. */
  onRename: ((name: string) => Promise<string | null>) | null;
  onClose: () => void;
  onRevoke: () => void;
}) {
  const kind = deviceKind(label);
  const deviceId = device.id.toLowerCase();
  const [draft, setDraft] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [renameError, setRenameError] = useState<string | null>(null);
  const renamable = onRename !== null;
  const cleaned = draft === null ? "" : normalizeDeviceName(draft);

  async function submitRename(event: FormEvent) {
    event.preventDefault();
    if (!onRename || !cleaned || saving) return;
    setSaving(true);
    setRenameError(null);
    const error = await onRename(cleaned);
    setSaving(false);
    if (error) setRenameError(error);
    else setDraft(null);
  }

  return (
    <div className="modal-scrim" onMouseDown={onClose}>
      <div
        className="modal dev-detail"
        role="dialog"
        aria-modal="true"
        aria-labelledby="dev-detail-title"
        onMouseDown={(event) => event.stopPropagation()}
      >
        <div className="info-sheet">
          <DeviceTile label={label} size={64} />
          <strong id="dev-detail-title">{displayName(label)}</strong>
          <span className={isCurrent ? "dev-active" : undefined}>
            {isCurrent ? "This browser · Active now" : lastActiveLabel(device)}
          </span>
        </div>

        <div className="set-card">
          <div className="set-row dev-info">
            <span>Type</span>
            <span>{kind.label}</span>
          </div>
          <div className="set-row dev-info">
            <span>Linked</span>
            <span>{fullTimestamp(device.created_at)}</span>
          </div>
          <div className="set-row dev-info">
            <span>Last active</span>
            <span>
              {isCurrent
                ? "Now"
                : device.last_seen_at
                  ? fullTimestamp(device.last_seen_at)
                  : "Never"}
            </span>
          </div>
          <div className="set-row dev-info">
            <span>Device ID</span>
            <code>{deviceId}</code>
            <CopyButton value={deviceId} label="device ID" />
          </div>
        </div>

        {draft !== null ? (
          <form className="dev-rename" onSubmit={submitRename}>
            <label htmlFor="dev-rename-input">Device name</label>
            <input
              id="dev-rename-input"
              className="afield-input"
              value={draft}
              onChange={(event) => setDraft(event.target.value)}
              maxLength={DEVICE_NAME_MAX_BYTES}
              autoComplete="off"
              spellCheck={false}
              autoFocus
            />
            {renameError ? <p className="dev-rename-error" role="alert">{renameError}</p> : null}
            <p>Encrypted — only your devices can read it.</p>
            <div className="modal-actions">
              <button
                type="button"
                className="btn btn-secondary"
                onClick={() => {
                  setDraft(null);
                  setRenameError(null);
                }}
              >
                Cancel
              </button>
              <button type="submit" className="btn btn-primary" disabled={!cleaned || saving}>
                {saving ? "Saving…" : "Save"}
              </button>
            </div>
          </form>
        ) : null}

        {isCurrent ? (
          <p>
            To remove this browser from your account, use Log Out in Settings. It also erases
            everything Shroud keeps here.
          </p>
        ) : null}

        <div className="modal-actions" hidden={draft !== null}>
          <button type="button" className="btn btn-secondary" onClick={onClose} autoFocus>
            Done
          </button>
          {renamable ? (
            <button
              type="button"
              className="btn btn-secondary"
              onClick={() => setDraft(label?.name ?? "")}
            >
              Rename
            </button>
          ) : null}
          {!isCurrent ? (
            <button
              type="button"
              className="btn btn-destructive"
              onClick={onRevoke}
              disabled={isRevoking}
            >
              {isRevoking ? "Removing…" : "Remove Device"}
            </button>
          ) : null}
        </div>
      </div>
    </div>
  );
}
