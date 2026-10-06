import { splitFilePick, type MediaOpeners } from "./filePick";

/** The paperclip's split (docs/file-sharing.md §4, §7) with stand-in decoders. */
const unreadable = new Set(["scan.tiff", "talk.avi"]);
const openers: MediaOpeners = {
  photo: async (file) => !unreadable.has(file.name),
  video: async (file) => !unreadable.has(file.name),
};
const file = (name: string, type: string) => new File([new Uint8Array([1])], name, { type });

const picked = [
  file("beach.jpg", "image/jpeg"),
  file("report.pdf", "application/pdf"),
  file("clip.mp4", "video/mp4"),
  file("scan.tiff", "image/tiff"),
  file("IMG_0001.HEIC", ""),
  file("talk.avi", "video/x-msvideo"),
  file("notes.txt", "text/plain"),
  // Audio files go to the file composer as they are, whatever type the OS gives them (§11.1).
  file("song.ogg", "video/ogg"),
  file("Track 01.M4A", "audio/x-m4a"),
  file("podcast.mp3", "audio/mpeg"),
];
const split = await splitFilePick(picked, openers);
const names = (files: File[]) => files.map((f) => f.name).join(",");
if (names(split.photos) !== "beach.jpg,IMG_0001.HEIC") throw new Error(`photos: ${names(split.photos)}`);
if (names(split.videos) !== "clip.mp4") throw new Error(`videos: ${names(split.videos)}`);
if (names(split.files) !== "report.pdf,scan.tiff,talk.avi,notes.txt,song.ogg,Track 01.M4A,podcast.mp3") {
  throw new Error(`files: ${names(split.files)}`);
}
// Drops and pastes route by type too: an audio file is never a clip.
const { videoFiles } = await import("./prepareVideo");
if (videoFiles([file("song.ogg", "video/ogg"), file("clip.webm", "video/webm")]).map((f) => f.name).join() !== "clip.webm") {
  throw new Error("an .ogg with a video type must not count as a clip");
}

const none = await splitFilePick([], openers);
if (none.photos.length + none.videos.length + none.files.length !== 0) throw new Error("empty pick");

console.log("filePick selftest ok");
