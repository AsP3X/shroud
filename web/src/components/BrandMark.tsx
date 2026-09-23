import type { SVGProps } from "react";

/** The veil path from the app icon; the master is design/icon/shroud-mark.svg. */
const VEIL =
  "M512 204C686 204 788 330 792 500C795 612 806 690 818 752C826 792 806 818 776 818C742 818 720 752 684 752C646 752 628 824 590 824C552 824 532 758 494 758C446 758 400 790 340 818C300 836 250 850 206 852C236 820 242 776 236 720C230 650 230 580 232 500C236 330 338 204 512 204Z";

/** Shroud's brand mark, glyph only: fills with `currentColor`, sized like a lucide icon. */
export function BrandMark({ size = 24, ...rest }: { size?: number } & SVGProps<SVGSVGElement>) {
  return (
    <svg width={size} height={size} viewBox="172 188 680 680" fill="currentColor" aria-hidden="true" {...rest}>
      <path d={VEIL} />
    </svg>
  );
}
