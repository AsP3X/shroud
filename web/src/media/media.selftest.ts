import { isHeif } from "./heic";
import { clipboardImages, imageFiles } from "./prepareImage";

function heifLike(brand: string): Uint8Array {
  const out = new Uint8Array(12);
  out[3] = 12;
  out.set(new TextEncoder().encode("ftyp"), 4);
  out.set(new TextEncoder().encode(brand), 8);
  return out;
}

if (!isHeif(heifLike("heic"))) throw new Error("isHeif: heic brand");
if (!isHeif(heifLike("heic"), "image/heic")) throw new Error("isHeif: mime + brand");
if (isHeif(heifLike("mif1")) !== true) throw new Error("isHeif: mif1");
if (isHeif(heifLike("jpeg"))) throw new Error("isHeif: jpeg brand must fail");
if (isHeif(new Uint8Array([0xff, 0xd8, 0xff]), "image/jpeg")) throw new Error("isHeif: jpeg mime");
if (!isHeif(new Uint8Array(4), "image/heif")) throw new Error("isHeif: short heif mime");

const jpeg = new File([new Uint8Array([0xff, 0xd8])], "a.jpg", { type: "image/jpeg" });
const png = new File([new Uint8Array([0x89])], "a.png", { type: "image/png" });
const svg = new File([new Uint8Array([0x3c])], "a.svg", { type: "image/svg+xml" });
const namedHeic = new File([new Uint8Array(4)], "IMG_0001.HEIC", { type: "" });
const text = new File([new Uint8Array([0x61])], "a.txt", { type: "text/plain" });
const picked = imageFiles([jpeg, png, svg, namedHeic, text]);
if (picked.length !== 3) throw new Error(`imageFiles: expected 3, got ${picked.length}`);
if (!picked.includes(namedHeic)) throw new Error("imageFiles: HEIC by name");
if (picked.includes(svg) || picked.includes(text)) throw new Error("imageFiles: rejected svg/text");

const transfer = {
  files: [] as unknown as FileList,
  items: [] as unknown as DataTransferItemList,
} as DataTransfer;
if (clipboardImages(transfer).length !== 0) throw new Error("clipboardImages: empty");
if (clipboardImages(null).length !== 0) throw new Error("clipboardImages: null");

console.log("media selftest ok");
