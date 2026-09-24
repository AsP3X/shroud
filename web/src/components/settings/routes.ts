export type SettingsRoute =
  | "notifications"
  | "devices"
  | "privacy"
  | "data"
  | "appearance"
  | "server";

export const SETTINGS_TITLES: Record<SettingsRoute, string> = {
  notifications: "Notifications and Sounds",
  devices: "Devices",
  privacy: "Privacy and Security",
  data: "Data and Storage",
  appearance: "Appearance",
  server: "Server",
};
