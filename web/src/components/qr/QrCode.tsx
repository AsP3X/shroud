import { useMemo, type CSSProperties } from "react";
import { Shield } from "lucide-react";
import { encode } from "uqr";

/* Geometry in viewBox units, taken 1:1 from the "QR Code" component in
   design/webclient.pen. Every styling choice here was checked with a decoder
   across 15 render scales — see the notes before changing any of them. */
const M = 6; // one module
const QUIET = 28; // ≈4.7 modules of white — scanners need ≥4
const HOLE = 4; // 9×9 modules cleared behind the mark (≈7% of a v4 code; EC level Q recovers 25%)
const MARK = 40;
const RINGS = 7;
const INK = "#0b0b12";
const ACCENT = "#5e5ce6";

/* uqr module types */
const FINDER = 2;
const TIMING = 3;
const ALIGNMENT = 4;

const R = +(M * 0.32).toFixed(2);
const K = +(M - 2 * R).toFixed(2);

/* Data modules are squircles that still touch their neighbours. Separated dots
   looked nicer but broke decoding at 8 of 15 scales: the gaps split the
   continuous dark runs decoders measure. */
const squircle = (x: number, y: number) =>
  `M${x + R} ${y}h${K}a${R} ${R} 0 0 1 ${R} ${R}v${K}a${R} ${R} 0 0 1 ${-R} ${R}h${-K}a${R} ${R} 0 0 1 ${-R} ${-R}v${-K}a${R} ${R} 0 0 1 ${R} ${-R}z`;
/* Timing modules stay square: dotting them was the other half of that failure. */
const square = (x: number, y: number) => `M${x} ${y}h${M}v${M}h${-M}z`;

type Built = {
  side: number;
  rings: string[];
  aligns: { x: number; y: number; ring: number }[];
  finders: { x: number; y: number }[];
};

function build(value: string): Built {
  const { size: n, data, types } = encode(value, { ecc: "Q", border: 0 });
  const c = (n - 1) / 2;
  const reach = Math.hypot(c, c) + 0.5;
  const ringOf = (x: number, y: number) =>
    Math.min(RINGS - 1, Math.floor((Math.hypot(x - c, y - c) / reach) * RINGS));

  const rings = Array.from({ length: RINGS }, () => "");
  const aligns: Built["aligns"] = [];
  for (let y = 0; y < n; y++) {
    for (let x = 0; x < n; x++) {
      const type = types[y][x];
      if (type === ALIGNMENT) {
        const origin = types[y - 1]?.[x] !== ALIGNMENT && types[y][x - 1] !== ALIGNMENT;
        if (origin) aligns.push({ x: x * M, y: y * M, ring: ringOf(x + 2, y + 2) });
        continue;
      }
      if (!data[y][x] || type === FINDER) continue;
      if (Math.abs(x - c) <= HOLE && Math.abs(y - c) <= HOLE) continue;
      rings[ringOf(x, y)] += type === TIMING ? square(x * M, y * M) : squircle(x * M, y * M);
    }
  }
  const finders = [
    { x: 0, y: 0 },
    { x: (n - 7) * M, y: 0 },
    { x: 0, y: (n - 7) * M },
  ];
  return { side: QUIET * 2 + n * M, rings, aligns, finders };
}

export function QrCode({ value, label }: { value: string; label: string }) {
  const built = useMemo(() => (value.trim() ? build(value) : null), [value]);
  if (!built) return null;
  const { side, rings, aligns, finders } = built;
  const mid = side / 2;
  return (
    <svg
      className="qr-svg"
      viewBox={`0 0 ${side} ${side}`}
      role="img"
      aria-label={label}
      style={{ "--rings": RINGS } as CSSProperties}
    >
      <g transform={`translate(${QUIET} ${QUIET})`}>
        {rings.map((d, i) =>
          d ? (
            <path key={i} className="qr-ring" d={d} fill={INK} style={{ "--ring": i } as CSSProperties} />
          ) : null,
        )}
        {aligns.map(({ x, y, ring }) => (
          <g key={`${x}-${y}`} className="qr-ring" style={{ "--ring": ring } as CSSProperties}>
            <rect x={x + M / 2} y={y + M / 2} width={4 * M} height={4 * M} rx={M / 2} fill="none" stroke={INK} strokeWidth={M} />
            <rect x={x + 2 * M} y={y + 2 * M} width={M} height={M} rx={R} fill={INK} />
          </g>
        ))}
        <g className="qr-eyes">
          {finders.map(({ x, y }) => (
            <g key={`${x}-${y}`}>
              <rect x={x + M / 2} y={y + M / 2} width={6 * M} height={6 * M} rx={M} fill="none" stroke={ACCENT} strokeWidth={M} />
              <rect x={x + 2 * M} y={y + 2 * M} width={3 * M} height={3 * M} rx={M} fill={ACCENT} />
            </g>
          ))}
        </g>
      </g>
      <g className="qr-mark">
        <rect x={mid - MARK / 2} y={mid - MARK / 2} width={MARK} height={MARK} rx={12} fill={ACCENT} />
        <Shield x={mid - 10} y={mid - 10} width={20} height={20} color="#fff" strokeWidth={2.2} aria-hidden="true" />
      </g>
    </svg>
  );
}
