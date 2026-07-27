"use client";

import { ArrowLeft } from "lucide-react";
import { useRouter } from "next/navigation";

import { useLeaveInterceptor } from "@/components/UnsavedChangesGuard";

/**
 * Round back control (Android `BackPill` parity): steps to the previous screen, or to an
 * explicit `href` when the page knows where "back" should land (deep links, post-save).
 *
 * This is the ONLY back control on a page. Forms used to add a second, rounded "Back" pill of their
 * own purely because the unsaved-changes prompt needed to live where the dirty flag was; that pill
 * is gone and the prompt happens here instead, via the guard context. If you are adding a back
 * control to a form, you do not need one — the page header already has it.
 */
export function BackButton({ href }: { href?: string }) {
  const router = useRouter();
  const interceptLeave = useLeaveInterceptor();
  return (
    <button
      type="button"
      aria-label="Go back"
      title="Go back"
      onClick={() => {
        // A dirty form answers true and raises its own prompt; leaving is then that dialog's
        // decision, not ours. Checked before either navigation branch so an explicit `href` cannot
        // slip past the prompt.
        if (interceptLeave()) return;
        if (href) router.push(href);
        else router.back();
      }}
      className="grid h-10 w-10 shrink-0 place-items-center rounded-full border border-line-200 bg-card text-purple-700 shadow-sm transition hover:border-purple-300 hover:bg-purple-50"
    >
      <ArrowLeft className="h-5 w-5" aria-hidden />
    </button>
  );
}
