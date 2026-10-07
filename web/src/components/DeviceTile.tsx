import type { ComponentType } from "react";
import { Globe, Laptop, Monitor, MonitorSmartphone, Smartphone, Tablet } from "lucide-react";
import type { DeviceLabel } from "../crypto/deviceName";

/*
 * A device's coloured icon tile: Settings → Devices, and the sign-in's device-limit dialog. Kept
 * out of the settings views so the sign-in flow doesn't pull them into the first download.
 */

type IconProps = { size?: number };
type DeviceKind = { label: string; Icon: ComponentType<IconProps>; tint: string };

/**
 * What a device is: the kind sealed with its name, else a guess from the name — the same rules
 * as iOS `DeviceKind` (`DevicesView.swift:784-846`). The Android app seals kind 4 (port plan
 * decision P4): "Android app", the phone icon, green. A name that only looks like a phone keeps
 * the guess "Phone" — it may be an Android device renamed by a client that dropped its kind.
 */
export function deviceKind(label: DeviceLabel | null): DeviceKind {
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

/** Also the login's "Log out your oldest device?" card, with no label: the "Unknown" glyph. */
export function DeviceTile({ label, size = 30 }: { label: DeviceLabel | null; size?: number }) {
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
