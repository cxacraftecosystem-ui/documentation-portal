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

import { ArtisanCardPanel } from "@/components/artisans/ArtisanCardPanel";
import { ArtisanCardSheetActions } from "@/components/artisans/ArtisanCardSheetActions";
import { ArtisanQuestionnairePanel } from "@/components/ArtisanQuestionnairePanel";
import { useAuth } from "@/components/AuthProvider";
import { CollabPanel } from "@/components/CollabPanel";
import { ArtisanDuplicateBanner } from "@/components/duplicates/ArtisanDuplicateBanner";
import { ExistingMedia } from "@/components/media/ExistingMedia";
import { PageHeader } from "@/components/PageHeader";
import { CompletenessMeter } from "@/components/records/CompletenessMeter";
import { RecordDangerZone } from "@/components/records/RecordDangerZone";
import { RECORD_LIST_HREF } from "@/components/records/dangerZoneRules";
import { RecordMediaList } from "@/components/records/RecordMediaList";
import { MediaReviewProvider } from "@/components/review/MediaReview";
import { RecordReviewBar } from "@/components/review/RecordReviewBar";
import { ReviewNoteBanner } from "@/components/review/ReviewNoteBanner";
import {
  RECORD_VIEW_ENDPOINT,
  RECORD_VIEW_NOUN,
  recordFieldRows,
  recordTitle
} from "@/components/records/recordFields";
import { StatusBadge } from "@/components/StatusBadge";
import { apiFetch } from "@/lib/api";
import { craftIndex, loadCraftCatalogue } from "@/lib/craftCatalogue";
import { formatDateTime } from "@/lib/format";
import { isCompletenessKind, type CompletenessBlock } from "@/lib/completenessCore";
import { canCreateRecords, canReview, isAdmin } from "@/lib/permissions";
import { TRANSCRIPT_SOURCE } from "@/lib/transcriptAnswer";
import { ButtonRow } from "@/components/ui/ButtonRow";
import { canUpdateRecord, recordUpdateHref, type RecordViewType } from "@/lib/recordView";
import { fetchRecordMediaReview, type RecordMediaReview } from "@/lib/review";
import type { Artisan, MediaFile, QuestionnaireResponse, RecordStatus, User } from "@/lib/types";
import { Badge } from "@/components/ui/Badge";

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

async function withCraftPath(record: LoadedRecord): Promise<LoadedRecord> {
  const subCategoryId = typeof record.subCategoryId === "string" ? record.subCategoryId : null;
  if (!subCategoryId || (record.subCategory && typeof record.subCategory === "object")) return record;
  const catalogue = await loadCraftCatalogue().catch(() => null);
  const index = catalogue ? craftIndex(catalogue) : null;
  const sub = index?.subCategoryById.get(subCategoryId);
  if (!index || !sub) return record;
  const category = index.categoryById.get(sub.categoryId)?.name;
  return { ...record, subCategory: { id: sub.id, name: sub.name }, category: record.category || category || null };
}

export function RecordView({ type, id }: { type: RecordViewType; id: string }) {
  const { user } = useAuth();
  const [record, setRecord] = useState<LoadedRecord | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [reloads, setReloads] = useState(0);
  const [decided, setDecided] = useState<string | null>(null);
  const [mediaReview, setMediaReview] = useState<RecordMediaReview | null>(null);
  const reviewer = canReview(user);
  const noun = RECORD_VIEW_NOUN[type];

  useEffect(() => {
    if (!reviewer || type === "craft") {
      setMediaReview(null);
      return;
    }
    let cancelled = false;
    fetchRecordMediaReview(type, id)
      .then((answer) => {
        if (!cancelled) setMediaReview(answer);
      })
      .catch(() => {
        if (!cancelled) setMediaReview(null);
      });
    return () => {
      cancelled = true;
    };
  }, [type, id, reviewer, reloads]);

  useEffect(() => {
    let cancelled = false;
    if (reloads === 0) setRecord(null);
    setError(null);
    apiFetch<LoadedRecord>(`${RECORD_VIEW_ENDPOINT[type]}/${encodeURIComponent(id)}`)
      .then((loaded) => (type === "craft" ? withCraftPath(loaded) : loaded))
      .then((loaded) => {
        if (!cancelled) setRecord(loaded);
      })
      .catch((err) => {
        if (!cancelled) setError(err instanceof Error ? err.message : `Couldn't load this ${noun}.`);
      });
    return () => {
      cancelled = true;
    };
  }, [type, id, noun, reloads]);

  const Icon = ICON[type];
  const updateHref = recordUpdateHref(type, id);
  const title = record ? recordTitle(type, record) : noun.charAt(0).toUpperCase() + noun.slice(1);
  const mayUpdate = Boolean(updateHref) && canUpdateRecord(user, type);
  const completenessKind = type === "questionnaire" ? "interview" : type;
  const completeness =
    record && isCompletenessKind(completenessKind) ? ((record as { completeness?: CompletenessBlock | null }).completeness ?? null) : null;

  return (
    <>
      <PageHeader
        title={title}
        description={`Read-only view of this ${noun}.`}
        icon={<Icon className="h-5 w-5" aria-hidden />}
        actions={
          record ? (
            <>
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
      {type === "artisan" && record && isAdmin(user) ? <ArtisanDuplicateBanner artisanId={record.id} /> : null}
      {!record ? (
        error ? null : <div className="panel p-4 text-sm text-ink-muted">Loading this {noun}…</div>
      ) : (
        <div className="grid gap-5">
          {decided ? (
            <div role="status" className="rounded-md border border-success-600/30 bg-success-100 px-3 py-2 text-sm text-success-600">
              {decided}
            </div>
          ) : null}
          {type !== "craft" ? (
            <>
              <ReviewNoteBanner recordType={type} recordId={record.id} status={record.status} />
              <RecordReviewBar
                recordType={type}
                recordId={record.id}
                status={record.status}
                approvesWith={mediaReview?.approvesWith ?? 0}
                onDecided={(message) => {
                  setDecided(message);
                  setReloads((n) => n + 1);
                }}
              />
            </>
          ) : null}
          {completeness ? <CompletenessMeter block={completeness} updateHref={mayUpdate ? updateHref : null} /> : null}
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
          <MediaReviewProvider
            value={
              mediaReview
                ? {
                    rejectable: new Set(mediaReview.rejectable),
                    onRejected: (message) => {
                      setDecided(message);
                      setReloads((n) => n + 1);
                    }
                  }
                : null
            }
          >
            <TypeExtras type={type} record={record} user={user} onRecordChange={setRecord} />
          </MediaReviewProvider>
          <section className="panel p-4">
            <CollabPanel recordType={type} recordId={record.id} />
          </section>
          <RecordDangerZone
            noun={noun}
            endpoint={`${RECORD_VIEW_ENDPOINT[type]}/${encodeURIComponent(id)}`}
            afterHref={RECORD_LIST_HREF[type] ?? "/dashboard"}
          />
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
          <ArtisanCardPanel artisanId={record.id} canReissue={canUpdateRecord(user, "artisan")} />
          <ExistingMedia linkedRecordType="artisan" linkedRecordId={record.id} title="Attached media" readOnly />
          <ArtisanQuestionnairePanel artisanId={record.id} artisanName={String((record as { name?: unknown }).name ?? "")} />
        </>
      );
    case "workshop":
      return (
        <>
          <WorkshopCards workshopId={record.id} />
          <ExistingMedia linkedRecordType={type} linkedRecordId={record.id} title="Attached media" readOnly />
        </>
      );
    case "product":
    case "tool":
    case "craft":
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
                      {step.name?.trim() ? null : (
                        <Badge tone="attention">Unnamed step</Badge>
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

function WorkshopCards({ workshopId }: { workshopId: string }) {
  return (
    <section className="panel grid gap-3 p-4 empty:hidden" aria-label="Artisan cards">
      <ArtisanCardSheetActions source={{ workshopId }} primaryLabel="Print artisan cards" />
    </section>
  );
}

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
              <Badge as="dd" className="mt-1">
                From transcript
              </Badge>
            ) : null}
            {response.answeredBy?.name ? <dd className="mt-1 text-xs text-ink-muted">Answered by {response.answeredBy.name}</dd> : null}
          </div>
        ))}
      </dl>
    </Section>
  );
}

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
