import type { ComponentType } from "react";
import { Bell, Database, Info, Lock, MonitorSmartphone, Palette, User } from "lucide-react";

/** The seven sections in the settings list, and the pages that open under one of them. */
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

/** The line under each page's title. Delete account carries its own. */
export const SETTINGS_INTROS: Partial<Record<SettingsRoute, string>> = {
  account: "Who you are signed in as, how people add you, and the way out.",
  devices: "The devices signed in to your account. Up to five share it.",
  notifications: "Delivered by the browser while this tab is closed or locked, and by the page while it is open.",
  appearance: "How the client looks on this device. The theme follows the system unless you pin one.",
  privacy: "What your contacts see of you, and what keeps this browser's chats sealed.",
  data: "Messages and previews are held encrypted in this browser until you clear them.",
  about: "This client, its updates, and the server it talks to. The server only ever sees ciphertext.",
  licenses: "The open-source software this client is built from.",
};

/** Pages under a section: Back goes there, and the section stays lit in the list. */
export const SETTINGS_PARENTS: Partial<Record<SettingsRoute, SettingsSection>> = {
  licenses: "about",
  "delete-account": "account",
};

export const SETTINGS_SECTIONS: { id: SettingsSection; Icon: ComponentType<{ size?: number }> }[] = [
  { id: "account", Icon: User },
  { id: "devices", Icon: MonitorSmartphone },
  { id: "notifications", Icon: Bell },
  { id: "appearance", Icon: Palette },
  { id: "privacy", Icon: Lock },
  { id: "data", Icon: Database },
  { id: "about", Icon: Info },
];

export function sectionOf(route: SettingsRoute): SettingsSection {
  return SETTINGS_PARENTS[route] ?? (route as SettingsSection);
}
