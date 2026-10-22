"use client";

import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";

import { useAdminView } from "@/components/AdminViewProvider";
import { FieldDialog } from "@/components/dialogs/FieldDialog";
import { ButtonRow } from "@/components/ui/ButtonRow";
import { CalendarField } from "@/components/ui/CalendarField";
import { SearchableSelect } from "@/components/ui/SearchableSelect";
import { listResource } from "@/lib/api";
import { contractError, questionnaireApi, type Respondent, type Session } from "@/lib/questionnaire/api";
import { qs } from "@/lib/questionnaire/qs";
import { searchPeople } from "@/lib/questionnairePortal";
import type { Artisan } from "@/lib/types";

type Panel = null | "researcher" | "guest" | "edit" | "continue" | "merge" | "delete" | { register: Respondent } | { editGuest: Respondent };

function todayLocal(): string {
  const d = new Date();
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

/**
 * What else can be done with a session (DESIGN §4.3, UNIFY B6, B8, B9, A14, A17, A18): add a
 * researcher, add or edit a guest and register one as an artisan, edit the session's details,
 * continue it another day, and — content admins — merge it into another session or delete it
 * (a restorable soft delete). The row is pills on a wide screen and stacked on a phone (ButtonRow).
 */
export function SessionActions({ session, onChanged }: { session: Session; onChanged: () => void }) {
  const router = useRouter();
  const { canDeleteRecords } = useAdminView();
  const [panel, setPanel] = useState<Panel>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const guests = (session.respondents ?? []).filter((r) => !r.artisanId);

  async function run(work: () => Promise<unknown>, after?: () => void) {
    setBusy(true);
    setError(null);
    try {
      await work();
      setPanel(null);
      if (after) after();
      else onChanged();
    } catch (failure) {
      setError(contractError(failure)?.message ?? (failure instanceof Error ? failure.message : String(failure)));
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="panel grid gap-3 p-4" data-session-actions>
      {guests.length ? (
        <ul className="grid gap-2" aria-label={qs("guests")}>
          {guests.map((g) => (
            <li key={g.id} className="flex flex-wrap items-center justify-between gap-2 text-sm" data-guest={g.id}>
              <span className="min-w-0 text-ink-900">
                {g.name}
                <span className="ml-2 text-xs text-ink-500">{qs("guestBadge")}</span>
              </span>
              <ButtonRow size="sm" align="end">
                <button type="button" className="field-button-secondary" onClick={() => setPanel({ editGuest: g })}>
                  {qs("edit")}
                </button>
                <button type="button" className="field-button-secondary" onClick={() => setPanel({ register: g })}>
                  {qs("registerAsArtisan")}
                </button>
              </ButtonRow>
            </li>
          ))}
        </ul>
      ) : null}
      <ButtonRow
        size="sm"
        destructive={
          canDeleteRecords ? (
            <button type="button" className="field-button-secondary field-danger" data-danger onClick={() => setPanel("delete")}>
              {qs("deleteSession")}
            </button>
          ) : undefined
        }
      >
        <button type="button" className="field-button-secondary" onClick={() => setPanel("researcher")}>
          {qs("addResearcher")}
        </button>
        <button type="button" className="field-button-secondary" onClick={() => setPanel("guest")}>
          {qs("addGuest")}
        </button>
        <button type="button" className="field-button-secondary" onClick={() => setPanel("edit")}>
          {qs("editSession")}
        </button>
        <button type="button" className="field-button-secondary" onClick={() => setPanel("continue")}>
          {qs("continueAnotherDay")}
        </button>
        {canDeleteRecords ? (
          <button type="button" className="field-button-secondary" onClick={() => setPanel("merge")}>
            {qs("mergeSession")}
          </button>
        ) : null}
      </ButtonRow>
      {error && !panel ? (
        <p role="alert" className="text-sm text-error-600">
          {error}
        </p>
      ) : null}

      {panel === "researcher" ? (
        <AddResearcher busy={busy} error={error} onClose={() => setPanel(null)} onPick={(id) => void run(() => questionnaireApi.addResearcher(session.id, { userId: id, role: "CONTRIBUTOR" }))} />
      ) : null}
      {panel === "guest" ? (
        <GuestForm
          title={qs("addGuest")}
          busy={busy}
          error={error}
          onClose={() => setPanel(null)}
          onSave={(name, notes) => void run(() => questionnaireApi.addRespondents(session.id, [{ name, kind: "GUEST", notes }]))}
        />
      ) : null}
      {panel && typeof panel === "object" && "editGuest" in panel ? (
        <GuestForm
          title={qs("editGuest")}
          initial={panel.editGuest}
          busy={busy}
          error={error}
          retract
          onClose={() => setPanel(null)}
          onSave={(name, notes) => void run(() => questionnaireApi.patchRespondent(session.id, panel.editGuest.id, { name, notes: notes ?? "" }))}
        />
      ) : null}
      {panel && typeof panel === "object" && "register" in panel ? (
        <RegisterGuest
          guest={panel.register}
          busy={busy}
          error={error}
          onClose={() => setPanel(null)}
          onPick={(artisanId) => void run(() => questionnaireApi.registerRespondent(panel.register.id, artisanId))}
        />
      ) : null}
      {panel === "edit" ? <EditSession session={session} busy={busy} error={error} onClose={() => setPanel(null)} onSave={(body) => void run(() => questionnaireApi.patchSession(session.id, body))} /> : null}
      {panel === "continue" ? (
        <ContinueDay
          busy={busy}
          error={error}
          onClose={() => setPanel(null)}
          onSave={(sessionDate) => {
            let next: string | null = null;
            void run(
              async () => {
                const out = await questionnaireApi.continueSessions({
                  fromSessionId: session.id,
                  artisanIds: session.participants.map((p) => p.artisanId),
                  split: "ONE",
                  sessionDate
                });
                next = out.sessions[0]?.id ?? null;
              },
              () => {
                if (next) router.push(`/questionnaire/sessions/${encodeURIComponent(next)}`);
              }
            );
          }}
        />
      ) : null}
      {panel === "merge" ? (
        <MergeInto
          session={session}
          busy={busy}
          error={error}
          onClose={() => setPanel(null)}
          onPick={(targetId) =>
            void run(
              () => questionnaireApi.mergeInto(session.id, targetId),
              () => router.replace(`/questionnaire/sessions/${encodeURIComponent(targetId)}`)
            )
          }
        />
      ) : null}
      {panel === "delete" ? (
        <FieldDialog open onClose={() => setPanel(null)} title={qs("deleteSessionTitle")} tone="danger" busy={busy} role="alertdialog">
          <p className="text-sm text-ink-700">{qs("deleteSessionBody")}</p>
          {error ? <p className="mt-2 text-sm text-error-600">{error}</p> : null}
          <ButtonRow className="mt-4">
            <button type="button" className="field-button-secondary" onClick={() => setPanel(null)}>
              {qs("cancel")}
            </button>
            <button
              type="button"
              className="field-button field-danger"
              data-confirm-delete
              disabled={busy}
              onClick={() => void run(() => questionnaireApi.deleteSession(session.id), () => router.push("/questionnaire"))}
            >
              {qs("delete")}
            </button>
          </ButtonRow>
        </FieldDialog>
      ) : null}
    </section>
  );
}

function AddResearcher({ busy, error, onClose, onPick }: { busy: boolean; error: string | null; onClose: () => void; onPick: (id: string) => void }) {
  const [search, setSearch] = useState("");
  const [people, setPeople] = useState<{ id: string; name: string }[]>([]);
  const [chosen, setChosen] = useState("");
  useEffect(() => {
    let cancelled = false;
    const timer = window.setTimeout(() => {
      void searchPeople(search)
        .then((rows) => {
          if (!cancelled) setPeople(rows);
        })
        .catch(() => undefined);
    }, 250);
    return () => {
      cancelled = true;
      window.clearTimeout(timer);
    };
  }, [search]);
  return (
    <FieldDialog open onClose={onClose} title={qs("addResearcher")} busy={busy}>
      <SearchableSelect
        value={chosen}
        onChange={setChosen}
        onSearch={setSearch}
        options={people.map((p) => ({ value: p.id, label: p.name }))}
        searchable
        advanceOnSelect={false}
        ariaLabel={qs("addResearcher")}
      />
      {error ? <p className="mt-2 text-sm text-error-600">{error}</p> : null}
      <ButtonRow className="mt-4">
        <button type="button" className="field-button-secondary" onClick={onClose}>
          {qs("cancel")}
        </button>
        <button type="button" className="field-button" disabled={!chosen || busy} onClick={() => onPick(chosen)}>
          {qs("add")}
        </button>
      </ButtonRow>
    </FieldDialog>
  );
}

function GuestForm({
  title,
  initial,
  busy,
  error,
  retract = false,
  onClose,
  onSave
}: {
  title: string;
  initial?: Respondent;
  busy: boolean;
  error: string | null;
  retract?: boolean;
  onClose: () => void;
  onSave: (name: string, notes: string | null) => void;
}) {
  const [name, setName] = useState(initial?.name ?? "");
  const [notes, setNotes] = useState(initial?.notes ?? "");
  return (
    <FieldDialog open onClose={onClose} title={title} busy={busy}>
      <div className="grid gap-3">
        <label className="grid gap-1 text-sm">
          <span className="field-label">{qs("fieldGuestName")}</span>
          <input className="field-input" value={name} maxLength={120} onChange={(e) => setName(e.target.value)} data-guest-name-edit />
        </label>
        <label className="grid gap-1 text-sm">
          <span className="field-label">{qs("guestNotes")}</span>
          <textarea className="field-input min-h-16" value={notes} maxLength={2000} onChange={(e) => setNotes(e.target.value)} />
        </label>
        {retract ? <p className="text-xs text-ink-500">{qs("retractHint")}</p> : null}
        {error ? <p className="text-sm text-error-600">{error}</p> : null}
      </div>
      <ButtonRow className="mt-4">
        <button type="button" className="field-button-secondary" onClick={onClose}>
          {qs("cancel")}
        </button>
        <button type="button" className="field-button" disabled={busy || (!retract && !name.trim())} onClick={() => onSave(name.trim(), notes.trim() || null)}>
          {qs("save")}
        </button>
      </ButtonRow>
    </FieldDialog>
  );
}

function RegisterGuest({ guest, busy, error, onClose, onPick }: { guest: Respondent; busy: boolean; error: string | null; onClose: () => void; onPick: (artisanId: string) => void }) {
  const [search, setSearch] = useState(guest.name);
  const [artisans, setArtisans] = useState<Artisan[]>([]);
  const [chosen, setChosen] = useState("");
  useEffect(() => {
    let cancelled = false;
    const timer = window.setTimeout(() => {
      void listResource<Artisan>("/artisans", { pageSize: 40, search: search || undefined })
        .then((page) => {
          if (!cancelled) setArtisans(page.items ?? []);
        })
        .catch(() => undefined);
    }, 250);
    return () => {
      cancelled = true;
      window.clearTimeout(timer);
    };
  }, [search]);
  return (
    <FieldDialog open onClose={onClose} title={qs("registerTitle", { name: guest.name })} description={qs("registerBody")} busy={busy}>
      <SearchableSelect
        value={chosen}
        onChange={setChosen}
        onSearch={setSearch}
        options={artisans.map((a) => ({ value: a.id, label: a.place ? `${a.name} · ${a.place}` : a.name }))}
        searchable
        advanceOnSelect={false}
        ariaLabel={qs("fieldArtisan")}
      />
      {error ? <p className="mt-2 text-sm text-error-600">{error}</p> : null}
      <ButtonRow className="mt-4">
        <button type="button" className="field-button-secondary" onClick={onClose}>
          {qs("cancel")}
        </button>
        <button type="button" className="field-button" disabled={!chosen || busy} onClick={() => onPick(chosen)}>
          {qs("register")}
        </button>
      </ButtonRow>
    </FieldDialog>
  );
}

function EditSession({
  session,
  busy,
  error,
  onClose,
  onSave
}: {
  session: Session;
  busy: boolean;
  error: string | null;
  onClose: () => void;
  onSave: (body: { version: number; place: string | null; note: string | null; sessionDate?: string }) => void;
}) {
  const [place, setPlace] = useState(session.place ?? "");
  const [note, setNote] = useState(session.note ?? "");
  const [date, setDate] = useState(session.sessionDate ?? "");
  return (
    <FieldDialog open onClose={onClose} title={qs("editSession")} busy={busy}>
      <div className="grid gap-3">
        <CalendarField label={qs("fieldDate")} value={date} onChange={setDate} clearable={false} required />
        <label className="grid gap-1 text-sm">
          <span className="field-label">{qs("fieldPlace")}</span>
          <input className="field-input" value={place} maxLength={200} onChange={(e) => setPlace(e.target.value)} />
        </label>
        <label className="grid gap-1 text-sm">
          <span className="field-label">{qs("fieldNote")}</span>
          <input className="field-input" value={note} maxLength={80} onChange={(e) => setNote(e.target.value)} />
        </label>
        {error ? <p className="text-sm text-error-600">{error}</p> : null}
      </div>
      <ButtonRow className="mt-4">
        <button type="button" className="field-button-secondary" onClick={onClose}>
          {qs("cancel")}
        </button>
        <button
          type="button"
          className="field-button"
          disabled={busy || !date}
          onClick={() =>
            // An edit sends only what changed: a field left alone is never cleared (DR audit C1, H1).
            onSave({
              version: session.version,
              place: place.trim() || null,
              note: note.trim() || null,
              ...(date && date !== session.sessionDate ? { sessionDate: date } : {})
            })
          }
        >
          {qs("save")}
        </button>
      </ButtonRow>
    </FieldDialog>
  );
}

function ContinueDay({ busy, error, onClose, onSave }: { busy: boolean; error: string | null; onClose: () => void; onSave: (date: string) => void }) {
  const [date, setDate] = useState(todayLocal());
  return (
    <FieldDialog open onClose={onClose} title={qs("continueAnotherDay")} description={qs("continueBody")} busy={busy}>
      <CalendarField label={qs("fieldDate")} value={date} onChange={setDate} clearable={false} required />
      {error ? <p className="mt-2 text-sm text-error-600">{error}</p> : null}
      <ButtonRow className="mt-4">
        <button type="button" className="field-button-secondary" onClick={onClose}>
          {qs("cancel")}
        </button>
        <button type="button" className="field-button" disabled={busy || !date} onClick={() => onSave(date)}>
          {qs("continueStart")}
        </button>
      </ButtonRow>
    </FieldDialog>
  );
}

function MergeInto({ session, busy, error, onClose, onPick }: { session: Session; busy: boolean; error: string | null; onClose: () => void; onPick: (id: string) => void }) {
  const [options, setOptions] = useState<{ value: string; label: string }[]>([]);
  const [chosen, setChosen] = useState("");
  useEffect(() => {
    void questionnaireApi
      .listSessions({ questionnaireId: session.questionnaire.id, limit: 50 })
      .then((page) => setOptions(page.items.filter((s) => s.id !== session.id).map((s) => ({ value: s.id, label: s.title }))))
      .catch(() => setOptions([]));
  }, [session.id, session.questionnaire.id]);
  return (
    <FieldDialog open onClose={onClose} title={qs("mergeSession")} description={qs("mergeBody")} busy={busy}>
      <SearchableSelect value={chosen} onChange={setChosen} options={options} searchable advanceOnSelect={false} ariaLabel={qs("mergeSession")} />
      {error ? <p className="mt-2 text-sm text-error-600">{error}</p> : null}
      <ButtonRow className="mt-4">
        <button type="button" className="field-button-secondary" onClick={onClose}>
          {qs("cancel")}
        </button>
        <button type="button" className="field-button" disabled={!chosen || busy} onClick={() => onPick(chosen)}>
          {qs("merge")}
        </button>
      </ButtonRow>
    </FieldDialog>
  );
}
