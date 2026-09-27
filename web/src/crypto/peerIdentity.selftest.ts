/**
 * Pinning a contact's identity key: the first key sticks, a different one waits.
 * Run: npx esbuild src/crypto/peerIdentity.selftest.ts --bundle --platform=node --format=esm | node --input-type=module
 */
import { acceptPeerKey, confirmPeerKey, notePeerKey, peerKeyBlocked } from "./peerIdentity";

function check(ok: boolean, what: string): void {
  if (!ok) throw new Error(`peer identity selftest: ${what}`);
}

const first = "A".repeat(44); // stand-in; notePeerKey does not re-decode
const next = "B".repeat(44);

const pinned = notePeerKey(null, first);
check(pinned.key === first && !pinned.verified && pinned.pending === null, "the first key is pinned");
check(!peerKeyBlocked(pinned), "a first key does not block");

const same = notePeerKey(pinned, first);
check(same.key === first && same.pending === null, "the same key changes nothing");

const changed = notePeerKey(confirmPeerKey(pinned), next);
check(changed.key === first && changed.pending === next && changed.verified, "a new key waits, and the pin stays");
check(peerKeyBlocked(changed), "a waiting key blocks sends and calls");

const accepted = acceptPeerKey(changed);
check(accepted.key === next && !accepted.verified && accepted.pending === null, "accepting starts the comparison over");
check(confirmPeerKey(accepted).verified, "comparing marks it");

console.log("peer identity selftest ok");
