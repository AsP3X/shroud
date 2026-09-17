export type SettingsRoute = "devices" | "privacy" | "data" | "appearance" | "server";

export const SETTINGS_TITLES: Record<SettingsRoute, string> = {
  devices: "Devices",
  privacy: "Privacy and Security",
  data: "Data and Storage",
  appearance: "Appearance",
  server: "Server",
};
