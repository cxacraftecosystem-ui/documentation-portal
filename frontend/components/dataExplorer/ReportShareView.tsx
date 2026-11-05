"use client";

import { useState } from "react";

import {
  chartValue,
  dayLabel,
  formatWhen,
  hasData,
  kpiValue,
  labelEvery,
  niceMax,
  productName,
  stackTotals,
  ticks,
  type ShareChart,
  type ShareSnapshot
} from "@/lib/dataExplorer/reportShare";

const STATUS_FILL: Record<string, string> = {
  Complete: "bg-success-600",
  Partial: "bg-amber-500",
  Missing: "bg-error-600"
};

function ChartTable({ chart }: { chart: ShareChart }) {
  const dated = chart.key === "recordingsOverTime";
  return (
    <details className="mt-3 text-xs text-ink-500">
      <summary className="cursor-pointer select-none font-medium text-ink-700">Show as table</summary>
      <div className="mt-2 max-h-64 overflow-auto rounded-md border border-line-200">
        <table className="w-full text-left text-xs">
          <thead className="sticky top-0 z-10 bg-surface-50 text-ink-500">
            <tr>
              <th scope="col" className="px-2 py-1.5 font-medium">{dated ? "Date" : "Category"}</th>
              {chart.series.map((s) => (
                <th key={s.name} scope="col" className="px-2 py-1.5 text-right font-medium">{s.name}</th>
              ))}
            </tr>
          </thead>
          <tbody>
            {chart.categories.map((category, i) => (
              <tr key={category} className="border-t border-line-200 text-ink-700">
                <th scope="row" className="px-2 py-1 font-normal">{dated ? dayLabel(category) : category}</th>
                {chart.series.map((s) => (
                  <td key={s.name} className="px-2 py-1 text-right tabular-nums">{chartValue(s.values[i] ?? 0, chart.unit)}</td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </details>
  );
}

function BarList({ chart }: { chart: ShareChart }) {
  const values = chart.series[0]?.values ?? [];
  const top = chart.unit === "percent" ? 100 : Math.max(1, ...values);
  return (
    <ul className="grid gap-2.5">
      {chart.categories.map((category, i) => {
        const value = values[i] ?? 0;
        return (
          <li key={category} className="grid grid-cols-[minmax(0,7.5rem)_minmax(0,1fr)_auto] items-center gap-3 text-sm" title={`${category}: ${chartValue(value, chart.unit)}`}>
            <span className="truncate text-ink-700">{category}</span>
            <span className="h-3 rounded-full bg-progress-track">
              <span className="block h-3 rounded-full bg-progress-fill" style={{ width: `${Math.max(2, (value / top) * 100)}%` }} />
            </span>
            <span className="min-w-8 text-right font-medium tabular-nums text-ink-900">{chartValue(value, chart.unit)}</span>
          </li>
        );
      })}
    </ul>
  );
}

function StackedBars({ chart }: { chart: ShareChart }) {
  const totals = stackTotals(chart);
  return (
    <div className="grid gap-3">
      <ul className="flex flex-wrap gap-x-4 gap-y-1 text-xs text-ink-500" aria-label="Legend">
        {chart.series.map((s) => (
          <li key={s.name} className="flex items-center gap-1.5">
            <span aria-hidden className={`h-2.5 w-2.5 rounded-sm ${STATUS_FILL[s.name] ?? "bg-progress-fill"}`} />
            {s.name}
          </li>
        ))}
      </ul>
      <ul className="grid gap-2.5">
        {chart.categories.map((category, i) => {
          const total = totals[i] || 1;
          const parts = chart.series.map((s) => ({ name: s.name, value: s.values[i] ?? 0 })).filter((p) => p.value > 0);
          const summary = parts.map((p) => `${p.name} ${p.value}`).join(", ");
          return (
            <li key={category} className="grid gap-1 text-sm" title={`${category}: ${summary}`}>
              <span className="flex items-baseline justify-between gap-2">
                <span className="truncate text-ink-700">{category}</span>
                <span className="shrink-0 text-xs tabular-nums text-ink-500">{summary}</span>
              </span>
              <span className="flex h-3 gap-0.5 overflow-hidden rounded-full bg-progress-track">
                {parts.map((p) => (
                  <span key={p.name} className={`h-3 first:rounded-l-full last:rounded-r-full ${STATUS_FILL[p.name] ?? "bg-progress-fill"}`} style={{ width: `${(p.value / total) * 100}%` }} />
                ))}
              </span>
            </li>
          );
        })}
      </ul>
    </div>
  );
}

const COLUMN_HEIGHT = 180;
const COLUMN_WIDTH = 420;
const AXIS_LEFT = 28;
const AXIS_BOTTOM = 22;

function Columns({ chart }: { chart: ShareChart }) {
  const [hover, setHover] = useState<number | null>(null);
  const values = chart.series[0]?.values ?? [];
  const top = niceMax(Math.max(0, ...values));
  const marks = ticks(Math.max(0, ...values));
  const count = chart.categories.length;
  const plotWidth = COLUMN_WIDTH - AXIS_LEFT;
  const slot = plotWidth / Math.max(1, count);
  const barWidth = Math.max(2, Math.min(28, slot - 2));
  const every = labelEvery(count, 6);
  const plotHeight = COLUMN_HEIGHT - AXIS_BOTTOM;
  const shown = hover !== null ? { label: dayLabel(chart.categories[hover] ?? ""), value: values[hover] ?? 0 } : null;
  return (
    <div className="relative">
      <p className="mb-1 h-5 text-xs text-ink-700" aria-live="off">
        {shown ? (
          <>
            <span className="font-medium text-ink-900">{chartValue(shown.value, chart.unit)}</span> {chart.series[0]?.name.toLowerCase()} · {/week/i.test(chart.title) ? `week of ${shown.label}` : shown.label}
          </>
        ) : null}
      </p>
      <svg viewBox={`0 0 ${COLUMN_WIDTH} ${COLUMN_HEIGHT}`} className="h-auto w-full" role="group" aria-label={`${chart.title}, ${count} periods`} onMouseLeave={() => setHover(null)}>
        {marks.map((mark) => {
          const y = plotHeight - (mark / top) * (plotHeight - 4);
          return (
            <g key={mark}>
              <line x1={AXIS_LEFT} x2={COLUMN_WIDTH} y1={y} y2={y} className="stroke-line-200" strokeWidth={1} />
              <text x={AXIS_LEFT - 6} y={y + 3} textAnchor="end" className="fill-ink-500 text-[11px]">{mark}</text>
            </g>
          );
        })}
        {values.map((value, i) => {
          const height = top > 0 ? (value / top) * (plotHeight - 4) : 0;
          const x = AXIS_LEFT + i * slot + (slot - barWidth) / 2;
          const radius = Math.min(4, barWidth / 2, height);
          return (
            <g key={chart.categories[i]}>
              <rect
                x={AXIS_LEFT + i * slot}
                y={0}
                width={slot}
                height={plotHeight}
                fill="transparent"
                onMouseEnter={() => setHover(i)}
                onFocus={() => setHover(i)}
                onBlur={() => setHover(null)}
                tabIndex={value > 0 ? 0 : -1}
                aria-label={`${dayLabel(chart.categories[i] ?? "")}: ${value}`}
              />
              {height > 0 ? (
                <path
                  pointerEvents="none"
                  className={hover === i ? "fill-purple-900 dark:fill-purple-300" : "fill-progress-fill"}
                  d={`M${x},${plotHeight} V${plotHeight - height + radius} Q${x},${plotHeight - height} ${x + radius},${plotHeight - height} H${x + barWidth - radius} Q${x + barWidth},${plotHeight - height} ${x + barWidth},${plotHeight - height + radius} V${plotHeight} Z`}
                />
              ) : null}
              {i % every === 0 ? (
                <text x={AXIS_LEFT + i * slot + slot / 2} y={COLUMN_HEIGHT - 6} textAnchor="middle" className="fill-ink-500 text-[11px]">
                  {dayLabel(chart.categories[i] ?? "")}
                </text>
              ) : null}
            </g>
          );
        })}
        <line x1={AXIS_LEFT} x2={COLUMN_WIDTH} y1={plotHeight} y2={plotHeight} className="stroke-ink-300" strokeWidth={1} />
      </svg>
    </div>
  );
}

function ChartCard({ chart }: { chart: ShareChart }) {
  const empty = !hasData(chart);
  return (
    <section className="panel p-4 sm:p-5" aria-labelledby={`share-chart-${chart.key}`}>
      <h2 id={`share-chart-${chart.key}`} className="font-display text-base font-bold text-ink-900">{chart.title}</h2>
      <div className="mt-3">
        {empty ? (
          <p className="py-6 text-center text-sm text-ink-500">Nothing to show yet.</p>
        ) : chart.kind === "stackedBar" ? (
          <StackedBars chart={chart} />
        ) : chart.kind === "column" || chart.kind === "line" ? (
          <Columns chart={chart} />
        ) : (
          <BarList chart={chart} />
        )}
      </div>
      {chart.earlierOmitted ? <p className="mt-2 text-xs text-ink-500">Showing the latest 52 weeks; {chart.earlierOmitted.toLocaleString("en-IN")} earlier not shown.</p> : null}
      {chart.sectionsOmitted ? <p className="mt-2 text-xs text-ink-500">{chart.sectionsOmitted.toLocaleString("en-IN")} more sections not shown.</p> : null}
      {empty ? null : <ChartTable chart={chart} />}
    </section>
  );
}

export function ReportShareView({ snapshot }: { snapshot: ShareSnapshot }) {
  const product = productName(snapshot.portal);
  const trail = snapshot.crumbs.slice(1, -1);
  const filters = Object.entries(snapshot.filters ?? {});
  return (
    <div className="mx-auto grid w-full max-w-6xl gap-5">
      <header className="panel grid gap-3 p-5 sm:p-6">
        <p className="eyebrow">{product} · Live report</p>
        <div className="grid gap-1">
          <h1 className="display-title text-2xl md:text-3xl">{snapshot.title}</h1>
          {trail.length ? <p className="text-sm text-ink-500">{trail.join(" / ")}</p> : null}
        </div>
        <dl className="flex flex-wrap gap-x-6 gap-y-1 text-xs text-ink-500">
          <div className="flex gap-1">
            <dt>Shared by</dt>
            <dd className="font-medium text-ink-700">{snapshot.generatedBy || "a team member"}</dd>
          </div>
          <div className="flex gap-1">
            <dt>Figures from</dt>
            <dd className="font-medium text-ink-700">{formatWhen(snapshot.generatedAt)}</dd>
          </div>
          <div className="flex gap-1">
            <dt>Link expires</dt>
            <dd className="font-medium text-ink-700">{formatWhen(snapshot.expiresAt)}</dd>
          </div>
          {filters.map(([key, value]) => (
            <div key={key} className="flex gap-1">
              <dt className="capitalize">{key}</dt>
              <dd className="font-medium text-ink-700">{value}</dd>
            </div>
          ))}
        </dl>
        {snapshot.truncated ? <p className="text-xs text-amber-800">Large folder: figures cover the first 5,000 of each kind.</p> : null}
      </header>
      <section aria-label="Key figures" className="grid grid-cols-2 gap-3 sm:grid-cols-4 lg:grid-cols-7">
        {snapshot.kpis.map((kpi) => (
          <div key={kpi.key} className="panel grid content-start gap-1 p-4">
            <span className="text-xs font-medium text-ink-500">{kpi.label}</span>
            <span className="font-display text-2xl font-bold tabular-nums text-ink-900">{kpiValue(kpi)}</span>
            {kpi.sub ? <span className="text-xs text-ink-500">{kpi.sub}</span> : null}
          </div>
        ))}
      </section>
      <div className="grid gap-4 lg:grid-cols-2">
        {snapshot.charts.map((chart) => (
          <ChartCard key={chart.key} chart={chart} />
        ))}
      </div>
      {snapshot.withheldTranscripts > 0 ? (
        <p className="text-center text-xs text-ink-500">
          {snapshot.withheldTranscripts.toLocaleString("en-IN")} {snapshot.withheldTranscripts === 1 ? "recording is" : "recordings are"} left out for consent.
        </p>
      ) : null}
      <p className="text-center text-xs text-ink-500">Read-only. Figures were fixed when the link was made.</p>
    </div>
  );
}
