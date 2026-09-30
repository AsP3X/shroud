/**
 * Nothing of the account is readable from storage: a pre-vault browser is sealed on its first
 * unlock (session token included), a lock makes everything unreadable, the PIN only works with
 * the server's pepper and a limited number of tries, and "Forgot PIN" opens the vault with the
 * phrase.
 * Run: npx esbuild src/crypto/vault.selftest.ts --bundle --platform=node --format=esm --outfile=/tmp/v.mjs && node /tmp/v.mjs
 */

function memoryStorage(map: Map<string, string>): Storage {
  return {
    getItem: (key: string) => (map.has(key) ? map.get(key)! : null),
    setItem: (key: string, value: string) => {
      map.set(key, String(value));
    },
    removeItem: (key: string) => {
      map.delete(key);
    },
    key: (index: number) => [...map.keys()][index] ?? null,
    get length() {
      return map.size;
    },
    clear: () => map.clear(),
  };
}

const memory = new Map<string, string>();
Object.defineProperty(globalThis, "localStorage", { value: memoryStorage(memory), configurable: true });
Object.defineProperty(globalThis, "sessionStorage", { value: memoryStorage(new Map()), configurable: true });
Object.defineProperty(globalThis, "window", { value: globalThis, configurable: true });

/* A stand-in for the server's PIN guard (server/.../routes/pin_guard.rs), same rules. */
type Guard = { verifier: string; pepper: string; failed: number };
const guards = new Map<string, Guard>();
let online = true;
let requests = 0;
const b64 = (bytes: Uint8Array) => btoa(String.fromCharCode(...bytes));
async function sha256b64(value: string): Promise<string> {
  const raw = Uint8Array.from(atob(value), (c) => c.charCodeAt(0));
  return b64(new Uint8Array(await crypto.subtle.digest("SHA-256", raw)));
}
function reply(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}
globalThis.fetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
  if (!online) throw new TypeError("Failed to fetch");
  requests++;
  const url = String(input);
  const body = JSON.parse(String(init?.body ?? "{}")) as Record<string, string>;
  if (url.endsWith("/pin-guard")) {
    const auth = new Headers(init?.headers).get("Authorization");
    if (auth !== "Bearer legacy-token-XYZ") return reply(401, { error: { code: "UNAUTHORIZED" } });
    const id = crypto.randomUUID();
    const pepper = b64(crypto.getRandomValues(new Uint8Array(32)));
    guards.clear(); // one guard per device
    guards.set(id, { verifier: body.verifier, pepper, failed: 0 });
    return reply(201, { guard_id: id, pepper, max_attempts: 10 });
  }
  if (url.endsWith("/pin-guard/unlock")) {
    const guard = guards.get(body.guard_id);
    if (!guard) return reply(410, { error: { code: "PIN_GUARD_GONE", message: "gone" } });
    if ((await sha256b64(body.auth_key)) === guard.verifier) {
      guard.failed = 0;
      return reply(200, { pepper: guard.pepper });
    }
    guard.failed++;
    if (guard.failed >= 10) {
      guards.delete(body.guard_id);
      return reply(410, { error: { code: "PIN_GUARD_GONE", message: "gone" } });
    }
    return reply(403, { error: { code: "PIN_INCORRECT", message: `Wrong PIN. ${10 - guard.failed} attempts left.` } });
  }
  return reply(404, {});
}) as typeof fetch;

const { establish, historyKeyFromMnemonic, serializeIdentity } = await import("./identity");
const { loadIdentity } = await import("./store");
const { loadPlaintext, loadPreview, savePlaintext } = await import("./plaintextCache");
const { closeVault, derivePinSecrets, isVaultOpen, openVaultWithPhrase, openVaultWithPin, vaultPinGuard } =
  await import("./vault");
const { clearPin, hasPin, needsPhrase, setPin, unlockWithPin } = await import("./vaultAccess");
const { loadSession } = await import("../session");
const { expectedLanguage } = await import("../voice/language");

function check(ok: boolean, message: string): void {
  if (!ok) throw new Error(message);
}

const phrase = Array.from({ length: 11 }, () => "abandon").concat("about");
const me = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
const peer = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";
const messageId = "cccccccc-cccc-4ccc-8ccc-cccccccccccc";
const token = "legacy-token-XYZ";
const identity = establish(phrase, me, 2);

async function sha256Hex(value: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value));
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

/* A browser from before the vault: everything in the clear, and a SHA-256 PIN hash. */
const secret = "the transcript nobody may read";
memory.set(
  "shroud.session",
  JSON.stringify({ token, user: { id: me, username: "me", share_code: "x" }, device: { id: "d", name: null } }),
);
memory.set("shroud.last-active", String(Date.now()));
memory.set(`shroud.identity.${me}`, serializeIdentity(identity));
memory.set(`shroud.pin.${me}`, JSON.stringify({ salt: "ab", hash: await sha256Hex("ab:123456") }));
memory.set(`shroud.pt.${messageId}`, JSON.stringify({ t: "voice", c: secret }));
memory.set(
  `shroud.preview.${me}.${peer}`,
  JSON.stringify({ id: messageId, text: secret, at: "2026-09-23T00:00:00.000Z", isMine: false }),
);
memory.set(`shroud.ratchet.${me}.${peer}`, JSON.stringify({ secret }));
memory.set("transcription.languageStats", JSON.stringify({ [peer]: { de: 3 }, "*": { de: 3 } }));

check(hasPin(me), "the legacy PIN still unlocks");
check(!(await unlockWithPin(me, "654321")).ok, "a wrong legacy PIN must fail");
check((await unlockWithPin(me, "123456")).ok, "the legacy PIN unlocks and creates the vault");
check(guards.size === 1, "the first unlock registers a PIN guard with the server");

function assertNothingReadable(): void {
  for (const [name, value] of memory) {
    check(!value.includes(secret), `plaintext left under ${name}`);
    check(!value.includes(token), `session token left in the clear under ${name}`);
    check(!name.includes(messageId) && !name.includes(peer), `id left in the name ${name}`);
    check(!value.includes(peer), `peer id left in the value of ${name}`);
    check(!name.startsWith("shroud.pin."), "the legacy PIN hash must be gone");
  }
  check(!memory.get(`shroud.identity.${me}`)?.includes("agreementPrivate"), "identity sealed");
}
assertNothingReadable();

check(loadSession()?.token === token, "unlocked: the token reads back from the vault");
check(JSON.parse(loadPlaintext(messageId) ?? "{}").c === secret, "the body opens after migration");
check(loadPreview(me, peer)?.text === secret, "the preview opens after migration");
check(loadIdentity(me)?.userId === me, "the identity opens after migration");
check(expectedLanguage(peer) === "de", "language statistics survive migration");

/* Locked: nothing reads, nothing writes. */
closeVault();
check(loadPlaintext(messageId) === null, "locked: body unreadable");
check(loadPreview(me, peer) === null, "locked: preview unreadable");
check(loadIdentity(me) === null, "locked: identity unreadable");
const before = memory.size;
savePlaintext("dddddddd-dddd-4ddd-8ddd-dddddddddddd", "written while locked");
check(memory.size === before, "locked: nothing is written");
check(![...memory.values()].some((v) => v.includes("written while locked")), "locked: no plaintext");

/* A copy of storage without the server: even the right PIN does not open it. */
const guard = vaultPinGuard(me)!;
const offlineGuess = await derivePinSecrets("123456", guard.salt, guard.iter);
check(
  !openVaultWithPin(me, { secrets: offlineGuess, pepper: new Uint8Array(32), guardId: guard.guardId }),
  "the right PIN without the server's pepper must not open the vault",
);

online = false;
const offline = await unlockWithPin(me, "000000");
check(!offline.ok && offline.kind === "offline", "unreachable server: reported, not counted");
online = true;
const wrong = await unlockWithPin(me, "000000");
check(!wrong.ok && wrong.kind === "wrong" && wrong.message.includes("9"), "wrong PIN: counted by the server");
check(!isVaultOpen(me), "wrong PIN leaves it closed");
check((await unlockWithPin(me, "123456")).ok, "right PIN opens the vault");
check(loadPlaintext(messageId) !== null, "unlocked: body readable again");

/* Sealed values are bound to their names: swapping two does not open either. */
const [nameA, nameB] = [...memory.keys()].filter((n) => n.startsWith("shroud.pt.") || n.startsWith("shroud.preview."));
const valueA = memory.get(nameA)!;
memory.set(nameA, memory.get(nameB)!);
check(loadPlaintext(messageId) === null || loadPreview(me, peer) === null, "a swapped value must not open");
memory.set(nameA, valueA);

/* Ten wrong PINs in a row: the server deletes its pepper; only the phrase is left. */
closeVault();
let last = await unlockWithPin(me, "111111");
for (let i = 1; i < 10 && !(!last.ok && last.kind === "gone"); i++) last = await unlockWithPin(me, "111111");
check(!last.ok && last.kind === "gone", "the tenth wrong PIN ends the guard");
check(!hasPin(me) && needsPhrase(me), "after the guard is gone only the phrase opens the vault");
const afterGone = requests;
check(!(await unlockWithPin(me, "123456")).ok, "the right PIN no longer opens it");
check(requests === afterGone, "no more guesses reach the server once the guard is gone");

/* Forgot PIN: the phrase opens it, then a new PIN (and guard) is chosen. */
clearPin(me);
const wrongPhrase = historyKeyFromMnemonic(
  "legal winner thank year wave sausage worth useful legal winner thank yellow".split(" "),
);
check(!openVaultWithPhrase(me, wrongPhrase), "a different phrase must not open the vault");
check(openVaultWithPhrase(me, historyKeyFromMnemonic(phrase)), "the phrase opens the vault");
await setPin(me, "246810");
closeVault();
check(!(await unlockWithPin(me, "123456")).ok, "the old PIN no longer opens it");
check((await unlockWithPin(me, "246810")).ok, "the new PIN opens it");
check(JSON.parse(loadPlaintext(messageId) ?? "{}").c === secret, "the history survives Forgot PIN");
assertNothingReadable();

console.log("vault selftest ok");
