import { completenessAria, completenessSentence, type CompletenessBlock } from "@/lib/completenessCore";

/**
 * A record's completeness as a small ring: 16px on a list row, 28px on the record's page. Drawn with
 * the progress tokens (§11.12) so it reads like every other meter, and green at complete — with the
 * count in its accessible name, so the colour is never the only signal.
 */
export function CompletenessRing({
  block,
  size = 16,
  title
}: {
  block: Pick<CompletenessBlock, "filled" | "total" | "percent">;
  size?: 16 | 18 | 28;
  title?: string;
}) {
  const stroke = size >= 28 ? 4 : 3;
  const radius = (size - stroke) / 2;
  const circumference = 2 * Math.PI * radius;
  const fraction = block.total ? block.filled / block.total : 1;
  const done = block.filled >= block.total;
  const label = completenessAria(block);
  return (
    <svg
      role="img"
      aria-label={label}
      width={size}
      height={size}
      viewBox={`0 0 ${size} ${size}`}
      className="shrink-0 -rotate-90"
      data-completeness-ring
    >
      <title>{title ?? label}</title>
      <circle cx={size / 2} cy={size / 2} r={radius} fill="none" className="stroke-progress-track" strokeWidth={stroke} />
      {fraction > 0 ? (
        <circle
          cx={size / 2}
          cy={size / 2}
          r={radius}
          fill="none"
          className={done ? "stroke-progress-done" : "stroke-progress-fill"}
          strokeWidth={stroke}
          strokeLinecap="round"
          strokeDasharray={`${circumference * fraction} ${circumference}`}
        />
      ) : null}
    </svg>
  );
}

/** The 16px ring a list row draws before the record's name, its sentence as the tooltip. */
export function RowCompletenessRing({ block }: { block?: CompletenessBlock | null }) {
  if (!block) return null;
  return <CompletenessRing block={block} size={16} title={completenessSentence(block)} />;
}
