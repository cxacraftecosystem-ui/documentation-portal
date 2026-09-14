"use client";

/**
 * "Trace this image into line art" — an offline vector tracer over any photograph on any record.
 *
 * WHAT IT IS FOR. A researcher photographs a block, a motif, a tool, a page of a pattern book on a
 * workshop table under one tube light, with a phone. What reaches the archive is a grey rectangle
 * with a drawing somewhere inside it. This panel offers the twenty style presets, the ten subject
 * presets, every control the upstream tracer's dock offers, and the sharpening the upstream engine
 * has always been able to do and its own UI never exposed. All of it is arithmetic on this device.
 *
 * HOW MANY CONTROLS THAT IS, IT DOES NOT SAY — `traceParamTable.PARAM_COUNT` publishes the total and
 * `ADVANCED_COUNT` publishes what the disclosure button reveals, which is the number that button
 * prints. A figure written out in prose here is a second copy that goes stale the first time a slider
 * is added.
 *
 * ────────────────────────────────────────────────────────────────────────────
 * THE FOUR PROPERTIES THIS PANEL EXISTS TO HOLD
 * ────────────────────────────────────────────────────────────────────────────
 *
 * 1. **IT NEVER UPLOADS ANYTHING, AND IT NEVER TOUCHES THE ORIGINAL IMAGE.** This component's whole
 *    output is one `File` handed to {@link TracePanelProps.onAttach}, which the host points at its own
 *    media pipeline — the same door a camera photograph goes in by, so eager pre-upload, multipart,
 *    per-file retry, orphan cleanup and the offline draft store (`lib/media.ts`'s header lists all of
 *    them) already apply to it unchanged. A panel that uploaded its own file would be a second upload
 *    path to keep working offline, in an application whose whole point is working offline.
 *
 *    THE ORIGINAL IS READ AND NEVER WRITTEN. `decodeToPixels` decodes it; nothing here re-encodes it,
 *    renames it, replaces it or hands it to anybody. `docs/MEDIA_PIPELINE.md` — "the original file
 *    *is* the artifact … re-encoding through a canvas destroys full resolution and strips the EXIF" —
 *    is the rule, and the only canvases in this feature produce a DERIVED drawing and two display
 *    plates that never leave the tab.
 *
 *    AND THE FRAME CHOOSER IS INSIDE THAT PROMISE RATHER THAN AN EXCEPTION TO IT. `FramePanel` crops
 *    and sharpens PIXELS IN MEMORY, in a worker, from a COPY of the decode — so the decode itself
 *    survives and a frame can always be widened back out. It produces no file, so there is still
 *    exactly one exit from this component: the single `await onAttach(outcome.file)` below. See
 *    {@link traceSource}, which is the one value the trace, the re-preview and the comparison plates
 *    all read, and `FramePanel.tsx`'s own header for the argument in full.
 *
 * 2. **IT RUNS ON THE DEVICE, IN A WORKER, AND THERE IS NO PATH AROUND THAT.** `Pipeline.run` is
 *    straight loops over typed arrays; a 12 MP trace is seconds of solid CPU, and on the page thread
 *    that is a frozen tab — not a slow one, a frozen one. `lib/trace/traceClient.ts` refuses to offer
 *    a synchronous path at all, and this component never asks for one. A browser that will not start
 *    a module worker gets a sentence and the panel hides itself, because the honest answer to "this
 *    device cannot do it" is not a button that fails.
 *
 * 3. **THE ENGINE IS NOT IN THIS PAGE'S BUNDLE UNTIL SOMEBODY TRACES SOMETHING.** Every heavy import
 *    is inside `traceRuntime.ts` and `imageEditRuntime.ts`, behind `await import()`, and this file
 *    imports no engine value at all — only `traceParamTable.ts`, which is a table of numbers and
 *    strings, and `FramePanel.tsx`, whose own imports are a pure geometry module and that second
 *    dynamic door. `e2e/trace-frame-geometry-unit.spec.ts` counts the static `@/lib/trace` imports
 *    across every module reachable from here and requires the answer to stay at zero. The rule is
 *    `.claude/skills/gsap/SKILL.md` §2, "The dynamic import is not optional", already enforced on this
 *    repository's one 70 KB library and restated in `lib/trace/README.md` §4 with measured figures:
 *    5,070 bytes gzipped on the main thread against 44,253 inside the worker.
 *    **Do not add a top-level import from `@/lib/trace/*` to this file.**
 *
 * 4. **"KEEP THE IMAGE AS IT IS" IS A REAL ANSWER AND COSTS ONE PRESS.** A threshold is a decision to
 *    discard everything on one side of it, and an over-traced drawing has lost something — a faint
 *    construction line, a smudged tone that showed where a curve was being felt out, a note in the
 *    margin. The person who can tell whether that mattered is the researcher with the actual object in
 *    front of them, so the trace is shown before it is attached, declining needs no explanation, and
 *    the panel writes only when a button is pressed.
 *
 *    DECLINING IS ALWAYS SAFE HERE, which is a property of the contract rather than a promise this
 *    file makes: the host already has the image — it handed it to this panel — so nothing of the
 *    researcher's is riding on the press. Closing the panel files nothing and loses nothing but the
 *    trace.
 *
 * 5. **NOTHING ON THIS PANEL IS STORED, AND THE PANEL SAYS SO WHERE IT MATTERS.** The trace lives in
 *    React state and dies on unmount; there is no IndexedDB row, no `localStorage` key and no cache of
 *    a trace anywhere in this repository. Two consequences are stated on screen:
 *
 *    · THE DOWNLOADS ARE MADE FROM MEMORY, ON THE PRESS, and they re-trace at full resolution first
 *      exactly as the attach does — because what is on screen is a preview at a smaller working edge,
 *      and saving that would hand the researcher a coarser drawing than the one they approved with
 *      nothing on screen to say so. Close this panel, change the image or reload the page and the
 *      trace is gone.
 *
 *    · THE COMPARATOR HOLDS BLOB URLS, which is the one kind of memory a component can leak
 *      permanently: an un-revoked object URL pins its whole bitmap for the life of the TAB, and these
 *      are photographs. They are revoked when they are replaced, when the image changes and when this
 *      component unmounts — see `compareUrlsRef` and the dispose effect.
 *
 * 6. **IT OPENS ON THE PRIMARY PATH, AND EVERYTHING ELSE IS ONE PRESS AWAY BEHIND ONE DISCLOSURE.**
 *    Exposing all the settings at once overwhelms: `PARAM_COUNT` controls in five fieldsets, five
 *    download buttons and five paragraphs of format copy would all arrive above the one button most
 *    researchers came to press. What is on screen when the panel opens is the traced result, the
 *    comparison, the style and subject presets, the {@link ESSENTIAL_KEYS} controls and "Add the line
 *    art". The three properties that make that safe rather than merely tidier:
 *
 *    · **NOTHING IS DROPPED, BY CONSTRUCTION.** `ControlGroups` filters on `isEssential` and is called
 *      twice with the two answers, so the two halves are exhaustive and disjoint and a control added
 *      to the table lands in one of them without anybody choosing. `ADVANCED_COUNT` counts the same
 *      predicate, so the number on the button is the number the press reveals.
 *
 *    · **WHAT IS HIDDEN CAN STILL SPEAK.** A non-essential setting moved away from its preset says so
 *      on the toggle ("· 3 changed") and names itself underneath. A control whose effect is invisible
 *      is indistinguishable from one that does nothing.
 *
 *    · **COLLAPSING DESTROYS NOTHING.** The contents are mounted on the first press and thereafter
 *      hidden rather than unmounted — see {@link advancedMounted} — because the shared `Accordion`
 *      primitive and a plain conditional both unmount, and either would throw away state a researcher
 *      was in the middle of setting.
 *
 * ────────────────────────────────────────────────────────────────────────────
 * THE SEAM — WHAT THE HOST OWNS AND WHAT THIS PANEL OWNS
 * ────────────────────────────────────────────────────────────────────────────
 *
 * This component is not wired into any screen: the mounting is done by hand, and the contract is
 * three props wide on purpose.
 *
 *     <TracePanel image={media.url} imageName={media.fileName}
 *                 currentFileName={existingLineArt?.fileName ?? null}
 *                 onAttach={attach} />
 *
 * THE HOST OWNS THE PICKER AND THE RECORD. It chose the image (from its own media field, its own
 * camera capture, its own stored `MediaFile`), and it decides what `onAttach` does with the derived
 * file. This panel owns the decode, the trace, the preview and the export, and it knows nothing at all
 * about records, fields, stages or upload state — which is what lets one panel serve every screen that
 * has an image on it.
 */

import { useCallback, useEffect, useId, useMemo, useRef, useState } from "react";
import {
  AlertTriangle,
  Check,
  ChevronDown,
  ChevronUp,
  Crop,
  Download,
  Image as ImageIcon,
  Loader2,
  Sliders,
  Wand2,
  X
} from "lucide-react";

import { Dropdown } from "@/components/ui/Dropdown";

import {
  ADVANCED_COUNT,
  CHOICES,
  ESSENTIAL_KEYS,
  PARAM_GROUPS,
  SLIDERS,
  TOGGLES,
  applyParamPatch,
  changedAdvancedLabels,
  changedLabels,
  formatValue,
  inactiveReason,
  isEssential,
  overwriteNotice,
  type ChoiceSpec,
  type SliderSpec,
  type ToggleSpec
} from "./traceParamTable";
import {
  DECODE_MAX_EDGE_PX,
  decodeToPixels,
  fetchImageBlob,
  isDecoded,
  nameFromUrl,
  type DecodedPixels
} from "./decodeToPixels";
/*
  THE FRAME CHOOSER, WHICH IS A COMPONENT AND NOT AN ENGINE VALUE.

  `FramePanel` and the `frameGeometry` module behind it import nothing from `@/lib/trace/*` at run
  time: the crop-and-sharpen worker is reached through `imageEditRuntime.loadImageEditor()`, whose one
  `import()` is inside a function body, and the arithmetic the panel draws itself from is plain
  numbers. So this import keeps property 3 intact — `e2e/trace-frame-geometry-unit.spec.ts` counts the
  static `@/lib/trace` imports across every module this file can reach and requires the answer to stay
  at zero.
*/
import { FramePanel, type EditedFrame } from "./FramePanel";
import { UNNAMED_SOURCE_STEM } from "./geometryToSvg";
import { saveBlobToDisk } from "./saveToDevice";
import {
  ATTACHABLE_FORMATS,
  EXPORT_FORMATS,
  PNG_MAX_EDGE_PX,
  RENDER_SUFFIX,
  TRACE_SUFFIX,
  exportPngFile,
  exportSvgFile,
  exportVectorFile,
  isExported,
  paintGeometry,
  type AttachFormatId,
  type ExportFormatId
} from "./traceExport";
import {
  COMPARISON_DIFFERENCE_ALT,
  COMPARISON_DIFFERENCE_BADGE,
  COMPARISON_DIFFERENCE_NOTE,
  COMPARISON_DIFFERENCE_PENDING,
  buildComparisonPlates,
  buildDifferencePlate,
  comparisonStatusFor,
  isComparable,
  isDifference
} from "./comparisonPlates";
import { TraceCompare } from "./TraceCompare";
import { REVEAL_PEEK_HOLD_MS } from "./compareTransform";
import {
  PROGRESS_UNMEASURED_NOTE,
  UNWEIGHTED,
  fractionAt,
  progressWeights,
  traceProgressSentence,
  type ProgressWeights
} from "./traceStages";
import type { SvgInput } from "./geometryToSvg";
import {
  loadTracePresets,
  loadTraceRuntime,
  type SerializedTraceResult,
  type StyleChoice,
  type SubjectChoice,
  type TraceParams,
  type TraceRuntime,
  type Tracer
} from "./traceRuntime";

/**
 * The long edge the live preview is drawn at.
 *
 * The engine's own preview mode runs every stage of the same pipeline at ~720px and returns geometry
 * in the SAME document coordinates as a full run, so it is safe to tune against and safe to draw in
 * the same viewport. This constant is the CANVAS size, not the trace's — a preview larger than the box
 * it is drawn in costs memory to show nobody anything.
 */
const PREVIEW_BOX_PX = 420;

/**
 * How long the panel waits after a control moves before re-tracing.
 *
 * A slider drag is dozens of events. The worker supersedes a running trace when a newer one arrives
 * and `Tracer` settles the older promise itself, so an undebounced drag is correct — but it is also a
 * worker that never finishes anything, and on a handset it is a hot phone. 220 ms is long enough that
 * a drag produces one trace at the end and short enough that a single click feels immediate.
 */
const RETRACE_DEBOUNCE_MS = 220;

/**
 * What a host may answer when it is offered the derived file.
 *
 * `false` MEANS "I HAVE ALREADY TOLD THEM, DO NOT CLAIM THIS WORKED" — a host that refused the file
 * (no record chosen, a failed device write, an upload the queue would not take) has printed its own
 * reason somewhere this panel cannot see, and a green tick underneath claiming the line art had been
 * added would be two answers to one question. Everything else — `void`, `true`, a promise of either —
 * is the ordinary host and is unchanged.
 */
export type AttachAnswer = void | boolean | Promise<void | boolean>;

export interface TracePanelProps {
  /**
   * The image to trace: bytes the host already holds, or a URL it can fetch.
   *
   * ── WHY THREE SHAPES AND NOT ONE ───────────────────────────────────────────────────────────────
   *
   * Because the two moments a researcher wants to trace something are genuinely different. Just after
   * a capture the host has a `File` in hand and a URL for it does not exist yet. Later, looking at a
   * record, what exists is a stored `MediaFile` and its URL — and making the host re-pick from disk to
   * trace a picture it is already showing would be asking for the same photograph twice. A `Blob` is
   * the third because a host that has already fetched the bytes should not have to fetch them again.
   *
   * `null` IS A STATE AND NOT AN ERROR: the host has nothing chosen yet. The panel says where the one
   * picker is rather than drawing a second one — see the note at the empty state.
   *
   * THE IDENTITY OF THIS VALUE IS WHAT THE PANEL WATCHES. Changing it resets the trace, the comparison
   * and every sentence about the old image; passing a NEW `File` object for the same bytes on every
   * render would therefore reset the panel on every render. Hold it in the host's own state.
   */
  image: File | Blob | string | null;
  /**
   * The image's own name, when {@link image} carries none.
   *
   * A `File` has one and it is used. A `Blob` has none and a URL's last path segment is usually a
   * storage key rather than a name anybody wrote, so a host that knows the real name should pass it:
   * every derived file is named from it (`geometryToSvg.derivedFileName`), and a downloads folder
   * where four traces are all called `a1b2c3-line-art.svg` identifies nothing.
   */
  imageName?: string;
  /**
   * What is filed in the slot the line art would go into, if anything.
   *
   * PURELY A SENTENCE, AND THE HOST IS THE ONLY ONE WHO KNOWS IT. This panel hands a file to
   * `onAttach` and has no idea whether that adds a file or replaces one — that is the host's field,
   * the host's cardinality and the host's decision. So the host says, and the panel prints it BEFORE
   * the press, where it can still change somebody's mind. `null` or absent means "nothing there, or
   * the host does not answer this", and the panel says nothing rather than guessing either way.
   */
  currentFileName?: string | null;
  /** Hands the derived file to the host — the same door a camera photograph goes in by. */
  onAttach: (file: File) => AttachAnswer;
  disabled?: boolean;
}

type Phase =
  | { status: "idle" }
  | { status: "loading" }
  | { status: "ready" }
  | { status: "unavailable"; reason: string };

/**
 * Which full-resolution run is in flight, if any.
 *
 * ONE PIECE OF STATE FOR ALL OF THEM, because they are one operation with a different ending: disarm
 * the debounce, re-trace at full resolution, then attach / save the file. Independent busy flags would
 * allow two full-resolution traces at once, and `runTrace` aborts the previous controller — so the
 * loser would report "the trace did not finish" while the winner quietly succeeded, which is the exact
 * class of bug the debounce/attach collision already was.
 *
 * THE DOWNLOAD ARM IS KEYED BY FORMAT ID RATHER THAN BY A HAND-WRITTEN LIST, so a row added to
 * `EXPORT_FORMATS` gets its own spinner with nothing here to remember. A literal union of two ids
 * would spin the wrong button, or none, the day a third format appeared.
 */
type FullRun = "attach" | `download-${ExportFormatId}`;

/**
 * How much of the traced frame the comparator shows before the divider is dragged.
 *
 * ZERO IS THE WHOLE POINT AND IT IS NOT THE COMPONENT'S DEFAULT. `TraceCompare` clips the BEFORE
 * layer by `position`, so 0 means "before fully clipped": the traced result fills the frame and the
 * divider sits hard against the leading edge. Its own default of 50 opens half-and-half, which is the
 * right default for a marketing strip and the wrong one for "did this trace work". Named here so the
 * argument is not a bare literal in the JSX.
 */
const COMPARE_START_POSITION = 0;

/**
 * The four things the comparator can be showing.
 *
 * THE FIRST THREE ARE THE HANDSET'S CHIPS, BY NAME — "Drawing", "Wipe" and "Photograph". A wipe alone
 * makes its two ends reachable only by dragging the seam to an edge or by pressing Home and End: a
 * researcher who simply wants to look at the drawing whole has to know that.
 *
 * THE FOURTH is named identically on both clients — see {@link COMPARISON_DIFFERENCE_NOTE} for the
 * sentence and `differenceRgba` for the arithmetic the two share. It is last because the wipe is what
 * a researcher reaches for and this is what they reach for when the wipe has left them unsure, and
 * because it is the only one that costs a third plate.
 */
type CompareMode = "drawing" | "wipe" | "photograph" | "difference";

/**
 * The chip row, in the handset's order, with the handset's words.
 *
 * THREE LITERALS AND ONE CONSTANT, WHICH IS NOT AN OVERSIGHT. "Difference" is the one label that is
 * also written ON the picture, so the chip and the badge have to be the same word: a researcher who
 * presses one and reads the other has been shown two names for one view. The other three name nothing
 * but themselves.
 */
const COMPARE_MODES: readonly { readonly id: CompareMode; readonly label: string }[] = [
  { id: "drawing", label: "Drawing" },
  { id: "wipe", label: "Wipe" },
  { id: "photograph", label: "Photograph" },
  { id: "difference", label: COMPARISON_DIFFERENCE_BADGE }
];

/**
 * The most a researcher may magnify a plate in the comparator.
 *
 * Six, the same as the handset's cap and for the reason stated there: beyond it a plate capped at
 * `COMPARISON_LONG_EDGE_PX` is showing its own pixels rather than the drawing's. The magnifier is not
 * a convenience — a pencil line on a 1024px plate rendered into a card a few hundred CSS pixels wide
 * is sub-pixel, so the failure this comparator exists to catch is invisible at fit.
 */
const COMPARE_MAX_ZOOM = 6;

/**
 * This card's name, in the ONE spelling every surface uses.
 *
 * A constant rather than the literal typed in four places — the collapsed trigger, the open heading,
 * the foot control and the close button's own sentence — because those four are the same claim and a
 * card whose heading and whose collapse control disagree about its name is a card a reader cannot
 * match up. Sentence case, matching every other card in this application.
 */
const CARD_TITLE = "Trace this image into line art";

export function TracePanel({ image, imageName, currentFileName, onAttach, disabled }: TracePanelProps) {
  const panelId = useId();
  /*
    DERIVED FROM THE ONE `useId`, exactly as `${panelId}-style-hint` and its siblings below are. Two
    `useId()` calls would be two independent ids for one component, which is how a `for`/`id` pair and
    an `aria-controls` end up naming different things after a refactor moves one of them.
  */
  const advancedId = `${panelId}-advanced`;
  const advancedToggleId = `${panelId}-advanced-toggle`;
  const [open, setOpen] = useState(false);
  const [phase, setPhase] = useState<Phase>({ status: "idle" });

  const [runtime, setRuntime] = useState<TraceRuntime | null>(null);
  const [styles, setStyles] = useState<readonly StyleChoice[]>([]);
  const [styleGroups, setStyleGroups] = useState<readonly string[]>([]);
  const [subjects, setSubjects] = useState<readonly SubjectChoice[]>([]);

  /**
   * The bytes being traced and the name every derived file is built from, once both are known.
   *
   * ONE PIECE OF STATE FOR THE PAIR, because they are only ever useful together and a name that is a
   * render behind its bytes is how a drawing gets filed under the previous image's name. The three
   * shapes {@link TracePanelProps.image} may take all collapse to this before anything else runs.
   */
  const [source, setSource] = useState<{ readonly blob: Blob; readonly name: string } | null>(null);
  const [pixels, setPixels] = useState<DecodedPixels | null>(null);
  /**
   * The frame `FramePanel` last committed — a crop, a sharpen, or both — or null for "as decoded".
   *
   * THE PIXELS HERE ARE A DERIVATIVE HELD IN MEMORY AND NOTHING ELSE. The host's image is decoded once
   * into {@link pixels} and never written to; this is a second buffer the worker produced from a COPY
   * of it, so widening a frame back out is always possible and the original is never the thing that
   * was overwritten. See `FramePanel`'s header for why that matters and what it forbids.
   */
  const [edited, setEdited] = useState<EditedFrame | null>(null);

  const [params, setParams] = useState<TraceParams | null>(null);
  /**
   * The parameters as the last preset left them.
   *
   * Kept so a row can be ringed as "you changed this" — a panel that cannot distinguish a value the
   * researcher set from a value a style set cannot honestly claim either one.
   */
  const [presetParams, setPresetParams] = useState<TraceParams | null>(null);
  const [styleId, setStyleId] = useState<string>("");
  /**
   * Which subject adjustment was last applied.
   *
   * Held here rather than left to the control, because the themed dropdown is a `<button>` and has no
   * value of its own. Holding it also makes the panel honest about a thing it is already doing: an
   * applied subject is a decision the researcher can see on the control that made it.
   */
  const [subjectId, setSubjectId] = useState<string>("");

  const [result, setResult] = useState<SerializedTraceResult | null>(null);
  const [tracing, setTracing] = useState(false);
  const [progress, setProgress] = useState<string | null>(null);
  /**
   * How full the bar is, or null when there is nothing to draw one from.
   *
   * NULL FOR A PREVIEW, AND NOT BECAUSE OF A FLAG HERE. `worker/trace.worker.ts` hands `Pipeline.run`
   * a progress callback and hands `Pipeline.runPreview` none at all, so a preview emits no stage
   * events and this simply never leaves null for one. Which is the right answer: a preview is a few
   * hundred milliseconds and a bar that appeared and vanished on every slider release would be noise.
   */
  const [progressAt, setProgressAt] = useState<number | null>(null);
  /**
   * Where each stage starts on the bar, from THIS machine's last completed trace.
   *
   * See `traceStages.ts` for why the engine's own fraction is not good enough: it is a stage count, it
   * never reaches 1, and the two stages that dominate a real trace are worth several of the others put
   * together — so an unweighted bar rushes to a half and then sits there for most of the wait.
   */
  const [weights, setWeights] = useState<ProgressWeights>(UNWEIGHTED);
  /**
   * True from the press of Stop until the run's own `finally` is reached.
   *
   * A SEPARATE FLAG, AND IT IS WHAT MAKES "Stopping…" HONEST. The engine checks its cancellation token
   * BETWEEN stages and nowhere else, so the worst case is the length of the longest single stage —
   * seconds at full resolution. A control that vanished on the press would claim the run had stopped
   * while it was still running; one that promised instant would be wrong.
   */
  const [stopping, setStopping] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [done, setDone] = useState<string | null>(null);
  /**
   * Whether the one "Show more options" disclosure is open.
   *
   * Opening the panel with every setting on screen at once overwhelms: `PARAM_COUNT` controls in five
   * fieldsets, five download buttons and five paragraphs about file formats, above the single button
   * most researchers came to press. So the panel opens on the PRIMARY PATH only and everything else is
   * one press away behind this. Nothing was dropped: `ADVANCED_COUNT` is measured off the table, so a
   * control that is not essential is inside this section by construction rather than by anybody
   * remembering to put it there.
   */
  const [advancedOpen, setAdvancedOpen] = useState(false);
  /**
   * Whether the disclosure's contents have EVER been rendered.
   *
   * ── WHY NEITHER `Accordion` NOR A PLAIN CONDITIONAL IS RIGHT HERE ───────────────────────────────
   *
   * The shared `Accordion` primitive unmounts its children on collapse — a stated contract, not an
   * optimisation — and so does a plain `{open ? … : null}`. Either would destroy the state of every
   * control inside this section each time it closed: a slider mid-drag, a half-typed number. A
   * researcher who closed the section to look at the preview would come back to a reset.
   *
   * And mounting it ALWAYS is not free either: it is `ADVANCED_COUNT` rows of inputs, each with a hint
   * paragraph, on a panel that may be one of several on a record page.
   *
   * So: nothing until the first press, and after that the contents stay mounted and are hidden with
   * `hidden` (Tailwind's preflight makes it `display: none`, which also takes the subtree out of the
   * accessibility tree and out of the tab order). This is also what makes `aria-controls` honest,
   * because the id it names exists from the first press onwards and never afterwards points at
   * nothing.
   */
  const [advancedMounted, setAdvancedMounted] = useState(false);
  /*
    THE CHOOSER HOLDS AN `AttachFormatId`, NOT AN `ExportFormatId`, AND THE TYPE IS THE ENFORCEMENT.
    Three of the five formats are take-away only (`traceExport.ts`'s header: a `.dxf` filed on a record
    arrives typed IMAGE and renders as a broken picture), so "Attach as" draws from
    `ATTACHABLE_FORMATS` and this state cannot hold anything else. `attachTrace` reads it, and its
    `format === "svg"` test is therefore exhaustive over two cases rather than five.
  */
  const [format, setFormat] = useState<AttachFormatId>("svg");
  const [running, setRunning] = useState<FullRun | null>(null);
  /** What the last download saved, for the live region beside the buttons. Never closes the panel. */
  const [saved, setSaved] = useState<string | null>(null);
  /**
   * The two comparison plates, as object URLs, or null when there is nothing to compare.
   *
   * URLs rather than blobs because that is what `TraceCompare` takes, and the URLs are created HERE
   * rather than in `comparisonPlates.ts` for a reason that file states: a URL is a thing that has to be
   * revoked, and only the component knows when it left the screen.
   */
  const [compare, setCompare] = useState<{
    readonly traceUrl: string;
    readonly originalUrl: string;
    /**
     * The same two pictures as blobs, for the difference view to subtract when it is asked for.
     *
     * NO EXTRA MEMORY. An object URL pins its blob until it is revoked, so these two references are
     * already alive for exactly as long as the URLs beside them; holding them is what keeps
     * `buildDifferencePlate` from having to fetch a `blob:` URL back through the network stack to
     * reach bytes this component never let go of.
     */
    readonly traceBlob: Blob;
    readonly originalBlob: Blob;
    readonly width: number;
    readonly height: number;
    readonly reduced: boolean;
  } | null>(null);
  /** Why there is no comparison, when there is a trace but the plates could not be built. */
  const [compareProblem, setCompareProblem] = useState<string | null>(null);
  /** Which of the four views the comparator is showing. Wipe, as on the handset, is the default. */
  const [compareMode, setCompareMode] = useState<CompareMode>("wipe");
  /**
   * Where the researcher left the seam.
   *
   * HELD HERE AND NOT IN `TraceCompare`, which is what makes the three chips possible at all: "Drawing"
   * and "Photograph" write the DISPLAYED position to an end without touching this, so pressing Wipe
   * again comes back to where the researcher was rather than to the middle.
   */
  const [comparePosition, setComparePosition] = useState(COMPARE_START_POSITION);
  /** The third plate, once somebody has asked for it. Object URL, revoked with the other two. */
  const [difference, setDifference] = useState<string | null>(null);
  /** Why there is no third plate, in the sentence written to be read. */
  const [differenceProblem, setDifferenceProblem] = useState<string | null>(null);
  const [differenceBusy, setDifferenceBusy] = useState(false);

  const tracerRef = useRef<Tracer | null>(null);
  /**
   * The same weights as {@link weights}, for the progress callback to read.
   *
   * A REF AS WELL AS STATE, and not instead of it. `runTrace` is a `useCallback` that must not list
   * the weights in its dependency array — doing so would give it a new identity the moment a trace
   * finished, which the debounce effect watches, so every completed trace would arm another one. The
   * callback inside it therefore reads this, which is the value NOW; the bar renders from the state.
   */
  const weightsRef = useRef<ProgressWeights>(UNWEIGHTED);
  const abortRef = useRef<AbortController | null>(null);
  const canvasRef = useRef<HTMLCanvasElement | null>(null);

  /**
   * Whether the engine load has been STARTED, as a ref rather than as a piece of state.
   *
   * A REF BECAUSE THE GUARD MUST NOT BE A DEPENDENCY. The effect below writes `phase` in its own body,
   * so guarding on `phase.status` and listing it in the dependency array makes the effect cancel
   * itself: the synchronous `setPhase({loading})` re-renders, the dependency changes, React runs the
   * cleanup, and the `await` continuation then throws the loaded runtime away — while the re-run hits
   * `phase.status !== "idle"` and returns at once. Nothing ever sets the phase back to "idle" and
   * `loadTraceRuntime` memoises its promise, so the spinner is permanent and reopening the panel does
   * not recover. A ref is read and written without telling React, which is exactly what a "have I
   * started yet" flag needs.
   */
  const startedRef = useRef(false);
  /** Set once, by the dispose effect, when this component really goes away. */
  const goneRef = useRef(false);
  /**
   * The pending preview retrace, so an attach can cancel the timer instead of losing a race to it.
   *
   * The debounce effect arms a timer on every parameter change. Pressing "Add the line art" inside
   * that window starts the full-resolution trace, and the timer then fires a PREVIEW that aborts it —
   * an abort this panel treats (correctly, everywhere else) as the normal consequence of moving a
   * slider, so the attach would report "the trace did not finish" with a finished drawing on screen.
   */
  const retraceTimerRef = useRef<number | null>(null);
  /**
   * Which image this panel is currently working on, as a counter.
   *
   * ── WHY A TOKEN AND NOT A CANCELLED FLAG ON EACH EFFECT ────────────────────────────────────────
   *
   * Because the races that matter here cross a BUTTON PRESS rather than an effect. The host's picker
   * stays live while this panel encodes a PNG, awaits a writer chunk on a slow connection or waits for
   * `onAttach` to resolve — so the image can be replaced in the middle of any of them. Without the
   * token the old image's drawing goes to `onAttach` under the new image's name, minutes later, and a
   * green tick names a file the panel no longer holds. Every await in this file is followed by a
   * comparison against this counter, and each of those comparisons has a comment saying which failure
   * it closes.
   *
   * It is also what keeps a slow decode from overwriting a fast one: resolving image A then image B
   * leaves `source` at B and `pixels` at A's if A's decode finishes last, and the panel then traces
   * one image and names the output after the other.
   */
  const pickRef = useRef(0);
  /**
   * The value of {@link TracePanelProps.image} this panel has already taken up.
   *
   * THE GUARD IS IDENTITY AND THE INITIAL VALUE IS `undefined`, which is deliberately not `null`:
   * `null` is a legal value of the prop (the host has nothing chosen), so starting there would make
   * the panel skip the adopt on a mount with no image and never run the resets that put it in a known
   * state. `undefined` is a value the prop cannot take, so the first run always adopts.
   */
  const adoptedRef = useRef<File | Blob | string | null | undefined>(undefined);
  /**
   * The image whose bytes have already been fetched and decoded.
   *
   * SEPARATE FROM {@link adoptedRef} BECAUSE THE TWO ANSWER DIFFERENT QUESTIONS. Adopting happens the
   * moment the prop changes, open or closed, because the resets it runs are about forgetting the old
   * image. Resolving happens only once the panel is OPEN, because a decode is hundreds of milliseconds
   * of `getImageData` on a 4096px photograph and no record page should pay for it on mount for a panel
   * nobody has opened. One ref could not hold both without the decode running on every open.
   */
  const resolvedRef = useRef<File | Blob | string | null | undefined>(undefined);
  /**
   * True while a full-resolution run — an attach or any download — is in flight, for the debounce
   * effect to read synchronously.
   *
   * A REF AS WELL AS `running`, and not instead of it. `running` is what the buttons render from; this
   * is what the debounce effect reads in its own body, where a piece of state would be the value from
   * the render that scheduled the effect rather than the value now. The downloads share it because
   * they share the hazard: a preview armed 220 ms ago aborts the full-resolution trace a press is
   * awaiting, and the abort reads as "nothing finished" with a finished drawing on screen.
   */
  const fullRunRef = useRef(false);
  /**
   * Every object URL the comparator currently holds.
   *
   * THE LEAK THIS PREVENTS IS THE WORST KIND: `URL.createObjectURL` keeps its blob alive until it is
   * revoked or the document goes away, so a panel that made two 1024px PNGs per trace and forgot them
   * would pin one photograph-sized bitmap per slider drag for as long as the tab lived. The array is
   * revoked and replaced together, in one place, so a plate can never be dropped without its URL.
   */
  const compareUrlsRef = useRef<readonly string[]>([]);
  const triggerRef = useRef<HTMLButtonElement | null>(null);
  const headingRef = useRef<HTMLHeadingElement | null>(null);
  /** Whether the panel was open on the previous render, so focus is returned only on a real close. */
  const wasOpenRef = useRef(false);
  /** The disclosure's own container, so opening it can put focus inside what just appeared. */
  const advancedRef = useRef<HTMLDivElement | null>(null);
  /**
   * True between "a press asked for the frame chooser" and "focus has been moved into it".
   *
   * A REF AND AN EFFECT RATHER THAN A `requestAnimationFrame` INSIDE THE HANDLER. The press both
   * mounts the section and opens it, so the element focus is owed to does not exist yet when the
   * handler runs; an effect keyed on `advancedOpen` runs after the commit that created it, which is
   * the first moment the ref is populated. The flag is what keeps the effect from stealing focus on an
   * open nobody asked to be moved for — the "Show more options" toggle sits directly above its own
   * panel, so a reader is already where they need to be.
   */
  const focusAdvancedRef = useRef(false);

  /* ──────────────────────────────────────────────────────────────────────────
   * Loading the engine — only once the panel is opened
   * ────────────────────────────────────────────────────────────────────────── */

  useEffect(() => {
    if (!open || startedRef.current) return;
    startedRef.current = true;
    /**
     * Whether the engine itself came up, as opposed to only the presets.
     *
     * A LOCAL, NOT `params`. The two failures want opposite answers — a dead engine hides the panel,
     * a missing preset list is a sentence — and the obvious test, "did `params` get set", reads the
     * value from the render that STARTED this effect, which is always null. It would therefore report
     * every preset failure as a device that cannot trace.
     */
    let runtimeUp = false;
    setPhase({ status: "loading" });
    void (async () => {
      try {
        const loaded = await loadTraceRuntime();
        if (goneRef.current) return;
        runtimeUp = true;
        setRuntime(loaded);
        setParams(loaded.defaults);
        setPresetParams(loaded.defaults);
        /*
          THE STYLE PICKER OPENS ON THE STYLE THAT IS ACTUALLY LOADED.

          An "Engine defaults" row would name one thing while the parameters underneath carried
          `styleId: "clean-line"` — the control naming one style, the drawing made by another, and the
          hint below showing a generic fallback instead of that style's own description. Choosing that
          row would find no preset and return having done nothing: a dead menu row that also
          mislabelled the live state.

          `sanitizeTraceParams` forces a non-empty `styleId` (an empty one becomes the default), so
          "nothing selected" is not a state the engine can be in and the picker does not offer it.
        */
        setStyleId(loaded.defaults.styleId);
        setPhase({ status: "ready" });
        // The presets are fetched after the runtime rather than beside it: `engine/subjects.ts` pulls
        // `engine/classify.ts` onto the MAIN thread, and there is no reason to make the researcher
        // wait for it before the engine is up. A failure here costs the two preset lists and nothing
        // else, so it is caught separately and never blocks the panel.
        const presets = await loadTracePresets();
        if (goneRef.current) return;
        setStyles(presets.styles);
        setStyleGroups(presets.styleGroups);
        setSubjects(presets.subjects);
      } catch (error) {
        if (goneRef.current) return;
        if (runtimeUp) {
          // The engine is up and only the presets failed. Say so where it is true rather than
          // disabling a panel that works.
          setNotice("The style and subject presets could not be loaded, so only the individual controls are available.");
          return;
        }
        setPhase({
          status: "unavailable",
          reason:
            error instanceof Error && error.message
              ? error.message
              : "The tracing engine could not be loaded. Check your connection and reload the page."
        });
      }
    })();
    // NO CLEANUP, AND THAT IS THE FIX RATHER THAN AN OVERSIGHT. A cleanup on this effect runs whenever
    // `open` changes, so a researcher who closed the panel while the chunk was still in flight would
    // abandon a load that `startedRef` will never let start again — the same permanent spinner by
    // another route. Closing the panel does not unmount this component, so the state writes above are
    // writes to a live component; the only bail-out that is real is an unmount, and `goneRef` carries
    // that from the dispose effect below.
    //
    // `open` ONLY in the dependency array. Nothing this effect writes may appear here — see
    // `startedRef`.
  }, [open]);

  /**
   * Move focus deliberately, in both directions.
   *
   * OPENING UNMOUNTS THE TRIGGER AND CLOSING UNMOUNTS THE CLOSE BUTTON, so without this the focused
   * element simply disappears and focus falls to `<body>`: a keyboard user loses their place mid-page
   * and a screen-reader user is told nothing happened at all. Neither direction is a modal, so this is
   * not a focus TRAP — Tab must still be free to leave an inline panel. It is the other two thirds of
   * focus management: move focus INTO the thing that just appeared, and hand it back to what opened
   * it.
   *
   * The heading rather than the first control, because the heading carries the panel's name — so a
   * reader hears "Trace this image into line art, heading" instead of a bare "Style, button".
   * `wasOpenRef` is what keeps the close half from firing on first mount, when nothing was ever opened
   * and the page's own focus is not this component's to take.
   */
  useEffect(() => {
    if (open) {
      wasOpenRef.current = true;
      headingRef.current?.focus();
      return;
    }
    if (!wasOpenRef.current) return;
    wasOpenRef.current = false;
    triggerRef.current?.focus();
  }, [open]);

  /**
   * Put focus inside the disclosure when it was a "Choose a frame" press that opened it.
   *
   * "Choose a frame" sits near the top of the panel and the frame chooser is at the top of a section
   * further down: opening it and leaving focus on the button would make a keyboard user tab through
   * every preset, every essential control and the toggle to reach the thing they just asked for. The
   * "Show more options" toggle deliberately does NOT set the flag — it is directly above its own
   * panel, so the next Tab already lands inside it and moving focus would be taking a reader somewhere
   * they were about to arrive.
   */
  useEffect(() => {
    if (!advancedOpen || !focusAdvancedRef.current) return;
    focusAdvancedRef.current = false;
    advancedRef.current?.focus();
  }, [advancedOpen]);

  /**
   * A worker outlives the component that forgot it — and so does an object URL.
   *
   * ONE TEARDOWN SITE FOR BOTH, deliberately. The two leaks are the same shape: a resource the browser
   * holds on this component's behalf and will not reclaim on unmount by itself. A blob URL is the more
   * expensive of the two here, because the blob behind it is a photograph and it is pinned for the
   * life of the TAB rather than of the page view — closing an inline panel is not a navigation.
   */
  useEffect(() => {
    return () => {
      goneRef.current = true;
      if (retraceTimerRef.current !== null) window.clearTimeout(retraceTimerRef.current);
      abortRef.current?.abort();
      tracerRef.current?.dispose();
      tracerRef.current = null;
      for (const url of compareUrlsRef.current) URL.revokeObjectURL(url);
      compareUrlsRef.current = [];
    };
  }, []);

  /* ──────────────────────────────────────────────────────────────────────────
   * Taking up the host's image
   * ────────────────────────────────────────────────────────────────────────── */

  /**
   * Forget everything the previous image left behind, and claim a fresh pick token.
   *
   * ── ONE DOOR, BECAUSE THE RESETS ARE THE HALF THAT GETS FORGOTTEN ──────────────────────────────
   *
   * Every line below is a sentence or a piece of geometry that belongs to the OLD image, and a second
   * entry point that set `source` without them would leave the panel showing one image's answer over
   * another image's picture.
   */
  const adoptImage = useCallback(() => {
    pickRef.current += 1;
    /*
      THE TRACE THAT IS STILL RUNNING BELONGS TO THE OLD IMAGE, and nothing else stops it. Without this
      line every `setResult`, `setProblem` and `setProgress` below is cleared here and then written
      again, seconds later, by a promise that has been in flight since before the change — so the panel
      shows the OLD image's drawing on the canvas, the old image's path and node counts in the `<dl>`,
      and `buildComparisonPlates` stacks the old drawing over the NEW photograph, aligned by nothing.
      The window is not narrow: nothing re-arms a trace until the new image's decode lands.
    */
    abortRef.current?.abort();
    setProblem(null);
    setDone(null);
    // "photo-line-art.svg was saved to this device" IS ABOUT THE OLD IMAGE. It sits under the download
    // buttons and survives a close and a reopen, so a researcher who saved a copy of one trace and
    // then changed the image would be told the new one had already been saved — under a file name that
    // is now nobody's.
    setSaved(null);
    setResult(null);
    setSource(null);
    setPixels(null);
    /*
      A FRAME CHOSEN ON ONE SHEET IS MEANINGLESS ON THE NEXT, and it has to be cleared HERE rather than
      left to `FramePanel`: setting `pixels` to null unmounts that panel, so its own reset effect never
      runs, and the previous image's crop would survive as the region this one is traced from. Nothing
      on screen would distinguish the two — the same failure `pickRef` exists to stop, arriving by a
      different route.
    */
    setEdited(null);
    // A NEW IMAGE HAS TO BE FETCHED AND DECODED AGAIN, whatever was resolved for the last one. Cleared
    // HERE rather than in the resolve effect so that the two refs can never disagree about which image
    // the panel is holding.
    resolvedRef.current = undefined;
    return pickRef.current;
  }, []);

  /**
   * Resolve the host's image to bytes and decode it — once per image, and only once the panel is open.
   *
   * ── WHY THE DECODE WAITS FOR THE PANEL TO OPEN ─────────────────────────────────────────────────
   *
   * `decodeToPixels` resizes and calls `getImageData`, which is hundreds of milliseconds for a 4096px
   * photograph on a handset, and a URL source costs a network fetch on top. A record page that mounts
   * this panel beside every image would pay all of it on load, for a feature most visits never touch.
   * The adopt above still runs closed, because forgetting the old image is not optional.
   *
   * ── AND WHY THE FETCH FAILURE IS PRINTED HERE AND THE DECODE FAILURE IS TOO ────────────────────
   *
   * Both are sentences `decodeToPixels.ts` wrote to be read, and this panel is the only surface that
   * knows a trace was being attempted. The host printed nothing: from its side it handed over a URL
   * and heard no more.
   */
  useEffect(() => {
    if (adoptedRef.current !== image) {
      adoptedRef.current = image;
      adoptImage();
    }
    if (!open || image === null) return;
    if (resolvedRef.current === image) return;
    resolvedRef.current = image;
    const pick = pickRef.current;
    void (async () => {
      let blob: Blob;
      let name: string;
      if (typeof image === "string") {
        const fetched = await fetchImageBlob(image);
        if (pick !== pickRef.current || goneRef.current) return;
        if ("reason" in fetched) {
          setProblem(fetched.reason);
          return;
        }
        blob = fetched;
        name = imageName ?? nameFromUrl(image);
      } else {
        blob = image;
        // `instanceof File` rather than `"name" in image`, because a `Blob` with a stray `name`
        // property is a thing a host can hand over and a `File` is the only shape whose `name` means
        // what this panel needs it to mean.
        name = imageName ?? (image instanceof File ? image.name : UNNAMED_SOURCE_STEM);
      }
      setSource({ blob, name });
      const outcome = await decodeToPixels(blob);
      if (pick !== pickRef.current || goneRef.current) return;
      if (!isDecoded(outcome)) {
        setPixels(null);
        setProblem(outcome.reason);
        return;
      }
      setPixels(outcome);
    })();
    // `imageName` is listed because it is read inside, and it cannot re-run the body on its own: the
    // guard above is keyed on `image` alone, so a host that renames an image it has already handed
    // over changes the NEXT derived file's name and does not re-fetch anything.
  }, [adoptImage, image, imageName, open]);

  /* ──────────────────────────────────────────────────────────────────────────
   * Tracing
   * ────────────────────────────────────────────────────────────────────────── */

  /**
   * The pixels the trace actually runs on: the frame `FramePanel` committed, or the whole decode.
   *
   * ONE PLACE, SO NOTHING CAN TRACE A DIFFERENT FRAME FROM THE ONE THE PANEL SAYS IS APPLIED. Three
   * consumers read it — `runTrace`, the debounce effect that re-previews, and the comparison plates —
   * and the third is the one that would break silently: `buildComparisonPlates` stacks the traced
   * drawing over the photograph, so feeding it the whole image while the trace ran on a crop would put
   * two different frames in one comparator, aligned by nothing.
   *
   * `sourceWidth`/`sourceHeight` are carried through UNCHANGED, and that is correct rather than lazy:
   * they mean "the file's own pixel size, before any capping" (`decodeToPixels.DecodedPixels`), which a
   * crop does not change. `width`/`height` are the frame.
   *
   * A memo, not an expression at the call site: the debounce effect watches this object's identity, and
   * rebuilt every render it would re-arm the retrace timer on every keystroke anywhere in the panel.
   */
  const traceSource = useMemo<DecodedPixels | null>(() => {
    if (pixels === null) return null;
    if (edited === null) return pixels;
    return {
      data: edited.data,
      width: edited.width,
      height: edited.height,
      sourceWidth: pixels.sourceWidth,
      sourceHeight: pixels.sourceHeight,
      decodeMs: pixels.decodeMs
    };
  }, [edited, pixels]);

  /**
   * Run one trace and hand the answer back.
   *
   * IT RETURNS THE RESULT AS WELL AS STORING IT, and that is not redundancy. `attachTrace` awaits a
   * full-resolution re-trace and then needs its answer immediately — but `setResult` schedules a
   * render, so reading `result` (or a ref an effect updates) straight after the await reads the
   * PREVIEW, and the file attached would be coarser than the one the researcher approved with nothing
   * on screen showing the difference. The return value is the only synchronously correct answer.
   *
   * `traceSource` AND NOT `pixels`, so the drawing on screen is always made from the frame the panel
   * says is applied — see that memo for the three consumers that have to agree.
   */
  const runTrace = useCallback(
    async (preview: boolean): Promise<SerializedTraceResult | null> => {
      if (runtime === null || traceSource === null || params === null) return null;
      /*
        THE SAME TOKEN THE DECODE CARRIES, ON THE OTHER LONG AWAIT. `adoptImage` aborts this run when
        the image is replaced, and an abort is the fast path — but abort is a request, not a guarantee:
        a worker that has already posted its answer resolves anyway, and the two racing inside one tick
        is precisely the case a signal cannot decide. The token can. Read here and compared past every
        await below, it is what makes "this answer is about an image that is no longer on screen" a
        thing this function can KNOW rather than hope, and it costs one integer.
      */
      const pick = pickRef.current;
      abortRef.current?.abort();
      const controller = new AbortController();
      abortRef.current = controller;

      if (tracerRef.current === null) tracerRef.current = new runtime.Tracer();
      const tracer = tracerRef.current;

      setTracing(true);
      setProblem(null);
      setProgress(null);
      setProgressAt(null);
      setStopping(false);
      try {
        const answer = await tracer.trace({
          // A fresh clone every time. The buffer is TRANSFERRED, so the caller's typed array is
          // detached once it has been posted — the upstream learned this the hard way and recorded the
          // symptom: transferring the original made "the second trace produces a blank image", which
          // surfaces as a rendering bug rather than as an error.
          image: runtime.transferableFrom(traceSource),
          params,
          preview,
          signal: controller.signal,
          onProgress: (p) => {
            // THE ENGINE'S OWN LABEL, NEVER THIS FILE'S. `traceProgressSentence` adds the stage number
            // around it and nothing else — re-typing engine wording in a client is how two clients end
            // up describing one operation differently.
            setProgress(traceProgressSentence(p.stageId, p.label));
            setProgressAt(fractionAt(weightsRef.current, p.stageId, p.fraction));
          }
        });
        /*
          NOTHING OF THIS ANSWER IS PUBLISHED UNDER AN IMAGE IT WAS NOT TRACED FROM. Returning rather
          than storing is deliberate and is what the two full-run callers read: the drawing is real, it
          simply describes an image the host has replaced, and `result` is the one piece of state on
          this panel that a stale write makes actively misleading rather than merely old — the canvas
          paints it, the `<dl>` counts it, the comparator stacks it over whatever photograph is on
          screen now, and "Add the line art" stays live over the pair.
        */
        if (pick !== pickRef.current || goneRef.current) return null;
        setResult(answer);
        // THE BAR LEARNS FROM THE RUN THAT JUST FINISHED. `stages` is empty for a preview, and
        // `progressWeights` answers UNWEIGHTED for that rather than dividing by zero — so a panel that
        // has only ever previewed keeps the engine's even spacing and keeps saying so.
        const learned = progressWeights(answer.stages);
        weightsRef.current = learned;
        setWeights(learned);
        return answer;
      } catch (error) {
        // A superseded or aborted trace is the normal consequence of moving a slider, not a failure,
        // and reporting it would fill the panel with sentences about work the researcher replaced.
        if (runtime.isCancelled(error)) return null;
        if (runtime.isUnavailable(error)) {
          setPhase({ status: "unavailable", reason: (error as Error).message });
          return null;
        }
        /*
          AFTER `isUnavailable` AND BEFORE `setProblem`, WHICH IS THE ONLY ORDER THAT IS RIGHT. "This
          device cannot trace at all" is a fact about the device and stands whichever image provoked
          it — losing it because the host changed the image would leave the panel offering controls
          that cannot work. "That image could not be traced" is a fact about ONE image, and printed
          after a different one has been taken up it is a red box accusing a picture that was never
          tried.
        */
        if (pick !== pickRef.current || goneRef.current) return null;
        setProblem(
          error instanceof Error && error.message
            ? error.message
            : "That image could not be traced. Try another, or a lower trace resolution."
        );
        return null;
      } finally {
        if (abortRef.current === controller) {
          setTracing(false);
          setProgress(null);
          setProgressAt(null);
          // CLEARED HERE AND NOWHERE ELSE, which is the whole of what makes "Stopping…" mean anything:
          // this line runs when the run really has unwound, so the word is on screen for exactly as
          // long as stopping takes rather than for a guessed interval.
          setStopping(false);
        }
      }
    },
    [runtime, traceSource, params]
  );

  /**
   * Re-preview whenever the pixels or the parameters change. Debounced — see RETRACE_DEBOUNCE_MS.
   *
   * THE TIMER IS KEPT IN A REF AS WELL AS IN THE CLEANUP, because an attach has to be able to cancel
   * it from outside this effect. A parameter changed within 220 ms of pressing "Add the line art"
   * leaves a preview armed; the preview then aborts the full-resolution trace the attach is awaiting,
   * that abort is (rightly) treated as the ordinary consequence of moving a slider, and the attach
   * would report "the trace did not finish" with a finished drawing on screen. `beginFullRun` clears
   * this timer before any of the presses starts, and `fullRunRef` keeps the effect from arming a new
   * one behind it.
   */
  useEffect(() => {
    if (runtime === null || traceSource === null || params === null) return;
    if (fullRunRef.current) return;
    const timer = window.setTimeout(() => {
      retraceTimerRef.current = null;
      void runTrace(true);
    }, RETRACE_DEBOUNCE_MS);
    retraceTimerRef.current = timer;
    return () => {
      window.clearTimeout(timer);
      if (retraceTimerRef.current === timer) retraceTimerRef.current = null;
    };
    // `traceSource` RATHER THAN `pixels`, so committing a frame in `FramePanel` re-traces exactly as
    // moving a slider does — same debounce, same supersede-the-older-run behaviour. A frame is another
    // input to the trace and nothing more.
  }, [runtime, traceSource, params, runTrace]);

  /* ──────────────────────────────────────────────────────────────────────────
   * Drawing the answer
   * ────────────────────────────────────────────────────────────────────────── */

  const svgInput: SvgInput | null = useMemo(() => {
    if (result === null) return null;
    return {
      geometry: result.geometry,
      width: result.width,
      height: result.height,
      background: result.background
    };
  }, [result]);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (canvas === null || svgInput === null) return;
    const scale = Math.min(1, PREVIEW_BOX_PX / Math.max(svgInput.width, svgInput.height));
    canvas.width = Math.max(1, Math.round(svgInput.width * scale));
    canvas.height = Math.max(1, Math.round(svgInput.height * scale));
    const context = canvas.getContext("2d");
    if (context === null) return;
    context.clearRect(0, 0, canvas.width, canvas.height);
    // The SAME painter the PNG export uses. A preview drawn by different code from the file that gets
    // attached is a preview that can lie — see `paintGeometry`'s header.
    paintGeometry(context, svgInput, scale);
  }, [svgInput]);

  /**
   * Install a fresh pair of comparison URLs and revoke whatever the last pair was.
   *
   * REVOKE-THEN-REPLACE IN ONE FUNCTION, so the two can never drift apart. The previous URLs are dead
   * the moment this returns, which is safe because the state write that renders them happens in the
   * same call: React commits the new `src` values, and no `<img>` ever points at a revoked URL. Doing
   * it in two places is how one of the pair gets forgotten.
   */
  const installCompareUrls = useCallback((urls: readonly string[]) => {
    for (const url of compareUrlsRef.current) URL.revokeObjectURL(url);
    compareUrlsRef.current = urls;
  }, []);

  /**
   * Add one URL to the set the comparator holds, WITHOUT revoking what is already there.
   *
   * A SEPARATE FUNCTION AND NOT `installCompareUrls([...previous, url])`, which is the obvious spelling
   * and revokes the two plates it was meant to preserve: that helper revokes the whole previous array
   * by design, because its job is replacing a pair. This one is for the third plate, which JOINS a pair
   * that has to stay alive. Getting the two confused blanks the comparator the moment the fourth chip
   * is pressed.
   */
  const addCompareUrl = useCallback((url: string) => {
    compareUrlsRef.current = [...compareUrlsRef.current, url];
  }, []);

  /**
   * Forget the difference plate, because the two it was subtracted from are gone.
   *
   * CALLED FROM EVERY PATH THAT INSTALLS NEW URLS, and it has to be: `installCompareUrls` revokes the
   * whole previous array, the difference URL is in it, and a `difference` state that survived would be
   * an `<img src>` pointing at a revoked blob — a broken picture where a researcher expects the answer
   * to "did the trace lose that line". The refusal sentence goes with it: a device that could not make
   * room for one plate may well manage the next one, and a stale refusal beside a fresh trace is a
   * sentence about work that is no longer being described.
   */
  const forgetDifference = useCallback(() => {
    setDifference(null);
    setDifferenceProblem(null);
    setDifferenceBusy(false);
  }, []);

  /**
   * Build the two pictures the before/after comparator shows, whenever the drawing or the image
   * changes.
   *
   * WHY IT IS AN EFFECT AND NOT A BUTTON. The comparator is the answer to "is this trace any good",
   * which is the question every slider on this panel is asked in service of — so it has to be looking
   * at the trace that is on screen now, not at whichever one the researcher last pressed a button for.
   * A stale comparison is worse than none: it says the drawing is fine while the drawing on screen is
   * not.
   *
   * WHAT IT COSTS, MEASURED IN THE ONLY UNIT THAT MATTERS HERE. One box-filter downscale of the
   * decoded image plus two PNG encodes at `COMPARISON_LONG_EDGE_PX`, per SETTLED trace — `svgInput`
   * only changes when a trace resolves, and a slider drag produces one trace at the end because of
   * `RETRACE_DEBOUNCE_MS`. It is not per frame and not per pointer move.
   *
   * AND THAT DOWNSCALE READS THE WHOLE DECODE, up to 4096px on its long edge — 16.7 million pixels,
   * whatever size the plate it produces is. Which is why it is `resampleRgbaInBands` and not the
   * synchronous one: the work is identical and interruptible instead of one long task on the page
   * thread. A settled trace per slider drag is exactly often enough for a frozen tab to be noticed.
   *
   * THE CANCEL TOKEN IS NOT OPTIONAL. Two traces can settle in quick succession (a preview, then the
   * attach's full-resolution run), and without the token the first build's URLs are installed after
   * the second's — so the comparator shows the older drawing, and the newer pair's URLs are the ones
   * that get revoked. `installCompareUrls` is called only past the guard for exactly that reason.
   */
  useEffect(() => {
    if (svgInput === null || traceSource === null) {
      installCompareUrls([]);
      setCompare(null);
      setCompareProblem(null);
      forgetDifference();
      return;
    }
    let cancelled = false;
    void (async () => {
      // `traceSource`, NOT `pixels`: the before-layer has to be the frame the trace actually ran on,
      // or a cropped trace is stacked over the uncropped photograph and the two layers line up nowhere.
      const outcome = await buildComparisonPlates(traceSource, svgInput, {
        // The downscale is done in bands with the page thread given a turn between them, and this is
        // what stops a superseded build partway through instead of paying for a plane nobody will see.
        // The guard below is still what decides whether an answer is used; this only saves the work.
        shouldStop: () => cancelled || goneRef.current
      });
      if (cancelled || goneRef.current) return;
      if (!isComparable(outcome)) {
        installCompareUrls([]);
        setCompare(null);
        setCompareProblem(outcome.reason);
        forgetDifference();
        return;
      }
      const traceUrl = URL.createObjectURL(outcome.trace);
      const originalUrl = URL.createObjectURL(outcome.original);
      installCompareUrls([traceUrl, originalUrl]);
      setCompareProblem(null);
      forgetDifference();
      setCompare({
        traceUrl,
        originalUrl,
        traceBlob: outcome.trace,
        originalBlob: outcome.original,
        width: outcome.width,
        height: outcome.height,
        reduced: outcome.reduced
      });
    })();
    return () => {
      cancelled = true;
    };
  }, [svgInput, traceSource, installCompareUrls, forgetDifference]);

  /**
   * Build the third plate, once, on the first press of the fourth chip.
   *
   * ON THE PRESS AND NOT WITH THE OTHER TWO. It is the only view of the four that costs a plate of its
   * own, most researchers never open it, and `buildComparisonPlates` already runs per SETTLED trace —
   * which is once per slider drag, on the page thread. Adding a third encode there would be paid by
   * everybody for a picture almost nobody asked for.
   *
   * THE CHIP STAYS PRESSABLE AFTER A REFUSAL. Pressing it is how a researcher reads the sentence saying
   * why there is nothing there; a chip that went dead with no explanation is the state this whole panel
   * is written against. So a second press with a refusal standing simply tries again — a browser that
   * would not give the page a surface a moment ago may well now.
   */
  const showDifference = useCallback(() => {
    setCompareMode("difference");
    if (compare === null || difference !== null || differenceBusy) return;
    setDifferenceProblem(null);
    setDifferenceBusy(true);
    const plates = compare;
    void (async () => {
      const outcome = await buildDifferencePlate(
        plates.originalBlob,
        plates.traceBlob,
        plates.width,
        plates.height
      );
      if (goneRef.current) return;
      // THE PLATES MAY HAVE MOVED UNDER THIS. A newer trace settling while the subtraction ran
      // installed a new pair and revoked the old URLs, so a difference built from the old pair would be
      // a picture of a drawing that is no longer on screen — the stale-comparison failure the build
      // effect's own cancel token exists to prevent, one press further along.
      if (compareUrlsRef.current[0] !== plates.traceUrl) return;
      setDifferenceBusy(false);
      if (!isDifference(outcome)) {
        setDifferenceProblem(outcome.reason);
        return;
      }
      const url = URL.createObjectURL(outcome.plate);
      addCompareUrl(url);
      setDifference(url);
    })();
  }, [addCompareUrl, compare, difference, differenceBusy]);

  /* ──────────────────────────────────────────────────────────────────────────
   * Presets and controls
   * ────────────────────────────────────────────────────────────────────────── */

  const setParamsFrom = useCallback(
    (next: TraceParams, sourceLabel: string | null, base: TraceParams | null) => {
      if (params !== null && sourceLabel !== null) setNotice(overwriteNotice(sourceLabel, params, next));
      setParams(next);
      if (base !== null) setPresetParams(base);
    },
    [params]
  );

  const pickStyle = useCallback(
    (id: string) => {
      const preset = styles.find((s) => s.id === id);
      if (!preset) return;
      setStyleId(preset.id);
      // A style REPLACES the settings — it is a complete tree, not a nudge — so it becomes both the
      // live parameters and the baseline the "you changed this" rings are measured against.
      setParamsFrom(preset.params, `The “${preset.name}” style`, preset.params);
    },
    [styles, setParamsFrom]
  );

  const applySubject = useCallback(
    (id: string) => {
      const preset = subjects.find((s) => s.id === id);
      if (!preset || params === null) return;
      // A subject leaves the style alone by design and is idempotent, so the baseline is untouched:
      // it says something about the material in front of the camera, not about the drawing wanted.
      setParamsFrom(preset.adjust(params), `The “${preset.name}” adjustment`, null);
    },
    [subjects, params, setParamsFrom]
  );

  const patchParams = useCallback(
    (patch: Parameters<typeof applyParamPatch>[1]) => {
      if (runtime === null || params === null) return;
      setNotice(null);
      setParams(applyParamPatch(params, patch, runtime.sanitize));
    },
    [runtime, params]
  );

  const modifiedLabels = useMemo(
    () => (params && presetParams ? changedLabels(presetParams, params) : []),
    [params, presetParams]
  );
  const hiddenModified = useMemo(
    () => (params && presetParams ? changedAdvancedLabels(presetParams, params) : []),
    [params, presetParams]
  );
  const modifiedSet = useMemo(() => new Set(modifiedLabels), [modifiedLabels]);

  /**
   * The style the engine would have picked for this image, when it is not the one already chosen.
   *
   * NULL IS THE COMMON ANSWER and every branch that produces it is a real state rather than a guard:
   * no trace yet, a preview (which does not classify, so `profile` is null), a suggestion naming a
   * preset this build's list does not have, and — the one that matters — a suggestion the researcher is
   * already using, where a row saying "try the style you are using" is noise.
   */
  const suggestedStyle = useMemo(() => {
    const id = result?.profile?.suggestion ?? "";
    if (id.length === 0 || id === styleId) return null;
    return styles.find((style) => style.id === id) ?? null;
  }, [result, styleId, styles]);

  /**
   * Why the comparator is not showing a comparison, or the empty string when it is.
   *
   * THE JUDGEMENT LIVES IN `comparisonPlates.ts` and not in this ternary chain, because there is no
   * React renderer in this repository and anything written inside a component cannot be exercised by a
   * test. Six branches of copy that a researcher reads at the moment they are unsure whether the tool
   * is working is exactly the thing worth pinning; `e2e/trace-compare-unit.spec.ts` does.
   */
  const comparisonStatus = comparisonStatusFor({
    haveCompare: compare !== null,
    compareProblem,
    tracing,
    progress,
    traceProblem: problem,
    haveResult: result !== null
  });

  /* ──────────────────────────────────────────────────────────────────────────
   * Attaching
   * ────────────────────────────────────────────────────────────────────────── */

  /**
   * Claim the panel for a full-resolution run, whichever of them it is.
   *
   * DISARMING THE PENDING PREVIEW IS THE FIRST THING IT DOES, and it is the whole reason this is a
   * function rather than four lines repeated. See the debounce effect: a preview that fires mid-run
   * aborts the full-resolution trace the press is awaiting, and the abort reads as "nothing finished"
   * while a finished drawing is on screen. The bug is found once on the attach; the downloads would
   * reproduce it exactly.
   */
  function beginFullRun(kind: FullRun) {
    if (retraceTimerRef.current !== null) {
      window.clearTimeout(retraceTimerRef.current);
      retraceTimerRef.current = null;
    }
    fullRunRef.current = true;
    setRunning(kind);
    setProblem(null);
  }

  function endFullRun() {
    fullRunRef.current = false;
    setRunning(null);
  }

  /**
   * Open or close the one disclosure.
   *
   * `setAdvancedMounted(true)` on every press rather than only the first: it is already true after
   * that, React skips a write of the same value, and a guard here would be a second place for the two
   * flags to disagree. See {@link advancedMounted} for why the contents are never unmounted again.
   */
  function toggleAdvanced() {
    setAdvancedMounted(true);
    setAdvancedOpen((value) => !value);
  }

  /**
   * The "Choose a frame" press — the handset's own control, and the direct route to the frame chooser.
   *
   * It drives the SAME disclosure the toggle does rather than a second one, because two disclosures
   * over one region is two states that can disagree about whether it is open. What it adds is the
   * focus move: see the effect above.
   */
  function chooseFrame() {
    if (advancedOpen) {
      setAdvancedOpen(false);
      return;
    }
    focusAdvancedRef.current = true;
    setAdvancedMounted(true);
    setAdvancedOpen(true);
  }

  /**
   * Ask the running trace to stop.
   *
   * REAL WORK-STOPPING, NOT HIDING. The abort posts a cancel to the worker, whose `CancellationToken`
   * unwinds `Pipeline.run` between stages — the same mechanism a superseded preview uses, which is why
   * this needed a BUTTON and not a mechanism. Without one, the only way to abandon a full-resolution
   * trace is to move a slider and hope, and nothing on screen says so.
   *
   * `runTrace`'s `catch` already treats a cancellation as the ordinary consequence of changing
   * something rather than as a failure, so nothing red appears and whatever drawing is already on
   * screen stays exactly where it was.
   *
   * IT ALSO ENDS THE FULL RUN, not just the trace. A press is awaiting `runTrace` and its own `finally`
   * will run — but the buttons render from `running`, and leaving every one of them disabled until the
   * last stage finishes unwinding is a panel that looks stopped without being usable. The pending
   * preview timer goes with it, because a re-trace firing 220 ms after a researcher pressed Stop is the
   * panel disagreeing with the button.
   */
  function stopTrace() {
    if (!tracing || stopping) return;
    setStopping(true);
    abortRef.current?.abort();
    if (retraceTimerRef.current !== null) {
      window.clearTimeout(retraceTimerRef.current);
      retraceTimerRef.current = null;
    }
    endFullRun();
  }

  /**
   * The provenance sentence written into a derived file.
   *
   * SHARED BY THE ATTACH AND BY THE DOWNLOADED VECTORS, because the downloaded copy is the one most
   * likely to be mailed on, printed, or opened in Illustrator by somebody who never saw this panel —
   * so it is the copy that most needs to be able to say what made it and from what. `buildSvg`'s
   * header states the limit this stays inside: nothing identifying, no researcher, no account, no
   * record.
   */
  function provenanceFor(latest: SerializedTraceResult, sourceName: string): string {
    return (
      `Traced on the device from ${sourceName} by Field Repository. ` +
      `${latest.shapeCount} paths, ${latest.nodeCount} nodes.` +
      /*
        THE FRAME IS PART OF THE PROVENANCE, AND IT IS THE PART A REVIEWER CANNOT INFER.

        A crop is destructive — everything outside it is absent from the drawing — so somebody holding
        the SVG and the photograph side by side and finding that they do not match needs to be able to
        tell whether that was a decision or a fault. The sentence is written by
        `lib/trace/imageEdit.describeEdit`, inside the worker that read the pixels, and carried up
        through `FramePanel` — not re-derived here — so a change to what the frame does changes what
        the file says about itself. `TraceCrop.traceCropNote` writes the same sentence on the handset,
        character for character, because the two clients' drawings land in one archive.

        THE PNG CARRIES NONE OF THIS AND CANNOT. `exportPngFile` takes no provenance argument — a PNG
        has no comment channel this code writes — so a cropped trace attached as a PNG records its
        frame nowhere but on this screen. Stated in the copy under the format buttons, and again under
        the download buttons, rather than quietly tolerated.
      */
      (edited !== null && edited.note.length > 0 ? ` ${edited.note}` : "")
    );
  }

  /** The traced document, in the shape both exporters and the painter take. */
  function inputFrom(latest: SerializedTraceResult): SvgInput {
    return {
      geometry: latest.geometry,
      width: latest.width,
      height: latest.height,
      background: latest.background
    };
  }

  async function attachTrace() {
    if (svgInput === null || source === null || runtime === null || params === null) return;
    /*
      THE IMAGE THIS PRESS IS ABOUT, TAKEN BEFORE THE LONG RUN. `source`, `svgInput` and `params` are
      closed over from the render that drew the button, so everything below this line describes one
      image no matter what happens on screen while the full-resolution trace runs — and the host's
      picker is NOT disabled while it runs, because the host learns nothing until `onAttach` is called,
      which is after. Without the guard below, replacing the image mid-run files the OLD image's line
      art, minutes later, and leaves a green tick naming a file the panel no longer holds.
    */
    const pick = pickRef.current;
    beginFullRun("attach");
    try {
      // FULL RESOLUTION, ONCE, ON THE BUTTON — never on a drag. Everything on screen until now was a
      // preview at a smaller working edge, and `SerializedTraceResult.workingWidth` is how the panel
      // knows: attaching the preview would file a drawing coarser than the one being approved.
      const latest = await runTrace(false);
      /*
        SILENTLY, AND BEFORE THE "did not finish" SENTENCE BELOW, WHICH WOULD OTHERWISE BE THE WRONG
        ANSWER TWICE OVER: the trace did not fail, it was abandoned — because the host replaced the
        image — and `adoptImage` has already emptied the panel of everything the sentence could refer
        to. A red box arriving on the NEW image's card about the OLD one is the exact failure the reset
        was written to end, arriving from the far side of an await.
      */
      if (pick !== pickRef.current || goneRef.current) return;
      if (latest === null) {
        setProblem("The trace did not finish, so there is nothing to attach yet.");
        return;
      }
      const input = inputFrom(latest);
      const note = provenanceFor(latest, source.name);
      const outcome =
        format === "svg"
          ? exportSvgFile(input, source.name, note)
          : await exportPngFile(input, source.name);
      /*
        THE SAME TOKEN AGAIN, BECAUSE THE GUARD ABOVE IS ONE AWAIT TOO EARLY TO COVER THIS.

        `exportPngFile` paints the full-resolution geometry into a canvas and then awaits
        `canvasToBlob` — a real PNG encode at `PNG_MAX_EDGE_PX`, seconds on a handset for a dense
        drawing — and the host's picker is live for every one of them: this panel's own `running` greys
        out this panel's own buttons and nothing else, and the host learns nothing until `onAttach` is
        called, which is below. So "Attach as PNG", then replace the image while it encodes, and
        without this line the OLD image's plate goes to `onAttach` under the new image.

        HERE RATHER THAN AFTER `isExported`, so a refusal sentence goes with it. "This browser would
        not give the page a drawing surface" printed over an image that was never tried is the same red
        box accusing the wrong picture that `runTrace`'s catch guards against.

        NOTHING HAS BEEN WRITTEN YET AT THIS LINE, which is what makes returning the honest answer: the
        export is in memory, no host has been called, and `adoptImage` has already emptied the panel of
        everything a sentence could refer to.
      */
      if (pick !== pickRef.current || goneRef.current) return;
      if (!isExported(outcome)) {
        setProblem(outcome.reason);
        return;
      }
      /*
        THE HOST'S ANSWER DECIDES WHETHER A SUCCESS SENTENCE IS PRINTED AT ALL.

        Calling `onAttach` and then setting the green sentence unconditionally means a host that
        refused the file — no record chosen, a failed device write, an upload the queue would not take
        — prints its own red refusal above a tick claiming the line art had been added. See
        {@link AttachAnswer}: `false` means "I have already told them, do not claim this worked", and
        `undefined` is every other host, unchanged.

        THE PANEL STAYS OPEN ON A REFUSAL, with the traced geometry and the image intact, because
        re-tracing a plate to recover from somebody else's refusal is work nobody should have to redo.
      */
      if ((await onAttach(outcome.file)) === false) return;
      /*
        ── AND ONCE MORE, FOR THE WINDOW A TWO-PHASE HOST LEAVES OPEN ──────────────────────────────

        A host whose `onAttach` stages the bytes, writes a draft and then syncs and reloads with the
        page live resolves long after the researcher can act again. What is prevented here is not the
        line art — that is filed, the host has said so in its own way, and there is no taking it back.
        It is a green tick naming a file the panel no longer holds, over an image it did not come from.

        SILENTLY, UNDER `AttachAnswer`'S OWN RULE: the host has already told them. A sentence here
        could only describe an image that is no longer on the screen.
      */
      if (pick !== pickRef.current || goneRef.current) return;
      setDone(
        `${outcome.file.name} was handed over.` +
          (currentFileName ? ` It replaces ${currentFileName}.` : "") +
          ` The image itself is untouched.` +
          (outcome.note ? ` ${outcome.note}` : "")
      );
      setOpen(false);
    } catch (error) {
      setProblem(
        error instanceof Error ? `The line art could not be made: ${error.message}` : "The line art could not be made."
      );
    } finally {
      endFullRun();
    }
  }

  /**
   * Save one of the derived artefacts to the device. Nothing is uploaded and nothing is filed.
   *
   * ────────────────────────────────────────────────────────────────────────────
   * WHAT IS BEING DOWNLOADED, AND WHY IT IS NOT WHAT IS ON SCREEN
   * ────────────────────────────────────────────────────────────────────────────
   *
   * IT RE-TRACES AT FULL RESOLUTION FIRST, EXACTLY AS THE ATTACH DOES. The drawing on screen is a
   * preview traced at a smaller working edge, and the panel already says so under the canvas. A
   * download button that saved `svgInput` would hand over a coarser drawing than the one being looked
   * at, with the same shape count and the same name — the single most plausible way for this feature
   * to be wrong while appearing to work. So the press pays for one more trace, and every download and
   * the attach all come from the same full-resolution run.
   *
   * WHAT THE ARTEFACTS ARE:
   *   · `svg` — the VECTOR geometry. Editable, scalable, re-openable in Illustrator, Inkscape or
   *     CorelDRAW, and byte-for-byte the file the host receives when SVG is the chosen attach format.
   *   · `png` — the RENDERED raster, painted by the same `paintGeometry` that drew the preview above.
   *     What anybody can open, print or drop into a slide.
   *   · `pdf`, `eps`, `dxf` — that same vector geometry, written for a machine that will not take an
   *     SVG: anybody's PDF reader, a print shop's PostScript workflow, a cutter or CNC controller.
   * Every name is the image's own stem plus one suffix (`traceExport.ts` holds both words), so the
   * downloads folder still says which image each came from.
   *
   * THE HANDLER IS FORMAT-DRIVEN AND THE ROW OF BUTTONS RENDERS FROM THE SAME TABLE, which is the
   * whole mechanism that stops the next writer going unexposed: there is no place to add a format that
   * is not also the place the control comes from. `e2e/trace-export-formats-unit.spec.ts` asserts that
   * in both directions.
   *
   * NOTHING PERSISTS. There is no stored trace to fetch and none is created — see property 5 in this
   * file's header. This is a `File` made in memory on the press and handed to the browser's own save
   * path; close the panel and it cannot be produced again without re-tracing.
   */
  async function downloadDerived(what: ExportFormatId) {
    if (svgInput === null || source === null || runtime === null || params === null) return;
    // The same token, for the same reason, on the other button that pays for a full-resolution run.
    const pick = pickRef.current;
    beginFullRun(`download-${what}`);
    setSaved(null);
    try {
      const latest = await runTrace(false);
      /*
        AND THE SAME SILENCE. `saveBlobToDisk` below would put a file named after the replaced image
        into the downloads folder without the panel showing that image anywhere, and `setSaved` would
        then re-write the very sentence `adoptImage` clears — "was saved to this device", under a file
        name that is now nobody's. Better nothing than a file the researcher cannot connect to anything
        on screen.
      */
      if (pick !== pickRef.current || goneRef.current) return;
      if (latest === null) {
        setProblem("The trace did not finish, so there is nothing to download yet.");
        return;
      }
      const input = inputFrom(latest);
      /*
        THE PNG IS THE ONE THAT GETS NO PROVENANCE NOTE, AND THAT IS NOT AN OMISSION HERE.
        `exportPngFile` takes no such argument — a PNG has no comment channel this code writes — and
        the same is true of the DXF one layer down, where `writeDxf` has no metadata parameter at all.
        Both gaps are stated in the copy under these buttons rather than quietly tolerated.
      */
      const outcome =
        what === "svg"
          ? exportSvgFile(input, source.name, provenanceFor(latest, source.name), { suffix: TRACE_SUFFIX })
          : what === "png"
            ? await exportPngFile(input, source.name, PNG_MAX_EDGE_PX, { suffix: RENDER_SUFFIX })
            : await exportVectorFile(what, input, source.name, provenanceFor(latest, source.name), {
                suffix: TRACE_SUFFIX
              });
      /*
        THE SAME TOKEN AGAIN, AND ON THIS BUTTON THE WINDOW IS THE WIDEST ON THE PANEL. Three of the
        five formats reach the network here: `exportVectorFile` dynamic-imports its writer chunk, and
        `WRITER_UNAVAILABLE` exists precisely because that fetch fails on a slow connection — so it can
        be seconds, and the PNG branch pays for a full-resolution encode instead. The host's picker is
        enabled for all of it: this panel's `running` disables this panel's own buttons and the host
        never learns a download is happening at all.
      */
      if (pick !== pickRef.current || goneRef.current) return;
      if (!isExported(outcome)) {
        setProblem(outcome.reason);
        return;
      }
      saveBlobToDisk(outcome.file, outcome.file.name);
      setSaved(
        `${outcome.file.name} was saved to this device. Nothing was filed on the record and nothing was ` +
          `uploaded.` +
          // A cap that reduced the file is stated on screen, never swallowed. `exportPngFile` reports
          // its 2048px ceiling and `buildSvg` its shape ceiling this way, and a download that quietly
          // dropped the sentence would be the one place the ceiling is invisible.
          (outcome.note ? ` ${outcome.note}` : "")
      );
    } catch (error) {
      setProblem(
        error instanceof Error
          ? `That file could not be made: ${error.message}`
          : "That file could not be made."
      );
    } finally {
      endFullRun();
    }
  }

  /* ──────────────────────────────────────────────────────────────────────────
   * Render
   * ────────────────────────────────────────────────────────────────────────── */

  /**
   * Any full-resolution run at all, for the buttons that must not be pressed during another.
   *
   * EVERY PRESS DISABLES EVERY BUTTON, not just its own. They share one `AbortController` (`abortRef`)
   * and one worker, so a second press aborts the first press's trace — and this panel treats an abort
   * as the ordinary consequence of moving a slider, so the first press would fail with "the trace did
   * not finish" for a reason that appears nowhere on screen.
   */
  const busy = running !== null;

  /**
   * The one sentence under the card's name, in both states.
   *
   * ONE STRING FOR COLLAPSED AND OPEN. A card that says nothing while closed makes the fact that
   * decides whether somebody is willing to hand an image to a tracing tool at all — that nothing
   * leaves the device — readable only after they have already committed to opening it.
   */
  const CARD_DESCRIPTION =
    "Everything below is computed on this device — the image is not sent anywhere to be traced. The " +
    "original stays exactly as it is; only the drawing is added.";

  if (phase.status === "unavailable") {
    /*
      "This device cannot do it at all" wants the control gone, not a button that fails.

      NOTHING IS LOST BY THIS BRANCH, which is a property of the contract rather than a claim about a
      screen this file cannot see: the host already holds the image — it handed it to this panel — so
      a researcher whose browser will not start a module worker has lost a tool and not a photograph.
      That is the difference between this panel and one that also owned the picker, where hiding
      everything would have taken the only route to filing the original with it.
    */
    return (
      <div
        role="alert"
        className="mt-3 flex items-start gap-2 rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-xs text-ink-500"
      >
        <AlertTriangle className="mt-0.5 h-3.5 w-3.5 shrink-0" aria-hidden />
        <span>{phase.reason}</span>
      </div>
    );
  }

  const trigger = (
    <section className="rounded-md border border-line-200 bg-surface-50">
      <h4>
        <button
          type="button"
          ref={triggerRef}
          className="flex w-full items-start gap-2 rounded-md p-3 text-left disabled:cursor-not-allowed disabled:opacity-60"
          onClick={() => setOpen(true)}
          disabled={disabled}
          aria-expanded={false}
          /*
            NO `aria-controls` WHILE THE PANEL IS UNMOUNTED. Pointing at an id that is in no document
            is worse than not pointing, because a reader is offered a jump that goes nowhere. Opening
            replaces this button outright, so there is no state in which it can honestly carry one.
          */
        >
          <Wand2 className="mt-0.5 h-4 w-4 shrink-0 text-ink-500" aria-hidden />
          <span className="min-w-0 flex-1">
            <span className="block text-sm font-medium text-ink-900">{CARD_TITLE}</span>
            <span className="mt-0.5 block text-xs leading-5 text-ink-500">{CARD_DESCRIPTION}</span>
          </span>
          {/*
            The chevron is DECORATION over `aria-expanded`, never the state itself, and its rotation is
            a CSS transition that the app's reduced-motion rules zero — so open and closed are still
            told apart by the arrow's direction with no motion at all.
          */}
          <ChevronDown className="mt-0.5 h-4 w-4 shrink-0 text-ink-500 transition-transform" aria-hidden />
        </button>
      </h4>
    </section>
  );

  /**
   * The take-away formats, lifted out of the panel's JSX so the ONE disclosure can hold them.
   *
   * A `const` rather than a component, because it closes over six values from this body — the running
   * flags, the traced result, the source and the saved sentence — and a component taking six props
   * would be six chances for one of them to stop being passed. It is rendered in exactly one place.
   *
   * WHY IT IS INSIDE THE DISCLOSURE AT ALL. The primary path is the result, the comparison, the
   * presets, the essential controls and "Add the line art"; a download is a copy for the person at the
   * keyboard and reaches no field, no upload queue and no draft store.
   */
  const downloads = (
    <div className="mt-4 border-t border-line-200 pt-3">
      <span className="field-label">Download a copy to this device</span>
      {/*
        ONE BUTTON PER TABLE ROW, WHICH IS THE WHOLE OF THE MECHANISM. Two hard-coded buttons beside a
        table with five entries is how three finished writers sit in an engine with nothing on screen
        able to reach them. Rendering from the table means the two facts are one fact: a row without a
        control cannot exist, and `e2e/trace-export-formats-unit.spec.ts` fails if a writer the engine
        can run is neither in the table nor in `NOT_OFFERED` with a reason.

        The words come from `entry.download` rather than from here, so each label lives beside the
        format it belongs to.
      */}
      <div className="mt-1 flex flex-wrap gap-2">
        {EXPORT_FORMATS.map((entry) => (
          <button
            key={entry.id}
            type="button"
            className="field-button-secondary"
            onClick={() => void downloadDerived(entry.id)}
            disabled={disabled || busy || result === null || source === null}
          >
            {running === `download-${entry.id}` ? (
              <Loader2 className="h-4 w-4 animate-spin" aria-hidden />
            ) : (
              <Download className="h-4 w-4" aria-hidden />
            )}
            {entry.download}
          </button>
        ))}
      </div>
      {/* HONEST NAMING, ONE LINE EACH. The audience is a researcher, not a developer: nobody should
          have to know what a `.dxf` is before pressing a button that makes one, and the sentence that
          says what a format is FOR is the same string the "Attach as" chooser shows, so the two
          surfaces cannot describe one format differently. */}
      {/* FULL WIDTH, NOT `max-w-prose`. This is a LIST of formats, not running prose: 65ch against a
          12px font is ~400px while every ancestor here is full width, so a measure clamp would wrap
          "what a .dxf is for" into a narrow ribbon with the rest of the panel empty beside it. */}
      <ul className="mt-2 grid gap-1 text-xs leading-5 text-ink-500">
        {EXPORT_FORMATS.map((entry) => (
          <li key={entry.id}>
            <span className="font-medium text-ink-700">{entry.label}</span> — {entry.hint}
          </li>
        ))}
      </ul>
      <p className="mt-2 max-w-prose text-xs leading-5 text-ink-500">
        Every one of these is re-traced at full resolution when you press it, so none of them is the
        smaller preview above. None of them is filed on the record and none is uploaded: a drawing
        downloaded here is a copy for you, your printer or your CAD operator. Attaching to the record is
        the “Add the line art” button below this section.
      </p>
      <p className="mt-1 max-w-prose text-xs leading-5 text-ink-500">
        Nothing here is stored: the trace lives only while this panel is open, so closing it, changing
        the image or reloading the page discards it, and a download after that means tracing again.
      </p>
      {/*
        WHAT IS WRITTEN INSIDE THE FILE ABOUT WHO MADE IT, SCOPED TO THE TWO FORMATS IT IS ACTUALLY
        TRUE OF. `exportVectorFile` passes `includeMetadata: true`, so `pdfWriter` emits
        `/Producer (Offline Tracer) /Creator (Offline Tracer)` and `epsWriter` emits
        `%%Creator: Offline Tracer`. The SVG is NOT among them: this page writes its own through
        `geometryToSvg.buildSvg` rather than through the engine's writer, and that function emits no
        producer line at all. Re-check with
        `grep -rn "Offline Tracer" frontend/lib/trace frontend/components/trace`.
      */}
      <p className="mt-1 max-w-prose text-xs leading-5 text-ink-500">
        The PDF and the EPS record that they were made by the Offline Tracer engine, which is the
        tracing library this app uses on the device. That is a note about the software — not about the
        drawing, and not about you. The SVG this page writes carries no such line, because this page
        writes it rather than the engine.
      </p>
      {/*
        THE SAME GAP AS THE ATTACH'S, SAID IN THE OTHER HALF OF THE PANEL.

        `provenanceFor` reaches every format that has somewhere to put it, and three of the five do:
        the SVG carries it as an XML comment, the PDF in its `/Title` and the EPS in its `%%Title:`.
        `exportPngFile` takes no provenance argument — a PNG has no comment channel this code writes —
        and `writeDxf` has no metadata parameter at all, because DXF R12 has nowhere to keep one. The
        copy under the format buttons says this for the file that reaches the HOST; without this line,
        somebody who cropped and then pressed "Download the rendered image" would get a file whose
        frame is recorded nowhere and would be told nothing about it.
      */}
      {edited !== null ? (
        <p className="mt-2 max-w-prose text-xs leading-5 text-amber-800">
          The frame you chose is written into the SVG&apos;s provenance note, the PDF&apos;s title and
          the EPS&apos;s header, so those three say how they were made. The PNG and the DXF have
          nowhere to carry it: saved on their own, neither records that the drawing was cropped or
          sharpened.
        </p>
      ) : null}
      {/* Mounted whether or not anything has been saved, so the sentence is a CHANGE to a region
          already in the document — a live region that appears with its text already in it is announced
          by nothing. */}
      <p aria-live="polite" aria-atomic="true" className="mt-1 text-xs leading-5 text-ink-500">
        {saved ? (
          <span className="flex items-start gap-2">
            <Check className="mt-0.5 h-3.5 w-3.5 shrink-0 text-success-600" aria-hidden />
            <span>{saved}</span>
          </span>
        ) : null}
      </p>
    </div>
  );

  const panel = (
    <section className="rounded-md border border-line-200 bg-surface-50">
      {/*
        THE HEADER CARRIES THE PADDING AND THE BODY CARRIES ITS OWN, and the root carries none, so a
        border can separate the two without either of them growing a margin that only shows in one
        state.
      */}
      <div className="flex items-start gap-2 p-3">
        <Wand2 className="mt-0.5 h-4 w-4 shrink-0 text-ink-500" aria-hidden />
        <div className="min-w-0 flex-1">
          {/*
            `tabIndex={-1}` makes the heading focusable by script and not by Tab, which is what a
            deliberate focus move needs and what a tab stop on a heading would get wrong.

            AND IT IS THE ONE THING THAT KEEPS THIS HEADER A HEADING RATHER THAN A TOGGLE. Opening this
            panel moves focus here on purpose, so a reader hears the panel's NAME and knows where they
            have landed. A heading that were also the collapse button would be announced as a button,
            and a reflex Space on arrival would shut the panel the researcher had just asked for.

            `min-w-0 flex-1` ON THE COLUMN: without it a long sentence in a `justify-between` row
            pushes the close control off its own edge instead of wrapping.
          */}
          <h4
            ref={headingRef}
            tabIndex={-1}
            className="text-sm font-medium text-ink-900 focus:outline-none focus-visible:ring-2 focus-visible:ring-purple-600/40"
          >
            {CARD_TITLE}
          </h4>
          <p className="mt-0.5 text-xs leading-5 text-ink-500">{CARD_DESCRIPTION}</p>
        </div>
        <button
          type="button"
          className="rounded-md p-1 text-ink-500 transition hover:bg-field-100 hover:text-ink-900"
          onClick={() => setOpen(false)}
          aria-label="Close the tracing panel"
        >
          <X className="h-4 w-4" aria-hidden />
        </button>
      </div>

      <div id={panelId} className="border-t border-line-200 p-3">
        {phase.status === "loading" ? (
          <p aria-live="polite" className="flex items-center gap-2 py-6 text-sm text-ink-500">
            <Loader2 className="h-4 w-4 animate-spin" aria-hidden />
            Loading the tracing engine…
          </p>
        ) : null}

        {phase.status === "ready" ? (
          <>
            {/* ── What this panel is working from ─────────────────────────────── */}
            {/*
              NAMED, NEVER SILENT. This panel draws no picker of its own — the host chose the image and
              this panel traces it — so the one thing it owes the reader is which image that is. A panel
              of inert controls over an unnamed picture is the "a control that vanishes is
              indistinguishable from a feature this build does not have" failure, and it is why the
              empty state below says where the one picker is rather than offering a second one.
            */}
            <div className="mb-3">
              <p className="rounded-md border border-line-200 bg-card px-3 py-2 text-xs leading-5 text-ink-500">
                {source ? (
                  <>
                    <span className="font-medium text-ink-900">{source.name}</span>
                    {pixels && (pixels.sourceWidth !== pixels.width || pixels.sourceHeight !== pixels.height)
                      ? ` — read at ${pixels.width}x${pixels.height}, reduced from ${pixels.sourceWidth}x${pixels.sourceHeight} before tracing.`
                      : pixels
                        ? ` — ${pixels.width}x${pixels.height}.`
                        : " — reading…"}
                    {` Anything over ${DECODE_MAX_EDGE_PX}px on the long edge is reduced before tracing; the original is untouched.`}
                  </>
                ) : (
                  <span className="flex items-start gap-2">
                    <ImageIcon className="mt-0.5 h-3.5 w-3.5 shrink-0 text-ink-500" aria-hidden />
                    <span>
                      No image has been chosen yet. Choose one with the picker on this screen — this panel
                      traces whatever is chosen there and never picks one of its own.
                    </span>
                  </span>
                )}
              </p>
            </div>

            {/* ── What the trace is framed to ─────────────────────────────────── */}
            {/*
              ── THE FRAME'S ONE LINE STAYS ON THE PRIMARY PATH; THE CHOOSER DOES NOT ──────────────
              The chooser itself is a configuration surface and lives in the one disclosure below with
              everything else that is not the primary path. This row does not, and the handset argues
              why in a comment beside its own copy of it (`TraceCropPanel.kt:174`): a closed control
              that says nothing about its own state is a control somebody has to open to find out
              whether they touched it, and this one changes what the drawing IS. A crop is destructive
              — everything outside it is absent from the drawing — so it is the one setting that may
              never be invisible.

              "Choose a frame" is the handset's word for the control that opens it
              (`TraceCropPanel.kt:199`), and it opens the SAME disclosure the toggle below does rather
              than a second one; it only differs in moving focus, because the chooser is a long way
              down inside it.

              THE MULTIPLICATION SIGN HERE AND A LETTER `x` IN THE CHOOSER IS A KNOWN DIVERGENCE, not a
              slip: this is the handset's screen typography (`traceCropReadout`), and `FramePanel`'s own
              readout keeps the letter because the rest of this panel's size sentences do.
            */}
            {pixels ? (
              <div className="mb-3 flex flex-wrap items-start gap-3 rounded-md border border-line-200 bg-card p-3">
                <span className="mt-0.5 grid h-8 w-8 shrink-0 place-items-center rounded-lg bg-field-200 text-field-600">
                  <Crop className="h-4 w-4" aria-hidden />
                </span>
                <div className="min-w-0 flex-1">
                  <p className="text-xs font-semibold text-ink-900">The part of the photograph to trace</p>
                  <p className="mt-0.5 text-xs leading-5 text-ink-500">
                    {edited === null
                      ? "The whole photograph."
                      : `${edited.crop.width}×${edited.crop.height} of ${pixels.width}×${pixels.height}.`}
                    {edited !== null && edited.sharpen.amount > 0
                      ? " Sharpened on this device before tracing."
                      : ""}{" "}
                    The image itself is never altered.
                  </p>
                </div>
                <button
                  type="button"
                  className="field-button-secondary"
                  onClick={chooseFrame}
                  disabled={disabled}
                  aria-expanded={advancedOpen}
                  aria-controls={advancedMounted ? advancedId : undefined}
                >
                  <Crop className="h-4 w-4" aria-hidden />
                  {advancedOpen ? "Done" : "Choose a frame"}
                </button>
              </div>
            ) : null}

            {/* ── The preview ────────────────────────────────────────────────── */}
            {pixels ? (
              <div className="mb-3 rounded-md border border-line-200 bg-card p-3">
                <div className="flex items-center justify-between gap-2">
                  <span className="field-label">Traced result</span>
                  {/* Rendered whether or not a trace is running — a live region that appears with its
                      text already in it is announced by nothing. Empty while idle, so it costs a reader
                      silence rather than "blank". */}
                  <span aria-live="polite" className="flex items-center gap-1.5 text-xs text-ink-500">
                    {tracing ? (
                      <>
                        <Loader2 className="h-3 w-3 animate-spin" aria-hidden />
                        {stopping ? "Stopping…" : (progress ?? "Tracing…")}
                      </>
                    ) : null}
                  </span>
                </div>
                {/*
                  ── THE BAR AND THE STOP, WHICH ARE ONE ROW BECAUSE THEY ANSWER ONE QUESTION ──────

                  "Is this thing still going, and can I get out of it." A full-resolution trace of a
                  12 MP photograph is seconds of solid arithmetic and the stage that dominates it is
                  `edge`, so a label with no bar reads as a hang there.

                  DRAWN ONLY WHEN THERE IS SOMETHING TO DRAW. `progressAt` is null for a preview,
                  because `trace.worker.ts` hands `Pipeline.runPreview` no progress callback at all —
                  so this row belongs to the presses, which are the runs long enough to want it.
                */}
                {tracing && progressAt !== null ? (
                  <div className="mt-2 grid gap-1">
                    <div className="h-1 w-full overflow-hidden rounded-full bg-field-200">
                      <div
                        className="h-full rounded-full bg-primary transition-[width] duration-200"
                        style={{ width: `${Math.round(progressAt * 100)}%` }}
                      />
                    </div>
                    {/* A BAR THAT WILL VISIBLY STALL, SAYING SO. Until this machine has finished one
                        trace the boundaries are the engine's even twelfths, and the two long stages are
                        worth several of the others put together. One line costs less than a researcher
                        deciding the panel has frozen. */}
                    {!weights.measured ? (
                      <p className="text-xs leading-4 text-ink-500">{PROGRESS_UNMEASURED_NOTE}</p>
                    ) : null}
                  </div>
                ) : null}
                {tracing ? (
                  <div className="mt-2">
                    <button
                      type="button"
                      className="rounded-md border border-line-200 bg-card px-2 py-1 text-xs font-medium text-ink-700 transition hover:border-purple-300 disabled:opacity-60"
                      onClick={stopTrace}
                      disabled={stopping}
                    >
                      {stopping ? "Stopping…" : "Stop"}
                    </button>
                  </div>
                ) : null}
                {/* 420px, because this canvas is a picture to LOOK at: a drawing taller than a phone
                    screen pushes the attach buttons under it out of reach. `PREVIEW_BOX_PX` above is
                    the same number for the bitmap itself. */}
                <div className="mt-2 grid place-items-center rounded-md bg-field-100 p-2">
                  <canvas ref={canvasRef} className="max-h-[420px] max-w-full" aria-label="The traced drawing" />
                </div>
                {result ? (
                  <dl className="mt-2 grid grid-cols-2 gap-x-4 gap-y-1 text-xs text-ink-500 sm:grid-cols-4">
                    <div>
                      <dt className="inline">Paths </dt>
                      <dd className="inline font-medium text-ink-900">{result.shapeCount.toLocaleString("en-IN")}</dd>
                    </div>
                    <div>
                      <dt className="inline">Nodes </dt>
                      <dd className="inline font-medium text-ink-900">{result.nodeCount.toLocaleString("en-IN")}</dd>
                    </div>
                    <div>
                      <dt className="inline">Took </dt>
                      <dd className="inline font-medium text-ink-900">{Math.round(result.totalMillis)} ms</dd>
                    </div>
                    <div>
                      <dt className="inline">Run at </dt>
                      <dd className="inline font-medium text-ink-900">
                        {result.workingWidth}x{result.workingHeight}
                      </dd>
                    </div>
                  </dl>
                ) : null}
                {/* Every sentence the engine produced, rendered without exception. The engine says how
                    much of the frame a matte removed and how many specks it dropped, and those are
                    exactly the facts a researcher needs in order to disbelieve a result that looks
                    clean. */}
                {result && result.notes.length > 0 ? (
                  <ul className="mt-2 space-y-1 text-xs text-ink-500">
                    {result.notes.map((note) => (
                      <li key={note}>· {note}</li>
                    ))}
                  </ul>
                ) : null}
                {result && result.autoSubjectId ? (
                  <p className="mt-2 text-xs text-ink-500">
                    The engine applied the “{result.autoSubjectId}” subject adjustment on its own.
                  </p>
                ) : null}
                {result && result.workingWidth < result.width ? (
                  <p className="mt-2 text-xs text-ink-500">
                    This is a preview at a smaller working size. Attaching or downloading re-traces at full
                    resolution first.
                  </p>
                ) : null}
              </div>
            ) : null}

            {/* ── The trace against the photograph ───────────────────────────── */}
            {/*
              THE COMPARATOR, OPENING ON THE TRACE.

              `TraceCompare` clips the BEFORE layer by `position`, so the trace is passed as
              `afterImage` with `position` starting at 0 (`COMPARE_START_POSITION`) — the drawing fills
              the frame, the divider sits hard against the leading edge, and dragging reveals the
              photograph underneath. Passing them the other way round is the obvious mistake and the
              component's own header says so.
            */}
            {pixels ? (
              <div className="mb-3 rounded-md border border-line-200 bg-card p-3">
                <div className="flex items-center justify-between gap-2">
                  <span className="field-label">The trace against the image</span>
                  {/* One place for every "why is there no comparison" sentence, and empty when there is
                      one — a live region that appears with its text already in it is announced by
                      nothing, and one that says "blank" costs a reader a sentence for no information. */}
                  <span aria-live="polite" className="text-xs text-ink-500">
                    {comparisonStatus}
                  </span>
                </div>
                {compare ? (
                  <>
                    {/*
                      THE FOUR VIEWS, AS THE HANDSET'S CHIP ROW. `aria-pressed` rather than a radio
                      group, matching the "Attach as" row below: these are four ways of looking at one
                      thing, not four values of a field the panel is about to write.
                    */}
                    <div className="mt-2 flex flex-wrap gap-2">
                      {COMPARE_MODES.map((entry) => (
                        <button
                          key={entry.id}
                          type="button"
                          className={
                            compareMode === entry.id
                              ? "rounded-md border border-purple-600 bg-purple-50 px-3 py-1.5 text-xs font-medium text-purple-800"
                              : "rounded-md border border-line-200 bg-card px-3 py-1.5 text-xs font-medium text-ink-700 transition hover:border-purple-300"
                          }
                          aria-pressed={compareMode === entry.id}
                          onClick={() => (entry.id === "difference" ? showDifference() : setCompareMode(entry.id))}
                        >
                          {entry.label}
                        </button>
                      ))}
                    </div>
                    <TraceCompare
                      className="mt-2"
                      beforeImage={{
                        src: compare.originalUrl,
                        alt: source ? `The image ${source.name}, as the tracing engine read it` : "The image"
                      }}
                      afterImage={{ src: compare.traceUrl, alt: "The traced drawing, on white" }}
                      beforeLabel="Image"
                      afterLabel="Traced drawing"
                      /*
                        CONTROLLED, WHICH IS WHAT MAKES THE CHIPS POSSIBLE. The two end states are this
                        one number written to 0 and 100 — and they are written to the DISPLAYED position
                        only, because `comparePosition` is where the researcher left the seam and
                        pressing Wipe again has to come back to it.
                      */
                      position={
                        compareMode === "drawing" ? 0 : compareMode === "photograph" ? 100 : comparePosition
                      }
                      onPosition={(next) => {
                        setComparePosition(next);
                        // MOVING THE SEAM IS ASKING FOR THE WIPE. A researcher who drags while "Drawing"
                        // is selected has just told the panel which view they want, and a chip row that
                        // then disagreed with the picture would be a control claiming something untrue.
                        setCompareMode("wipe");
                      }}
                      initialPosition={COMPARE_START_POSITION}
                      ariaLabel="Traced drawing against the image"
                      maxZoom={COMPARE_MAX_ZOOM}
                      peekHoldMs={REVEAL_PEEK_HOLD_MS}
                      /*
                        THE DESCRIPTION AND THE BADGE ARE BOTH CONSTANTS rather than literals, because
                        this frame and the handset's frame show one picture to one researcher and the
                        two apps drifting apart on what they call it is the failure the shared wording
                        exists to prevent. The badge is not decoration — a difference plate of a GOOD
                        trace is very nearly black, and a nearly black frame with no word on it is
                        indistinguishable from a plate that failed to draw.
                      */
                      soloImage={
                        compareMode === "difference" && difference !== null
                          ? {
                              src: difference,
                              alt: COMPARISON_DIFFERENCE_ALT,
                              label: COMPARISON_DIFFERENCE_BADGE
                            }
                          : null
                      }
                      // The image's own ratio, so neither layer is centre-cropped. Without it the frame
                      // is 16:9 and a portrait sheet loses most of the drawing off the top and bottom.
                      aspectRatio={compare.width / compare.height}
                    />
                    {/*
                      THE INSTRUCTION IS WRITTEN OUT BECAUSE THE GESTURE IS NOT DISCOVERABLE, and the
                      keyboard half is written out because it exists: a drag is a pointer gesture and is
                      unreachable from a keyboard, from a switch device and from a screen reader, and
                      the comparator answers to the arrow keys, Home and End for exactly that reason. A
                      hint nobody can see is a feature nobody can reach.
                    */}
                    <p className="mt-2 text-xs leading-5 text-ink-500">
                      The drawing is shown first. Drag the handle — or focus it and use the arrow keys, Home and
                      End — to reveal the image underneath. Press and hold the picture to see the image, and let
                      go to come back: the seam stays where you left it. Hold Ctrl (or ⌘) and scroll to magnify up
                      to {COMPARE_MAX_ZOOM}×, or press + and − with the frame focused and 0 to go back to fit;
                      magnified, dragging moves the picture instead of the seam. The comparison paints the drawing
                      on white so it is visible over the image; the file that is attached or downloaded keeps
                      whatever background you chose.
                      {compare.reduced
                        ? ` Both pictures here are ${compare.width}x${compare.height}, reduced from ${
                            result ? `${Math.round(result.width)}x${Math.round(result.height)}` : "the traced size"
                          } for the comparison only.`
                        : ""}
                    </p>
                    {/*
                      THE DIFFERENCE VIEW'S OWN SENTENCE, SAID ONLY WHERE IT IS TRUE. The plate is black
                      where the two agree and bright where they do not, which is not what anybody expects
                      a picture of their photograph to look like — printed under every view it would be
                      four lines a researcher learns to skip, and skipping is how the sentence that
                      matters gets missed.
                    */}
                    {compareMode === "difference" ? (
                      <p aria-live="polite" className="mt-2 text-xs leading-5 text-ink-500">
                        {differenceProblem !== null
                          ? differenceProblem
                          : difference === null
                            ? COMPARISON_DIFFERENCE_PENDING
                            : COMPARISON_DIFFERENCE_NOTE}
                      </p>
                    ) : null}
                  </>
                ) : null}
              </div>
            ) : null}

            {/* ── Presets ────────────────────────────────────────────────────── */}
            {styles.length > 0 ? (
              <div className="mb-3 grid gap-3 sm:grid-cols-2">
                <div className="grid gap-1">
                  {/*
                    ── THE THEMED DROPDOWN, AND WHAT THE `<optgroup>` WAS TRADED FOR ────────────────
                    Twenty style presets in a native `<select>` is the longest list in this application
                    with no way to type into it. `SearchableSelect`'s own threshold is eight options;
                    this list is twenty and fixed at twenty, so it is the clearest case there is.

                    `SelectOption` carries no group field, so the grouping cannot come across as
                    `<optgroup>` markup. It comes across in the LABEL instead — "Line · Ink line" — and
                    that is better here rather than merely equivalent: an `<optgroup>` heading is chrome
                    a reader can only scroll to, while a group name inside the label is something they
                    can TYPE. Filtering on "line" returns that whole family, which the native control
                    cannot do at all.

                    Group ORDER is `styleGroups`, so the list reads down the page in the order the
                    engine declares; the filter re-ranks only while a query is being typed.

                    `advanceOnSelect={false}` BECAUSE THIS IS NOT A FORM FIELD. That prop's own note
                    says it: on by default for a form filled top-to-bottom in one pass, and off for a
                    control that changes the screen it sits on. Picking a style re-traces the drawing
                    four inches above it, and jumping focus to the next control at that moment takes the
                    researcher away from the thing they are adjusting.

                    A `<span className="field-label">` beside the control rather than a
                    `<label htmlFor>`: `Dropdown` renders a button and takes no id, so a `for` would
                    name an element that does not exist.
                  */}
                  <span className="field-label">Style</span>
                  <Dropdown
                    value={styleId}
                    onChange={pickStyle}
                    disabled={disabled}
                    ariaLabel="Style"
                    searchable
                    advanceOnSelect={false}
                    /* NO "Engine defaults" ROW — see the load effect. It would be a row that cannot be
                       chosen, on a control that opens claiming it. */
                    options={styleGroups.flatMap((group) =>
                      styles
                        .filter((style) => style.group === group)
                        .map((style) => ({ value: style.id, label: `${group} · ${style.name}` }))
                    )}
                  />
                  {/*
                    THE CHOSEN STYLE'S OWN DESCRIPTION, UNDER THE CONTROL.

                    NOT LINKED WITH `aria-describedby`, AND THAT IS A GAP RATHER THAN A CHOICE: this
                    repository's `Dropdown` accepts no `describedBy` and renders a `<button>` with no id
                    to hang one on. The sentence is therefore read by a screen-reader user in document
                    order after the control rather than as part of it. Closing it is a change to
                    `components/ui/SearchableSelect.tsx`, which this port may not edit — one prop
                    forwarded to the trigger's `aria-describedby`.
                  */}
                  <p className="text-xs text-ink-500">
                    {styles.find((s) => s.id === styleId)?.description ??
                      "A style sets every control at once. Pick one, then adjust."}
                  </p>
                </div>

                <div className="grid gap-1">
                  {/* THE PLAINER LABEL. "Subject" is the engine's word for the table and not a
                      researcher's word for the thing in front of the camera. */}
                  <span className="field-label">What this is a picture of</span>
                  <Dropdown
                    value={subjectId}
                    onChange={(next) => {
                      setSubjectId(next);
                      if (next) applySubject(next);
                    }}
                    disabled={disabled || subjects.length === 0}
                    ariaLabel="What this is a picture of"
                    placeholder="Choose a material"
                    searchable
                    advanceOnSelect={false}
                    /*
                      THE LIST ALWAYS CARRIES ITS OWN "Choose a material" ROW, so the empty state is
                      unreachable and a sentence that cannot render is a sentence the next reader has to
                      disprove. Re-picking the same subject re-applies it, which is safe by
                      construction: `engine/subjects.ts` declares `adjust` as idempotent over the
                      current tree, which is the property the hint below promises in words.

                      THE TEN HINTS CANNOT GO ON THE ROWS. `loadTracePresets` carries `subject.hint` —
                      the one sentence saying what each subject actually changes ("Weave is periodic
                      texture, not line work…") — and this repository's `SelectOption` is
                      `{ value, label, disabled? }` with nowhere to put it. So the hint is shown under
                      the control for whichever subject is chosen, which is where a researcher wonders
                      what they picked; on the rows, where it would also be searchable, it needs a
                      `hint` field on `SelectOption` and that file is not this port's to edit.
                    */
                    options={[
                      { value: "", label: "Choose a material" },
                      ...subjects.map((subject) => ({ value: subject.id, label: subject.name }))
                    ]}
                  />
                  <p className="text-xs text-ink-500">
                    {subjects.find((subject) => subject.id === subjectId)?.hint ??
                      "A subject nudges the settings for the material in front of the camera. It leaves the style alone and can be applied more than once without compounding."}
                  </p>
                </div>
              </div>
            ) : null}

            {/* ── What the engine thinks this image is ────────────────────────── */}
            {/*
              THE CLASSIFICATION IS ALREADY PAID FOR. `SerializedProfile.suggestion` is a style preset
              id, is never empty on a full trace, and crosses the worker boundary on every one of them —
              so the engine looks at the photograph, forms an opinion and sends it. Discarding it while
              a researcher scrolls a twenty-item list is work thrown away.

              IT PROPOSES AND NEVER APPLIES. Applying a style REPLACES every setting — `pickStyle` says
              so — so a suggestion that applied itself would silently discard a researcher's tuning at
              the exact moment their trace finished. The button is the application.

              DRAWN ONLY WHEN THERE IS SOMETHING TO SAY: nothing on a preview, because previews do not
              classify and `profile` is null for them, and nothing when the engine agrees with the style
              already chosen.
            */}
            {suggestedStyle !== null ? (
              <div aria-live="polite" className="mb-3 rounded-md border border-line-200 bg-field-100 px-3 py-2">
                <p className="text-xs leading-5 text-ink-700">
                  Looking at this image, the engine suggests the “{suggestedStyle.name}” style.{" "}
                  {suggestedStyle.description}
                </p>
                <button
                  type="button"
                  className="field-button-secondary mt-2"
                  onClick={() => pickStyle(suggestedStyle.id)}
                  disabled={disabled || busy}
                >
                  <Wand2 className="h-4 w-4" aria-hidden />
                  Use the “{suggestedStyle.name}” style
                </button>
              </div>
            ) : null}

            {notice ? (
              <p role="alert" className="mb-3 rounded-md border border-line-200 bg-amber-100 px-3 py-2 text-xs text-amber-800">
                {notice}
              </p>
            ) : null}

            {/* ── Controls: the ones that decide what KIND of drawing comes out ── */}
            {/*
              `ESSENTIAL_KEYS`, AND THE TABLE DECIDES WHICH THEY ARE. Its own comment gives the rule —
              each of them changes the KIND of drawing that comes out, while the rest tune a drawing the
              researcher already has — plus sharpening, because a workshop photograph under one tube
              light is soft far more often than it is noisy. Grouped rather than flat for the reason the
              disclosure below is grouped: "which stage of the pipeline is this" is how a researcher
              looks for a control.
            */}
            {params ? (
              <div className="mb-3">
                <span className="field-label mb-2 block">Controls</span>
                <ControlGroups
                  params={params}
                  advanced={false}
                  disabled={disabled}
                  modifiedSet={modifiedSet}
                  onPatch={patchParams}
                  idPrefix={panelId}
                />
              </div>
            ) : null}

            {/* ── The one disclosure ─────────────────────────────────────────── */}
            {/*
              ══ EVERYTHING ELSE, BEHIND ONE PRESS ═══════════════════════════════════════════════════

              THE NUMBER IS `ADVANCED_COUNT` AND IS WRITTEN NOWHERE ELSE. A button reading "Show all 32
              controls" while seven of the thirty-two are already on screen promises 32 and reveals 25.
              `ADVANCED_COUNT` is `SLIDERS + TOGGLES + CHOICES` filtered on `!isEssential`, so it is the
              count of what this press actually reveals, by construction.

              AND THE SETTINGS ARE NOT ALL THAT IS IN HERE, so the line under the toggle says what else
              is — the take-away formats. A disclosure that names only part of what it holds is the same
              silence as a list that stops without saying so.

              NO HEIGHT ANIMATION, AND THAT IS A DECISION. The contents must survive being collapsed
              (see `advancedMounted`), which rules out an `AnimatePresence` height spring — that
              primitive animates a subtree in and out of existence. What is left is the chevron, whose
              `transition-transform` is CSS and is therefore already reached by both of this app's
              reduced-motion switches; there is no framer-written inline style here to have to branch
              on.
            */}
            {params ? (
              <div className="mb-3 rounded-md border border-line-200 bg-surface-50 p-3">
                <button
                  type="button"
                  id={advancedToggleId}
                  className="inline-flex items-center gap-1.5 text-xs font-medium text-purple-700 transition hover:text-purple-800"
                  onClick={toggleAdvanced}
                  aria-expanded={advancedOpen}
                  /* ONLY WHILE THE PANEL IS MOUNTED. Before the first press the id names nothing, and
                     pointing at a missing element is worse than not pointing. */
                  aria-controls={advancedMounted ? advancedId : undefined}
                >
                  <Sliders className="h-3.5 w-3.5" aria-hidden />
                  {advancedOpen
                    ? `Hide the other ${ADVANCED_COUNT} settings`
                    : `Show more options · ${ADVANCED_COUNT} settings` +
                      (hiddenModified.length > 0 ? ` · ${hiddenModified.length} changed` : "")}
                  <ChevronDown
                    className={
                      advancedOpen
                        ? "h-3.5 w-3.5 rotate-180 transition-transform"
                        : "h-3.5 w-3.5 transition-transform"
                    }
                    aria-hidden
                  />
                </button>

                {!advancedOpen ? (
                  <p className="mt-2 text-xs leading-5 text-ink-500">
                    Inside: the {ADVANCED_COUNT} settings that are not above, and the formats you can
                    download a copy in. Nothing in there is required — the trace runs on the settings shown
                    above.
                  </p>
                ) : null}

                {/* Progressive disclosure is only honest if what it hides can still announce itself.
                    "Not on screen" rather than "hidden", which is the better word on both clients:
                    "hidden" points a researcher at this one disclosure, and what is true everywhere is
                    that the control is not in front of them. The count is on the toggle as well,
                    because a researcher who has learned to skip a paragraph still reads the button they
                    are about to press. */}
                {!advancedOpen && hiddenModified.length > 0 ? (
                  <p className="mt-2 text-xs leading-5 text-amber-800">
                    {hiddenModified.length === 1
                      ? `One setting that is not on screen has moved: ${hiddenModified[0]}.`
                      : `${hiddenModified.length} settings that are not on screen have moved: ${hiddenModified.join(", ")}.`}
                  </p>
                ) : null}

                {advancedMounted ? (
                  <div
                    id={advancedId}
                    ref={advancedRef}
                    /* `tabIndex={-1}` makes it focusable by script and not by Tab — what a deliberate
                       focus move needs, and what a tab stop on a container would get wrong. See
                       `chooseFrame`: the press that asks for the frame chooser lands a reader inside
                       the region it just opened rather than leaving them above it. */
                    tabIndex={-1}
                    /*
                      `role="group"` IS WHAT MAKES `aria-labelledby` DO ANYTHING. A bare `<div>` has no
                      role, and an accessible name on an element with no role is dropped — so the
                      attribute would have been decoration that looks like accessibility work. With the
                      role, a reader arriving inside this region is told which press opened it, by the
                      toggle's own words, which is the same fact `aria-controls` gives from the other
                      side.
                    */
                    role="group"
                    aria-labelledby={advancedToggleId}
                    /*
                      `hidden` RATHER THAN UNMOUNTING. Tailwind's preflight makes it `display: none`,
                      which also takes the whole subtree out of the accessibility tree and out of the
                      tab order — so a collapsed section is genuinely closed to a keyboard and a screen
                      reader, while every control inside it keeps the state a researcher put there. See
                      `advancedMounted`.
                    */
                    hidden={!advancedOpen}
                    className="mt-3 grid gap-4 focus:outline-none focus-visible:ring-2 focus-visible:ring-purple-600/40"
                  >
                    {/*
                      `onEdited={setEdited}` IS THE WHOLE WIRING, and it is a bare setter deliberately.
                      That panel resets the frame in an effect whose dependency array contains this
                      callback, so an inline arrow function would give it a new identity on every render
                      and the effect would reset the frame, on a loop, forever. A `useState` setter is
                      stable for the life of the component — and an `EditedFrame` is an object rather
                      than a function, so React cannot read it as an updater.
                    */}
                    {pixels ? <FramePanel pixels={pixels} disabled={disabled} onEdited={setEdited} /> : null}

                    {/* The same five group headings as above, holding the controls that were not
                        essential. Two fieldsets can therefore carry one legend — "Edges" appears in
                        both — and that is right rather than confusing: the taxonomy is the pipeline's,
                        and splitting it by importance instead would mean a researcher looking for a
                        cleanup control had to know whether somebody had called it essential. */}
                    <ControlGroups
                      params={params}
                      advanced
                      disabled={disabled}
                      modifiedSet={modifiedSet}
                      onPatch={patchParams}
                      idPrefix={panelId}
                    />

                    {downloads}
                  </div>
                ) : null}
              </div>
            ) : null}

            {/* ── Format and attach ──────────────────────────────────────────── */}
            <div className="grid gap-1">
              <span className="field-label">Attach as</span>
              {/* TWO CHIPS, NOT FIVE, AND THE TABLE DECIDES WHICH. `ATTACHABLE_FORMATS` is
                  `EXPORT_FORMATS` filtered on `attachable`, so a format moves between this row and the
                  download row by one boolean and never by editing JSX in two places. Why the other
                  three are not here is argued in `traceExport.ts`'s header — a `.dxf` filed on a record
                  arrives typed IMAGE and renders as a broken picture. */}
              <div className="flex flex-wrap gap-2">
                {ATTACHABLE_FORMATS.map((entry) => (
                  <button
                    key={entry.id}
                    type="button"
                    className={
                      format === entry.id
                        ? "rounded-md border border-purple-600 bg-purple-50 px-3 py-1.5 text-xs font-medium text-purple-800"
                        : "rounded-md border border-line-200 bg-card px-3 py-1.5 text-xs font-medium text-ink-700 transition hover:border-purple-300"
                    }
                    onClick={() => setFormat(entry.id)}
                    aria-pressed={format === entry.id}
                    disabled={disabled}
                  >
                    {entry.label}
                  </button>
                ))}
              </div>
              <p className="text-xs text-ink-500">{ATTACHABLE_FORMATS.find((e) => e.id === format)?.hint}</p>
              {/* A CAP STATED WHERE IT BITES. The SVG carries the frame in its provenance note (see
                  `provenanceFor`); a PNG has no channel for it, so a cropped or sharpened trace filed
                  as a PNG records how it was made nowhere but on this screen. Skipped work is said out
                  loud, and this is skipped work inside a file rather than on a list. */}
              {format === "png" && edited !== null ? (
                <p className="text-xs leading-4 text-amber-800">
                  The frame you chose is written into the SVG&apos;s provenance note. A PNG has nowhere
                  to carry it, so a reviewer holding only the PNG cannot tell it was cropped or
                  sharpened.
                </p>
              ) : null}
            </div>

            {problem ? (
              <p
                role="alert"
                className="mt-3 flex items-start gap-2 rounded-md border border-red-200 bg-error-100 px-3 py-2 text-xs text-error-600"
              >
                <AlertTriangle className="mt-0.5 h-3.5 w-3.5 shrink-0" aria-hidden />
                <span>{problem}</span>
              </p>
            ) : null}

            <div className="mt-3 flex flex-wrap items-center gap-2">
              <button
                type="button"
                className="field-button"
                onClick={() => void attachTrace()}
                disabled={disabled || busy || result === null || source === null}
              >
                {running === "attach" ? (
                  <Loader2 className="h-4 w-4 animate-spin" aria-hidden />
                ) : (
                  <ImageIcon className="h-4 w-4" aria-hidden />
                )}
                Add the line art
              </button>
              {/* PROPERTY 4, AS A BUTTON. Declining is a real answer and costs one press: the panel
                  closes, nothing is handed to the host, and the image is exactly where it was. */}
              <button type="button" className="field-button-secondary" onClick={() => setOpen(false)} disabled={busy}>
                Keep the image as it is
              </button>
            </div>
            <p className="mt-2 text-xs leading-5 text-ink-500">
              Nothing is filed until “Add the line art” is pressed.{" "}
              {currentFileName
                ? `Pressing it would replace ${currentFileName}. `
                : ""}
              The image itself is never altered, whichever you choose. Closing this panel — with the ×
              above or “Collapse” below — files nothing.
            </p>
          </>
        ) : null}

        {/*
          ── THE COLLAPSE AT THE FOOT ───────────────────────────────────────────────────────────────

          This panel is tall — a result, a comparator, two preset pickers, the essential controls, five
          download buttons behind a disclosure and a row of attach buttons — so a researcher who has
          just pressed the last of them is at the BOTTOM of all of it, and a close control only in the
          header means scrolling back up past everything they have finished with to put it away.

          A REAL BUTTON WITH THE CARD'S NAME IN IT, not a bare "Close": at the foot of a panel this long
          there is no heading in view to say what would be closing. It closes by the same path the
          header × does — `setOpen(false)`, whose effect hands focus back to the trigger — so the two
          controls are one act rather than two that can disagree about what is open.
        */}
        <div className="mt-3">
          <button
            type="button"
            className="inline-flex items-center gap-1.5 text-xs font-medium text-ink-500 underline"
            onClick={() => setOpen(false)}
          >
            <ChevronUp className="h-3.5 w-3.5" aria-hidden />
            Collapse “{CARD_TITLE}”
          </button>
        </div>
      </div>
    </section>
  );

  /**
   * ONE WRAPPER AROUND BOTH STATES, AND THE LIVE REGION LIVES ON IT.
   *
   * The success sentence is set as the panel closes, which is the moment the whole panel subtree is
   * replaced by the trigger button. A live region mounted in that same commit is new DOM that already
   * carries text, and a reader announces a live region's CHANGES rather than its arrival — so the
   * sentence would be silent exactly when it matters. Keeping the region outside the open/closed
   * switch means it is in the document from the first render and only its contents ever change.
   */
  return (
    <div className="mt-3">
      {open ? panel : trigger}
      <div aria-live="polite" aria-atomic="true">
        {done ? (
          <p className="mt-2 flex items-start gap-2 text-xs text-ink-500">
            <Check className="mt-0.5 h-3.5 w-3.5 shrink-0 text-success-600" aria-hidden />
            <span>{done}</span>
          </p>
        ) : null}
      </div>
    </div>
  );
}

/* ────────────────────────────────────────────────────────────────────────────
 * Rows
 *
 * One component per control KIND rather than one hand-written block per control, for the reason the
 * parameter table itself gives: every control has to read its value, write a change, say whether it
 * differs from the preset and name itself to a screen reader, and a block per control is a chance per
 * control to forget the last two. (How many that would be is `PARAM_COUNT`, not a figure typed here.)
 * ──────────────────────────────────────────────────────────────────────────── */

type PatchFn = (patch: Parameters<typeof applyParamPatch>[1]) => void;

/**
 * The parameter table, drawn group by group — one half of it per call.
 *
 * ── ONE RENDERER FOR BOTH HALVES, WHICH IS WHAT KEEPS THEM ONE TABLE ────────────────────────────
 *
 * The panel draws the essential controls on the primary path and the rest inside the disclosure, and
 * the obvious way to write that is two blocks of JSX. Two blocks is two places to forget the
 * "you changed this" ring, the `aria-describedby`, or the `InertNote`. `advanced` is the ONLY
 * difference between the two calls.
 *
 * THE FILTER IS `isEssential` AND NOTHING ELSE, so the two halves are exhaustive and disjoint by
 * construction: every row in `SLIDERS`/`TOGGLES`/`CHOICES` is drawn exactly once, and a control added
 * to the table lands in one of the two without anybody choosing. That is the property the count on the
 * disclosure's button depends on — `ADVANCED_COUNT` counts the same predicate.
 *
 * A group with nothing in it renders nothing, so "Sharpening" does not appear twice with one empty
 * fieldset; a group with controls on both sides appears in both, under the same legend, because the
 * taxonomy is the pipeline's stages and not this panel's idea of importance.
 */
function ControlGroups({
  params,
  advanced,
  disabled,
  modifiedSet,
  onPatch,
  idPrefix
}: {
  params: TraceParams;
  advanced: boolean;
  disabled?: boolean;
  modifiedSet: ReadonlySet<string>;
  onPatch: PatchFn;
  idPrefix: string;
}) {
  return (
    <div className="grid gap-4">
      {PARAM_GROUPS.map((group) => {
        const wanted = (key: string) => (advanced ? !isEssential(key) : isEssential(key));
        const sliders = SLIDERS.filter((s) => s.group === group && wanted(s.key));
        const toggles = TOGGLES.filter((t) => t.group === group && wanted(t.key));
        const choices = CHOICES.filter((c) => c.group === group && wanted(c.key));
        if (sliders.length + toggles.length + choices.length === 0) return null;
        return (
          <fieldset key={group} className="rounded-md border border-line-200 bg-card p-3">
            <legend className="field-label px-1">{group}</legend>
            <div className="grid gap-3">
              {choices.map((spec) => (
                <ChoiceRow
                  key={spec.key}
                  spec={spec}
                  params={params}
                  disabled={disabled}
                  modified={modifiedSet.has(spec.label)}
                  onPatch={onPatch}
                  idPrefix={idPrefix}
                />
              ))}
              {sliders.map((spec) => (
                <SliderRow
                  key={spec.key}
                  spec={spec}
                  params={params}
                  disabled={disabled}
                  modified={modifiedSet.has(spec.label)}
                  onPatch={onPatch}
                  idPrefix={idPrefix}
                />
              ))}
              {toggles.map((spec) => (
                <ToggleRow
                  key={spec.key}
                  spec={spec}
                  params={params}
                  disabled={disabled}
                  modified={modifiedSet.has(spec.label)}
                  onPatch={onPatch}
                  idPrefix={idPrefix}
                />
              ))}
            </div>
          </fieldset>
        );
      })}
    </div>
  );
}

/** The ring that says "you changed this". A ring and not only a colour — colour never carries meaning alone. */
const MODIFIED_RING = "rounded-md ring-2 ring-purple-600/15";

function SliderRow({
  spec,
  params,
  disabled,
  modified,
  onPatch,
  idPrefix
}: {
  spec: SliderSpec;
  params: TraceParams;
  disabled?: boolean;
  modified: boolean;
  onPatch: PatchFn;
  idPrefix: string;
}) {
  const id = `${idPrefix}-${spec.key}`;
  const value = spec.read(params);
  return (
    <div className={modified ? `${MODIFIED_RING} p-1` : "p-1"}>
      <div className="flex items-baseline justify-between gap-2">
        <label className="text-xs font-medium text-ink-900" htmlFor={id}>
          {spec.label}
          {modified ? <span className="ml-1 text-purple-700">·</span> : null}
        </label>
        <output className="text-xs tabular-nums text-ink-500" htmlFor={id}>
          {formatValue(value, spec.step)}
        </output>
      </div>
      <input
        id={id}
        type="range"
        className="mt-1 w-full accent-purple-700"
        min={spec.min}
        max={spec.max}
        step={spec.step}
        value={value}
        disabled={disabled}
        aria-describedby={`${id}-hint`}
        onChange={(event) => onPatch(spec.patch(Number(event.target.value)))}
      />
      <p id={`${id}-hint`} className="mt-0.5 text-xs leading-4 text-ink-500">
        {spec.hint}
      </p>
      <InertNote reason={inactiveReason(spec.key, params)} />
    </div>
  );
}

function ToggleRow({
  spec,
  params,
  disabled,
  modified,
  onPatch,
  idPrefix
}: {
  spec: ToggleSpec;
  params: TraceParams;
  disabled?: boolean;
  modified: boolean;
  onPatch: PatchFn;
  idPrefix: string;
}) {
  const id = `${idPrefix}-${spec.key}`;
  const value = spec.read(params);
  return (
    <div className={modified ? `${MODIFIED_RING} p-1` : "p-1"}>
      <div className="flex items-start gap-2">
        <input
          id={id}
          type="checkbox"
          className="mt-0.5 h-4 w-4 shrink-0 accent-purple-700"
          checked={value}
          disabled={disabled}
          aria-describedby={`${id}-hint`}
          onChange={(event) => onPatch(spec.patch(event.target.checked))}
        />
        <div className="min-w-0">
          <label className="text-xs font-medium text-ink-900" htmlFor={id}>
            {spec.label}
            {modified ? <span className="ml-1 text-purple-700">·</span> : null}
          </label>
          <p id={`${id}-hint`} className="text-xs leading-4 text-ink-500">
            {spec.hint}
          </p>
          <InertNote reason={inactiveReason(spec.key, params)} />
        </div>
      </div>
    </div>
  );
}

function ChoiceRow({
  spec,
  params,
  disabled,
  modified,
  onPatch,
  idPrefix
}: {
  spec: ChoiceSpec;
  params: TraceParams;
  disabled?: boolean;
  modified: boolean;
  onPatch: PatchFn;
  idPrefix: string;
}) {
  const id = `${idPrefix}-${spec.key}`;
  const value = spec.read(params);
  return (
    <div className={modified ? `${MODIFIED_RING} p-1` : "p-1"}>
      {/*
        ── DELIBERATELY A NATIVE <select>, UNLIKE THE STYLE AND SUBJECT PICKERS ABOVE ──
        Two reasons, and neither is inertia. First, every one of these lists is a per-parameter enum of
        two to five values declared in `traceParamTable` — which is exactly the fixed vocabulary a
        filter box makes worse: an extra tab stop and a "No matches" state over a list read at a
        glance. `SearchableSelect`'s own threshold comment says the same thing about closed
        vocabularies.

        Second, and this is the part that would not be recoverable: a `<label htmlFor>` and an
        `aria-describedby` are wired to this control by id, and the themed dropdown renders a
        `<button>` and accepts no id, no ref and no `describedBy`. Converting would trade a correctly
        named and described field for a filter box nobody needs, on a panel that renders up to
        `PARAM_COUNT` of these at once.
      */}
      <label className="text-xs font-medium text-ink-900" htmlFor={id}>
        {spec.label}
        {modified ? <span className="ml-1 text-purple-700">·</span> : null}
      </label>
      <select
        id={id}
        className="field-input mt-1"
        value={value}
        disabled={disabled}
        aria-describedby={`${id}-hint`}
        onChange={(event) => onPatch(spec.patch(event.target.value))}
      >
        {spec.options.map((option) => (
          <option key={option.value} value={option.value}>
            {option.label}
          </option>
        ))}
      </select>
      <p id={`${id}-hint`} className="mt-0.5 text-xs leading-4 text-ink-500">
        {spec.hint}
      </p>
      <InertNote reason={inactiveReason(spec.key, params)} />
    </div>
  );
}

/**
 * "This control is doing nothing under your current settings", when it is.
 *
 * ── A SENTENCE, NEVER A DISABLED ROW ──────────────────────────────────────────────────────────
 *
 * `inactiveReason` reads the condition in `engine/pipeline.ts` that makes the claim true, and the
 * trap it exists for is not hypothetical: the MEDIAN noise filter reads a radius this panel does not
 * expose and never reads "Noise reduction" at all, and MEDIAN is what the `sketch` subject selects.
 * So a researcher could drag that slider for a minute on the commonest configuration this panel has
 * and conclude the trace was broken.
 *
 * The row stays writable, because greying it out would stop somebody setting a value for the
 * configuration they are about to switch to — which is exactly what comparing two edge engines is.
 *
 * NOT A LIVE REGION. It changes as a consequence of a control the researcher just operated, in the
 * same commit, so a reader who moved the switch is told by the switch; announcing it as well would
 * talk over them. It is inside the row and reached in the ordinary way.
 */
function InertNote({ reason }: { reason: string | null }) {
  if (reason === null) return null;
  return <p className="mt-0.5 text-xs leading-4 text-amber-800">{reason}</p>;
}

/** Re-exported so a host can name the essentials without importing the table. */
export { ESSENTIAL_KEYS };
