"use client";

import Link from "next/link";
import { useEffect, useMemo } from "react";
import { Boxes, ClipboardList, Hammer, Workflow } from "lucide-react";

import { useAuth } from "@/components/AuthProvider";
import { carryContextToParams, rememberCarryContext, type CarryContext } from "@/lib/carryContext";

export type CarryForwardContext = Partial<CarryContext>;

function buildHref(path: string, context: CarryForwardContext, extra?: Record<string, string>) {
  const params = carryContextToParams(context);
  for (const [key, value] of Object.entries(extra ?? {})) params.set(key, value);
  const query = params.toString();
  return query ? `${path}?${query}` : path;
}

export function CarryForwardCards({
  context,
  heading = "Continue with this context"
}: {
  context: CarryForwardContext;
  heading?: string;
}) {
  const { user } = useAuth();
  const userId = user?.id ?? null;
  const signature = useMemo(() => carryContextToParams(context).toString(), [context]);

  useEffect(() => {
    rememberCarryContext(userId, Object.fromEntries(new URLSearchParams(signature)));
  }, [userId, signature]);

  const cards = [
    { href: buildHref("/products/new", context), title: "Add a product", body: "Record an object, product or sample.", icon: Boxes },
    { href: buildHref("/tools/new", context), title: "Add a tool", body: "Document a tool used by this artisan.", icon: Hammer },
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
            <span className="grid h-11 w-11 shrink-0 place-items-center rounded-lg bg-field-200 text-field-700 dark:text-purple-300">
              <card.icon className="h-5 w-5" aria-hidden />
            </span>
            <span>
              <span className="block font-display font-bold text-lg text-ink">{card.title}</span>
              <span className="mt-1 block text-sm leading-6 text-ink-muted">{card.body}</span>
              {context.artisanName ? <span className="mt-2 block text-xs font-semibold uppercase text-field-700 dark:text-purple-300">{context.artisanName}</span> : null}
            </span>
          </Link>
        ))}
      </div>
    </section>
  );
}
