"use client";

import { use, useEffect, useMemo, useState } from "react";
import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { AlertTriangle, FileAudio, GitCompareArrows, Layers, User, Users } from "lucide-react";

import { AudioPlayer } from "@/components/ui/AudioPlayer";
import { DownloadCsvButton } from "@/components/DownloadCsvButton";
import { EmptyState } from "@/components/EmptyState";
import { Markdown } from "@/components/Markdown";
import { PageHeader } from "@/components/PageHeader";
import { StatusBadge } from "@/components/StatusBadge";
import {
  useWorkshopScope,
  WorkshopScopeSelect,
  workshopScopeFromSearchParams
} from "@/components/WorkshopScopeSelect";
import { apiFetch, buildQuery } from "@/lib/api";
import { formatDate } from "@/lib/format";
import {
  DATE_BASIS_NOTE,
  GROUP,
  type ConsolidatedAnswer,
  type ConsolidatedQuestionnaire,
  type ConsolidatedSection,
  type InterviewSource
} from "@/lib/consolidatedQuestionnaire";
import type { RecordStatus } from "@/lib/types";

/**
 * One artisan's questionnaire, read as a single document.
 *
 * The repository stores one interview per exact SET of artisans, so an artisan who was interviewed
 * alone once and in three group sittings has their account split across four entries. Most artisans
 * here are in that position. This page gathers those into one ordered read WITHOUT touching how they
 * are stored — and its whole job is to keep the seams visible while doing so:
 *
 *   - every answer says which sitting it came from and when;
 *   - a question answered differently twice shows both, newest first, marked as a divergence;
 *   - an answer from a group sitting is labelled as unattributable, because the data model records
 *     no link between a response and one member of the group.
 */
export default function ConsolidatedQuestionnairePage({
  params
}: {
  params: Promise<{ artisanId: string }>;
}) {
  const { artisanId } = use(params);
  const searchParams = useSearchParams();
  const [data, setData] = useState<ConsolidatedQuestionnaire | null>(null);
  const [error, setError] = useState<string | null>(null);

  /**
   * WHICH WORKSHOPS THIS DOCUMENT COVERS.
   *
   * `defaultToMostRecent` is FALSE here, and it is the one screen where that is right. This is a
   * document — the artifact a researcher cites — and its default meaning has always been "everything
   * this artisan has ever said". Silently narrowing it to one workshop would change what a citation
   * means without the reader asking for it. The index page and the URL can still open it scoped, and
   * the control makes narrowing one click.
   *
   * The whole document is recomputed server-side over the narrowed interviews, so the summary counts
   * and the divergence flags describe the scope rather than being filtered after the fact: a
   * disagreement between two workshops is not a disagreement within one of them.
   */
  const scope = useWorkshopScope({
    defaultToMostRecent: false,
    initialWorkshopIds: workshopScopeFromSearchParams(new URLSearchParams(searchParams.toString()))
  });

  useEffect(() => {
    if (scope.settling) return;
    let active = true;
    setData(null);
    setError(null);
    apiFetch<ConsolidatedQuestionnaire>(
      `/questionnaire/artisans/${artisanId}/consolidated${buildQuery({ workshopIds: scope.queryValue })}`
    )
      .then((result) => {
        if (active) setData(result);
      })
      .catch((err: unknown) => {
        if (active) setError(err instanceof Error ? err.message : "Unable to load this questionnaire.");
      });
    return () => {
      active = false;
    };
  }, [artisanId, scope.settling, scope.queryValue]);

  const jumpTargets = useMemo(
    () => (data?.sections ?? []).map((section) => ({ id: section.id, code: section.code, title: section.title })),
    [data]
  );

  /**
   * The scope control is rendered in EVERY branch, including the two failures below.
   *
   * That is not tidiness. Narrowing to a workshop this artisan was not at is a perfectly ordinary
   * thing to do by accident, and it produces an empty or failed document — so the control that caused
   * it has to still be on screen to undo it. Hiding it behind a successful load would leave the reader
   * on a dead page whose only exit is the back button.
   */
  const scopeControl = (
    <div className="mb-5 max-w-xl">
      <WorkshopScopeSelect scope={scope} label="Workshops in this document" />
    </div>
  );

  if (error) {
    return (
      <div className="pb-16">
        <PageHeader title="Consolidated questionnaire" />
        {scopeControl}
        <EmptyState title="This questionnaire could not be loaded" body={error} />
      </div>
    );
  }

  if (!data) {
    return (
      <div className="pb-16">
        <PageHeader title="Consolidated questionnaire" />
        {scopeControl}
        <div className="panel p-6 text-sm text-ink-500">Gathering answers from every interview…</div>
      </div>
    );
  }

  const { artisan, summary } = data;
  const subtitle = [artisan.craftName, artisan.place].filter(Boolean).join(" · ");
  const nothingRecorded = data.sections.length === 0 && data.unfiled.length === 0;

  return (
    <div className="pb-16">
      <PageHeader
        title={artisan.name}
        // The sentence has to follow the scope. "All N interviews they took part in" is a claim about
        // the whole corpus, and repeating it under a workshop filter would tell the reader they were
        // looking at everything while they were looking at one workshop.
        description={
          [
            subtitle,
            scope.workshopIds.length
              ? `Questionnaire answers from the ${summary.interviewCount} interview${
                  summary.interviewCount === 1 ? "" : "s"
                } this artisan took part in within the chosen workshops.`
              : `Every questionnaire answer recorded for this artisan, gathered from all ${summary.interviewCount} interview${
                  summary.interviewCount === 1 ? "" : "s"
                } they took part in.`
          ]
            .filter(Boolean)
            .join(" — ")
        }
        icon={<Layers className="h-5 w-5" aria-hidden />}
        actions={
          <DownloadCsvButton
            // The CSV carries the same scope, so the file a researcher cites is the document they read.
            // The endpoint is download-gated (see its docstring) while this page is not, so a reader
            // without download access still gets the document and simply cannot take the file.
            path={`/questionnaire/artisans/${artisan.id}/consolidated.csv${buildQuery({
              workshopIds: scope.queryValue
            })}`}
            filename={`questionnaire-${artisan.name.replace(/[^A-Za-z0-9]+/g, "-")}.csv`}
          />
        }
      />

      {scopeControl}

      <SummaryStrip summary={summary} />

      <div className="mt-6 grid gap-6 lg:grid-cols-[minmax(0,1fr)_20rem] lg:items-start">
        <div className="min-w-0 space-y-6">
          {nothingRecorded ? (
            <EmptyState
              title={summary.interviewCount === 0 ? "Nothing in this scope" : "Nothing recorded yet"}
              body={
                summary.interviewCount === 0
                  ? scope.workshopIds.length
                    ? `${artisan.name} took part in no interviews at the chosen workshops. Widen the workshop scope, or choose All records.`
                    : `${artisan.name} has not taken part in any interview yet.`
                  : `${artisan.name} appears in ${summary.interviewCount} interview${
                      summary.interviewCount === 1 ? "" : "s"
                    }, but no answers or recordings have been filed against them.`
              }
            />
          ) : null}

          {data.sections.map((section) => (
            <SectionBlock key={section.id} section={section} />
          ))}

          {data.unfiled.length > 0 ? <UnfiledBlock rows={data.unfiled} /> : null}
        </div>

        <aside className="space-y-4 lg:sticky lg:top-24">
          <SourcesPanel interviews={data.interviews} />
          {jumpTargets.length > 1 ? <JumpPanel targets={jumpTargets} /> : null}
        </aside>
      </div>
    </div>
  );
}

/* ------------------------------------------------------------------------------------------------
 * Summary
 * ---------------------------------------------------------------------------------------------- */

function SummaryStrip({ summary }: { summary: ConsolidatedQuestionnaire["summary"] }) {
  const tiles: Array<{ label: string; value: number; hint?: string; tone?: "caution" | "finding" }> = [
    { label: "Interviews", value: summary.interviewCount },
    {
      label: "Group sittings",
      value: summary.groupSittingCount,
      hint: "Answers from these cannot be attributed to one person",
      tone: summary.groupSittingCount > 0 ? "caution" : undefined
    },
    { label: "Questions answered", value: summary.answeredQuestionCount },
    { label: "Recordings", value: summary.recordedAnswerCount },
    {
      label: "Divergent answers",
      value: summary.conflictCount,
      hint: "Questions answered differently in different interviews",
      tone: summary.conflictCount > 0 ? "finding" : undefined
    }
  ];
  return (
    <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-5">
      {tiles.map((tile) => (
        <div
          key={tile.label}
          className={`rounded-lg border px-4 py-3 ${
            tile.tone === "caution"
              ? "border-amber-500/30 bg-amber-100 dark:border-amber-500/25 dark:bg-amber-500/10"
              : tile.tone === "finding"
                ? "border-purple-300 bg-purple-50 dark:border-purple-800 dark:bg-purple-950/40"
                : "border-line-200 bg-card"
          }`}
        >
          <div
            className={`font-display text-2xl font-bold ${
              tile.tone === "caution"
                ? "text-amber-800 dark:text-amber-500"
                : tile.tone === "finding"
                  ? "text-purple-700 dark:text-purple-300"
                  : "text-ink-900"
            }`}
          >
            {tile.value}
          </div>
          <div className="mt-0.5 text-xs font-medium uppercase tracking-wide text-ink-500">{tile.label}</div>
          {tile.hint ? <p className="mt-1 text-[0.7rem] leading-4 text-ink-500">{tile.hint}</p> : null}
        </div>
      ))}
    </div>
  );
}

/* ------------------------------------------------------------------------------------------------
 * Provenance chips — the part of this page that must never be quietly dropped
 * ---------------------------------------------------------------------------------------------- */

/**
 * Says whether the answer can be pinned to this artisan. A group answer gets the amber treatment
 * because the honest reading is a caution: the sentence was said in a room of five and the record
 * does not say by whom.
 */
function AttributionChip({ row, compact = false }: { row: ConsolidatedAnswer | InterviewSource; compact?: boolean }) {
  if (row.attribution === GROUP) {
    return (
      <span className="inline-flex items-center gap-1.5 rounded-md border border-amber-500/30 bg-amber-100 px-2 py-0.5 text-xs font-medium text-amber-800 dark:border-amber-500/25 dark:bg-amber-500/10 dark:text-amber-500">
        <Users className="h-3.5 w-3.5 shrink-0" aria-hidden />
        {compact ? `Group of ${row.artisanCount}` : `Group of ${row.artisanCount} — speaker not recorded`}
      </span>
    );
  }
  return (
    <span className="inline-flex items-center gap-1.5 rounded-md border border-line-200 bg-surface-50 px-2 py-0.5 text-xs font-medium text-ink-500">
      <User className="h-3.5 w-3.5 shrink-0" aria-hidden />
      Spoke alone
    </span>
  );
}

/** Interview, date and who else was present — repeated on every answer, deliberately. */
function Provenance({ row }: { row: ConsolidatedAnswer }) {
  return (
    <div className="space-y-1 text-xs text-ink-500">
      <div className="flex flex-wrap items-center gap-x-2 gap-y-1">
        <AttributionChip row={row} compact />
        <span aria-hidden>·</span>
        <Link
          href={`/questionnaire?interviewId=${row.interviewId}`}
          className="font-medium text-purple-700 underline decoration-purple-300 underline-offset-2 hover:text-purple-800"
        >
          {row.interviewTitle}
        </Link>
        <span aria-hidden>·</span>
        <time dateTime={row.interviewDate ?? undefined} title={DATE_BASIS_NOTE[row.dateBasis]}>
          {formatDate(row.interviewDate)}
          {row.dateBasis !== "interviewDate" ? <span className="text-ink-300"> (recorded)</span> : null}
        </time>
        {row.recordedByName ? (
          <>
            <span aria-hidden>·</span>
            <span>noted by {row.recordedByName}</span>
          </>
        ) : null}
      </div>
      {/* Its own line rather than another separator-joined item: these lists run to four names and
          wrapped mid-list, leaving a dangling "·" at the end of the row above. */}
      {row.coParticipants.length > 0 ? <p className="leading-5">with {row.coParticipants.join(", ")}</p> : null}
    </div>
  );
}

/* ------------------------------------------------------------------------------------------------
 * Answers
 * ---------------------------------------------------------------------------------------------- */

function AnswerCard({ row, ordinal }: { row: ConsolidatedAnswer; ordinal?: number }) {
  const isGroup = row.attribution === GROUP;
  return (
    <article
      className={`rounded-md border-l-2 bg-card py-3 pl-4 pr-3 ${isGroup ? "border-l-amber-500/50 dark:border-l-amber-500/40" : "border-l-purple-300"}`}
    >
      {ordinal ? (
        <div className="mb-1.5 text-[0.7rem] font-semibold uppercase tracking-[0.12em] text-ink-300">
          Account {ordinal}
        </div>
      ) : null}

      {row.kind === "TYPED" ? (
        <p className="whitespace-pre-wrap text-sm leading-6 text-ink-900">{row.answerText || "—"}</p>
      ) : (
        <RecordingBody row={row} />
      )}

      {row.notes ? (
        <p className="mt-2 rounded-md bg-surface-50 px-3 py-2 text-xs leading-5 text-ink-700">
          <span className="font-semibold text-ink-500">Note: </span>
          {row.notes}
        </p>
      ) : null}

      <div className="mt-2.5">
        <Provenance row={row} />
      </div>
    </article>
  );
}

function RecordingBody({ row }: { row: ConsolidatedAnswer }) {
  const failed = row.transcriptStatus && row.transcriptStatus !== "COMPLETED";
  /**
   * A recording with no URL. This is a ROUTINE state, not a fault, and the two reasons for it read very
   * differently to the person looking at the page:
   *
   *   * the clip is here but this account may not take the file. Reading the repository is open to
   *     everyone; a URL is the file itself, so it travels only to callers who may download that
   *     uploader's data. The transcript below IS still the record, which is the point.
   *   * the upload never finished, in which case there is no file to have.
   *
   * The page cannot tell them apart from the payload — deliberately, since saying "you are not allowed
   * this" per clip would be noise on a document — so it says the one true thing that covers both, rather
   * than leaving a bare filename with nothing under it and no explanation.
   */
  const withheld = !row.url;
  return (
    <div className="space-y-2">
      <div className="flex flex-wrap items-center gap-2 text-xs text-ink-500">
        <FileAudio className="h-4 w-4 shrink-0 text-purple-700" aria-hidden />
        <span className="break-all font-medium text-ink-700">{row.filename ?? "Recording"}</span>
      </div>
      {row.url ? <AudioPlayer src={row.url} /> : null}
      {row.transcriptText ? (
        <div className="rounded-md border border-line-200 bg-surface-50 px-3 py-2">
          <Markdown text={row.transcriptText} />
        </div>
      ) : (
        <p className="text-xs italic text-ink-500">
          {failed
            ? "Transcription did not complete for this clip."
            : "No transcript yet for this clip."}
          {withheld ? " The audio is not available to play here." : " The audio above is the record."}
        </p>
      )}
      {withheld && row.transcriptText ? (
        <p className="text-[0.7rem] italic leading-4 text-ink-500">
          The transcript is the record here — playing or downloading the audio itself needs download
          access to the researcher who recorded it.
        </p>
      ) : null}
    </div>
  );
}

/**
 * A question whose answers disagree. Both are kept and the divergence is named, because an artisan
 * whose account changed between two sittings is a finding a researcher needs to see, not a
 * duplicate for the software to resolve.
 */
function ConflictBanner({ count }: { count: number }) {
  return (
    <div className="mb-2 flex items-start gap-2 rounded-md border border-purple-300 bg-purple-50 px-3 py-2 text-xs leading-5 text-purple-700 dark:border-purple-800 dark:bg-purple-950/40 dark:text-purple-300">
      <GitCompareArrows className="mt-0.5 h-4 w-4 shrink-0" aria-hidden />
      <span>
        <span className="font-semibold">Answered differently in {count} interviews.</span> Both accounts are shown,
        most recent first. Neither has been chosen for you.
      </span>
    </div>
  );
}

function SectionBlock({ section }: { section: ConsolidatedSection }) {
  return (
    <section id={section.id} className="panel scroll-mt-24 p-5 sm:p-6">
      <header className="mb-4 border-b border-line-200 pb-3">
        <div className="flex flex-wrap items-center gap-2">
          <span className="rounded-md bg-purple-950 px-2 py-0.5 font-mono text-xs font-semibold text-white dark:bg-purple-800">
            {section.code}
          </span>
          <h2 className="display-title text-lg sm:text-xl">{section.title}</h2>
        </div>
      </header>

      <div className="space-y-5">
        {section.questions.map((question) => (
          <div key={question.id}>
            <h3 className="mb-2 text-sm font-semibold leading-6 text-ink-900">{question.prompt}</h3>
            {question.conflict ? <ConflictBanner count={question.answers.length} /> : null}
            <div className="space-y-2.5">
              {question.answers.map((answer, index) => (
                <AnswerCard
                  key={answer.sourceId}
                  row={answer}
                  ordinal={question.conflict ? index + 1 : undefined}
                />
              ))}
            </div>
          </div>
        ))}

        {section.recordings.length > 0 ? (
          <div>
            <h3 className="mb-1 text-sm font-semibold leading-6 text-ink-900">Recordings filed to this section</h3>
            {/* Said plainly: the clip names its section and stops there. Pinning it to a question
                would be a guess, and this page does not guess. */}
            <p className="mb-2 text-xs leading-5 text-ink-500">
              These clips identify the section but not a specific question, so they sit under the section heading.
            </p>
            <div className="space-y-2.5">
              {section.recordings.map((row) => (
                <AnswerCard key={row.sourceId} row={row} />
              ))}
            </div>
          </div>
        ) : null}
      </div>
    </section>
  );
}

function UnfiledBlock({ rows }: { rows: ConsolidatedAnswer[] }) {
  return (
    <section className="panel p-5 sm:p-6">
      <header className="mb-3 flex items-start gap-2 border-b border-line-200 pb-3">
        <AlertTriangle className="mt-0.5 h-5 w-5 shrink-0 text-amber-800 dark:text-amber-500" aria-hidden />
        <div>
          <h2 className="display-title text-lg">Recordings with no section ({rows.length})</h2>
          <p className="mt-1 text-xs leading-5 text-ink-500">
            These were recorded against this artisan&apos;s interviews but carry no section in their filename or
            metadata, so they could not be placed in the document above. They are listed here rather than dropped.
          </p>
        </div>
      </header>
      <div className="space-y-2.5">
        {rows.map((row) => (
          <AnswerCard key={row.sourceId} row={row} />
        ))}
      </div>
    </section>
  );
}

/* ------------------------------------------------------------------------------------------------
 * Sidebar
 * ---------------------------------------------------------------------------------------------- */

function SourcesPanel({ interviews }: { interviews: InterviewSource[] }) {
  return (
    <div className="panel p-4">
      <h2 className="eyebrow mb-3">Sources ({interviews.length})</h2>
      <ol className="space-y-3">
        {interviews.map((interview) => (
          <li key={interview.id} className="border-l-2 border-line-200 pl-3">
            <Link
              href={`/questionnaire?interviewId=${interview.id}`}
              className="block text-sm font-medium leading-5 text-purple-700 underline decoration-purple-300 underline-offset-2 hover:text-purple-800"
            >
              {interview.title}
            </Link>
            <div className="mt-1 flex flex-wrap items-center gap-1.5 text-xs text-ink-500">
              <time dateTime={interview.date ?? undefined} title={DATE_BASIS_NOTE[interview.dateBasis]}>
                {formatDate(interview.date)}
              </time>
              <StatusBadge status={interview.status as RecordStatus} />
            </div>
            <div className="mt-1.5">
              <AttributionChip row={interview} compact />
            </div>
            {interview.coParticipants.length > 0 ? (
              <p className="mt-1 text-xs leading-5 text-ink-500">with {interview.coParticipants.join(", ")}</p>
            ) : null}
          </li>
        ))}
      </ol>
    </div>
  );
}

function JumpPanel({ targets }: { targets: Array<{ id: string; code: string; title: string }> }) {
  return (
    <nav className="panel p-4" aria-label="Jump to section">
      <h2 className="eyebrow mb-3">Sections</h2>
      <ul className="space-y-1">
        {targets.map((target) => (
          <li key={target.id}>
            <a
              href={`#${target.id}`}
              className="flex items-baseline gap-2 rounded-md px-2 py-1 text-sm text-ink-700 transition hover:bg-purple-50 hover:text-purple-700 focus-visible:bg-purple-50"
            >
              <span className="font-mono text-xs font-semibold text-ink-500">{target.code}</span>
              <span className="line-clamp-1">{target.title}</span>
            </a>
          </li>
        ))}
      </ul>
    </nav>
  );
}
