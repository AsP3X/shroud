import { isHeif } from "./heic";
import { clipboardImages, imageFiles } from "./prepareImage";
import { clipboardVideos, isVideoFile, videoFiles } from "./prepareVideo";
import {
  clockLabel,
  effectiveTrim,
  planResolutionLabel,
  planVideo,
  VideoTooLongError,
  type VideoProbe,
} from "./videoPlan";
import { isVideoPayload, isVoicePayload, parseMediaPayload } from "../crypto/mediaPayload";

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

const mp4 = new File([new Uint8Array(8)], "clip.mp4", { type: "video/mp4" });
const mov = new File([new Uint8Array(8)], "IMG_0001.MOV", { type: "" });
const webm = new File([new Uint8Array(8)], "a.webm", { type: "video/webm" });
if (!isVideoFile(mp4) || !isVideoFile(mov) || !isVideoFile(webm)) throw new Error("isVideoFile: extensions");
if (isVideoFile(jpeg) || isVideoFile(text)) throw new Error("isVideoFile: rejected photo/text");
const clips = videoFiles([mp4, mov, jpeg, text, webm]);
if (clips.length !== 3) throw new Error(`videoFiles: expected 3, got ${clips.length}`);
if (clipboardVideos(transfer).length !== 0) throw new Error("clipboardVideos: empty");
if (clipboardVideos(null).length !== 0) throw new Error("clipboardVideos: null");

if (clockLabel(7) !== "0:07") throw new Error("clockLabel: 7s");
if (clockLabel(750) !== "12:30") throw new Error("clockLabel: 12:30");
if (effectiveTrim({ start: 0, end: 8 }, 8) !== null) throw new Error("effectiveTrim: whole clip");
const kept = effectiveTrim({ start: 1, end: 4 }, 8);
if (!kept || kept.start !== 1 || kept.end !== 4) throw new Error("effectiveTrim: window");

function probe(partial: Partial<VideoProbe> & Pick<VideoProbe, "bytes" | "duration" | "width" | "height">): VideoProbe {
  return {
    fps: 30,
    videoCodec: "avc",
    audioCodec: "aac",
    audioChannels: 2,
    audioSampleRate: 48000,
    audioBitrate: 128_000,
    audioDecodable: true,
    ...partial,
  };
}

const copied = planVideo(probe({ bytes: 2_000_000, duration: 8, width: 1280, height: 720 }));
if (copied.video !== "copy" || copied.audio !== "copy") throw new Error("planVideo: modest H.264 should copy");

const hevc = planVideo(probe({ bytes: 12_000_000, duration: 8, width: 1920, height: 1080, videoCodec: "hevc" }));
if (hevc.video !== "encode" || hevc.width > 1280 || hevc.height > 720) {
  throw new Error("planVideo: HEVC should re-encode into the 1280 box");
}

const fullHd = probe({ bytes: 2_000_000, duration: 8, width: 1920, height: 1080 });
const asHigh = planVideo(fullHd, { quality: "high" });
if (asHigh.video !== "encode" || asHigh.width !== 1280 || asHigh.height !== 720) {
  throw new Error(`planVideo: high caps 1080p at 720p, got ${asHigh.width}×${asHigh.height} ${asHigh.video}`);
}
if (planResolutionLabel(asHigh, fullHd, "high") !== "720p") throw new Error("planResolutionLabel: 720p");
const asOriginal = planVideo(fullHd, { quality: "original" });
if (asOriginal.video !== "copy") throw new Error("planVideo: original keeps a modest H.264 file");
if (planResolutionLabel(asOriginal, fullHd, "original") !== "Original") {
  throw new Error("planResolutionLabel: original copy");
}

const hevcOriginal = planVideo(
  probe({ bytes: 12_000_000, duration: 8, width: 1920, height: 1080, videoCodec: "hevc" }),
  { quality: "original" },
);
if (hevcOriginal.video !== "encode" || hevcOriginal.width !== 1920 || hevcOriginal.height !== 1080) {
  throw new Error("planVideo: original re-encodes HEVC at the source size");
}

const medium = planVideo(probe({ bytes: 2_000_000, duration: 8, width: 1280, height: 720 }), { quality: "medium" });
if (medium.video !== "encode" || medium.width !== 960 || medium.height !== 540) {
  throw new Error(`planVideo: medium is 540p, got ${medium.width}×${medium.height}`);
}
const kept540 = planVideo(probe({ bytes: 800_000, duration: 8, width: 960, height: 540 }), { quality: "medium" });
if (kept540.video !== "copy") throw new Error("planVideo: medium keeps a modest 540p file");

const small = planVideo(probe({ bytes: 2_000_000, duration: 8, width: 1280, height: 720 }), { quality: "small" });
if (small.video !== "encode" || small.width !== 640 || small.height !== 360) {
  throw new Error(`planVideo: small is 360p, got ${small.width}×${small.height}`);
}
const small43 = planVideo(probe({ bytes: 2_000_000, duration: 8, width: 1440, height: 1080 }), { quality: "small" });
if (small43.video !== "encode" || small43.width !== 480 || small43.height !== 360) {
  throw new Error(`planVideo: 4:3 small stays 360p, got ${small43.width}×${small43.height}`);
}

let originalThrew = false;
try {
  planVideo(
    probe({ bytes: 80_000_000, duration: 600, width: 3840, height: 2160, fps: 30, videoCodec: "hevc" }),
    { quality: "original" },
  );
} catch (err) {
  originalThrew = err instanceof VideoTooLongError && /original/i.test(err.message);
}
if (!originalThrew) throw new Error("planVideo: original refuses a clip that cannot stay full size");

const muted = planVideo(probe({ bytes: 2_000_000, duration: 8, width: 1280, height: 720 }), { mute: true });
if (muted.audio !== "none") throw new Error("planVideo: mute drops sound");

let threw = false;
try {
  planVideo(probe({ bytes: 80_000_000, duration: 3600, width: 3840, height: 2160, fps: 60, videoCodec: "hevc" }));
} catch (err) {
  threw = err instanceof VideoTooLongError && err.maxSeconds >= 1;
}
if (!threw) throw new Error("planVideo: dense hour-long clip must be too long");

const videoByType = parseMediaPayload('{"t":"video","k":"YQ==","mime":"video/mp4","w":1,"h":1}');
if (!videoByType || !isVideoPayload(videoByType) || isVoicePayload(videoByType)) {
  throw new Error("isVideoPayload: t=video");
}
const videoByMime = parseMediaPayload('{"t":"media","k":"YQ==","mime":"video/quicktime"}');
if (!videoByMime || !isVideoPayload(videoByMime)) throw new Error("isVideoPayload: mime sniff");
const photoNotVideo = parseMediaPayload('{"t":"image","k":"YQ==","mime":"video/mp4"}');
if (!photoNotVideo || isVideoPayload(photoNotVideo)) throw new Error("isVideoPayload: t=image wins");

console.log("media selftest ok");
