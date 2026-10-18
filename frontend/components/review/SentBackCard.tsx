import Link from "next/link";
import { ArrowRight, MessageSquareWarning } from "lucide-react";

import { sentBackNote, sentBackTitle } from "@/lib/review";

/** The dashboard's "Sent back to you (N)" card (A2): one tap to every record a reviewer returned. */
export function SentBackCard({ sentBack, rejected }: { sentBack: number; rejected: number }) {
  return (
    <Link
      href="/sent-back"
      className="panel mb-5 flex items-center gap-3 border-purple-300 p-4 transition hover:border-purple-600 hover:bg-purple-50"
    >
      <span className="grid h-10 w-10 shrink-0 place-items-center rounded-md bg-purple-700 text-white">
        <MessageSquareWarning className="h-5 w-5" aria-hidden />
      </span>
      <span className="min-w-0 flex-1">
        <span className="block font-display text-base font-bold text-ink-900">{sentBackTitle(sentBack + rejected)}</span>
        <span className="block text-sm text-ink-500">{sentBackNote(sentBack, rejected)}</span>
      </span>
      <ArrowRight className="h-5 w-5 shrink-0 text-purple-700" aria-hidden />
    </Link>
  );
}
