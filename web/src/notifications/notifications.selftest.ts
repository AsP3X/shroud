/**
 * Notification preferences, mutes, and what an arrival does: nothing, a sound, or a system
 * notification.
 * Run: npx esbuild src/notifications/notifications.selftest.ts --bundle --platform=node --format=esm | node --input-type=module
 */

const memory = new Map<string, string>();
Object.defineProperty(globalThis, "localStorage", {
  value: {
    getItem: (key: string) => (memory.has(key) ? memory.get(key)! : null),
    setItem: (key: string, value: string) => void memory.set(key, value),
    removeItem: (key: string) => void memory.delete(key),
    key: (index: number) => [...memory.keys()][index] ?? null,
    get length() {
      return memory.size;
    },
  },
  configurable: true,
});
Object.defineProperty(globalThis, "window", {
  value: { addEventListener() {}, removeEventListener() {} },
  configurable: true,
});

/* The page's view of the world, set per case. */
const page = { hidden: false, focused: true };
Object.defineProperty(globalThis, "document", {
  value: {
    get hidden() {
      return page.hidden;
    },
    hasFocus: () => page.focused,
    title: "Shroud",
    querySelector: () => null,
  },
  configurable: true,
});
const played: string[] = [];
class FakeAudio {
  preload = "";
  volume = 1;
  currentTime = 0;
  constructor(readonly src: string) {}
  play() {
    played.push(this.src);
    return Promise.resolve();
  }
  pause() {}
}
Object.defineProperty(globalThis, "Audio", { value: FakeAudio, configurable: true });
const shown: { title: string; body?: string; tag?: string; silent?: boolean }[] = [];
class FakeNotification {
  static permission: NotificationPermission = "granted";
  onclick: (() => void) | null = null;
  constructor(title: string, options: NotificationOptions) {
    shown.push({ title, body: options.body, tag: options.tag, silent: options.silent ?? undefined });
  }
  close() {}
}
Object.defineProperty(globalThis, "Notification", { value: FakeNotification, configurable: true });

const { DEFAULT_PREFS, loadNotificationPrefs, serverSettings, updateNotificationPrefs } = await import("./prefs");
const { isMuted, muteLabel, MUTE_CHOICES } = await import("./mute");
const { announce } = await import("./attention");
const { muteSeconds } = await import("../components/ChatMenu");

function check(ok: boolean, what: string): void {
  if (!ok) throw new Error(`notifications selftest: ${what}`);
}
const settle = () => new Promise((resolve) => setTimeout(resolve, 0));

/* --- preferences --- */
check(loadNotificationPrefs() === DEFAULT_PREFS, "no stored preferences are the defaults");
check(!DEFAULT_PREFS.enabled && !DEFAULT_PREFS.showPreview, "off, and no text, until asked");
memory.set("shroud.notifications", "{not json");
check(loadNotificationPrefs().sound === "note", "garbage reads as the defaults");
memory.set("shroud.notifications", JSON.stringify({ enabled: true, sound: "../x", reactions: "yes" }));
const odd = loadNotificationPrefs();
check(odd.enabled && odd.sound === "note" && odd.reactions, "bad fields fall back one by one");
const saved = updateNotificationPrefs({ sound: "none", badge: false });
check(saved.enabled && saved.sound === "none" && !saved.badge, "an update keeps the rest");
check(loadNotificationPrefs() === loadNotificationPrefs(), "reads are stable for React");
check(JSON.parse(memory.get("shroud.notifications")!).sound === "none", "an update is stored");
const server = serverSettings(saved);
check(server.sound === "none" && server.badge === false && server.enabled === true, "server gets what it acts on");
check(serverSettings({ ...saved, sound: "chime" }).sound === "default", "a browser plays the system's sound");
check(!("show_preview" in server), "message text is never a server setting");

/* --- mutes --- */
const now = Date.parse("2026-09-24T12:00:00Z");
check(!isMuted(null, now) && !isMuted(undefined, now), "no mute");
check(isMuted({ until: null }, now), "until unmuted");
check(isMuted({ until: "2026-09-24T13:00:00Z" }, now), "an hour to go");
check(!isMuted({ until: "2026-09-24T11:59:00Z" }, now), "a past mute is over");
check(muteLabel({ until: null }, now) === "Muted", "forever reads Muted");
check(muteLabel(null, now) === "On", "unmuted reads On");
check(muteLabel({ until: "2026-09-24T11:00:00Z" }, now) === "On", "an expired mute reads On");
check(/^Muted until /.test(muteLabel({ until: "2026-09-24T18:00:00Z" }, now)), "a timed mute names its end");
check(MUTE_CHOICES[MUTE_CHOICES.length - 1].seconds === null, "the last choice has no end");
check(muteSeconds("mute:3600") === 3600 && muteSeconds("mute:forever") === null, "menu ids parse");
check(muteSeconds("read") === undefined && muteSeconds("unmute") === undefined, "other actions are not mutes");

/* --- arrivals --- */
updateNotificationPrefs({ ...DEFAULT_PREFS, enabled: true, sound: "chime" });
const arrival = {
  kind: "message" as const,
  tag: "conv-1",
  peer: "peer-1",
  sender: "alice",
  text: "see you at 8",
  muted: false,
  lookingAtThis: false,
};

page.hidden = false;
page.focused = true;
announce({ ...arrival, lookingAtThis: true });
await settle();
check(played.length === 0 && shown.length === 0, "the chat being read stays quiet");

announce(arrival);
await settle();
check(played.length === 1 && played[0] === "/sounds/chime.wav", "another chat rings while the window is watched");
check(shown.length === 0, "a watched window gets no system notification");

announce({ ...arrival, muted: true });
await settle();
check(played.length === 1, "a muted chat is silent");

page.focused = false;
announce(arrival);
await settle();
check(shown.length === 1, "an unwatched window gets a notification");
check(shown[0].title === "alice" && shown[0].body === "New message", "name, but no text by default");
check(shown[0].tag === "conv-1" && shown[0].silent === false, "grouped per chat, with sound");

updateNotificationPrefs({ showPreview: true, showSender: false, sound: "none" });
announce(arrival);
await settle();
check(shown[1].title === "Shroud" && shown[1].body === "see you at 8", "text when asked, no name when not");
check(shown[1].silent === true, "None silences system notifications too");

updateNotificationPrefs({ reactions: false });
announce({ ...arrival, kind: "reaction", text: null });
await settle();
check(shown.length === 2, "reactions off: nothing");

updateNotificationPrefs({ contactRequests: true });
announce({ ...arrival, kind: "contact_request", tag: "contacts", muted: true, text: null });
await settle();
check(shown.length === 3 && shown[2].body === "Wants to add you as a contact", "requests ignore chat mutes");

updateNotificationPrefs({ enabled: false, sound: "pop" });
// A burst rings once: the next sound waits out the pause after the last one.
await new Promise((resolve) => setTimeout(resolve, 1300));
announce(arrival);
await settle();
check(shown.length === 3 && played[played.length - 1] === "/sounds/pop.wav", "notifications off: a sound instead");

console.log("notifications selftest ok");
