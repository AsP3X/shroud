export type SettingsRoute =
  | "notifications"
  | "devices"
  | "privacy"
  | "delete-account"
  | "data"
  | "appearance"
  | "server"
  | "about"
  | "licenses";

export const SETTINGS_TITLES: Record<SettingsRoute, string> = {
  notifications: "Notifications and Sounds",
  devices: "Devices",
  privacy: "Privacy and Security",
  "delete-account": "Delete Account",
  data: "Data and Storage",
  appearance: "Appearance",
  server: "Server",
  about: "About Shroud",
  licenses: "Open-Source Licenses",
};

/** The line under each page's title. Delete Account carries its own. */
export const SETTINGS_INTROS: Partial<Record<SettingsRoute, string>> = {
  notifications: "Delivered by the browser while this tab is closed or locked, and by the page while it is open.",
  devices: "The devices signed in to your account. Up to five share it, and each can remove another.",
  privacy: "Messages decrypt only on your devices. Compare safety numbers in person before you trust a change.",
  data: "Messages and previews are held encrypted in this browser until you clear them.",
  appearance: "How the client looks on this device. The theme follows the system unless you pin one.",
  server: "The server this client talks to. It only ever sees ciphertext.",
  about: "This build, the server it talks to, and the code it runs.",
  licenses: "The open-source software this client is built from.",
};

/** Pages under another page: Back goes there instead of to Settings. */
export const SETTINGS_PARENTS: Partial<Record<SettingsRoute, SettingsRoute>> = {
  licenses: "about",
  "delete-account": "privacy",
};
