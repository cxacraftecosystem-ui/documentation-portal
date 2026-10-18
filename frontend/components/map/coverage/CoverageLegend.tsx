"use client";

import { binLabels } from "@/components/map/coverage/coverageMath";
import { BIN_CLASSES } from "@/components/map/coverage/CoverageMap";

/** Five quantile bins, the hatched "no workshop" swatch and the dashed parent outline. */
export function CoverageLegend({
  breaks,
  metricLabel,
  showParent
}: {
  breaks: number[];
  metricLabel: string;
  showParent: boolean;
}) {
  const labels = binLabels(breaks);
  return (
    <div className="flex flex-wrap items-center gap-x-4 gap-y-2 text-xs text-ink-500" data-testid="coverage-legend">
      <span className="font-medium text-ink-700">{metricLabel}</span>
      <span className="inline-flex items-center gap-1.5">
        <svg width={14} height={14} aria-hidden>
          <rect width={14} height={14} rx={3} className="fill-field-100 stroke-line-200" />
        </svg>
        None
      </span>
      {labels.map((label, index) => (
        <span key={label} className="inline-flex items-center gap-1.5">
          <svg width={14} height={14} aria-hidden>
            <rect width={14} height={14} rx={3} className={BIN_CLASSES[index]} />
          </svg>
          {label}
        </span>
      ))}
      <span className="inline-flex items-center gap-1.5">
        <svg width={14} height={14} aria-hidden>
          <defs>
            <pattern id="cov-legend-hatch" width={4} height={4} patternUnits="userSpaceOnUse" patternTransform="rotate(45)">
              <line x1={0} y1={0} x2={0} y2={4} className="stroke-ink-500" strokeWidth={1.2} />
            </pattern>
          </defs>
          <rect width={14} height={14} rx={3} fill="url(#cov-legend-hatch)" className="stroke-line-200" />
        </svg>
        No workshop
      </span>
      {showParent ? (
        <span className="inline-flex items-center gap-1.5">
          <svg width={14} height={14} aria-hidden>
            <rect x={1} y={1} width={12} height={12} rx={3} className="fill-none stroke-ink-500" strokeDasharray="3 2" />
          </svg>
          Includes a district shown inside its parent
        </span>
      ) : null}
    </div>
  );
}
