/** The Shroud veil on an accent tile, from web/public/favicon-simple.svg (copied, not imported). */
export function Mark({ size }: { size: 28 | 32 | 44 }) {
  const glyph = size === 44 ? 26 : size === 32 ? 20 : 17;
  return (
    <span className={`mark mark--${size}`} aria-hidden="true">
      <svg width={glyph} height={glyph} viewBox="172 188 680 680">
        <path
          fill="currentColor"
          d="M512 204C686 204 788 330 792 500C795 612 806 690 818 752C826 792 806 818 776 818C742 818 720 752 684 752C646 752 628 824 590 824C552 824 532 758 494 758C446 758 400 790 340 818C300 836 250 850 206 852C236 820 242 776 236 720C230 650 230 580 232 500C236 330 338 204 512 204Z"
        />
      </svg>
    </span>
  );
}
