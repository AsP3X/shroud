import { decodesNatively } from "./heic";
import { imageFiles } from "./prepareImage";
import { inspectVideo, isVideoFile } from "./prepareVideo";

/** A file pick split by where each file goes (docs/file-sharing.md §4, §7), each list in pick order. */
export type SplitPick = { videos: File[]; photos: File[]; files: File[] };

/** Whether the photo and video sheets can open a file. */
export type MediaOpeners = {
  photo: (file: File) => Promise<boolean>;
  video: (file: File) => Promise<boolean>;
};

/** HEIC goes through the pipeline's own converter; anything else must decode in this browser. */
const browserOpeners: MediaOpeners = {
  photo: async (file) => /\.(heic|heif)$/i.test(file.name) || /^image\/hei[cf]/i.test(file.type) || decodesNatively(file),
  video: (file) => inspectVideo(file).then(
    () => true,
    () => false,
  ),
};

/**
 * The paperclip's pick: images and videos this browser can show go to the photo and video sheets,
 * so they appear in the chat as photos and videos; the rest — an image or clip it can't decode
 * included (a TIFF in Chrome, an AVI) — stays a file.
 */
export async function splitFilePick(picked: File[], openers: MediaOpeners = browserOpeners): Promise<SplitPick> {
  const kinds = await Promise.all(
    picked.map(async (file): Promise<keyof SplitPick> => {
      if (imageFiles([file]).length > 0) return (await openers.photo(file)) ? "photos" : "files";
      if (isVideoFile(file)) return (await openers.video(file)) ? "videos" : "files";
      return "files";
    }),
  );
  const split: SplitPick = { videos: [], photos: [], files: [] };
  picked.forEach((file, index) => split[kinds[index]].push(file));
  return split;
}
