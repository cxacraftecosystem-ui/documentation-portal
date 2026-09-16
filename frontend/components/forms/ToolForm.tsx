"use client";

import { useId, useMemo, useRef, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";

import { useAuth } from "@/components/AuthProvider";
import { mergeById } from "@/components/data/cappedList";
import { CappedListNotice } from "@/components/data/CappedListNotice";
import { Field, Select, TextInput } from "@/components/FormControls";
import { CarryContextBanner, carryScope, useCarryContext } from "@/components/forms/CarryContextBanner";
import { cmTextFromInches, inchesTextFromCm, propagate } from "@/components/forms/dimensionUnits";
import { LocationFields, type LocationInitialValues } from "@/components/forms/LocationFields";
import {
  forgetAcceptance,
  measurementMethodsFor,
  NO_ACCEPTED_MEASUREMENTS,
  rememberAcceptance,
  type AcceptedMeasurements
} from "@/components/forms/measurementMethods";
import { MediaCaptureField } from "@/components/forms/MediaCaptureField";
import {
  craftNameFor,
  craftsChangeClearsArtisans,
  craftsKey,
  sortArtisansByCraft,
  useCraftAndArtisanOptions,
  useRecordsOffPage
} from "@/components/forms/recordPickers";
import { useWorkshopSelection, WorkshopSelect } from "@/components/forms/WorkshopSelect";
import { ExistingMedia } from "@/components/media/ExistingMedia";
import { GridMeasurement, type GridFiles, type GridGroup } from "@/components/media/GridMeasurement";
import { RecordPhotoMeasure, type MeasureColumn } from "@/components/media/RecordPhotoMeasure";
import { UploadProgress } from "@/components/media/UploadProgress";
import { DictatedTextInput } from "@/components/richtext/DictatedTextInput";
import { DictationUnavailableNotice } from "@/components/richtext/DictationUnavailableNotice";
import { RichTextField } from "@/components/richtext/RichTextField";
import { appendStoredParagraph } from "@/components/richtext/storedRichText";
import { MultiSelectDropdown } from "@/components/ui/Dropdown";
import { UnsavedChangesDialog } from "@/components/UnsavedChangesDialog";
import { useLeaveGuard } from "@/components/UnsavedChangesGuard";
import { apiFetch } from "@/lib/api";
import { locationFromForm, numericValue, recordedAtFromForm, recordedTimezoneFromForm, requiredText, sameIdSet, textValue, useUnsavedChanges } from "@/lib/forms";
import { handleFormEnter } from "@/lib/formNav";
import { collectExifMetadata, exifMetadataToRemark, uploadMediaBatch, uploadMediaFile, type BatchProgress } from "@/lib/media";
import { saveOrQueue } from "@/lib/offline";
import { hasRank } from "@/lib/permissions";
import type { Artisan, Craft, RecordStatus, ToolDocumentation } from "@/lib/types";
import { makerOptions, traditionOptions } from "@/lib/types";

/**
 * Dropdown label for a linked artisan: "Craft · Name · Place", craft FIRST.
 *
 * ── THE CRAFT LEADS, AND THAT IS THE GROUPING ──────────────────────────────────────────────────
 * This picker is a multi-select over the artisans of SEVERAL crafts at once, ordered by craft name
 * A→Z and then by artisan name (`sortArtisansByCraft`). A group HEADING would say it better, and
 * this repository's `SelectOption` is `{value, label, disabled?}` — no `group` field, and neither
 * handset's has one either, so widening it here would put the browser a step ahead of two clients
 * that cannot follow. Leading with the craft is the same information in the space that exists: the
 * sorted list reads as blocks, and `SearchableSelect`'s filter matches on the label, so typing a
 * craft name narrows the list to that craft's people.
 *
 * "·" (middle dot), not "•" — Android joins every record label with the middle dot, and the process
 * form already does; using both marks in one tool form reads as two conventions.
 *
 * A craft this client cannot name is simply omitted from the label rather than filled in with a
 * guess — those rows sort last, together, for the same reason.
 */
function artisanOptionLabel(artisan: Artisan, craftName: string) {
  const name = artisan.name?.trim() || "Unnamed artisan";
  return [craftName.trim(), name, artisan.place?.trim()].filter(Boolean).join(" · ");
}

/**
 * THE CRAFTS A STORED TOOL IS LINKED TO, WITH THE TOOL'S OWN `craftId` AT THE HEAD.
 *
 * Read TWICE and by design: once to seed the picker, once at submit as the baseline the selection is
 * diffed against. One function, so "what this record holds" cannot be answered two ways by the same
 * form — a seed and a baseline that disagreed would make an untouched form look edited, or an edited
 * one look untouched.
 *
 * ── AN ABSENT `craftLinks` IS NOT AN EMPTY ONE ────────────────────────────────────────────────
 * The web deploys to Vercel and the API to EC2 separately, so this bundle can be reading a server
 * with no join table at all; and a tool saved through an older client after the table existed has a
 * `craftId` and no link rows, because an omitted `craftIds` means "leave the links alone". Seeding
 * `[]` in either case would open the edit form with the picker empty over a record that IS linked —
 * and the researcher's obvious repair, picking the craft again, is the one action that rewrites the
 * links.
 *
 * ── AND THE SCALAR LEADS, WHICH IS A FIX AND NOT A TIDY-UP ────────────────────────────────────
 * Element 0 is what the route writes back into `tool.craftId` / `tool.artisanId`. See
 * {@link storedArtisanIds} for the failure that makes the hoist load-bearing; the craft side takes
 * it for symmetry and for the same reason in miniature, since a picker whose element 0 is not the
 * record's own scalar is a picker that moves the scalar on a save nobody made.
 */
function storedCraftIds(tool?: ToolDocumentation): string[] {
  const linked = (tool?.craftLinks ?? []).map((link) => link.craftId).filter(Boolean);
  const head = tool?.craftId ?? "";
  if (!head) return linked;
  return [head, ...linked.filter((id) => id !== head)];
}

/**
 * THE ARTISANS A STORED TOOL IS LINKED TO, WITH THE TOOL'S OWN `artisanId` AT THE HEAD.
 *
 * Same contract as {@link storedCraftIds} — same two call sites, same absent-vs-empty argument — and
 * the head hoist here is closing a defect rather than keeping a symmetry.
 *
 * ── `artisanLinks` DOES NOT MEAN "THIS TOOL'S ARTISANS". IT MEANS "ALSO ASSIGNED TO". ─────────
 * `ToolArtisan` predates this form's own picker by three months: "Assign a tool to multiple
 * artisans" writes rows for anybody, of any craft, and touches none of `artisanId` / `artisanName` /
 * `place`; `DELETE /tools/{id}/artisans/{artisanId}` removes a row just as freely, the tool's own
 * scalar artisan included. So `artisanId = A` beside `artisanLinks = [B, C]` is an ORDINARY shape —
 * no legacy data required — and seeding the picker from the links alone made element 0 `B`. Someone
 * opening `/tools/T/edit` to fix a typo in Material then pressed Update and sent `artisanId: B` with
 * `artisanName` and `place` still describing A: the tool now points at B while naming A, and A's
 * association is gone from the scalar AND from the join table, under a 200, with nothing on screen
 * having changed. Hoisting the scalar keeps `artisanId` stable across a reopen-and-save, keeps A in
 * the selection instead of deleting the record's own artisan, and makes the seeded
 * `artisanName`/`place` agree with element 0.
 *
 * ── AND THE WIRE ORDER IS NOT WHAT THIS LEANS ON ──────────────────────────────
 * `routes/tools._order_artisan_links` now pins the tool's own `artisanId` to the front of
 * `artisanLinks` and orders the rest `createdAt asc, id asc`, so a current API already answers in
 * this shape and the hoist is a no-op against it. It is kept for the two cases that pin cannot
 * reach: a tool whose `artisanId` has no link row at all has nothing to pin — exactly the
 * assign-then-unassign shape above, which migration 20260916090000 repaired for existing rows and
 * cannot prevent for future ones — and this bundle may be reading an older API, since the web
 * deploys to Vercel and the API to EC2 separately. Before either landed, `artisanLinks` had no order
 * whatever: ticking Bhavesh then Anil and reopening could hand this form `[Anil, Bhavesh]` while
 * both name boxes still read Bhavesh, and the next save of any kind wrote `artisanId = Anil` beside
 * `artisanName = "Bhavesh"`.
 */
function storedArtisanIds(tool?: ToolDocumentation): string[] {
  const linked = (tool?.artisanLinks ?? []).map((link) => link.artisanId).filter(Boolean);
  const head = tool?.artisanId ?? "";
  if (!head) return linked;
  return [head, ...linked.filter((id) => id !== head)];
}

/**
 * The dimension columns the on-device measurement may be accepted into, in the order the boxes are
 * drawn below.
 *
 * ── THE THIRD ENTRY POINTS AT `heightInches`, AND THAT IS THE WHOLE POINT OF IT ───────────────
 * `ToolDocumentation` carries BOTH a plain unit-less `height` and a `heightInches` (nullable
 * `Decimal(10, 2)`, added by migration 20260913120100 and declared on `ToolCreate` / `ToolUpdate`).
 * Only the second is in `measurement_provenance.DIMENSION_FIELDS`, and the difference is not
 * cosmetic: **a marker naming `height` is a 422 on the whole save**, by name, which `saveOrQueue`
 * will not queue — so it costs the researcher the form rather than costing them a provenance hint.
 * It is also the column that cannot say what it holds: a reading accepted into `height` is stored
 * with no recoverable unit, which is exactly the defect the inches column was added to close.
 *
 * ── `height` IS THE CENTIMETRE BOX NOW, AND IT IS STILL NOT A PROPOSAL DESTINATION ────────────
 * THE PARAGRAPH THAT STOOD HERE IS RETIRED RATHER THAN DELETED, because half of it is still the
 * reason this list has three entries. It read: *"It still holds every number already typed into it,
 * in a unit nothing can name, so its box stays on the form below and keeps working exactly as it
 * did. What it does not receive is a MACHINE reading."* The first sentence stopped being true on
 * 2026-09-15: `height` is now the CENTIMETRE partner of `heightInches` and `width` the centimetre
 * partner of `breadthInches`, paired 1:1 by a real unit conversion (`forms/dimensionUnits.ts`), and
 * the on-screen note under the pair says so.
 *
 * The second sentence is UNCHANGED and is why neither centimetre box appears in this list. A
 * proposal here is two things and not one — a number AND a claim about how it was obtained — and
 * `measurement_provenance.DIMENSION_FIELDS` admits only the three inch columns, so a marker naming
 * `height` or `width` is a 422 on the whole save, by name. What the accept callbacks below do
 * instead is fill the centimetre partner FROM the accepted inches, arithmetically, after the
 * acceptance is filed against the inch box. The centimetre box carries no provenance of its own and
 * must never be given a fake one: the machine measured inches, and a converted figure is a
 * derivation of that reading rather than a second one.
 *
 * ── WHY `thickness` AND `radius` ARE STILL NOT OFFERED ───────────────────────────────────────
 * Not an oversight: they are uncontrolled `defaultValue` boxes read straight out of `FormData` at
 * submit, so a proposal has nowhere to land without making two more inputs controlled, and their
 * units are undeclared with no established convention to lean on. Offering a measurement into a box
 * whose unit nobody has ever written down would be inventing one. `width` used to be on this list
 * for the same reason and no longer is — it is controlled now, because it is half of a pair — but it
 * is still not a DESTINATION, for the provenance reason above.
 *
 * Re-check: `grep -n heightInches backend/prisma/schema.prisma backend/app/schemas/records.py`.
 */
const MEASURE_COLUMNS: MeasureColumn[] = [
  { key: "lengthInches", label: "Length (inches)", unit: "in" },
  { key: "breadthInches", label: "Breadth (inches)", unit: "in" },
  // No `note`, and its absence IS the argument: the column states its unit in its own name, so there
  // is nothing left for a sentence under the button to disclose. `MeasureColumn.note` stays on the
  // type for the next column that needs it.
  { key: "heightInches", label: "Height (inches)", unit: "in" }
];

/**
 * Status policy (backend-enforced; the UI mirrors it): professor+ may pick any status and new
 * records default to APPROVED; everyone below sees a locked chip — creations are forced to PENDING
 * and unauthorized status changes are silently dropped server-side on update.
 */
function StatusField({
  canSetStatus,
  initialStatus,
  onDirty
}: {
  canSetStatus: boolean;
  initialStatus?: RecordStatus;
  onDirty?: () => void;
}) {
  if (canSetStatus) {
    const options: RecordStatus[] = ["DRAFT", "PENDING", "APPROVED", "REJECTED"];
    if (initialStatus === "NEEDS_REVISION") options.push("NEEDS_REVISION");
    return (
      <Field label="Status">
        <Select name="status" defaultValue={initialStatus ?? "APPROVED"} onChange={onDirty}>
          {options.map((status) => (
            <option key={status}>{status}</option>
          ))}
        </Select>
      </Field>
    );
  }
  const text = initialStatus ? initialStatus.charAt(0) + initialStatus.slice(1).toLowerCase().replace(/_/g, " ") : "Pending";
  return (
    <div className="grid content-start gap-1">
      <span className="field-label">Status</span>
      <span
        className="inline-flex h-10 w-fit items-center rounded-full border border-line-200 bg-surface-50 px-4 text-sm font-medium text-ink"
        title="Submitted for review — a reviewer sets the final status."
      >
        {text}
      </span>
    </div>
  );
}

/**
 * ── DICTATION ON THIS FORM: WHICH BOXES HAVE A MICROPHONE, AND WHY THE REST DO NOT ──────────────
 *
 * The rule: a free-text box HAS a microphone unless there is a reason it must not, and the reason is
 * written down here so that a later reader can tell a decision from an oversight. One-line boxes use
 * `DictatedTextInput`; the two narrative ones use `RichTextField`, whose editor carries the
 * microphone at the caret so a phrase lands inside the document rather than on the end of it.
 *
 * DICTATED: Toolkit name · Local name · English name · Craft name · Artisan name · Place · Process
 * used in · Material · Suggestions for improvement · Remarks · (and Village, inside the location
 * card, which owns its own decision).
 *
 * MATERIAL IS DICTATED AND THE MEASUREMENTS ARE NOT, which is the one line of this register a
 * reviewer will stop at. "Mango wood with an iron collar" is free prose — neither a measurement nor a
 * vocabulary — and it is the answer most likely to be given while holding the tool.
 *
 * NOT DICTATED, one line each, and each is a rule rather than a preference:
 *
 *  - **Workshop, Linked crafts, Linked artisans, Maker, Tradition type, Status** — closed
 *    vocabularies and record pickers behind a themed dropdown. There is no free text to speak. The
 *    two craft/artisan pickers are MULTI-selects now; that changes how many answers they take, not
 *    whether any of them is spoken.
 *  - **Years in use, Height (cm), Height (inches), Width (cm), Length, Breadth, Thickness, Weight,
 *    Radius, Replacement cost** — `type="number"` boxes. A recogniser spells digits out in words ("thirty"),
 *    which a native number input DISCARDS silently: the box is empty after a spoken answer with
 *    nothing saying why. Length, Breadth and Height (inches) carry a second reason — the
 *    grid-measurement capture PROPOSES all three and a person accepts, so a spoken fourth route
 *    would record an acceptance for a reading nobody can re-derive.
 *  - **Document using grid, Process stages media, Tool media** — file pickers and capture cards.
 *
 * NOT title-cased, deliberately, though they are dictated: **Local name** (Devanagari or Gujarati,
 * where capitalising means nothing), **Process used in** and **Material** — all three are absent
 * from the API's title-cased set (`backend/app/services/records.py:339-354`), so a "Will be saved
 * as …" hint on any of them would promise a normalisation that never happens.
 *
 * ONE SENTENCE FOR THE WHOLE FORM. Every control above passes `explainWhenUnavailable={false}` and
 * `DictationUnavailableNotice` sits once at the top. Eleven microphones down one form is eleven
 * copies of the Firefox paragraph, which is how a true sentence becomes wallpaper.
 */
export function ToolForm({ initial }: { initial?: ToolDocumentation }) {
  const router = useRouter();
  const searchParams = useSearchParams();
  const { user } = useAuth();
  const canSetStatus = hasRank(user, "PROFESSOR");
  const formRef = useRef<HTMLFormElement>(null);
  const [mediaFiles, setMediaFiles] = useState<File[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [uploadProgress, setUploadProgress] = useState<BatchProgress | null>(null);
  /**
   * EVERY LINKED CRAFT AND EVERY LINKED ARTISAN, IN THE RESEARCHER'S OWN ORDER.
   *
   * Both pickers take several answers now. The ORDER is the wire contract and not a display choice:
   * the route derives `tool.craftId` from element 0 and `tool.craftName` from every name joined ", "
   * in this order, so a set would lose exactly the fact the server persists.
   *
   * ── SEEDING AN EDIT, WHICH IS `storedCraftIds` / `storedArtisanIds` AND NOTHING ELSE ─────────
   * Both are the record's stored links with the record's own scalar hoisted to element 0 — the
   * absent-vs-empty argument and the reason the hoist is a DEFECT FIX rather than a preference are
   * written out at those two functions. They are read again at submit as the baseline the selection
   * is diffed against, so the form has exactly one answer to "what does this record hold".
   *
   * The query-string fallback below is the CREATE path only: a link the record already holds always
   * wins over a carried one, and on a create there is no record to disagree with.
   */
  const [craftIds, setCraftIds] = useState<string[]>(() => {
    const seeded = storedCraftIds(initial);
    if (seeded.length) return seeded;
    const carried = searchParams.get("craftId");
    return carried ? [carried] : [];
  });
  const [artisanIds, setArtisanIds] = useState<string[]>(() => {
    const seeded = storedArtisanIds(initial);
    if (seeded.length) return seeded;
    const carried = searchParams.get("artisanId");
    return carried ? [carried] : [];
  });
  /**
   * THE FIRST OF EACH, DERIVED — not a second source of truth.
   *
   * `tool.craftId` and `tool.artisanId` keep holding the first selected record, for backward
   * compatibility with every filter, index, report and carry-forward that reads them, so the form
   * keeps a name for that value rather than spelling `craftIds[0] ?? ""` at the five places that
   * want it: the payload, the banked sitting, the carry scope and the two "is anything picked yet"
   * guards. Derived and never `setState`d, so the two cannot drift.
   */
  const craftId = craftIds[0] ?? "";
  const artisanId = artisanIds[0] ?? "";
  // Android parity: picking a linked craft fills the craft name; picking a linked artisan fills the
  // artisan name + place — so these three are controlled.
  const [craftName, setCraftName] = useState(initial?.craftName ?? searchParams.get("craftName") ?? "");
  const [artisanName, setArtisanName] = useState(initial?.artisanName ?? searchParams.get("artisanName") ?? "");
  const [place, setPlace] = useState(initial?.place ?? searchParams.get("place") ?? "");
  /*
    HOISTED FOR THE MICROPHONE. These five were uncontrolled `defaultValue` boxes; `DictatedTextInput`
    is controlled by its caller and has exactly one mode, for the reason written out in that file (a
    self-controlled box repaints stale text on a form cleared by `formElement.reset()`). This form
    clears by NAVIGATING AWAY, so the trap does not bite here — but one contract for the control
    across the app is worth more than a second mode on this one screen.
  */
  const [toolkitName, setToolkitName] = useState(initial?.toolkitName ?? "");
  const [localName, setLocalName] = useState(initial?.localName ?? "");
  const [englishName, setEnglishName] = useState(initial?.englishName ?? "");
  /**
   * IS "ENGLISH NAME" STILL FOLLOWING "TOOLKIT NAME"? A one-way door, decided once per form.
   *
   * Two states. ARMED: every write of the toolkit name — typed, dictated, pasted, or set by a
   * carry/prefill path — writes the English name too. DIVORCED: the researcher has touched the
   * English box themselves and it is theirs from then on. There is no way back; a form that re-armed
   * would overwrite a hand-typed name the next time somebody corrected a typo in the toolkit name.
   *
   * ── HOW THE INITIAL STATE IS DECIDED, WHICH IS THE WHOLE EDGE CASE ────────────────────────────
   * A CREATE always arms (there is no stored English name to protect). An EDIT arms only when the
   * stored English name is blank or is RAW-EQUAL to the stored toolkit name — i.e. when mirroring
   * could not destroy anything anybody chose. A record whose two names genuinely differ opens
   * DIVORCED, so correcting the toolkit name of an existing tool never clobbers its English name.
   * Raw equality on purpose: no trim, no case fold. "Aari  Needle" and "Aari Needle" are two
   * different strings and somebody typed the second one.
   *
   * ── WHY A `useRef` AND NOT `useState` ────────────────────────────────────────────────────────
   * It is read inside `applyToolkitName`, which is handed to a child as a callback. A `useState`
   * value captured in a stale closure would re-arm a divorced form on the next keystroke — the
   * failure being silent and destructive, which is exactly the shape that must not depend on
   * render timing. Nothing renders off it, so there is nothing to re-render for.
   *
   * ── AND WHY THE MIRROR IS AT THE WRITE SITE RATHER THAN IN AN EFFECT ─────────────────────────
   * `useEffect(() => { if (armed) setEnglishName(toolkitName) }, [toolkitName])` fires ON MOUNT with
   * the loaded toolkit name. On an edit whose stored English name is empty — legitimately ARMED — it
   * would write the English name before anybody had typed, making the form dirty and changing a
   * saved record by merely OPENING it. Mirroring at the write site cannot do that: there is no write
   * until somebody or something writes.
   */
  const mirrorArmed = useRef(
    initial ? (initial.englishName ?? "").trim() === "" || (initial.englishName ?? "") === (initial.toolkitName ?? "") : true
  );
  /**
   * The one writer of `toolkitName` on this form, so the mirror cannot be forgotten at a call site.
   *
   * `user` says whether a person did it. It drives `markDirty()` and NOTHING ELSE — in particular it
   * does not disarm, because a programmatic write of the TOOLKIT name is not an edit of the ENGLISH
   * one. Only the English box's own handler disarms.
   *
   * THERE IS NO PROGRAMMATIC CALLER ON THIS FORM TODAY, and the parameter is here anyway. Nothing
   * carries a toolkit name: `useCarryContext` banks craft, artisan, place, workshop and tool, and
   * `onApply` above sets none of them into this box. The day a carry node, a query-string seed or a
   * duplicate-record path does, it has to go through this helper — a bare `setToolkitName` would
   * write the box and silently not mirror, which looks exactly like the feature working — and the
   * parameter is what makes it impossible to add one without deciding whether it counts as an edit.
   *
   * THE MIRRORED STRING IS THE RAW ONE, and that is not a shortcut. Both columns are in the API's
   * title-cased set (`backend/app/services/records.py` `TITLE_CASE_FIELDS`), and `TitleCasedInput`
   * does not transform anything — it renders a "Will be saved as …" hint and passes the event
   * through — so the normalisation happens once, on the server, to both values. `titleCase` is a
   * pure function of the string, so mirroring the raw text GUARANTEES the two stored values are
   * identical. Normalising here would be a second implementation of a server rule, and a second
   * implementation of a rule is a rule that can drift.
   */
  function applyToolkitName(next: string, { user }: { user: boolean }) {
    setToolkitName(next);
    if (user) markDirty();
    if (mirrorArmed.current) setEnglishName(next);
  }
  const [processUsedIn, setProcessUsedIn] = useState(initial?.processUsedIn ?? "");
  const [material, setMaterial] = useState(initial?.material ?? "");
  // Android parity: ordered "Process stages" captures, archived as STAGE_STEP_1, STAGE_STEP_2, …
  const [stageFiles, setStageFiles] = useState<File[]>([]);
  // Grid-measurable dimensions are controlled so the "Document using grid" capture can auto-fill them.
  const [length, setLength] = useState(initial?.lengthInches != null ? String(initial.lengthInches) : "");
  const [breadth, setBreadth] = useState(initial?.breadthInches != null ? String(initial.breadthInches) : "");
  /**
   * THE TWO CENTIMETRE BOXES. `height` pairs with `heightInches`, `width` pairs with `breadthInches`.
   *
   * ── SEEDED FROM THEIR OWN COLUMN AND FROM NOTHING ELSE. NO CONVERSION ON LOAD. ────────────────
   * The pairing is new; the columns are not. Rows saved before it genuinely hold two unrelated
   * numbers in `height` and `heightInches` — the schema comment and the on-screen note under the
   * pair both say so — so converting at mount would rewrite a stored value the moment somebody
   * OPENED the record, and the next save would make it permanent. Neither box is compared against
   * its partner here, ever. Conversion happens on USER INPUT, in the typing box's own handler.
   *
   * ── `width` IS CONTROLLED NOW, WHERE IT WAS `defaultValue` ───────────────────────────────────
   * It has to be: a partner box is written by code, and an uncontrolled input cannot be. The payload
   * reads it from state for the reason already written beside `heightInches` below — a controlled
   * input's value does still reach `FormData`, but state is the single source and cannot disagree
   * with what is on screen.
   */
  const [height, setHeight] = useState(initial?.height != null ? String(initial.height) : "");
  const [width, setWidth] = useState(initial?.width != null ? String(initial.width) : "");
  /*
    THE THIRD MEASUREMENT THE GRID PANEL PROPOSES, AND THE COLUMN IT SHOULD ALWAYS HAVE FILLED.

    TWO SENTENCES OF THIS PARAGRAPH ARE RETIRED RATHER THAN DELETED, because the paragraph around
    them is still the reason this state exists. They read: *"`height` above is the OLD unit-less
    column. It is kept rather than merged because rows already hold values in it and nothing in the
    database can say what unit those are in"* and *"the unit-less box is typed by hand or not at
    all."* Both stopped being true on 2026-09-15, when `height` was PAIRED with this column as its
    centimetre half (`MEASURE_COLUMNS` above, the seeding comment beside `setHeight`, the two boxes
    and the note under them): `height` is the centimetre partner, it is written by code — by
    `typeInches` on every keystroke in this box, and by both accept callbacks through
    `propagate(text, cmTextFromInches, setHeight)` — and a maintainer who read the old sentence
    would take those writers for the bug rather than the feature.

    WHAT IS STILL TRUE, AND IS WHY BOTH COLUMNS EXIST. Rows saved before the pairing hold values in
    `height` whose unit nothing in the database can name — see `backend/app/schemas/records.py` at
    `ToolCreate.heightInches` and migration 20260913120100 — so the two are never converted on
    load and an old record can show two figures that disagree.

    The defect this box closes is not a missing field, it is a SILENT one: `GridMeasurement`'s
    `onHeight` returned a reading in INCHES and the only box it could reach was the unit-less one, so
    every grid-measured tool height in this repository was stored with no recoverable unit, under a
    200, with the number looking perfectly right on screen. `onHeight` below writes THIS state, and
    the centimetre box takes the CONVERTED figure beside it — a derivation, carrying no
    provenance of its own, never the inches verbatim.
  */
  const [heightInches, setHeightInches] = useState(initial?.heightInches != null ? String(initial.heightInches) : "");
  /**
   * WHICH OF THE THREE INCH BOXES STILL HOLDS A MACHINE'S NUMBER, and what produced it.
   *
   * Written only by an accept button, cleared by a keystroke in the box it describes, and read once —
   * by `measurementMethodsFor` while the save body is built. It holds the accepted TEXT beside the
   * marker, which is the whole mechanism: see `components/forms/measurementMethods.ts` for why a
   * marker that outlives the number it describes is worse than no marker at all.
   *
   * THE UNIT-LESS `height` IS NEVER IN HERE. It is not in `DIMENSION_FIELDS`, so a marker naming it
   * is a 422 on the whole save rather than a dropped hint — `rememberAcceptance` refuses the key
   * itself, which is the guard that matters on THIS form specifically, because `height` is one
   * plausible typo away from `heightInches`.
   *
   * EMPTY ON AN EDIT FORM, deliberately. A stored dimension arrives with no marker in the payload —
   * its method, if it ever had one, is already in the record's own provenance — and this form has no
   * grounds to make a fresh claim about a number it did not watch anybody produce.
   */
  const [accepted, setAccepted] = useState<AcceptedMeasurements>(NO_ACCEPTED_MEASUREMENTS);
  const [gridFiles, setGridFiles] = useState<GridFiles>({});
  /**
   * The photograph the DETERMINISTIC panel measured from, and whether its reference was a grid.
   *
   * Kept beside `gridFiles` rather than inside it because the two are different evidence: a grid file
   * is a photograph a model was asked to read, and this one is a photograph a person marked. Both are
   * stored with the record — the number is worthless to a later reader without the frame it came off.
   */
  const [measurePhoto, setMeasurePhoto] = useState<{ file: File; isGrid: boolean } | null>(null);
  /*
    ONE SENTENCE, TWO BOXES, AND IT IS REFERENCED BY BOTH.

    THE QUESTION IT ANSWERS CHANGED ON 2026-09-15 AND THE OLD ANSWER IS QUOTED RATHER THAN DELETED.
    It used to read: *"Two boxes both labelled with the word 'Height' on one form is a question a
    researcher cannot answer from the labels"* — and the paragraph it pointed at told them to fill
    one of the two and leave the other alone. That instruction is now REVERSED: the two boxes are the
    same measurement in two units and filling either fills the other. The sentence is still needed,
    and needed by BOTH boxes, for a different reason — a researcher has to know that the number
    appearing in the box they did not type in was computed, and that a record saved before the
    pairing existed may hold two figures that disagree.

    `aria-describedby` on BOTH inputs — not one — because a reader who tabs into either one has
    exactly the same question. `useId` rather than a literal so the attribute cannot collide if this
    form is ever mounted twice on a page.

    IT NAMES THE HEIGHT PAIR AND NOT THE WIDTH ONE, deliberately. The paragraph describes all four
    boxes by label, and pointing `width` / `breadthInches` at it as well would double the count this
    form's parity spec asserts without telling a reader anything the text does not already say.

    It lives OUTSIDE the two `Field`s deliberately: `Field` is a `<label>`, and a `<p>` is not
    phrasing content, so nesting it there is invalid markup AND folds the whole sentence into each
    box's accessible name — a screen reader would read the paragraph twice before saying "Height".
  */
  const heightHelpId = useId();
  /**
   * The craft and artisan dropdowns' contents, and what they are NOT showing.
   *
   * Shared with ProductForm, which asks the identical question and had the identical defect in it —
   * see `forms/recordPickers` for the three requests this makes and for the 100-row ceiling that
   * made the second and third ones necessary. `referenceState` still means what it did ("can I see
   * this artisan?" and "is there any signal?" are different answers, and `useCarryContext` treats
   * them differently); it lives in the hook only because it is settled by the same load.
   */
  const {
    artisans,
    crafts,
    referenceState,
    craftCut,
    craftArtisanCut,
    artisansLoadedForCrafts
  } = useCraftAndArtisanOptions({ craftIds, artisanIds });
  /**
   * EVERY ONE OF THIS TOOL'S OWN CRAFTS IS ALWAYS AN OPTION, wherever each of them sorts.
   *
   * The hook above does the by-id rescue for the ARTISANS and for nobody else. `GET /crafts` is
   * clamped to 100 rows and ordered NAME ASCENDING (deliberately — `routes/crafts.py:82-87`), so the
   * cut is stable and always falls in the same place: every tool of a craft whose name sorts past it
   * opens with that craft simply missing from the picker beside a REQUIRED "Craft name" box holding
   * the right name. The stored link is intact and would be saved untouched — but the form says it is
   * not, and the obvious repair for a craft that looks unlinked is to pick one, which is the single
   * action that really does rewrite the links.
   *
   * PLURAL, AND THAT IS THE WHOLE POINT OF THE CHANGE. A multi-select has one such craft per ticked
   * id, not one in total, so rescuing only `craftIds[0]` would leave a tool linked to three crafts
   * drawing two blank chips. `useRecordsOffPage` asks once per id with no loaded row.
   */
  const offPageCrafts = useRecordsOffPage<Craft>("/crafts", craftIds, crafts);
  const craftOptions = useMemo(
    () => (offPageCrafts.length ? mergeById(crafts, offPageCrafts) : crafts),
    [crafts, offPageCrafts]
  );
  const { dirty, markDirty, resetDirty } = useUnsavedChanges();
  const [backPromptOpen, setBackPromptOpen] = useState(false);
  // Hands the prompt to the round back control in the page header, which is now the only back
  // control on the page.
  useLeaveGuard(dirty, () => setBackPromptOpen(true));
  // The API includes the record's stored location (not yet in the TS type); pass it so the edit
  // form pre-fills coordinates instead of auto-capturing the editor's current position.
  const initialLocation = initial
    ? ((initial as ToolDocumentation & { location?: LocationInitialValues | null }).location ?? null)
    : undefined;
  const isEdit = Boolean(initial);
  // The workshop this tool was documented at: shared picker, shared most-recent defaulting, and the
  // late-submission gate (see components/forms/WorkshopSelect).
  const workshop = useWorkshopSelection({ initialWorkshopId: initial?.workshopId, isEdit, resetKey: initial?.id ?? null });

  const toNum = (value: string) => {
    const n = Number(value);
    return value.trim() && Number.isFinite(n) ? n : null;
  };

  /**
   * AN INCH BOX A PERSON IS TYPING IN, which is three facts and not one: the new text, that whatever
   * a machine proposed into this box is no longer what it holds, and — for the two that have one —
   * the centimetre partner that has to follow.
   *
   * A marker is a claim about how THIS number was obtained, so a researcher who accepts a geometry
   * reading and then edits the box has left a `PHOTO_GEOMETRY` claim standing over a typed number —
   * a false statement in a record an auditor cannot check, and strictly worse than the `UNRECORDED`
   * an absent marker earns. `forgetAcceptance` returns the same object when there is nothing to
   * forget, so this costs no re-render on a form nobody has measured on.
   *
   * IT IS THE SECOND OF TWO GUARDS AND NOT THE LOAD-BEARING ONE. `measurementMethodsFor` at the
   * payload re-checks each box against the accepted text regardless of how it came to differ; this
   * handler is what additionally catches a person typing the identical digits back by hand, which is
   * the one case value-equality cannot see.
   *
   * THE CENTIMETRE BOXES DO NOT GO THROUGH IT, and that is not an omission: nothing machine-produced
   * can land in `height` or `width` (see MEASURE_COLUMNS), so there is never an acceptance to
   * forget, and wiring them through would suggest there could be. They have their own factory below.
   *
   * ── THE THIRD ARGUMENT IS THE CENTIMETRE PARTNER, AND IT IS OPTIONAL BECAUSE ONE BOX HAS NONE ──
   * `heightInches` pairs with `height`, `breadthInches` pairs with `width`, and `lengthInches` is
   * STANDALONE — it gets no centimetre partner and no new column, so it passes none.
   *
   * A FACTORY RATHER THAN THREE INLINE HANDLERS, which it already was: the two things a keystroke in
   * a dimension box means are the same three times over, and a handler written out per box is three
   * chances to forget the second of them.
   */
  const typeInches =
    (set: (value: string) => void, key: string, setPartner?: (value: string) => void) =>
    (event: React.ChangeEvent<HTMLInputElement>) => {
      set(event.target.value);
      setAccepted((current) => forgetAcceptance(current, key));
      if (setPartner) propagate(event.target.value, cmTextFromInches, setPartner);
    };

  /**
   * A CENTIMETRE BOX A PERSON IS TYPING IN, which writes its INCH partner and nothing else.
   *
   * ONE-DIRECTIONAL, PER KEYSTROKE. The box being typed in writes its partner; the partner never
   * writes back. That is the ONLY shape that cannot lose a value: a watcher over both states fires
   * for whichever one changed, so 1 cm becomes 0.39 in becomes 0.99 cm and a round trip eats the
   * number. React's `setState` does not re-invoke the target input's `onChange`, so writing the
   * partner from inside the source's handler is already one-directional — it is spelled out because
   * the `useEffect` version reads like a tidy-up and is the way this gets broken by accident.
   *
   * NO ACCEPTANCE IS FORGOTTEN HERE, and that is deliberate rather than an oversight: these two
   * columns are not in `DIMENSION_FIELDS`, so `forgetAcceptance` is a no-op for them — but calling
   * it would advertise a relationship that does not exist. The centimetre boxes carry no provenance.
   *
   * A partially typed decimal is a number, not a mistake: "1." parses and converts, so the partner
   * tracks the keystrokes instead of blanking and refilling. Only a string that cannot be a number at
   * all leaves the partner alone — and only an EMPTY box clears it, because empty is the one input
   * that means "no value". The rule itself lives in `forms/dimensionUnits.propagate`, where a test
   * can drive it.
   */
  const typeCm =
    (set: (value: string) => void, setPartner: (value: string) => void) =>
    (event: React.ChangeEvent<HTMLInputElement>) => {
      set(event.target.value);
      propagate(event.target.value, inchesTextFromCm, setPartner);
    };

  // Offer the sitting this researcher was last working in, however they got here — the query string
  // only survives a click straight through from the save screen (lib/carryContext). The TOOL in the
  // bag is this form's own subject and is never applied here; a product or process in it belongs to
  // other forms and is left alone rather than dropped, so they still have it.
  const carry = useCarryContext({
    enabled: !isEdit,
    // Both dropdowns are built from exactly these two lists, so "absent from the list" is both
    // "you can no longer reach it" and "this form could not show it" — one check answers both.
    // `craftOptions`, not `crafts`: a carried craft that is merely off the picker's first page IS
    // reachable — the by-id lookup fetched it — and pruning it would drop a perfectly good link from
    // the bag for the same "absent from page one" reason the artisan side already corrects.
    scopes: [carryScope("artisan", referenceState, artisans), carryScope("craft", referenceState, craftOptions)],
    // This form has no product, tool or process field, so it neither fills those in nor lets the
    // banner claim it did — they stay in the bag for the forms that do.
    applies: ["craft", "artisan", "workshop"],
    // THE BAG HOLDS ONE CRAFT AND ONE ARTISAN, AND IT STAYS THAT WAY. A "sitting" is one craft and
    // one artisan in one courtyard — six other forms read the same bag and a banner claiming a LIST
    // would change what it says on all of them. A carried record therefore seeds a one-element
    // selection here, which the researcher adds to. It does NOT touch the English name: a carried
    // craft is not somebody typing in that box.
    onApply: (context) => {
      if (context.craftId) setCraftIds([context.craftId]);
      if (context.craftName) setCraftName(context.craftName);
      if (context.artisanId) setArtisanIds([context.artisanId]);
      if (context.artisanName) setArtisanName(context.artisanName);
      if (context.place) setPlace(context.place);
      if (context.workshopId && !workshop.touched) workshop.setWorkshopId(context.workshopId);
    }
  });

  /**
   * "Change": drop every carried value so the researcher picks from scratch.
   *
   * IT DOES NOT TOUCH `englishName`, AND THAT IS A RULE RATHER THAN AN OMISSION. This control
   * dismisses a GUESS about which craft and artisan the researcher is sitting with; the English name
   * is neither carried nor guessed, and clearing it here would be this form deleting something
   * somebody typed in answer to a question nobody asked. The toolkit name is untouched for the same
   * reason, so the mirror has nothing to do either.
   */
  function clearCarriedContext() {
    carry.change();
    setCraftIds([]);
    setCraftName("");
    setArtisanIds([]);
    setArtisanName("");
    setPlace("");
  }

  /**
   * THE ARTISANS THE PICKER OFFERS: everybody who practises any TICKED craft, plus anybody already
   * ticked, ordered by craft name A→Z and then by artisan name A→Z.
   *
   * ── WHY THE ALREADY-TICKED ARE UNCONDITIONALLY IN ─────────────────────────────────────────────
   * Same rule the single-select had ("keeping any pre-existing selection"), and it matters more now:
   * an artisan whose `craftId` this client cannot see, or who was linked before their craft row
   * changed, would otherwise vanish from a multi-select that is DRAWING them as ticked — and a
   * `MultiSelectDropdown` cannot show a value it has no option for. Dropping a link has to be an act
   * the researcher performs, never a side effect of what a page happened to load.
   *
   * ── AND WHY THE ORDER IS COMPUTED HERE RATHER THAN TAKEN FROM THE SERVER ──────────────────────
   * `GET /artisans` orders `createdAt desc` and says so; no client may depend on that. The A→Z rule
   * lives in `forms/recordPickers` because the handset has to produce the identical order from the
   * identical rows — see `sortArtisansByCraft` for the collation rules and why neither client may
   * reach for `localeCompare`.
   */
  const selectedCrafts = useMemo(
    () => craftOptions.filter((craft) => craftIds.includes(craft.id)),
    [craftOptions, craftIds]
  );
  const artisansForCrafts = useMemo(() => {
    const offered = craftIds.length
      ? artisans.filter((artisan) => (artisan.craftId && craftIds.includes(artisan.craftId)) || artisanIds.includes(artisan.id))
      : artisans.filter((artisan) => artisanIds.includes(artisan.id));
    return sortArtisansByCraft(offered, selectedCrafts);
  }, [artisans, artisanIds, craftIds, selectedCrafts]);

  /**
   * The craft names joined ", " in tick order — what the server will store in `tool.craftName`.
   *
   * WRITTEN INTO THE BOX ON EVERY SELECTION CHANGE THAT LEAVES A CRAFT TICKED, so the box never
   * shows something other than what will be saved. The accepted cost, stated once so nobody
   * re-litigates it: a hand correction typed into "Craft name" is lost while crafts are linked,
   * because the server derives that column from `craftIds` alone — a queued body replayed a
   * fortnight later must still produce a `craftName` that agrees with its links. The honest future
   * fix is an explicit override flag, not a heuristic that compares against the previous value.
   *
   * UNTICKING THE LAST CRAFT DOES NOT BLANK THE BOX, and that is the same rule the single-select had
   * ("Unlinked / type below" never cleared the name). With no links the server stores whatever the
   * body carries, so the box IS still showing what will be stored — and "Craft name" is REQUIRED, so
   * emptying it would make an untick delete a mandatory answer and refuse the save until it was
   * retyped, over a craft nothing else on the form now records.
   */
  function joinCraftNames(ids: readonly string[]) {
    return ids
      .map((id) => craftOptions.find((craft) => craft.id === id)?.name)
      .filter(Boolean)
      .join(", ");
  }

  /**
   * "ARTISAN NAME" AND "PLACE" FOLLOW THE FIRST ARTISAN — the one writer both routes into this go
   * through, because there are two of them and they used to disagree.
   *
   * `tool.artisanId` is element 0 of the selection; `tool.artisanName` and `tool.place` are single
   * NOT NULL columns describing that person. Two gestures move element 0 — untick the head in the
   * artisan picker, or untick the CRAFT that brought them in — and only the first one refilled the
   * boxes. The end state of the two is identical and the stored row was not: a craft untick left
   * `artisanId = W1` beside `artisanName = "P1"`, so every report, export and record sheet printed
   * one person's name over another's link, with nothing on screen saying so. One helper, called from
   * both, is what stops a third writer forgetting again.
   *
   * ── ONLY WHEN THE HEAD ACTUALLY CHANGES, WHICH IS THE OTHER HALF OF THE RULE ─────────────────
   * The server does NOT derive these two from `artisanIds` — `routes/tools.py` says so in as many
   * words, because both are things a researcher legitimately corrects by hand ("A. Khatri" →
   * "Abdul Khatri"; "Bhuj" → "Bhuj, Kutch") and the body's values stand. This helper used to run on
   * EVERY toggle, so correcting Place by hand and then ticking a SECOND artisan silently reverted
   * the box to the first artisan's stored value — a promise the wire contract had just made, broken
   * by the form. A toggle that leaves element 0 where it was writes nothing.
   *
   * ── AND TWO CASES WHERE IT DELIBERATELY WRITES NOTHING ───────────────────────────────────────
   * An EMPTY selection leaves both boxes standing: they are required, the record still has to name
   * somebody, and "no link, the name retained" is the same rule the craft name box follows when the
   * last craft is unticked. A new head this page cannot SEE (an off-page row whose by-id rescue was
   * refused) leaves them standing too — the form has no row to copy, and inventing one is worse than
   * a name the researcher can still correct.
   */
  function syncArtisanColumns(next: readonly string[], previous: readonly string[]) {
    const head = next[0] ?? "";
    if (!head || head === (previous[0] ?? "")) return;
    const first = artisans.find((artisan) => artisan.id === head);
    if (!first) return;
    setArtisanName(first.name);
    setPlace(first.place);
  }

  /**
   * A CRAFT WAS TICKED OR UNTICKED.
   *
   * Preserves the SPIRIT of the single-select's `craftChangeClearsArtisan` — which this form no
   * longer calls, and `ProductForm` still does — in the one shape a multi-select allows: deselecting
   * a craft drops exactly the artisans this form KNOWS practise only a craft THAT GESTURE REMOVED,
   * and keeps every other one. An artisan the page cannot see is never dropped: "not on the list"
   * and "not of that craft" are different observations, and reading the first as the second is the
   * silent link deletion that rule exists to stop. The rule itself is in `forms/recordPickers`,
   * where a test can reach it and where the Kotlin twin's assertions mirror it one for one.
   *
   * ── THE REMOVED CRAFTS ARE COMPUTED HERE BECAUSE ONLY HERE KNOWS THEM ────────────────────────
   * The rule is handed `removedCraftIds` and not just the next selection, and that argument is the
   * difference between "drop this craft's artisans" and "drop everyone whose craft is not ticked".
   * The tool's artisans do not all arrive through this picker — "Assign a tool to multiple artisans"
   * links anybody, of any craft — so the second reading deleted a potter's assignment when a
   * researcher unticked Block printing. `craftIds` is the PREVIOUS selection (state, not yet
   * replaced), so the difference is exact rather than a length comparison.
   *
   * ── AND THE SURVIVORS ARE COMPUTED BEFORE THEY ARE SET ───────────────────────────────────────
   * `setArtisanIds(kept)` rather than a functional update, because the new head has to be read out
   * of `kept` in the same breath: dropping the FIRST artisan promotes a new one, and the two columns
   * that describe them follow through `syncArtisanColumns` exactly as they do when the artisan
   * picker itself drops the head.
   */
  function onCraftsChanged(next: string[]) {
    const removed = craftIds.filter((id) => !next.includes(id));
    if (removed.length) {
      const dropped = craftsChangeClearsArtisans({
        nextCraftIds: next,
        removedCraftIds: removed,
        artisanIds,
        artisans
      });
      if (dropped.length) {
        const kept = artisanIds.filter((id) => !dropped.includes(id));
        setArtisanIds(kept);
        syncArtisanColumns(kept, artisanIds);
      }
    }
    setCraftIds(next);
    const joined = joinCraftNames(next);
    if (joined) setCraftName(joined);
    markDirty();
  }

  function handleBack() {
    if (dirty) setBackPromptOpen(true);
    else router.back();
  }

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    // Read the form synchronously: React nulls event.currentTarget across the await below.
    const form = new FormData(event.currentTarget);
    // A workshop that has already ended makes this a late submission needing admin approval — say so
    // before anything is written. Resolves true immediately when there is nothing to warn about.
    if (!(await workshop.confirmSubmission())) return;
    setSaving(true);
    setError(null);
    try {
      const exifItems = await collectExifMetadata(
        [...Object.values(gridFiles), measurePhoto?.file, ...stageFiles, ...mediaFiles].filter(Boolean) as File[]
      );
      const exifRemark = exifMetadataToRemark(exifItems);
      const recordedAt = recordedAtFromForm(form);
      const recordedTimezone = recordedTimezoneFromForm(form);
      const location = locationFromForm(form);
      /*
        ── DID EITHER PICKER ACTUALLY CHANGE? ────────────────────────────────────────────────────
        Compared against `storedCraftIds` / `storedArtisanIds` — the SAME functions the two pickers
        were seeded from, so "unchanged" here means exactly "nobody touched the control". Set
        comparison and not sequence: `SearchableMultiSelect` hands back tick order while the record's
        links come back in the join table's own, so comparing order would report a change nobody
        made. Always true on a CREATE — there is no stored list to differ from, and the create path
        has no such check to trip.
      */
      const craftLinksChanged = !initial || !sameIdSet(craftIds, storedCraftIds(initial));
      const artisanLinksChanged = !initial || !sameIdSet(artisanIds, storedArtisanIds(initial));
      const payload = {
        craftName: requiredText(form, "craftName"),
        place: requiredText(form, "place"),
        artisanName: requiredText(form, "artisanName"),
        toolkitName: requiredText(form, "toolkitName"),
        localName: textValue(form, "localName"),
        englishName: textValue(form, "englishName"),
        processUsedIn: textValue(form, "processUsedIn"),
        material: textValue(form, "material"),
        yearsInUse: numericValue(form, "yearsInUse"),
        height: toNum(height),
        // `toNum` and not `numericValue`, for the same reason as `heightInches` below: the box is
        // controlled now — it is the centimetre partner of `breadthInches` and a partner box is
        // written by code — and reading it from state is the single source that cannot disagree with
        // what is on screen.
        width: toNum(width),
        lengthInches: toNum(length),
        breadthInches: toNum(breadth),
        // The unit-bearing height. `toNum` and not `numericValue` for the same reason as the three
        // above it: the box is controlled so the grid panel can fill it, and a controlled input's
        // value still reaches FormData — but reading it from state is the single source and cannot
        // disagree with what is on screen.
        heightInches: toNum(heightInches),
        /*
          ── HOW EACH OF THE THREE INCH DIMENSIONS ABOVE WAS MEASURED ────────────────────────────
          `{"lengthInches": {"method": "PHOTO_GEOMETRY", "technique": "SCALE"}}` for a reading
          accepted out of `RecordPhotoMeasure`, or the vision model's own `methodMarker` echoed back
          verbatim for one accepted out of `GridMeasurement`. `records.merge_field_provenance` pops
          the key — it is not a column — and merges the method INTO the `{by, byName, at}` stamp it
          was already writing, so the row reads *a vision model estimated this, and this person
          accepted it into the record at that moment* instead of asserting they measured it by hand.

          THE UNIT-LESS `height` ABOVE IS NEVER NAMED IN HERE. It is not in the server's
          `DIMENSION_FIELDS`, so a marker naming it is a 422 on the whole save; nothing machine-
          produced lands there any more (see MEASURE_COLUMNS) and `rememberAcceptance` refuses the key
          even if something tried.

          ── THIS KEY IS ONLY SENDABLE ONCE THE SERVER DECLARES IT ───────────────────────────────
          `ToolCreate` / `ToolUpdate` share an `APIModel` that is `ConfigDict(extra="forbid")`, so a
          body carrying a key the schema does not declare is rejected 422 IN FULL — and `saveOrQueue`
          will not queue a 4xx, so the record would be neither saved nor retried. Re-check both
          halves before trusting this paragraph:

            grep -n "MARKER_BODY_KEY" backend/app/services/access.py
            grep -n "measurementMethods" backend/app/schemas/records.py

          ── WHAT MAY BE IN IT, WHICH IS LESS THAN WHAT WAS ACCEPTED ─────────────────────────────
          `measurementMethodsFor` emits a marker ONLY for a box still holding the exact text the
          route proposed. Typed over, cleared, or never accepted and the key is simply not there —
          the server reads absence as `UNRECORDED`, which is honest and is never the false human
          claim. `undefined` and not `null` when there is nothing to say, so the key leaves the
          `JSON.stringify` entirely and a save with no machine measurement is byte-for-byte the save
          this form has always sent.
        */
        measurementMethods: measurementMethodsFor(accepted, {
          lengthInches: length,
          breadthInches: breadth,
          heightInches
        }),
        thickness: numericValue(form, "thickness"),
        weight: numericValue(form, "weight"),
        radius: numericValue(form, "radius"),
        maker: requiredText(form, "maker") || "UNKNOWN",
        traditionType: requiredText(form, "traditionType") || "UNKNOWN",
        replacementCost: numericValue(form, "replacementCost"),
        suggestionsForToolImprovement: textValue(form, "suggestionsForToolImprovement"),
        // `appendStoredParagraph` and NOT `appendRemarksWithExif`: remarks is a rich-text editor
        // now, so this column may hold a JSON document, and concatenating the EXIF summary onto the
        // end of a JSON string produces a value that is neither valid JSON nor readable prose. The
        // helper appends INTO the document when there is one and is byte-for-byte the old behaviour
        // when there is not.
        remarks: appendStoredParagraph(textValue(form, "remarks") as string | null, exifRemark),
        artisanId: artisanId || null,
        craftId: craftId || null,
        /*
          ── EVERY LINKED CRAFT AND ARTISAN, AS TWO ORDERED LISTS, WHEN THEY CHANGED ───────────
          Not columns: the route pops them and writes `ToolCraft` / `ToolArtisan` rows, and DERIVES
          `craftId` / `artisanId` from element 0 when the list is non-empty — so the two scalars
          above are sent as well, agree with these by construction, and keep every existing filter,
          index, report and carry-forward reading exactly what they read before. `craftName` is
          derived from the craft NAMES in the same order, which is why the box on screen is written
          from `joinCraftNames` on every change.

          ORDER IS THE CONTRACT. The server preserves it and returns `craftLinks` in it; this is the
          researcher's own tick order and nothing here re-sorts it.

          `[]` AND ABSENT ARE DIFFERENT, AND THAT DISTINCTION IS WHAT THE DIFF ABOVE RESTS ON. An
          omitted key means "leave the stored links alone" and `[]` means "no links". An emptied
          picker still DIFFERS from the stored list, so it still travels as `[]` and still deletes
          the rows; a picker nobody opened travels not at all. NEVER `null`: the schema refuses an
          explicit null by name, because null and absent would otherwise be indistinguishable on a
          PATCH. `undefined` is how a key is left out — it leaves `JSON.stringify` entirely, the same
          mechanism `measurementMethods` above relies on, and it leaves a replayed outbox body with
          it.

          ── WHY AN UNCHANGED LIST IS NOT SENT: A PERMISSION FIX AND A RACE FIX ────────────────
          `PATCH /tools/{id}` re-checks each list it is SENT. For a caller who is not an admin, not
          the tool's author and holds no EDIT grant, `assert_can_contribute_relation(..., populated =
          count > 0, ...)` refuses ANY send against an already-populated relation — *"Only the
          original contributor or an admin can change populated relation: craftIds"* — even when
          the list sent is identical to the one stored. Sending both unconditionally therefore
          answered a contributor who had typed into "Material" with a refusal about a picker they
          never opened, and `saveOrQueue` will not queue a 4xx, so they lost the form. Worse than
          lost: that check sits AFTER `db.tooldocumentation.update(...)` and after a COMMITTED
          `RecordRevision`, so the row was already written and the ledger already stamped underneath
          the 403. The foreign-artisan gate (`_may_manage_tool_links`, which has no rank clause)
          fires earlier still and loses a professor's save outright when somebody else created one of
          the linked artisans.

          AND THE RACE: between mount and Save a colleague may assign this tool to two more artisans
          through "Assign a tool to multiple artisans". The list this form seeded at mount knows
          nothing about them, and `_replace_artisan_links` is delete-all-then-create — so
          re-sending it deleted their rows under a 200. A picker nobody opened now replaces nothing.

          The same shape as `app/(protected)/workshops/page.tsx`, which diffs its two rosters this
          way for the first of those two reasons; `sameIdSet` is shared with it.

          ── AND AN OLDER API REFUSES BOTH KEYS BY NAME ────────────────────────────────────────
          `APIModel` is `ConfigDict(extra="forbid")`, so a body carrying a key the schema does not
          declare is a 422 IN FULL — and `saveOrQueue` will not queue a 4xx, so the researcher would
          lose the form rather than retry it. The web deploys to Vercel and the API to EC2
          separately, so these two keys may not ship ahead of the server that declares them:

            grep -n "craftIds" backend/app/schemas/records.py
        */
        craftIds: craftLinksChanged ? craftIds : undefined,
        artisanIds: artisanLinksChanged ? artisanIds : undefined,
        workshopId: workshop.workshopId || null,
        // Below professor no status control is rendered: create submits PENDING, edit resubmits the
        // current status (the backend drops unauthorized changes either way).
        status: requiredText(form, "status") || initial?.status || "PENDING",
        recordedAt,
        recordedTimezone,
        location,
        // extraMetadata stays programmatic (EXIF etc.) — the raw JSON textarea was removed.
        extraMetadata: exifItems.length ? { mediaExif: exifItems } : {}
      };
      // Offline this queues instead of failing. Four groups, four batches: the measurement grids, the
      // photograph the on-device panel measured from, the numbered process-stage captures and the
      // general field media each keep their own caption, because the caption is the only thing that
      // says which photo is which.
      const outcome = await saveOrQueue<ToolDocumentation>({
        label: `Tool · ${payload.toolkitName || "Untitled"}`,
        endpoint: initial ? `/tools/${initial.id}` : "/tools",
        method: initial ? "PATCH" : "POST",
        body: payload,
        media: [
          ...(Object.entries(gridFiles) as [GridGroup, File][]).map(([group, file]) => ({
            files: [file],
            linkedRecordType: "tool",
            caption: `${group === "lengthBreadth" ? "Length & breadth" : "Height"} grid (measurement) for ${payload.toolkitName || "tool"}`,
            location,
            recordedAt,
            recordedTimezone,
            transcribeAudio: false
          })),
          /*
            THE PHOTOGRAPH THE ON-DEVICE PANEL MEASURED FROM, kept with the record for the same reason
            the grid shots are: a dimension whose evidence is gone is a number nobody can re-derive,
            and re-deriving it is the whole advantage this route has over the vision model.
          */
          ...(measurePhoto
            ? [
                {
                  files: [measurePhoto.file],
                  linkedRecordType: "tool",
                  caption: `Measured-from photo${measurePhoto.isGrid ? " (grid)" : ""} for ${payload.toolkitName || "tool"}`,
                  location,
                  recordedAt,
                  recordedTimezone,
                  transcribeAudio: false
                }
              ]
            : []),
          ...stageFiles.map((file, index) => ({
            files: [new File([file], `STAGE_STEP_${index + 1}_${file.name}`, { type: file.type, lastModified: file.lastModified })],
            linkedRecordType: "tool",
            caption: `Process stage step ${index + 1} for ${payload.toolkitName || "tool"}`,
            location,
            recordedAt,
            recordedTimezone,
            transcribeAudio: false
          })),
          {
            files: mediaFiles,
            linkedRecordType: "tool",
            caption: `Field media for ${payload.toolkitName || "tool"}`,
            location,
            recordedAt,
            recordedTimezone,
            extraMetadata: exifItems.length ? { mediaExif: exifItems } : undefined
          }
        ]
      });
      // Bank the sitting the moment the record is accepted, so the next form opened from the
      // dashboard already knows where the researcher is.
      //
      // ONE CRAFT'S OWN NAME, NEVER THE JOINED STRING. `payload.craftName` is every linked craft
      // joined ", " now, and the bag holds ONE craft — six other forms prefill from it and the
      // banner prints it as a single name, so banking "Bandhani, Block printing" would read as one
      // craft called that. The first ticked craft is the one the scalar `craftId` records, so its
      // own name is the one that agrees with the id beside it. Falls back to the payload for a tool
      // saved with no craft linked, where the box holds a hand-typed name and nothing else does.
      const sitting = {
        artisanId,
        artisanName: payload.artisanName,
        place: payload.place,
        craftId,
        craftName: craftOptions.find((craft) => craft.id === craftId)?.name ?? payload.craftName,
        workshopId: workshop.workshopId,
        workshopName: workshop.workshops.find((w) => w.id === workshop.workshopId)?.title ?? null
      };
      if (outcome.queued) {
        // Offline is the normal case, but a queued tool has no id yet, so nothing can be assigned to
        // it. Whatever tool was in the bag is dropped rather than left to stand in for the one just
        // recorded — an old tool offered under a new one's name is a wrong link.
        carry.prune("tool");
        carry.remember(sitting);
        // OutboxBanner at the top of the page names the entry and says where it lives.
        resetDirty();
        if (typeof window !== "undefined") window.scrollTo({ top: 0, behavior: "smooth" });
        setSaving(false);
        return;
      }
      const saved = outcome.saved;
      // The tool itself now joins the bag, so "assign this tool to more artisans" opens with the
      // tool already picked instead of hunting it out of a dropdown of seventy.
      carry.remember({ ...sitting, toolId: saved.id, toolName: saved.toolkitName });
      // Store each captured grid photo as media linked to the tool (the measured value is already in
      // the field). Best-effort per file so one failure doesn't lose the record.
      for (const [group, file] of Object.entries(gridFiles) as [GridGroup, File][]) {
        try {
          await uploadMediaFile({
            file,
            linkedRecordType: "tool",
            linkedRecordId: saved.id,
            caption: `${group === "lengthBreadth" ? "Length & breadth" : "Height"} grid (measurement) for ${saved.toolkitName}`,
            location,
            recordedAt,
            recordedTimezone,
            transcribeAudio: false
          });
        } catch {
          /* keep the saved record even if a grid photo fails to store */
        }
      }
      // The same, for the photograph the on-device panel measured from. Best-effort for the same
      // reason: the dimension and its marker are already saved, and losing the evidence photo must
      // not lose the record.
      if (measurePhoto) {
        try {
          await uploadMediaFile({
            file: measurePhoto.file,
            linkedRecordType: "tool",
            linkedRecordId: saved.id,
            caption: `Measured-from photo${measurePhoto.isGrid ? " (grid)" : ""} for ${saved.toolkitName}`,
            location,
            recordedAt,
            recordedTimezone,
            transcribeAudio: false
          });
        } catch {
          /* keep the saved record even if the measurement photo fails to store */
        }
      }
      // Android parity: each process-stage capture is stored as a numbered step (STAGE_STEP_n).
      const stageFailed: string[] = [];
      for (const [index, file] of stageFiles.entries()) {
        try {
          await uploadMediaFile({
            file: new File([file], `STAGE_STEP_${index + 1}_${file.name}`, { type: file.type, lastModified: file.lastModified }),
            linkedRecordType: "tool",
            linkedRecordId: saved.id,
            caption: `Process stage step ${index + 1} for ${saved.toolkitName}`,
            location,
            recordedAt,
            recordedTimezone
          });
        } catch {
          stageFailed.push(file.name);
        }
      }
      if (stageFailed.length) {
        setError(
          `${stageFailed.length} process stage file(s) failed to upload: ${stageFailed.join(", ")}. ` +
            "The tool record was saved; re-open it to retry those files."
        );
        setSaving(false);
        return;
      }
      if (mediaFiles.length) {
        const { failed } = await uploadMediaBatch({
          files: mediaFiles,
          linkedRecordType: "tool",
          linkedRecordId: saved.id,
          caption: `Field media for ${saved.toolkitName}`,
          location,
          recordedAt,
          recordedTimezone,
          extraMetadata: exifItems.length ? { mediaExif: exifItems } : undefined,
          onProgress: setUploadProgress
        });
        setUploadProgress(null);
        if (failed.length) {
          setError(`${failed.length} of ${mediaFiles.length} file(s) failed to upload: ${failed.map((f) => f.name).join(", ")}. The record was saved; re-open it to retry those files.`);
          setSaving(false);
          return;
        }
      }
      resetDirty();
      router.push("/tools");
      router.refresh();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to save tool record");
    } finally {
      setSaving(false);
      setUploadProgress(null);
    }
  }

  return (
    <>
      <form ref={formRef} onSubmit={submit} onInput={markDirty} onKeyDown={handleFormEnter} className="panel grid gap-4 p-4">
        {error ? <div className="rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div> : null}
        <CarryContextBanner offer={carry.applied} onChange={clearCarriedContext} />
        {/*
          THE ONE PLACE THIS FORM EXPLAINS A MISSING MICROPHONE — see `DictationUnavailableNotice`.
          Every dictated control below passes `explainWhenUnavailable={false}`, including the two
          rich-text editors, because on Firefox the same honest paragraph printed eleven times down
          one form is a block of grey text nobody reads. This form carries more microphones than any
          other screen in the app, which is exactly why it may not repeat itself.

          ABOVE the grid and not inside it: the grid is up to three columns, so a paragraph mounted
          as one of its children would be a column-wide sliver beside the workshop picker.
        */}
        <DictationUnavailableNotice />
        <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-3">
          {/* Android parity (ToolForm): the workshop opens the form, because it is the context
              every other answer belongs to — not merely the first dropdown. */}
          <WorkshopSelect state={workshop} onDirty={markDirty} saving={saving} />
          {/* Toolkit/English/craft/artisan names and place are title-cased by the API on write
              (`backend/app/services/records.py:339-354`), so the box says what will actually be
              stored — `titleCased` mounts `TitleCasedInput` itself inside the dictated box, never a
              copy of its hint. Local name is NOT: it is Devanagari/Gujarati, where capitalising means
              nothing. `markDirty()` BY HAND in every `onChange` below: a dictated phrase is a React
              state write and fires no native `input` event for the form's `onInput` to catch. */}
          {/* Every write of this box goes through `applyToolkitName`, which also fills "English name"
              while the mirror is armed — see that helper for the one-way door and for why the mirror
              is here rather than in an effect. A dictated phrase arrives through the same `onChange`,
              so speaking a toolkit name mirrors exactly as typing one does. */}
          <DictatedTextInput
            name="toolkitName"
            label="Toolkit name"
            required
            titleCased
            explainWhenUnavailable={false}
            value={toolkitName}
            onChange={(next) => applyToolkitName(next, { user: true })}
          />
          <DictatedTextInput
            name="localName"
            label="Local name"
            explainWhenUnavailable={false}
            value={localName}
            onChange={(next) => {
              setLocalName(next);
              markDirty();
            }}
          />
          {/* TOUCHING THIS BOX ENDS THE MIRROR, PERMANENTLY, AND CLEARING IT COUNTS.
              Every route into this handler is a person: a keystroke, a paste, a dictated phrase, or
              backspacing it to nothing. Each of them is the researcher saying the English name is
              theirs, so the latch drops here and nothing re-arms it for the life of this form — an
              emptied box then STAYS empty rather than refilling itself on the next keystroke in
              "Toolkit name". The mirror's own write does not come through here and therefore cannot
              disarm anything. */}
          <DictatedTextInput
            name="englishName"
            label="English name"
            titleCased
            explainWhenUnavailable={false}
            value={englishName}
            onChange={(next) => {
              setEnglishName(next);
              mirrorArmed.current = false;
              markDirty();
            }}
          />
          {/*
            A MULTI-SELECT, BECAUSE ONE DOCUMENTED TOOL GENUINELY COVERS SEVERAL CRAFTS.
            `tool.craftId` keeps the FIRST of them and `tool.craftName` every name joined ", " in
            this order; the `ToolCraft` join table holds all of them. Nothing that reads `craftId`
            today reads anything different after this change.

            STILL INSIDE `Field`, which is a `<label>`. `ToolAssignmentSection` on this repository's
            own tools page already mounts its two multi-selects this way and a `<button>` is a
            labelable element, so the label activates the trigger; there is no second wrapper in this
            codebase to be consistent with instead.

            `searchable` IS PASSED EXPLICITLY. `SearchableSelect` grows its filter box at eight
            options, and this list is one craft long on a fresh deployment and 178 on the sibling's —
            a control whose searchability depends on how much data has been entered is a control two
            researchers describe differently.

            TWO PROPERTIES OF `SearchableMultiSelect` THIS FORM DEPENDS ON, both of which are true of
            it today and neither of which is obvious from the call:
             1. TICK ORDER IS PRESERVED. Its `toggle` appends to `values` and removes in place, so
                the array this receives is the order the researcher picked in — which is the order
                the server stores and the order `craftName` is joined in.
             2. A TICKED ID WITH NO OPTION IS NEVER SILENTLY DROPPED. `toggle` filters `values`, not
                the option list, so a craft this page could not load — an off-page row whose by-id
                rescue was refused — survives every interaction and is still saved. It is invisible
                in the summary, which is a display cost; it is not a data one.
          */}
          <Field label="Linked crafts (fills craft name)">
            <MultiSelectDropdown
              values={craftIds}
              onChange={onCraftsChanged}
              searchable
              placeholder="Select crafts"
              emptyLabel="No crafts are available to link"
              options={craftOptions.map((craft) => ({ value: craft.id, label: craft.name }))}
            />
            <CappedListNotice cuts={[craftCut]} />
          </Field>
          <DictatedTextInput
            name="craftName"
            label="Craft name"
            required
            titleCased
            explainWhenUnavailable={false}
            value={craftName}
            onChange={(next) => {
              setCraftName(next);
              markDirty();
            }}
          />
          {/*
            A MULTI-SELECT TOO, OVER THE ARTISANS OF EVERY TICKED CRAFT.
            `tool.artisanId`, `tool.artisanName` and `tool.place` keep the FIRST of them — the last
            two are NOT NULL columns and are filled from that artisan's own record on selection, as
            the single-select always did — and the `ToolArtisan` join table, which already existed
            for "Assign a tool to multiple artisans", holds all of them. No second mechanism.

            STILL DISABLED UNTIL A CRAFT IS TICKED, which is the same rule the single-select had: the
            options ARE the ticked crafts' rosters, so an enabled control with nothing in it says
            "there is nobody" where the truth is "you have not said which craft yet".
          */}
          <Field label="Linked artisans (fills artisan + place)">
            <MultiSelectDropdown
              values={artisanIds}
              onChange={(next) => {
                /*
                  THE FIRST ARTISAN FILLS THE TWO NOT-NULL COLUMNS, and only the first: `artisanName`
                  and `place` are single columns and always were. Through `syncArtisanColumns`, which
                  is also what a CRAFT untick goes through — the two gestures reach the same end state
                  and used to store two different rows — and which writes only when element 0 actually
                  MOVES, so a hand correction survives ticking a second artisan. The whole argument is
                  at that helper.
                */
                const previous = artisanIds;
                setArtisanIds(next);
                syncArtisanColumns(next, previous);
                const first = next.length ? artisans.find((artisan) => artisan.id === next[0]) : undefined;
                if (first) {
                  // An explicit pick replaces the remembered context and retires the banner: from
                  // here on the artisan on screen is the researcher's own choice, not a suggestion.
                  // The bag holds ONE artisan, so it is the first of the selection — and a craft NAME
                  // rather than the joined string, for the reason written at `sitting` in `submit`.
                  //
                  // NOT GATED ON THE HEAD CHANGING, unlike the two boxes above it. The bag is about
                  // where the researcher IS, and touching this picker at all is that statement; the
                  // boxes are about what the record SAYS, and rewriting those over a toggle that
                  // moved nothing is what overwrote a hand correction.
                  carry.remember(
                    {
                      artisanId: first.id,
                      artisanName: first.name,
                      place: first.place,
                      craftId,
                      craftName: craftOptions.find((craft) => craft.id === craftId)?.name ?? craftName
                    },
                    { explicit: true }
                  );
                }
                markDirty();
              }}
              searchable
              disabled={craftIds.length === 0}
              placeholder={craftIds.length ? "Select artisans" : "Select a linked craft first"}
              emptyLabel={craftIds.length ? "No artisans for these crafts" : "Select a linked craft first"}
              /* Craft first in the label, which is what makes the A→Z-by-craft order read as groups
                 on a control whose `SelectOption` has no `group` field — see `artisanOptionLabel`. */
              options={artisansForCrafts.map((artisan) => ({
                value: artisan.id,
                label: artisanOptionLabel(artisan, craftNameFor(artisan, selectedCrafts))
              }))}
            />
            {/* A claim about the REPOSITORY, so it waits for the repository's answer about THESE
                crafts. Printed off a stale roster it said "no artisans are linked to this craft yet"
                over a craft with a dozen of them — the silent-emptiness failure in one sentence, and
                the reason `artisansLoadedForCrafts` records WHICH crafts the loaded rows are for
                rather than a bare boolean. `craftsKey` is the only thing that may build the side to
                compare against: ticking A then B and ticking B then A are one roster. */}
            {craftIds.length > 0 && artisansLoadedForCrafts === craftsKey(craftIds) && artisansForCrafts.length === 0 ? (
              <p className="mt-1 text-xs text-ink-muted">
                No artisans are linked to {craftIds.length === 1 ? "this craft" : "these crafts"} yet.
              </p>
            ) : null}
            <CappedListNotice cuts={[craftIds.length ? craftArtisanCut : null]} />
          </Field>
          <DictatedTextInput
            name="artisanName"
            label="Artisan name"
            required
            titleCased
            explainWhenUnavailable={false}
            value={artisanName}
            onChange={(next) => {
              setArtisanName(next);
              markDirty();
            }}
          />
          <DictatedTextInput
            name="place"
            label="Place"
            required
            titleCased
            explainWhenUnavailable={false}
            value={place}
            onChange={(next) => {
              setPlace(next);
              markDirty();
            }}
          />
          {/* STILL A SINGLE-LINE BOX, and still disagreeing with the review registry — see the note
              above the two editors below. A microphone changes what the box can DO, not what the
              field IS. */}
          <DictatedTextInput
            name="processUsedIn"
            label="Process used in"
            explainWhenUnavailable={false}
            value={processUsedIn}
            onChange={(next) => {
              setProcessUsedIn(next);
              markDirty();
            }}
          />
          {/* Free prose — "mango wood with an iron collar" — neither a measurement nor a vocabulary,
              and the answer most likely to be given while holding the tool. */}
          <DictatedTextInput
            name="material"
            label="Material"
            explainWhenUnavailable={false}
            value={material}
            onChange={(next) => {
              setMaterial(next);
              markDirty();
            }}
          />
          <Field label="Years in use">
            <TextInput name="yearsInUse" type="number" min={0} defaultValue={initial?.yearsInUse ?? ""} />
          </Field>
          {/*
            `min={0}` ON EVERY MEASUREMENT AND PRICE ON THIS FORM, AND IT IS HALF OF A PAIR.

            Every one of these boxes accepted a negative and stored it. A negative length is not a
            measurement, and the sibling application's workshop registry declares the fields these
            are carried into as non-negative — so this product was accepting a quantity that product
            would refuse on a row it filled in FROM here. The server half landed with it (`ge=0`
            across `ToolCreate`/`ToolUpdate`, `backend/app/schemas/records.py:572-592`), and the two
            are deliberately not interchangeable: `min` refuses the value IN THE BOX, by name, before
            a request is made, while `ge=0` refuses it for every client that is not this one.

            IT IS `min`, NOT A PATTERN OR A CHECK IN `submit`. A native number input with `min={0}`
            blocks the submit and names the field; a JS check would have to invent its own error
            surface, and this form's error treatments are chosen by meaning — "you typed a negative
            into a box that is on screen" is a field-level refusal, which is exactly what the browser
            already draws.

            A BEHAVIOUR CHANGE ON EDIT, AND KNOWINGLY SO: this form posts the WHOLE payload back on
            an edit, so a row that already holds a negative will refuse every save until the number
            is corrected — including a save that was only fixing the village name. The audit query
            that finds those rows is written out beside the server bound.
          */}
          {/* THE LABEL SAYS THE UNIT NOW; THE FIELD NAME DOES NOT CHANGE.
              `height` and `width` keep their names on the wire, in `_CLEARABLE_COLUMNS`, in
              `ToolCreate`/`ToolUpdate`, in `lib/types.ts` and on the columns themselves — only what a
              person reads changes. Both handsets and the sibling repository's web form were renamed
              in the same breath, deliberately: a box a researcher moving between the four clients has
              to recognise cannot be called two things. */}
          <Field label="Height (cm)">
            <TextInput
              name="height"
              type="number"
              step="0.01"
              min={0}
              aria-describedby={heightHelpId}
              value={height}
              onChange={typeCm(setHeight, setHeightInches)}
            />
          </Field>
          <Field label="Width (cm)">
            <TextInput
              name="width"
              type="number"
              step="0.01"
              min={0}
              value={width}
              onChange={typeCm(setWidth, setBreadth)}
            />
          </Field>
          {/* These three go through `typeInches`, which writes the box, forgets whatever a machine
              proposed into it, and — for the two that have one — converts into the centimetre
              partner. See that helper for why a marker must not outlive the number it describes, and
              why the centimetre boxes are not wired through it. Length is STANDALONE: no centimetre
              partner, no new column, so it passes no third argument. */}
          <Field label="Length (inches)">
            <TextInput
              name="lengthInches"
              type="number"
              step="0.01"
              min={0}
              value={length}
              onChange={typeInches(setLength, "lengthInches")}
            />
          </Field>
          <Field label="Breadth (inches)">
            <TextInput
              name="breadthInches"
              type="number"
              step="0.01"
              min={0}
              value={breadth}
              onChange={typeInches(setBreadth, "breadthInches", setWidth)}
            />
          </Field>
          <Field label="Height (inches)">
            <TextInput
              name="heightInches"
              type="number"
              step="0.01"
              min={0}
              aria-describedby={heightHelpId}
              value={heightInches}
              onChange={typeInches(setHeightInches, "heightInches", setHeight)}
            />
          </Field>
          {/*
            THE DISAMBIGUATION, SPANNING THE ROW SO IT SITS UNDER BOTH BOXES IT DESCRIBES.

            Named by `aria-describedby` from "Height (cm)" and from "Height (inches)" — see
            `heightHelpId` above for why it is a paragraph outside both `Field`s rather than a hint
            inside either, and for the full-width row that keeps it from reading as a note about one
            box.

            ── THE SENTENCE IT USED TO CARRY IS RETIRED, NOT EDITED AWAY ─────────────────────────
            It read: *"Two height boxes, on purpose. **Height (inches)** is the one to fill in… **Height**
            is the older box, kept because tools already hold values in it and nothing recorded what
            unit those were measured in — leave it empty unless you are correcting one of those."*
            Every clause of that was true until 2026-09-15, when the two columns were PAIRED: `height`
            is the centimetre box, `heightInches` is the inch box, they are one measurement, and
            filling either fills the other. An instruction to fill one and not the other is now
            exactly backwards, so it is replaced rather than amended.

            WHAT SURVIVES FROM IT IS THE LAST CLAUSE, and it has to: rows saved before the pairing
            genuinely hold two unrelated numbers, nothing can say what unit the old `height` figures
            were in, and this form NEVER converts on load — so the paragraph has to tell a researcher
            that an old record may show two figures that disagree and that correcting either one
            fixes the pair.
          */}
          <p id={heightHelpId} className="text-xs leading-5 text-ink-500 md:col-span-2 xl:col-span-3">
            <strong className="font-semibold">Height (cm)</strong> and{" "}
            <strong className="font-semibold">Height (inches)</strong> are the same measurement in two units, and
            filling either fills the other (1&nbsp;inch = 2.54&nbsp;cm, rounded to two decimals).{" "}
            <strong className="font-semibold">Width (cm)</strong> and{" "}
            <strong className="font-semibold">Breadth (inches)</strong> pair the same way.{" "}
            <strong className="font-semibold">Length (inches)</strong> has no centimetre box. Records saved
            before this pairing existed can hold two numbers that disagree — opening one never rewrites either
            box, so correct whichever is wrong and its partner follows.
          </p>
          <Field label="Thickness">
            <TextInput name="thickness" type="number" step="0.01" min={0} defaultValue={initial?.thickness ?? ""} />
          </Field>
          <Field label="Weight">
            <TextInput name="weight" type="number" step="0.01" min={0} defaultValue={initial?.weight ?? ""} />
          </Field>
          <Field label="Radius">
            <TextInput name="radius" type="number" step="0.01" min={0} defaultValue={initial?.radius ?? ""} />
          </Field>
        </div>
        {/*
          ── THE PRIMARY MEASUREMENT ROUTE, AND WHY IT IS ABOVE THE OTHER ONE ────────────────────
          Deterministic, on this device, no connection and no per-call cost: the researcher marks
          across N squares of the grid sheet they were already photographing the tool on, and the
          arithmetic is a ratio of two pixel distances. It is FIRST on the page because it is the
          primary path — the vision-model route below costs money on every capture, needs a connection
          it has no queue behind, and cannot say how it reached a number. Order is not decoration
          here: whichever control a researcher meets first is the one they learn.

          IT PROPOSES; IT NEVER WRITES. `setLength`/`setBreadth`/`setHeightInches` are reached only
          from `onPropose`, which the panel calls only from a button's `onClick`.

          THE SENTENCE THAT FOLLOWED THAT ONE IS RETIRED: *"The plain `height` box has no machine
          writer at all."* True until the centimetre pairing, and contradicted thirty lines below by
          the `propagate(text, cmTextFromInches, setHeight)` in this panel's own `onPropose`. Both
          centimetre boxes are written from the accept callbacks now — still only from a button's
          `onClick`, still only by CONVERSION from the accepted inches, and still with no method
          marker, because the machine measured inches and a converted figure is not a second reading.

          AND THE ACCEPTANCE IS RECORDED, NOT JUST THE NUMBER. The third argument is
          `photoMeasure.methodMarker(result)` — `{method: "PHOTO_GEOMETRY", technique: "SCALE"}` or
          `"RECTIFIED"`, whichever geometry actually produced the figure on the button — and it rides
          out on the save's `measurementMethods` for as long as the box still holds this number.
        */}
        <RecordPhotoMeasure
          columns={MEASURE_COLUMNS}
          values={{ lengthInches: length, breadthInches: breadth, heightInches }}
          onPropose={(key, text, method) => {
            if (key === "lengthInches") setLength(text);
            // THE ACCEPTED READING FILLS ITS CENTIMETRE PARTNER TOO, ARITHMETICALLY.
            // The panel measures in inches and proposes in inches; the centimetre box is the same
            // measurement in the other unit, so leaving it holding an older, unrelated figure would
            // put two disagreeing numbers on one record under one Save. Written with the bare setter
            // and NOT through `typeCm`: that factory is what a PERSON typing produces, and routing a
            // machine acceptance through a human's handler is how the two become impossible to tell
            // apart later. The conversion is the same function either way.
            else if (key === "breadthInches") {
              setBreadth(text);
              propagate(text, cmTextFromInches, setWidth);
            }
            // `heightInches` and NOT `height`. A measured number belongs in the column that says what
            // unit it is in — and only that column can carry the method marker `DIMENSION_FIELDS`
            // gates. The centimetre box takes the converted figure and NO marker: the machine
            // measured inches, and a derived number is not a second reading.
            else if (key === "heightInches") {
              setHeightInches(text);
              propagate(text, cmTextFromInches, setHeight);
            }
            // AFTER the box is written and keyed by the same `key`, so the remembered text is
            // exactly what went in. `rememberAcceptance` refuses anything outside `DIMENSION_FIELDS`
            // itself — which on THIS form is the guard that matters, because the wrong `key` here is
            // `height`, and a marker naming it is a 422 that loses the researcher the whole form.
            //
            // THE MARKER SURVIVES THE PARTNER WRITE, and that is the property to hold on to:
            // `measurementMethodsFor` compares the INCH box's text against the accepted text
            // character for character at save time, and filling the centimetre box does not touch
            // the inch box. Nothing here may write the inch box a second time.
            setAccepted((current) => rememberAcceptance(current, key, text, method));
            markDirty();
          }}
          onPhotoChange={(photo) => {
            setMeasurePhoto(photo);
            // Only when there IS one. The panel reports `null` once on mount, and a blank new form
            // announcing unsaved work before anybody has typed is what trains researchers to click
            // through the guard.
            if (photo) markDirty();
          }}
        />
        {/*
          ── THE FALLBACK, KEPT AND LABELLED ────────────────────────────────────────────────────
          `GridMeasurement` posts the photograph to `POST /media/analyze-measurement`, which asks a
          vision model to ESTIMATE the inches. It is retained deliberately: a tool that will not lie
          flat, or a researcher who cannot mark the frame, still has it. What it is not any more is
          the first thing on the page, and this wrapper is where it says which of the two it is.

          THE HEADING SAYS "ESTIMATE" AND THE BADGE SAYS "NEEDS A CONNECTION", and neither is
          rhetoric. The route has no queue, no outbox entry and no retry, so in a courtyard with no
          signal it fails every single time; and its answer is a model's guess, which nobody can
          re-derive from the photograph the way the panel above can.

          NOT COLLAPSED, AND THAT IS ON PURPOSE. Its capture state (which groups are ticked, the
          reading on offer) lives inside the component, while the FILES it has captured live up here
          in `gridFiles`. Unmounting it on collapse would drop the first and keep the second, leaving
          a photograph queued for upload with nothing on screen saying so.
        */}
        <section className="grid gap-2 rounded-lg border border-line-200 bg-card p-4">
          <div className="flex flex-wrap items-center gap-2">
            <h3 className="text-sm font-semibold text-ink-900">If you cannot mark it: estimate with the vision model</h3>
            <span className="rounded-full border border-amber-500 bg-amber-100 px-2 py-0.5 text-xs font-medium text-amber-800">
              Needs a connection
            </span>
          </div>
          <p className="text-xs leading-5 text-ink-500">
            This asks a model to read the inches off the photograph. It is an <strong>estimate</strong>, not a
            measurement: it carries no error bar and nobody — including the model — can re-derive it from the picture
            afterwards. Prefer the panel above wherever the grid or a ruler is in the frame.
          </p>
          {/*
            THE MARKER THIS ONE CARRIES IS THE SERVER'S OWN, ECHOED BACK UNCHANGED. `POST
            /media/analyze-measurement` answers with `methodMarker` beside the analysis —
            `{method: "VISION_MODEL", provider, modelId, selfReportedConfidence}`, with any key the
            model did not answer OMITTED rather than invented — and a client's job is to hand it back
            on the save, not to compose one. `null` when the API predates that key, and
            `rememberAcceptance` then records no acceptance at all: the reading is stored
            `UNRECORDED`, because this client was told a number and not told how it was reached.
          */}
          <GridMeasurement
            includeHeight
            onLengthBreadth={(l, b, method) => {
              // Keyed one dimension at a time and only for the ones that actually arrived: a
              // photograph that yielded a length and no breadth must not leave a marker standing
              // over a breadth box this call never touched — and, now, must not rewrite the
              // centimetre partner of a box it never touched either.
              if (l) {
                setLength(l);
                setAccepted((current) => rememberAcceptance(current, "lengthInches", l, method));
              }
              if (b) {
                setBreadth(b);
                // Breadth's centimetre partner is `width`. No marker for it — see `onPropose` above
                // and `MEASURE_COLUMNS` for why a converted figure carries no provenance of its own.
                propagate(b, cmTextFromInches, setWidth);
                setAccepted((current) => rememberAcceptance(current, "breadthInches", b, method));
              }
              markDirty();
            }}
            /*
              THE READING IS IN INCHES, SO IT GOES IN THE BOX THAT SAYS INCHES — the same destination
              as the panel above.

              This wrote `setHeight` — the unit-less column — until the record-parity sweep, which is
              how every grid-measured tool height in this repository came to be stored with no
              recoverable unit. Nothing reported it: the number was right, the box was filled, the
              save returned 200, and only the UNIT was lost. See `heightInches` above and migration
              20260913120100. Two measurement routes on one form must also not land in two different
              boxes; a researcher who tried the panel and then this fallback would otherwise be
              looking at two heights, having been told nothing about why there are two.
            */
            onHeight={(value, method) => {
              setHeightInches(value);
              // AND THE CENTIMETRE PARTNER, CONVERTED. The reading is in inches and goes in the inch
              // box; `height` is the same measurement in centimetres, so it follows. The assertion in
              // `record-parity-fields-unit.spec.ts` that this block must NOT contain `setHeight` was
              // right until the pairing existed and is now inverted by name — the defect it guarded
              // was writing the INCHES VERBATIM into the unit-less box, and `cmTextFromInches` is
              // the opposite of that: a stated conversion into a box whose label states the unit.
              propagate(value, cmTextFromInches, setHeight);
              // `heightInches` and not `height` here too — the marker has to name the same column the
              // number went into, or it describes a measurement of something else. The centimetre box
              // gets the number and no claim about it.
              setAccepted((current) => rememberAcceptance(current, "heightInches", value, method));
              markDirty();
            }}
            onFilesChange={(files) => {
              setGridFiles(files);
              markDirty();
            }}
          />
        </section>
        <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-3">
          <Field label="Maker">
            <Select name="maker" defaultValue={initial?.maker ?? "UNKNOWN"} onChange={markDirty}>
              {makerOptions.map((option) => (
                <option key={option}>{option}</option>
              ))}
            </Select>
          </Field>
          <Field label="Tradition type">
            <Select name="traditionType" defaultValue={initial?.traditionType ?? "UNKNOWN"} onChange={markDirty}>
              {traditionOptions.map((option) => (
                <option key={option}>{option}</option>
              ))}
            </Select>
          </Field>
          <Field label="Replacement cost">
            <TextInput name="replacementCost" type="number" step="0.01" min={0} defaultValue={initial?.replacementCost ?? ""} />
          </Field>
        </div>
        <div className="grid gap-3 md:grid-cols-2">
          {/*
            THE TWO NARRATIVE BOXES ON THIS FORM. Both were already `<TextArea>` (`min-h-24`, no
            length cap) and both hold prose a researcher would rather speak than thumb in. `remarks`
            is also searched by a raw `contains` at `tools.py:99`, which is the reason
            `RichTextField` keeps writing plain prose until something is actually formatted.

            `processUsedIn` above is still a SINGLE-LINE box even though the review registry
            (`components/review/reviewEditFields.ts`) marks it `multiline: true` and the CSV exports
            it as "Usage". It now carries a microphone (line 611) — that changes what the
            box can DO, not what the field IS, so the disagreement is untouched and still open.
            Recorded here so the next person does not read either half as an oversight. The old
            version of this paragraph cited "line 487" for that box, which had drifted by seven lines
            before this edit and is the standing proof that a `file:line` inside a `.tsx` is checked
            by nothing: `docs/tools/check-docs.mjs` verifies citations in `.md` files only.
          */}
          <RichTextField
            name="suggestionsForToolImprovement"
            label="Suggestions for improvement"
            defaultValue={initial?.suggestionsForToolImprovement ?? ""}
            className="md:col-span-2"
            // Said once at the top of this form by `DictationUnavailableNotice`.
            explainWhenUnavailable={false}
            onDirty={markDirty}
          />
          <RichTextField
            name="remarks"
            label="Remarks"
            defaultValue={initial?.remarks ?? ""}
            className="md:col-span-2"
            // Said once at the top of this form by `DictationUnavailableNotice`.
            explainWhenUnavailable={false}
            onDirty={markDirty}
          />
          <StatusField canSetStatus={canSetStatus} initialStatus={initial?.status} onDirty={markDirty} />
        </div>
        <MediaCaptureField
          files={stageFiles}
          onFilesChange={(files) => {
            setStageFiles(files);
            markDirty();
          }}
          title="Process stages"
          description="Document each step of making or using this tool. Captures are archived in order as STAGE_STEP_1, STAGE_STEP_2, …"
        />
        {initial ? <ExistingMedia linkedRecordType="tool" linkedRecordId={initial.id} /> : null}
        <MediaCaptureField
          files={mediaFiles}
          onFilesChange={(files) => {
            setMediaFiles(files);
            markDirty();
          }}
          title="Tool media"
          description="Attach or capture tool images, videos, audio notes, and documents. Image EXIF is retained and summarized in remarks."
        />
        <LocationFields initial={initialLocation} onDirty={markDirty} />
        {uploadProgress ? <UploadProgress progress={uploadProgress} /> : null}
        <div className="flex justify-end gap-2">
          <button type="button" className="field-button-secondary" onClick={handleBack}>
            Cancel
          </button>
          <button className="field-button" disabled={saving}>
            {saving ? "Saving..." : initial ? "Update tool" : "Save tool"}
          </button>
        </div>
      </form>
      <UnsavedChangesDialog
        open={backPromptOpen}
        saving={saving}
        onKeepEditing={() => setBackPromptOpen(false)}
        onDiscard={() => {
          setBackPromptOpen(false);
          resetDirty();
          router.back();
        }}
        onSave={() => {
          setBackPromptOpen(false);
          formRef.current?.requestSubmit();
        }}
      />
    </>
  );
}
