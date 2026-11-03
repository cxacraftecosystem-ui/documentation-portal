"use client";

import { Lock } from "lucide-react";

export function RestrictedPanel({ title, body }: { title: string; body: string }) {
  return (
    <section className="panel px-6 py-12 text-center">
      <div className="mx-auto mb-3 grid h-11 w-11 place-items-center rounded-full bg-purple-100 text-purple-700">
        <Lock className="h-5 w-5" aria-hidden />
      </div>
      <h2 className="font-display text-base font-semibold text-ink-900">{title}</h2>
      <p className="mx-auto mt-2 max-w-md text-sm leading-6 text-ink-500">{body}</p>
    </section>
  );
}
