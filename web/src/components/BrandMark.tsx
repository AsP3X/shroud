import { useId, type SVGProps } from "react";
import { useLogoStyle, type LogoStyle } from "../logo";

/*
 * The app icon, drawn inline: design/icon/shroud-icon.svg (detailed) and
 * shroud-icon-simple.svg (simple) are the masters, and design/icon/build.sh names this file.
 */
const VEIL =
  "M512 204C686 204 788 330 792 500C795 612 806 690 818 752C826 792 806 818 776 818C742 818 720 752 684 752C646 752 628 824 590 824C552 824 532 758 494 758C446 758 400 790 340 818C300 836 250 850 206 852C236 820 242 776 236 720C230 650 230 580 232 500C236 330 338 204 512 204Z";
const FOLDS = [
  "M596 430C640 530 676 640 684 752C704 790 730 812 760 830C748 690 690 530 596 430Z",
  "M424 440C468 540 490 650 494 758C512 790 540 816 572 836C562 690 510 540 424 440Z",
];
const PLACE = "translate(512 518) scale(0.9) translate(-512 -528)";

/**
 * Shroud's brand mark: the app icon on a rounded tile. Detailed by default, the flat veil when
 * the viewer picked the simple logo in Appearance; `variant` pins one (the Appearance preview).
 */
export function BrandMark({
  size = 24,
  variant,
  ...rest
}: { size?: number; variant?: LogoStyle } & SVGProps<SVGSVGElement>) {
  const chosen = useLogoStyle();
  const style = variant ?? chosen;
  // Several marks share a page; gradient ids must not collide. useId's colons break url(#…).
  const id = `bm${useId().replace(/[^a-zA-Z0-9]/g, "")}`;
  return (
    <svg width={size} height={size} viewBox="0 0 1024 1024" aria-hidden="true" {...rest}>
      <defs>
        <linearGradient id={`${id}bg`} x1="0.15" y1="0" x2="0.85" y2="1">
          <stop offset="0" stopColor="#7D7BFA" />
          <stop offset="0.55" stopColor="#5E5CE6" />
          <stop offset="1" stopColor="#3432B8" />
        </linearGradient>
        <clipPath id={`${id}tile`}>
          <rect width="1024" height="1024" rx="230" />
        </clipPath>
        {style === "detailed" && (
          <>
            <radialGradient id={`${id}glow`} cx="0.5" cy="0.3" r="0.6">
              <stop offset="0" stopColor="#fff" stopOpacity="0.18" />
              <stop offset="1" stopColor="#fff" stopOpacity="0" />
            </radialGradient>
            <linearGradient id={`${id}cloth`} x1="0" y1="0" x2="0" y2="1">
              <stop offset="0" stopColor="#FFFFFF" />
              <stop offset="1" stopColor="#E4E3FF" />
            </linearGradient>
            <linearGradient id={`${id}fold`} x1="0" y1="0" x2="0" y2="1">
              <stop offset="0" stopColor="#4B49D8" stopOpacity="0" />
              <stop offset="1" stopColor="#4B49D8" stopOpacity="0.24" />
            </linearGradient>
            <linearGradient id={`${id}side`} x1="0" y1="0" x2="1" y2="0">
              <stop offset="0.7" stopColor="#4B49D8" stopOpacity="0" />
              <stop offset="1" stopColor="#4B49D8" stopOpacity="0.12" />
            </linearGradient>
            <filter id={`${id}shadow`} x="-20%" y="-20%" width="140%" height="150%">
              <feGaussianBlur stdDeviation="22" />
            </filter>
            <clipPath id={`${id}veil`}>
              <path d={VEIL} />
            </clipPath>
          </>
        )}
      </defs>
      <g clipPath={`url(#${id}tile)`}>
        <rect width="1024" height="1024" fill={`url(#${id}bg)`} />
        {style === "detailed" ? (
          <>
            <rect width="1024" height="1024" fill={`url(#${id}glow)`} />
            <g transform={PLACE}>
              <path d={VEIL} fill="#0A0930" opacity="0.35" filter={`url(#${id}shadow)`} transform="translate(0 26)" />
              <path d={VEIL} fill={`url(#${id}cloth)`} />
              <g clipPath={`url(#${id}veil)`}>
                <rect x="200" y="200" width="620" height="660" fill={`url(#${id}side)`} />
                {FOLDS.map((d) => (
                  <path key={d} d={d} fill={`url(#${id}fold)`} />
                ))}
              </g>
            </g>
          </>
        ) : (
          <path d={VEIL} transform={PLACE} fill="#fff" />
        )}
      </g>
    </svg>
  );
}
