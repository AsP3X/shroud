import { useEffect, useRef, useState, type FormEvent, type KeyboardEvent } from "react";
import { AlertTriangle, ImagePlus, Send, ShieldCheck, X } from "lucide-react";
import { formatBytes } from "../format";
import { blobOf } from "../media/images";
import {
  imageFiles,
  MAX_PHOTOS_PER_SEND,
  PHOTO_ACCEPT,
  prepareImage,
  type PreparedImage,
} from "../media/prepareImage";
import { Modal } from "./Modal";

/** Captions share the sealed envelope with the preview, so keep them Telegram-short. */
const MAX_CAPTION = 1024;

type Entry =
  | { state: "preparing" }
  | { state: "ready"; image: PreparedImage; url: string }
  | { state: "error"; message: string };

/**
 * The "Send photos" sheet: previews what will actually go out (oriented, resized,
 * metadata stripped), takes a caption for the first photo, and hands the prepared
 * images back. A bottom sheet on phones, a dialog on desktop.
 */
export function ImageComposer({
  files,
  peerName,
  onFilesChange,
  onClose,
  onSend,
}: {
  files: File[];
  peerName: string;
  onFilesChange: (files: File[]) => void;
  onClose: () => void;
  onSend: (images: PreparedImage[], caption: string) => void;
}) {
  const [entries, setEntries] = useState<ReadonlyMap<File, Entry>>(() => new Map());
  const [caption, setCaption] = useState("");
  const [sending, setSending] = useState(false);
  const picker = useRef<HTMLInputElement>(null);
  /** Guards the hand-off: the effect below can run again before the sheet unmounts. */
  const handedOff = useRef(false);
  /** React keys for tiles: the same file can be picked twice. */
  const tileKeys = useRef(new WeakMap<File, number>());
  const nextKey = useRef(0);
  const started = useRef(new Set<File>());
  const queue = useRef<Promise<void>>(Promise.resolve());
  const alive = useRef(true);
  const current = useRef({ files, entries });
  current.current = { files, entries };
  const finePointer = window.matchMedia?.("(pointer: fine)").matches ?? false;

  useEffect(() => {
    alive.current = true;
    return () => {
      alive.current = false;
      for (const entry of current.current.entries.values()) {
        if (entry.state === "ready") URL.revokeObjectURL(entry.url);
      }
    };
  }, []);

  /* Prepare each new file once, one after another: a batch of 24 MP originals decoded
     at the same time would take a phone's tab down. */
  useEffect(() => {
    const set = (file: File, entry: Entry) => {
      if (alive.current) setEntries((prev) => new Map(prev).set(file, entry));
    };
    for (const file of files) {
      if (started.current.has(file)) continue;
      started.current.add(file);
      set(file, { state: "preparing" });
      queue.current = queue.current.then(async () => {
        // Removed from the sheet before its turn came.
        if (!alive.current || !current.current.files.includes(file)) return;
        try {
          const image = await prepareImage(file);
          const url = URL.createObjectURL(blobOf(image.bytes, image.mime));
          if (!alive.current) {
            URL.revokeObjectURL(url);
            return;
          }
          set(file, { state: "ready", image, url });
        } catch (err) {
          set(file, {
            state: "error",
            message: err instanceof Error ? err.message : "Couldn’t read this image.",
          });
        }
      });
    }
  }, [files]);

  const list = files.map((file) => ({ file, entry: entries.get(file) ?? ({ state: "preparing" } as Entry) }));
  const ready = list.flatMap(({ entry }) => (entry.state === "ready" ? [entry.image] : []));
  const preparing = list.some(({ entry }) => entry.state === "preparing");
  const failures = list.filter(({ entry }) => entry.state === "error").length;
  const bytes = ready.reduce((sum, image) => sum + image.bytes.byteLength, 0);

  /* Send was pressed while photos were still being prepared: go as soon as they are. */
  useEffect(() => {
    if (!sending || preparing || handedOff.current) return;
    if (ready.length === 0) {
      setSending(false);
      return;
    }
    handedOff.current = true;
    onSend(ready, caption.trim());
  }, [sending, preparing, ready, caption, onSend]);

  function remove(file: File) {
    const entry = entries.get(file);
    if (entry?.state === "ready") URL.revokeObjectURL(entry.url);
    const next = files.filter((f) => f !== file);
    if (next.length === 0) onClose();
    else onFilesChange(next);
  }

  function add(picked: File[]) {
    const room = MAX_PHOTOS_PER_SEND - files.length;
    if (room > 0 && picked.length) onFilesChange([...files, ...picked.slice(0, room)]);
  }

  function submit(event?: FormEvent) {
    event?.preventDefault();
    if (ready.length === 0 && !preparing) return;
    setSending(true);
  }

  function onKeyDown(event: KeyboardEvent<HTMLTextAreaElement>) {
    if (event.key !== "Enter" || event.shiftKey || event.nativeEvent.isComposing) return;
    event.preventDefault();
    submit();
  }

  const count = files.length;
  const title = count === 1 ? "Send photo" : `Send ${count} photos`;
  const single = count === 1 ? list[0] : null;
  const canAdd = count < MAX_PHOTOS_PER_SEND;
  const keyOf = (file: File) => {
    let key = tileKeys.current.get(file);
    if (key == null) {
      key = nextKey.current++;
      tileKeys.current.set(file, key);
    }
    return key;
  };

  return (
    <Modal title={title} onClose={onClose} sheet className="attach-modal">
      {single ? (
        <div className="attach-hero">
          <PhotoPreview entry={single.entry} />
        </div>
      ) : (
        <ul className="attach-grid" aria-label="Photos to send">
          {list.map(({ file, entry }) => (
            <li key={keyOf(file)} className="attach-tile">
              <PhotoPreview entry={entry} />
              <button
                className="attach-remove"
                type="button"
                aria-label={`Remove ${file.name || "photo"}`}
                onClick={() => remove(file)}
              >
                <X size={14} />
              </button>
            </li>
          ))}
          {canAdd ? (
            <li className="attach-tile">
              <button className="attach-add" type="button" onClick={() => picker.current?.click()}>
                <ImagePlus size={22} aria-hidden="true" />
                <span>Add</span>
              </button>
            </li>
          ) : null}
        </ul>
      )}

      <div className="attach-info">
        <span className="attach-note">
          <ShieldCheck size={14} aria-hidden="true" />
          {preparing
            ? "Preparing…"
            : `${ready.length} photo${ready.length === 1 ? "" : "s"}${bytes ? ` · ${formatBytes(bytes)}` : ""} · location removed`}
        </span>
        {single && canAdd ? (
          <button className="attach-more" type="button" onClick={() => picker.current?.click()}>
            <ImagePlus size={15} aria-hidden="true" />
            Add
          </button>
        ) : null}
      </div>
      {failures > 0 ? (
        <p className="err" role="status">
          {failures === count
            ? "This image can’t be sent — try a JPEG, PNG or HEIC photo."
            : `${failures} of these can’t be read and will be skipped.`}
        </p>
      ) : null}

      <form className="attach-compose" onSubmit={submit}>
        <div className="compose-grow" data-value={`${caption} `}>
          <textarea
            className="compose-field"
            rows={1}
            placeholder="Add a caption…"
            aria-label={`Caption for ${peerName}`}
            maxLength={MAX_CAPTION}
            value={caption}
            onChange={(event) => setCaption(event.target.value)}
            onKeyDown={onKeyDown}
            enterKeyHint="send"
            data-autofocus={finePointer ? "" : undefined}
          />
        </div>
        <button
          className={sending ? "send is-busy" : "send"}
          type="submit"
          aria-label={count === 1 ? "Send photo" : `Send ${count} photos`}
          disabled={sending || (ready.length === 0 && !preparing)}
        >
          {sending ? <span className="send-spin" aria-hidden="true" /> : <Send size={16} />}
        </button>
      </form>

      <input
        ref={picker}
        type="file"
        accept={PHOTO_ACCEPT}
        multiple
        hidden
        onChange={(event) => {
          add(imageFiles(event.target.files));
          event.target.value = "";
        }}
      />
    </Modal>
  );
}

function PhotoPreview({ entry }: { entry: Entry }) {
  if (entry.state === "ready") {
    return (
      <img
        className="attach-img"
        src={entry.url}
        alt=""
        draggable={false}
        style={{ aspectRatio: `${entry.image.width} / ${entry.image.height}` }}
      />
    );
  }
  if (entry.state === "error") {
    return (
      <span className="attach-failed" title={entry.message}>
        <AlertTriangle size={20} aria-hidden="true" />
        <span>Can’t read</span>
      </span>
    );
  }
  return <span className="attach-placeholder skeleton" aria-label="Preparing photo" />;
}
