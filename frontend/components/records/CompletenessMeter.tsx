"use client";

import Link from "next/link";

import { CompletenessRing } from "@/components/records/CompletenessRing";
import { completenessSentence, withFocus, type CompletenessBlock } from "@/lib/completenessCore";

const PILL =
  "inline-flex min-h-9 items-center rounded-full border px-3 py-1 text-xs font-semibold transition max-sm:min-h-11";
const PILL_REQUIRED = `${PILL} border-red-200 bg-error-100 text-red-700`;
const PILL_RECOMMENDED = `${PILL} border-line-200 bg-card text-ink-700`;
const PILL_LINK_HOVER = "hover:border-purple-300 hover:bg-purple-50 hover:text-purple-800";

/**
 * The ring, the sentence, and one pill per missing field — required ones first, in the error tone.
 *
 * A pill is a link into the edit form with `focus=<key>` when the reader may update the record
 * (`updateHref` given), and plain text otherwise, so a ministry or read-only reader still sees what is
 * missing. On an edit form, `onPick` replaces the link: the field is on the same screen.
 */
export function CompletenessMeter({
  block,
  updateHref,
  onPick,
  compact = false,
  className
}: {
  block: CompletenessBlock;
  updateHref?: string | null;
  onPick?: (key: string) => void;
  compact?: boolean;
  className?: string;
}) {
  const sentence = completenessSentence(block);
  const complete = block.missing.length === 0;
  return (
    <section
      aria-label="Completeness"
      data-completeness-meter
      className={`${compact ? "" : "panel px-4 py-3"} flex flex-wrap items-center gap-x-3 gap-y-2 ${className ?? ""}`}
    >
      <CompletenessRing block={block} size={compact ? 18 : 28} />
      <span className={`text-sm ${complete ? "font-semibold text-ink-900" : "text-ink-700"}`}>{sentence}</span>
      {complete ? null : (
        <ul className="flex basis-full flex-wrap gap-2 sm:basis-auto" aria-label="Missing fields">
          {block.missing.map((item) => {
            const tone = item.level === "required" ? PILL_REQUIRED : PILL_RECOMMENDED;
            return (
              <li key={item.key}>
                {onPick ? (
                  <button type="button" className={`${tone} ${PILL_LINK_HOVER}`} onClick={() => onPick(item.key)} data-focus-key={item.key}>
                    {item.label}
                  </button>
                ) : updateHref ? (
                  <Link className={`${tone} ${PILL_LINK_HOVER}`} href={withFocus(updateHref, item.key)} data-focus-key={item.key}>
                    {item.label}
                  </Link>
                ) : (
                  <span className={tone}>{item.label}</span>
                )}
              </li>
            );
          })}
        </ul>
      )}
    </section>
  );
}
