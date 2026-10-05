import { useEffect, useState } from "react";
import { Download } from "lucide-react";
import { TEXT_VIEWER_BYTES, TEXT_VIEWER_CUT } from "../files";
import { Modal } from "./Modal";

/**
 * A received `.txt` or `.csv`, read in the app (docs/file-sharing.md §7): the first 1 MiB as
 * plain text in the mono font — React puts it in as text, never as HTML — with Download in the
 * header for the whole file.
 */
export function TextFileViewer({
  name,
  blob,
  onDownload,
  onClose,
}: {
  name: string;
  blob: Blob;
  onDownload: () => void;
  onClose: () => void;
}) {
  const [text, setText] = useState<string | null>(null);

  useEffect(() => {
    let alive = true;
    void blob
      .slice(0, TEXT_VIEWER_BYTES)
      .arrayBuffer()
      .then(
        (bytes) => {
          if (alive) setText(new TextDecoder().decode(bytes));
        },
        () => {
          if (alive) setText("");
        },
      );
    return () => {
      alive = false;
    };
  }, [blob]);

  return (
    <Modal
      title={name}
      onClose={onClose}
      className="text-viewer"
      actions={
        <button className="text-viewer-download" type="button" onClick={onDownload}>
          <Download size={15} aria-hidden="true" />
          Download
        </button>
      }
    >
      {text == null ? (
        <div className="text-viewer-body skeleton" aria-busy="true" />
      ) : (
        <pre className="text-viewer-body" tabIndex={0}>
          {text}
        </pre>
      )}
      {blob.size > TEXT_VIEWER_BYTES ? <p className="text-viewer-cut">{TEXT_VIEWER_CUT}</p> : null}
    </Modal>
  );
}
