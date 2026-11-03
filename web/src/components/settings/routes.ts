import type { ComponentType } from "react";
import {
  Bell,
  Database,
  Info,
  Lock,
  MonitorSmartphone,
  Palette,
  ScrollText,
  Trash2,
  User,
} from "lucide-react";

/** The seven sections of settings, and the pages that open under one of them. */
export type SettingsSection =
  | "account"
  | "devices"
  | "notifications"
  | "appearance"
  | "privacy"
  | "data"
  | "about";
export type SettingsRoute = SettingsSection | "delete-account" | "licenses";

export const SETTINGS_TITLES: Record<SettingsRoute, string> = {
  account: "Account",
  devices: "Devices",
  notifications: "Notifications and sounds",
  appearance: "Appearance",
  privacy: "Privacy and security",
  data: "Data and storage",
  about: "About",
  "delete-account": "Delete account",
  licenses: "Open-source licenses",
};

/** The line under a page's name in its bar: one short sentence, since the bar never wraps. */
export const SETTINGS_INTROS: Record<SettingsRoute, string> = {
  account: "Who you are, how people add you, and the way out.",
  devices: "Everything signed in to your account. Up to five share it.",
  notifications: "What reaches you while Shroud is open, closed or locked.",
  appearance: "Theme and logo, on this browser only.",
  privacy: "Locking, what contacts see of you, and this browser’s keys.",
  data: "What this browser keeps, sealed under your PIN.",
  about: "This client, its updates, and the server it talks to.",
  "delete-account": "This cannot be undone. Your password confirms it.",
  licenses: "The open-source software this client is built from.",
};

/** The glyph in a page's bar, and in the list for a section. */
export const SETTINGS_ICONS: Record<SettingsRoute, ComponentType<{ size?: number }>> = {
  account: User,
  devices: MonitorSmartphone,
  notifications: Bell,
  appearance: Palette,
  privacy: Lock,
  data: Database,
  about: Info,
  "delete-account": Trash2,
  licenses: ScrollText,
};

/** Pages under a section: Back goes there, and the section stays lit in the list. */
export const SETTINGS_PARENTS: Partial<Record<SettingsRoute, SettingsSection>> = {
  licenses: "about",
  "delete-account": "account",
};

/** The sections listed as rows. Account is the profile card above them. */
export const SETTINGS_SECTIONS: SettingsSection[] = [
  "devices",
  "notifications",
  "appearance",
  "privacy",
  "data",
  "about",
];

export function sectionOf(route: SettingsRoute): SettingsSection {
  return SETTINGS_PARENTS[route] ?? (route as SettingsSection);
}
