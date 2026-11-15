"use client";

import Link from "next/link";
import { MoreVertical } from "lucide-react";
import { useRouter } from "next/navigation";
import { useEffect, useId, useMemo, useRef, useState } from "react";

import { useAdminView } from "@/components/AdminViewProvider";
import { FieldDialog } from "@/components/dialogs/FieldDialog";
import { locationChanged, placeFromLocation, SessionPlaceField, sessionLocationFromForm } from "@/components/questionnaire/SessionPlaceField";
import { SpokenLanguagePicker, type SpokenLanguageChoice } from "@/components/transcript/SpokenLanguagePicker";
import { AnchoredPopover } from "@/components/ui/AnchoredPopover";
import { SearchableMultiSelect, SearchableSelect } from "@/components/ui/SearchableSelect";
import { ButtonRow } from "@/components/ui/ButtonRow";
import { CalendarField } from "@/components/ui/CalendarField";
import { SegmentedChoice } from "@/components/ui/SegmentedChoice";
import { Toggle } from "@/components/ui/Toggle";
import { listResource } from "@/lib/api";
import { contractError, questionnaireApi, type RecordBy, type Respondent, type Session, type SessionLocation } from "@/lib/questionnaire/api";
import { qs, type QsKey } from "@/lib/questionnaire/qs";
import { encodeQr, qrSvgPath } from "@/lib/qrEncode";
import { searchPeople } from "@/lib/questionnairePortal";
import type { Artisan } from "@/lib/types";

type Panel = null | "researcher" | "guest" | "edit" | "continue" | "merge" | "delete" | "join" | "removeArtisan" | { register: Respondent } | { editGuest: Respondent };

export type MenuItem = "editDetails" | "addResearcher" | "joinCode" | "readView" | "continueAnotherDay" | "removeArtisan" | "merge" | "delete";

export function sessionMenuItems(input: { saved: boolean; hasJoin: boolean; canRemove: boolean; isContentAdmin: boolean }): { regular: MenuItem[]; danger: MenuItem[] } {
  if (!input.saved) return { regular: [], danger: [] };
  const regular: MenuItem[] = ["editDetails", "addResearcher"];
  if (input.hasJoin) regular.push("joinCode");
  regular.push("readView", "continueAnotherDay");
  const danger: MenuItem[] = [];
  if (input.canRemove) danger.push("removeArtisan");
  if (input.isContentAdmin) danger.push("merge", "delete");
  return { regular, danger };
}

export const MENU_LABEL: Record<MenuItem, QsKey> = {
  editDetails: "editSession",
  addResearcher: "addResearcher",
  joinCode: "joinCodeAndQr",
  readView: "readView",
  continueAnotherDay: "continueAnotherDay",
  removeArtisan: "removeArtisan",
  merge: "mergeIntoAnotherSession",
  delete: "deleteSession"
};

const PANEL_OF: Record<Exclude<MenuItem, "readView">, Panel> = {
  editDetails: "edit",
  addResearcher: "researcher",
  joinCode: "join",
  continueAnotherDay: "continue",
  removeArtisan: "removeArtisan",
  merge: "merge",
  delete: "delete"
};

function todayLocal(): string {
  const d = new Date();
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

function useRunner(onChanged: () => void) {
  const [panel, setPanel] = useState<Panel>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
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
  return { panel, setPanel, error, busy, run };
}

const MENU_ROW = "flex min-h-11 items-center rounded-sm px-3 text-left text-sm font-medium transition hover:bg-field-100";

export function SessionActions({
  session,
  onChanged,
  recordBy,
  onRecordBy,
  spoken,
  onRemoveArtisan
}: {
  session: Session;
  onChanged: () => void;
  recordBy: RecordBy;
  onRecordBy: (value: RecordBy) => void;
  spoken?: SpokenLanguageChoice | null;
  onRemoveArtisan?: (artisanId: string) => void;
}) {
  const router = useRouter();
  const { canDeleteRecords } = useAdminView();
  const { panel, setPanel, error, busy, run } = useRunner(onChanged);
  const [open, setOpen] = useState(false);
  const anchor = useRef<HTMLButtonElement | null>(null);
  const recordById = useId();
  const live = session.state === "OPEN";
  const items = sessionMenuItems({
    saved: session.version > 0,
    hasJoin: Boolean(session.joinCode || session.joinQr),
    canRemove: Boolean(onRemoveArtisan) && session.participants.length > 1,
    isContentAdmin: canDeleteRecords
  });
  const qr = useMemo(() => {
    if (!session.joinQr) return null;
    try {
      return qrSvgPath(encodeQr(session.joinQr, "M"));
    } catch {
      return null;
    }
  }, [session.joinQr]);
  const viewHref = `/questionnaire/sessions/${encodeURIComponent(session.id)}/view`;

  const itemButton = (item: MenuItem, danger: boolean) =>
    item === "readView" ? (
      <Link key={item} role="menuitem" href={viewHref} className={`${MENU_ROW} text-ink-900`} onClick={() => setOpen(false)} data-menu-item={item}>
        {qs(MENU_LABEL[item])}
      </Link>
    ) : (
      <button
        key={item}
        type="button"
        role="menuitem"
        data-menu-item={item}
        className={`${MENU_ROW} ${danger ? "text-error-600" : "text-ink-900"}`}
        onClick={() => {
          setOpen(false);
          setPanel(PANEL_OF[item]);
        }}
      >
        {qs(MENU_LABEL[item])}
      </button>
    );

  return (
    <>
      <button
        ref={anchor}
        type="button"
        aria-label={qs("sessionOptions")}
        aria-haspopup="menu"
        aria-expanded={open}
        className="flex h-11 w-11 shrink-0 items-center justify-center rounded-full border border-line-200 bg-card text-ink-900 transition hover:border-purple-300 aria-expanded:bg-purple-100 aria-expanded:text-purple-800 dark:aria-expanded:bg-purple-700/25 dark:aria-expanded:text-purple-200"
        onClick={() => setOpen(!open)}
        data-session-options
      >
        <MoreVertical className="h-5 w-5" aria-hidden />
      </button>
      <AnchoredPopover open={open} onClose={() => setOpen(false)} anchorRef={anchor} label={qs("sessionOptions")} maxWidth={300} className="w-[300px] p-2">
        <div className="grid gap-0.5" data-session-actions>
          <div className="grid gap-1.5 px-2 pb-2.5 pt-2" data-record-by>
            <span id={recordById} className="field-label">
              {qs("fieldRecordBy")}
            </span>
            <SegmentedChoice
              label={qs("fieldRecordBy")}
              value={recordBy}
              disabled={!live}
              options={[
                { value: "SECTION", label: qs("recordBySection") },
                { value: "QUESTION", label: qs("recordByQuestion") }
              ]}
              onChange={(value) => onRecordBy(value === "QUESTION" ? "QUESTION" : "SECTION")}
            />
            <span className="text-xs text-ink-500">{qs("recordByHelper")}</span>
          </div>
          {items.regular.length ? <div aria-hidden className="mx-2 my-0.5 h-px bg-line-200" /> : null}
          <div role="menu" aria-label={qs("sessionOptions")} className="grid gap-0.5">
            {items.regular.map((item) => itemButton(item, false))}
            {items.danger.length ? <div aria-hidden className="mx-2 my-1 h-px bg-line-200" /> : null}
            {items.danger.map((item) => itemButton(item, true))}
          </div>
        </div>
      </AnchoredPopover>

      {panel === "researcher" ? (
        <AddResearcher busy={busy} error={error} onClose={() => setPanel(null)} onPick={(id) => void run(() => questionnaireApi.addResearcher(session.id, { userId: id, role: "CONTRIBUTOR" }))} />
      ) : null}
      {panel === "edit" ? (
        <EditSession session={session} busy={busy} error={error} spoken={spoken ?? null} onClose={() => setPanel(null)} onSave={(body) => void run(() => questionnaireApi.patchSession(session.id, body))} />
      ) : null}
      {panel === "join" ? (
        <FieldDialog open onClose={() => setPanel(null)} title={qs("joinCodeAndQr")}>
          <div className="grid justify-items-center gap-3" data-join-panel>
            {qr ? (
              <svg
                viewBox={`0 0 ${qr.extent} ${qr.extent}`}
                role="img"
                aria-label={qs("joinCode", { code: session.joinCode ?? "" })}
                className="h-48 w-48 rounded-md border border-line-200 bg-white p-2"
                data-join-qr
              >
                <path d={qr.path} fill="#000" shapeRendering="crispEdges" />
              </svg>
            ) : null}
            {session.joinCode ? <p className="font-display text-lg font-bold tracking-[0.2em] text-ink-900">{qs("joinCode", { code: session.joinCode })}</p> : null}
          </div>
          <ButtonRow className="mt-4">
            <button type="button" className="field-button" onClick={() => setPanel(null)}>
              {qs("ok")}
            </button>
          </ButtonRow>
        </FieldDialog>
      ) : null}
      {panel === "removeArtisan" && onRemoveArtisan ? (
        <RemoveArtisan
          session={session}
          onClose={() => setPanel(null)}
          onPick={(artisanId) => {
            setPanel(null);
            onRemoveArtisan(artisanId);
          }}
        />
      ) : null}
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
    </>
  );
}

export function SessionPeople({
  session,
  onChanged,
  onPresence,
  onAddArtisan
}: {
  session: Session;
  onChanged: () => void;
  onPresence?: (artisanId: string, present: boolean) => void;
  onAddArtisan?: () => void;
}) {
  const { panel, setPanel, error, busy, run } = useRunner(onChanged);
  const [adding, setAdding] = useState(false);
  const addRef = useRef<HTMLButtonElement | null>(null);
  const guests = (session.respondents ?? []).filter((r) => !r.artisanId);
  const saved = session.version > 0;
  const live = session.state === "OPEN";
  const canAddArtisan = live && Boolean(onAddArtisan);
  const canAddGuest = live && saved;
  return (
    <section className="grid gap-2" aria-label={qs("people")} data-session-people>
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h2 className="field-label">{qs("people")}</h2>
        {canAddArtisan || canAddGuest ? (
          <button
            ref={addRef}
            type="button"
            className="min-h-11 text-sm font-semibold text-purple-700 dark:text-purple-300"
            aria-haspopup="menu"
            aria-expanded={adding}
            onClick={() => setAdding((v) => !v)}
            data-add-people
          >
            {qs("addPeople")}
          </button>
        ) : null}
      </div>
      <AnchoredPopover open={adding} onClose={() => setAdding(false)} anchorRef={addRef} label={qs("addPeople")} className="w-56 p-1">
        <div role="menu" aria-label={qs("addPeople")} className="grid">
          {canAddArtisan ? (
            <button
              type="button"
              role="menuitem"
              className="flex min-h-11 w-full items-center rounded-md px-3 text-left text-sm text-ink-900 hover:bg-field-100"
              onClick={() => {
                setAdding(false);
                onAddArtisan?.();
              }}
              data-add-artisan-link
            >
              {qs("addArtisan")}
            </button>
          ) : null}
          {canAddGuest ? (
            <button
              type="button"
              role="menuitem"
              className="flex min-h-11 w-full items-center rounded-md px-3 text-left text-sm text-ink-900 hover:bg-field-100"
              onClick={() => {
                setAdding(false);
                setPanel("guest");
              }}
            >
              {qs("addGuest")}
            </button>
          ) : null}
        </div>
      </AnchoredPopover>
      <div className="flex flex-wrap items-center gap-x-5 gap-y-2">
        {session.participants.map((p) => (
          <div key={p.artisanId} className="min-w-0">
            <Toggle label={p.name} description={p.leftAt ? qs("away") : qs("present")} checked={!p.leftAt} disabled={!live} onChange={(present) => onPresence?.(p.artisanId, present)} />
          </div>
        ))}
      </div>
      {guests.length ? (
        <ul className="grid gap-2" aria-label={qs("guests")}>
          {guests.map((g) => (
            <li key={g.id} className="flex flex-wrap items-center justify-between gap-2 text-sm" data-guest={g.id}>
              <span className="min-w-0 text-ink-900">
                {g.name}
                <span className="ml-2 text-xs text-ink-500">{qs("guestBadge")}</span>
              </span>
              {saved ? (
                <ButtonRow size="sm" align="end">
                  <button type="button" className="field-button-secondary" onClick={() => setPanel({ editGuest: g })}>
                    {qs("edit")}
                  </button>
                  <button type="button" className="field-button-secondary" onClick={() => setPanel({ register: g })}>
                    {qs("registerAsArtisan")}
                  </button>
                </ButtonRow>
              ) : null}
            </li>
          ))}
        </ul>
      ) : null}
      {error && !panel ? (
        <p role="alert" className="text-sm text-error-600">
          {error}
        </p>
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
    </section>
  );
}

function RemoveArtisan({ session, onClose, onPick }: { session: Session; onClose: () => void; onPick: (artisanId: string) => void }) {
  const [chosen, setChosen] = useState("");
  return (
    <FieldDialog open onClose={onClose} title={qs("removeArtisan")}>
      <SearchableSelect
        value={chosen}
        onChange={setChosen}
        options={session.participants.map((p) => ({ value: p.artisanId, label: p.name }))}
        advanceOnSelect={false}
        ariaLabel={qs("removeArtisan")}
      />
      <ButtonRow className="mt-4">
        <button type="button" className="field-button-secondary" onClick={onClose}>
          {qs("cancel")}
        </button>
        <button type="button" className="field-button field-danger" disabled={!chosen} onClick={() => onPick(chosen)}>
          {qs("remove")}
        </button>
      </ButtonRow>
    </FieldDialog>
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
  spoken,
  onClose,
  onSave
}: {
  session: Session;
  busy: boolean;
  error: string | null;
  spoken: SpokenLanguageChoice | null;
  onClose: () => void;
  onSave: (body: { version: number; place: string | null; note: string | null; sessionDate?: string; location?: SessionLocation | null; crafts?: string[]; craftsByHand?: string[] }) => void;
}) {
  const placeId = useId();
  const formRef = useRef<HTMLFormElement | null>(null);
  const [place, setPlace] = useState(session.place ?? "");
  const [crafts, setCrafts] = useState<string[]>(session.crafts ?? []);
  const fromArtisans = (session.crafts ?? []).filter((c) => !(session.craftsByHand ?? []).includes(c));
  const [note, setNote] = useState(session.note ?? "");
  const [date, setDate] = useState(session.sessionDate ?? "");
  return (
    <FieldDialog open onClose={onClose} title={qs("editSession")} busy={busy}>
      <div className="grid gap-3">
        <CalendarField label={qs("fieldDate")} value={date} onChange={setDate} clearable={false} required />
        <form ref={formRef} className="grid gap-3" onSubmit={(event) => event.preventDefault()}>
          <SessionPlaceField id={placeId} place={place} onPlace={setPlace} saved={session.location ?? null} editing disabled={busy} />
        </form>
        <div className="grid gap-1 text-sm" data-session-crafts-field>
          <span className="field-label">{qs("fieldCrafts")}</span>
          <SearchableMultiSelect values={crafts} onChange={setCrafts} options={crafts.map((c) => ({ value: c, label: c }))} searchable ariaLabel={qs("fieldCrafts")} />
        </div>
        {spoken ? (
          <div data-session-spoken-language>
            <SpokenLanguagePicker value={spoken.value} onChange={spoken.choose} suggestion={spoken.suggestion} />
          </div>
        ) : null}
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
          onClick={() => {
            const location = formRef.current ? sessionLocationFromForm(new FormData(formRef.current)) : null;
            onSave({
              version: session.version,
              place: placeFromLocation(place, location) || null,
              note: note.trim() || null,
              ...(date && date !== session.sessionDate ? { sessionDate: date } : {}),
              ...(locationChanged(session.location ?? null, location) ? { location } : {}),
              ...(crafts.join("|") !== (session.crafts ?? []).join("|") ? { crafts, craftsByHand: crafts.filter((c) => !fromArtisans.includes(c)) } : {})
            });
          }}
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
