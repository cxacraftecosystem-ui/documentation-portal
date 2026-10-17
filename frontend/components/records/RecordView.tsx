"use client";

import Link from "next/link";
import { useEffect, useState, type ReactNode } from "react";
import {
  Boxes,
  ClipboardList,
  GitBranch,
  Hammer,
  ImagePlus,
  Layers,
  type LucideIcon,
  MapPin,
  Pencil,
  Users
} from "lucide-react";

import { ArtisanQuestionnairePanel } from "@/components/ArtisanQuestionnairePanel";
import { useAuth } from "@/components/AuthProvider";
import { CollabPanel } from "@/components/CollabPanel";
import { ExistingMedia } from "@/components/media/ExistingMedia";
import { PageHeader } from "@/components/PageHeader";
import { RecordMediaList } from "@/components/records/RecordMediaList";
import {
  RECORD_VIEW_ENDPOINT,
  RECORD_VIEW_NOUN,
  recordFieldRows,
  recordTitle
} from "@/components/records/recordFields";
import { StatusBadge } from "@/components/StatusBadge";
import { apiFetch } from "@/lib/api";
import { formatDateTime } from "@/lib/format";
import { canCreateRecords, canReview } from "@/lib/permissions";
import { TRANSCRIPT_SOURCE } from "@/lib/transcriptAnswer";
import { ButtonRow } from "@/components/ui/ButtonRow";
import { canUpdateRecord, recordUpdateHref, type RecordViewType } from "@/lib/recordView";
import type { Artisan, MediaFile, QuestionnaireResponse, RecordStatus, User } from "@/lib/types";

const ICON: Record<RecordViewType, LucideIcon> = {
  artisan: Users,
  product: Boxes,
  tool: Hammer,
  craft: Layers,
  workshop: MapPin,
  process: GitBranch,
  questionnaire: ClipboardList
};

type LoadedRecord = Record<string, unknown> & {
  id: string;
  status?: RecordStatus | string;
  createdAt?: string;
  createdBy?: User | null;
};

/**
 * A record, READ-ONLY — what a click on its row or card opens (see `lib/recordView.ts`).
 *
 * Every field the type carries is printed, missing ones as "Not recorded"; attached files are
 * previewed and open in the lightbox, which carries the Download button. The one way to change
 * anything is the "Update" button in the header, which opens the edit form that already existed for
 * this type and is drawn only for an account the server would let save it (`canUpdateRecord`).
 * Comments sit at the foot, as on the handset's View Data detail. The back arrow returns to the list,
 * which puts its search, filter, page and scroll back as they were (`useListReturn`).
 */
export function RecordView({ type, id }: { type: RecordViewType; id: string }) {
  const { user } = useAuth();
  const [record, setRecord] = useState<LoadedRecord | null>(null);
  const [error, setError] = useState<string | null>(null);
  const noun = RECORD_VIEW_NOUN[type];

  useEffect(() => {
    let cancelled = false;
    setRecord(null);
    setError(null);
    apiFetch<LoadedRecord>(`${RECORD_VIEW_ENDPOINT[type]}/${encodeURIComponent(id)}`)
      .then((loaded) => {
        if (!cancelled) setRecord(loaded);
      })
      .catch((err) => {
        if (!cancelled) setError(err instanceof Error ? err.message : `Couldn't load this ${noun}.`);
      });
    return () => {
      cancelled = true;
    };
  }, [type, id, noun]);

  const Icon = ICON[type];
  const updateHref = recordUpdateHref(type, id);
  const title = record ? recordTitle(type, record) : noun.charAt(0).toUpperCase() + noun.slice(1);

  return (
    <>
      <PageHeader
        title={title}
        description={`Read-only view of this ${noun}.`}
        icon={<Icon className="h-5 w-5" aria-hidden />}
        actions={
          record ? (
            <>
              {/* A9: the media page opens on this record, so its files need no second pick. */}
              {canCreateRecords(user) ? (
                <Link
                  className="field-button-secondary"
                  href={`/media?linkedType=${encodeURIComponent(type)}&linkedId=${encodeURIComponent(record.id)}`}
                >
                  <ImagePlus className="h-4 w-4" aria-hidden />
                  Add media
                </Link>
              ) : null}
              {updateHref && canUpdateRecord(user, type) ? (
                <Link className="field-button" href={updateHref}>
                  <Pencil className="h-4 w-4" aria-hidden />
                  Update
                </Link>
              ) : null}
            </>
          ) : null
        }
      />
      {error ? <div className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div> : null}
      {!record ? (
        error ? null : <div className="panel p-4 text-sm text-ink-muted">Loading this {noun}…</div>
      ) : (
        <div className="grid gap-5">
          <section className="panel p-4" aria-label={`${title} details`}>
            <div className="mb-4 flex flex-wrap items-center gap-2 text-xs text-ink-muted">
              {record.status ? <StatusBadge status={record.status} /> : null}
              <span>
                Recorded by {record.createdBy?.name ?? "Unknown"}
                {record.createdAt ? ` · ${formatDateTime(record.createdAt)}` : ""}
              </span>
            </div>
            <dl className="grid gap-x-6 gap-y-3 sm:grid-cols-2">
              {recordFieldRows(type, record).map((row) => (
                <div key={row.label} className="min-w-0">
                  <dt className="field-label">{row.label}</dt>
                  <dd className={`mt-0.5 whitespace-pre-line break-words text-sm ${row.value === "Not recorded" ? "text-ink-300" : "text-ink-900"}`}>
                    {row.value}
                  </dd>
                </div>
              ))}
            </dl>
          </section>
          <TypeExtras type={type} record={record} user={user} onRecordChange={setRecord} />
          <section className="panel p-4">
            <CollabPanel recordType={type} recordId={record.id} />
          </section>
        </div>
      )}
    </>
  );
}

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="panel grid gap-3 p-4">
      <h2 className="font-display text-lg font-bold text-ink-900">{title}</h2>
      {children}
    </section>
  );
}

/** What one type shows below its fields: its files, and the parts that are lists of their own. */
function TypeExtras({
  type,
  record,
  user,
  onRecordChange
}: {
  type: RecordViewType;
  record: LoadedRecord;
  user: User | null | undefined;
  onRecordChange: (record: LoadedRecord) => void;
}) {
  switch (type) {
    case "artisan":
      return (
        <>
          <ArtisanNextSteps artisan={record as unknown as Artisan} user={user} />
          <ExistingMedia linkedRecordType="artisan" linkedRecordId={record.id} title="Attached media" readOnly />
          <ArtisanQuestionnairePanel artisanId={record.id} />
        </>
      );
    case "product":
    case "tool":
    case "craft":
    case "workshop":
      return <ExistingMedia linkedRecordType={type} linkedRecordId={record.id} title="Attached media" readOnly />;
    case "process": {
      const steps = (record.steps as Array<{ id: string; name: string; stepType: string; sortOrder?: number; notes?: string | null; media?: MediaFile[] }> | undefined) ?? [];
      return (
        <>
          <Section title="Pre-process media">
            <RecordMediaList items={(record.media as MediaFile[] | undefined) ?? []} />
          </Section>
          <Section title={`Steps (${steps.length})`}>
            {steps.length === 0 ? <p className="text-sm text-ink-muted">No steps recorded.</p> : null}
            <ol className="grid gap-4">
              {[...steps]
                .sort((a, b) => (a.sortOrder ?? 0) - (b.sortOrder ?? 0))
                .map((step, index) => (
                  <li key={step.id} className="grid gap-2 rounded-md border border-line-200 p-3">
                    <div className="flex flex-wrap items-center gap-2 text-sm font-semibold text-ink-900">
                      <span>
                        Step {index + 1} · {step.stepType === "SEQUENTIAL" ? "Sequential" : "Group of activities"}
                        {step.name?.trim() ? ` — ${step.name}` : ""}
                      </span>
                      {/* C22: a step saved before it was named is flagged until somebody names it. */}
                      {step.name?.trim() ? null : (
                        <span className="rounded-full border border-amber-500 bg-amber-100 px-2 py-0.5 text-xs font-medium text-amber-800">
                          Unnamed step
                        </span>
                      )}
                    </div>
                    {step.notes?.trim() ? <p className="whitespace-pre-line text-sm text-ink-700">{step.notes}</p> : null}
                    <RecordMediaList items={step.media ?? []} empty="No media for this step." />
                  </li>
                ))}
            </ol>
          </Section>
        </>
      );
    }
    case "questionnaire":
      return (
        <>
          <InterviewTranscripts record={record} user={user} onRecordChange={onRecordChange} />
          <InterviewAnswers responses={(record.responses as QuestionnaireResponse[] | undefined) ?? []} />
          <Section title="Recordings & media">
            <RecordMediaList items={(record.media as MediaFile[] | undefined) ?? []} empty="No media attached." />
          </Section>
        </>
      );
  }
}

/**
 * A7 — "Use transcripts as answers" for a whole sitting, one tap (a reviewer's shortcut). The server
 * fills only EMPTY answers from each question's transcribed clip and marks them "From transcript";
 * a typed answer is never touched. Offered while the sitting has a transcribed clip at all.
 */
function InterviewTranscripts({
  record,
  user,
  onRecordChange
}: {
  record: LoadedRecord;
  user: User | null | undefined;
  onRecordChange: (record: LoadedRecord) => void;
}) {
  const [busy, setBusy] = useState(false);
  const [note, setNote] = useState<string | null>(null);
  const media = (record.media as MediaFile[] | undefined) ?? [];
  const transcribed = media.filter((item) => (item.caption ?? "").trim().toLowerCase().startsWith("question audio:") && item.transcriptText?.trim());
  if (!transcribed.length || !(canReview(user) || canCreateRecords(user))) return null;
  async function apply() {
    setBusy(true);
    setNote(null);
    try {
      const result = await apiFetch<{ applied: number; skipped: number; interview: LoadedRecord }>(
        `/questionnaire/interviews/${encodeURIComponent(record.id)}/apply-transcripts`,
        { method: "POST" }
      );
      onRecordChange(result.interview);
      setNote(result.applied ? `Filled ${result.applied} answer${result.applied === 1 ? "" : "s"}.` : "Nothing to fill — every one is answered.");
    } catch (err) {
      setNote(err instanceof Error ? err.message : "Couldn't fill the answers.");
    } finally {
      setBusy(false);
    }
  }
  return (
    <section className="panel grid gap-2 p-4">
      <p className="text-sm text-ink-700">
        {transcribed.length} transcribed clip{transcribed.length === 1 ? "" : "s"}.
      </p>
      <ButtonRow align="start">
        <button type="button" className="field-button-secondary" onClick={apply} disabled={busy}>
          {busy ? "Filling…" : "Use transcripts as answers"}
        </button>
      </ButtonRow>
      {note ? <p className="text-xs text-ink-500" role="status">{note}</p> : null}
    </section>
  );
}

/** An interview's answers, in questionnaire order, each under its section code and prompt. */
function InterviewAnswers({ responses }: { responses: QuestionnaireResponse[] }) {
  const answered = responses
    .filter((response) => response.answerText?.trim())
    .sort((a, b) => {
      const sa = a.question?.sectionCode ?? "";
      const sb = b.question?.sectionCode ?? "";
      return sa === sb ? (a.question?.sortOrder ?? 0) - (b.question?.sortOrder ?? 0) : sa.localeCompare(sb);
    });
  return (
    <Section title={`Answers (${answered.length})`}>
      {answered.length === 0 ? <p className="text-sm text-ink-muted">No answers recorded yet.</p> : null}
      <dl className="grid gap-3">
        {answered.map((response) => (
          <div key={response.id} className="rounded-md border border-line-200 p-3">
            <dt className="text-xs font-semibold text-ink-500">
              {response.question ? `${response.question.sectionCode}${response.question.sortOrder} · ${response.question.prompt}` : "Answer"}
            </dt>
            <dd className="mt-1 whitespace-pre-line text-sm text-ink-900">{response.answerText}</dd>
            {response.answerSource === TRANSCRIPT_SOURCE ? (
              <dd className="mt-1 w-fit rounded-full border border-purple-300 bg-purple-50 px-2 py-0.5 text-xs font-medium text-purple-800">
                From transcript
              </dd>
            ) : null}
            {response.answeredBy?.name ? <dd className="mt-1 text-xs text-ink-muted">Answered by {response.answeredBy.name}</dd> : null}
          </div>
        ))}
      </dl>
    </Section>
  );
}

/**
 * "Make a tool entry" / "Make a product entry" / "Start questionnaire", with this artisan already
 * on the form. These used to appear on the artisans list when a row was clicked; a row click now
 * opens this view, so they live here, under the person they are about.
 *
 * The artisan's own workshop deliberately does not travel — see `artisanEntryHref`.
 */
function ArtisanNextSteps({ artisan, user }: { artisan: Artisan; user: User | null | undefined }) {
  const creator = canCreateRecords(user);
  const items = [
    creator ? { href: artisanEntryHref("/tools/new", artisan), title: "Make a tool entry", body: "Document a tool used by this artisan.", icon: Hammer } : null,
    creator ? { href: artisanEntryHref("/products/new", artisan), title: "Make a product entry", body: "Record an object, product or sample.", icon: Boxes } : null,
    { href: artisanEntryHref("/questionnaire", artisan), title: "Start questionnaire", body: "Open the interview with RESP prefilled.", icon: ClipboardList }
  ].filter((item): item is NonNullable<typeof item> => item !== null);
  return (
    <section className="grid gap-3 md:grid-cols-3" aria-label="Next steps for this artisan">
      {items.map((item) => (
        <Link key={item.href} href={item.href} className="panel group flex min-h-32 items-start gap-3 p-4 transition hover:-translate-y-0.5 hover:shadow-panel">
          <span className="grid h-11 w-11 shrink-0 place-items-center rounded-lg bg-field-200 text-field-700 dark:text-purple-300">
            <item.icon className="h-5 w-5" aria-hidden />
          </span>
          <span>
            <span className="block font-display text-xl font-bold text-ink">{item.title}</span>
            <span className="mt-1 block text-sm leading-6 text-ink-muted">{item.body}</span>
          </span>
        </Link>
      ))}
    </section>
  );
}

/**
 * Open a capture form with this artisan already on it.
 *
 * ── THE ARTISAN'S OWN WORKSHOP DELIBERATELY DOES NOT TRAVEL ──────────────────────────────────────
 *
 * `artisan.workshopId` is where the PERSON'S RECORD was filed, which is routinely a workshop months
 * ago and a district away — not where the researcher clicking this link is standing. Opening the
 * interview form on it would file the sitting at the wrong workshop by default, and it would
 * override `useWorkshopSelection`'s probe, whose job is to land on a workshop this account may
 * actually SUBMIT to (`components/forms/WorkshopSelect.tsx`). A seeded artisan the default workshop's
 * roster does not hold is named in a sentence under the picker instead — rule 6 in
 * `components/questionnaires/interviewArtisans.ts`. (Moved here from the artisans list with the
 * cards it serves; the full argument was written there first.)
 */
function artisanEntryHref(path: string, artisan: Artisan) {
  const params = new URLSearchParams({
    artisanId: artisan.id,
    artisanName: artisan.name,
    place: artisan.place
  });
  if (artisan.craftId) params.set("craftId", artisan.craftId);
  if (artisan.craft?.name) params.set("craftName", artisan.craft.name);
  return `${path}?${params.toString()}`;
}
