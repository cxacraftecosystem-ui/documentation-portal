"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { ArrowDown, ArrowUp, ClipboardList, GripVertical, Lock, Mic, Pencil, Plus, Save, Square, Trash2 } from "lucide-react";

import { CappedListNotice } from "@/components/data/CappedListNotice";
import { deleteConfirm, useConfirm } from "@/components/dialogs/ConfirmDialog";
import { OnDeviceDictationButton } from "@/components/dictation/OnDeviceDictationButton";
import { EmptyState } from "@/components/EmptyState";
import { Field, MultiNoteField, Select, TextArea, TextInput } from "@/components/FormControls";
import { CarryContextBanner, carryScope, useCarryContext } from "@/components/forms/CarryContextBanner";
import { LocationFields } from "@/components/forms/LocationFields";
import { MediaCaptureField } from "@/components/forms/MediaCaptureField";
import { QuestionnaireCaptureControls, useCapturePrefs } from "@/components/forms/QuestionnaireCaptureControls";
import { useWorkshopSelection, WorkshopSelect } from "@/components/forms/WorkshopSelect";
import { MediaLightbox, MediaPreviewTile, type PreviewMedia } from "@/components/media/MediaLightbox";
import { UploadProgress } from "@/components/media/UploadProgress";
import { UploadTray } from "@/components/media/UploadTray";
import { RecordingStrip } from "@/components/media/Waveform";
import { DictatedTextArea } from "@/components/richtext/DictatedTextArea";
import { DictatedTextInput } from "@/components/richtext/DictatedTextInput";
import { appendDictatedPhrase } from "@/components/richtext/dictatedValue";
import { DictationUnavailableNotice } from "@/components/richtext/DictationUnavailableNotice";
import { PageHeader } from "@/components/PageHeader";
import { Pagination } from "@/components/Pagination";
import {
  artisanPickerOptions,
  artisanSetKey,
  artisansNotAtWorkshop,
  outOfWorkshopNotice,
  primaryInterviewArtisanId,
  useWorkshopArtisans,
  workshopScopeSettling
} from "@/components/questionnaires/interviewArtisans";
import { QuestionHelpText, RequiredByInstrument } from "@/components/questionnaires/QuestionHelpText";
import { RowActions, rowAction } from "@/components/RowActions";
import { SearchInput } from "@/components/SearchInput";
import { EMPTY_FUNNEL, FunnelFilters, type FunnelValue } from "@/components/FunnelFilters";
import { StatusBadge } from "@/components/StatusBadge";
import { Accordion } from "@/components/ui/Accordion";
import { MultiSelectDropdown } from "@/components/ui/Dropdown";
import { useWorkshopScope, WorkshopScopeSelect } from "@/components/WorkshopScopeSelect";
import { useAdminView } from "@/components/AdminViewProvider";
import { useAuth } from "@/components/AuthProvider";
import { apiFetch, buildQuery, listResource } from "@/lib/api";
import { formatDate } from "@/lib/format";
import { locationFromForm, recordedAtFromForm, recordedTimezoneFromForm, textValue } from "@/lib/forms";
import { handleFormEnter } from "@/lib/formNav";
import {
  audioExtensionForMimeType,
  pickAudioRecorderMimeType,
  SPEECH_AUDIO_CONSTRAINTS,
  uploadMediaBatch,
  type BatchProgress
} from "@/lib/media";
import { saveOrQueue } from "@/lib/offline";
import { canManageQuestionnaire, hasRank, isAdmin } from "@/lib/permissions";
import { UploadsProvider, useEagerStaging, useUploads } from "@/lib/uploads";
import type { PageResult, Questionnaire, QuestionnaireInterview, QuestionnaireQuestion, QuestionnaireSection } from "@/lib/types";

/**
 * The API's own ceiling on an interview title — `QuestionnaireInterviewCreate.title` is
 * `Field(min_length=1, max_length=220)` (`backend/app/schemas/questionnaire.py`).
 *
 * WRITTEN DOWN BECAUSE DICTATION IS THE ONE PATH THAT CAN EXCEED IT. A DOM `maxLength` bounds typing
 * and pasting and has no opinion at all about a value written into React state, which is exactly what
 * a committed phrase is; `clampToColumn` inside `DictatedTextInput` is what actually enforces this,
 * and it does nothing at all unless a number is handed to it. An over-long title 422s the WHOLE body,
 * so a researcher who spoke one sentence too many into the title box loses every answer in the
 * interview — and `saveOrQueue` refuses to bank a 4xx for later (`lib/offline.ts`), so the sitting is
 * not queued either: it is gone, with the artisan already on their way home.
 *
 * ONLY THE TITLE CARRIES ONE. `place`, `language` and `notes` are declared `str | None` with no
 * `max_length` on the same model, and inventing a cap the API does not have is a cap that refuses an
 * answer the API would have stored.
 */
const INTERVIEW_TITLE_MAX = 220;

/** Section ids the two questionnaire upload paths publish under, for the page-level tray. */
const INTERVIEW_SECTION = "interview-audio";
const INTERVIEW_SECTION_LABEL = "Interview audio";

/**
 * Recorded clips are keyed by what they answer: a question id, or `section:<id>` for one take that
 * covers a whole section. Same keying as the Android form, so both clients think about a section
 * recording the same way and the same caption reaches the server from either.
 */
const SECTION_CLIP_PREFIX = "section:";
const sectionClipKey = (sectionId: string) => `${SECTION_CLIP_PREFIX}${sectionId}`;
const isSectionClipKey = (key: string) => key.startsWith(SECTION_CLIP_PREFIX);
/** Tray section id for a clip key — ":" is stripped so the id stays a plain slug. */
const clipTraySectionId = (key: string) => `question-audio-${key.replace(SECTION_CLIP_PREFIX, "section-")}`;

export default function QuestionnairePage() {
  return (
    <UploadsProvider>
      <QuestionnairePageBody />
      <UploadTray />
    </UploadsProvider>
  );
}

function QuestionnairePageBody() {
  const confirm = useConfirm();
  const { user } = useAuth();
  const { adminMode } = useAdminView();
  const { addCompleted } = useUploads();
  const searchParams = useSearchParams();
  const [sections, setSections] = useState<QuestionnaireSection[]>([]);
  /**
   * WHICH INSTRUMENT THIS CAPTURE IS ON. Two exist and their section codes collide completely, so
   * "section A" is meaningless without one.
   *
   * `instrumentTouched` is the same stale-prefill-vs-deliberate-choice problem the RESP block below
   * solves with `prefilled`: the workshop effect wants to move the instrument whenever the workshop
   * changes, and a manager who deliberately picked another one must not have it snatched back on
   * the next re-render. The ref is what tells those two cases apart.
   */
  const [instruments, setInstruments] = useState<Questionnaire[]>([]);
  const [questionnaireId, setQuestionnaireId] = useState<string | null>(null);
  const instrumentTouched = useRef(false);
  const [data, setData] = useState<PageResult<QuestionnaireInterview> | null>(null);
  const [answers, setAnswers] = useState<Record<string, string>>({});
  const [questionAudioFiles, setQuestionAudioFiles] = useState<Record<string, File[]>>({});
  /**
   * WHO THIS INTERVIEW IS WITH — one ordered list, one control.
   *
   * It replaces the `selectedArtisanId` + `additionalArtisanIds` pair the form carried until 0.0.5.
   * That pair was a UI invention with nothing behind it: `QuestionnaireInterviewArtisan`
   * (`backend/prisma/schema.prisma`) is `@@id([interviewId, artisanId])` with no rank column, and
   * this page already flattened the two controls into ONE de-duplicated set before every request it
   * made. See `components/questionnaires/interviewArtisans.ts` for the whole argument, for the rule
   * that answers "which one" where something still needs a single artisan, and for why the list is
   * ORDERED (the researcher's own tick order is what element 0 means).
   *
   * `?artisanId=` still seeds it — that deep link is how the artisan page hands an interview off —
   * and it seeds a one-element SET rather than a "primary".
   */
  const [selectedArtisanIds, setSelectedArtisanIds] = useState<string[]>(() => {
    const seeded = searchParams.get("artisanId");
    return seeded ? [seeded] : [];
  });
  const [existingEntry, setExistingEntry] = useState<QuestionnaireInterview | null>(null);
  // Whole-section vs per-question capture, and whether the written-answer boxes are on screen.
  // Remembered across sections and across visits — see useCapturePrefs.
  const { prefs: capture, update: updateCapture } = useCapturePrefs();
  const [mediaFiles, setMediaFiles] = useState<File[]>([]);
  const [activePreview, setActivePreview] = useState<PreviewMedia | null>(null);
  const [questionAudioPreviews, setQuestionAudioPreviews] = useState<Record<string, PreviewMedia[]>>({});
  const [recordingKey, setRecordingKey] = useState<string | null>(null);
  // The live stream is state (not just a ref) because <RecordingStrip>'s waveform re-renders on it.
  const [questionStream, setQuestionStream] = useState<MediaStream | null>(null);
  const [questionElapsedMs, setQuestionElapsedMs] = useState(0);
  const [interviewProgress, setInterviewProgress] = useState<BatchProgress | null>(null);
  const [questionProgress, setQuestionProgress] = useState<Record<string, BatchProgress | null>>({});
  const [page, setPage] = useState(1);
  const [funnel, setFunnel] = useState<FunnelValue>(EMPTY_FUNNEL);
  /**
   * Has the funnel settled on its own default workshop yet?
   *
   * `EMPTY_FUNNEL` and "the researcher chose All workshops" are the SAME VALUE — `workshopId: ""` —
   * so the list below cannot tell "nothing has been chosen yet" from "everything was chosen" by
   * looking at the funnel. This flag is the difference, flipped by the funnel's first `onChange`
   * (which `FunnelFilters` guarantees, with the default or with "" when a deployment has no
   * workshops at all). Without it the first interview request would go out unscoped and be replaced
   * the instant the default landed.
   */
  const [funnelReady, setFunnelReady] = useState(false);
  const [searchInput, setSearchInput] = useState("");
  const [searchQuery, setSearchQuery] = useState("");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  /*
    THE THREE HEADER BOXES LIVE IN REACT STATE, WHICH IS FORCED ON US BY THE CONTROL, NOT PREFERRED.

    `DictatedTextInput` is controlled by its caller and deliberately has no self-controlled mode —
    see its header for the argument. The reason it matters HERE more than on most forms is the two
    `formElement.reset()` calls in `submit` below: `reset()` rewrites the DOM nodes and tells React
    nothing, so a box that owned its own value would re-paint the previous interview's title over the
    next artisan's. `clearHeaderBoxes` therefore sits in the same block as each `reset()`.

    THE `name` ATTRIBUTES ON THE THREE BOXES ARE LOAD-BEARING AND SILENT IF DROPPED. `submit` reads
    all three out of a `FormData` — `textValue(form, "title")`, `"place"`, `"language"` — so a box
    rendered without its `name` submits NOTHING, with no type error, no runtime warning and a 201 in
    reply. The interview saves with a generated title, no place and no language, and nobody finds out
    until somebody reads the record back. `DictatedTextInput` renders a real `<input name=…>`, so
    FormData reads these exactly as it read the `<TextInput>`s they replaced; keeping the state below
    in step is only about the reset.
  */
  const [title, setTitle] = useState("");
  const [place, setPlace] = useState("");
  const [language, setLanguage] = useState("");
  const recorderRef = useRef<MediaRecorder | null>(null);
  const streamRef = useRef<MediaStream | null>(null);
  const chunksRef = useRef<Blob[]>([]);
  const elapsedTimerRef = useRef<number | null>(null);

  function stopElapsedTimer() {
    if (elapsedTimerRef.current !== null) {
      window.clearInterval(elapsedTimerRef.current);
      elapsedTimerRef.current = null;
    }
  }

  // Professors and above may pick a record's status; everyone below is forced to PENDING
  // (mirrors the backend, which silently drops an unauthorized status on create).
  const canPickStatus = hasRank(user, "PROFESSOR");

  // The workshop this interview was taken at. Create-only form, so it always defaults to the most
  // recent workshop the interviewer may submit to (see components/forms/WorkshopSelect).
  const workshop = useWorkshopSelection();

  /**
   * THE ARTISANS THIS WORKSHOP'S INTERVIEWS MAY BE WITH, re-fetched whenever the workshop moves.
   *
   * The owner's report: *"when the workshop is already selected in the dropdown, why are artisans
   * from other workshops showing up"*. They were, because the list came down once at mount with no
   * workshop parameter on it. The whole rule set — the plural `workshopIds` and why not the
   * singular, what an unselected workshop means, why the roster REPLACES rather than merges, why
   * the request is held until the workshop picker has settled, and the Android contract all of it
   * owes — lives in `components/questionnaires/interviewArtisans.ts`. It is a separate file because
   * a rule the other client cannot read is a rule the other client will not match.
   */
  const workshopSettling = workshopScopeSettling(workshop);
  const artisanScope = useWorkshopArtisans({ workshopId: workshop.workshopId, settling: workshopSettling });
  /**
   * Every artisan row this page has loaded, from the repository-wide reachability probe and from
   * every workshop's roster. Used for LABELS and for the RESP block — never as the picker's offer,
   * which is `artisanScope.scoped` and only that.
   */
  const knownArtisans = artisanScope.known;

  const questions = useMemo(() => sections.flatMap((section) => section.questions), [sections]);
  const questionsById = useMemo(() => new Map(questions.map((question) => [question.id, question])), [questions]);
  const sectionsById = useMemo(() => new Map(sections.map((section) => [section.id, section])), [sections]);

  const orderedGroups = useMemo(() => {
    return sections.map((section) => [section.code, { section, title: section.title, items: section.questions }] as const);
  }, [sections]);

  /**
   * THE ONE ARTISAN THE RESP BLOCK IS ABOUT: the head of the selection, in the researcher's own tick
   * order. `primaryInterviewArtisanId` carries the argument for that rule and its in-repo precedent
   * (`tools.py` derives a tool's scalar `artisanId` from `artisan_ids[0]`, and Android's tool sheet
   * mirrors it).
   *
   * Looked up in `knownArtisans` and not in the workshop's roster: the RESP details must draw for
   * whoever is actually ticked, including the row rescued for a deep-linked artisan the roster does
   * not hold. An empty respondent block over a ticked name is a form that looks broken.
   */
  const primaryArtisanId = primaryInterviewArtisanId(selectedArtisanIds);
  const selectedArtisan = useMemo(
    () => knownArtisans.find((artisan) => artisan.id === primaryArtisanId),
    [knownArtisans, primaryArtisanId]
  );

  // The exact set of artisans this interview covers, de-duplicated. There is a single shared
  // questionnaire entry per such set — we look it up so the researcher sees that it has already been
  // started and which sections others have answered, instead of making a duplicate. `artisanSetKey`
  // sorts, because ticking A then B is the same interview as ticking B then A; the SENT order stays
  // the researcher's, which is what `primaryArtisanId` above reads.
  const selectedSetKey = artisanSetKey(selectedArtisanIds);

  useEffect(() => {
    if (selectedArtisanIds.length === 0) {
      setExistingEntry(null);
      return;
    }
    let active = true;
    // SCOPED TO THE INSTRUMENT ON SCREEN. Without it the lookup finds the OTHER instrument's
    // sitting for these artisans, the form flips to "Add to shared entry", and the researcher is
    // offered a sitting the create would never have folded into — the two would disagree.
    const query = [
      ...selectedArtisanIds.map((id) => `artisanIds=${encodeURIComponent(id)}`),
      ...(questionnaireId ? [`questionnaireId=${encodeURIComponent(questionnaireId)}`] : [])
    ].join("&");
    apiFetch<QuestionnaireInterview | null>(`/questionnaire/interviews/by-artisans?${query}`)
      .then((result) => {
        if (active) setExistingEntry(result ?? null);
      })
      .catch(() => {
        if (active) setExistingEntry(null);
      });
    return () => {
      active = false;
    };
  }, [selectedSetKey, selectedArtisanIds, questionnaireId]);

  /**
   * What this effect last wrote into `answers`, per question. It is how a stale prefill is told apart
   * from a typed answer, which is the whole difficulty: both are just strings in the same map.
   */
  const prefilled = useRef<Record<string, string>>({});

  /**
   * Which interview-list fetch is the current one. The search box is debounced and the funnel and
   * the pager fire the same effect, so more than one request is routinely in flight; without this a
   * slow answer for an abandoned filter could land last and win. Counted rather than aborted because
   * `listResource` takes no signal, and ignoring the late answer is the part that matters.
   */
  const currentInterviewLoad = useRef(0);

  useEffect(() => {
    if (!selectedArtisan || questions.length === 0) return;
    const respondentAnswers: Record<string, string> = {};
    questions
      .filter((question) => question.sectionCode === "RESP")
      .forEach((question) => {
        const prompt = question.prompt.toLowerCase();
        if (prompt.includes("name")) respondentAnswers[question.id] = selectedArtisan.name;
        else if (prompt.includes("craft")) respondentAnswers[question.id] = selectedArtisan.craft?.name ?? "";
        else if (prompt.includes("state") || prompt.includes("district") || prompt.includes("village")) respondentAnswers[question.id] = selectedArtisan.place;
        else if (prompt.includes("gender")) respondentAnswers[question.id] = selectedArtisan.gender ?? "";
        else if (prompt.includes("contact")) respondentAnswers[question.id] = selectedArtisan.phone ?? selectedArtisan.email ?? "";
        else if (prompt.includes("date")) respondentAnswers[question.id] = new Date().toLocaleDateString("en-IN");
        else if (prompt.includes("interviewer")) respondentAnswers[question.id] = user?.name ?? user?.email ?? "";
      });
    // Overwrite only what is still blank or still exactly what this effect put there last time.
    // The old rule kept every existing answer ahead of the new prefill, which read as "never clobber
    // the researcher" but really meant the RESP block froze on the FIRST artisan picked: change the
    // primary artisan and the interview kept the previous artisan's name, craft, place, gender and
    // phone, and saved them against the new one.
    const previous = prefilled.current;
    prefilled.current = respondentAnswers;
    setAnswers((current) => {
      const next = { ...current };
      let changed = false;
      Object.entries(respondentAnswers).forEach(([questionId, value]) => {
        const existing = next[questionId] ?? "";
        if (existing && existing !== (previous[questionId] ?? "")) return;
        if (existing === value) return;
        next[questionId] = value;
        changed = true;
      });
      return changed ? next : current;
    });
  }, [questions, selectedArtisan, user]);

  /**
   * THE INSTRUMENT AND ITS SECTIONS. They change only when an admin edits the questionnaire, so they
   * load once at mount, again when the instrument changes, and again on the builder's `onChanged`.
   *
   * THE ARTISAN LIST USED TO BE LOADED HERE AND IS NOT ANY MORE — the comment that stood on this
   * function said sections and artisans "load once … rather than per list page/filter", which was a
   * fair reading of a list that belonged to nobody in particular and is simply wrong now. An
   * artisan list is not instrument metadata: it belongs to the WORKSHOP, it has to be re-fetched
   * every time the workshop moves, and it must not be re-fetched when somebody switches instrument
   * or renames a section in the builder — all three of which this function does. It lives in
   * `useWorkshopArtisans` (`components/questionnaires/interviewArtisans.ts`), keyed on the workshop.
   */
  async function loadMeta(instrumentId?: string | null) {
    try {
      // TWO reads, one wave. The instrument list joins it rather than following it, because a
      // sequential "which instruments exist, then give me that one's sections" is two round trips
      // before the form can render a single question.
      const [instrumentList, sectionList] = await Promise.all([
        apiFetch<Questionnaire[]>("/questionnaires"),
        apiFetch<QuestionnaireSection[]>(
          `/questionnaire/sections${buildQuery({ questionnaireId: instrumentId ?? undefined })}`
        )
      ]);
      setInstruments(instrumentList);
      // The server resolved SOME instrument for that read whether or not we named one, and its
      // sections carry the id it chose. Reading it back off them keeps the picker showing what the
      // form is actually built from rather than what the client guessed.
      const resolved = instrumentId ?? sectionList[0]?.questionnaireId
        ?? instrumentList.find((instrument) => instrument.isDefault)?.id
        ?? instrumentList[0]?.id
        ?? null;
      setQuestionnaireId(resolved);
      setSections(sectionList);
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to load questionnaire");
    }
  }

  /**
   * THE WORKSHOP CHOOSES THE INSTRUMENT — until somebody says otherwise.
   *
   * A workshop is bound to one questionnaire by an admin, and opening the capture form at that
   * workshop should open the questions that apply there without anybody choosing again. Guarded by
   * `instrumentTouched` so a questionnaire manager running a pilot on the other instrument keeps
   * their choice: without the ref the next workshop re-render silently takes it back, and the
   * researcher's answers go to the instrument they did not pick.
   */
  const boundInstrumentId = useMemo(
    () => workshop.workshops?.find((row) => row.id === workshop.workshopId)?.questionnaireId ?? null,
    [workshop.workshops, workshop.workshopId]
  );

  useEffect(() => {
    if (instrumentTouched.current) return;
    const bound = boundInstrumentId;
    if (!bound || bound === questionnaireId) return;
    setQuestionnaireId(bound);
    void loadMeta(bound);
    // `loadMeta` is deliberately NOT a dependency: it is re-created every render, so listing it
    // would re-run this effect on every render and re-fetch the whole instrument each time.
  }, [boundInstrumentId, questionnaireId]);

  /**
   * Open on the artisan this researcher was last documenting.
   *
   * An interview is the record most often taken straight after a product or a tool — the artisan is
   * sitting right there — so the primary artisan is the one thing worth carrying in. Nothing narrower
   * transfers: an interview covers a person, not their products, and this form has no field for one.
   */
  const carry = useCarryContext({
    // The REPOSITORY-WIDE reachability probe, never the workshop's roster. `useCarryContext` reads
    // this id list to decide whether a carried artisan still exists and is still visible, and prunes
    // the carried record — and everything hanging off it — when it is not. An artisan documented at
    // another workshop is neither deleted nor invisible, so scoping this list would make carry
    // destroy a perfectly good context; and since the bag also carries the WORKSHOP that `onApply`
    // is about to select, the prune would land before the workshop it disagreed with had even moved.
    // See `useWorkshopArtisans` for why the probe is kept as its own request.
    scopes: [carryScope("artisan", artisanScope.referenceState, knownArtisans)],
    applies: ["artisan", "workshop"],
    onApply: (context) => {
      // Prepended rather than assigned: `onApply` fires once, before anything has been ticked, so in
      // practice this IS `[id]` — but writing it as an assignment would mean that the day the offer
      // is ever applied later (a "resume" affordance, a second bag) it would silently discard a
      // selection the researcher had already made. The `includes` guard keeps the list free of the
      // duplicate that would otherwise send one artisan twice.
      if (context.artisanId) {
        const carried = context.artisanId;
        setSelectedArtisanIds((current) => (current.includes(carried) ? current : [carried, ...current]));
      }
      if (context.workshopId && !workshop.touched) workshop.setWorkshopId(context.workshopId);
    }
  });
  /** "Change": drop the carried artisans so the researcher picks from scratch. */
  function clearCarriedContext() {
    carry.change();
    setSelectedArtisanIds([]);
  }

  /**
   * THE TICKED ARTISANS THIS WORKSHOP'S ROSTER DOES NOT ACCOUNT FOR — said, not silently undone.
   *
   * What stood here was `useArtisanSelectionScope`, which ran the same ruling and wrote the
   * survivors back into `selectedArtisanIds`: a workshop change quietly unticked anybody the new
   * workshop's complete roster did not hold, and told the researcher nothing. It also pruned the
   * carry banner on the way past, because a banner reading "Continuing with Ramesh" over a form that
   * had just dropped Ramesh is a page contradicting itself.
   *
   * BOTH OF THOSE ARE GONE, and the whole argument is in
   * `components/questionnaires/interviewArtisans.ts` (rule 6). The short version: an artisan is
   * linked to a workshop three ways and the third is HAVING SAT IN AN INTERVIEW TAKEN THERE — the
   * link this very form creates — so a roster that does not hold somebody is not evidence that
   * ticking them was a mistake. It is evidence they have not been interviewed here BEFORE. The two
   * paths that most often name such a person are the two this product actually ships for the
   * purpose: `/questionnaire?artisanId=` from the artisans page, and the carry bag after a tool or a
   * product. Both of them used to arrive ticked and go blank about a second later, taking the RESP
   * prefill with them, with no message anywhere — and Android kept the tick, so the same two taps
   * saved two different sets of `QuestionnaireInterviewArtisan` rows.
   *
   * The banner therefore stands, correctly: the carried artisan really is still on this interview.
   * `carry.prune("artisan")` is no longer called from this page at all.
   */
  const artisansOutOfWorkshop = useMemo(
    () =>
      artisansNotAtWorkshop({
        selectedIds: selectedArtisanIds,
        offeredIds: artisanScope.scoped.map((artisan) => artisan.id),
        loadedForWorkshop: artisanScope.loadedForWorkshop,
        workshopId: workshop.workshopId,
        cut: artisanScope.cut
      }),
    [selectedArtisanIds, artisanScope.scoped, artisanScope.loadedForWorkshop, artisanScope.cut, workshop.workshopId]
  );
  /**
   * The names for that sentence, out of `knownArtisans` — which is the only list that has them. The
   * workshop's roster by definition does not hold these people, and an id in a sentence is not a
   * sentence. An id whose row has not been loaded at all contributes nothing rather than printing a
   * cuid: the notice is capped and best-effort, and a half-named list is worse than a shorter one.
   */
  const outOfWorkshopMessage = useMemo(
    () =>
      outOfWorkshopNotice(
        artisansOutOfWorkshop
          .map((id) => knownArtisans.find((artisan) => artisan.id === id)?.name)
          .filter((name): name is string => Boolean(name))
      ),
    [artisansOutOfWorkshop, knownArtisans]
  );

  async function loadInterviews() {
    const generation = (currentInterviewLoad.current += 1);
    try {
      const result = await listResource<QuestionnaireInterview>("/questionnaire/interviews", {
        page,
        pageSize: 20,
        // THE WORKSHOP IS ON THE WIRE, and this is the web half of the owner's second report — the
        // Android one, where *"the questionnaire from the previous workshop are showing up even in
        // the third workshop"*. This list had the same hole: the funnel has offered a workshop
        // filter since it was written and this call quietly dropped it, so every workshop's browse
        // table listed every workshop's interviews.
        //
        // SINGULAR `workshopId` HERE AND PLURAL `workshopIds` ON THE ARTISAN LIST, and the asymmetry
        // is deliberate on both ends.
        //
        // `list_interviews` (`backend/app/api/routes/questionnaire.py`) declares BOTH — the singular
        // every caller has always sent, and a plural for the multi-select workshop scope Android's
        // `rememberWorkshopScope` and this page's own completion matrix drive. This control is not
        // that one: `FunnelFilters` is single-select and has no "Not linked to a workshop" option,
        // so it has exactly one id to say and the singular says it. For one real id the two are the
        // same predicate — `workshop_clause([id], false)` is `{"workshopId": {"in": [id]}}` — so
        // there is nothing to gain by spelling it the long way and one thing to lose: the singular
        // is understood by every deployed API and the plural is not. The web deploys to Vercel and
        // the API to EC2, separately, so a newer web build meets an older API routinely, and FastAPI
        // IGNORES an undeclared query parameter rather than refusing it — a plural sent to an API
        // that predates it produces an UNFILTERED list under a request that looks filtered, which is
        // this defect wearing the fix's clothes.
        //
        // On `/artisans` the same choice goes the other way, because there the two spellings are NOT
        // equivalent: see `components/questionnaires/interviewArtisans.ts`.
        workshopId: funnel.workshopId || undefined,
        artisanId: funnel.artisanId || undefined,
        search: searchQuery || undefined
      });
      // The answer to a question already moved on from must not land last and win — see the ref.
      if (generation !== currentInterviewLoad.current) return;
      setData(result);
      setError(null);
    } catch (err) {
      if (generation !== currentInterviewLoad.current) return;
      setError(err instanceof Error ? err.message : "Unable to load questionnaire interviews");
    }
  }

  useEffect(() => {
    loadMeta();
  }, []);

  /**
   * THE INTERVIEW LIST. The backend already returns interviews most-recent-first (`createdAt desc`);
   * the funnel narrows by WORKSHOP and by artisan and the search box by text.
   *
   * The sentence that stood here said the funnel narrows "by artisan (the only list param the
   * interviews endpoint supports)". That was never true of the route as shipped — `list_interviews`
   * has declared `workshopId` for as long as it has declared `artisanId` — and leaving it in place
   * is what let a reader conclude the missing workshop filter was the server's limitation rather
   * than this call's omission. The funnel's craft filter really does have nothing to narrow here:
   * an interview has artisans, not crafts, and there is no craft parameter on the route.
   *
   * HELD UNTIL THE FUNNEL HAS REPORTED. `FunnelFilters` defaults itself to the most recently held
   * workshop and announces that default through `onChange` once its own `/workshops` request lands —
   * its header says in as many words that parents should wait for that first call. Firing at mount
   * instead would send one unscoped request, paint every workshop's interviews, and replace them a
   * moment later: two requests and a visible flash of exactly the wrong list, which is the defect
   * this scoping is here to remove rather than to re-stage for a quarter of a second.
   */
  useEffect(() => {
    if (!funnelReady) return;
    loadInterviews();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [funnelReady, page, funnel.workshopId, funnel.artisanId, searchQuery]);

  // Leaving the page mid-recording must release the microphone and the clock, not leak either.
  useEffect(() => {
    return () => {
      stopElapsedTimer();
      recorderRef.current?.stream.getTracks().forEach((track) => track.stop());
      streamRef.current?.getTracks().forEach((track) => track.stop());
    };
  }, []);

  // Eager upload for the per-question recordings, matching every other capture surface. An
  // interview is the longest form in the app — a researcher can spend half an hour working down
  // the sections — and each answer is an audio clip. Waiting until Save to start the transfer
  // means the whole interview's audio goes up in one blocking burst at the end, on whatever
  // connection the field site has; staging each clip when the recorder stops spreads it across
  // the time already being spent. Saving then only links the objects. One owner for all of them
  // (rather than per question) keeps this a single row in the tray instead of thirty.
  const stagedQuestionAudio = useMemo(() => Object.values(questionAudioFiles).flat(), [questionAudioFiles]);
  const questionAudioStaging = useEagerStaging(stagedQuestionAudio, "Question recordings");
  const stagedByFile = useMemo(
    () => new Map(stagedQuestionAudio.map((file, index) => [file, questionAudioStaging.entries[index]])),
    [stagedQuestionAudio, questionAudioStaging.entries]
  );

  /** "3 clips · 2 uploaded" — the chip under a recorder, so "ready" is a fact not a hope. */
  function clipCountLabel(key: string): string {
    const files = questionAudioFiles[key] ?? [];
    const entries = files.map((file) => stagedByFile.get(file) ?? null);
    const ready = entries.filter((entry) => entry?.status === "ready").length;
    const failed = entries.filter((entry) => entry?.status === "error").length;
    const clips = `${files.length} clip${files.length === 1 ? "" : "s"}`;
    if (failed) return `${clips} · ${failed} failed to upload, will retry on save`;
    if (ready === files.length) return `${clips} · uploaded`;
    return `${clips} · ${ready} of ${files.length} uploaded`;
  }

  useEffect(() => {
    const nextPreviews: Record<string, PreviewMedia[]> = {};
    Object.entries(questionAudioFiles).forEach(([questionId, files]) => {
      nextPreviews[questionId] = files.map((file, index) => ({
        key: `${questionId}-${file.name}-${file.size}-${file.lastModified}-${index}`,
        name: file.name,
        mediaType: "AUDIO",
        mimeType: file.type || "audio/webm",
        sizeBytes: file.size,
        url: URL.createObjectURL(file)
      }));
    });
    setQuestionAudioPreviews(nextPreviews);
    return () => {
      Object.values(nextPreviews).flat().forEach((item) => {
        if (item.url) URL.revokeObjectURL(item.url);
      });
    };
  }, [questionAudioFiles]);

  function handleSearchChange(value: string) {
    setSearchInput(value);
    // Clearing the box (the red X) immediately drops the filter, matching the funnel's live feel.
    if (value === "") {
      setSearchQuery("");
      setPage(1);
    }
  }

  function applySearch() {
    setSearchQuery(searchInput.trim());
    setPage(1);
  }

  /**
   * Record against one clip key — a question id, or a section's key for a single whole-section take.
   * `filenameBase` is the human part of the object name; the extension follows the codec the browser
   * actually gave us.
   */
  async function startRecording(key: string, filenameBase: string) {
    try {
      if (recordingKey) recorderRef.current?.stop();
      const stream = await navigator.mediaDevices.getUserMedia({ audio: SPEECH_AUDIO_CONSTRAINTS });
      streamRef.current = stream;
      chunksRef.current = [];
      // Ask the browser what it can actually record: Safari/iOS produces audio/mp4, so a hardcoded
      // "audio/webm" name and type would lie about the bytes and break playback and transcription.
      const preferredType = pickAudioRecorderMimeType();
      const recorder = new MediaRecorder(stream, preferredType ? { mimeType: preferredType } : undefined);
      recorderRef.current = recorder;
      recorder.ondataavailable = (event) => {
        if (event.data.size > 0) chunksRef.current.push(event.data);
      };
      recorder.onstop = () => {
        const mimeType = recorder.mimeType || preferredType || "audio/webm";
        const extension = audioExtensionForMimeType(mimeType);
        const blob = new Blob(chunksRef.current, { type: mimeType });
        const file = new File([blob], `${filenameBase}.${extension}`, { type: mimeType });
        setQuestionAudioFiles((current) => ({
          ...current,
          [key]: [...(current[key] ?? []), file]
        }));
        stream.getTracks().forEach((track) => track.stop());
        // Tapping "Record this question" on ANOTHER question stops this recorder while the next one
        // is already running — only tear down the shared recording UI if this is still the live one.
        if (recorderRef.current !== recorder) return;
        streamRef.current = null;
        recorderRef.current = null;
        stopElapsedTimer();
        setQuestionStream(null);
        setQuestionElapsedMs(0);
        setRecordingKey(null);
      };
      // The clock starts when the recorder really starts; only it needs a timer, because the bars
      // run on <Waveform>'s own requestAnimationFrame loop.
      recorder.onstart = () => {
        const startedAt = Date.now();
        setQuestionElapsedMs(0);
        elapsedTimerRef.current = window.setInterval(() => setQuestionElapsedMs(Date.now() - startedAt), 250);
      };
      recorder.start();
      setQuestionStream(stream);
      setRecordingKey(key);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to start recording");
    }
  }

  function stopRecording() {
    recorderRef.current?.stop();
  }

  /**
   * How one clip key is described on the way out: the caption, the metadata that files it, and the
   * tray label. `null` for a key whose question or section is no longer in the form.
   *
   * A whole-section take carries the section, not a question — "Section audio: D RAW MATERIALS…",
   * which is exactly what Android writes and what the backend already parses
   * (`_CAPTION_SECTION` in services/media_naming.py, and `sectionCode` in the completion matrix).
   * Attribution to artisans is not carried here and must not be: the clip links to the interview,
   * and the interview links to every artisan in the set, so a group sitting's one section recording
   * counts for all of them — which is what a group sitting means.
   */
  function clipBatch(key: string): { caption: string; extraMetadata: Record<string, unknown>; trayLabel: string } | null {
    if (isSectionClipKey(key)) {
      const section = sectionsById.get(key.slice(SECTION_CLIP_PREFIX.length));
      if (!section) return null;
      return {
        caption: `Section audio: ${section.code} ${section.title}`.trim(),
        extraMetadata: { sectionId: section.id, sectionCode: section.code, sectionTitle: section.title },
        trayLabel: `Section ${section.code} audio`
      };
    }
    const question = questionsById.get(key);
    if (!question) return null;
    return {
      caption: `Question audio: ${question.sectionCode}${question.sortOrder} - ${question.prompt}`,
      extraMetadata: { questionId: question.id, questionPrompt: question.prompt, sectionCode: question.sectionCode },
      trayLabel: `Q${question.sectionCode}${question.sortOrder} audio`
    };
  }

  /**
   * The dictated header boxes, emptied — the half of "clear the form" that `reset()` cannot reach.
   *
   * CALLED BESIDE EVERY `formElement.reset()`, NEVER INSTEAD OF ONE. `reset()` still owns the
   * uncontrolled controls on this form (status, the workshop select, the location card, the notes
   * rows); this owns the three controlled ones. Miss this call and the next interview opens with the
   * previous artisan's title and place already typed in, which is worse than a stale box on any other
   * screen in this app: the questionnaire's uniqueness key is the artisan SET, so a researcher who
   * does not notice files a second sitting under a title naming the wrong person.
   *
   * `""` and not `undefined`: a controlled input handed `undefined` switches to uncontrolled, React
   * warns once in development and the box keeps whatever the DOM node last held — which is precisely
   * the bug this function exists to close.
   */
  function clearHeaderBoxes() {
    setTitle("");
    setPlace("");
    setLanguage("");
  }

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    // React nulls event.currentTarget after the first await — capture it before any async work.
    const formElement = event.currentTarget;
    const form = new FormData(formElement);
    // A workshop that has already ended makes this a late submission needing admin approval — say so
    // before anything is written. Resolves true immediately when there is nothing to warn about.
    if (!(await workshop.confirmSubmission())) return;
    setSaving(true);
    setError(null);
    const artisanIds = selectedArtisanIds;
    const responses = Object.entries(answers)
      .filter(([, answerText]) => answerText.trim())
      .map(([questionId, answerText]) => ({ questionId, answerText: answerText.trim() }));
    try {
      const location = locationFromForm(form);
      const recordedAt = recordedAtFromForm(form);
      const recordedTimezone = recordedTimezoneFromForm(form);
      const interviewTitle = textValue(form, "title") || `Interview ${new Date().toLocaleDateString()}`;
      const interviewPayload = {
          title: interviewTitle,
          // interviewDate is deliberately not sent: the server derives it from recordedAt.
          place: textValue(form, "place"),
          language: textValue(form, "language"),
          notes: textValue(form, "notes"),
          status: canPickStatus ? textValue(form, "status") || "APPROVED" : "PENDING",
          workshopId: workshop.workshopId || null,
          // WRITTEN AT QUEUE TIME, NOT AT REPLAY TIME. This same object is what `saveOrQueue`
          // serialises into the outbox, so an interview captured today and replayed next week files
          // on the instrument the researcher was actually looking at — not on whatever the default
          // has become by then. Do not "simplify" this away on the offline path.
          questionnaireId: questionnaireId || null,
          artisanIds,
          responses,
          recordedAt,
          recordedTimezone,
          location
      };
      // Offline this queues the whole interview — answers, the interview audio and every
      // per-question or whole-section clip — to the outbox. An interview is the one record that
      // cannot be reconstructed later: the artisan has gone home.
      const outcome = await saveOrQueue<QuestionnaireInterview>({
        label: `Interview · ${interviewTitle}`,
        endpoint: "/questionnaire/interviews",
        method: "POST",
        body: interviewPayload,
        media: [
          {
            files: mediaFiles,
            linkedRecordType: "questionnaire",
            caption: `Interview audio for ${interviewTitle}`,
            location,
            recordedAt,
            recordedTimezone,
            transcribeAudio: true
          },
          // One batch per clip key so the caption and the metadata that place a clip under its
          // answer — or under its whole section — survive the round trip through IndexedDB.
          ...Object.entries(questionAudioFiles).flatMap(([key, files]) => {
            const batch = clipBatch(key);
            if (!batch || !files.length) return [];
            return [
              {
                files,
                linkedRecordType: "questionnaire",
                caption: batch.caption,
                location,
                recordedAt,
                recordedTimezone,
                transcribeAudio: true,
                extraMetadata: batch.extraMetadata
              }
            ];
          })
        ]
      });
      if (outcome.queued) {
        // OutboxBanner at the top of the page names the entry and says where it lives.
        formElement.reset();
        clearHeaderBoxes();
        setAnswers({});
        setMediaFiles([]);
        setQuestionAudioFiles({});
        // THE HEAD STAYS TICKED AND THE REST DO NOT, which is exactly what the two-control form did:
        // it cleared "Additional artisans" and left "Primary artisan" where it was. The researcher is
        // still sitting in front of the same person and may well take a second sitting with them —
        // and the RESP block, which draws from the head, would otherwise empty itself under their
        // hands. The GROUP that sat in the interview just filed is finished, so the tail goes.
        setSelectedArtisanIds((current) => current.slice(0, 1));
        setSaving(false);
        if (typeof window !== "undefined") window.scrollTo({ top: 0, behavior: "smooth" });
        return;
      }
      const saved = outcome.saved;
      if (mediaFiles.length) {
        const { uploaded } = await uploadMediaBatch({
          files: mediaFiles,
          linkedRecordType: "questionnaire",
          linkedRecordId: saved.id,
          caption: `Interview audio for ${saved.title}`,
          location,
          recordedAt,
          recordedTimezone,
          transcribeAudio: true,
          onProgress: setInterviewProgress
        });
        setInterviewProgress(null);
        // Uploaded clips surface twice: as chips under this section and in the page-level tray.
        addCompleted(INTERVIEW_SECTION, INTERVIEW_SECTION_LABEL, uploaded);
      }
      for (const [key, files] of Object.entries(questionAudioFiles)) {
        const batch = clipBatch(key);
        if (!batch || files.length === 0) continue;
        const { uploaded } = await uploadMediaBatch({
          files,
          linkedRecordType: "questionnaire",
          linkedRecordId: saved.id,
          caption: batch.caption,
          location,
          recordedAt,
          recordedTimezone,
          transcribeAudio: true,
          extraMetadata: batch.extraMetadata,
          onProgress: (progress) => setQuestionProgress((current) => ({ ...current, [key]: progress }))
        });
        addCompleted(clipTraySectionId(key), batch.trayLabel, uploaded);
      }
      // Cleared only once every question has been pushed, so the tray's page-level total counts the
      // whole run rather than shrinking back to whichever question is uploading right now.
      setQuestionProgress({});
      // Bank the sitting: the interview does not become part of the carried context (nothing else
      // links to one) but the artisan it was taken with is exactly where the researcher still is.
      //
      // ONE artisan, and it is the head of the set. The carry bag has a single `artisanId` slot and
      // nothing about this change adds one — see `primaryInterviewArtisanId` for why element 0 is
      // the answer here as it is everywhere else in this repository, and why it does not move under
      // the researcher. An interview covering four people still hands the next form the person the
      // researcher is sitting in front of, which is who they ticked first.
      const interviewed = knownArtisans.find((artisan) => artisan.id === primaryArtisanId);
      if (interviewed) {
        carry.remember({
          artisanId: interviewed.id,
          artisanName: interviewed.name,
          place: interviewed.place,
          craftId: interviewed.craftId,
          craftName: interviewed.craft?.name ?? null,
          workshopId: workshop.workshopId,
          workshopName: workshop.workshops.find((w) => w.id === workshop.workshopId)?.title ?? null
        });
      }
      formElement.reset();
      clearHeaderBoxes();
      setAnswers({});
      setMediaFiles([]);
      setQuestionAudioFiles({});
      // Head kept, tail dropped — the same rule, and the same argument, as the queued branch above.
      setSelectedArtisanIds((current) => current.slice(0, 1));
      // Show the freshly saved (most recent) interview at the top of page one.
      if (page !== 1) setPage(1);
      else await loadInterviews();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to save interview");
    } finally {
      setSaving(false);
      setInterviewProgress(null);
      setQuestionProgress({});
    }
  }

  async function remove(id: string) {
    const ok = await confirm(
      deleteConfirm(
        "Delete this interview?",
        "This permanently deletes the interview and every answer in it. This action cannot be undone.",
        "The recordings made during the interview, and their transcripts, are deleted with it."
      )
    );
    if (!ok) return;
    try {
      await apiFetch(`/questionnaire/interviews/${id}`, { method: "DELETE" });
      await loadInterviews();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to delete interview");
    }
  }

  /**
   * WHAT THE ONE ARTISAN CONTROL OFFERS: this workshop's roster, in the server's order.
   *
   * What it replaced was `artisans.filter((a) => a.id !== selectedArtisanId)` — the repository-wide
   * list minus whoever was in the OTHER dropdown. Both halves of that expression are gone, and
   * neither is a loss. The exclusion existed only to stop one person appearing in two controls that
   * fed one set; with one control the set is the control, and `SearchableMultiSelect` cannot tick
   * the same row twice. The unscoped source is the defect itself.
   *
   * NOT SORTED HERE, deliberately. `GET /artisans` answers `createdAt desc` and Android renders that
   * order untouched (`ConsolidatedQuestionnaireScreen.kt` filters for the search box and never
   * re-orders), so imposing an alphabetical sort on the web would give the two clients two different
   * lists of the same people — the parity break is the ORDER, which is the sort of difference nobody
   * files a bug about and everybody notices.
   */
  const artisanOptions = useMemo(
    () => artisanPickerOptions({ scoped: artisanScope.scoped, known: knownArtisans, selectedIds: selectedArtisanIds }),
    [artisanScope.scoped, knownArtisans, selectedArtisanIds]
  );

  return (
    <>
      <PageHeader
        title="Questionnaire"
        description="Interview artisans section by section, answer only the questions asked, and link the interview to one or more artisans."
        icon={<ClipboardList className="h-5 w-5" aria-hidden />}
      />
      {error ? <div className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div> : null}

      {/* 1) Completion matrix — top of the page, collapsed by default. */}
      <CompletionMatrixPanel canOverride={adminMode && isAdmin(user)} questionnaireId={questionnaireId} />

      <form onSubmit={submit} onKeyDown={handleFormEnter} className="panel mb-5 grid gap-4 p-4">
        <CarryContextBanner offer={carry.applied} onChange={clearCarriedContext} />
        <div className="grid gap-3 md:grid-cols-2 lg:grid-cols-4">
          {/*
            THE ONE PLACE THIS PAGE EXPLAINS A MISSING MICROPHONE — see `DictationUnavailableNotice`.

            Every dictated box on this page passes `explainWhenUnavailable={false}`, and there are a
            lot of them: three here, one under every answer box in every section, and four more in the
            builder below. Without this line, on Firefox — which implements no `SpeechRecognition` at
            all — all of them would simply vanish and nothing anywhere would say why; with the button's
            own sentence under each instead, an eighty-one-question instrument prints eighty-one copies
            of one paragraph, which is the reader learning to skip grey text and then skipping the one
            place it mattered. EXACTLY ONE, on the whole page: the builder is a collapsed accordion
            further down this same file and deliberately does not mount a second.

            `md:col-span-2 lg:col-span-4` because this is a child of the four-column field grid and
            `className` here reaches the `<p>` itself — without it the sentence wraps into one cell.
          */}
          <DictationUnavailableNotice className="md:col-span-2 lg:col-span-4" />
          {/*
            `titleCased` ON TITLE AND PLACE, AND NOT ON LANGUAGE, is read off the server rule rather
            than guessed: `create_interview` runs `clean_data(...)` (`backend/app/api/routes/
            questionnaire.py`), which title-cases every column in `TITLE_CASE_FIELDS`
            (`backend/app/services/records.py`) — `title` and `place` are both in that set and
            `language` is not. `TitleCasedInput` then says "Will be saved as …" whenever the server's
            normalisation would change what is in the box. That sentence matters MORE with a
            microphone than without one: a recogniser hands back its own casing and nobody typed it,
            so without the hint the value simply changes after saving and the form and the record
            disagree with no one having been told.
          */}
          <DictatedTextInput
            name="title"
            label="Interview title"
            required
            titleCased
            maxLength={INTERVIEW_TITLE_MAX}
            explainWhenUnavailable={false}
            value={title}
            onChange={setTitle}
          />
          {/* No date field: the server derives interviewDate from recordedAt, which is when the
              interview was actually captured. Asking a researcher to confirm today's date was a
              field to tab past that could only ever be wrong. AND NO MICROPHONE ON A DATE even if one
              were re-added: a spoken date is the one thing a recogniser gets wrong in a way that
              still parses. */}
          <DictatedTextInput
            name="place"
            label="Place"
            titleCased
            explainWhenUnavailable={false}
            value={place}
            onChange={setPlace}
          />
          {/*
            Language is FREE TEXT here and not a closed vocabulary, which is why it gets a microphone
            at all. The column is `str | None` with no enum behind it on either side — the placeholder
            names three examples and the artisan's own answer ("Kutchi, and some Gujarati") is a
            perfectly good value. A dropdown of language names is the change that would REMOVE the
            microphone, not one that would sit beside it.
          */}
          <DictatedTextInput
            name="language"
            label="Language"
            placeholder="Bangla, Hindi, English..."
            explainWhenUnavailable={false}
            value={language}
            onChange={setLanguage}
          />
          {/* The workshop leads every other dropdown: it is the context the interview belongs to. */}
          <WorkshopSelect state={workshop} saving={saving} />
          {/*
            A <Field> + <Select>, and NOT a <details>. `e2e/questionnaire-capture.spec.ts` locates
            the questionnaire's sections as `form.panel details` minus "Captured at", then takes
            `.first()`; a collapsible control here would join that set and break the default-capture
            spec while looking like a styling change. Sits immediately after the workshop because
            that is what usually chooses it.
          */}
          <Field label="Questionnaire">
            <Select
              name="questionnaireId"
              value={questionnaireId ?? ""}
              disabled={saving || instruments.length === 0}
              onChange={(event) => {
                // An explicit pick, so the workshop effect stops moving it. See instrumentTouched.
                instrumentTouched.current = true;
                setQuestionnaireId(event.target.value);
                void loadMeta(event.target.value);
              }}
            >
              {instruments.map((instrument) => (
                <option key={instrument.id} value={instrument.id}>
                  {instrument.title}
                  {instrument.id === boundInstrumentId ? " (this workshop)" : ""}
                </option>
              ))}
            </Select>
          </Field>
          <Field label="Status">
            {canPickStatus ? (
              <Select name="status" defaultValue="APPROVED">
                {["DRAFT", "PENDING", "APPROVED", "REJECTED"].map((status) => (
                  <option key={status}>{status}</option>
                ))}
              </Select>
            ) : (
              <span
                className="inline-flex min-h-10 items-center gap-2 self-start rounded-md border border-line-200 bg-field-100 px-3 py-2 text-sm font-medium text-ink-muted"
                title="New interviews are submitted for review as Pending."
              >
                <Lock className="h-4 w-4" aria-hidden />
                Pending
              </span>
            )}
          </Field>
          {/*
            ONE CONTROL FOR EVERYBODY THE INTERVIEW IS WITH — the owner's third report: *"why are
            there primary and other artisans two different dropdowns in the web application? Why can
            it not be a multi-select dropdown that covers both?"*

            It can, and there was never anything under the split to defend: the database join has no
            rank column, and this form already de-duplicated the two controls into one set before
            every request it sent. The two boxes only ever created work — a set could be assembled
            two ways, "Primary" could be left blank while "Additional" held three people, and the
            RESP block then described nobody.

            `MultiSelectDropdown` and not a new control. It is a thin adapter over
            `components/ui/SearchableSelect`, which floats its panel through `AnchoredPopover` (so
            nothing shears it off), carries a filter box and "Select all N matching", and is the same
            primitive every other multi-select in this product uses. `confirmOnSelect` stays at its
            default: this fills in a form field rather than filtering the screen it sits on, so a
            researcher who has ticked three people needs the panel to say "done" and move on.

            `md:col-span-2` because an artisan row is "Name - Craft - Place" and the four-column
            field grid gives it about a third of the width it needs; the workshop and the instrument
            beside it are short by comparison.
          */}
          <div className="md:col-span-2">
            <Field label="Artisans interviewed">
              <MultiSelectDropdown
                values={selectedArtisanIds}
                onChange={(next) => {
                  setSelectedArtisanIds(next);
                  // An explicit pick replaces the remembered context and retires the banner: from
                  // here on the artisans on screen are the researcher's own choice, not a
                  // suggestion. The bag holds ONE artisan, so it is handed the head of the new set —
                  // see `primaryInterviewArtisanId`. An untick down to nothing remembers nothing
                  // rather than remembering a blank: the banner is retired either way, and writing
                  // an empty artisan into the bag would wipe a context the researcher never
                  // dismissed.
                  const head = knownArtisans.find((artisan) => artisan.id === primaryInterviewArtisanId(next));
                  if (head) {
                    carry.remember(
                      {
                        artisanId: head.id,
                        artisanName: head.name,
                        place: head.place,
                        craftId: head.craftId,
                        craftName: head.craft?.name ?? null
                      },
                      { explicit: true }
                    );
                  }
                }}
                options={artisanOptions}
                /*
                  THE SEARCH BOX IS FORCED ON rather than left to `SEARCH_THRESHOLD`'s option count.
                  This is the argument `ComboBox` makes for itself in `components/ui/Dropdown`: the
                  caller means "this list is meant to be searched" regardless of how few records
                  exist today, and an artisan roster is the clearest case of it — one workshop on
                  this deployment, forty on the next, and a researcher who knows the name and not the
                  position. Letting the count decide also makes the control CHANGE SHAPE when the
                  workshop changes, which is a picker that behaves differently at two workshops for
                  reasons the researcher cannot see.
                */
                searchable
                placeholder="Select the artisans this interview is with"
                /*
                  FOUR SENTENCES AND NOT ONE, because "the request failed", "the answer has not
                  arrived", "nobody is recorded at this workshop" and "nobody is recorded at all" are
                  four different facts and only two of them are about the repository.

                  "No artisans are recorded at this workshop yet" is a CLAIM. Printing it off an
                  empty array while the roster for the workshop now on screen is still in flight — or
                  after a dropped request — makes that claim before the answer exists, and a
                  researcher who reads it goes looking for a way to add an artisan who is already
                  there. It is the same rule `components/forms/recordPickers` states for its own
                  pickers: a caller must check that the list it holds belongs to the selection it is
                  describing before it says anything about emptiness.

                  THE EMPTY ARRAY IS NOW A ROUTINE STATE rather than a rare one, which is why the
                  failure arm had to be added. `useWorkshopArtisans` drops the previous workshop's
                  roster the moment the workshop changes (contract rule 4), so this control is
                  genuinely empty for the length of every scoped request, and STAYS empty when one
                  fails (rule 5). `loadedForWorkshop` tells the first two apart; `failed` is the only
                  thing that can tell a dropped request from a workshop with nobody in it, and
                  without it the handset and the browser would each invent their own answer.
                */
                emptyLabel={
                  artisanScope.failed
                    ? "This workshop's artisan list could not be loaded"
                    : artisanScope.loadedForWorkshop !== workshop.workshopId
                      ? "Loading artisans…"
                      : workshop.workshopId
                        ? "No artisans are recorded at this workshop yet"
                        : "No artisans recorded yet"
                }
              />
              {/*
                THE FAILED NARROWING, BESIDE THE CONTROL IT IS ABOUT — and not in the page's error
                banner, which is driven by the instrument load: that is the failure that stops the
                form working at all, and putting a roster blip next to it would teach a reader to
                discount both. A researcher on a field connection can re-pick the workshop to ask
                again, which is why the sentence says so; the alternative considered was a Retry
                button, and it was rejected because it is a second control for a gesture the form
                already has and the picker is where the researcher's hand already is.

                Deliberately NOT red. Nothing is broken and nothing has been lost — the form still
                saves, the ticks are still ticked, and the only thing missing is the offer.
              */}
              {artisanScope.failed ? (
                <p className="mt-1 text-xs leading-5 text-ink-500">
                  This workshop&apos;s artisan list could not be loaded, so nobody is being offered above.
                  Pick the workshop again to ask for it once more. Anyone already ticked is still on this
                  interview.
                </p>
              ) : null}
              {/*
                WHAT THIS PICKER IS NOT SHOWING — `components/data/cappedList`'s standing rule, that a
                list which quietly stops is indistinguishable from a place with no records. It matters
                more here than anywhere else it is used: every other capped picker costs a reader a
                second look, and this one decides whether an interview can be filed against the person
                who gave it at all.

                `reach="none"` and not `"search"`. The search box inside `SearchableMultiSelect`
                filters the options array in the browser; it does not send a term to the server. Only
                `components/forms/RecordSwitcher` has earned `"search"`, and claiming it here would be
                the same lie one layer down — the researcher would type a name, see nothing, and read
                that as a fact about the workshop.
              */}
              <CappedListNotice cuts={[artisanScope.cut]} />
              {/*
                WHO IS TICKED THAT THIS WORKSHOP'S ROSTER DOES NOT ACCOUNT FOR.

                The form used to untick these people and say nothing. It now keeps them and says
                this, for the reasons `interviewArtisans.ts` sets out under rule 6 — chief among them
                that an interview filed here is ONE OF THE THREE THINGS that links an artisan to a
                workshop, so the roster's silence about somebody is not a verdict on them.

                UNDER the capped-list line rather than above it, because the two answer questions of
                different sizes: the cut line is about the list, this is about the record being
                saved. And they are mutually exclusive in practice — `artisansNotAtWorkshop` says
                nothing at all while the roster is cut, since a truncated list cannot prove an
                absence.
              */}
              {outOfWorkshopMessage ? (
                <p className="mt-1 text-xs leading-5 text-ink-500">{outOfWorkshopMessage}</p>
              ) : null}
            </Field>
          </div>
        </div>
        {/* The theme defines exactly three amber tokens (100/500/800); 50/200/300/700 are not
            Tailwind classes here and silently render as nothing. */}
        {existingEntry ? (
          <section className="rounded-lg border border-amber-500 bg-amber-100 p-4">
            <h3 className="font-display font-bold text-lg text-amber-800">A shared entry already exists for this set of artisans</h3>
            <p className="mt-1 text-sm text-amber-800">
              There is one questionnaire entry per set of artisans. Saving below adds your answers and media to{" "}
              <span className="font-medium">{existingEntry.title}</span> — it will not create a duplicate. Questions
              already answered by someone else are shown here and can only be changed by that contributor or an admin.
            </p>
            {existingEntry.responses && existingEntry.responses.length > 0 ? (
              <div className="mt-3 grid gap-2">
                <div className="text-xs font-semibold uppercase tracking-wide text-amber-800">
                  Already recorded ({existingEntry.responses.length})
                </div>
                {existingEntry.responses.map((response) => (
                  <div key={response.id} className="rounded-md border border-amber-500 bg-card/70 p-2 text-xs">
                    <div className="font-semibold text-ink">
                      {response.question?.sectionCode ? `[${response.question.sectionCode}] ` : ""}
                      {response.question?.prompt ?? "Question"}
                    </div>
                    <div className="mt-1 whitespace-pre-wrap text-ink-muted">{response.answerText}</div>
                    {response.answeredBy?.name ? (
                      <div className="mt-1 text-amber-800">Recorded by {response.answeredBy.name}</div>
                    ) : null}
                  </div>
                ))}
              </div>
            ) : (
              <p className="mt-2 text-xs text-amber-800">No questions answered yet — you can be the first to fill them in.</p>
            )}
          </section>
        ) : null}
        {selectedArtisan ? (
          <section className="rounded-lg border border-line-200 bg-field-100 p-4">
            <h3 className="font-display font-bold text-lg text-ink">RESP. Respondent Information</h3>
            <div className="mt-2 grid gap-2 text-sm text-ink-muted sm:grid-cols-2 lg:grid-cols-3">
              <div><span className="font-medium text-ink">Name:</span> {selectedArtisan.name}</div>
              <div><span className="font-medium text-ink">Craft:</span> {selectedArtisan.craft?.name ?? "-"}</div>
              <div><span className="font-medium text-ink">Place:</span> {selectedArtisan.place}</div>
              <div><span className="font-medium text-ink">Gender:</span> {selectedArtisan.gender ?? "-"}</div>
              <div><span className="font-medium text-ink">Contact:</span> {selectedArtisan.phone || selectedArtisan.email || "-"}</div>
            </div>
          </section>
        ) : null}
        {/* Replaces the old "Record audio answers / Type answers manually" pair: that switch governed
            the same text boxes as the toggle below, under a different name than Android uses for it. */}
        <QuestionnaireCaptureControls prefs={capture} onChange={updateCapture} />
        {/* Always offered, as on Android: audio for the interview as a whole, distinct from the
            per-section and per-question takes and never hidden by the answer-box toggle. */}
        <MediaCaptureField
          files={mediaFiles}
          onFilesChange={setMediaFiles}
          title="Interview audio"
          description="Record or upload interview audio. The backend will transcribe it when a transcription provider API key (ElevenLabs, Deepgram, or OpenAI) is configured; otherwise the audio is still saved."
          allowDocuments={false}
          allowedTypes={["AUDIO"]}
        />
        <UploadProgress progress={interviewProgress} sectionId={INTERVIEW_SECTION} label={INTERVIEW_SECTION_LABEL} />
        <LocationFields />
        <div className="grid gap-3">
          {orderedGroups.map(([code, group], index) => (
            <details key={code} className="rounded-md border border-line-200 bg-field-100 p-3" open={index === 0}>
              <summary className="cursor-pointer font-display font-bold text-lg text-ink">
                {code}. {group.title}
              </summary>
              <div className="mt-3 grid gap-3">
                {/* One take for the whole section. Rendered whenever such a take EXISTS, not only in
                    section mode, so switching to individual mode part-way never hides audio that is
                    still going to be saved. */}
                {capture.recordingMode === "SECTION" || questionAudioFiles[sectionClipKey(group.section.id)]?.length ? (
                  <div className="rounded-md border border-line-200 bg-card p-3">
                    <ClipRecorder
                      hint={
                        capture.recordingMode === "SECTION"
                          ? "Record this entire section in one take."
                          : "A whole-section take from earlier. It still uploads and saves with this interview."
                      }
                      showRecordButton={capture.recordingMode === "SECTION"}
                      recording={recordingKey === sectionClipKey(group.section.id)}
                      recordLabel="Record section"
                      stopLabel="Stop section recording"
                      onStart={() =>
                        startRecording(
                          sectionClipKey(group.section.id),
                          `${safeFileName(group.section.code)}-sec-${safeFileName(group.section.title)}`
                        )
                      }
                      onStop={stopRecording}
                      files={questionAudioFiles[sectionClipKey(group.section.id)] ?? []}
                      previews={questionAudioPreviews[sectionClipKey(group.section.id)] ?? []}
                      countLabel={clipCountLabel(sectionClipKey(group.section.id))}
                      onClear={() => setQuestionAudioFiles((current) => ({ ...current, [sectionClipKey(group.section.id)]: [] }))}
                      onRemove={(index) =>
                        setQuestionAudioFiles((current) => ({
                          ...current,
                          [sectionClipKey(group.section.id)]: (current[sectionClipKey(group.section.id)] ?? []).filter((_, i) => i !== index)
                        }))
                      }
                      onOpenPreview={setActivePreview}
                      stream={questionStream}
                      elapsedMs={questionElapsedMs}
                    />
                    <UploadProgress
                      progress={questionProgress[sectionClipKey(group.section.id)] ?? null}
                      sectionId={clipTraySectionId(sectionClipKey(group.section.id))}
                      label={`Section ${group.section.code} audio`}
                      className="mt-2"
                    />
                  </div>
                ) : null}
                {group.items.map((question) => (
                  // Not <Field>: that wraps its children in a <label>, and a <label> names its first
                  // labelable descendant — which here is the record button, not the answer box. The
                  // button ended up announced as the whole prompt and the textarea as nothing at
                  // all. A plain heading plus aria-labelledby names both correctly.
                  <div key={question.id} className="grid gap-1">
                    <span className="field-label" id={`question-label-${question.id}`}>
                      {question.sortOrder}. {question.prompt}
                      <RequiredByInstrument question={question} />
                    </span>
                    {/* THE PRO-FORMA'S "Help text" COLUMN, ON THE SCREEN THE ANSWER IS GIVEN ON.
                        An admin types guidance under a question in Excel and the import stores it;
                        rendering it nowhere would mean the app asked a question and ignored the
                        answer — eighty-one help texts in the database and none on any screen. It is
                        deliberately OUTSIDE the label: `aria-labelledby` names the textarea with the
                        prompt, and folding a paragraph of guidance into that label would make a
                        screen reader announce the whole thing every time focus entered the box. */}
                    <QuestionHelpText question={question} />
                    {/* Same rule as the section recorder above: the button belongs to individual
                        mode, but clips already recorded stay visible and stay saved in either. */}
                    {capture.recordingMode === "INDIVIDUAL" || questionAudioFiles[question.id]?.length ? (
                      <ClipRecorder
                        hint={
                          capture.recordingMode === "INDIVIDUAL"
                            ? undefined
                            : "Recorded against this question earlier. It still uploads and saves with this interview."
                        }
                        showRecordButton={capture.recordingMode === "INDIVIDUAL"}
                        recording={recordingKey === question.id}
                        recordLabel="Record this question"
                        stopLabel="Stop question recording"
                        onStart={() =>
                          startRecording(
                            question.id,
                            `${safeFileName(question.sectionCode)}-${question.sortOrder}-${safeFileName(question.prompt)}`
                          )
                        }
                        onStop={stopRecording}
                        files={questionAudioFiles[question.id] ?? []}
                        previews={questionAudioPreviews[question.id] ?? []}
                        countLabel={clipCountLabel(question.id)}
                        onClear={() => setQuestionAudioFiles((current) => ({ ...current, [question.id]: [] }))}
                        onRemove={(index) =>
                          setQuestionAudioFiles((current) => ({
                            ...current,
                            [question.id]: (current[question.id] ?? []).filter((_, i) => i !== index)
                          }))
                        }
                        onOpenPreview={setActivePreview}
                        stream={questionStream}
                        elapsedMs={questionElapsedMs}
                      />
                    ) : null}
                    {capture.hideAnswers ? null : (
                      <>
                        <TextArea
                          aria-labelledby={`question-label-${question.id}`}
                          value={answers[question.id] ?? ""}
                          onChange={(event) => setAnswers((current) => ({ ...current, [question.id]: event.target.value }))}
                        />
                        {/*
                          THE ANSWER BOX IS THE REASON THIS PAGE HAS MICROPHONES AT ALL. A researcher
                          sitting on the floor of a workshop is holding a phone in one hand and a
                          question sheet in the other; an eighty-one-question instrument typed with a
                          thumb is why the answer boxes were switched off by default in the first
                          place.

                          THE BARE BUTTON AND NOT `DictatedTextArea`, which is the component every
                          other multi-line box on this page uses. Two reasons, both structural: this
                          box is named by the prompt above it through `aria-labelledby`, and
                          `DictatedTextArea` renders a `<label>` of its own — mounting it here would
                          print the prompt twice and a screen reader would read it twice. And the
                          value lives in the page's `answers` map (it is what `submit` turns into
                          `responses`), while `DictatedTextArea` owns its value internally and reports
                          it only through `FormData` under a `name`; these boxes have no `name`,
                          because eighty-one of them in one `FormData` is not how an interview is
                          submitted.

                          `setAnswers((current) => …)` — THE UPDATER FORM, NOT `answers[question.id]`
                          READ FROM THE RENDER CLOSURE. `OnDeviceDictationButton` installs its
                          recogniser handlers once and reaches this callback through a ref, so the
                          callback it calls is current; what is NOT current is any value this closure
                          captured. Reading the map from the closure would append each phrase to the
                          answers as they stood when the microphone was pressed, silently discarding
                          everything typed into any box in between — invisible while testing with one
                          short phrase and obvious only to the researcher who dictated three
                          paragraphs. `appendDictatedPhrase` is the shared joiner rule
                          (`components/richtext/dictatedValue.ts`): it APPENDS, because the recogniser
                          stops and starts at every pause for breath, and it puts a single space in
                          unless the box already ends in whitespace, or an answer dictated in five
                          goes comes out as "…the warpis sized…".

                          `explainWhenUnavailable={false}` on every one of these: the page says it
                          once at the top of the form.
                        */}
                        <OnDeviceDictationButton
                          fieldLabel={`the answer to question ${question.sectionCode}${question.sortOrder}`}
                          explainWhenUnavailable={false}
                          onCommit={(phrase) =>
                            setAnswers((current) => ({
                              ...current,
                              [question.id]: appendDictatedPhrase(current[question.id] ?? "", phrase)
                            }))
                          }
                        />
                      </>
                    )}
                    <UploadProgress
                      progress={questionProgress[question.id] ?? null}
                      sectionId={clipTraySectionId(question.id)}
                      label={`Q${question.sectionCode}${question.sortOrder} audio`}
                      className="mt-2"
                    />
                  </div>
                ))}
              </div>
            </details>
          ))}
        </div>
        <MultiNoteField name="notes" label="Interview notes" />
        <div>
          <button className="field-button" disabled={saving}>
            <Plus className="h-4 w-4" aria-hidden />
            {saving ? "Saving..." : existingEntry ? "Add to shared entry" : "Save interview"}
          </button>
        </div>
      </form>

      <div className="mb-4 grid gap-3">
        <FunnelFilters
          value={funnel}
          onChange={(next) => {
            setFunnel(next);
            setFunnelReady(true);
            setPage(1);
          }}
          showArtisan
        />
        <SearchInput
          value={searchInput}
          onChange={handleSearchChange}
          onSubmit={applySearch}
          placeholder="Search interviews by title, place, or notes"
        />
      </div>

      <section className="panel overflow-hidden">
        {!data ? (
          <div className="p-4 text-sm text-ink-700">Loading...</div>
        ) : data.items.length === 0 ? (
          <div className="p-4">
            <EmptyState title="No questionnaire interviews yet" />
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full min-w-[980px] text-left text-sm">
              <thead className="bg-surface-50 text-xs uppercase text-ink-500">
                <tr>
                  <th className="px-4 py-3">Interview</th>
                  <th className="px-4 py-3">Artisans</th>
                  <th className="px-4 py-3">Responses</th>
                  <th className="px-4 py-3">Researcher</th>
                  <th className="px-4 py-3">Status</th>
                  <th className="px-4 py-3">Date</th>
                  <th className="px-4 py-3 text-right">Actions</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-line-200">
                {data.items.map((interview) => (
                  <tr key={interview.id}>
                    <td className="px-4 py-3">
                      <div className="font-medium text-ink-900">{interview.title}</div>
                      <div className="text-xs text-ink-500">{interview.place ?? "-"}</div>
                    </td>
                    <td className="px-4 py-3 text-ink-700">
                      {interview.artisans?.map((link) => link.artisan.name).join(", ") || "-"}
                    </td>
                    <td className="px-4 py-3 text-ink-700">
                      <details>
                        <summary className="cursor-pointer font-semibold text-field-700">{interview.responses?.length ?? 0} answers</summary>
                        <div className="mt-2 grid max-w-lg gap-2">
                          {interview.responses?.map((response) => (
                            <div key={response.id} className="rounded-md bg-field-100 p-2 text-xs">
                              <div className="font-semibold text-ink">{response.question?.prompt}</div>
                              <div className="mt-1 whitespace-pre-wrap text-ink-muted">{response.answerText}</div>
                            </div>
                          ))}
                        </div>
                      </details>
                    </td>
                    <td className="px-4 py-3 text-ink-700">{interview.createdBy?.email ?? "-"}</td>
                    <td className="px-4 py-3">
                      <StatusBadge status={interview.status} />
                    </td>
                    {/* interviewDate is server-derived; recordedAt is what it is derived FROM, so it
                        is the right fallback for a row saved before the derivation existed. */}
                    <td className="px-4 py-3 text-ink-700">
                      {formatDate(interview.interviewDate ?? interview.recordedAt ?? interview.createdAt)}
                    </td>
                    <td className="px-4 py-3 text-right">
                      {adminMode ? (
                        <RowActions>
                          <button className={rowAction("danger")} onClick={() => remove(interview.id)}>
                            Delete
                          </button>
                        </RowActions>
                      ) : (
                        <span className="text-xs text-ink-500">Admin view only</span>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {data ? <Pagination page={data.page} pages={data.pages} total={data.total} onPage={setPage} /> : null}
      </section>

      {/* 2) Questionnaire builder — bottom of the page, collapsed by default. */}
      {canManageQuestionnaire(user) ? (
        <QuestionnaireAdminEditor
          sections={sections}
          questionnaireId={questionnaireId}
          onChanged={() => loadMeta(questionnaireId)}
        />
      ) : null}

      {activePreview ? <MediaLightbox item={activePreview} onClose={() => setActivePreview(null)} /> : null}
    </>
  );
}

function safeFileName(value: string) {
  return value
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-|-$/g, "")
    .slice(0, 80) || "question-audio";
}

/**
 * The recorder for one clip key — a whole section, or a single question. One component for both so
 * the two modes cannot drift into two slightly different recorders, and so a clip list is rendered
 * identically whether or not its recorder's button is currently on offer.
 */
function ClipRecorder({
  hint,
  showRecordButton,
  recording,
  recordLabel,
  stopLabel,
  onStart,
  onStop,
  files,
  previews,
  countLabel,
  onClear,
  onRemove,
  onOpenPreview,
  stream,
  elapsedMs
}: {
  hint?: string;
  showRecordButton: boolean;
  recording: boolean;
  recordLabel: string;
  stopLabel: string;
  onStart: () => void;
  onStop: () => void;
  files: File[];
  previews: PreviewMedia[];
  countLabel: string;
  onClear: () => void;
  onRemove: (index: number) => void;
  onOpenPreview: (item: PreviewMedia) => void;
  stream: MediaStream | null;
  elapsedMs: number;
}) {
  return (
    <>
      {hint ? <p className="mb-2 text-xs text-ink-muted">{hint}</p> : null}
      <div className="mb-2 flex flex-wrap items-center gap-2">
        {showRecordButton ? (
          <button type="button" className="field-button-secondary" onClick={() => (recording ? onStop() : onStart())}>
            {recording ? <Square className="h-4 w-4" aria-hidden /> : <Mic className="h-4 w-4" aria-hidden />}
            {recording ? stopLabel : recordLabel}
          </button>
        ) : null}
        {files.length ? (
          <>
            <span className="text-xs text-ink-muted">{countLabel}</span>
            <button type="button" className="text-xs font-semibold text-red-700" onClick={onClear}>
              Clear clips
            </button>
          </>
        ) : null}
      </div>
      {recording ? (
        <div className="mb-3">
          <RecordingStrip stream={stream} elapsedMs={elapsedMs} />
        </div>
      ) : null}
      {previews.length ? (
        <div className="mb-3 grid gap-2 sm:grid-cols-2 lg:grid-cols-3">
          {previews.map((item, index) => (
            <MediaPreviewTile
              key={item.key}
              item={item}
              onOpen={() => onOpenPreview(item)}
              action={
                <button type="button" className="text-xs font-semibold text-red-700" onClick={() => onRemove(index)}>
                  Remove
                </button>
              }
            />
          ))}
        </div>
      ) : null}
    </>
  );
}

// --- "Check completion" cellular matrix (Android parity) ---

type CompletionMatrix = {
  sections: Array<{ id: string; code: string; title: string; sortOrder: number }>;
  artisans: Array<{ id: string; name: string }>;
  cells: Array<{ artisanId: string; sectionId: string; derived: boolean; status: string | null; setByName?: string | null }>;
  /**
   * How many interviews the CHOSEN WORKSHOP SCOPE cannot see, because they name no workshop at all.
   *
   * The number exists so the failure mode can never be silent again. An interview with an empty
   * `workshopId` counts towards no workshop scope, which is correct — it genuinely does not say where it
   * was taken — but it is also exactly the shape of the bug that had this matrix reporting "nothing was
   * covered" while twenty-five interviews sat in the repository unlinked. Zero whenever the scope cannot
   * hide anything (no workshop chosen, or unassigned records explicitly included). Absent on an API that
   * predates the field.
   */
  unassignedInterviews?: number;
  /**
   * WHICH INSTRUMENT THIS MATRIX IS ABOUT. Two instruments run overlapping section codes, so a
   * grid of columns headed "A", "B", "C" is an unlabelled claim without this. Absent on an API
   * that predates the field.
   */
  questionnaireId?: string | null;
  questionnaireTitle?: string | null;
  /** True while an admin's mark is keyed on (artisan, section) alone, i.e. is not per workshop. */
  overridesAreRepositoryWide?: boolean;
};

/**
 * Artisans down the rows, questionnaire sections across the columns. A cell is green when the
 * section is covered — either derived from recorded answers/audio or forced by an admin override
 * (overrides carry an amber ring). In admin view, admins click a cell to cycle the override:
 * complete -> not complete -> clear (back to the derived state).
 */
function CompletionMatrixPanel({
  canOverride,
  questionnaireId
}: {
  canOverride: boolean;
  /**
   * WHICH INSTRUMENT'S MATRIX. Without it the server resolves the default, which is the right
   * answer for an old client and the wrong one for a page whose form is already open on the other
   * instrument — the columns would be one instrument's sections and the form's another's, both
   * labelled "A".
   */
  questionnaireId: string | null;
}) {
  const [matrix, setMatrix] = useState<CompletionMatrix | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [busyCell, setBusyCell] = useState<string | null>(null);
  const opened = useRef(false);

  /**
   * WHICH WORKSHOPS THIS MATRIX IS ABOUT, defaulting to the most recent one.
   *
   * The scope is held HERE rather than inside the accordion because the accordion unmounts its
   * children when it closes, and a selection that reset every time somebody collapsed the panel would
   * be worse than no control at all.
   *
   * It also scopes BOTH halves of the matrix on the server — which artisans are rows, and which
   * interviews count towards a cell. A matrix that scoped only one of the two would show a workshop's
   * artisans against every interview they have ever sat in, which is precisely the confusion this
   * control exists to remove.
   */
  const scope = useWorkshopScope();

  const refresh = useCallback(async () => {
    try {
      setMatrix(
        await apiFetch<CompletionMatrix>(
          `/questionnaire/completion${buildQuery({
            workshopIds: scope.queryValue,
            questionnaireId: questionnaireId ?? undefined
          })}`
        )
      );
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to load the completion matrix");
    }
  }, [scope.queryValue, questionnaireId]);

  /**
   * Load once the panel is open, and RELOAD whenever the scope moves — but never before the picker has
   * settled on its default, or the first request would go out scoped to "all" and be replaced a
   * moment later by the scoped one. Two requests, and for a second the matrix shows the wrong answer.
   */
  useEffect(() => {
    if (!opened.current || scope.settling) return;
    setLoading(true);
    refresh().finally(() => setLoading(false));
  }, [refresh, scope.settling]);

  function handleOpenChange(open: boolean) {
    if (!open || opened.current) return;
    opened.current = true;
    if (scope.settling) return; // the effect above fires as soon as the default lands
    setLoading(true);
    refresh().finally(() => setLoading(false));
  }

  const cellByKey = useMemo(() => {
    const map = new Map<string, CompletionMatrix["cells"][number]>();
    matrix?.cells.forEach((cell) => map.set(`${cell.artisanId}:${cell.sectionId}`, cell));
    return map;
  }, [matrix]);

  async function cycle(artisanId: string, sectionId: string) {
    if (!canOverride) return;
    const key = `${artisanId}:${sectionId}`;
    const cell = cellByKey.get(key);
    // No override yet -> force complete; forced complete -> force not complete; any other override -> clear.
    const next = !cell?.status ? "COMPLETED" : cell.status === "COMPLETED" ? "NEEDS_REDO" : null;
    setBusyCell(key);
    try {
      await apiFetch("/questionnaire/completion", {
        method: "PUT",
        body: JSON.stringify({ artisanId, sectionId, status: next })
      });
      await refresh();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to update the completion cell");
    } finally {
      setBusyCell(null);
    }
  }

  return (
    <Accordion
      title="Check completion"
      // The instrument is NAMED in the subtitle rather than left to be inferred from the column
      // headings. Two instruments run overlapping codes, so "A, B, C" across the top says nothing
      // about which questionnaire is being reported on.
      subtitle={`${
        matrix?.questionnaireTitle ? `${matrix.questionnaireTitle} — ` : ""
      }Which questionnaire sections are covered for each artisan, derived from recorded answers and audio.${
        canOverride ? " Click a cell to cycle an admin override: complete, not complete, clear." : ""
      }`}
      onOpenChange={handleOpenChange}
    >
      {/* The scope sits ABOVE the matrix, because it changes what every cell means. */}
      <div className="mb-4 max-w-xl">
        <WorkshopScopeSelect scope={scope} label="Workshops in this matrix" />
      </div>
      {error ? <div className="mb-3 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div> : null}
      {loading ? <div className="text-sm text-ink-muted">Loading completion matrix...</div> : null}

      {/* THE SHORTFALL, NAMED. An interview that names no workshop is excluded from this matrix under any
          workshop scope, and the exclusion used to be invisible: the matrix simply showed less green, which
          reads as fieldwork that never happened. Saying the number turns that into something an admin can
          act on — and the action is one page away. */}
      {matrix && (matrix.unassignedInterviews ?? 0) > 0 ? (
        <div className="mb-3 rounded-md border border-amber-500/40 bg-amber-100 px-3 py-2 text-xs leading-5 text-amber-800">
          <span className="font-semibold">
            {matrix.unassignedInterviews} interview{matrix.unassignedInterviews === 1 ? "" : "s"} in the
            repository name no workshop
          </span>
          , so {matrix.unassignedInterviews === 1 ? "it counts" : "they count"} towards no workshop scope and
          nothing {matrix.unassignedInterviews === 1 ? "it holds" : "they hold"} turns a cell green here.
          Choose <span className="font-semibold">All records</span> above to include{" "}
          {matrix.unassignedInterviews === 1 ? "it" : "them"}
          {canOverride ? (
            <>
              , or file {matrix.unassignedInterviews === 1 ? "it" : "them"} under a workshop from{" "}
              <Link href="/workshops" className="font-semibold underline">
                Workshops
              </Link>
            </>
          ) : null}
          .
        </div>
      ) : null}
      {matrix && matrix.artisans.length === 0 && !loading ? (
        <p className="text-sm text-ink-muted">
          {scope.workshopIds.length
            ? "No artisans in the chosen workshops. Widen the scope, or choose All records."
            : "No artisans yet — the matrix appears once artisans are recorded."}
        </p>
      ) : null}
      {matrix && matrix.artisans.length > 0 ? (
        <>
          <div className="overflow-x-auto">
            <table className="text-left text-sm">
              <thead>
                <tr>
                  <th className="py-2 pr-4 text-xs font-semibold uppercase text-ink-500">Artisan</th>
                  {matrix.sections.map((section) => (
                    <th
                      key={section.id}
                      className="px-1 pb-2 text-center align-bottom text-xs font-semibold text-ink-500"
                      title={`${section.code}. ${section.title}`}
                    >
                      {section.code}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {matrix.artisans.map((artisan) => (
                  <tr key={artisan.id}>
                    <td className="whitespace-nowrap py-1 pr-4 font-medium text-ink">{artisan.name}</td>
                    {matrix.sections.map((section) => {
                      const key = `${artisan.id}:${section.id}`;
                      const cell = cellByKey.get(key);
                      const overridden = Boolean(cell?.status);
                      const complete = cell ? (cell.status ? cell.status === "COMPLETED" : cell.derived) : false;
                      const state = overridden
                        ? `override ${cell?.status === "COMPLETED" ? "complete" : "not complete"}${cell?.setByName ? ` by ${cell.setByName}` : ""}`
                        : complete
                          ? "complete"
                          : "not complete";
                      const title = `${artisan.name} — ${section.code}: ${state}`;
                      const square = `h-6 w-6 rounded ${complete ? "bg-success-600" : "bg-line-200"}${overridden ? " ring-2 ring-amber-500" : ""}`;
                      return (
                        <td key={section.id} className="px-1 py-1 text-center">
                          {canOverride ? (
                            <button
                              type="button"
                              className={`${square} align-middle disabled:opacity-60`}
                              title={title}
                              aria-label={title}
                              disabled={busyCell === key}
                              onClick={() => cycle(artisan.id, section.id)}
                            />
                          ) : (
                            <div className={`mx-auto ${square}`} title={title} role="img" aria-label={title} />
                          )}
                        </td>
                      );
                    })}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <div className="mt-3 flex flex-wrap items-center gap-x-4 gap-y-2 text-xs text-ink-muted">
            <span className="inline-flex items-center gap-1.5">
              <span className="h-3.5 w-3.5 rounded bg-success-600" aria-hidden /> Complete
            </span>
            <span className="inline-flex items-center gap-1.5">
              <span className="h-3.5 w-3.5 rounded bg-line-200" aria-hidden /> Not complete
            </span>
            <span className="inline-flex items-center gap-1.5">
              <span className="h-3.5 w-3.5 rounded bg-line-200 ring-2 ring-amber-500" aria-hidden /> Admin override
            </span>
          </div>
          {/* WHAT THE WORKSHOP SCOPE DOES AND DOES NOT NARROW. The green derived from recordings is scoped
              to the chosen workshops; an admin's mark is not, because the override table is keyed on
              (artisan, section) with no workshop column — a mark is a judgement about that artisan's
              section across the repository. Said here rather than left for a reader to deduce from a cell
              that stays green when the scope moves. */}
          {matrix.overridesAreRepositoryWide ? (
            <p className="mt-2 text-[11px] leading-4 text-ink-500">
              The workshop scope narrows the green derived from recordings. An admin override is a judgement
              about that artisan&rsquo;s section across the whole repository, so a marked cell keeps its
              colour under every scope.
            </p>
          ) : null}
        </>
      ) : null}
    </Accordion>
  );
}

// --- Questionnaire builder (admin) — sections + drag-and-drop question tiles ---

type DragQuestion = { questionId: string; sectionId: string };
type DropIndicator = { sectionId: string; index: number };

/** Renumber a section's questions after an optimistic move and stamp them with the section identity. */
function reindexQuestions(list: QuestionnaireQuestion[], section: { id: string; code: string; title: string }): QuestionnaireQuestion[] {
  return list.map((question, index) => ({
    ...question,
    sortOrder: index + 1,
    sectionId: section.id,
    sectionCode: section.code,
    sectionTitle: section.title
  }));
}

/**
 * Pure optimistic move: takes the current sections and returns a new array with `questionId` moved
 * from its section to `gapIndex` within `toSectionId` (a drop-gap index, 0..length). Returns the
 * SAME reference when the move is a no-op so callers can skip the network round-trip.
 */
function moveQuestionInSections(
  sections: QuestionnaireSection[],
  questionId: string,
  fromSectionId: string,
  toSectionId: string,
  gapIndex: number
): QuestionnaireSection[] {
  const clone = sections.map((section) => ({ ...section, questions: [...section.questions] }));
  const from = clone.find((section) => section.id === fromSectionId);
  const to = clone.find((section) => section.id === toSectionId);
  if (!from || !to) return sections;
  const srcIndex = from.questions.findIndex((question) => question.id === questionId);
  if (srcIndex < 0) return sections;

  // A drop-gap counts the dragged tile; removing it first shifts every later gap left by one.
  let insertAt = from === to && srcIndex < gapIndex ? gapIndex - 1 : gapIndex;
  const [moved] = from.questions.splice(srcIndex, 1);
  insertAt = Math.max(0, Math.min(insertAt, to.questions.length));
  if (from === to && insertAt === srcIndex) return sections; // dropped back onto itself
  to.questions.splice(insertAt, 0, moved);
  from.questions = reindexQuestions(from.questions, from);
  if (to !== from) to.questions = reindexQuestions(to.questions, to);
  return clone;
}

/**
 * One section's "Title" box in the builder's edit row — a microphone on a box that submits through
 * `FormData`.
 *
 * WHY THIS IS A COMPONENT AND NOT THREE MORE LINES IN THE FORM ABOVE. `DictatedTextInput` is
 * controlled by its caller, and the edit row it sits in is otherwise uncontrolled: `updateSection`
 * reads `form.get("code")` and `form.get("title")` out of a `FormData` built from the submitted
 * element, and every other control in the row is seeded with `defaultValue`. Holding the title for
 * every section in one map in the parent would mean re-seeding that map from `localSections` on every
 * server reload — `onChanged` replaces the whole list after any add, remove, reorder or reparent —
 * and a map that missed one of those refreshes is a box showing a title the database no longer has.
 * Local state plus a re-seed keyed on the row's own identity is the same shape `QuestionTile` already
 * uses for `prompt` in this file, for the same reason.
 *
 * THE `name` IS STILL LOAD-BEARING. The component renders a real `<input name="title">`, so
 * `updateSection`'s `form.get("title")` reads it exactly as it read the `<TextInput>` this replaced.
 * Drop the attribute and Save writes `""` over the section's title — `String(form.get("title") ?? "")`
 * turns the missing key into an empty string and PATCHes it — which is a section renamed to nothing
 * on every question that denormalises `sectionTitle`.
 *
 * A TITLE IS FREE TEXT AND GETS A MICROPHONE; THE CODE BESIDE IT DOES NOT. See the note on the
 * add-section form for why an identity code is the one box dictation must not touch.
 */
function SectionTitleField({ section }: { section: QuestionnaireSection }) {
  const [value, setValue] = useState(section.title);

  /**
   * Re-seed when the ROW changes underneath us, which is not the same as when the component mounts.
   *
   * The builder keys its section blocks by `section.id`, so a reorder MOVES this component rather
   * than rebuilding it, and a save reloads every section from the server. Without this, a title
   * edited on the server (by the workbook import, or by another admin) would sit behind a stale local
   * value that the next Save would write straight back over. Depending on `section.title` and not
   * only on `section.id` is the half that catches the second case.
   */
  useEffect(() => {
    setValue(section.title);
  }, [section.id, section.title]);

  return (
    <DictatedTextInput
      name="title"
      label="Title"
      required
      explainWhenUnavailable={false}
      value={value}
      onChange={setValue}
    />
  );
}

function QuestionnaireAdminEditor({
  sections,
  questionnaireId,
  onChanged
}: {
  sections: QuestionnaireSection[];
  /** The instrument being built. A new section joins THIS one, not whichever is the default. */
  questionnaireId: string | null;
  onChanged: () => Promise<void>;
}) {
  const confirm = useConfirm();
  const [localSections, setLocalSections] = useState<QuestionnaireSection[]>(sections);
  const [newCode, setNewCode] = useState("");
  const [newTitle, setNewTitle] = useState("");
  const [dragSectionId, setDragSectionId] = useState<string | null>(null);
  const [dragQuestion, setDragQuestion] = useState<DragQuestion | null>(null);
  const [dropIndicator, setDropIndicator] = useState<DropIndicator | null>(null);
  const [busy, setBusy] = useState(false);
  const [saving, setSaving] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  /**
   * A REMOUNT COUNTER PER SECTION FOR THE ADD-QUESTION BOX, AND THE BUG IT CLOSES.
   *
   * That box is a `DictatedTextArea`, which owns its own value and re-seeds from `defaultValue` on
   * REMOUNT only. The add-question form clears itself with `formElement.reset()` — and `reset()`
   * rewrites the DOM node and tells React nothing at all. So without a key that changes, the prompt
   * an admin just filed STAYS ON SCREEN, the form looks as though nothing happened, and the next
   * press of "Add question" files the same question a second time. Two identical questions in one
   * section is not a cosmetic defect: the workbook download prints both, the re-upload matches both
   * by id, and every interview from then on asks the artisan the same thing twice.
   *
   * PER SECTION, KEYED BY `section.id`, NOT ONE COUNTER FOR THE BUILDER. A single number would
   * remount EVERY section's add-question box on every add, throwing away a prompt half-typed in
   * another section — the builder renders one such form per section and they are all on screen at
   * once. `?? 0` is the un-bumped state, so a section that has never been added to needs no entry.
   *
   * BUMPED ONLY ON SUCCESS, in the same block as the `reset()`. A failed add leaves the prompt in the
   * box on purpose: the admin's text is the only copy of it, and `addQuestion` throwing means the
   * server did not take it.
   */
  const [questionNonce, setQuestionNonce] = useState<Record<string, number>>({});

  // Server state is authoritative: whenever the parent reloads sections, replace the local copy
  // (this also lands the canonical result after an optimistic drag persists).
  useEffect(() => {
    setLocalSections(sections);
  }, [sections]);

  async function addSection(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setSaving(true);
    setMessage(null);
    try {
      await apiFetch("/questionnaire/sections", {
        method: "POST",
        // The instrument is sent EXPLICITLY. The server would resolve the default if it were
        // omitted — which is what an un-updated builder gets and is correct for it — but this page
        // knows which instrument it is showing, and adding a section to a different one than the
        // list above it is the mistake that field is for.
        body: JSON.stringify({ questionnaireId, code: newCode.trim(), title: newTitle.trim() })
      });
      setNewCode("");
      setNewTitle("");
      await onChanged();
    } catch (err) {
      setMessage(err instanceof Error ? err.message : "Unable to add section");
    } finally {
      setSaving(false);
    }
  }

  async function updateSection(section: QuestionnaireSection, form: FormData) {
    await apiFetch(`/questionnaire/sections/${section.id}`, {
      method: "PATCH",
      body: JSON.stringify({
        code: String(form.get("code") ?? "").trim(),
        title: String(form.get("title") ?? "").trim(),
        isActive: form.get("isActive") === "on"
      })
    });
    await onChanged();
  }

  async function removeSection(section: QuestionnaireSection) {
    // Amber, not red: this is a deactivation. Nothing already collected is lost, and saying so is the
    // difference between a manager pruning the form and a manager afraid to touch it.
    const ok = await confirm({
      title: `Remove section ${section.code}?`,
      body: (
        <>
          <span className="font-medium text-ink-900">{section.title}</span> and its questions stop appearing in new
          interviews.
        </>
      ),
      note: "Questions are deactivated, not erased: answers already recorded against them stay on the interviews that hold them.",
      tone: "warning",
      confirmLabel: "Remove section"
    });
    if (!ok) return;
    setMessage(null);
    try {
      await apiFetch(`/questionnaire/sections/${section.id}`, { method: "DELETE" });
      await onChanged();
    } catch (err) {
      setMessage(err instanceof Error ? err.message : "Unable to remove section");
    }
  }

  // Optimistically reorder sections, persist, and roll back if the server rejects it.
  async function performSectionReorder(fromId: string, toId: string) {
    if (!fromId || fromId === toId) return;
    const fromIndex = localSections.findIndex((section) => section.id === fromId);
    const toIndex = localSections.findIndex((section) => section.id === toId);
    if (fromIndex < 0 || toIndex < 0) return;
    const next = [...localSections];
    const [moved] = next.splice(fromIndex, 1);
    next.splice(toIndex, 0, moved);
    const reindexed = next.map((section, index) => ({ ...section, sortOrder: index + 1 }));
    const snapshot = localSections;
    setLocalSections(reindexed);
    setBusy(true);
    setMessage(null);
    try {
      await apiFetch("/questionnaire/sections/reorder", {
        method: "POST",
        body: JSON.stringify({ sectionIds: reindexed.map((section) => section.id) })
      });
      await onChanged();
    } catch (err) {
      setLocalSections(snapshot);
      setMessage(err instanceof Error ? err.message : "Unable to reorder sections");
    } finally {
      setBusy(false);
    }
  }

  async function addQuestion(section: QuestionnaireSection, form: FormData) {
    const prompt = String(form.get("prompt") ?? "").trim();
    if (!prompt) return;
    await apiFetch("/questionnaire/questions", {
      method: "POST",
      body: JSON.stringify({ sectionId: section.id, prompt })
    });
    await onChanged();
  }

  async function saveQuestion(question: QuestionnaireQuestion, prompt: string, isActive: boolean): Promise<boolean> {
    setBusy(true);
    setMessage(null);
    try {
      await apiFetch(`/questionnaire/questions/${question.id}`, {
        method: "PATCH",
        body: JSON.stringify({ prompt: prompt.trim(), isActive })
      });
      await onChanged();
      return true;
    } catch (err) {
      setMessage(err instanceof Error ? err.message : "Unable to update question");
      return false;
    } finally {
      setBusy(false);
    }
  }

  async function removeQuestion(question: QuestionnaireQuestion) {
    const ok = await confirm({
      title: "Remove this question?",
      body: (
        <>
          <span className="font-medium text-ink-900">{question.prompt}</span> stops appearing in new interviews.
        </>
      ),
      note: "Answers already recorded against it stay linked to the interviews that hold them.",
      tone: "warning",
      confirmLabel: "Remove question"
    });
    if (!ok) return;
    setMessage(null);
    try {
      await apiFetch(`/questionnaire/questions/${question.id}`, { method: "DELETE" });
      await onChanged();
    } catch (err) {
      setMessage(err instanceof Error ? err.message : "Unable to remove question");
    }
  }

  /**
   * Optimistically apply a question move, then persist. Within a section this is a single reorder;
   * across sections we first re-parent the question (updateQuestion{sectionId}) and then reorder the
   * TARGET section — exactly the two existing API calls. Rolls back on any error.
   */
  async function persistMove(next: QuestionnaireSection[], reorderSectionId: string, reparent?: { questionId: string; toSectionId: string }) {
    const snapshot = localSections;
    setLocalSections(next);
    setBusy(true);
    setMessage(null);
    try {
      if (reparent) {
        await apiFetch(`/questionnaire/questions/${reparent.questionId}`, {
          method: "PATCH",
          body: JSON.stringify({ sectionId: reparent.toSectionId })
        });
      }
      const target = next.find((section) => section.id === reorderSectionId);
      if (target) {
        await apiFetch("/questionnaire/questions/reorder", {
          method: "POST",
          body: JSON.stringify({ sectionId: reorderSectionId, questionIds: target.questions.map((question) => question.id) })
        });
      }
      await onChanged();
    } catch (err) {
      setLocalSections(snapshot);
      setMessage(err instanceof Error ? err.message : "Unable to move question");
    } finally {
      setBusy(false);
    }
  }

  function performQuestionMove(drag: DragQuestion, toSectionId: string, gapIndex: number) {
    const next = moveQuestionInSections(localSections, drag.questionId, drag.sectionId, toSectionId, gapIndex);
    setDragQuestion(null);
    setDropIndicator(null);
    if (next === localSections) return; // no-op
    persistMove(next, toSectionId, drag.sectionId !== toSectionId ? { questionId: drag.questionId, toSectionId } : undefined);
  }

  // Keyboard fallback: swap a question with its neighbour inside the same section.
  function keyboardMove(section: QuestionnaireSection, question: QuestionnaireQuestion, direction: -1 | 1) {
    const current = localSections.find((item) => item.id === section.id);
    if (!current) return;
    const index = current.questions.findIndex((item) => item.id === question.id);
    const target = index + direction;
    if (index < 0 || target < 0 || target >= current.questions.length) return;
    const nextQuestions = [...current.questions];
    [nextQuestions[index], nextQuestions[target]] = [nextQuestions[target], nextQuestions[index]];
    const next = localSections.map((item) =>
      item.id === section.id ? { ...item, questions: reindexQuestions(nextQuestions, item) } : item
    );
    persistMove(next, section.id);
  }

  function moveQuestionToSection(question: QuestionnaireQuestion, fromSectionId: string, toSectionId: string) {
    if (!toSectionId || toSectionId === fromSectionId) return;
    const target = localSections.find((section) => section.id === toSectionId);
    performQuestionMove({ questionId: question.id, sectionId: fromSectionId }, toSectionId, target ? target.questions.length : 0);
  }

  const dragActive = Boolean(dragQuestion);

  return (
    <Accordion
      title="Questionnaire Builder"
      subtitle="Master admin controls for sections, ordering, question text, moves and removals."
      headerRight={message ? <span className="text-sm text-red-700">{message}</span> : null}
    >
      <div className="grid gap-4">
        <form onSubmit={addSection} className="grid gap-3 rounded-md border border-line-200 bg-field-100 p-3 md:grid-cols-[160px_1fr_auto]">
          {/* NO MICROPHONE ON THE CODE. "A", "RESP", "FIELD" is an identity code in a closed set the
              instrument already uses — every question denormalises it as `sectionCode` and the
              workbook matches rows on it — and a recogniser asked for "RESP" hands back "resp",
              "rest" or "R E S P" with equal confidence. A box whose value must be exact is the one
              box dictation must not touch. */}
          <Field label="Section code">
            <TextInput value={newCode} onChange={(event) => setNewCode(event.target.value)} placeholder="A, RESP, FIELD..." required />
          </Field>
          {/* NO `name`, AND THAT IS CORRECT HERE RATHER THAN AN OVERSIGHT: `addSection` builds its
              body from `newCode`/`newTitle` state and never constructs a `FormData` at all, so a
              `name` would submit through a channel nothing reads. Contrast the three header boxes at
              the top of this file, where dropping `name` silently drops the value. */}
          <DictatedTextInput
            label="Section title"
            placeholder="Section title"
            required
            explainWhenUnavailable={false}
            value={newTitle}
            onChange={setNewTitle}
          />
          <div className="flex items-end">
            <button className="field-button" disabled={saving}>
              <Plus className="h-4 w-4" aria-hidden />
              Add section
            </button>
          </div>
        </form>

        <p className="text-xs text-ink-muted">
          Drag a question by its grip to reorder it within a section or drop it into another section. Keyboard users can use the
          up/down buttons on each tile, or the &ldquo;Move to&rdquo; menu to change section.
        </p>

        <div className="grid gap-4">
          {localSections.map((section) => (
            <div
              key={section.id}
              className={`rounded-md border bg-card p-3 transition ${dragSectionId === section.id ? "border-field-600 ring-2 ring-field-200" : "border-line-200"}`}
              onDragOver={(event) => {
                if (dragSectionId) event.preventDefault();
              }}
              onDrop={(event) => {
                if (!dragSectionId) return;
                event.preventDefault();
                performSectionReorder(dragSectionId, section.id);
                setDragSectionId(null);
              }}
            >
              <div className="flex items-center gap-2 font-display font-bold text-xl text-ink">
                <button
                  type="button"
                  className="grid h-9 w-9 cursor-grab place-items-center rounded-md border border-line-200 bg-field-50 text-ink-muted focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-field-600 active:cursor-grabbing disabled:opacity-60"
                  draggable={!busy}
                  disabled={busy}
                  aria-label={`Drag section ${section.code}`}
                  onDragStart={(event) => {
                    setDragSectionId(section.id);
                    event.dataTransfer.effectAllowed = "move";
                    event.dataTransfer.setData("text/plain", section.id);
                  }}
                  onDragEnd={() => setDragSectionId(null)}
                >
                  <GripVertical className="h-4 w-4" aria-hidden />
                </button>
                <span>{section.sortOrder}. {section.code} - {section.title}</span>
              </div>

              <form
                className="mt-3 grid gap-3 md:grid-cols-[120px_1fr_auto_auto]"
                onSubmit={async (event) => {
                  event.preventDefault();
                  const formData = new FormData(event.currentTarget);
                  setMessage(null);
                  try {
                    await updateSection(section, formData);
                  } catch (err) {
                    setMessage(err instanceof Error ? err.message : "Unable to update section");
                  }
                }}
              >
                <Field label="Code">
                  <TextInput name="code" defaultValue={section.code} required />
                </Field>
                <SectionTitleField section={section} />
                <label className="flex items-end gap-2 pb-2 text-sm text-ink-muted">
                  <input name="isActive" type="checkbox" defaultChecked={section.isActive} />
                  Active
                </label>
                <div className="flex flex-wrap items-end gap-2">
                  <button className="field-button-secondary" type="submit">
                    <Save className="h-4 w-4" aria-hidden />
                    Save
                  </button>
                  <button type="button" className="text-sm font-semibold text-red-700" onClick={() => removeSection(section)}>
                    <Trash2 className="inline h-4 w-4" aria-hidden /> Remove
                  </button>
                </div>
              </form>

              <div
                className="mt-4 grid gap-1"
                onDragOver={(event) => {
                  if (dragActive && section.questions.length === 0) {
                    event.preventDefault();
                    setDropIndicator({ sectionId: section.id, index: 0 });
                  }
                }}
                onDrop={(event) => {
                  if (dragQuestion && section.questions.length === 0) {
                    event.preventDefault();
                    performQuestionMove(dragQuestion, section.id, 0);
                  }
                }}
              >
                {section.questions.map((question, index) => (
                  <div key={question.id}>
                    <DropLine active={dragActive && dropIndicator?.sectionId === section.id && dropIndicator.index === index} />
                    <QuestionTile
                      question={question}
                      index={index}
                      total={section.questions.length}
                      sections={localSections}
                      currentSectionId={section.id}
                      dragActive={dragActive}
                      dragging={dragQuestion?.questionId === question.id}
                      disabled={busy}
                      onDragStart={() => setDragQuestion({ questionId: question.id, sectionId: section.id })}
                      onDragEnd={() => {
                        setDragQuestion(null);
                        setDropIndicator(null);
                      }}
                      onHover={(after) => setDropIndicator({ sectionId: section.id, index: index + (after ? 1 : 0) })}
                      onDropTile={(after) => performQuestionMove(dragQuestion as DragQuestion, section.id, index + (after ? 1 : 0))}
                      onMoveUp={() => keyboardMove(section, question, -1)}
                      onMoveDown={() => keyboardMove(section, question, 1)}
                      onMoveToSection={(toSectionId) => moveQuestionToSection(question, section.id, toSectionId)}
                      onSave={(prompt, isActive) => saveQuestion(question, prompt, isActive)}
                      onRemove={() => removeQuestion(question)}
                    />
                  </div>
                ))}
                <DropLine active={dragActive && dropIndicator?.sectionId === section.id && dropIndicator.index === section.questions.length} />
                {section.questions.length === 0 ? (
                  <div
                    className={`rounded-md border border-dashed p-3 text-center text-xs font-semibold ${
                      dragActive ? "border-field-600 bg-field-100 text-field-700" : "border-[#d7c7bc] text-ink-muted"
                    }`}
                  >
                    {dragActive ? `Drop a question here to move it into ${section.code}` : "No questions yet — add one below."}
                  </div>
                ) : null}

                <form
                  className="mt-2 grid gap-3 rounded-md border border-dashed border-[#d7c7bc] p-3 md:grid-cols-[1fr_auto]"
                  onSubmit={async (event) => {
                    event.preventDefault();
                    // Capture before the await: React nulls event.currentTarget afterwards.
                    const formElement = event.currentTarget;
                    setMessage(null);
                    try {
                      await addQuestion(section, new FormData(formElement));
                      // BOTH LINES, AND NEITHER IS THE OTHER'S SPELLING. `reset()` clears the
                      // uncontrolled DOM controls this form may grow; the nonce is the only thing
                      // that clears the dictated box, which React owns. See `questionNonce` above for
                      // the duplicate-question defect that a missing bump produces.
                      formElement.reset();
                      setQuestionNonce((current) => ({ ...current, [section.id]: (current[section.id] ?? 0) + 1 }));
                    } catch (err) {
                      setMessage(err instanceof Error ? err.message : "Unable to add question");
                    }
                  }}
                >
                  {/* `name="prompt"` IS LOAD-BEARING: `addQuestion` reads this box out of
                      `new FormData(formElement)`, so a dropped attribute is an "Add question" button
                      that silently adds nothing. `required` is carried through by the component
                      rather than re-added around it — `DictatedTextArea` puts it on the real
                      `<textarea>`, so the browser's own empty check is exactly what it was.

                      A `helper` AND NOT A `placeholder`, which is what this box carried before: a
                      placeholder vanishes the moment the first dictated phrase lands, and this
                      component has no placeholder prop for exactly that reason. The line is drawn
                      under the label instead, where it survives being spoken into. */}
                  <DictatedTextArea
                    key={questionNonce[section.id] ?? 0}
                    name="prompt"
                    label={`New question in ${section.code}`}
                    helper="Write the question prompt..."
                    required
                    explainWhenUnavailable={false}
                  />
                  <div className="flex items-end">
                    <button className="field-button-secondary">
                      <Plus className="h-4 w-4" aria-hidden />
                      Add question
                    </button>
                  </div>
                </form>
              </div>
            </div>
          ))}
        </div>
      </div>
    </Accordion>
  );
}

/** Purple insertion line shown between question tiles while dragging. */
function DropLine({ active }: { active: boolean }) {
  return <div className={`h-0.5 rounded-full transition-colors ${active ? "bg-purple-700" : "bg-transparent"}`} aria-hidden />;
}

/**
 * A single draggable question tile: grip handle drags it, up/down buttons are the keyboard fallback for
 * within-section reordering, the "Move to" menu changes section, and Edit toggles inline prompt/active
 * editing.
 */
function QuestionTile({
  question,
  index,
  total,
  sections,
  currentSectionId,
  dragActive,
  dragging,
  disabled,
  onDragStart,
  onDragEnd,
  onHover,
  onDropTile,
  onMoveUp,
  onMoveDown,
  onMoveToSection,
  onSave,
  onRemove
}: {
  question: QuestionnaireQuestion;
  index: number;
  total: number;
  sections: QuestionnaireSection[];
  currentSectionId: string;
  dragActive: boolean;
  dragging: boolean;
  disabled: boolean;
  onDragStart: () => void;
  onDragEnd: () => void;
  onHover: (after: boolean) => void;
  onDropTile: (after: boolean) => void;
  onMoveUp: () => void;
  onMoveDown: () => void;
  onMoveToSection: (toSectionId: string) => void;
  onSave: (prompt: string, isActive: boolean) => Promise<boolean>;
  onRemove: () => void;
}) {
  const [editing, setEditing] = useState(false);
  const [prompt, setPrompt] = useState(question.prompt);
  const [isActive, setIsActive] = useState(question.isActive);
  const [savingTile, setSavingTile] = useState(false);

  useEffect(() => {
    setPrompt(question.prompt);
    setIsActive(question.isActive);
  }, [question.id, question.prompt, question.isActive]);

  function pointerAfter(event: React.DragEvent) {
    const rect = event.currentTarget.getBoundingClientRect();
    return event.clientY > rect.top + rect.height / 2;
  }

  const otherSections = sections.filter((section) => section.id !== currentSectionId);

  async function handleSave() {
    setSavingTile(true);
    const ok = await onSave(prompt, isActive);
    setSavingTile(false);
    if (ok) setEditing(false);
  }

  return (
    <div
      className={`flex items-start gap-2 rounded-md border border-line-200 bg-field-50 p-2.5 transition ${dragging ? "opacity-50" : ""}`}
      onDragOver={(event) => {
        if (!dragActive) return;
        event.preventDefault();
        onHover(pointerAfter(event));
      }}
      onDrop={(event) => {
        if (!dragActive) return;
        event.preventDefault();
        onDropTile(pointerAfter(event));
      }}
    >
      <button
        type="button"
        className="mt-0.5 grid h-8 w-8 shrink-0 cursor-grab place-items-center rounded-md border border-line-200 bg-card text-ink-muted focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-field-600 active:cursor-grabbing disabled:opacity-60"
        draggable={!disabled}
        disabled={disabled}
        aria-label={`Drag question ${question.sortOrder}`}
        onDragStart={(event) => {
          onDragStart();
          event.dataTransfer.effectAllowed = "move";
          event.dataTransfer.setData("text/plain", question.id);
        }}
        onDragEnd={onDragEnd}
      >
        <GripVertical className="h-4 w-4" aria-hidden />
      </button>

      <div className="min-w-0 flex-1">
        {editing ? (
          <>
            <TextArea
              aria-label={`Prompt for question ${question.sectionCode}${question.sortOrder}`}
              value={prompt}
              onChange={(event) => setPrompt(event.target.value)}
              rows={2}
            />
            {/*
              The bare button and not `DictatedTextArea`, for the same two reasons as the answer
              boxes: this textarea is controlled by the tile (the "Cancel" button restores
              `question.prompt` from the props, which a self-controlled box could not be told about)
              and it carries no visible label of its own — the tile is a row in a list, not a form.
              `aria-label` above therefore names the box, and the microphone names it again in its own
              accessible label, so a screen-reader user knows which of a dozen tiles is listening.

              The updater form of `setPrompt` for the reason spelled out at the answer boxes: the
              recogniser's handlers are installed once, and a phrase committed a minute into a long
              rewording must append to what is in the box NOW, not to what it held when the microphone
              was pressed.
            */}
            <OnDeviceDictationButton
              fieldLabel={`the prompt for question ${question.sectionCode}${question.sortOrder}`}
              explainWhenUnavailable={false}
              disabled={disabled || savingTile}
              onCommit={(phrase) => setPrompt((current) => appendDictatedPhrase(current, phrase))}
            />
          </>
        ) : (
          <p className="text-sm text-ink-900">
            <span className="mr-1 font-semibold text-ink-500">{question.sortOrder}.</span>
            {question.prompt}
            {!question.isActive ? <span className="ml-2 rounded bg-line-200 px-1.5 py-0.5 text-xs text-ink-500">inactive</span> : null}
          </p>
        )}

        <div className="mt-2 flex flex-wrap items-center gap-2">
          {editing ? (
            <>
              <label className="flex items-center gap-2 text-sm text-ink-muted">
                <input type="checkbox" checked={isActive} onChange={(event) => setIsActive(event.target.checked)} />
                Active
              </label>
              <button type="button" className="field-button-secondary" onClick={handleSave} disabled={savingTile || disabled}>
                <Save className="h-4 w-4" aria-hidden />
                {savingTile ? "Saving..." : "Save"}
              </button>
              <button
                type="button"
                className="text-sm font-semibold text-ink-muted"
                onClick={() => {
                  setPrompt(question.prompt);
                  setIsActive(question.isActive);
                  setEditing(false);
                }}
              >
                Cancel
              </button>
            </>
          ) : (
            <>
              <button type="button" className="field-button-secondary" onClick={() => setEditing(true)} disabled={disabled}>
                <Pencil className="h-4 w-4" aria-hidden />
                Edit
              </button>
              {otherSections.length ? (
                <label className="flex items-center gap-1.5 text-xs text-ink-muted">
                  <span>Move to</span>
                  <span className="w-32">
                    <Select value="" onChange={(event) => onMoveToSection(event.target.value)} aria-label={`Move question ${question.sortOrder} to another section`}>
                      <option value="">Section...</option>
                      {otherSections.map((section) => (
                        <option key={section.id} value={section.id}>
                          {section.code}
                        </option>
                      ))}
                    </Select>
                  </span>
                </label>
              ) : null}
              <button type="button" className="text-sm font-semibold text-red-700" onClick={onRemove} disabled={disabled}>
                Remove
              </button>
            </>
          )}
        </div>
      </div>

      <div className="flex shrink-0 flex-col gap-1">
        <button
          type="button"
          className="grid h-7 w-7 place-items-center rounded-md border border-line-200 bg-card text-ink-muted transition hover:border-purple-300 hover:bg-purple-50 disabled:opacity-40"
          aria-label={`Move question ${question.sortOrder} up`}
          disabled={disabled || index === 0}
          onClick={onMoveUp}
        >
          <ArrowUp className="h-4 w-4" aria-hidden />
        </button>
        <button
          type="button"
          className="grid h-7 w-7 place-items-center rounded-md border border-line-200 bg-card text-ink-muted transition hover:border-purple-300 hover:bg-purple-50 disabled:opacity-40"
          aria-label={`Move question ${question.sortOrder} down`}
          disabled={disabled || index === total - 1}
          onClick={onMoveDown}
        >
          <ArrowDown className="h-4 w-4" aria-hidden />
        </button>
      </div>
    </div>
  );
}
