/**
 * Reaction envelopes open only as tagged v2: an untagged box (what the server can build from
 * public keys alone), a v1 box, or a box from anyone but the claimed sender is refused.
 * Run bundled: `npx esbuild src/crypto/reactionsCrypto.selftest.ts --bundle --platform=node
 * --format=esm | node --input-type=module`.
 */
import { x25519 } from "@noble/curves/ed25519.js";
import { b64ToBytes, randomBytes, utf8, utf8decode } from "./bytes";

try {
  void globalThis.localStorage;
} catch {
  const map = new Map<string, string>();
  const storage: Storage = {
    get length() {
      return map.size;
    },
    clear() {
      map.clear();
    },
    getItem(key) {
      return map.get(key) ?? null;
    },
    key(index) {
      return [...map.keys()][index] ?? null;
    },
    removeItem(key) {
      map.delete(key);
    },
    setItem(key, value) {
      map.set(key, String(value));
    },
  };
  Object.defineProperty(globalThis, "localStorage", { value: storage, configurable: true });
}

const { openTaggedEnvelope, sealIdentityEnvelope } = await import("./messageCrypto");
const { createVault, derivePinSecrets } = await import("./vault");
const { parseReaction } = await import("../reactions");

const alice = randomBytes(32);
const bob = randomBytes(32);
const mallory = randomBytes(32);
const aPub = x25519.getPublicKey(alice);
const bPub = x25519.getPublicKey(bob);
const mPub = x25519.getPublicKey(mallory);

await derivePinSecrets("123456").then((secrets) =>
  createVault("00000000-0000-4000-8000-00000000000a", { secrets, pepper: randomBytes(32), guardId: "test" }, randomBytes(32)),
);

async function rejects(label: string, run: () => Promise<unknown>): Promise<void> {
  try {
    await run();
  } catch {
    return;
  }
  throw new Error(`accepted: ${label}`);
}

const bobOpens = (data: Uint8Array, claimedSender = aPub) =>
  openTaggedEnvelope({
    envelopeData: data,
    ourPrivate: bob,
    ourIdentityPublic: bPub,
    senderIdentityPublic: claimedSender,
    asSender: false,
  }).then(utf8decode);

const sealed = await sealIdentityEnvelope(utf8("❤️"), alice, bPub, aPub);
if ((await bobOpens(sealed)) !== "❤️") throw new Error("tagged v2 opens for the peer");
const own = await openTaggedEnvelope({
  envelopeData: sealed,
  ourPrivate: alice,
  ourIdentityPublic: aPub,
  senderIdentityPublic: aPub,
  asSender: true,
}).then(utf8decode);
if (own !== "❤️") throw new Error("tagged v2 opens for our other devices");

const parsed = JSON.parse(utf8decode(sealed)) as { v: number; peer: { ek: string; ct: string; t?: string } };
const untagged = utf8(JSON.stringify({ ...parsed, peer: { ek: parsed.peer.ek, ct: parsed.peer.ct } }));
await rejects("untagged box", () => bobOpens(untagged));
await rejects("v1 envelope", () => bobOpens(utf8(JSON.stringify({ v: 1, ek: parsed.peer.ek, ct: parsed.peer.ct }))));
await rejects("genuine box claimed by someone else", () => bobOpens(sealed, mPub));

// Sealed by iOS (`MessageCrypto.seal` + `MessageReactionPayload.make`) from 0xb2… to 0xa1…: both
// clients must agree on the envelope, the sender tag and which strings are one emoji. iOS opens
// the reverse (`MessageReactionTests.reactionSealedOnTheWebOpensHere`).
const IOS_SEALED_REACTION =
  "eyJwZWVyIjp7ImVrIjoibjFEQTdvS0tUU05vRFFJQTZlMFlscjY2NWU4SlwvV3luSXRFMStMWXVvVmM9IiwiY3QiOiJLbWdB" +
  "TCtkdGUzQkd1XC82NGJKV1VYZ25EVHZHazlSQlIzWVg5THZsbnJwQnExOFJXWkVjQlJodDFhb3pmWE1MQmdcL1Y3K05MQkQ4" +
  "WnAyTENhY1pxMVNUYXRNaFVadjZPaG1uaEZZTXN0eEtTYnBlVzAyNzJEQTV0NWJ5RllUSnRPOTNZbkFNUHFLTjY4ZXprQXMx" +
  "Sys5Y2xIbjlYVTlPNWdXWW5QMUx3cU1RUlwvc1wvc01lSEdiNnk2alwvTHBZT0hYeUZreUg5ZUtNSGhSWHR3NmF3R0taZkF1" +
  "bTZcL052bzVWa3l5MU8wdEQ1RVRQT2RqSUhoSjhISnJmQ0s3K3Vxb3pYVTdiNGd2ZmxrYmpCaEk2bzlYejJ6eENjaUlFZHo0" +
  "WWxHOHJqXC9ZST0iLCJ0IjoiZEIxeFlFQjZRd0lsNU80OUVVUnBaR3d1Q3FWM3F5cUJtRk9RQzMxcmwyND0ifSwidiI6Miwi" +
  "c2VsZiI6eyJlayI6InlFTmR3VmVxbFwvVEttUXZRalpGTllGVnd6WWVpNTJIbElMc3NVUVEwZWhNPSIsImN0IjoiMlU2MHFZ" +
  "SmhxU1lhdko2UHNtRkNYV0N5Y2IxN2U4QklxUUlJOVdSV3JBK21sUlhneFBQdXIwc2IyeU9yYXVjU215cmdwRzFscWZWUnd2" +
  "KzFpZmZ2MFRuT0hiTElOREo2Q0ZHcWVEZjhmbHdOZ1pRSEk1N3QyanBqVnZVa2pOWVdmSUZoWnJlMWhYY2FlTlwvdHArNGZX" +
  "eVJ6TkFPNjZicTZYOWU0dkFBT0xhNHFkM01ONUJrazd2bk1ZQ3laMnZmWEg0OFVqVFdFNnVRcERyQU1FRjBXem5IOHFqU3Fj" +
  "cWcwald5YnFBSytFOVwvN3ZyZElpTkFKcDh3bW5OaWJLMHA3MHJmQ3N4TGpSR2d6V0ErbDFKczQrQTdNSTlrTFZyZnlIbFNV" +
  "Ync4PSIsInQiOiJPWTZSemJEVERaUUFvVFBwZ3pJbmJxNERQUXoyOGVTb2NmbDF1RHY1ZVZnPSJ9fQ==";
const iosSender = x25519.getPublicKey(new Uint8Array(32).fill(0xb2));
const iosPeer = new Uint8Array(32).fill(0xa1);
const iosMessage = "7C9E6679-7425-40DE-944B-E07FC1F90AE7";
// Sealed with "ok", "🔥🔥", a bare "❤" and a repeat on the end, which neither client shows.
const iosExpected = ["👍🏽", "🏳️‍🌈", "🇩🇪", "❤️", "❤️‍🔥", "1️⃣", "👨‍👩‍👧"].join(" ");
for (const [ourPrivate, asSender] of [[iosPeer, false], [new Uint8Array(32).fill(0xb2), true]] as const) {
  const opened = await openTaggedEnvelope({
    envelopeData: b64ToBytes(IOS_SEALED_REACTION),
    ourPrivate,
    ourIdentityPublic: x25519.getPublicKey(ourPrivate),
    senderIdentityPublic: iosSender,
    asSender,
  });
  if (parseReaction(utf8decode(opened), iosMessage.toLowerCase())?.join(" ") !== iosExpected) {
    throw new Error(`a reaction sealed on iOS opens here (${asSender ? "self" : "peer"} box)`);
  }
}

// A sender's key out of reach is no verdict on the record: the open rejects, so catch-up and pages
// come back for it instead of recording a removal. With the key it opens; from anyone else it is
// no reaction.
const { openReaction, reactionPayload } = await import("../reactions");
const { envelopeToWireB64 } = await import("./messageCrypto");
const { ApiError } = await import("../api/client");
const recordMessage = "3f2504e0-4f89-41d3-9a0c-0305e82c3301";
const record = await sealIdentityEnvelope(utf8(reactionPayload(["🔥"], recordMessage)), alice, bPub, aPub);
const wire = {
  message_id: recordMessage,
  user_id: "a1a1a1a1-0000-4000-8000-000000000001",
  ciphertext: envelopeToWireB64(record),
  seq: 4,
  updated_at: "",
};
const bobMaterial = { agreementPrivate: bob, agreementPublic: bPub } as unknown as Parameters<typeof openReaction>[2];
const bobId = "b2b2b2b2-0000-4000-8000-000000000002";
await rejects("a key out of reach", () =>
  openReaction(wire, bobId, bobMaterial, async () => {
    throw new ApiError("transport", "offline", 0);
  }),
);
// …but a real answer ("no such key") is a verdict: no reaction, so catch-up can move on.
const noKey = await openReaction(wire, bobId, bobMaterial, async () => {
  throw new ApiError("NOT_FOUND", "no key", 404);
});
if (noKey.emojis.length !== 0) throw new Error("no key: no reaction");
if ((await openReaction(wire, bobId, bobMaterial, async () => aPub)).emojis.join() !== "🔥") {
  throw new Error("a record opens once the key is there");
}
if ((await openReaction(wire, bobId, bobMaterial, async () => mPub)).emojis.length !== 0) {
  throw new Error("a box claimed by someone else is no reaction");
}

console.log("reactions crypto selftest ok");
