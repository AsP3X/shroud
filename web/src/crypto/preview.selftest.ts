/**
 * A deleted message must not come back through the plaintext cache or the chat-list line.
 * Run: node --experimental-strip-types src/crypto/preview.selftest.ts
 */

const memory = new Map<string, string>();
const storage = {
  getItem: (key: string) => (memory.has(key) ? memory.get(key)! : null),
  setItem: (key: string, value: string) => {
    memory.set(key, value);
  },
  removeItem: (key: string) => {
    memory.delete(key);
  },
  key: (index: number) => [...memory.keys()][index] ?? null,
  get length() {
    return memory.size;
  },
};
Object.defineProperty(globalThis, "localStorage", { value: storage, configurable: true });

const { forgetPlaintext, loadPlaintext, loadPreview, redactPreviewsFor, replacePreview, savePlaintext, savePreview } =
  await import("./plaintextCache.ts");

const me = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
const peer = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";
const messageId = "CCCCCCCC-CCCC-4CCC-8CCC-CCCCCCCCCCCC";

savePlaintext(messageId, "secret words");
if (loadPlaintext(messageId) !== "secret words") throw new Error("plaintext should round-trip");

savePreview(me, peer, {
  id: messageId,
  text: "secret words",
  at: "2026-09-22T00:00:00.000Z",
  isMine: true,
});

forgetPlaintext(messageId);
if (loadPlaintext(messageId) !== null) throw new Error("forgotten plaintext must not be readable");
savePlaintext(messageId, "secret words");
if (loadPlaintext(messageId) !== null) throw new Error("a withdrawn message must not be cached again");

savePreview(me, peer, {
  id: messageId,
  text: "resurrected",
  at: "2026-09-22T00:00:01.000Z",
  isMine: true,
});
if (loadPreview(me, peer)?.text !== "secret words") {
  throw new Error("a withdrawn message must not overwrite the chat-list line with its body");
}
// The later write is a resurrection attempt. It must not replace what redact is about to store,
// and it must not put the body back after redact either. Check the refusal first by redacting,
// then trying again.
redactPreviewsFor(me, messageId);
if (loadPreview(me, peer)?.text !== "Message deleted") {
  throw new Error("redact should replace the chat-list line");
}
savePreview(me, peer, {
  id: messageId.toLowerCase(),
  text: "secret words",
  at: "2026-09-22T00:00:02.000Z",
  isMine: true,
});
if (loadPreview(me, peer)?.text !== "Message deleted") {
  throw new Error("a withdrawn message must not restore its preview");
}

replacePreview(me, peer, {
  id: "dddddddd-dddd-4ddd-8ddd-dddddddddddd",
  text: "an older message",
  at: "2026-09-21T00:00:00.000Z",
  isMine: false,
});
if (loadPreview(me, peer)?.text !== "an older message") {
  throw new Error("replacePreview must move the line backwards after a delete");
}

console.log("preview selftest ok");
