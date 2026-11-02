import { useEffect, useRef, useState, type CSSProperties } from "react";
import { Link2, Share, UserRoundPlus } from "lucide-react";
import type { Session } from "../../api/client";
import { displayName } from "../../format";
import { normalizeShareCode, shareUrl } from "../../invite";
import { Avatar } from "../Avatar";
import { CopyButton } from "../CopyButton";
import { Modal } from "../Modal";
import { QrCode } from "./QrCode";

/** The code beside the identity it carries: one object, read left to right (the dialog shell is the caller's). */
export function MyQrBody({
  session,
  late = false,
  onAddContact,
}: {
  session: Session;
  /** The dialog is still morphing into place: hold the code's reveal until it has settled. */
  late?: boolean;
  onAddContact?: () => void;
}) {
  const code = normalizeShareCode(session.user.share_code);
  const link = shareUrl(code);
  const name = displayName(session.user.username);
  const canShare = typeof navigator.share === "function";
  const [shared, setShared] = useState(false);
  const copiedTimer = useRef(0);

  useEffect(() => () => window.clearTimeout(copiedTimer.current), []);

  async function copyLink() {
    try {
      await navigator.clipboard.writeText(link);
      setShared(true);
      window.clearTimeout(copiedTimer.current);
      copiedTimer.current = window.setTimeout(() => setShared(false), 1600);
    } catch {
      /* clipboard blocked: the link stays selectable below */
    }
  }

  async function share() {
    try {
      await navigator.share({ title: "Add me on Shroud", text: `Add ${name} on Shroud`, url: link });
    } catch (err) {
      if (err instanceof DOMException && err.name === "AbortError") return;
      await copyLink();
    }
  }

  const bare = link.replace(/^https?:\/\//, "");
  const cut = bare.indexOf("/u/");
  return (
    <>
      <div className="qr-layout">
        <div className="qr-side">
          {/* Focusable so keyboard users get the same sweep replay as hover. */}
          <figure className="qr-card" tabIndex={0} style={late ? ({ "--qr-start": "360ms" } as CSSProperties) : undefined}>
            <QrCode value={link} label={`QR code for ${link}`} />
            <span className="qr-sweep" aria-hidden="true" />
          </figure>
          <p className="qr-caption">Scan with Shroud on your phone</p>
        </div>
        <div className="qr-who">
          <div className="qr-name">
            <Avatar name={name} seed={session.user.id} size="sm" />
            <span>
              <strong>{name}</strong>
              <small>@{session.user.username}</small>
            </span>
          </div>
          <dl className="qr-facts">
            <div>
              <dt>Share code</dt>
              <dd className="mono">{code.replace(/^(.{4,6})(.+)$/, "$1 $2")}</dd>
              <CopyButton value={code} label="share code" />
            </div>
            <div>
              <dt>Invite link</dt>
              <dd>
                {cut >= 0 ? (
                  <>
                    {bare.slice(0, cut + 3)}
                    <wbr />
                    {bare.slice(cut + 3)}
                  </>
                ) : (
                  bare
                )}
              </dd>
              <CopyButton value={link} label="invite link" />
            </div>
          </dl>
          <p className="qr-note">The code and the link carry your share code. Anyone who has it can send you a contact request; you choose whether to accept.</p>
        </div>
      </div>
      <div className="dialog-foot">
        {onAddContact ? (
          <button type="button" className="btn-text" onClick={onAddContact}>
            <UserRoundPlus size={16} aria-hidden="true" />
            Add a contact
          </button>
        ) : null}
        <span className="dialog-foot-gap" />
        {canShare ? (
          <button className="btn btn-primary dialog-primary" type="button" onClick={() => void share()}>
            <Share size={16} aria-hidden="true" />
            Share invite
          </button>
        ) : (
          <button className="btn btn-primary dialog-primary" type="button" onClick={() => void copyLink()}>
            <Link2 size={16} aria-hidden="true" />
            {shared ? "Link copied" : "Copy invite link"}
          </button>
        )}
      </div>
    </>
  );
}

export function MyQrSheet({ session, onClose }: { session: Session; onClose: () => void }) {
  return (
    <Modal title="My QR code" onClose={onClose} className="qr-modal" sheet>
      <MyQrBody session={session} />
    </Modal>
  );
}
