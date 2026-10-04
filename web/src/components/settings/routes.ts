export type SettingsRoute =
  | "notifications"
  | "devices"
  | "privacy"
  | "data"
  | "appearance"
  | "server"
  | "about"
  | "licenses";

export const SETTINGS_TITLES: Record<SettingsRoute, string> = {
  notifications: "Notifications and Sounds",
  devices: "Devices",
  privacy: "Privacy and Security",
  data: "Data and Storage",
  appearance: "Appearance",
  server: "Server",
  about: "About Shroud",
  licenses: "Open-Source Licenses",
};

/** Pages under another page: Back goes there instead of to Settings. */
export const SETTINGS_PARENTS: Partial<Record<SettingsRoute, SettingsRoute>> = {
  licenses: "about",
};
