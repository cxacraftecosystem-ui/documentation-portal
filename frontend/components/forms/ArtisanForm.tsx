"use client";

import { useEffect, useId, useMemo, useRef, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";

import { useAuth } from "@/components/AuthProvider";
import { CarryForwardCards } from "@/components/CarryForwardCards";
import { Field, Select, TextInput } from "@/components/FormControls";
import { AadhaarField, aadhaarValidationError, isMaskedIdentityNumber } from "@/components/forms/AadhaarField";
import { CarryContextBanner, carryScope, useCarryContext, type CarryScopeState } from "@/components/forms/CarryContextBanner";
import { DraftOfferCard, useFormDraft } from "@/components/forms/FormDraft";
import { DosDontsField } from "@/components/forms/DosDontsField";
import { DuplicateArtisanDialog } from "@/components/forms/DuplicateArtisanDialog";
import { ArtisanLookup, type ArtisanLookupStart } from "@/components/forms/ArtisanLookup";
import { failedFilesOf, RetryFailedUploads } from "@/components/media/RetryFailedUploads";
import { Toggle } from "@/components/ui/Toggle";
import { PossibleDuplicateDialog } from "@/components/duplicates/PossibleDuplicateDialog";
import { PossibleDuplicates } from "@/components/duplicates/PossibleDuplicates";
import { LocationFields, type LocationInitialValues } from "@/components/forms/LocationFields";
import { MediaCaptureField } from "@/components/forms/MediaCaptureField";
import { PhoneField } from "@/components/forms/PhoneField";
import { GENDER_OPTIONS } from "@/lib/standardValues";
import { CraftSelect } from "@/components/forms/CraftSelect";
import { FieldBlock } from "@/components/tasks/TaskPrimitives";
import { useWorkshopSelection, WorkshopSelect } from "@/components/forms/WorkshopSelect";
import { ExistingMedia } from "@/components/media/ExistingMedia";
import { LiveCompleteness, locationOnForm, photosPicked } from "@/components/records/LiveCompleteness";
import { UploadProgress } from "@/components/media/UploadProgress";
import { DictatedTextArea } from "@/components/richtext/DictatedTextArea";
import { DictatedTextInput } from "@/components/richtext/DictatedTextInput";
import { DictationUnavailableNotice } from "@/components/richtext/DictationUnavailableNotice";
import { RichTextField } from "@/components/richtext/RichTextField";
import { CalendarField } from "@/components/ui/CalendarField";
import { RequiredMark } from "@/components/ui/RequiredMark";
import { toIsoDate } from "@/components/forms/DateTimeField";
import { UnsavedChangesDialog } from "@/components/UnsavedChangesDialog";
import { statusLabel } from "@/components/StatusBadge";
import { useLeaveGuard } from "@/components/UnsavedChangesGuard";
import { ApiError, apiErrorMessage, apiFetch } from "@/lib/api";
import { CRAFT_NOT_IN_LIST, useCraftCatalogue } from "@/lib/craftCatalogue";
import { ARTISAN_FORM_CONTRACT, missingArtisanDatesSentence } from "@/lib/artisanFormContract";
import {
  locationFromForm,
  recordedAtFromForm,
  recordedTimezoneFromForm,
  textValue,
  useFocusField,
  useUnsavedChanges
} from "@/lib/forms";
import { handleFormEnter } from "@/lib/formNav";
import { collectExifMetadata, exifMetadataToRemark, uploadMediaBatch, type BatchProgress } from "@/lib/media";
import {
  acknowledgedIds,
  candidatesToShow,
  checkDuplicates,
  checkInputFrom,
  checkKey,
  possibleDuplicateHold,
  type DuplicateCandidate,
  type PossibleDuplicateHold
} from "@/lib/duplicates";
import { saveOrQueue } from "@/lib/offline";
import { artisanBody, PEHCHAN_NO } from "@/lib/recordPayloads";
import { canManageCrafts, hasRank } from "@/lib/permissions";
import type { AadhaarLookupResult, Artisan, ArtisanIdentityConflict, ArtisanIdentityMatch, RecordStatus } from "@/lib/types";
import { ButtonRow } from "@/components/ui/ButtonRow";
import { RecordingConsentToggle } from "@/components/forms/RecordingConsentToggle";
import { SharingConsentToggle } from "@/components/forms/SharingConsentToggle";

const genderOptions = GENDER_OPTIONS;

const EXPERIENCE_MONTH_OPTIONS = Array.from({ length: 12 }, (_, month) => month);

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

const PEHCHAN_YES = "true";

const PEHCHAN_NUMBER_REQUIRED =
  "Enter the Pehchan Card number, or set the card to No.";

function errorDetail(error: unknown): unknown {
  if (!(error instanceof ApiError)) return null;
  const payload = error.payload;
  if (!payload || typeof payload !== "object" || !("detail" in payload)) return null;
  return (payload as { detail: unknown }).detail;
}

function readableError(error: unknown, fallback: string): string {
  return apiErrorMessage(error, fallback);
}

function identityConflict(error: unknown): ArtisanIdentityConflict | null {
  if (!(error instanceof ApiError) || error.status !== 409) return null;
  const detail = errorDetail(error);
  if (!detail || typeof detail !== "object") return null;
  const conflict = detail as ArtisanIdentityConflict;
  return conflict.code === "artisan_identity_conflict" ? conflict : null;
}

async function findArtisanByAadhaar(digits: string | null, excludeArtisanId: string | null): Promise<ArtisanIdentityMatch | null> {
  const number = (digits ?? "").trim();
  if (!number || isMaskedIdentityNumber(number) || aadhaarValidationError(number)) return null;
  try {
    const result = await apiFetch<AadhaarLookupResult>("/artisans/lookup/aadhaar", {
      method: "POST",
      body: JSON.stringify({ number })
    });
    const found = result.found ? (result.artisan ?? null) : null;
    return found && found.id !== excludeArtisanId ? found : null;
  } catch {
    return null;
  }
}

function PehchanFields({
  initialAvailable,
  initialNumber,
  onDirty
}: {
  initialAvailable: boolean | null;
  initialNumber?: string | null;
  onDirty: () => void;
}) {
  const baseId = useId();
  const numberId = `${baseId}-pehchan-number`;
  const hintId = `${baseId}-pehchan-hint`;
  const [answer, setAnswer] = useState<boolean | null>(initialAvailable);
  const available = answer === true;
  const storedMask = isMaskedIdentityNumber(initialNumber) ? String(initialNumber).trim() : null;
  const [replacing, setReplacing] = useState(false);
  const [number, setNumber] = useState(storedMask ? "" : (initialNumber ?? ""));
  const keptMask = replacing || !available ? null : storedMask;

  return (
    <>
      <div className="grid content-start gap-1">
        <span className="field-label">
          Artisan Pehchan Card available
          <RequiredMark when />
        </span>
        <Select
          name="pehchanCardAvailable"
          value={answer === null ? "" : answer ? PEHCHAN_YES : PEHCHAN_NO}
          aria-label="Artisan Pehchan Card available"
          required
          onChange={(event) => {
            const next = event.target.value === PEHCHAN_YES;
            setAnswer(event.target.value === "" ? null : next);
            if (!next) setNumber("");
            onDirty();
          }}
        >
          <option value="">Select…</option>
          <option value={PEHCHAN_YES}>Yes</option>
          <option value={PEHCHAN_NO}>No</option>
        </Select>
      </div>
      <div className="grid content-start gap-1">
        <label className="field-label" htmlFor={numberId}>
          Artisan Pehchan Card number
          <RequiredMark when={available} />
        </label>
        <input
          id={numberId}
          name="pehchanCardNumber"
          className="field-input read-only:bg-surface-50 read-only:text-ink-500 disabled:cursor-not-allowed disabled:bg-surface-50 disabled:text-ink-500"
          type="text"
          autoComplete="off"
          placeholder={available ? "As printed on the card" : answer === null ? "Answer the card question first" : "No card on record"}
          value={keptMask ?? number}
          readOnly={Boolean(keptMask)}
          required={available}
          disabled={!available}
          aria-disabled={!available}
          aria-describedby={hintId}
          onInvalid={(event) => event.currentTarget.setCustomValidity(PEHCHAN_NUMBER_REQUIRED)}
          onChange={(event) => {
            event.currentTarget.setCustomValidity("");
            setNumber(event.currentTarget.value.toUpperCase());
          }}
        />
        <p id={hintId} className="text-xs text-ink-muted">
          {answer === null
            ? "Has the artisan a Pehchan card?"
            : !available
            ? "No Pehchan card."
            : keptMask
              ? "On file and hidden. Saving keeps it."
              : "The PM Vishwakarma ID on the card."}
          {keptMask ? (
            <>
              {" "}
              <button
                type="button"
                className="font-semibold text-purple-700 dark:text-purple-300 underline"
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
                className="font-semibold text-purple-700 dark:text-purple-300 underline"
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
            <option key={status} value={status}>{statusLabel(status)}</option>
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
        title="A reviewer sets the status."
      >
        {text}
      </span>
    </div>
  );
}

const ARTISAN_DRAFT_FORM_FIELDS = ["gender", "dateOfBirth", "craftStartDate", "experienceYears", "experienceMonths", "phone", "phoneCountryCode", "address", "notes", "dos", "donts"];

export function ArtisanForm({ initial }: { initial?: Artisan }) {
  const router = useRouter();
  const { user } = useAuth();
  const canSetStatus = hasRank(user, "PROFESSOR");
  const identityLabelId = `${useId()}-identity`;
  const dateFieldsId = useId();
  const formRef = useRef<HTMLFormElement>(null);
  const [craftId, setCraftId] = useState(initial?.craftId ?? "");
  const [mediaFiles, setMediaFiles] = useState<File[]>([]);
  useFocusField();
  const completenessCounts = useMemo(() => ({ photos: photosPicked(mediaFiles) }), [mediaFiles]);
  const [error, setError] = useState<string | null>(null);
  const [conflict, setConflict] = useState<ArtisanIdentityConflict | null>(null);
  const [duplicatePromptOpen, setDuplicatePromptOpen] = useState(false);
  const [checkingDuplicate, setCheckingDuplicate] = useState(false);
  const [duplicateCandidates, setDuplicateCandidates] = useState<DuplicateCandidate[]>([]);
  const [dismissedDuplicates, setDismissedDuplicates] = useState<string[]>([]);
  const dismissedRef = useRef<string[]>([]);
  const [duplicateHold, setDuplicateHold] = useState<PossibleDuplicateHold | null>(null);
  const lastCheckKey = useRef("");
  const checkTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const [saving, setSaving] = useState(false);
  const [uploadProgress, setUploadProgress] = useState<BatchProgress | null>(null);
  const [savedRecord, setSavedRecord] = useState<Artisan | null>(null);
  const [email, setEmail] = useState(initial?.email ?? "");
  const [name, setName] = useState(initial?.name ?? "");
  const [localName, setLocalName] = useState(initial?.localName ?? "");
  const [place, setPlace] = useState(initial?.place ?? "");
  const [formKey, setFormKey] = useState(0);
  const [lookup, setLookup] = useState<ArtisanLookupStart | null>(initial ? { name: "", aadhaar: "" } : null);
  const [aadhaarNotShared, setAadhaarNotShared] = useState(Boolean(initial?.aadhaarNotShared));
  const [retry, setRetry] = useState<{ files: File[]; upload: (files: File[]) => Promise<{ failed: Array<{ name: string; error?: string }> }> } | null>(null);
  const { dirty, markDirty, resetDirty } = useUnsavedChanges();
  const [backPromptOpen, setBackPromptOpen] = useState(false);
  useLeaveGuard(dirty, () => setBackPromptOpen(true));
  const initialLocation = initial
    ? ((initial as Artisan & { location?: LocationInitialValues | null }).location ?? null)
    : undefined;
  const aadhaarRequired =
    (!initial || Boolean(initial.aadhaarNumber?.trim()) || Boolean(initial.aadhaarNotShared)) && !aadhaarNotShared;
  const notSharedOffered = !initial?.aadhaarNumber?.trim();
  const workshop = useWorkshopSelection({
    initialWorkshopId: initial?.workshopId,
    isEdit: Boolean(initial),
    resetKey: initial?.id ?? null
  });
  const [restored, setRestored] = useState<Record<string, string>>({});
  const [draftTick, setDraftTick] = useState(0);
  const draft = useFormDraft({
    kind: "artisan",
    recordId: initial?.id ?? null,
    dirty,
    label: name.trim(),
    fields: { name, localName, craftId, place, email, mediaFiles, workshopId: workshop.workshopId, tick: draftTick },
    collect: () => {
      const form = formRef.current ? new FormData(formRef.current) : null;
      const out: Record<string, string> = {};
      for (const field of ARTISAN_DRAFT_FORM_FIELDS) {
        const value = form?.get(field);
        if (typeof value === "string") out[`form.${field}`] = value;
      }
      return out;
    },
    onRestore: (fields) => {
      const text = (key: string) => (typeof fields[key] === "string" ? (fields[key] as string) : null);
      const setters: Array<[string, (value: string) => void]> = [
        ["name", setName],
        ["localName", setLocalName],
        ["craftId", setCraftId],
        ["place", setPlace],
        ["email", setEmail]
      ];
      for (const [key, set] of setters) {
        const value = text(key);
        if (value !== null) set(value);
      }
      if (Array.isArray(fields.mediaFiles)) setMediaFiles(fields.mediaFiles as File[]);
      const workshopId = text("workshopId");
      if (workshopId) workshop.setWorkshopId(workshopId);
      const seeded: Record<string, string> = {};
      for (const [key, value] of Object.entries(fields)) {
        if (key.startsWith("form.") && typeof value === "string") seeded[key.slice(5)] = value;
      }
      setRestored(seeded);
      setFormKey((key) => key + 1);
    }
  });

  const emailError =
    email.trim() && !EMAIL_RE.test(email.trim()) ? "Enter a valid email address (name@example.com)." : null;

  const craftCatalogue = useCraftCatalogue();
  const craftOptions = useMemo(() => craftCatalogue.catalogue?.crafts ?? [], [craftCatalogue.catalogue]);
  const craftListState: CarryScopeState =
    craftCatalogue.catalogue ? "loaded" : craftCatalogue.loading ? "pending" : "unavailable";

  const carry = useCarryContext({
    enabled: !initial,
    scopes: [carryScope("craft", craftListState, craftOptions)],
    applies: ["craft", "workshop"],
    onApply: (context) => {
      if (context.craftId) setCraftId(context.craftId);
      if (context.workshopId && !workshop.touched) workshop.setWorkshopId(context.workshopId);
    }
  });
  function clearCarriedContext() {
    carry.change();
    setCraftId("");
  }

  function duplicateCheckInput(form: FormData | null) {
    if (!form) return null;
    return checkInputFrom({
      name: textValue(form, "name") ?? "",
      localName: textValue(form, "localName"),
      gender: textValue(form, "gender"),
      dateOfBirth: textValue(form, "dateOfBirth"),
      phone: textValue(form, "phone"),
      place: textValue(form, "place"),
      state: textValue(form, "state"),
      district: textValue(form, "district"),
      village: textValue(form, "village"),
      craftId: textValue(form, "craftId"),
      aadhaar: textValue(form, "aadhaarNumber"),
      pehchanCardNumber: textValue(form, "pehchanCardNumber")
    });
  }

  async function runDuplicateCheck(form: FormData | null) {
    if (initial) return;
    if (typeof navigator !== "undefined" && navigator.onLine === false) return;
    const input = duplicateCheckInput(form);
    const key = checkKey(input);
    if (!input || key === lastCheckKey.current) return;
    lastCheckKey.current = key;
    try {
      setDuplicateCandidates(await checkDuplicates(input));
    } catch {
    }
  }

  function scheduleDuplicateCheck(event: React.FocusEvent<HTMLFormElement>) {
    if (initial) return;
    const target = event.target as HTMLElement & { name?: string };
    const field = target?.name ?? target?.closest?.("[data-duplicate-field]")?.getAttribute("data-duplicate-field") ?? "";
    if (!["name", "district", "phone", "localName", "dateOfBirth"].includes(field)) return;
    if (checkTimer.current) clearTimeout(checkTimer.current);
    checkTimer.current = setTimeout(() => {
      void runDuplicateCheck(formRef.current ? new FormData(formRef.current) : null);
    }, 600);
  }

  function dismissDuplicates(ids: string[]) {
    const next = acknowledgedIds([...dismissedRef.current, ...ids]);
    dismissedRef.current = next;
    setDismissedDuplicates(next);
  }

  useEffect(() => () => {
    if (checkTimer.current) clearTimeout(checkTimer.current);
  }, []);

  const shownDuplicates = candidatesToShow(duplicateCandidates, dismissedDuplicates);

  function handleBack() {
    if (dirty) setBackPromptOpen(true);
    else router.back();
  }

  function discardEntry() {
    setDuplicatePromptOpen(false);
    setConflict(null);
    setError(null);
    setDuplicateCandidates([]);
    setDuplicateHold(null);
    dismissedRef.current = [];
    setDismissedDuplicates([]);
    lastCheckKey.current = "";
    setMediaFiles([]);
    setEmail(initial?.email ?? "");
    setName(initial?.name ?? "");
    setLocalName(initial?.localName ?? "");
    setPlace(initial?.place ?? "");
    setRestored({});
    draft.clear();
    resetDirty();
    setFormKey((key) => key + 1);
    if (typeof window !== "undefined") window.scrollTo({ top: 0, behavior: "smooth" });
  }

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    setError(null);
    setConflict(null);
    setCheckingDuplicate(true);
    const existing = aadhaarNotShared ? null : await findArtisanByAadhaar(textValue(form, "aadhaarNumber"), initial?.id ?? null);
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
    if (!initial) {
      setCheckingDuplicate(true);
      await runDuplicateCheck(form);
      setCheckingDuplicate(false);
    }
    if (!(await workshop.confirmSubmission())) return;
    setSaving(true);
    try {
      const exifItems = await collectExifMetadata(mediaFiles);
      const exifRemark = exifMetadataToRemark(exifItems);
      const recordedAt = recordedAtFromForm(form);
      const recordedTimezone = recordedTimezoneFromForm(form);
      const location = locationFromForm(form);
      const craftId = textValue(form, "craftId");
      if (!craftId) {
        setError(CRAFT_NOT_IN_LIST);
        setSaving(false);
        return;
      }
      const missingDates = missingArtisanDatesSentence({
        dateOfBirth: textValue(form, "dateOfBirth"),
        craftStartDate: textValue(form, "craftStartDate")
      });
      if (missingDates) {
        setError(missingDates);
        setSaving(false);
        return;
      }
      const payload = artisanBody(form, {
        isUpdate: Boolean(initial),
        craftId,
        aadhaarNotShared,
        storedAadhaarNotShared: Boolean(initial?.aadhaarNotShared),
        workshopId: workshop.workshopId,
        initialStatus: initial?.status,
        dismissed: dismissedRef.current,
        formContract: ARTISAN_FORM_CONTRACT,
        exifItems,
        exifRemark
      });
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
        draft.clear();
        resetDirty();
        if (typeof window !== "undefined") window.scrollTo({ top: 0, behavior: "smooth" });
        setSaving(false);
        return;
      }
      const saved = outcome.saved;
      if (mediaFiles.length) {
        const uploadTo = (files: File[]) =>
          uploadMediaBatch({
            files,
            linkedRecordType: "artisan",
            linkedRecordId: saved.id,
            caption: `Field media for ${saved.name}`,
            location,
            recordedAt,
            recordedTimezone,
            extraMetadata: exifItems.length ? { mediaExif: exifItems } : undefined,
            onProgress: setUploadProgress
          });
        const { failed } = await uploadTo(mediaFiles);
        setUploadProgress(null);
        if (failed.length) {
          draft.clear();
          resetDirty();
          setRetry({ files: failedFilesOf(mediaFiles, failed), upload: uploadTo });
          if (!initial) setSavedRecord(saved);
          setSaving(false);
          return;
        }
      }
      draft.clear();
      resetDirty();
      if (initial) {
        router.push("/artisans");
        router.refresh();
      } else {
        setSavedRecord(saved);
        if (typeof window !== "undefined") window.scrollTo({ top: 0, behavior: "smooth" });
      }
    } catch (err) {
      const duplicate = identityConflict(err);
      const hold = possibleDuplicateHold(err);
      if (hold) {
        setDuplicateCandidates(hold.candidates);
        setDuplicateHold(hold);
      } else if (duplicate) {
        setConflict(duplicate);
        setDuplicatePromptOpen(true);
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
        {retry ? <RetryFailedUploads files={retry.files} upload={retry.upload} onDone={() => setRetry(null)} /> : null}
        <div className="panel p-4">
          <p className="text-sm font-medium text-ink">
            Saved &ldquo;{savedRecord.name}&rdquo;.
          </p>
          <ButtonRow align="start" className="mt-3">
            <button
              type="button"
              className="field-button-secondary"
              onClick={() => {
                setSavedRecord(null);
                setRetry(null);
                setMediaFiles([]);
                setEmail("");
                setAadhaarNotShared(false);
                setLookup(null);
                setName("");
                setLocalName("");
                setPlace("");
              }}
            >
              Add another artisan
            </button>
            <button type="button" className="field-button-secondary" onClick={() => { router.push("/artisans"); router.refresh(); }}>
              Back to artisans
            </button>
          </ButtonRow>
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

  if (!lookup) {
    return (
      <ArtisanLookup
        onStart={(start) => {
          setLookup(start);
          if (start.name) setName(start.name);
        }}
      />
    );
  }

  return (
    <>
      {retry && initial ? (
        <RetryFailedUploads
          files={retry.files}
          upload={retry.upload}
          onDone={() => {
            setRetry(null);
            router.push("/artisans");
            router.refresh();
          }}
        />
      ) : null}
      <form
        key={formKey}
        ref={formRef}
        onSubmit={submit}
        onInput={() => {
          markDirty();
          setDraftTick((tick) => tick + 1);
        }}
        onBlur={scheduleDuplicateCheck}
        onKeyDown={handleFormEnter}
        className="panel grid gap-4 p-4"
      >
        {error ? <div className="rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div> : null}
        <DraftOfferCard draft={draft} />
        <CarryContextBanner offer={carry.applied} onChange={clearCarriedContext} />
        <LiveCompleteness
          kind="artisan"
          formRef={formRef}
          stored={initial}
          derive={artisanCompletenessFields}
          counts={completenessCounts}
        />
        {conflict ? (
          <div role="alert" className="rounded-md border border-amber-500 bg-amber-100 px-3 py-2 text-sm text-amber-800">
            <p className="font-medium">{conflict.message}</p>
            {conflict.existingArtisan ? (
              <Link className="mt-1 inline-block font-medium underline" href={`/artisans/${conflict.existingArtisan.id}/edit`}>
                Open {conflict.existingArtisan.name}
                {conflict.existingArtisan.place ? ` (${conflict.existingArtisan.place})` : ""}
              </Link>
            ) : null}
            <p className="mt-1 text-xs">Not saved. Fix the number or edit the existing record.</p>
          </div>
        ) : null}
        <DictationUnavailableNotice />
        <div className="grid gap-3 md:grid-cols-2">
          <WorkshopSelect state={workshop} onDirty={markDirty} saving={saving} />
          <DictatedTextInput
            name="name"
            label="Name"
            required
            titleCased
            explainWhenUnavailable={false}
            value={name}
            onChange={(next) => {
              setName(next);
              markDirty();
            }}
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
          <div data-field="craft" className="min-w-0">
            <FieldBlock label="Craft" required>
            <CraftSelect
              name="craftId"
              ariaLabel="Craft"
              required
              value={craftId}
              canAddCraft={canManageCrafts(user)}
              fallbackLabels={initial?.craftId && initial.craft?.name ? { [initial.craftId]: initial.craft.name } : undefined}
              onDirty={markDirty}
              onChange={(next, craft) => {
                setCraftId(next);
                if (craft) carry.remember({ craftId: craft.id, craftName: craft.name }, { explicit: true });
              }}
            />
            </FieldBlock>
          </div>
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
          <Field label="Gender">
            <Select name="gender" defaultValue={restored.gender ?? initial?.gender?.trim() ?? ""} onChange={markDirty}>
              <option value="">Select gender</option>
              {genderOptions.map((option) => (
                <option key={option}>{option}</option>
              ))}
            </Select>
          </Field>
          <div className="grid min-w-0 gap-1" data-field="dateOfBirth">
            <label htmlFor={`${dateFieldsId}-dob`} className="field-label">
              Date of birth
              <RequiredMark when />
            </label>
            <CalendarField
              id={`${dateFieldsId}-dob`}
              name="dateOfBirth"
              defaultValue={restored.dateOfBirth ?? (initial?.dateOfBirth ? String(initial.dateOfBirth).slice(0, 10) : "")}
              max={toIsoDate(new Date())}
              required
              clearable
              onChange={() => markDirty()}
            />
          </div>
          <div className="grid min-w-0 gap-1" data-field="craftStartDate">
            <label htmlFor={`${dateFieldsId}-craft-start`} className="field-label">
              Craft start date
              <RequiredMark when />
            </label>
            <CalendarField
              id={`${dateFieldsId}-craft-start`}
              name="craftStartDate"
              defaultValue={restored.craftStartDate ?? (initial?.craftStartDate ? String(initial.craftStartDate).slice(0, 10) : "")}
              max={toIsoDate(new Date())}
              required
              clearable
              onChange={() => markDirty()}
            />
          </div>
          <Field label="Experience (years)">
            <TextInput
              name="experienceYears"
              type="number"
              inputMode="numeric"
              min={0}
              max={90}
              step={1}
              defaultValue={restored.experienceYears ?? initial?.experienceYears ?? ""}
              onChange={markDirty}
            />
          </Field>
          <Field label="Experience (months)">
            <Select
              name="experienceMonths"
              defaultValue={restored.experienceMonths ?? (initial?.experienceMonths != null ? String(initial.experienceMonths) : "")}
              onChange={markDirty}
            >
              <option value="">Not stated</option>
              {EXPERIENCE_MONTH_OPTIONS.map((month) => (
                <option key={month} value={String(month)}>
                  {month === 1 ? "1 month" : `${month} months`}
                </option>
              ))}
            </Select>
          </Field>
          <Field label="Phone">
            <PhoneField
              name="phone"
              countryCodeName="phoneCountryCode"
              countryCode={restored.phone !== undefined ? restored.phoneCountryCode : initial?.phoneCountryCode}
              defaultValue={restored.phone ?? initial?.phone}
              onValueChange={markDirty}
            />
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
          <DictatedTextArea
            name="address"
            label="Address"
            defaultValue={restored.address ?? initial?.address ?? ""}
            explainWhenUnavailable={false}
            onDirty={markDirty}
          />
          <RichTextField
            name="notes"
            label="Notes"
            defaultValue={restored.notes ?? initial?.notes ?? ""}
            join="paragraph"
            className="md:col-span-2"
            explainWhenUnavailable={false}
            onDirty={markDirty}
          />
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
                Masked on every shared or exported view.
                {aadhaarRequired || aadhaarNotShared ? "" : " Optional for this older record — add only with the artisan’s consent."}
              </p>
            </div>
            {aadhaarNotShared ? (
              <div className="grid content-start gap-1">
                <span className="field-label">Aadhaar number</span>
                <p className="rounded-md border border-amber-500 bg-amber-100 px-3 py-2 text-xs text-amber-800">
                  Not shared by artisan — flagged for follow-up.
                </p>
              </div>
            ) : (
              <AadhaarField
                defaultValue={initial?.aadhaarNumber ?? (lookup.aadhaar || null)}
                excludeArtisanId={initial?.id ?? null}
                required={aadhaarRequired}
                onValueChange={markDirty}
              />
            )}
            <PehchanFields
              initialAvailable={initial ? (initial.pehchanCardAvailable ?? true) : null}
              initialNumber={initial?.pehchanCardNumber}
              onDirty={markDirty}
            />
            {notSharedOffered ? (
              <Toggle
                className="md:col-span-3"
                label="Not shared by artisan"
                description="Save without an Aadhaar number. The record is flagged for follow-up."
                checked={aadhaarNotShared}
                onChange={(next) => {
                  setAadhaarNotShared(next);
                  markDirty();
                }}
              />
            ) : null}
            {initial?.id ? <RecordingConsentToggle artisanId={initial.id} /> : null}
            {initial?.id ? <SharingConsentToggle artisanId={initial.id} /> : null}
          </div>
          <DosDontsField
            name="dos"
            fieldKey="dos"
            label="Do's"
            helper="One point per line."
            defaultValue={restored.dos ?? initial?.dos}
          />
          <DosDontsField
            name="donts"
            fieldKey="donts"
            label="Don'ts"
            helper="One point per line."
            defaultValue={restored.donts ?? initial?.donts}
          />
          <StatusField canSetStatus={canSetStatus} initialStatus={initial?.status} onDirty={markDirty} />
        </div>
        <div className="grid gap-4" data-field="photo">
          {initial ? <ExistingMedia linkedRecordType="artisan" linkedRecordId={initial.id} /> : null}
          <MediaCaptureField
            files={mediaFiles}
            onFilesChange={(files) => {
              setMediaFiles(files);
              markDirty();
            }}
            title="Artisan media"
            description="Photos, audio, video and documents."
            artisanIds={initial?.id ? [initial.id] : undefined}
          />
        </div>
        <div data-field="location">
          <LocationFields
            initial={initialLocation}
            onDirty={markDirty}
            subjectLabel="the artisan"
            statedPlace={initial?.place}
          />
        </div>
        {!initial ? (
          <PossibleDuplicates
            candidates={shownDuplicates}
            onDifferent={() => dismissDuplicates(shownDuplicates.map((c) => c.artisan.id))}
          />
        ) : null}
        {uploadProgress ? <UploadProgress progress={uploadProgress} /> : null}
        <ButtonRow align="end">
          <button type="button" className="field-button-secondary" onClick={handleBack}>
            Cancel
          </button>
          <button className="field-button" disabled={saving || checkingDuplicate}>
            {checkingDuplicate ? "Checking..." : saving ? "Saving..." : initial ? "Update artisan" : "Save artisan"}
          </button>
        </ButtonRow>
      </form>
      <DuplicateArtisanDialog
        open={duplicatePromptOpen}
        artisan={conflict?.existingArtisan}
        message={conflict?.message}
        maskedValue={conflict?.maskedValue}
        onOpenExisting={() => {
          setDuplicatePromptOpen(false);
          resetDirty();
          if (conflict?.existingArtisan) router.push(`/artisans/${conflict.existingArtisan.id}/edit`);
        }}
        onDiscard={discardEntry}
        onKeepEditing={() => setDuplicatePromptOpen(false)}
      />
      <PossibleDuplicateDialog
        hold={duplicateHold}
        busy={saving}
        onKeepEditing={() => setDuplicateHold(null)}
        onDifferent={() => {
          dismissDuplicates((duplicateHold?.candidates ?? []).map((c) => c.artisan.id));
          setDuplicateHold(null);
          formRef.current?.requestSubmit();
        }}
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

function artisanCompletenessFields(data: FormData): Record<string, unknown> {
  return { ...locationOnForm(data) };
}
