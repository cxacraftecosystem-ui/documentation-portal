"use client";

import Link from "next/link";
import { Boxes, ClipboardList, Hammer } from "lucide-react";

export type CarryForwardContext = {
  artisanId?: string;
  artisanName?: string;
  place?: string;
  craftId?: string | null;
  craftName?: string | null;
};

function buildHref(path: string, context: CarryForwardContext) {
  const params = new URLSearchParams();
  if (context.artisanId) params.set("artisanId", context.artisanId);
  if (context.artisanName) params.set("artisanName", context.artisanName);
  if (context.place) params.set("place", context.place);
  if (context.craftId) params.set("craftId", context.craftId);
  if (context.craftName) params.set("craftName", context.craftName);
  const query = params.toString();
  return query ? `${path}?${query}` : path;
}

/**
 * Cards that carry the current record's context (artisan, place, craft) into the next entry, so a
 * researcher can flow from one record straight into a linked tool/product/questionnaire without
 * re-typing shared fields.
 */
export function CarryForwardCards({
  context,
  heading = "Continue with this context"
}: {
  context: CarryForwardContext;
  heading?: string;
}) {
  const cards = [
    { href: buildHref("/products/new", context), title: "Add a product", body: "Record an object, product or sample.", icon: Boxes },
    { href: buildHref("/tools/new", context), title: "Add a tool", body: "Document a tool used by this artisan.", icon: Hammer },
    { href: buildHref("/questionnaire", context), title: "Start questionnaire", body: "Open the interview with details prefilled.", icon: ClipboardList }
  ];
  return (
    <section className="grid gap-3">
      <h3 className="font-serif text-lg text-ink">{heading}</h3>
      <div className="grid gap-3 md:grid-cols-3">
        {cards.map((card) => (
          <Link
            key={card.href}
            href={card.href}
            className="panel group flex min-h-28 items-start gap-3 p-4 transition hover:-translate-y-0.5 hover:shadow-panel active:scale-[0.99]"
          >
            <span className="grid h-11 w-11 shrink-0 place-items-center rounded-lg bg-field-200 text-field-700">
              <card.icon className="h-5 w-5" aria-hidden />
            </span>
            <span>
              <span className="block font-serif text-lg text-ink">{card.title}</span>
              <span className="mt-1 block text-sm leading-6 text-ink-muted">{card.body}</span>
              {context.artisanName ? <span className="mt-2 block text-xs font-semibold uppercase text-field-700">{context.artisanName}</span> : null}
            </span>
          </Link>
        ))}
      </div>
    </section>
  );
}
