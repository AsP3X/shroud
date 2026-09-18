import { useEffect, useRef, useState } from "react";
import { Link2, ScanLine, Share } from "lucide-react";
import type { Session } from "../../api/client";
import { normalizeShareCode, shareUrl } from "../../invite";
import { Avatar } from "../Avatar";
import { CopyButton } from "../CopyButton";
import { Modal } from "../Modal";
import { displayName } from "../settings/SettingsHome";
import { QrCode } from "./QrCode";

export function MyQrSheet({ session, onClose }: { session: Session; onClose: () => void }) {
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

  return (
    <Modal title="My QR code" onClose={onClose} className="qr-modal" sheet>
      <div className="qr-identity">
        <Avatar name={name} seed={session.user.id} size="lg" />
        <div>
          <strong>{name}</strong>
          <span>@{session.user.username}</span>
        </div>
      </div>

      {/* Focusable so keyboard users get the same sweep replay as hover. */}
      <figure className="qr-card" tabIndex={0}>
        <QrCode value={link} label={`QR code for ${link}`} />
        <span className="qr-sweep" aria-hidden="true" />
      </figure>

      <p className="qr-caption">
        <ScanLine size={14} aria-hidden="true" />
        Scan with Shroud on your phone
      </p>

      <div className="qr-codes">
        <div className="qr-code-row">
          <div>
            <span>Share code</span>
            <code>{code}</code>
          </div>
          <CopyButton value={code} label="share code" />
        </div>
        <div className="qr-code-row">
          <div>
            <span>Invite link</span>
            <code>{link.replace(/^https?:\/\//, "")}</code>
          </div>
          <CopyButton value={link} label="invite link" />
        </div>
      </div>

      {canShare ? (
        <button className="btn btn-primary qr-action" type="button" onClick={() => void share()}>
          <Share size={17} aria-hidden="true" />
          Share invite
        </button>
      ) : (
        <button className="btn btn-primary qr-action" type="button" onClick={() => void copyLink()}>
          <Link2 size={17} aria-hidden="true" />
          {shared ? "Link copied" : "Copy invite link"}
        </button>
      )}
    </Modal>
  );
}
