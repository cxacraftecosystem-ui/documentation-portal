"use client";

import { ArrowLeft } from "lucide-react";
import { useRouter } from "next/navigation";

/**
 * Round back control (Android `BackPill` parity): steps to the previous screen, or to an
 * explicit `href` when the page knows where "back" should land (deep links, post-save).
 */
export function BackButton({ href }: { href?: string }) {
  const router = useRouter();
  return (
    <button
      type="button"
      aria-label="Go back"
      title="Go back"
      onClick={() => {
        if (href) router.push(href);
        else router.back();
      }}
      className="grid h-10 w-10 shrink-0 place-items-center rounded-full border border-line-200 bg-card text-purple-700 shadow-sm transition hover:border-purple-300 hover:bg-purple-50"
    >
      <ArrowLeft className="h-5 w-5" aria-hidden />
    </button>
  );
}
