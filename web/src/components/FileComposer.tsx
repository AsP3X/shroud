import { useEffect, useRef, useState, type FormEvent, type KeyboardEvent } from "react";
import { Music, Send, TriangleAlert, X } from "lucide-react";
import { audioComposerLine } from "../audioFiles";
import { COMPOSER_NOTE, fileMetaLine, fileTypeOf, sanitizeFileName, warningLine } from "../files";
import type { AudioSendInfo } from "../media/audioSendPreview";
import { FileCategoryIcon, FileName } from "./FileBubble";
import { Modal } from "./Modal";

/** Captions share the sealed envelope, so keep them Telegram-short (as photos do). */
const MAX_CAPTION = 1024;

/**
 * The "Send File" sheet (docs/file-sharing.md §7): every picked file with its tile, name,
 * `{size} · {TYPE}` and warning, a caption for the first, and Send. Files go out exactly as
 * picked — no compression, metadata kept — which the line under the list says.
 */
export function FileComposer({
  files,
  peerName,
  onFilesChange,
  onClose,
  onSend,
}: {
  /** Already checked against the type table, the size limits and the count. */
  files: File[];
  peerName: string;
  onFilesChange: (files: File[]) => void;
  onClose: () => void;
  onSend: (files: File[], caption: string) => void;
}) {
  const [caption, setCaption] = useState("");
  /** React keys for rows: the same file can be picked twice. */
  const rowKeys = useRef(new WeakMap<File, number>());
  const nextKey = useRef(0);
  const handedOff = useRef(false);
  const finePointer = window.matchMedia?.("(pointer: fine)").matches ?? false;

  const keyOf = (file: File) => {
    let key = rowKeys.current.get(file);
    if (key == null) {
      key = nextKey.current++;
      rowKeys.current.set(file, key);
    }
    return key;
  };

  function remove(index: number) {
    const next = files.filter((_, i) => i !== index);
    if (next.length === 0) onClose();
    else onFilesChange(next);
  }

  function submit(event?: FormEvent) {
    event?.preventDefault();
    if (handedOff.current || files.length === 0) return;
    handedOff.current = true;
    onSend(files, caption.trim());
  }

  function onKeyDown(event: KeyboardEvent<HTMLTextAreaElement>) {
    if (event.key !== "Enter" || event.shiftKey || event.nativeEvent.isComposing) return;
    event.preventDefault();
    submit();
  }

  const count = files.length;
  const title = count === 1 ? "Send File" : `Send ${count} Files`;

  return (
    <Modal title={title} onClose={onClose} sheet className="attach-modal file-compose">
      <ul className="file-compose-list" aria-label="Files to send">
        {files.map((file, index) => {
          const name = sanitizeFileName(file.name);
          const type = fileTypeOf(name);
          if (type?.category === "audio") {
            return <AudioComposeRow key={keyOf(file)} file={file} name={name} onRemove={() => remove(index)} />;
          }
          return (
            <li key={keyOf(file)} className="file-compose-row">
              <span className="file-tile" aria-hidden="true">
                <span className="file-glyph">{type ? <FileCategoryIcon category={type.category} /> : null}</span>
              </span>
              <span className="file-text">
                <FileName name={name} />
                <span className="file-meta">{fileMetaLine(file.size, name)}</span>
                {type?.warning ? (
                  <span className="file-warning">
                    <TriangleAlert size={12} aria-hidden="true" />
                    {warningLine(type.warning)}
                  </span>
                ) : null}
              </span>
              <button
                className="icon-btn file-compose-remove"
                type="button"
                aria-label="Remove"
                title="Remove"
                onClick={() => remove(index)}
              >
                <X size={16} />
              </button>
            </li>
          );
        })}
      </ul>
      <p className="file-compose-note">{COMPOSER_NOTE}</p>

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
        <button className="send" type="submit" aria-label="Send" disabled={count === 0}>
          <Send size={16} />
        </button>
      </form>
    </Modal>
  );
}

/**
 * An audio file in the sheet (docs/file-sharing.md §11.3): a round cover (the file's art, else the
 * accent circle with a music glyph), the title tag (else the name, cut in the middle) and
 * `{ar} · {duration} · {size} · {EXT}`. What is read here is what the send seals (§11.2).
 */
function AudioComposeRow({ file, name, onRemove }: { file: File; name: string; onRemove: () => void }) {
  const [info, setInfo] = useState<AudioSendInfo | null>(null);
  const [cover, setCover] = useState<string | null>(null);

  useEffect(() => {
    let live = true;
    void import("../media/audioSendPreview")
      .then((m) => m.audioSendInfo(file))
      .then((read) => {
        if (live) setInfo(read);
      })
      .catch(() => undefined);
    return () => {
      live = false;
    };
  }, [file]);

  useEffect(() => {
    if (!info?.thumb) return;
    const url = URL.createObjectURL(new Blob([info.thumb.slice()], { type: "image/jpeg" }));
    setCover(url);
    return () => {
      URL.revokeObjectURL(url);
      setCover(null);
    };
  }, [info]);

  return (
    <li className="file-compose-row">
      <span className="file-tile audio-cover" aria-hidden="true">
        {cover ? <img className="file-thumb" src={cover} alt="" draggable={false} /> : null}
        {cover ? null : (
          <span className="file-glyph">
            <Music size={20} aria-hidden="true" />
          </span>
        )}
      </span>
      <span className="file-text">
        {info?.title ? (
          <span className="audio-title" title={info.title}>
            {info.title}
          </span>
        ) : (
          <FileName name={name} />
        )}
        <span className="file-meta">{audioComposerLine(name, info?.artist, info?.durationMs, file.size)}</span>
      </span>
      <button className="icon-btn file-compose-remove" type="button" aria-label="Remove" title="Remove" onClick={onRemove}>
        <X size={16} />
      </button>
    </li>
  );
}
