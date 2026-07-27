"use client";

import Link from "next/link";
import { useEffect, useMemo } from "react";
import { Boxes, ClipboardList, Hammer, Workflow } from "lucide-react";

import { useAuth } from "@/components/AuthProvider";
import { carryContextToParams, rememberCarryContext, type CarryContext } from "@/lib/carryContext";

/**
 * Everything the just-saved record knows about the sitting. It is the same bag lib/carryContext
 * stores, so a card's link and the remembered context can never drift apart.
 */
export type CarryForwardContext = Partial<CarryContext>;

function buildHref(path: string, context: CarryForwardContext, extra?: Record<string, string>) {
  const params = carryContextToParams(context);
  for (const [key, value] of Object.entries(extra ?? {})) params.set(key, value);
  const query = params.toString();
  return query ? `${path}?${query}` : path;
}

/**
 * Cards that carry the current record's context — artisan, craft, place, workshop, and the product
 * itself once there is one — into the next entry, so a researcher can flow from one record straight
 * into a linked tool/product/process/questionnaire without re-typing shared fields.
 *
 * The links pass the context in the query string, which only survives a click made from THIS
 * screen. Mounting also banks it in lib/carryContext, so a researcher who instead goes out via the
 * dashboard — or comes back to a product form an hour later — is still offered the same context,
 * which is the path they actually take.
 */
export function CarryForwardCards({
  context,
  heading = "Continue with this context"
}: {
  context: CarryForwardContext;
  heading?: string;
}) {
  const { user } = useAuth();
  const userId = user?.id ?? null;
  // The caller rebuilds the object every render, so the effect keys off the values themselves.
  const signature = useMemo(() => carryContextToParams(context).toString(), [context]);

  useEffect(() => {
    rememberCarryContext(userId, Object.fromEntries(new URLSearchParams(signature)));
  }, [userId, signature]);

  const cards = [
    { href: buildHref("/products/new", context), title: "Add a product", body: "Record an object, product or sample.", icon: Boxes },
    { href: buildHref("/tools/new", context), title: "Add a tool", body: "Document a tool used by this artisan.", icon: Hammer },
    // A process documents a product being made, so the card is only honest once there is a product
    // to point it at — offering it earlier lands the researcher on a form they cannot complete.
    ...(context.productId
      ? [{ href: buildHref("/processes", context, { new: "1" }), title: "Document the process", body: `Capture how ${context.productName ?? "this product"} is made, step by step.`, icon: Workflow }]
      : []),
    { href: buildHref("/questionnaire", context), title: "Start questionnaire", body: "Open the interview with details prefilled.", icon: ClipboardList }
  ];
  return (
    <section className="grid gap-3">
      <h3 className="font-display font-bold text-lg text-ink">{heading}</h3>
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
              <span className="block font-display font-bold text-lg text-ink">{card.title}</span>
              <span className="mt-1 block text-sm leading-6 text-ink-muted">{card.body}</span>
              {context.artisanName ? <span className="mt-2 block text-xs font-semibold uppercase text-field-700">{context.artisanName}</span> : null}
            </span>
          </Link>
        ))}
      </div>
    </section>
  );
}
