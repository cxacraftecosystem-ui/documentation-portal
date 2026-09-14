"use client";

import { useMemo, useRef, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";

import { useAuth } from "@/components/AuthProvider";
import { mergeById } from "@/components/data/cappedList";
import { CappedListNotice } from "@/components/data/CappedListNotice";
import { Field, Select, TextInput } from "@/components/FormControls";
import { CarryContextBanner, carryScope, useCarryContext } from "@/components/forms/CarryContextBanner";
import { LocationFields, type LocationInitialValues } from "@/components/forms/LocationFields";
import {
  forgetAcceptance,
  measurementMethodsFor,
  NO_ACCEPTED_MEASUREMENTS,
  rememberAcceptance,
  type AcceptedMeasurements
} from "@/components/forms/measurementMethods";
import { MediaCaptureField } from "@/components/forms/MediaCaptureField";
import { craftChangeClearsArtisan, useCraftAndArtisanOptions, useRecordOffPage } from "@/components/forms/recordPickers";
import { useWorkshopSelection, WorkshopSelect } from "@/components/forms/WorkshopSelect";
import { ExistingMedia } from "@/components/media/ExistingMedia";
import { GridMeasurement, type GridFiles, type GridGroup } from "@/components/media/GridMeasurement";
import { RecordPhotoMeasure, type MeasureColumn } from "@/components/media/RecordPhotoMeasure";
import { UploadProgress } from "@/components/media/UploadProgress";
import { DictatedTextInput } from "@/components/richtext/DictatedTextInput";
import { DictationUnavailableNotice } from "@/components/richtext/DictationUnavailableNotice";
import { RichTextField } from "@/components/richtext/RichTextField";
import { appendStoredParagraph } from "@/components/richtext/storedRichText";
import { UnsavedChangesDialog } from "@/components/UnsavedChangesDialog";
import { useLeaveGuard } from "@/components/UnsavedChangesGuard";
import { apiFetch } from "@/lib/api";
import { locationFromForm, numericValue, recordedAtFromForm, recordedTimezoneFromForm, requiredText, textValue, useUnsavedChanges } from "@/lib/forms";
import { handleFormEnter } from "@/lib/formNav";
import { collectExifMetadata, exifMetadataToRemark, uploadMediaBatch, uploadMediaFile, type BatchProgress } from "@/lib/media";
import { saveOrQueue } from "@/lib/offline";
import { hasRank } from "@/lib/permissions";
import type { Artisan, Craft, ProductDocumentation, RecordStatus } from "@/lib/types";
import { marketDemandOptions, productTypes } from "@/lib/types";
import { TraceFromCapture } from "@/components/trace/TraceFromCapture";

/** Dropdown label for a linked artisan: always "Name · Place" (name alone if no place), never ids. */
function artisanOptionLabel(artisan: Artisan) {
  const name = artisan.name?.trim() || "Unnamed artisan";
  // "·" (middle dot), not "•" — Android joins every record label with the middle dot, and the
  // process form already does; using both marks in one product form reads as two conventions.
  return artisan.place?.trim() ? `${name} · ${artisan.place.trim()}` : name;
}

/**
 * The dimension columns the on-device measurement may be accepted into, in the order the boxes are
 * drawn below.
 *
 * THE UNIT IS THE COLUMN'S, NOT THE REFERENCE'S, and it is stated here because it is the one fact
 * the panel cannot work out for itself: a researcher measuring against a 300 mm steel rule gets an
 * answer in millimetres, and `lengthInches` is inches. `proposalFor` converts, then rounds to what
 * `Decimal(10, 2)` can hold. All three of this record's dimension columns say their unit in their own
 * name, so none of them needs the `note` `MeasureColumn` carries.
 *
 * THE LIST IS ALSO THE GUARD. `measurement_provenance.DIMENSION_FIELDS` on the server is exactly
 * these three names, and a marker naming anything else is a 422 on the whole save rather than a
 * dropped hint. `rememberAcceptance` refuses a key outside that set independently, so a fourth entry
 * added here would lose its marker rather than lose the researcher's form.
 */
const MEASURE_COLUMNS: MeasureColumn[] = [
  { key: "lengthInches", label: "Length (inches)", unit: "in" },
  { key: "breadthInches", label: "Breadth (inches)", unit: "in" },
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
 * `DictatedTextInput`; the four narrative ones use `RichTextField`, whose editor carries the
 * microphone at the caret so a phrase lands inside the document rather than on the end of it.
 *
 * DICTATED: Product name · Local name · Craft name · Artisan name · Place · Time taken to complete ·
 * Size · Raw materials used · Main tools used · Function or use · Remarks · (and Village, inside the
 * location card, which owns its own decision).
 *
 * TIME TAKEN AND SIZE ARE DICTATED, though they sit beside a row of numbers that are not. Neither is
 * a measurement: both are `String?` columns nothing parses, and what researchers actually write in
 * them is a sentence — "about three days, longer in the monsoon", "roughly a forearm across". The
 * boxes below them are `type="number"`, which is a different thing entirely and is why they are on
 * the other list.
 *
 * NOT DICTATED, one line each, and each is a rule rather than a preference:
 *
 *  - **Workshop, Linked craft, Linked artisan, Product type, Market demand, Status** — closed
 *    vocabularies and record pickers behind a themed dropdown. There is no free text to speak.
 *  - **Length, Breadth, Height, Cost of making, Selling price** — `type="number"` boxes. A recogniser
 *    spells digits out in words ("thirty"), which a native number input DISCARDS silently: the box is
 *    empty after a spoken answer with nothing on screen saying why. The three dimensions carry a
 *    second reason — the grid-measurement capture PROPOSES them and a person accepts, so a spoken
 *    third route would record an acceptance for a reading nobody can re-derive.
 *  - **Document using grid, Product media** — file pickers and capture cards.
 *
 * NOT title-cased, deliberately, though they are dictated: **Local name** (Devanagari or Gujarati,
 * where capitalising means nothing — `records.py:335-338` leaves it out for that reason), **Time
 * taken** and **Size** (absent from the API's title-cased set, so a hint would promise a
 * normalisation that never happens).
 *
 * ONE SENTENCE FOR THE WHOLE FORM. Every control above passes `explainWhenUnavailable={false}` and
 * `DictationUnavailableNotice` sits once at the top. Twelve microphones down one form is twelve
 * copies of the Firefox paragraph, which is how a true sentence becomes wallpaper.
 */
export function ProductForm({ initial }: { initial?: ProductDocumentation }) {
  const router = useRouter();
  const searchParams = useSearchParams();
  const { user } = useAuth();
  const canSetStatus = hasRank(user, "PROFESSOR");
  const formRef = useRef<HTMLFormElement>(null);
  const [mediaFiles, setMediaFiles] = useState<File[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [uploadProgress, setUploadProgress] = useState<BatchProgress | null>(null);
  const [craftId, setCraftId] = useState(initial?.craftId ?? searchParams.get("craftId") ?? "");
  const [artisanId, setArtisanId] = useState(initial?.artisanId ?? searchParams.get("artisanId") ?? "");
  // Android parity: picking a linked craft fills the craft name; picking a linked artisan fills the
  // artisan name + place — so these three are controlled.
  const [craftName, setCraftName] = useState(initial?.craftName ?? searchParams.get("craftName") ?? "");
  const [artisanName, setArtisanName] = useState(initial?.artisanName ?? searchParams.get("artisanName") ?? "");
  const [place, setPlace] = useState(initial?.place ?? searchParams.get("place") ?? "");
  /*
    HOISTED FOR THE MICROPHONE. These four were uncontrolled `defaultValue` boxes; `DictatedTextInput`
    is controlled by its caller and has exactly one mode, for the reason written out in that file (a
    self-controlled box repaints stale text on a form cleared by `formElement.reset()`). This form
    clears by NAVIGATING AWAY, so the trap does not bite here — but one contract for the control
    across the app is worth more than a second mode on this one screen.
  */
  const [productName, setProductName] = useState(initial?.productName ?? "");
  const [localName, setLocalName] = useState(initial?.localName ?? "");
  const [timeTaken, setTimeTaken] = useState(initial?.timeTakenToCompleteProduct ?? "");
  const [size, setSize] = useState(initial?.size ?? "");
  /*
    Dimensions are controlled so a measurement route can write into them from its accept button.
    This line read "so the 'Document using grid' capture can auto-fill them" until the provenance
    sweep, and the verb was the defect: nothing auto-fills any more. Both routes PROPOSE and a person
    accepts — see `components/media/gridProposal.ts` for why, and `measurementMethods` below for what
    the acceptance now records.
  */
  const [length, setLength] = useState(initial?.lengthInches != null ? String(initial.lengthInches) : "");
  const [breadth, setBreadth] = useState(initial?.breadthInches != null ? String(initial.breadthInches) : "");
  const [height, setHeight] = useState(initial?.heightInches != null ? String(initial.heightInches) : "");
  /**
   * WHICH OF THE THREE BOXES ABOVE STILL HOLDS A MACHINE'S NUMBER, and what produced it.
   *
   * Written only by an accept button, cleared by a keystroke in the box it describes, and read once —
   * by `measurementMethodsFor` while the save body is built. It holds the accepted TEXT beside the
   * marker, which is the whole mechanism: see `components/forms/measurementMethods.ts` for why a
   * marker that outlives the number it describes is worse than no marker at all.
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
  /**
   * The craft and artisan dropdowns' contents, and what they are NOT showing.
   *
   * Shared with ToolForm, which asks the identical question and had the identical defect in it —
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
    artisansLoadedForCraft
  } = useCraftAndArtisanOptions({ craftId, artisanId });
  /**
   * THIS PRODUCT'S OWN CRAFT IS ALWAYS AN OPTION, wherever it sorts.
   *
   * The hook above does the by-id rescue for the ARTISAN and for nobody else, so without this line
   * the defect `useRecordOffPage` exists to close would still be fully present on the craft dropdown
   * of this form and of ToolForm, on the same screen as the artisan dropdown that had been fixed.
   * `GET /crafts` is clamped to 100 rows and ordered NAME ASCENDING (deliberately — see the ordering
   * comment in `routes/crafts.py:82-87`), so the cut is stable and always falls in the same place:
   * every product of a craft whose name sorts past it opens with its craft dropdown reading
   * "Unlinked / type below" beside a REQUIRED "Craft name" box holding the right name. The stored
   * link is intact and would be saved untouched — but the form says it is not, and the obvious
   * repair for a craft that looks unlinked is to pick one, which is the single action that really
   * does rewrite the link.
   *
   * Same hook as the artisan side, called the same way. Do not write a variant of it: several
   * pickers need this rule, and one of them getting a bespoke version is exactly how the others came
   * to be missing it.
   */
  const offPageCraft = useRecordOffPage<Craft>("/crafts", craftId, crafts);
  const craftOptions = useMemo(() => (offPageCraft ? mergeById(crafts, [offPageCraft]) : crafts), [crafts, offPageCraft]);
  const { dirty, markDirty, resetDirty } = useUnsavedChanges();
  const [backPromptOpen, setBackPromptOpen] = useState(false);
  // Hands the prompt to the round back control in the page header, which is now the only back
  // control on the page.
  useLeaveGuard(dirty, () => setBackPromptOpen(true));
  // The API includes the record's stored location (not yet in the TS type); pass it so the edit
  // form pre-fills coordinates instead of auto-capturing the editor's current position.
  const initialLocation = initial
    ? ((initial as ProductDocumentation & { location?: LocationInitialValues | null }).location ?? null)
    : undefined;
  const isEdit = Boolean(initial);
  // The workshop this product was documented at: shared picker, shared most-recent defaulting, and
  // the late-submission gate (see components/forms/WorkshopSelect).
  const workshop = useWorkshopSelection({ initialWorkshopId: initial?.workshopId, isEdit, resetKey: initial?.id ?? null });

  const toNum = (value: string) => {
    const n = Number(value);
    return value.trim() && Number.isFinite(n) ? n : null;
  };

  /**
   * A DIMENSION BOX A PERSON IS TYPING IN, which is two facts and not one: the new text, and that
   * whatever a machine proposed into this box is no longer what it holds.
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
   * A FACTORY RATHER THAN THREE INLINE HANDLERS so that the pairing cannot be half-applied: a box
   * wired to a bare `setLength` would keep its marker through an edit, and the mistake would be
   * invisible on screen.
   */
  const typeInto =
    (set: (value: string) => void, key: string) => (event: React.ChangeEvent<HTMLInputElement>) => {
      set(event.target.value);
      setAccepted((current) => forgetAcceptance(current, key));
    };

  // Task 6: once a craft is linked, the artisan dropdown only offers artisans of that craft. The
  // currently-selected artisan is always kept visible even if the data predates the craft link.
  const artisansForCraft = craftId
    ? artisans.filter((artisan) => artisan.craftId === craftId || artisan.id === artisanId)
    : artisans;

  // Offer the sitting this researcher was last working in, however they got here — the query string
  // only survives a click straight through from the save screen (lib/carryContext). The PRODUCT in
  // the bag is this form's own subject and is never applied here; a tool or process in it belongs
  // to other forms and is left alone rather than dropped, so they still have it.
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
    onApply: (context) => {
      if (context.craftId) setCraftId(context.craftId);
      if (context.craftName) setCraftName(context.craftName);
      if (context.artisanId) setArtisanId(context.artisanId);
      if (context.artisanName) setArtisanName(context.artisanName);
      if (context.place) setPlace(context.place);
      if (context.workshopId && !workshop.touched) workshop.setWorkshopId(context.workshopId);
    }
  });

  /** "Change": drop every carried value so the researcher picks from scratch. */
  function clearCarriedContext() {
    carry.change();
    setCraftId("");
    setCraftName("");
    setArtisanId("");
    setArtisanName("");
    setPlace("");
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
        [...Object.values(gridFiles), measurePhoto?.file, ...mediaFiles].filter(Boolean) as File[]
      );
      const exifRemark = exifMetadataToRemark(exifItems);
      const recordedAt = recordedAtFromForm(form);
      const recordedTimezone = recordedTimezoneFromForm(form);
      const location = locationFromForm(form);
      const payload = {
        craftName: requiredText(form, "craftName"),
        place: requiredText(form, "place"),
        artisanName: requiredText(form, "artisanName"),
        productName: requiredText(form, "productName"),
        localName: textValue(form, "localName"),
        productType: requiredText(form, "productType") || "OTHER",
        timeTakenToCompleteProduct: textValue(form, "timeTakenToCompleteProduct"),
        size: textValue(form, "size"),
        lengthInches: toNum(length),
        breadthInches: toNum(breadth),
        heightInches: toNum(height),
        /*
          ── HOW EACH OF THE THREE DIMENSIONS ABOVE WAS MEASURED ─────────────────────────────────
          `{"lengthInches": {"method": "PHOTO_GEOMETRY", "technique": "SCALE"}}` for a reading
          accepted out of `RecordPhotoMeasure`, or the vision model's own `methodMarker` echoed back
          verbatim for one accepted out of `GridMeasurement`. `records.merge_field_provenance` pops
          the key — it is not a column — and merges the method INTO the `{by, byName, at}` stamp it
          was already writing, so the row reads *a vision model estimated this, and this person
          accepted it into the record at that moment* instead of asserting they measured it by hand.

          ── THIS KEY IS ONLY SENDABLE ONCE THE SERVER DECLARES IT ───────────────────────────────
          `ProductCreate` / `ProductUpdate` share an `APIModel` that is `ConfigDict(extra="forbid")`,
          so a body carrying a key the schema does not declare is rejected 422 IN FULL — and
          `saveOrQueue` will not queue a 4xx ("the server saw it and said no"), so the record would be
          neither saved nor retried. The ordering that makes this safe is: the server's
          `measurementMethods` declaration first, then this. Do not trust this paragraph on its word —
          re-check both halves:

            grep -n "MARKER_BODY_KEY" backend/app/services/access.py
            grep -n "measurementMethods" backend/app/schemas/records.py

          ── WHAT MAY BE IN IT, WHICH IS LESS THAN WHAT WAS ACCEPTED ─────────────────────────────
          `measurementMethodsFor` emits a marker ONLY for a box still holding the exact text the
          route proposed. Typed over, cleared, or never accepted and the key is simply not there —
          the server reads absence as `UNRECORDED`, which is honest and is never the false human
          claim. `undefined` and not `null` when there is nothing to say, so the key leaves the
          `JSON.stringify` entirely and a save with no machine measurement is byte-for-byte the save
          this form has always sent — which is what keeps a newer web build safe against an older
          API. See `components/forms/measurementMethods.ts` for both rules.
        */
        measurementMethods: measurementMethodsFor(accepted, {
          lengthInches: length,
          breadthInches: breadth,
          heightInches: height
        }),
        costOfMaking: numericValue(form, "costOfMaking"),
        sellingPrice: numericValue(form, "sellingPrice"),
        marketDemand: requiredText(form, "marketDemand") || "UNKNOWN",
        rawMaterialsUsed: textValue(form, "rawMaterialsUsed"),
        mainToolsUsed: textValue(form, "mainToolsUsed"),
        productFunctionUse: textValue(form, "productFunctionUse"),
        // `appendStoredParagraph` and NOT `appendRemarksWithExif`: remarks is a rich-text editor
        // now, so this column may hold a JSON document, and concatenating the EXIF summary onto the
        // end of a JSON string produces a value that is neither valid JSON nor readable prose. The
        // helper appends INTO the document when there is one and is byte-for-byte the old behaviour
        // when there is not.
        remarks: appendStoredParagraph(textValue(form, "remarks") as string | null, exifRemark),
        artisanId: artisanId || null,
        craftId: craftId || null,
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
      // Offline this queues instead of failing, carrying the grid photos and the field media with
      // it — each group as its own batch so the captions that identify a grid photo survive.
      const outcome = await saveOrQueue<ProductDocumentation>({
        label: `Product · ${payload.productName || "Untitled"}`,
        endpoint: initial ? `/products/${initial.id}` : "/products",
        method: initial ? "PATCH" : "POST",
        body: payload,
        media: [
          ...(Object.entries(gridFiles) as [GridGroup, File][]).map(([group, file]) => ({
            files: [file],
            linkedRecordType: "product",
            caption: `${group === "lengthBreadth" ? "Length & breadth" : "Height"} grid (measurement) for ${payload.productName || "product"}`,
            location,
            recordedAt,
            recordedTimezone,
            transcribeAudio: false
          })),
          /*
            THE PHOTOGRAPH THE ON-DEVICE PANEL MEASURED FROM, kept with the record for the same reason
            the grid shots are: a dimension whose evidence is gone is a number nobody can re-derive,
            and re-deriving it is the whole advantage this route has over the vision model. Its own
            batch so its caption survives — `saveOrQueue` carries one caption per batch.
          */
          ...(measurePhoto
            ? [
                {
                  files: [measurePhoto.file],
                  linkedRecordType: "product",
                  caption: `Measured-from photo${measurePhoto.isGrid ? " (grid)" : ""} for ${payload.productName || "product"}`,
                  location,
                  recordedAt,
                  recordedTimezone,
                  transcribeAudio: false
                }
              ]
            : []),
          {
            files: mediaFiles,
            linkedRecordType: "product",
            caption: `Field media for ${payload.productName || "product"}`,
            location,
            recordedAt,
            recordedTimezone,
            extraMetadata: exifItems.length ? { mediaExif: exifItems } : undefined
          }
        ]
      });
      // Bank the sitting the moment the record is accepted, so the next form opened from the
      // dashboard already knows where the researcher is.
      const sitting = {
        artisanId,
        artisanName: payload.artisanName,
        place: payload.place,
        craftId,
        craftName: payload.craftName,
        workshopId: workshop.workshopId,
        workshopName: workshop.workshops.find((w) => w.id === workshop.workshopId)?.title ?? null
      };
      if (outcome.queued) {
        // Offline is the normal case, but a queued product has no id yet, so no process form could
        // link to it. Whatever product was in the bag is dropped rather than left to stand in for
        // the one just recorded — an old product offered under a new one's name is a wrong link.
        carry.prune("product");
        carry.remember(sitting);
        // OutboxBanner at the top of the page names the entry and says where it lives.
        resetDirty();
        if (typeof window !== "undefined") window.scrollTo({ top: 0, behavior: "smooth" });
        setSaving(false);
        return;
      }
      const saved = outcome.saved;
      // The product itself now joins the bag: a process is documented against a product, so the
      // process form should be offering this one rather than making them find it again.
      carry.remember({ ...sitting, productId: saved.id, productName: saved.productName });
      // Store each captured grid photo as media linked to the product (the measured value is already
      // in the field). Best-effort per file so one failure doesn't lose the record.
      for (const [group, file] of Object.entries(gridFiles) as [GridGroup, File][]) {
        try {
          await uploadMediaFile({
            file,
            linkedRecordType: "product",
            linkedRecordId: saved.id,
            caption: `${group === "lengthBreadth" ? "Length & breadth" : "Height"} grid (measurement) for ${saved.productName}`,
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
            linkedRecordType: "product",
            linkedRecordId: saved.id,
            caption: `Measured-from photo${measurePhoto.isGrid ? " (grid)" : ""} for ${saved.productName}`,
            location,
            recordedAt,
            recordedTimezone,
            transcribeAudio: false
          });
        } catch {
          /* keep the saved record even if the measurement photo fails to store */
        }
      }
      if (mediaFiles.length) {
        const { failed } = await uploadMediaBatch({
          files: mediaFiles,
          linkedRecordType: "product",
          linkedRecordId: saved.id,
          caption: `Field media for ${saved.productName}`,
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
      router.push("/products");
      router.refresh();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to save product record");
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
          Every dictated control below passes `explainWhenUnavailable={false}`, including the four
          rich-text editors, because on Firefox the same honest paragraph printed twelve times down
          one form is a block of grey text nobody reads. Removing this line does not remove the
          sentence from one box; it removes it from ALL of them.

          ABOVE the grid and not inside it: the grid is up to three columns, so a paragraph mounted
          as one of its children would be a column-wide sliver beside the workshop picker.
        */}
        <DictationUnavailableNotice />
        <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-3">
          {/* Android parity (ProductForm): the workshop opens the form, because it is the context
              every other answer belongs to — not merely the first dropdown. */}
          <WorkshopSelect state={workshop} onDirty={markDirty} saving={saving} />
          {/* Product/craft/artisan names and place are title-cased by the API on write
              (`backend/app/services/records.py:339-354`), so the box says what will actually be
              stored — `titleCased` mounts `TitleCasedInput` itself inside the dictated box, never a
              copy of its hint. `markDirty()` BY HAND in every `onChange` below: a dictated phrase is
              a React state write and fires no native `input` event for the form's `onInput` to
              catch, so a researcher who only ever spoke would be told there was nothing to lose. */}
          <DictatedTextInput
            name="productName"
            label="Product name"
            required
            titleCased
            explainWhenUnavailable={false}
            value={productName}
            onChange={(next) => {
              setProductName(next);
              markDirty();
            }}
          />
          {/* NOT title-cased: `localName` is Devanagari or Gujarati, where capitalising means
              nothing. It still gets a microphone — the recogniser takes whichever of the eleven
              languages it is set to. */}
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
          <Field label="Product type">
            <Select name="productType" defaultValue={initial?.productType ?? "OTHER"} onChange={markDirty}>
              {productTypes.map((option) => (
                <option key={option}>{option}</option>
              ))}
            </Select>
          </Field>
          <Field label="Linked craft (fills craft name)">
            <Select
              name="craftId"
              value={craftId}
              onChange={(event) => {
                const next = event.target.value;
                setCraftId(next);
                const craft = craftOptions.find((c) => c.id === next);
                if (craft) setCraftName(craft.name);
                // Drop the artisan ONLY when this form actually knows they practise a different
                // craft — never merely because it cannot see them. The distinction, and the silent
                // link deletion that made it necessary, are argued in `forms/recordPickers`.
                if (craftChangeClearsArtisan({ nextCraftId: next, artisanId, artisans })) {
                  setArtisanId("");
                }
                markDirty();
              }}
            >
              {/* "Unlinked" must mean unlinked. It is the placeholder a browser falls back to when
                  `value` matches no <option>, so it doubled as "linked to a craft that is not on
                  page one" until `craftOptions` carried that craft — see `offPageCraft` above. */}
              <option value="">Unlinked / type below</option>
              {craftOptions.map((craft) => (
                <option key={craft.id} value={craft.id}>
                  {craft.name}
                </option>
              ))}
            </Select>
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
          <Field label="Linked artisan (fills artisan + place)">
            <Select
              name="artisanId"
              value={artisanId}
              onChange={(event) => {
                const next = event.target.value;
                setArtisanId(next);
                const artisan = artisans.find((a) => a.id === next);
                if (artisan) {
                  setArtisanName(artisan.name);
                  setPlace(artisan.place);
                  // An explicit pick replaces the remembered context and retires the banner: from
                  // here on the artisan on screen is the researcher's own choice, not a suggestion.
                  carry.remember(
                    { artisanId: artisan.id, artisanName: artisan.name, place: artisan.place, craftId, craftName },
                    { explicit: true }
                  );
                }
                markDirty();
              }}
              disabled={!craftId}
            >
              <option value="">{craftId ? "Unlinked / type below" : "Select a linked craft first"}</option>
              {artisansForCraft.map((artisan) => (
                <option key={artisan.id} value={artisan.id}>
                  {artisanOptionLabel(artisan)}
                </option>
              ))}
            </Select>
            {/* A claim about the REPOSITORY, so it waits for the repository's answer about THIS
                craft. Printed off a stale roster it said "no artisans are linked to this craft yet"
                over a craft with a dozen of them — the silent-emptiness failure in one sentence, and
                the reason `artisansLoadedForCraft` records WHICH craft the loaded rows are for
                rather than a bare boolean. */}
            {craftId && artisansLoadedForCraft === craftId && artisansForCraft.length === 0 ? (
              <p className="mt-1 text-xs text-ink-muted">No artisans are linked to this craft yet.</p>
            ) : null}
            <CappedListNotice cuts={[craftId ? craftArtisanCut : null]} />
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
          {/* DICTATED ON PURPOSE, BESIDE THE NUMBERS THAT ARE NOT. Neither of these is a
              measurement: both are `String?` columns nothing parses, and what researchers write in
              them is a sentence. Not title-cased — both are absent from the API's title-cased set,
              so a hint here would promise a normalisation that never happens. */}
          <DictatedTextInput
            name="timeTakenToCompleteProduct"
            label="Time taken to complete"
            explainWhenUnavailable={false}
            value={timeTaken}
            onChange={(next) => {
              setTimeTaken(next);
              markDirty();
            }}
          />
          <DictatedTextInput
            name="size"
            label="Size"
            explainWhenUnavailable={false}
            value={size}
            onChange={(next) => {
              setSize(next);
              markDirty();
            }}
          />
          {/*
            `min={0}` ON EVERY MEASUREMENT AND PRICE ON THIS FORM, AND IT IS HALF OF A PAIR.

            Every one of these boxes accepted a negative and stored it. A negative length is not a
            measurement and a negative selling price is not a price, and the sibling application's
            workshop registry declares the fields these are carried into as non-negative — so this
            product was accepting a quantity that product would refuse on a row it filled in FROM
            here. The server half landed with it (`ge=0` across `ProductCreate`/`ProductUpdate`,
            `backend/app/schemas/records.py:444-451`), and the two are deliberately not
            interchangeable: `min` refuses the value IN THE BOX, by name, before a request is made,
            while `ge=0` refuses it for every client that is not this one. The same pair is on
            `ToolForm`, which carries the longer form of this argument.

            A BEHAVIOUR CHANGE ON EDIT, AND KNOWINGLY SO: this form posts the WHOLE payload back on
            an edit, so a row that already holds a negative will refuse every save until the number
            is corrected — including a save that was only fixing a caption. The audit query that
            finds those rows is written out beside the server bound.
          */}
          {/* All three go through `typeInto`, which writes the box AND forgets whatever a machine
              proposed into it — see that helper for why a marker must not outlive the number it
              describes. */}
          <Field label="Length (inches)">
            <TextInput
              name="lengthInches"
              type="number"
              step="0.01"
              min={0}
              value={length}
              onChange={typeInto(setLength, "lengthInches")}
            />
          </Field>
          <Field label="Breadth (inches)">
            <TextInput
              name="breadthInches"
              type="number"
              step="0.01"
              min={0}
              value={breadth}
              onChange={typeInto(setBreadth, "breadthInches")}
            />
          </Field>
          <Field label="Height (inches)">
            <TextInput
              name="heightInches"
              type="number"
              step="0.01"
              min={0}
              value={height}
              onChange={typeInto(setHeight, "heightInches")}
            />
          </Field>
        </div>
        {/*
          ── THE PRIMARY MEASUREMENT ROUTE, AND WHY IT IS ABOVE THE OTHER ONE ────────────────────
          Deterministic, on this device, no connection and no per-call cost: the researcher marks
          across N squares of the grid sheet they were already photographing the object on, and the
          arithmetic is a ratio of two pixel distances. It is FIRST on the page because it is the
          primary path — the vision-model route below costs money on every capture, needs a connection
          it has no queue behind, and cannot say how it reached a number. Order is not decoration
          here: whichever control a researcher meets first is the one they learn.

          IT PROPOSES; IT NEVER WRITES. `setLength`/`setBreadth`/`setHeight` are reached only from
          `onPropose`, which the panel calls only from a button's `onClick`.

          AND THE ACCEPTANCE IS RECORDED, NOT JUST THE NUMBER. The third argument is
          `photoMeasure.methodMarker(result)` — `{method: "PHOTO_GEOMETRY", technique: "SCALE"}` or
          `"RECTIFIED"`, whichever geometry actually produced the figure on the button — and it rides
          out on the save's `measurementMethods` for as long as the box still holds this number.
        */}
        <RecordPhotoMeasure
          columns={MEASURE_COLUMNS}
          values={{ lengthInches: length, breadthInches: breadth, heightInches: height }}
          onPropose={(key, text, method) => {
            if (key === "lengthInches") setLength(text);
            else if (key === "breadthInches") setBreadth(text);
            else if (key === "heightInches") setHeight(text);
            // AFTER the box is written and keyed by the same `key`, so the remembered text is
            // exactly what went in. `rememberAcceptance` refuses anything outside `DIMENSION_FIELDS`
            // itself, which is what keeps a panel misconfigured with a fourth column from composing
            // a marker the API answers 422 to.
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
          vision model to ESTIMATE the inches. It is retained deliberately: an object that will not
          lie flat, or a researcher who cannot mark the frame, still has it. What it is not any more
          is the first thing on the page, and this wrapper is where it says which of the two it is.

          THE HEADING SAYS "ESTIMATE" AND THE BADGE SAYS "NEEDS A CONNECTION", and neither is
          rhetoric. The route has no queue, no outbox entry and no retry, so in a courtyard with no
          signal it fails every single time; and its answer is a model's guess, which nobody can
          re-derive from the photograph the way the panel above can. The component states the
          connection requirement in full in its own copy — this is the one-line summary above it.

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
              // over a breadth box this call never touched.
              if (l) {
                setLength(l);
                setAccepted((current) => rememberAcceptance(current, "lengthInches", l, method));
              }
              if (b) {
                setBreadth(b);
                setAccepted((current) => rememberAcceptance(current, "breadthInches", b, method));
              }
              markDirty();
            }}
            onHeight={(value, method) => {
              setHeight(value);
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
          <Field label="Cost of making">
            <TextInput name="costOfMaking" type="number" step="0.01" min={0} defaultValue={initial?.costOfMaking ?? ""} />
          </Field>
          <Field label="Selling price">
            <TextInput name="sellingPrice" type="number" step="0.01" min={0} defaultValue={initial?.sellingPrice ?? ""} />
          </Field>
          <Field label="Market demand">
            <Select name="marketDemand" defaultValue={initial?.marketDemand ?? "UNKNOWN"} onChange={markDirty}>
              {marketDemandOptions.map((option) => (
                <option key={option}>{option}</option>
              ))}
            </Select>
          </Field>
        </div>
        <div className="grid gap-3 md:grid-cols-2">
          {/*
            THE FOUR NARRATIVE BOXES ON THIS FORM, and all four qualify under the rule the user set:
            each was already a `<TextArea>` (`min-h-24`, no length cap) holding prose about how the
            product is made, what it is made of and what it is for. The boxes above — code,
            dimensions, cost, selling price, market demand, the two names — deliberately get
            nothing: a formatting toolbar on a price field is noise, and dictating four digits is
            slower than typing them.

            THREE OF THESE FOUR ARE SEARCHED COLUMNS. `products.py:89-91` runs a raw Prisma
            `contains` against `rawMaterialsUsed`, `mainToolsUsed` and `remarks`, which is why
            `RichTextField` stores prose for as long as the field is unformatted — see the header of
            `components/richtext/storedRichText.ts`, and do not "simplify" the encode.

            Raw materials and main tools are lists as often as they are sentences, which is exactly
            what the editor's bullet button is for; they are seeded as prose rather than as lists
            (no `listKind`) because the existing values in these columns are comma-separated
            sentences and reshaping them on open would be the editor arguing with what was written.

            `md:col-span-2` on each is not decoration: this grid is two columns, and the toolbar
            carries eight groups of controls that wrap to four rows inside a half-width column,
            leaving the chrome taller than the box it belongs to.

            `explainWhenUnavailable={false}` on each: the editor mounts the on-device microphone
            inside itself, so without the flag a browser with no recogniser (Firefox) would print the
            form-level paragraph PLUS one copy under each of these four — five copies of one sentence
            down one form, which is how a true sentence becomes wallpaper. The form says it once, at
            the top (`DictationUnavailableNotice`).
          */}
          <RichTextField
            name="rawMaterialsUsed"
            label="Raw materials used"
            defaultValue={initial?.rawMaterialsUsed ?? ""}
            className="md:col-span-2"
            explainWhenUnavailable={false}
            onDirty={markDirty}
          />
          <RichTextField
            name="mainToolsUsed"
            label="Main tools used"
            defaultValue={initial?.mainToolsUsed ?? ""}
            className="md:col-span-2"
            explainWhenUnavailable={false}
            onDirty={markDirty}
          />
          <RichTextField
            name="productFunctionUse"
            label="Function or use"
            defaultValue={initial?.productFunctionUse ?? ""}
            className="md:col-span-2"
            explainWhenUnavailable={false}
            onDirty={markDirty}
          />
          <RichTextField
            name="remarks"
            label="Remarks"
            defaultValue={initial?.remarks ?? ""}
            className="md:col-span-2"
            explainWhenUnavailable={false}
            onDirty={markDirty}
          />
          <StatusField canSetStatus={canSetStatus} initialStatus={initial?.status} onDirty={markDirty} />
        </div>
        {initial ? <ExistingMedia linkedRecordType="product" linkedRecordId={initial.id} /> : null}
        <MediaCaptureField
          files={mediaFiles}
          onFilesChange={(files) => {
            setMediaFiles(files);
            markDirty();
          }}
          title="Product media"
          description="Attach or capture product images, videos, audio notes, and documents. Image EXIF is retained and summarized in remarks."
        />
        <TraceFromCapture files={mediaFiles} onFilesChange={setMediaFiles} />
        <LocationFields initial={initialLocation} onDirty={markDirty} />
        {uploadProgress ? <UploadProgress progress={uploadProgress} /> : null}
        <div className="flex justify-end gap-2">
          <button type="button" className="field-button-secondary" onClick={handleBack}>
            Cancel
          </button>
          <button className="field-button" disabled={saving}>
            {saving ? "Saving..." : initial ? "Update product" : "Save product"}
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
