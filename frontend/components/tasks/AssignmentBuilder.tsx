"use client";

import { useEffect, useMemo, useState } from "react";
import { Send, Sparkles, X } from "lucide-react";

import { Field, TextArea, TextInput } from "@/components/FormControls";
import { Dropdown, MultiSelectDropdown } from "@/components/ui/Dropdown";
import { FieldBlock } from "@/components/tasks/TaskPrimitives";
import { assignmentPreview, derivedTargetFor, scopeTitle } from "@/components/tasks/scope";
import type { TaskBatchResult, TaskOptions } from "@/components/tasks/types";
import { apiFetch } from "@/lib/api";
import { formatDate } from "@/lib/format";
import { ROLES_BY_RANK, roleLabel } from "@/lib/permissions";
import { handleFormEnter } from "@/lib/formNav";
import type { UserRole } from "@/lib/types";

/**
 * The assignment builder — one scope, many people, one POST.
 *
 * Every dimension the backend accepts is expressible here (record types, assignees, an optional
 * artisan subset, questionnaire sections, a target count, title/description/due date) and the panel
 * at the bottom says the combination back in a sentence before anything is written. That preview is
 * not decoration: five independent multi-selects produce combinations nobody can verify by reading
 * the controls, and an assignment sent to fifteen people is fifteen rows to unpick if it was wrong.
 *
 * The workshop lives one level up, on the page, because it scopes the accountability and batch views
 * too — this component receives it and narrows its own pickers to match.
 */

function Step({ n, title, hint, children }: { n: number; title: string; hint?: string; children: React.ReactNode }) {
  return (
    <section className="grid gap-3">
      <div className="flex items-start gap-3">
        <span className="mt-0.5 grid h-6 w-6 shrink-0 place-items-center rounded-full bg-purple-700 text-xs font-semibold text-white">
          {n}
        </span>
        <div>
          <h3 className="font-display text-base font-bold text-ink-900">{title}</h3>
          {hint ? <p className="mt-0.5 text-xs leading-5 text-ink-500">{hint}</p> : null}
        </div>
      </div>
      <div className="grid gap-3 pl-9">{children}</div>
    </section>
  );
}

/** What a multi-select has actually got in it — the trigger only ever says "N selected". */
function Picked({ labels, empty }: { labels: string[]; empty: string }) {
  if (!labels.length) return <p className="text-xs text-ink-300">{empty}</p>;
  const shown = labels.slice(0, 6);
  const rest = labels.length - shown.length;
  return (
    <p className="text-xs text-ink-500">
      {shown.join(", ")}
      {rest > 0 ? ` +${rest} more` : ""}
    </p>
  );
}

export function AssignmentBuilder({
  options,
  loading,
  workshopId,
  workshopTitle,
  onAssigned
}: {
  options: TaskOptions | null;
  loading: boolean;
  workshopId: string;
  workshopTitle: string | null;
  onAssigned: (result: TaskBatchResult) => void;
}) {
  const [roleFilter, setRoleFilter] = useState<string>("");
  const [assigneeIds, setAssigneeIds] = useState<string[]>([]);
  const [recordTypes, setRecordTypes] = useState<string[]>([]);
  const [sectionIds, setSectionIds] = useState<string[]>([]);
  const [artisanIds, setArtisanIds] = useState<string[]>([]);
  const [targetCount, setTargetCount] = useState("");
  const [title, setTitle] = useState("");
  const [description, setDescription] = useState("");
  const [dueDate, setDueDate] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const allAssignees = useMemo(() => options?.assignees ?? [], [options]);
  const allArtisans = useMemo(() => options?.artisans ?? [], [options]);
  const allSections = useMemo(() => options?.sections ?? [], [options]);

  // Switching workshop reloads a narrower artisan list; anything picked from the previous workshop
  // has to go, or the batch would silently carry artisans who are not at this workshop at all.
  useEffect(() => {
    const available = new Set(allArtisans.map((artisan) => artisan.id));
    setArtisanIds((prev) => {
      const next = prev.filter((id) => available.has(id));
      return next.length === prev.length ? prev : next;
    });
  }, [allArtisans]);

  /** The hierarchy filter: the roles that actually appear below this admin, highest tier first. */
  const roleOptions = useMemo(() => {
    const counts = new Map<string, number>();
    allAssignees.forEach((user) => counts.set(user.role, (counts.get(user.role) ?? 0) + 1));
    return [
      { value: "", label: `Everyone below me (${allAssignees.length})` },
      ...ROLES_BY_RANK.filter((role) => counts.has(role)).map((role) => ({
        value: role as string,
        label: `${roleLabel(role)} (${counts.get(role)})`
      }))
    ];
  }, [allAssignees]);

  const visibleAssignees = useMemo(
    () => (roleFilter ? allAssignees.filter((user) => user.role === roleFilter) : allAssignees),
    [allAssignees, roleFilter]
  );

  const selectedAssignees = useMemo(
    () => allAssignees.filter((user) => assigneeIds.includes(user.id)),
    [allAssignees, assigneeIds]
  );
  const selectedSections = useMemo(
    () => allSections.filter((section) => sectionIds.includes(section.id)),
    [allSections, sectionIds]
  );
  const selectedArtisans = useMemo(
    () => allArtisans.filter((artisan) => artisanIds.includes(artisan.id)),
    [allArtisans, artisanIds]
  );

  const target = Number.parseInt(targetCount, 10);
  const validTarget = Number.isFinite(target) && target > 0 ? target : null;

  const scope = {
    recordTypes,
    sections: selectedSections,
    artisans: selectedArtisans,
    targetCount: validTarget,
    workshopTitle
  };
  const generatedTitle = scopeTitle(scope);
  const derivedTarget = derivedTargetFor({
    recordTypes,
    sectionCount: selectedSections.length,
    artisanCount: selectedArtisans.length,
    targetCount: validTarget
  });

  const hasWork = recordTypes.length > 0 || selectedSections.length > 0;
  const canSubmit = !busy && !loading && assigneeIds.length > 0 && hasWork;

  function reset() {
    setAssigneeIds([]);
    setRecordTypes([]);
    setSectionIds([]);
    setArtisanIds([]);
    setTargetCount("");
    setTitle("");
    setDescription("");
    setDueDate("");
  }

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    if (!canSubmit) return;
    setBusy(true);
    setError(null);
    try {
      const result = await apiFetch<TaskBatchResult>("/tasks/batch", {
        method: "POST",
        body: JSON.stringify({
          workshopId: workshopId || undefined,
          assigneeIds,
          recordTypes,
          artisanIds,
          sectionIds,
          targetCount: validTarget ?? undefined,
          title: title.trim() || undefined,
          description: description.trim() || undefined,
          // The date input yields YYYY-MM-DD; send a full ISO instant at local midnight.
          dueAt: dueDate ? new Date(`${dueDate}T00:00:00`).toISOString() : undefined
        })
      });
      reset();
      onAssigned(result);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to create the assignment");
    } finally {
      setBusy(false);
    }
  }

  return (
    <form className="panel grid gap-6 p-5" onSubmit={submit} onKeyDown={handleFormEnter}>
      <Step
        n={2}
        title="Who does the work"
        hint="Only people ranked below you can be given a task. Narrow by tier first if the list is long — one task row is created per person."
      >
        <div className="grid gap-3 md:grid-cols-2">
          <FieldBlock label="Filter by tier">
            {/* Filters the list beside it rather than filling a field, so it must not steal focus. */}
            <Dropdown
              value={roleFilter}
              onChange={setRoleFilter}
              options={roleOptions}
              advanceOnSelect={false}
              ariaLabel="Filter assignees by role"
              placeholder="Everyone below me"
            />
          </FieldBlock>
          <FieldBlock label="Assignees" required>
            <MultiSelectDropdown
              values={assigneeIds}
              onChange={setAssigneeIds}
              options={visibleAssignees.map((user) => ({
                value: user.id,
                label: `${user.name} — ${user.roleLabel ?? roleLabel(user.role)}`
              }))}
              placeholder={loading ? "Loading people..." : "Select people"}
              emptyLabel={loading ? "Loading people..." : "Nobody ranked below you"}
              confirmLabel="Confirm people"
            />
          </FieldBlock>
        </div>
        {selectedAssignees.length ? (
          <div className="flex flex-wrap items-center gap-1.5">
            {selectedAssignees.map((user) => (
              <span
                key={user.id}
                className="inline-flex items-center gap-1.5 rounded-full border border-purple-200 bg-purple-50 py-1 pl-2.5 pr-1.5 text-xs font-medium text-purple-700"
              >
                {user.name}
                <span className="text-purple-700/70">{user.roleLabel ?? roleLabel(user.role)}</span>
                <button
                  type="button"
                  aria-label={`Remove ${user.name}`}
                  className="grid h-4 w-4 place-items-center rounded-full hover:bg-purple-200"
                  onClick={() => setAssigneeIds((prev) => prev.filter((id) => id !== user.id))}
                >
                  <X className="h-3 w-3" aria-hidden />
                </button>
              </span>
            ))}
            <button type="button" className="text-xs font-medium text-ink-500 underline" onClick={() => setAssigneeIds([])}>
              Clear all
            </button>
          </div>
        ) : (
          <div className="flex flex-wrap items-center gap-3">
            <p className="text-xs text-ink-300">Nobody selected yet.</p>
            {visibleAssignees.length ? (
              <button
                type="button"
                className="text-xs font-medium text-purple-700 underline"
                onClick={() => setAssigneeIds(visibleAssignees.map((user) => user.id))}
              >
                Select all {visibleAssignees.length} shown
              </button>
            ) : null}
          </div>
        )}
      </Step>

      <Step
        n={3}
        title="What they must produce"
        hint="Record types and questionnaire sections can be combined. Leave the artisan list empty to mean every artisan in scope."
      >
        <div className="grid gap-3 md:grid-cols-2">
          <FieldBlock
            label="Record types"
            hint={
              <Picked
                labels={(options?.recordTypes ?? []).filter((k) => recordTypes.includes(k.value)).map((k) => k.pluralLabel)}
                empty="No record documentation asked for."
              />
            }
          >
            <MultiSelectDropdown
              values={recordTypes}
              onChange={setRecordTypes}
              options={(options?.recordTypes ?? []).map((kind) => ({
                value: kind.value,
                label: kind.pluralLabel.charAt(0).toUpperCase() + kind.pluralLabel.slice(1)
              }))}
              placeholder="Artisans, products, tools..."
              confirmLabel="Confirm record types"
            />
          </FieldBlock>
          <FieldBlock
            label="Questionnaire sections"
            hint={
              <Picked
                labels={selectedSections.map((section) => `Section ${section.code}`)}
                empty="No questionnaire coverage asked for."
              />
            }
          >
            <MultiSelectDropdown
              values={sectionIds}
              onChange={setSectionIds}
              options={allSections.map((section) => ({ value: section.id, label: `${section.code} — ${section.title}` }))}
              placeholder="Sections to cover"
              emptyLabel="No active questionnaire sections"
              confirmLabel="Confirm sections"
            />
          </FieldBlock>
        </div>
        <div className="grid gap-3 md:grid-cols-2">
          <FieldBlock
            label="Artisan subset"
            hint={
              <Picked
                labels={selectedArtisans.map((artisan) => artisan.name)}
                empty={workshopId ? "Every artisan at this workshop." : "Every artisan in the repository."}
              />
            }
          >
            <MultiSelectDropdown
              values={artisanIds}
              onChange={setArtisanIds}
              options={allArtisans.map((artisan) => ({
                value: artisan.id,
                label: artisan.place ? `${artisan.name} · ${artisan.place}` : artisan.name
              }))}
              placeholder={workshopId ? "All artisans at this workshop" : "All artisans"}
              emptyLabel={workshopId ? "No artisans linked to this workshop" : "No artisans yet"}
              confirmLabel="Confirm artisans"
            />
          </FieldBlock>
          <Field label="Target count">
            <TextInput
              type="number"
              min={1}
              max={100000}
              inputMode="numeric"
              value={targetCount}
              placeholder="e.g. 10"
              onChange={(event) => setTargetCount(event.target.value)}
            />
            <p className="text-xs text-ink-500">
              {validTarget
                ? `Each person is asked for ${validTarget} record${validTarget === 1 ? "" : "s"}.`
                : "Optional. Without it, record work reads as “as many as apply” and has no percentage."}
            </p>
          </Field>
        </div>
      </Step>

      <Step n={4} title="Title, brief and deadline" hint="Leave the title empty to use the one generated from the scope.">
        <div className="grid gap-3">
          <Field label="Title">
            <TextInput
              value={title}
              placeholder={generatedTitle}
              maxLength={300}
              onChange={(event) => setTitle(event.target.value)}
            />
          </Field>
          <Field label="Description">
            <TextArea
              rows={3}
              value={description}
              placeholder="What good work looks like here. Markdown is supported."
              onChange={(event) => setDescription(event.target.value)}
            />
          </Field>
          <div className="grid gap-3 md:grid-cols-2">
            <Field label="Due date">
              <TextInput type="date" value={dueDate} onChange={(event) => setDueDate(event.target.value)} />
            </Field>
          </div>
        </div>
      </Step>

      <section className="rounded-lg border border-purple-200 bg-surface-50 p-4">
        <p className="eyebrow flex items-center gap-1.5">
          <Sparkles className="h-3.5 w-3.5" aria-hidden />
          This will create
        </p>
        <p className="mt-1.5 font-display text-lg font-bold leading-7 text-ink-900">
          {assigneeIds.length > 0 && hasWork
            ? assignmentPreview(assigneeIds.length, scope)
            : "Pick the people and the work — the preview appears here."}
        </p>
        <dl className="mt-3 grid gap-x-6 gap-y-2 border-t border-line-200 pt-3 text-sm sm:grid-cols-2">
          <div>
            <dt className="text-xs font-medium text-ink-500">Task title</dt>
            <dd className="text-ink-900">{title.trim() || generatedTitle}</dd>
          </div>
          <div>
            <dt className="text-xs font-medium text-ink-500">Workshop</dt>
            <dd className="text-ink-900">{workshopTitle ?? "Not tied to a workshop"}</dd>
          </div>
          <div>
            <dt className="text-xs font-medium text-ink-500">Repository counts against</dt>
            <dd className="text-ink-900">
              {derivedTarget ? `${derivedTarget} item${derivedTarget === 1 ? "" : "s"} per person` : "No fixed denominator"}
            </dd>
          </div>
          <div>
            <dt className="text-xs font-medium text-ink-500">Due</dt>
            <dd className="text-ink-900">{dueDate ? formatDate(new Date(`${dueDate}T00:00:00`).toISOString()) : "No deadline"}</dd>
          </div>
        </dl>
        {!hasWork && assigneeIds.length ? (
          <p className="mt-3 text-sm text-amber-800">Pick at least one record type or questionnaire section — a task with no work in it is rejected.</p>
        ) : null}
        {hasWork && !assigneeIds.length ? <p className="mt-3 text-sm text-amber-800">Pick at least one person to assign this to.</p> : null}
      </section>

      {error ? <div className="rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div> : null}

      <div className="flex flex-wrap items-center gap-3">
        <button type="submit" className="field-button" disabled={!canSubmit}>
          <Send className="h-4 w-4" aria-hidden />
          {busy
            ? "Assigning..."
            : `Assign work${assigneeIds.length ? ` to ${assigneeIds.length} ${assigneeIds.length === 1 ? "person" : "people"}` : ""}`}
        </button>
        <button type="button" className="field-button-secondary" onClick={reset} disabled={busy}>
          Reset
        </button>
      </div>
    </form>
  );
}
