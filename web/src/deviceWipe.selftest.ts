import {
  describeStoredLeftovers,
  hasOrphanedAccountData,
  isKeyMaterialKey,
  isMessageKey,
  isPreservedStorageKey,
  removeKeys,
  storageKeys,
} from "./deviceWipe";

/*
 * Selftest for the parts of the logout wipe that decide what is account data. IndexedDB, Cache
 * Storage and the dialog run in the browser; what happens to local storage is decided here.
 */

class FakeStorage {
  private map = new Map<string, string>();
  constructor(entries: Record<string, string> = {}) {
    for (const [k, v] of Object.entries(entries)) this.map.set(k, v);
  }
  get length(): number {
    return this.map.size;
  }
  key(index: number): string | null {
    return [...this.map.keys()][index] ?? null;
  }
  getItem(key: string): string | null {
    return this.map.get(key) ?? null;
  }
  removeItem(key: string): void {
    this.map.delete(key);
  }
}

function check(condition: boolean, message: string): void {
  if (!condition) throw new Error(message);
}

const ME = "6ba7b810-9dad-11d1-80b4-00c04fd430c8";
const PEER = "3f2504e0-4f89-41d3-9a0c-0305e82c3301";

/** A browser in the middle of a session, with every kind of key the client writes. */
function signedIn(): Record<string, string> {
  return {
    "shroud.session": "{}",
    "shroud.token-hash": "ungWv48Bz+pBQUDeXa4iI7ADYaOWF3qctBD/YfIAFa0=",
    "shroud.last-active": "1",
    "shroud.device-anchor": "{}",
    [`shroud.identity.${ME}`]: "secret",
    [`shroud.ratchet.${ME}.${PEER}`]: "state",
    [`shroud.pin.${ME}`]: "{}",
    [`shroud.pt.${PEER}`]: "hello",
    [`shroud.preview.${ME}.${PEER}`]: "{}",
    "shroud.theme": "dark",
    "shroud.lock-on-hidden": "1",
    "transcription.languageStats": "{}",
    "transcription.locale": "de",
    "transcription.model": "base",
  };
}

/* --- classification ------------------------------------------------------ */

check(isMessageKey(`shroud.pt.${PEER}`) && isMessageKey(`shroud.preview.${ME}.${PEER}`), "message keys");
check(!isMessageKey("shroud.pin.x") && !isMessageKey("shroud.theme"), "not message keys");
check(
  isKeyMaterialKey(`shroud.identity.${ME}`) &&
    isKeyMaterialKey(`shroud.ratchet.${ME}.${PEER}`) &&
    isKeyMaterialKey(`shroud.pin.${ME}`),
  "key material",
);
check(!isKeyMaterialKey("shroud.session") && !isKeyMaterialKey("shroud.device-anchor"), "not key material");
// The lock screen's removal check reads it in the clear; the wipe removes it with the keys.
check(isKeyMaterialKey("shroud.token-hash"), "the token hash goes with the keys");

/* --- removal ------------------------------------------------------------- */

{
  const store = new FakeStorage(signedIn());
  // Removing while iterating reindexes a real Storage; the helper must read all keys first.
  check(removeKeys(store, isMessageKey) === 2, "removeKeys counts what it removed");
  check(storageKeys(store).every((key) => !isMessageKey(key)), "message keys gone");
  check(removeKeys(store, () => true) === 12, "everything else");
  check(store.length === 0, "store empty");
}

/* --- when a page load may sweep ------------------------------------------ */

check(!hasOrphanedAccountData(new FakeStorage(signedIn())), "never sweep a signed-in browser");
check(
  !hasOrphanedAccountData(new FakeStorage({ "shroud.session": "{}", "shroud.wipe-pending": "1" })),
  "a newer sign-in outranks an old marker",
);
check(!hasOrphanedAccountData(new FakeStorage()), "an empty browser has nothing to sweep");
check(
  !hasOrphanedAccountData(new FakeStorage({ "shroud.theme": "light", "transcription.model": "tiny" })),
  "preferences chosen while signed out are left alone",
);
check(hasOrphanedAccountData(new FakeStorage({ "shroud.wipe-pending": "1" })), "an unfinished wipe");
{
  // An old logout: token gone, keys and messages still there.
  const leftover = signedIn();
  delete leftover["shroud.session"];
  check(hasOrphanedAccountData(new FakeStorage(leftover)), "a logout from before the wipe existed");
}
check(
  hasOrphanedAccountData(new FakeStorage({ "shroud.device-anchor": "{}" })),
  "a device anchor left by an older logout names the account",
);
check(
  hasOrphanedAccountData(new FakeStorage({ "transcription.languageStats": "{}" })),
  "language statistics come from the account's voice notes",
);
check(
  hasOrphanedAccountData(new FakeStorage({ "shroud.token-hash": "x" })),
  "a token hash without its session is left over from one",
);

/* --- what verify reports ------------------------------------------------- */

check(describeStoredLeftovers(new FakeStorage({ "shroud.wipe-pending": "1" })).length === 0, "marker alone is clean");
check(
  describeStoredLeftovers(new FakeStorage({ "shroud.device-anchor": "{}" })).length === 1,
  "the device anchor is a leftover",
);
{
  const found = describeStoredLeftovers(new FakeStorage({ "shroud.token-hash": "x" }));
  check(found.length === 1 && found[0].step === "keys", "verify reports a token hash against the keys step");
}
{
  const store = new FakeStorage({
    "shroud.device-anchor": "{\"deviceId\":\"x\"}",
    "shroud.theme": "dark",
    "shroud.wipe-pending": "1",
    [`shroud.identity.${ME}`]: "secret",
  });
  removeKeys(store, (key) => !isPreservedStorageKey(key));
  check(store.getItem("shroud.device-anchor") === null, "anchor cleared");
  check(store.getItem("shroud.wipe-pending") === "1", "marker kept");
  check(store.getItem("shroud.theme") === null, "theme cleared");
  check(store.getItem(`shroud.identity.${ME}`) === null, "identity cleared");
  check(describeStoredLeftovers(store).length === 0, "the marker is not a leftover");
}
{
  const found = describeStoredLeftovers(new FakeStorage(signedIn()));
  const named = found.map((l) => `${l.step}:${l.label}`).join(",");
  check(
    named === "messages:messages,keys:encryption keys,settings:settings",
    `every kind is named once, against the step that owns it: ${named}`,
  );
}

console.log("device wipe selftest ok");
