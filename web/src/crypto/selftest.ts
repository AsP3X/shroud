import { generateMnemonic, mnemonicToSeed, validateMnemonic } from "./bip39";
import { bytesToB64, bytesToHex } from "./bytes";
import { establish, matchesMnemonic } from "./identity";

const known = Array.from({ length: 11 }, () => "abandon").concat("about");

const a = establish(known, "00000000-0000-4000-8000-000000000001", 2);
const b = establish(known, "00000000-0000-4000-8000-000000000001", 2);
if (bytesToHex(a.agreementPublic) !== bytesToHex(b.agreementPublic)) {
  throw new Error("identity public key not stable");
}
if (a.registrationId !== b.registrationId) throw new Error("registration id not stable");
if (bytesToHex(a.signingPublic) !== bytesToHex(b.signingPublic)) {
  throw new Error("signing public key not stable");
}
if (!matchesMnemonic(a, known)) throw new Error("matchesMnemonic failed");
if (matchesMnemonic(a, Array.from({ length: 12 }, () => "ability"))) {
  throw new Error("matchesMnemonic accepted a different phrase");
}

const generated = generateMnemonic();
validateMnemonic(generated);
mnemonicToSeed(generated);
establish(generated, "00000000-0000-4000-8000-000000000002", 3);

console.log("crypto selftest ok", {
  registrationId: a.registrationId,
  identity: bytesToB64(a.agreementPublic),
});
