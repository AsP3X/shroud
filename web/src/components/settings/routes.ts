import type { ComponentType } from "react";
import { Bell, Database, Lock, Palette, Server, User } from "lucide-react";

/** The six sections in the settings list, and the pages that open under one of them. */
export type SettingsSection = "account" | "notifications" | "appearance" | "privacy" | "data" | "server";
export type SettingsRoute = SettingsSection | "delete-account" | "licenses";

export const SETTINGS_TITLES: Record<SettingsRoute, string> = {
  account: "Account",
  notifications: "Notifications and sounds",
  appearance: "Appearance",
  privacy: "Privacy and security",
  data: "Data and storage",
  server: "Server",
  "delete-account": "Delete account",
  licenses: "Open-source licenses",
};

/** The line under each page's title. Delete account carries its own. */
export const SETTINGS_INTROS: Partial<Record<SettingsRoute, string>> = {
  account: "Your username and the devices signed in to it. Up to five devices share the account.",
  notifications: "Delivered by the browser while this tab is closed or locked, and by the page while it is open.",
  appearance: "How the client looks on this device. The theme follows the system unless you pin one.",
  privacy: "Messages decrypt only on your devices. Compare safety numbers in person before you trust a change.",
  data: "Messages and previews are held encrypted in this browser until you clear them.",
  server: "The server this client talks to. It only ever sees ciphertext.",
  licenses: "The open-source software this client is built from.",
};

/** Pages under a section: Back goes there, and the section stays lit in the list. */
export const SETTINGS_PARENTS: Partial<Record<SettingsRoute, SettingsSection>> = {
  licenses: "server",
  "delete-account": "privacy",
};

export const SETTINGS_SECTIONS: { id: SettingsSection; Icon: ComponentType<{ size?: number }> }[] = [
  { id: "account", Icon: User },
  { id: "notifications", Icon: Bell },
  { id: "appearance", Icon: Palette },
  { id: "privacy", Icon: Lock },
  { id: "data", Icon: Database },
  { id: "server", Icon: Server },
];

export function sectionOf(route: SettingsRoute): SettingsSection {
  return SETTINGS_PARENTS[route] ?? (route as SettingsSection);
}
