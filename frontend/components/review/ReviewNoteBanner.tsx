"use client";

import { useEffect, useState } from "react";
import { MessageSquareWarning } from "lucide-react";

import { formatDateTime } from "@/lib/format";
import {
  fetchLatestReview,
  isReturned,
  returnedHeading,
  returnedNextStep,
  type LatestReview
} from "@/lib/review";

export function ReviewNoteBanner({
  recordType,
  recordId,
  status
}: {
  recordType: string;
  recordId: string;
  status: string | null | undefined;
}) {
  const [review, setReview] = useState<LatestReview | null>(null);
  const returned = isReturned(status);

  useEffect(() => {
    if (!returned) {
      setReview(null);
      return;
    }
    let cancelled = false;
    fetchLatestReview(recordType, recordId)
      .then((answer) => {
        if (!cancelled) setReview(answer.review);
      })
      .catch(() => {
        if (!cancelled) setReview(null);
      });
    return () => {
      cancelled = true;
    };
  }, [recordType, recordId, returned]);

  if (!returned || !review || !status) return null;
  const rejected = status === "REJECTED";
  return (
    <section
      role="status"
      aria-label="Reviewer's note"
      className={`mb-4 rounded-lg border px-4 py-3 ${
        rejected ? "border-error-600/25 bg-error-100 text-red-800" : "border-purple-300 bg-purple-50 text-purple-900"
      }`}
    >
      <div className="flex items-start gap-3">
        <MessageSquareWarning className="mt-0.5 h-5 w-5 shrink-0" aria-hidden />
        <div className="min-w-0">
          <p className="text-sm font-semibold">
            {returnedHeading(status, review.reviewerName)}
            {review.reviewedAt ? <span className="font-normal"> · {formatDateTime(review.reviewedAt)}</span> : null}
          </p>
          {review.reasonLabel ? (
            <p className="mt-1">
              <span className="rounded-full bg-white/70 px-2 py-0.5 text-xs font-medium">{review.reasonLabel}</span>
            </p>
          ) : null}
          {review.notes && review.notes !== review.reasonLabel ? (
            <p className="mt-1.5 whitespace-pre-line break-words text-sm">{review.notes}</p>
          ) : null}
          <p className="mt-1.5 text-xs opacity-80">{returnedNextStep(status)}</p>
        </div>
      </div>
    </section>
  );
}
