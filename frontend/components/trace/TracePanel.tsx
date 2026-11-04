"use client";

import { ProgressBar } from "@/components/ui/ProgressBar";
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
import { Toggle } from "@/components/ui/Toggle";
import { SearchableSelect } from "@/components/ui/SearchableSelect";
import { ButtonRow } from "@/components/ui/ButtonRow";

const PREVIEW_BOX_PX = 420;

const RETRACE_DEBOUNCE_MS = 220;

export type AttachAnswer = void | boolean | Promise<void | boolean>;

export interface TracePanelProps {
  image: File | Blob | string | null;
  imageName?: string;
  currentFileName?: string | null;
  onAttach: (file: File) => AttachAnswer;
  disabled?: boolean;
}

type Phase =
  | { status: "idle" }
  | { status: "loading" }
  | { status: "ready" }
  | { status: "unavailable"; reason: string };

type FullRun = "attach" | `download-${ExportFormatId}`;

const COMPARE_START_POSITION = 0;

type CompareMode = "drawing" | "wipe" | "photograph" | "difference";

const COMPARE_MODES: readonly { readonly id: CompareMode; readonly label: string }[] = [
  { id: "drawing", label: "Drawing" },
  { id: "wipe", label: "Wipe" },
  { id: "photograph", label: "Photograph" },
  { id: "difference", label: COMPARISON_DIFFERENCE_BADGE }
];

const COMPARE_MAX_ZOOM = 6;

const CARD_TITLE = "Trace this image into line art";

export function TracePanel({ image, imageName, currentFileName, onAttach, disabled }: TracePanelProps) {
  const panelId = useId();
  const advancedId = `${panelId}-advanced`;
  const advancedToggleId = `${panelId}-advanced-toggle`;
  const [open, setOpen] = useState(false);
  const [phase, setPhase] = useState<Phase>({ status: "idle" });

  const [runtime, setRuntime] = useState<TraceRuntime | null>(null);
  const [styles, setStyles] = useState<readonly StyleChoice[]>([]);
  const [styleGroups, setStyleGroups] = useState<readonly string[]>([]);
  const [subjects, setSubjects] = useState<readonly SubjectChoice[]>([]);

  const [source, setSource] = useState<{ readonly blob: Blob; readonly name: string } | null>(null);
  const [pixels, setPixels] = useState<DecodedPixels | null>(null);
  const [edited, setEdited] = useState<EditedFrame | null>(null);

  const [params, setParams] = useState<TraceParams | null>(null);
  const [presetParams, setPresetParams] = useState<TraceParams | null>(null);
  const [styleId, setStyleId] = useState<string>("");
  const [subjectId, setSubjectId] = useState<string>("");

  const [result, setResult] = useState<SerializedTraceResult | null>(null);
  const [tracing, setTracing] = useState(false);
  const [progress, setProgress] = useState<string | null>(null);
  const [progressAt, setProgressAt] = useState<number | null>(null);
  const [weights, setWeights] = useState<ProgressWeights>(UNWEIGHTED);
  const [stopping, setStopping] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [done, setDone] = useState<string | null>(null);
  const [advancedOpen, setAdvancedOpen] = useState(false);
  const [advancedMounted, setAdvancedMounted] = useState(false);
  const [format, setFormat] = useState<AttachFormatId>("svg");
  const [running, setRunning] = useState<FullRun | null>(null);
  const [saved, setSaved] = useState<string | null>(null);
  const [compare, setCompare] = useState<{
    readonly traceUrl: string;
    readonly originalUrl: string;
    readonly traceBlob: Blob;
    readonly originalBlob: Blob;
    readonly width: number;
    readonly height: number;
    readonly reduced: boolean;
  } | null>(null);
  const [compareProblem, setCompareProblem] = useState<string | null>(null);
  const [compareMode, setCompareMode] = useState<CompareMode>("wipe");
  const [comparePosition, setComparePosition] = useState(COMPARE_START_POSITION);
  const [difference, setDifference] = useState<string | null>(null);
  const [differenceProblem, setDifferenceProblem] = useState<string | null>(null);
  const [differenceBusy, setDifferenceBusy] = useState(false);

  const tracerRef = useRef<Tracer | null>(null);
  const weightsRef = useRef<ProgressWeights>(UNWEIGHTED);
  const abortRef = useRef<AbortController | null>(null);
  const canvasRef = useRef<HTMLCanvasElement | null>(null);

  const startedRef = useRef(false);
  const goneRef = useRef(false);
  const retraceTimerRef = useRef<number | null>(null);
  const pickRef = useRef(0);
  const adoptedRef = useRef<File | Blob | string | null | undefined>(undefined);
  const resolvedRef = useRef<File | Blob | string | null | undefined>(undefined);
  const fullRunRef = useRef(false);
  const compareUrlsRef = useRef<readonly string[]>([]);
  const triggerRef = useRef<HTMLButtonElement | null>(null);
  const headingRef = useRef<HTMLHeadingElement | null>(null);
  const wasOpenRef = useRef(false);
  const advancedRef = useRef<HTMLDivElement | null>(null);
  const focusAdvancedRef = useRef(false);

  useEffect(() => {
    if (!open || startedRef.current) return;
    startedRef.current = true;
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
        setStyleId(loaded.defaults.styleId);
        setPhase({ status: "ready" });
        const presets = await loadTracePresets();
        if (goneRef.current) return;
        setStyles(presets.styles);
        setStyleGroups(presets.styleGroups);
        setSubjects(presets.subjects);
      } catch (error) {
        if (goneRef.current) return;
        if (runtimeUp) {
          setNotice("Presets didn’t load. Individual controls still work.");
          return;
        }
        setPhase({
          status: "unavailable",
          reason:
            error instanceof Error && error.message
              ? error.message
              : "Tracing engine didn’t load. Reload the page."
        });
      }
    })();
  }, [open]);

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

  useEffect(() => {
    if (!advancedOpen || !focusAdvancedRef.current) return;
    focusAdvancedRef.current = false;
    advancedRef.current?.focus();
  }, [advancedOpen]);

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

  const adoptImage = useCallback(() => {
    pickRef.current += 1;
    abortRef.current?.abort();
    setProblem(null);
    setDone(null);
    setSaved(null);
    setResult(null);
    setSource(null);
    setPixels(null);
    setEdited(null);
    resolvedRef.current = undefined;
    return pickRef.current;
  }, []);

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
  }, [adoptImage, image, imageName, open]);

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

  const runTrace = useCallback(
    async (preview: boolean): Promise<SerializedTraceResult | null> => {
      if (runtime === null || traceSource === null || params === null) return null;
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
          image: runtime.transferableFrom(traceSource),
          params,
          preview,
          signal: controller.signal,
          onProgress: (p) => {
            setProgress(traceProgressSentence(p.stageId, p.label));
            setProgressAt(fractionAt(weightsRef.current, p.stageId, p.fraction));
          }
        });
        if (pick !== pickRef.current || goneRef.current) return null;
        setResult(answer);
        const learned = progressWeights(answer.stages);
        weightsRef.current = learned;
        setWeights(learned);
        return answer;
      } catch (error) {
        if (runtime.isCancelled(error)) return null;
        if (runtime.isUnavailable(error)) {
          setPhase({ status: "unavailable", reason: (error as Error).message });
          return null;
        }
        if (pick !== pickRef.current || goneRef.current) return null;
        setProblem(
          error instanceof Error && error.message
            ? error.message
            : "Couldn’t trace this image. Try a lower resolution."
        );
        return null;
      } finally {
        if (abortRef.current === controller) {
          setTracing(false);
          setProgress(null);
          setProgressAt(null);
          setStopping(false);
        }
      }
    },
    [runtime, traceSource, params]
  );

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
  }, [runtime, traceSource, params, runTrace]);

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
    paintGeometry(context, svgInput, scale);
  }, [svgInput]);

  const installCompareUrls = useCallback((urls: readonly string[]) => {
    for (const url of compareUrlsRef.current) URL.revokeObjectURL(url);
    compareUrlsRef.current = urls;
  }, []);

  const addCompareUrl = useCallback((url: string) => {
    compareUrlsRef.current = [...compareUrlsRef.current, url];
  }, []);

  const forgetDifference = useCallback(() => {
    setDifference(null);
    setDifferenceProblem(null);
    setDifferenceBusy(false);
  }, []);

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
      const outcome = await buildComparisonPlates(traceSource, svgInput, {
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
      setParamsFrom(preset.params, `The “${preset.name}” style`, preset.params);
    },
    [styles, setParamsFrom]
  );

  const applySubject = useCallback(
    (id: string) => {
      const preset = subjects.find((s) => s.id === id);
      if (!preset || params === null) return;
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

  const suggestedStyle = useMemo(() => {
    const id = result?.profile?.suggestion ?? "";
    if (id.length === 0 || id === styleId) return null;
    return styles.find((style) => style.id === id) ?? null;
  }, [result, styleId, styles]);

  const comparisonStatus = comparisonStatusFor({
    haveCompare: compare !== null,
    compareProblem,
    tracing,
    progress,
    traceProblem: problem,
    haveResult: result !== null
  });

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

  function toggleAdvanced() {
    setAdvancedMounted(true);
    setAdvancedOpen((value) => !value);
  }

  function chooseFrame() {
    if (advancedOpen) {
      setAdvancedOpen(false);
      return;
    }
    focusAdvancedRef.current = true;
    setAdvancedMounted(true);
    setAdvancedOpen(true);
  }

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

  function provenanceFor(latest: SerializedTraceResult, sourceName: string): string {
    return (
      `Traced on the device from ${sourceName} by Field Repository. ` +
      `${latest.shapeCount} paths, ${latest.nodeCount} nodes.` +
      (edited !== null && edited.note.length > 0 ? ` ${edited.note}` : "")
    );
  }

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
    const pick = pickRef.current;
    beginFullRun("attach");
    try {
      const latest = await runTrace(false);
      if (pick !== pickRef.current || goneRef.current) return;
      if (latest === null) {
        setProblem("The trace didn’t finish.");
        return;
      }
      const input = inputFrom(latest);
      const note = provenanceFor(latest, source.name);
      const outcome =
        format === "svg"
          ? exportSvgFile(input, source.name, note)
          : await exportPngFile(input, source.name);
      if (pick !== pickRef.current || goneRef.current) return;
      if (!isExported(outcome)) {
        setProblem(outcome.reason);
        return;
      }
      if ((await onAttach(outcome.file)) === false) return;
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

  async function downloadDerived(what: ExportFormatId) {
    if (svgInput === null || source === null || runtime === null || params === null) return;
    const pick = pickRef.current;
    beginFullRun(`download-${what}`);
    setSaved(null);
    try {
      const latest = await runTrace(false);
      if (pick !== pickRef.current || goneRef.current) return;
      if (latest === null) {
        setProblem("The trace didn’t finish.");
        return;
      }
      const input = inputFrom(latest);
      const outcome =
        what === "svg"
          ? exportSvgFile(input, source.name, provenanceFor(latest, source.name), { suffix: TRACE_SUFFIX })
          : what === "png"
            ? await exportPngFile(input, source.name, PNG_MAX_EDGE_PX, { suffix: RENDER_SUFFIX })
            : await exportVectorFile(what, input, source.name, provenanceFor(latest, source.name), {
                suffix: TRACE_SUFFIX
              });
      if (pick !== pickRef.current || goneRef.current) return;
      if (!isExported(outcome)) {
        setProblem(outcome.reason);
        return;
      }
      saveBlobToDisk(outcome.file, outcome.file.name);
      setSaved(
        `${outcome.file.name} saved to this device.` +
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

  const busy = running !== null;

  const CARD_DESCRIPTION =
    "Traced on this device. The original image is never changed.";

  if (phase.status === "unavailable") {
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
        >
          <Wand2 className="mt-0.5 h-4 w-4 shrink-0 text-ink-500" aria-hidden />
          <span className="min-w-0 flex-1">
            <span className="block text-sm font-medium text-ink-900">{CARD_TITLE}</span>
            <span className="mt-0.5 block text-xs leading-5 text-ink-500">{CARD_DESCRIPTION}</span>
          </span>
          <ChevronDown className="mt-0.5 h-4 w-4 shrink-0 text-ink-500 transition-transform" aria-hidden />
        </button>
      </h4>
    </section>
  );

  const downloads = (
    <div className="mt-4 border-t border-line-200 pt-3">
      <span className="field-label">Download a copy to this device</span>
      <ButtonRow align="start" className="mt-1">
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
      </ButtonRow>
      <ul className="mt-2 grid gap-1 text-xs leading-5 text-ink-500">
        {EXPORT_FORMATS.map((entry) => (
          <li key={entry.id}>
            <span className="font-medium text-ink-700">{entry.label}</span> — {entry.hint}
          </li>
        ))}
      </ul>
      <p className="mt-2 max-w-prose text-xs leading-5 text-ink-500">
        Full-resolution copies for you — not saved to the record. Closing this panel discards the trace.
      </p>
      {edited !== null ? (
        <p className="mt-2 max-w-prose text-xs leading-5 text-amber-800 dark:text-amber-200">
          PNG and DXF don&apos;t record the crop or sharpening.
        </p>
      ) : null}
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
      <div className="flex items-start gap-2 p-3">
        <Wand2 className="mt-0.5 h-4 w-4 shrink-0 text-ink-500" aria-hidden />
        <div className="min-w-0 flex-1">
          <h4
            ref={headingRef}
            tabIndex={-1}
            className="text-sm font-medium text-ink-900 focus:outline-hidden focus-visible:ring-2 focus-visible:ring-purple-600/40"
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
                    {` Images over ${DECODE_MAX_EDGE_PX}px are reduced for tracing.`}
                  </>
                ) : (
                  <span className="flex items-start gap-2">
                    <ImageIcon className="mt-0.5 h-3.5 w-3.5 shrink-0 text-ink-500" aria-hidden />
                    <span>
                      Choose an image to trace.
                    </span>
                  </span>
                )}
              </p>
            </div>

            {pixels ? (
              <div className="mb-3 flex flex-wrap items-start gap-3 rounded-md border border-line-200 bg-card p-3">
                <span className="mt-0.5 grid h-8 w-8 shrink-0 place-items-center rounded-lg bg-field-200 text-field-600 dark:text-purple-300">
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

            {pixels ? (
              <div className="mb-3 rounded-md border border-line-200 bg-card p-3">
                <div className="flex items-center justify-between gap-2">
                  <span className="field-label">Traced result</span>
                  <span aria-live="polite" className="flex items-center gap-1.5 text-xs text-ink-500">
                    {tracing ? (
                      <>
                        <Loader2 className="h-3 w-3 animate-spin" aria-hidden />
                        {stopping ? "Stopping…" : (progress ?? "Tracing…")}
                      </>
                    ) : null}
                  </span>
                </div>
                {tracing && progressAt !== null ? (
                  <div className="mt-2 grid gap-1">
                    <ProgressBar
                      percent={Math.round(progressAt * 100)}
                      className="h-1"
                      fillClassName="transition-[width] duration-200"
                    />
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
                    Preview size. Saving uses full resolution.
                  </p>
                ) : null}
              </div>
            ) : null}

            {pixels ? (
              <div className="mb-3 rounded-md border border-line-200 bg-card p-3">
                <div className="flex items-center justify-between gap-2">
                  <span className="field-label">The trace against the image</span>
                  <span aria-live="polite" className="text-xs text-ink-500">
                    {comparisonStatus}
                  </span>
                </div>
                {compare ? (
                  <>
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
                      position={
                        compareMode === "drawing" ? 0 : compareMode === "photograph" ? 100 : comparePosition
                      }
                      onPosition={(next) => {
                        setComparePosition(next);
                        setCompareMode("wipe");
                      }}
                      initialPosition={COMPARE_START_POSITION}
                      ariaLabel="Traced drawing against the image"
                      maxZoom={COMPARE_MAX_ZOOM}
                      peekHoldMs={REVEAL_PEEK_HOLD_MS}
                      soloImage={
                        compareMode === "difference" && difference !== null
                          ? {
                              src: difference,
                              alt: COMPARISON_DIFFERENCE_ALT,
                              label: COMPARISON_DIFFERENCE_BADGE
                            }
                          : null
                      }
                      aspectRatio={compare.width / compare.height}
                    />
                    <p className="mt-2 text-xs leading-5 text-ink-500">
                      Drag the handle to compare. Hold to see the image. Ctrl/⌘ + scroll to zoom (up to{" "}
                      {COMPARE_MAX_ZOOM}×).
                      {compare.reduced
                        ? ` Both pictures here are ${compare.width}x${compare.height}, reduced from ${
                            result ? `${Math.round(result.width)}x${Math.round(result.height)}` : "the traced size"
                          } for the comparison only.`
                        : ""}
                    </p>
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

            {styles.length > 0 ? (
              <div className="mb-3 grid gap-3 sm:grid-cols-2">
                <div className="grid gap-1">
                  <span className="field-label">Style</span>
                  <Dropdown
                    value={styleId}
                    onChange={pickStyle}
                    disabled={disabled}
                    ariaLabel="Style"
                    searchable
                    advanceOnSelect={false}
                    options={styleGroups.flatMap((group) =>
                      styles
                        .filter((style) => style.group === group)
                        .map((style) => ({ value: style.id, label: `${group} · ${style.name}` }))
                    )}
                  />
                  <p className="text-xs text-ink-500">
                    {styles.find((s) => s.id === styleId)?.description ??
                      "A style sets every control at once. Pick one, then adjust."}
                  </p>
                </div>

                <div className="grid gap-1">
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
                    options={[
                      { value: "", label: "Choose a material" },
                      ...subjects.map((subject) => ({ value: subject.id, label: subject.name }))
                    ]}
                  />
                  <p className="text-xs text-ink-500">
                    {subjects.find((subject) => subject.id === subjectId)?.hint ??
                      "Tunes the settings for the material."}
                  </p>
                </div>
              </div>
            ) : null}

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

            {params ? (
              <div className="mb-3 rounded-md border border-line-200 bg-surface-50 p-3">
                <button
                  type="button"
                  id={advancedToggleId}
                  className="inline-flex items-center gap-1.5 text-xs font-medium text-purple-700 dark:text-purple-300 transition hover:text-purple-800"
                  onClick={toggleAdvanced}
                  aria-expanded={advancedOpen}
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
                    {ADVANCED_COUNT} more settings and download formats. Optional.
                  </p>
                ) : null}

                {!advancedOpen && hiddenModified.length > 0 ? (
                  <p className="mt-2 text-xs leading-5 text-amber-800 dark:text-amber-200">
                    {hiddenModified.length === 1
                      ? `One setting that is not on screen has moved: ${hiddenModified[0]}.`
                      : `${hiddenModified.length} settings that are not on screen have moved: ${hiddenModified.join(", ")}.`}
                  </p>
                ) : null}

                {advancedMounted ? (
                  <div
                    id={advancedId}
                    ref={advancedRef}
                    tabIndex={-1}
                    role="group"
                    aria-labelledby={advancedToggleId}
                    hidden={!advancedOpen}
                    className="mt-3 grid gap-4 focus:outline-hidden focus-visible:ring-2 focus-visible:ring-purple-600/40"
                  >
                    {pixels ? <FramePanel pixels={pixels} disabled={disabled} onEdited={setEdited} /> : null}

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

            <div className="grid gap-1">
              <span className="field-label">Attach as</span>
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
              {format === "png" && edited !== null ? (
                <p className="text-xs leading-4 text-amber-800">
                  A PNG doesn’t record the crop or sharpening — the SVG does.
                </p>
              ) : null}
            </div>

            {problem ? (
              <p
                role="alert"
                className="mt-3 flex items-start gap-2 rounded-md border border-red-200 bg-error-100 px-3 py-2 text-xs text-red-800"
              >
                <AlertTriangle className="mt-0.5 h-3.5 w-3.5 shrink-0" aria-hidden />
                <span>{problem}</span>
              </p>
            ) : null}

            <ButtonRow align="start" className="mt-3">
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
              <button type="button" className="field-button-secondary" onClick={() => setOpen(false)} disabled={busy}>
                Keep the image as it is
              </button>
            </ButtonRow>
            <p className="mt-2 text-xs leading-5 text-ink-500">
              Nothing is saved until “Add the line art”.{" "}
              {currentFileName ? `It replaces ${currentFileName}.` : ""}
            </p>
          </>
        ) : null}

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

type PatchFn = (patch: Parameters<typeof applyParamPatch>[1]) => void;

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
          {modified ? <span className="ml-1 text-purple-700 dark:text-purple-300">·</span> : null}
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
      <Toggle
        id={id}
        label={modified ? `${spec.label} ·` : spec.label}
        description={spec.hint}
        checked={value}
        disabled={disabled}
        onChange={(next) => onPatch(spec.patch(next))}
      />
      <InertNote reason={inactiveReason(spec.key, params)} />
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
      <label className="text-xs font-medium text-ink-900" htmlFor={id}>
        {spec.label}
        {modified ? <span className="ml-1 text-purple-700 dark:text-purple-300">·</span> : null}
      </label>
      <SearchableSelect
        id={id}
        className="mt-1"
        value={value}
        disabled={disabled}
        describedBy={`${id}-hint`}
        advanceOnSelect={false}
        options={spec.options.map((option) => ({ value: option.value, label: option.label }))}
        onChange={(next) => onPatch(spec.patch(next))}
      />
      <p id={`${id}-hint`} className="mt-0.5 text-xs leading-4 text-ink-500">
        {spec.hint}
      </p>
      <InertNote reason={inactiveReason(spec.key, params)} />
    </div>
  );
}

function InertNote({ reason }: { reason: string | null }) {
  if (reason === null) return null;
  return <p className="mt-0.5 text-xs leading-4 text-amber-800 dark:text-amber-200">{reason}</p>;
}

export { ESSENTIAL_KEYS };
