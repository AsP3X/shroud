import { useSyncExternalStore } from "react";
import { RefreshCw, X } from "lucide-react";
import { dismissUpdate, subscribeUpdate, updateBannerShown } from "../appVersion";
import { useCallBusy } from "../calls/store";

const MESSAGE = "A new version of Shroud is available.";

/**
 * A newer build is deployed (`appVersion.ts`). Over every screen, never in the way for long. It
 * waits while a call rings or runs: a reload would end the call (and on a phone the banner would
 * sit on the call pill).
 */
export function UpdateBanner() {
  const available = useSyncExternalStore(subscribeUpdate, updateBannerShown);
  const callBusy = useCallBusy();
  const shown = available && !callBusy;
  return (
    <>
      {/* Mounted empty, so screen readers announce the message when it arrives. */}
      <p className="sr-only" role="status">
        {shown ? MESSAGE : ""}
      </p>
      {shown && <UpdateBannerCard />}
    </>
  );
}

function UpdateBannerCard() {
  return (
    <section className="update-banner" aria-label="Update">
      <span className="update-banner-icon" aria-hidden="true">
        <RefreshCw size={16} strokeWidth={2.2} />
      </span>
      <p className="update-banner-text">
        {MESSAGE}
      </p>
      <button type="button" className="update-banner-reload" onClick={() => window.location.reload()}>
        Reload
      </button>
      <button type="button" className="icon-btn update-banner-close" aria-label="Dismiss" onClick={dismissUpdate}>
        <X size={16} />
      </button>
    </section>
  );
}
