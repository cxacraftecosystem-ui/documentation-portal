"use client";

import { useEffect, useId, useRef, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";

import { useAuth } from "@/components/AuthProvider";
import { CarryForwardCards } from "@/components/CarryForwardCards";
import { Field, MultiNoteField, Select, TextArea, TextInput } from "@/components/FormControls";
import { AadhaarField, aadhaarValidationError } from "@/components/forms/AadhaarField";
import { DosDontsField } from "@/components/forms/DosDontsField";
import { DuplicateArtisanDialog } from "@/components/forms/DuplicateArtisanDialog";
import { LocationFields, type LocationInitialValues } from "@/components/forms/LocationFields";
import { MediaCaptureField } from "@/components/forms/MediaCaptureField";
import { PhoneField } from "@/components/forms/PhoneField";
import { TitleCasedInput } from "@/components/forms/TitleCasedInput";
import { useWorkshopSelection, WorkshopSelect } from "@/components/forms/WorkshopSelect";
import { ExistingMedia } from "@/components/media/ExistingMedia";
import { UploadProgress } from "@/components/media/UploadProgress";
import { UnsavedChangesDialog } from "@/components/UnsavedChangesDialog";
import { ApiError, apiFetch, buildQuery, listResource } from "@/lib/api";
import { locationFromForm, recordedAtFromForm, recordedTimezoneFromForm, requiredText, textValue, useUnsavedChanges } from "@/lib/forms";
import { handleFormEnter } from "@/lib/formNav";
import { appendRemarksWithExif, collectExifMetadata, exifMetadataToRemark, uploadMediaBatch, type BatchProgress } from "@/lib/media";
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
 */
async function findArtisanByAadhaar(digits: string | null, excludeArtisanId: string | null): Promise<ArtisanIdentityMatch | null> {
  const number = (digits ?? "").trim();
  if (!number || aadhaarValidationError(number)) return null;
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
  const [number, setNumber] = useState(initialNumber ?? "");

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
          className="field-input disabled:cursor-not-allowed disabled:bg-surface-50 disabled:text-ink-500"
          type="text"
          autoComplete="off"
          placeholder={available ? "As printed on the card" : "No card on record"}
          value={number}
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
          {available
            ? "The PM Vishwakarma artisan ID printed on the card."
            : 'Disabled because this artisan holds no Pehchan card. Switch "available" to Yes to enter a number.'}
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
   * stops a stored number being quietly emptied.
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
    listResource<Craft>("/crafts", { pageSize: 100 })
      .then((result) => setCrafts(result.items))
      .catch(() => setCrafts([]));
  }, []);

  function handleBack() {
    if (dirty) setBackPromptOpen(true);
    else router.back();
  }

  /**
   * Throw the in-progress entry away and start from a clean form.
   *
   * The workshop selection is deliberately left alone: the researcher is still standing in the same
   * workshop, and re-picking it after every discarded duplicate would be busywork.
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
      // State and pincode are columns on Location (validated against the canonical list in
      // backend/app/services/address.py), but `locationFromForm` only knows the coordinate half, so
      // they are attached here — including onto the media batch below, which is the same place.
      const coordinates = locationFromForm(form);
      const location = coordinates
        ? { ...coordinates, state: textValue(form, "state"), pincode: textValue(form, "pincode") }
        : undefined;
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
      const payload = {
        name: requiredText(form, "name"),
        localName: textValue(form, "localName"),
        gender: textValue(form, "gender"),
        phone: textValue(form, "phone"),
        email: textValue(form, "email"),
        place: requiredText(form, "place"),
        address: textValue(form, "address"),
        notes: appendRemarksWithExif(textValue(form, "notes") as string | null, exifRemark),
        // Identity. The Aadhaar mirror input carries the bare digits (the visible box only groups
        // them for reading); the card number is sent explicitly as null when the artisan holds no
        // card, so an edit that flips the answer to No clears the stored number instead of orphaning
        // it — `aadhaarNumber` and `pehchanCardNumber` are both clearable server-side.
        aadhaarNumber: textValue(form, "aadhaarNumber"),
        pehchanCardAvailable: pehchanAvailable,
        pehchanCardNumber: pehchanAvailable ? textValue(form, "pehchanCardNumber") : null,
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
            craftName: savedRecord.craft?.name
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
        <div>
          <button type="button" className="field-button-secondary" onClick={handleBack}>
            Back
          </button>
        </div>
        {error ? <div className="rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div> : null}
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
            <Select name="craftId" defaultValue={initial?.craftId ?? ""} onChange={markDirty}>
              <option value="">Select existing craft</option>
              {crafts.map((craft) => (
                <option value={craft.id} key={craft.id}>
                  {craft.name}
                </option>
              ))}
            </Select>
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
          <Field label="Address">
            <TextArea name="address" defaultValue={initial?.address ?? ""} />
          </Field>
          <MultiNoteField defaultValue={initial?.notes ?? ""} />
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
        <LocationFields initial={initialLocation} onDirty={markDirty} />
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
