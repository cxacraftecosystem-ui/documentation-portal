"use client";

import { useEffect, useId, useMemo, useRef, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";

import { useAuth } from "@/components/AuthProvider";
import { LIST_PAGE_CEILING, listCut, mergeById, type ListCut } from "@/components/data/cappedList";
import { CappedListNotice } from "@/components/data/CappedListNotice";
import { CarryForwardCards } from "@/components/CarryForwardCards";
import { Field, Select, TextInput } from "@/components/FormControls";
import { AadhaarField, aadhaarValidationError, isMaskedIdentityNumber } from "@/components/forms/AadhaarField";
import { CarryContextBanner, carryScope, useCarryContext, type CarryScopeState } from "@/components/forms/CarryContextBanner";
import { DosDontsField } from "@/components/forms/DosDontsField";
import { DuplicateArtisanDialog } from "@/components/forms/DuplicateArtisanDialog";
import { LocationFields, type LocationInitialValues } from "@/components/forms/LocationFields";
import { MediaCaptureField } from "@/components/forms/MediaCaptureField";
import { PhoneField } from "@/components/forms/PhoneField";
import { useRecordOffPage } from "@/components/forms/recordPickers";
import { TitleCasedInput } from "@/components/forms/TitleCasedInput";
import { useWorkshopSelection, WorkshopSelect } from "@/components/forms/WorkshopSelect";
import { ExistingMedia } from "@/components/media/ExistingMedia";
import { UploadProgress } from "@/components/media/UploadProgress";
import { DictatedTextArea } from "@/components/richtext/DictatedTextArea";
import { RichTextField } from "@/components/richtext/RichTextField";
import { appendStoredParagraph } from "@/components/richtext/storedRichText";
import { UnsavedChangesDialog } from "@/components/UnsavedChangesDialog";
import { useLeaveGuard } from "@/components/UnsavedChangesGuard";
import { ApiError, apiFetch, buildQuery, listResource } from "@/lib/api";
import { locationFromForm, recordedAtFromForm, recordedTimezoneFromForm, requiredText, textValue, useUnsavedChanges } from "@/lib/forms";
import { handleFormEnter } from "@/lib/formNav";
import { collectExifMetadata, exifMetadataToRemark, uploadMediaBatch, type BatchProgress } from "@/lib/media";
import { saveOrQueue } from "@/lib/offline";
import { hasRank } from "@/lib/permissions";
import type { AadhaarLookupResult, Artisan, ArtisanIdentityConflict, ArtisanIdentityMatch, Craft, RecordStatus } from "@/lib/types";

// Android parity (MainActivity.kt genderOptions).
const genderOptions = ["Male", "Female", "Transgender", "Other"];

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

// The Pehchan Yes/No dropdown submits these through the Select's mirror input; `submit` parses them
// back into the boolean the API expects. Keeping them as the option VALUES (with "Yes"/"No" only as
// labels) means the payload never depends on how the question happens to be worded on screen.
const PEHCHAN_YES = "true";
const PEHCHAN_NO = "false";

/**
 * The one combination the API refuses outright, worded exactly as Android and the server word it.
 * The browser's own "Please fill out this field." says nothing about the way OUT of the problem
 * (flip the answer to No), which is the half a researcher without a card in hand actually needs.
 */
const PEHCHAN_NUMBER_REQUIRED =
  "Enter the Artisan Pehchan Card number, or set the card to 'No' if the artisan does not hold one.";

/** The `detail` of an API error response, whatever shape the server chose for it. */
function errorDetail(error: unknown): unknown {
  if (!(error instanceof ApiError)) return null;
  const payload = error.payload;
  if (!payload || typeof payload !== "object" || !("detail" in payload)) return null;
  return (payload as { detail: unknown }).detail;
}

/**
 * The sentence the server actually wrote, dug back out of the response body.
 *
 * `apiFetch` builds `ApiError.message` with `String(detail)`, which is right for the plain-string
 * details most routes raise and useless for the two structured ones the identity fields produce: a
 * 409 whose detail is a conflict object, and FastAPI's 422 whose detail is a list of field errors.
 * Both stringify to "[object Object]", and a mistyped Aadhaar is exactly the case where the specific
 * message ("that number fails its checksum") is the entire value of the response.
 */
function readableError(error: unknown, fallback: string): string {
  const detail = errorDetail(error);
  if (typeof detail === "string" && detail.trim()) return detail;
  if (Array.isArray(detail)) {
    const messages = detail
      .map((entry) => (entry && typeof entry === "object" && "msg" in entry ? String((entry as { msg: unknown }).msg) : ""))
      // Pydantic prefixes every custom validator message with "Value error, "; the researcher only
      // needs the sentence after it.
      .map((message) => message.replace(/^Value error,\s*/, "").trim())
      .filter(Boolean);
    if (messages.length) return messages.join(" ");
  }
  if (detail && typeof detail === "object" && "message" in detail) {
    const message = String((detail as { message: unknown }).message);
    if (message.trim()) return message;
  }
  const message = error instanceof Error ? error.message : "";
  return message && message !== "[object Object]" ? message : fallback;
}

/** The identity conflict behind a 409, or null when the failure was something else entirely. */
function identityConflict(error: unknown): ArtisanIdentityConflict | null {
  if (!(error instanceof ApiError) || error.status !== 409) return null;
  const detail = errorDetail(error);
  if (!detail || typeof detail !== "object") return null;
  const conflict = detail as ArtisanIdentityConflict;
  return conflict.code === "artisan_identity_conflict" ? conflict : null;
}

/**
 * The artisan who already holds `digits`, or null — the save-time half of the Aadhaar duplicate check.
 *
 * `AadhaarField` runs the same lookup as the number is typed and shows an inline warning, but a
 * warning three fields up the page is easy to type past. Asking again at submit is what turns it into
 * a decision (see :func:`DuplicateArtisanDialog`), and it costs one cheap request on a path that was
 * about to make an expensive one.
 *
 * Nothing here ever blocks on its own: a number that fails validation cannot match a stored (already
 * validated) Aadhaar, and a failed request means the server simply gets to answer the question itself
 * with its 409. Being offline must never stop a researcher saving.
 *
 * A MASK is skipped outright rather than left to fail validation. It means the number was not
 * changed, so the only artisan it could ever "match" is this one — and asking the server to look up
 * "XXXX XXXX 9012" would put a masked identifier in a query string for no answer at all.
 */
async function findArtisanByAadhaar(digits: string | null, excludeArtisanId: string | null): Promise<ArtisanIdentityMatch | null> {
  const number = (digits ?? "").trim();
  if (!number || isMaskedIdentityNumber(number) || aadhaarValidationError(number)) return null;
  try {
    const result = await apiFetch<AadhaarLookupResult>(`/artisans/lookup/aadhaar${buildQuery({ number })}`);
    const found = result.found ? (result.artisan ?? null) : null;
    return found && found.id !== excludeArtisanId ? found : null;
  } catch {
    return null;
  }
}

/**
 * "Does the artisan hold a Pehchan card?" and the card number, which only exist in one consistent
 * pair of states: Yes with a number, or No with nothing.
 *
 * Answering No clears the number rather than merely disabling the box — a disabled input is omitted
 * from FormData, so a stale number would survive invisibly in React state and reappear the moment
 * the answer flipped back to Yes. The API applies the same rule server-side (it forces the number to
 * null whenever availability is false); this is the UI half of that contract, so what the researcher
 * sees and what gets stored never disagree.
 *
 * `initialNumber` may be a MASK. `public_encode` runs the Pehchan number through the same
 * `mask_identity_number` as the Aadhaar, so a caller who is neither the artisan's own researcher nor
 * a professor upwards is handed "XXXX XXXX 3456" and may still edit the record. Editing the mask has
 * to be impossible: `validate_pehchan` accepts it (it is alphanumeric once the spaces come off and
 * comfortably inside 4-32 characters), so the API would have stored the literal "XXXXXXXX3456" over
 * a real card number — silently, with no error to notice, and then refused the next artisan who
 * genuinely holds that card on the unique index. The box is therefore read-only while it holds a
 * mask, and `submit` leaves the field out of the payload entirely rather than posting it back.
 */
function PehchanFields({
  initialAvailable,
  initialNumber,
  onDirty
}: {
  initialAvailable: boolean;
  initialNumber?: string | null;
  onDirty: () => void;
}) {
  const baseId = useId();
  const numberId = `${baseId}-pehchan-number`;
  const hintId = `${baseId}-pehchan-hint`;
  const [available, setAvailable] = useState(initialAvailable);
  const storedMask = isMaskedIdentityNumber(initialNumber) ? String(initialNumber).trim() : null;
  // Set when the editor chooses to type a new card number over one they were never shown.
  const [replacing, setReplacing] = useState(false);
  const [number, setNumber] = useState(storedMask ? "" : (initialNumber ?? ""));
  // The mask still standing in for the stored number: what to show, and what to submit. Dropped when
  // the answer is No, so the box does not keep displaying a card number under the line that says
  // this artisan holds no card — and a No that is saved clears the stored number as it always did.
  const keptMask = replacing || !available ? null : storedMask;

  return (
    <>
      <div className="grid content-start gap-1">
        <span className="field-label">Artisan Pehchan Card available</span>
        <Select
          name="pehchanCardAvailable"
          value={available ? PEHCHAN_YES : PEHCHAN_NO}
          aria-label="Artisan Pehchan Card available"
          onChange={(event) => {
            const next = event.target.value === PEHCHAN_YES;
            setAvailable(next);
            if (!next) setNumber("");
            // The themed Dropdown is a button, so it fires no native input event for the form's
            // onInput to catch: the dirty flag has to be raised by hand.
            onDirty();
          }}
        >
          <option value={PEHCHAN_YES}>Yes</option>
          <option value={PEHCHAN_NO}>No</option>
        </Select>
      </div>
      <div className="grid content-start gap-1">
        <label className="field-label" htmlFor={numberId}>
          Artisan Pehchan Card number{available ? " *" : ""}
        </label>
        <input
          id={numberId}
          name="pehchanCardNumber"
          className="field-input read-only:bg-surface-50 read-only:text-ink-500 disabled:cursor-not-allowed disabled:bg-surface-50 disabled:text-ink-500"
          type="text"
          autoComplete="off"
          placeholder={available ? "As printed on the card" : "No card on record"}
          value={keptMask ?? number}
          // A read-only box is exempt from constraint validation, which is the right answer here:
          // `required` must not demand a number the editor is not permitted to read.
          readOnly={Boolean(keptMask)}
          required={available}
          disabled={!available}
          aria-disabled={!available}
          aria-describedby={hintId}
          onInvalid={(event) => event.currentTarget.setCustomValidity(PEHCHAN_NUMBER_REQUIRED)}
          // The API stores card numbers upper-cased without separators; showing that as it is typed
          // keeps the box honest about what will actually be saved. Clearing the custom validity
          // here is what lets a corrected value submit — it survives until it is reset by hand.
          onChange={(event) => {
            event.currentTarget.setCustomValidity("");
            setNumber(event.currentTarget.value.toUpperCase());
          }}
        />
        <p id={hintId} className="text-xs text-ink-muted">
          {!available
            ? 'Disabled because this artisan holds no Pehchan card. Switch "available" to Yes to enter a number.'
            : keptMask
              ? "On file, but hidden from you: the full card number is shown only to the researcher who recorded this artisan and to professors upwards. Saving leaves it exactly as it is."
              : "The PM Vishwakarma artisan ID printed on the card."}
          {keptMask ? (
            <>
              {" "}
              <button
                type="button"
                className="font-semibold text-purple-700 underline"
                onClick={() => setReplacing(true)}
              >
                Replace this number
              </button>
            </>
          ) : null}
          {available && storedMask && replacing ? (
            <>
              {" "}
              <button
                type="button"
                className="font-semibold text-purple-700 underline"
                onClick={() => {
                  setReplacing(false);
                  setNumber("");
                }}
              >
                Keep the stored number
              </button>
            </>
          ) : null}
        </p>
      </div>
    </>
  );
}

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

export function ArtisanForm({ initial }: { initial?: Artisan }) {
  const router = useRouter();
  const { user } = useAuth();
  const canSetStatus = hasRank(user, "PROFESSOR");
  const identityLabelId = `${useId()}-identity`;
  const formRef = useRef<HTMLFormElement>(null);
  const [crafts, setCrafts] = useState<Craft[]>([]);
  // Controlled so the carried craft can land in it after the list arrives; a defaultValue is fixed
  // at first render and the crafts request has not answered by then.
  const [craftId, setCraftId] = useState(initial?.craftId ?? "");
  // "Can I see this craft?" and "is there any signal?" are different answers — see useCarryContext.
  const [craftListState, setCraftListState] = useState<CarryScopeState>("pending");
  const [craftCut, setCraftCut] = useState<ListCut | null>(null);
  const [mediaFiles, setMediaFiles] = useState<File[]>([]);
  const [error, setError] = useState<string | null>(null);
  // A rejected duplicate is not a generic error: it names an existing artisan the researcher should
  // open instead, so it gets its own state and its own panel with a link.
  const [conflict, setConflict] = useState<ArtisanIdentityConflict | null>(null);
  // The panel above is a reminder that stays put; the dialog is the one-time question asked at the
  // moment of saving. Separate flags so dismissing the question does not erase the reminder.
  const [duplicatePromptOpen, setDuplicatePromptOpen] = useState(false);
  const [checkingDuplicate, setCheckingDuplicate] = useState(false);
  const [saving, setSaving] = useState(false);
  const [uploadProgress, setUploadProgress] = useState<BatchProgress | null>(null);
  const [savedRecord, setSavedRecord] = useState<Artisan | null>(null);
  const [email, setEmail] = useState(initial?.email ?? "");
  // Bumped to throw the form away and rebuild it ("Discard this entry"). Remounting is what clears
  // the state living inside the field components — the Aadhaar digits, the Pehchan pair, the notes
  // rows, the Do's/Don'ts lists — which no amount of `form.reset()` can reach.
  const [formKey, setFormKey] = useState(0);
  const { dirty, markDirty, resetDirty } = useUnsavedChanges();
  const [backPromptOpen, setBackPromptOpen] = useState(false);
  // Hands the prompt to the round back control in the page header, which is now the only back
  // control on the page.
  useLeaveGuard(dirty, () => setBackPromptOpen(true));
  // The API includes the record's stored location (not yet in the Artisan TS type); pass it so the
  // edit form pre-fills coordinates instead of auto-capturing the editor's current position.
  const initialLocation = initial
    ? ((initial as Artisan & { location?: LocationInitialValues | null }).location ?? null)
    : undefined;
  /**
   * Aadhaar is what stops one artisan becoming two records, so a NEW artisan must come with one.
   * An artisan documented before that rule has none, and a researcher who opened the record to fix a
   * phone number must not have to invent a government ID to save the correction — so on edit it is
   * required only when the record already carries one, where the requirement costs nothing and also
   * stops a stored number being quietly emptied. A masked number counts as carrying one — it is a
   * real number this caller may not read — and AadhaarField satisfies the requirement by posting the
   * mask straight back.
   */
  const aadhaarRequired = !initial || Boolean(initial.aadhaarNumber?.trim());
  // The workshop this artisan was documented at: shared picker, shared most-recent defaulting, and
  // the late-submission gate (see components/forms/WorkshopSelect).
  const workshop = useWorkshopSelection({
    initialWorkshopId: initial?.workshopId,
    isEdit: Boolean(initial),
    resetKey: initial?.id ?? null
  });

  const emailError =
    email.trim() && !EMAIL_RE.test(email.trim()) ? "Enter a valid email address (name@example.com)." : null;

  useEffect(() => {
    listResource<Craft>("/crafts", { pageSize: LIST_PAGE_CEILING })
      .then((result) => {
        setCrafts(result.items);
        // What this dropdown is NOT showing. `/crafts` is clamped to 100 rows server-side and the
        // envelope's `total` was being dropped on the floor, so a repository past the ceiling showed
        // a partial list of crafts under no indication at all that it was partial — and this field
        // is REQUIRED, sitting directly above "Or new craft name". A researcher who cannot find an
        // existing craft in a list that will not admit it is short does the reasonable thing and
        // types the name into the box below, which creates a SECOND craft row for a craft that
        // already exists. See `components/data/cappedList`.
        setCraftCut(listCut(result, "crafts"));
        setCraftListState("loaded");
      })
      .catch(() => {
        setCrafts([]);
        setCraftListState("unavailable");
      });
  }, []);

  /**
   * THIS ARTISAN'S OWN CRAFT IS ALWAYS AN OPTION, wherever it sorts.
   *
   * `GET /crafts` is clamped to 100 rows and ordered NAME ASCENDING (deliberately — see the ordering
   * comment in `routes/crafts.py:82-87`), so once the repository holds more crafts than that the cut
   * is stable and always falls in the same place: every artisan of a craft whose name sorts past it
   * opens with this REQUIRED dropdown reading "Select existing craft", as though the record had no
   * craft. The stored link is intact and would be saved untouched — but the form says it is not, and
   * the two repairs it invites are both destructive: pick a different craft, or type the real name
   * into "Or new craft name" below and mint a DUPLICATE craft row for a craft that already exists.
   *
   * Same hook as ProductForm and ToolForm use for the same reason (`forms/recordPickers`). Three
   * forms need this rule; do not give one of them a bespoke version.
   */
  const offPageCraft = useRecordOffPage<Craft>("/crafts", craftId, crafts);
  const craftOptions = useMemo(() => (offPageCraft ? mergeById(crafts, [offPageCraft]) : crafts), [crafts, offPageCraft]);

  /**
   * The craft and the workshop carry into a new artisan; the ARTISAN in the bag never does.
   *
   * This form's whole job is to create a person who is not yet in the bag, so prefilling the last
   * one would be worse than useless — it is the "wrong artisan" hazard with the record itself as the
   * casualty. Their place does not carry either: it belongs to that artisan, not to the sitting, and
   * two artisans documented back to back are routinely from different villages. What genuinely
   * transfers is the craft everyone at this workshop practises, and the workshop.
   */
  const carry = useCarryContext({
    enabled: !initial,
    // `craftOptions`, not `crafts`: a carried craft that is merely off the picker's first page IS
    // reachable — the by-id lookup above fetched it — and pruning it would drop a good link from the
    // bag for the "absent from page one" reason that is exactly what this port set out to stop
    // meaning "absent from the repository".
    scopes: [carryScope("craft", craftListState, craftOptions)],
    applies: ["craft", "workshop"],
    onApply: (context) => {
      if (context.craftId) setCraftId(context.craftId);
      if (context.workshopId && !workshop.touched) workshop.setWorkshopId(context.workshopId);
    }
  });
  /** "Change": drop the carried craft so the researcher picks from scratch. */
  function clearCarriedContext() {
    carry.change();
    setCraftId("");
  }

  function handleBack() {
    if (dirty) setBackPromptOpen(true);
    else router.back();
  }

  /**
   * Throw the in-progress entry away and start from a clean form.
   *
   * The workshop and the craft are deliberately left alone: the researcher is still standing in the
   * same workshop documenting the same craft, and re-picking both after every discarded duplicate
   * would be busywork. Clearing the craft would also make the carry-forward banner above lie about
   * a field it no longer fills.
   */
  function discardEntry() {
    setDuplicatePromptOpen(false);
    setConflict(null);
    setError(null);
    setMediaFiles([]);
    setEmail(initial?.email ?? "");
    resetDirty();
    setFormKey((key) => key + 1);
    if (typeof window !== "undefined") window.scrollTo({ top: 0, behavior: "smooth" });
  }

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    // Read the form synchronously: React nulls event.currentTarget across the await below.
    const form = new FormData(event.currentTarget);
    setError(null);
    setConflict(null);
    // Ask about a duplicate BEFORE the late-submission prompt: there is no point weighing up a late
    // save that is about to be abandoned anyway.
    setCheckingDuplicate(true);
    const existing = await findArtisanByAadhaar(textValue(form, "aadhaarNumber"), initial?.id ?? null);
    setCheckingDuplicate(false);
    if (existing) {
      setConflict({
        code: "artisan_identity_conflict",
        field: "aadhaarNumber",
        message: `${existing.name} is already recorded with this Aadhaar number.`,
        existingArtisan: existing
      });
      setDuplicatePromptOpen(true);
      return;
    }
    // A workshop that has already ended makes this a late submission needing admin approval — say so
    // before anything is written. Resolves true immediately when there is nothing to warn about.
    if (!(await workshop.confirmSubmission())) return;
    setSaving(true);
    try {
      const exifItems = await collectExifMetadata(mediaFiles);
      const exifRemark = exifMetadataToRemark(exifItems);
      const recordedAt = recordedAtFromForm(form);
      const recordedTimezone = recordedTimezoneFromForm(form);
      // Everything LocationFields renders, including the state and pincode that used to be merged
      // in here by hand — `locationFromForm` reads them now, so the five other forms that share it
      // stopped throwing the two answers away. Also goes onto the media batch below: same place.
      const location = locationFromForm(form);
      // Android parity: an artisan needs either an existing craft or a new craft name.
      const craftId = textValue(form, "craftId");
      const newCraftName = textValue(form, "newCraftName");
      if (!craftId && !newCraftName) {
        setError("Select an existing craft or enter a new craft name.");
        setSaving(false);
        return;
      }
      // The Yes/No dropdown mirrors its option value into FormData; anything other than an explicit
      // "No" means the artisan holds a card, which matches the API's own default of Yes.
      const pehchanAvailable = textValue(form, "pehchanCardAvailable") !== PEHCHAN_NO;
      const pehchanNumber = textValue(form, "pehchanCardNumber");
      // A masked card number means the editor was never shown the real one and left it alone. Unlike
      // the Aadhaar mask the API does NOT recognise this one — `validate_pehchan` happily normalises
      // "XXXX XXXX 3456" to "XXXXXXXX3456" and stores it over the real card — so the key is dropped
      // from the payload instead, which a PATCH reads as "not sent, not changed".
      const pehchanUnchanged = pehchanAvailable && isMaskedIdentityNumber(pehchanNumber);
      const payload = {
        name: requiredText(form, "name"),
        localName: textValue(form, "localName"),
        gender: textValue(form, "gender"),
        phone: textValue(form, "phone"),
        email: textValue(form, "email"),
        place: requiredText(form, "place"),
        address: textValue(form, "address"),
        // `appendStoredParagraph` and NOT `appendRemarksWithExif`: the notes box is a rich-text
        // editor now, so this column may hold a JSON document. Concatenating the EXIF summary onto
        // the end of a JSON string produces a value that is neither valid JSON nor readable prose —
        // the editor would show raw braces followed by the summary, and so would the artisan CSV,
        // the review panel and the Android form. The helper appends INTO the document when there is
        // one and is byte-for-byte `appendRemarksWithExif` when there is not.
        //
        // "paragraph" because this column is blank-line separated: see the RichTextField below.
        notes: appendStoredParagraph(textValue(form, "notes") as string | null, exifRemark, "paragraph"),
        // Identity. The Aadhaar mirror input carries the bare digits (the visible box only groups
        // them for reading), or the mask verbatim when the editor was never shown the real number —
        // which the API recognises and drops, leaving the stored value alone. The Pehchan mask has
        // no such server-side guard, so it is omitted above instead. The card number IS sent, as an
        // explicit null, when the artisan holds no card, so an edit that flips the answer to No
        // clears the stored number instead of orphaning it: `aadhaarNumber` and `pehchanCardNumber`
        // are both clearable server-side.
        aadhaarNumber: textValue(form, "aadhaarNumber"),
        pehchanCardAvailable: pehchanAvailable,
        ...(pehchanUnchanged ? {} : { pehchanCardNumber: pehchanAvailable ? pehchanNumber : null }),
        dos: requiredText(form, "dos"),
        donts: requiredText(form, "donts"),
        craftId,
        craftName: craftId ? null : newCraftName,
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
      // With no connection this queues to the offline outbox instead of failing at the Save button;
      // the media goes with it, because the artisan will have gone home by the time signal returns.
      const outcome = await saveOrQueue<Artisan>({
        label: `Artisan · ${payload.name || "Untitled"}`,
        endpoint: initial ? `/artisans/${initial.id}` : "/artisans",
        method: initial ? "PATCH" : "POST",
        body: payload,
        media: [
          {
            files: mediaFiles,
            linkedRecordType: "artisan",
            caption: `Field media for ${payload.name || "artisan"}`,
            location,
            recordedAt,
            recordedTimezone,
            extraMetadata: exifItems.length ? { mediaExif: exifItems } : undefined
          }
        ]
      });
      if (outcome.queued) {
        // No per-form "queued" banner: OutboxBanner at the top of the page already names the entry
        // and is the one place that says where it lives. Scroll so it is the next thing seen.
        resetDirty();
        if (typeof window !== "undefined") window.scrollTo({ top: 0, behavior: "smooth" });
        setSaving(false);
        return;
      }
      const saved = outcome.saved;
      if (mediaFiles.length) {
        const { failed } = await uploadMediaBatch({
          files: mediaFiles,
          linkedRecordType: "artisan",
          linkedRecordId: saved.id,
          caption: `Field media for ${saved.name}`,
          location,
          recordedAt,
          recordedTimezone,
          extraMetadata: exifItems.length ? { mediaExif: exifItems } : undefined,
          onProgress: setUploadProgress
        });
        setUploadProgress(null);
        if (failed.length) {
          setError(
            `${failed.length} of ${mediaFiles.length} file(s) failed to upload: ${failed.map((f) => f.name).join(", ")}. ` +
              "The artisan record was saved; re-open it to retry those files."
          );
          setSaving(false);
          return;
        }
      }
      resetDirty();
      if (initial) {
        router.push("/artisans");
        router.refresh();
      } else {
        setSavedRecord(saved);
        if (typeof window !== "undefined") window.scrollTo({ top: 0, behavior: "smooth" });
      }
    } catch (err) {
      // A duplicate Aadhaar/Pehchan number is the deduplication working, not a breakage: show the
      // server's sentence and a way to reach the artisan who already holds the number.
      const duplicate = identityConflict(err);
      if (duplicate) {
        setConflict(duplicate);
        // Same dialog as the pre-flight catch, so a duplicate reads identically whether it was found
        // before the request or by the unique index behind it.
        setDuplicatePromptOpen(true);
        // The panel renders at the top of a long form while the researcher is at the Save button:
        // without this the save simply appears to do nothing.
        if (typeof window !== "undefined") window.scrollTo({ top: 0, behavior: "smooth" });
      } else {
        setError(readableError(err, "Unable to save artisan"));
      }
    } finally {
      setSaving(false);
      setUploadProgress(null);
    }
  }

  if (savedRecord) {
    return (
      <div className="grid gap-6">
        <div className="panel p-4">
          <p className="text-sm font-medium text-ink">
            Saved &ldquo;{savedRecord.name}&rdquo;. Continue documenting with the same context, or add another artisan.
          </p>
          <div className="mt-3 flex flex-wrap gap-2">
            <button type="button" className="field-button-secondary" onClick={() => { setSavedRecord(null); setMediaFiles([]); setEmail(""); }}>
              Add another artisan
            </button>
            <button type="button" className="field-button-secondary" onClick={() => { router.push("/artisans"); router.refresh(); }}>
              Back to artisans
            </button>
          </div>
        </div>
        <CarryForwardCards
          context={{
            artisanId: savedRecord.id,
            artisanName: savedRecord.name,
            place: savedRecord.place,
            craftId: savedRecord.craftId,
            craftName: savedRecord.craft?.name,
            workshopId: savedRecord.workshopId,
            workshopName: workshop.workshops.find((w) => w.id === savedRecord.workshopId)?.title ?? null
          }}
        />
      </div>
    );
  }

  return (
    <>
      <form
        key={formKey}
        ref={formRef}
        onSubmit={submit}
        onInput={markDirty}
        onKeyDown={handleFormEnter}
        className="panel grid gap-4 p-4"
      >
        {error ? <div className="rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div> : null}
        <CarryContextBanner offer={carry.applied} onChange={clearCarriedContext} />
        {conflict ? (
          <div role="alert" className="rounded-md border border-amber-500 bg-amber-100 px-3 py-2 text-sm text-amber-800">
            <p className="font-medium">{conflict.message}</p>
            {conflict.existingArtisan ? (
              <Link className="mt-1 inline-block font-medium underline" href={`/artisans/${conflict.existingArtisan.id}/edit`}>
                Open {conflict.existingArtisan.name}
                {conflict.existingArtisan.place ? ` (${conflict.existingArtisan.place})` : ""}
              </Link>
            ) : null}
            <p className="mt-1 text-xs">Nothing was saved. Correct the number, or edit the existing record instead.</p>
          </div>
        ) : null}
        <div className="grid gap-3 md:grid-cols-2">
          {/* Android parity (ArtisanForm): the workshop opens the form, because it is the context
              every other answer belongs to — not merely the first dropdown. */}
          <WorkshopSelect state={workshop} onDirty={markDirty} saving={saving} />
          <Field label="Name" required>
            {/* Name, new craft name and place are title-cased by the API on write, so the box says
                what will actually be stored (Android parity — see components/forms/TitleCasedInput). */}
            <TitleCasedInput name="name" required defaultValue={initial?.name ?? ""} />
          </Field>
          <Field label="Local name">
            <TextInput name="localName" defaultValue={initial?.localName ?? ""} />
          </Field>
          <Field label="Craft" required>
            <Select
              name="craftId"
              value={craftId}
              onChange={(event) => {
                setCraftId(event.target.value);
                // An explicit pick replaces the remembered craft and retires the banner: from here
                // on what is on screen is the researcher's own choice, not a suggestion.
                const craft = craftOptions.find((candidate) => candidate.id === event.target.value);
                if (craft) carry.remember({ craftId: craft.id, craftName: craft.name }, { explicit: true });
                markDirty();
              }}
            >
              {/* This placeholder is what a browser falls back to when `value` matches no <option>,
                  so until `craftOptions` carried the record's own craft it doubled as "linked to a
                  craft that is not on page one" — see `offPageCraft` above. */}
              <option value="">Select existing craft</option>
              {craftOptions.map((craft) => (
                <option value={craft.id} key={craft.id}>
                  {craft.name}
                </option>
              ))}
            </Select>
            <CappedListNotice cuts={[craftCut]} />
          </Field>
          <Field label="Or new craft name">
            <TitleCasedInput name="newCraftName" placeholder="Used when no existing craft is selected" />
          </Field>
          <Field label="Place" required>
            <TitleCasedInput name="place" required defaultValue={initial?.place ?? ""} />
          </Field>
          <Field label="Gender">
            <Select name="gender" defaultValue={initial?.gender?.trim() ? initial.gender : "Male"} onChange={markDirty}>
              {genderOptions.map((option) => (
                <option key={option}>{option}</option>
              ))}
            </Select>
          </Field>
          <Field label="Phone">
            <PhoneField name="phone" defaultValue={initial?.phone} onValueChange={markDirty} />
          </Field>
          <Field label="Email">
            <TextInput
              name="email"
              type="email"
              pattern="[^\s@]+@[^\s@]+\.[^\s@]+"
              title="name@example.com"
              value={email}
              aria-invalid={!!emailError}
              onChange={(event) => setEmail(event.target.value)}
            />
            {emailError ? <p className="text-xs text-error-600">{emailError}</p> : null}
          </Field>
          {/*
            DICTATION BUT NOT RICH TEXT, and the split is the whole point of there being two
            controls. An address is three lines, so a researcher standing in a courtyard genuinely
            wants to speak it rather than thumb it in — but a bold word or a bulleted list in a
            postal address is meaningless, and a formatting toolbar here would be an invitation to
            store a document in the column `record_fields.py:257` prints as "Address" in the CSV and
            the workbook.
          */}
          <DictatedTextArea
            name="address"
            label="Address"
            defaultValue={initial?.address ?? ""}
            onDirty={markDirty}
          />
          {/*
            NOTES IS THE ONE LARGE NARRATIVE BOX ON THIS FORM, so it is the one that gets the editor.

            IT REPLACES `MultiNoteField`, whose several textareas were joined with a blank line into
            this same `Artisan.notes` column. `join="paragraph"` is what keeps that contract: an
            unformatted document is written back blank-line separated, so `MultiNoteField` in
            `components/FormControls.tsx` — which still edits this same column on the questionnaire
            and workshop pages, and splits it on a blank line — and `MultiNoteInput` in Android's
            `MainActivity.kt` both still reconstruct exactly the notes written here. Drop that
            argument and four notes silently become one the next time the record is opened on a
            handset or on either of those two pages.

            WHAT THE RESEARCHER LOSES is the explicit "Add note" button; what they gain is a real
            list control, a microphone, and the ability to write a paragraph that is longer than two
            rows without it looking like a mistake. The stored value is the same shape either way,
            which is why this substitution needs no migration and no Android release.

            The EXIF remark that `submit` appends to this field goes through `appendStoredParagraph`
            rather than `appendRemarksWithExif` for the reason given up there.
          */}
          <RichTextField
            name="notes"
            label="Notes"
            defaultValue={initial?.notes ?? ""}
            join="paragraph"
            className="md:col-span-2"
            onDirty={markDirty}
          />
          {/* Android parity (ArtisanForm): the three identity answers sit after the contact and
              notes fields and before Do's/Don'ts. Grouping them makes the dependency between
              "holds a card" and "card number" obvious at a glance. */}
          <div
            role="group"
            aria-labelledby={identityLabelId}
            className="grid gap-3 rounded-lg border border-line-200 bg-surface-50 p-3 md:col-span-2 md:grid-cols-3"
          >
            <div className="md:col-span-3">
              <h3 id={identityLabelId} className="field-label">
                Identity
              </h3>
              <p className="mt-0.5 text-xs text-ink-muted">
                Government identifiers, kept so the same artisan documented at two workshops resolves
                to one record. Stored securely and masked on every shared or exported view.
                {aadhaarRequired
                  ? ""
                  : " This artisan was recorded before an Aadhaar number was required, so the record still saves without one — add it only if the artisan is willing."}
              </p>
            </div>
            <AadhaarField
              defaultValue={initial?.aadhaarNumber}
              excludeArtisanId={initial?.id ?? null}
              required={aadhaarRequired}
              onValueChange={markDirty}
            />
            <PehchanFields
              initialAvailable={initial?.pehchanCardAvailable ?? true}
              initialNumber={initial?.pehchanCardNumber}
              onDirty={markDirty}
            />
          </div>
          <DosDontsField
            name="dos"
            label="Do's (positive prompt)"
            helper="Lessons from years at the craft — the things the artisan has learnt to do. Press Enter for each new point."
            defaultValue={initial?.dos}
          />
          <DosDontsField
            name="donts"
            label="Don'ts (negative prompt)"
            helper="Lessons from years at the craft — the things the artisan has learnt not to do / to avoid. Press Enter for each new point."
            defaultValue={initial?.donts}
          />
          <StatusField canSetStatus={canSetStatus} initialStatus={initial?.status} onDirty={markDirty} />
        </div>
        {initial ? <ExistingMedia linkedRecordType="artisan" linkedRecordId={initial.id} /> : null}
        <MediaCaptureField
          files={mediaFiles}
          onFilesChange={(files) => {
            setMediaFiles(files);
            markDirty();
          }}
          title="Artisan media"
          description="Attach or capture artisan images, audio introductions, videos, and documents. Image EXIF is retained and summarized in notes."
        />
        {/*
          `statedPlace` is the free-text box the researchers used while there was no district column
          — "Bagru, Jaipur, Rajasthan", "Rudraprayag, Dehradun" — and it is passed READ ONLY, so the
          card can tell a researcher that this record's Kharagpur coordinates disagree with the
          Rajasthan place they typed. Nothing is parsed out of it and written back.
        */}
        <LocationFields
          initial={initialLocation}
          onDirty={markDirty}
          subjectLabel="the artisan"
          statedPlace={initial?.place}
        />
        {uploadProgress ? <UploadProgress progress={uploadProgress} /> : null}
        <div className="flex justify-end gap-2">
          <button type="button" className="field-button-secondary" onClick={handleBack}>
            Cancel
          </button>
          <button className="field-button" disabled={saving || checkingDuplicate}>
            {checkingDuplicate ? "Checking..." : saving ? "Saving..." : initial ? "Update artisan" : "Save artisan"}
          </button>
        </div>
      </form>
      <DuplicateArtisanDialog
        open={duplicatePromptOpen}
        artisan={conflict?.existingArtisan}
        message={conflict?.message}
        maskedValue={conflict?.maskedValue}
        onOpenExisting={() => {
          setDuplicatePromptOpen(false);
          // Leaving for the other record discards this one either way, so drop the guard rather than
          // making the researcher answer a second "unsaved changes" prompt on the way out.
          resetDirty();
          if (conflict?.existingArtisan) router.push(`/artisans/${conflict.existingArtisan.id}/edit`);
        }}
        onDiscard={discardEntry}
        onKeepEditing={() => setDuplicatePromptOpen(false)}
      />
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
