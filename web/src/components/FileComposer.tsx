import { useRef, useState, type FormEvent, type KeyboardEvent } from "react";
import { Send, TriangleAlert, X } from "lucide-react";
import { COMPOSER_NOTE, fileMetaLine, fileTypeOf, sanitizeFileName, warningLine } from "../files";
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
