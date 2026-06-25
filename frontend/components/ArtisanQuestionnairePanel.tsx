"use client";

import { useEffect, useState } from "react";
import { ClipboardList } from "lucide-react";

import { apiFetch } from "@/lib/api";
import { formatDate } from "@/lib/format";
import type { ArtisanAnswer, ArtisanQuestionnaire } from "@/lib/types";

/**
 * Shows the questionnaire questions this artisan was actually asked, with their answers, grouped by
 * section. Only answered questions are returned by the backend so the page is never crowded with
 * blank prompts.
 */
export function ArtisanQuestionnairePanel({ artisanId }: { artisanId: string }) {
  const [data, setData] = useState<ArtisanQuestionnaire | null>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    let active = true;
    apiFetch<ArtisanQuestionnaire>(`/artisans/${artisanId}/questionnaire`)
      .then((result) => {
        if (active) setData(result);
      })
      .catch(() => {
        if (active) setFailed(true);
      });
    return () => {
      active = false;
    };
  }, [artisanId]);

  if (failed) return null;
  if (!data) return <div className="panel p-4 text-sm text-ink-muted">Loading questionnaire answers...</div>;
  const recordings = (data.interviews ?? []).filter(
    (interview) => (interview.media?.length ?? 0) > 0 || Boolean(interview.notes?.trim())
  );
  if (data.answered.length === 0 && recordings.length === 0) return null;

  const groups = new Map<string, ArtisanAnswer[]>();
  for (const answer of data.answered) {
    const key = `${answer.sectionCode ?? ""}|${answer.sectionTitle ?? ""}`;
    const bucket = groups.get(key);
    if (bucket) bucket.push(answer);
    else groups.set(key, [answer]);
  }

  return (
    <section className="panel p-4">
      <div className="mb-2 flex items-center gap-2">
        <ClipboardList className="h-5 w-5 text-field-700" aria-hidden />
        <h3 className="font-serif text-lg text-ink">Questionnaire answers ({data.total})</h3>
      </div>
      <p className="mb-3 text-sm text-ink-muted">Only questions this artisan actually answered are shown.</p>
      {data.answered.length > 0 ? (
        <div className="grid gap-4">
          {Array.from(groups.entries()).map(([key, answers]) => {
            const [code, title] = key.split("|");
            return (
              <div key={key}>
                <h4 className="mb-2 text-xs font-semibold uppercase text-ink-soft">
                  {code}
                  {title ? ` · ${title}` : ""}
                </h4>
                <div className="grid gap-2">
                  {answers.map((answer) => (
                    <div key={answer.responseId} className="rounded-md bg-field-100 p-3">
                      <div className="text-sm font-medium text-ink">{answer.prompt}</div>
                      <div className="mt-1 whitespace-pre-wrap text-sm text-ink-muted">{answer.answerText}</div>
                      <div className="mt-2 text-xs text-ink-soft">
                        {[
                          answer.interviewTitle ? `Interview: ${answer.interviewTitle}` : null,
                          answer.answeredByName ? `by ${answer.answeredByName}` : null,
                          answer.interviewDate ? formatDate(answer.interviewDate) : null
                        ]
                          .filter(Boolean)
                          .join(" · ")}
                      </div>
                    </div>
                  ))}
                </div>
              </div>
            );
          })}
        </div>
      ) : null}
      {recordings.length > 0 ? (
        <div className="mt-4 border-t border-field-200 pt-4">
          <h4 className="mb-2 text-sm font-semibold text-ink">Recordings &amp; notes</h4>
          <p className="mb-3 text-sm text-ink-muted">
            From every interview this artisan is part of — including ones recorded with others.
          </p>
          <div className="grid gap-3">
            {recordings.map((interview) => (
              <div key={interview.interviewId} className="rounded-md bg-field-100 p-3">
                <div className="text-sm font-medium text-ink">{interview.title || "Interview"}</div>
                <div className="text-xs text-ink-soft">
                  {[
                    interview.interviewDate ? formatDate(interview.interviewDate) : null,
                    interview.coArtisans && interview.coArtisans.length > 0
                      ? `with ${interview.coArtisans.join(", ")}`
                      : null
                  ]
                    .filter(Boolean)
                    .join(" · ")}
                </div>
                {interview.notes?.trim() ? (
                  <div className="mt-1 whitespace-pre-wrap text-sm text-ink-muted">{interview.notes}</div>
                ) : null}
                <div className="mt-2 grid gap-2">
                  {(interview.media ?? []).map((media) => (
                    <div key={media.id} className="rounded border border-field-200 bg-paper p-2">
                      <div className="flex items-center justify-between gap-2">
                        <a
                          href={media.url ?? "#"}
                          target="_blank"
                          rel="noreferrer"
                          className="truncate text-xs font-medium text-field-700 hover:underline"
                        >
                          {media.originalFilename}
                        </a>
                        {media.transcriptText?.trim() ? (
                          <button
                            type="button"
                            className="shrink-0 text-xs text-field-700 hover:underline"
                            onClick={() => navigator.clipboard?.writeText(media.transcriptText ?? "")}
                          >
                            Copy transcript
                          </button>
                        ) : null}
                      </div>
                      {media.transcriptText?.trim() ? (
                        <div className="mt-1 whitespace-pre-wrap text-xs text-ink-muted">{media.transcriptText}</div>
                      ) : null}
                    </div>
                  ))}
                </div>
              </div>
            ))}
          </div>
        </div>
      ) : null}
    </section>
  );
}
