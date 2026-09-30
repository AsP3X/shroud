/** Circular transfer indicator: fills with `progress` (0…1), or spins while the size is unknown. */
export function ProgressRing({
  progress,
  size = 40,
  stroke = 3,
}: {
  progress: number | null;
  size?: number;
  stroke?: number;
}) {
  const center = size / 2;
  const radius = (size - stroke) / 2;
  const circumference = 2 * Math.PI * radius;
  const known = progress != null;
  const filled = known ? Math.max(0.04, Math.min(1, progress)) * circumference : circumference * 0.26;
  return (
    <svg
      className={known ? "ring" : "ring ring-spin"}
      width={size}
      height={size}
      viewBox={`0 0 ${size} ${size}`}
      aria-hidden="true"
    >
      <circle className="ring-track" cx={center} cy={center} r={radius} strokeWidth={stroke} />
      <circle
        className="ring-fill"
        cx={center}
        cy={center}
        r={radius}
        strokeWidth={stroke}
        strokeDasharray={`${filled} ${circumference}`}
        transform={`rotate(-90 ${center} ${center})`}
      />
    </svg>
  );
}
