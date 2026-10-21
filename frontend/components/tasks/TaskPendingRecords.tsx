"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { ClipboardCheck } from "lucide-react";

import { plural, reviewQueueHref } from "@/lib/review";
import { loadTaskPendingRecords, type TaskPendingSummary } from "@/lib/taskPendingRecords";

/**
 * The records a task produced that are still waiting for review (B12), with a link to exactly those
 * rows in the review queue for whoever reviews them. Draws nothing while loading, on a failure, or
 * when nothing is waiting: it is an aside under the progress bar, never the card itself. Every card
 * mounted in the same tick shares one batched request (`lib/taskPendingRecords.ts`).
 */
export function TaskPendingRecords({ taskId, showReviewLink }: { taskId: string; showReviewLink: boolean }) {
  const [answer, setAnswer] = useState<TaskPendingSummary | null>(null);

  useEffect(() => {
    let cancelled = false;
    void loadTaskPendingRecords(taskId).then((value) => {
      if (!cancelled) setAnswer(value);
    });
    return () => {
      cancelled = true;
    };
  }, [taskId]);

  if (!answer || answer.total === 0) return null;
  return (
    <p className="mt-2 flex flex-wrap items-center gap-x-2 gap-y-1 text-xs text-ink-700">
      <ClipboardCheck className="h-3.5 w-3.5 text-purple-700" aria-hidden />
      <span>{plural(answer.total, "record")} waiting for review</span>
      {showReviewLink ? (
        <Link className="font-semibold text-purple-700 underline-offset-2 hover:underline" href={reviewQueueHref(answer.reviewQuery)}>
          Review them
        </Link>
      ) : null}
    </p>
  );
}
