/** A yes/no question about something that can't be undone (removing a device, a new QR code). */
export function ConfirmDialog({
  title,
  body,
  action,
  onCancel,
  onConfirm,
}: {
  title: string;
  body: string;
  action: string;
  onCancel: () => void;
  onConfirm: () => void;
}) {
  return (
    <div className="modal-scrim" onMouseDown={onCancel}>
      <div
        className="modal"
        role="alertdialog"
        aria-modal="true"
        aria-labelledby="set-confirm-title"
        aria-describedby="set-confirm-body"
        onMouseDown={(event) => event.stopPropagation()}
      >
        <header>
          <h2 id="set-confirm-title">{title}</h2>
        </header>
        <p id="set-confirm-body">{body}</p>
        <div className="modal-actions">
          <button type="button" className="btn btn-secondary" onClick={onCancel}>
            Cancel
          </button>
          <button type="button" className="btn btn-destructive" onClick={onConfirm} autoFocus>
            {action}
          </button>
        </div>
      </div>
    </div>
  );
}
