"use client";

import { useCallback, useEffect, useId, useMemo, useRef, useState } from "react";
import { AlertTriangle, Check, Crop, Loader2, Sparkles } from "lucide-react";

import { resampleRgbaInBands } from "./comparisonPlates";
import type { DecodedPixels } from "./decodeToPixels";
import {
  FRAME_PRESETS,
  clampCropRect,
  cropRectFromPoints,
  isWholeCropRect,
  matchingPresetId,
  moveCropBy,
  moveCropCorner,
  nudgeStepFor,
  presetCropRect,
  sourcePerDisplayPixel,
  type FrameCorner
} from "./frameGeometry";
import {
  loadImageEditor,
  type CropRect,
  type ImageEditor,
  type SharpenSettings
} from "./imageEditRuntime";
import { ButtonRow } from "@/components/ui/ButtonRow";

const PREVIEW_BOX_PX = 360;

const SHARPEN_AMOUNT_MAX = 5;
const SHARPEN_RADIUS_MIN = 0.3;
const SHARPEN_RADIUS_MAX = 8;
const SHARPEN_THRESHOLD_MAX = 0.2;
const SHARPEN_MAX_PIXELS = 8_000_000;
const CROP_MIN_EDGE_PX = 16;

const NO_SHARPEN: SharpenSettings = { amount: 0, radius: 1.5, threshold: 0 };

export interface EditedFrame {
  readonly data: Uint8ClampedArray;
  readonly width: number;
  readonly height: number;
  readonly crop: CropRect;
  readonly sharpen: SharpenSettings;
  readonly note: string;
  readonly millis: number;
}

export interface FramePanelProps {
  pixels: DecodedPixels;
  disabled?: boolean;
  onEdited: (frame: EditedFrame | null) => void;
}

type DragMode = FrameCorner | "move" | "draw";

interface Drag {
  readonly pointerId: number;
  readonly mode: DragMode;
  readonly startX: number;
  readonly startY: number;
  readonly start: CropRect;
  readonly originX: number;
  readonly originY: number;
  readonly perPxX: number;
  readonly perPxY: number;
}

const DRAW_SLOP_PX = 3;

export function FramePanel({ pixels, disabled, onEdited }: FramePanelProps) {
  const fieldId = useId();
  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  const pictureRef = useRef<HTMLDivElement | null>(null);
  const editorRef = useRef<ImageEditor | null>(null);
  const goneRef = useRef(false);

  const whole = useMemo<CropRect>(
    () => ({ x: 0, y: 0, width: pixels.width, height: pixels.height }),
    [pixels]
  );

  const [crop, setCrop] = useState<CropRect>(whole);
  const [draft, setDraft] = useState<{ key: keyof CropRect; text: string } | null>(null);
  const [clampNote, setClampNote] = useState<string | null>(null);
  const [sharpen, setSharpen] = useState<SharpenSettings>(NO_SHARPEN);
  const [applied, setApplied] = useState<{ crop: CropRect; sharpen: SharpenSettings; millis: number } | null>(null);
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  const [drag, setDrag] = useState<Drag | null>(null);
  const [shownWidth, setShownWidth] = useState(0);

  useEffect(() => {
    setCrop(whole);
    setDraft(null);
    setClampNote(null);
    setSharpen(NO_SHARPEN);
    setApplied(null);
    setProblem(null);
    onEdited(null);
  }, [whole, onEdited]);

  useEffect(() => {
    return () => {
      goneRef.current = true;
      editorRef.current?.dispose();
      editorRef.current = null;
    };
  }, []);

  const scale = Math.min(1, PREVIEW_BOX_PX / Math.max(pixels.width, pixels.height));
  const boxWidth = Math.max(1, Math.round(pixels.width * scale));
  const boxHeight = Math.max(1, Math.round(pixels.height * scale));

  const nudgeStep = nudgeStepFor(pixels.width, shownWidth > 0 ? shownWidth : boxWidth);

  useEffect(() => {
    const node = pictureRef.current;
    if (node === null) return;
    const read = () => {
      const width = node.getBoundingClientRect().width;
      if (width > 0) setShownWidth(width);
    };
    read();
    if (typeof ResizeObserver === "undefined") return;
    const observer = new ResizeObserver(read);
    observer.observe(node);
    return () => observer.disconnect();
  }, [pixels]);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (canvas === null) return;
    canvas.width = boxWidth;
    canvas.height = boxHeight;
    if (canvas.getContext("2d") === null) return;
    let cancelled = false;
    void (async () => {
      const small = await resampleRgbaInBands(
        pixels.data,
        pixels.width,
        pixels.height,
        boxWidth,
        boxHeight,
        () => cancelled || goneRef.current
      );
      if (small === null || cancelled || goneRef.current) return;
      const surface = canvasRef.current;
      if (surface === null || surface.width !== boxWidth || surface.height !== boxHeight) return;
      const target = surface.getContext("2d");
      if (target === null) return;
      const plate = target.createImageData(boxWidth, boxHeight);
      plate.data.set(small);
      target.clearRect(0, 0, boxWidth, boxHeight);
      target.putImageData(plate, 0, 0);
    })();
    return () => {
      cancelled = true;
    };
  }, [pixels, boxWidth, boxHeight]);

  const clamp = useCallback(
    (rect: CropRect): CropRect => clampCropRect(rect, pixels, CROP_MIN_EDGE_PX),
    [pixels]
  );

  const commitDraft = useCallback(
    (key: keyof CropRect, text: string) => {
      setDraft(null);
      const typed = Math.round(Number(text));
      if (text.trim() === "" || !Number.isFinite(typed)) {
        setClampNote(null);
        return;
      }
      const next = clamp({ ...crop, [key]: typed });
      setCrop(next);
      if (next[key] === typed) {
        setClampNote(null);
        return;
      }
      const minEdge =
        key === "width" ? Math.min(CROP_MIN_EDGE_PX, pixels.width) : Math.min(CROP_MIN_EDGE_PX, pixels.height);
      setClampNote(
        key === "x" || key === "y"
          ? `${CROP_FIELD_NAME[key]} cannot be ${typed}: the frame is ${
              key === "x" ? `${next.width} wide on a ${pixels.width}px` : `${next.height} tall on a ${pixels.height}px`
            } photo. Set to ${next[key]}; reduce ${key === "x" ? "Width" : "Height"} first.`
          : `${CROP_FIELD_NAME[key]} cannot be ${typed}: the frame is between ${minEdge} and ${
              key === "width" ? pixels.width : pixels.height
            } px. Set to ${next[key]}.`
      );
    },
    [clamp, crop, pixels.width, pixels.height]
  );

  const typeInto = useCallback(
    (key: keyof CropRect, text: string) => {
      const typed = Number(text);
      if (text.trim() !== "" && Number.isInteger(typed)) {
        const next = clamp({ ...crop, [key]: typed });
        if (next[key] === typed) {
          setDraft(null);
          setClampNote(null);
          setCrop(next);
          return;
        }
      }
      setDraft({ key, text });
    },
    [clamp, crop]
  );

  const measurePicture = useCallback((): { originX: number; originY: number; perPxX: number; perPxY: number } => {
    const box = pictureRef.current?.getBoundingClientRect();
    const drawnW = box && box.width > 0 ? box.width : boxWidth;
    const drawnH = box && box.height > 0 ? box.height : boxHeight;
    return {
      originX: box ? box.left : 0,
      originY: box ? box.top : 0,
      perPxX: sourcePerDisplayPixel(pixels.width, drawnW),
      perPxY: sourcePerDisplayPixel(pixels.height, drawnH)
    };
  }, [boxWidth, boxHeight, pixels.width, pixels.height]);

  function beginDrag(event: React.PointerEvent<HTMLElement>, mode: DragMode) {
    if (disabled || event.button !== 0) return;
    event.currentTarget.setPointerCapture(event.pointerId);
    event.preventDefault();
    if (mode !== "move" && mode !== "draw") (event.currentTarget as HTMLElement).focus();
    setDrag({
      pointerId: event.pointerId,
      mode,
      startX: event.clientX,
      startY: event.clientY,
      start: crop,
      ...measurePicture()
    });
  }

  function continueDrag(event: React.PointerEvent<HTMLElement>) {
    if (drag === null || drag.pointerId !== event.pointerId) return;
    const dx = (event.clientX - drag.startX) * drag.perPxX;
    const dy = (event.clientY - drag.startY) * drag.perPxY;

    const mode = drag.mode;

    if (mode === "draw") {
      if (
        Math.abs(event.clientX - drag.startX) < DRAW_SLOP_PX &&
        Math.abs(event.clientY - drag.startY) < DRAW_SLOP_PX
      ) {
        return;
      }
      const ax = (drag.startX - drag.originX) * drag.perPxX;
      const ay = (drag.startY - drag.originY) * drag.perPxY;
      setCrop(cropRectFromPoints(ax, ay, ax + dx, ay + dy, pixels, CROP_MIN_EDGE_PX));
      return;
    }

    if (mode === "move") {
      setCrop(moveCropBy(drag.start, dx, dy, pixels, CROP_MIN_EDGE_PX));
      return;
    }

    const heldX = mode === "nw" || mode === "sw" ? drag.start.x : drag.start.x + drag.start.width;
    const heldY = mode === "nw" || mode === "ne" ? drag.start.y : drag.start.y + drag.start.height;
    setCrop(moveCropCorner(drag.start, mode, heldX + dx, heldY + dy, pixels, CROP_MIN_EDGE_PX));
  }

  function endDrag(event: React.PointerEvent<HTMLElement>) {
    if (drag !== null && drag.pointerId === event.pointerId) setDrag(null);
  }

  function releaseDrag() {
    setDrag(null);
  }

  function nudge(event: React.KeyboardEvent<HTMLButtonElement>, corner: FrameCorner) {
    const unit = nudgeStep;
    const step = event.shiftKey ? unit * 10 : unit;
    let dx = 0;
    let dy = 0;
    if (event.key === "ArrowLeft") dx = -step;
    else if (event.key === "ArrowRight") dx = step;
    else if (event.key === "ArrowUp") dy = -step;
    else if (event.key === "ArrowDown") dy = step;
    else return;
    event.preventDefault();
    const x = (corner === "nw" || corner === "sw" ? crop.x : crop.x + crop.width) + dx;
    const y = (corner === "nw" || corner === "ne" ? crop.y : crop.y + crop.height) + dy;
    setCrop(moveCropCorner(crop, corner, x, y, pixels, CROP_MIN_EDGE_PX));
  }

  const cropped = crop.width * crop.height;
  const overCap = sharpen.amount > 0 && cropped > SHARPEN_MAX_PIXELS;
  const isWhole = isWholeCropRect(crop, pixels);
  const stale =
    applied !== null &&
    (applied.crop.x !== crop.x ||
      applied.crop.y !== crop.y ||
      applied.crop.width !== crop.width ||
      applied.crop.height !== crop.height ||
      applied.sharpen.amount !== sharpen.amount ||
      applied.sharpen.radius !== sharpen.radius ||
      applied.sharpen.threshold !== sharpen.threshold);
  const nothingToDo = isWhole && sharpen.amount === 0;

  async function apply() {
    if (disabled || overCap) return;
    setProblem(null);
    setBusy(true);
    try {
      const runtime = await loadImageEditor();
      if (goneRef.current) return;
      if (editorRef.current === null) editorRef.current = new runtime.ImageEditor();
      const edited = await editorRef.current.edit({ pixels, crop, sharpen });
      if (goneRef.current) return;
      setApplied({ crop, sharpen, millis: edited.millis });
      onEdited({
        data: edited.data,
        width: edited.width,
        height: edited.height,
        crop,
        sharpen,
        note: edited.note,
        millis: edited.millis
      });
    } catch (error) {
      if (goneRef.current) return;
      const runtimeKnown = error instanceof Error ? error.message : "";
      if (error instanceof Error && error.name === "ImageEditCancelledError") return;
      setProblem(
        runtimeKnown.length > 0
          ? runtimeKnown
          : "Couldn’t process the photo. The original is untouched."
      );
    } finally {
      if (!goneRef.current) setBusy(false);
    }
  }

  function reset() {
    setCrop(whole);
    setDraft(null);
    setClampNote(null);
    setSharpen(NO_SHARPEN);
    setApplied(null);
    setProblem(null);
    onEdited(null);
  }

  const pct = (value: number, total: number): string => `${(value / Math.max(1, total)) * 100}%`;
  const leftPct = pct(crop.x, pixels.width);
  const topPct = pct(crop.y, pixels.height);
  const widthPct = pct(crop.width, pixels.width);
  const heightPct = pct(crop.height, pixels.height);
  const rightPct = pct(crop.x + crop.width, pixels.width);
  const bottomPct = pct(crop.y + crop.height, pixels.height);
  const activePresetId = matchingPresetId(crop, pixels, CROP_MIN_EDGE_PX);
  const boxGesture: DragMode = isWhole ? "draw" : "move";

  return (
    <div className="mb-3 rounded-md border border-line-200 bg-card p-3">
      <div className="flex items-start gap-2">
        <span className="mt-0.5 grid h-8 w-8 shrink-0 place-items-center rounded-lg bg-field-200 text-field-600 dark:text-purple-300">
          <Crop className="h-4 w-4" aria-hidden />
        </span>
        <div className="min-w-0">
          <h5 className="text-sm font-semibold text-ink-900">Frame and sharpen the photograph</h5>
          <p className="mt-1 max-w-prose text-xs leading-5 text-ink-500">
            Changes only what is traced. The photo itself is never altered.
          </p>
        </div>
      </div>

      <div className="mt-3">
        <span className="field-label" id={`${fieldId}-frame-label`}>
          Choose a frame
        </span>

        <div className="mt-1 flex flex-wrap gap-1.5">
          {FRAME_PRESETS.map((preset) => (
            <button
              key={preset.id}
              type="button"
              disabled={disabled}
              title={preset.hint}
              aria-pressed={activePresetId === preset.id}
              className={
                activePresetId === preset.id
                  ? "inline-flex items-center gap-1 rounded-md border border-purple-600 bg-purple-50 px-2.5 py-1 text-xs font-medium text-purple-800 disabled:opacity-60"
                  : "inline-flex items-center gap-1 rounded-md border border-line-200 bg-card px-2.5 py-1 text-xs font-medium text-ink-700 transition hover:border-purple-300 disabled:opacity-60"
              }
              onClick={() => {
                setDraft(null);
                setClampNote(null);
                setCrop(presetCropRect(preset, pixels, CROP_MIN_EDGE_PX));
              }}
            >
              {activePresetId === preset.id ? <Check className="h-3 w-3" aria-hidden /> : null}
              {preset.label}
            </button>
          ))}
        </div>
        <p className="mt-1 text-xs leading-5 text-ink-500">
          {activePresetId === null
            ? "A frame of your own. Pick a shape above to start from a common one."
            : (FRAME_PRESETS.find((preset) => preset.id === activePresetId)?.hint ?? "")}
        </p>

        <div
          ref={pictureRef}
          className="relative mt-2 w-full touch-none rounded-md bg-field-100"
          style={{ maxWidth: boxWidth, aspectRatio: `${pixels.width} / ${pixels.height}` }}
        >
          <canvas
            ref={canvasRef}
            className="block h-full w-full rounded-md"
            aria-label="The photograph, with the frame that will be traced drawn over it"
          />

          <div aria-hidden className="pointer-events-none absolute inset-0">
            <div className="absolute left-0 right-0 top-0" style={{ height: topPct, background: "rgb(var(--ink-900) / 0.45)" }} />
            <div
              className="absolute bottom-0 left-0 right-0"
              style={{ top: bottomPct, background: "rgb(var(--ink-900) / 0.45)" }}
            />
            <div
              className="absolute left-0"
              style={{ top: topPct, height: heightPct, width: leftPct, background: "rgb(var(--ink-900) / 0.45)" }}
            />
            <div
              className="absolute right-0"
              style={{ top: topPct, height: heightPct, left: rightPct, background: "rgb(var(--ink-900) / 0.45)" }}
            />
          </div>

          <div
            aria-hidden
            className="absolute inset-0 cursor-crosshair"
            onPointerDown={(event) => beginDrag(event, "draw")}
            onPointerMove={continueDrag}
            onPointerUp={endDrag}
            onPointerCancel={endDrag}
            onLostPointerCapture={releaseDrag}
          />

          <div
            className={
              isWhole
                ? "absolute cursor-crosshair border-2 border-purple-600"
                : "absolute cursor-move border-2 border-purple-600"
            }
            style={{ left: leftPct, top: topPct, width: widthPct, height: heightPct }}
            onPointerDown={(event) => beginDrag(event, boxGesture)}
            onPointerMove={continueDrag}
            onPointerUp={endDrag}
            onPointerCancel={endDrag}
            onLostPointerCapture={releaseDrag}
          />

          {(["nw", "ne", "sw", "se"] as const).map((corner) => (
            <button
              key={corner}
              type="button"
              disabled={disabled}
              aria-label={
                `${CORNER_NAME[corner]} corner of the frame. Arrow keys move it by ${nudgeStep} ` +
                `${nudgeStep === 1 ? "pixel" : "pixels"} of the photograph; hold Shift for ${nudgeStep * 10}.`
              }
              className="absolute h-4 w-4 -translate-x-1/2 -translate-y-1/2 rounded-sm border border-card bg-purple-700 disabled:opacity-60"
              style={{
                left: corner === "nw" || corner === "sw" ? leftPct : rightPct,
                top: corner === "nw" || corner === "ne" ? topPct : bottomPct,
                cursor: corner === "nw" || corner === "se" ? "nwse-resize" : "nesw-resize"
              }}
              onPointerDown={(event) => beginDrag(event, corner)}
              onPointerMove={continueDrag}
              onPointerUp={endDrag}
              onPointerCancel={endDrag}
              onLostPointerCapture={releaseDrag}
              onKeyDown={(event) => nudge(event, corner)}
            />
          ))}
        </div>

        <p className="mt-2 text-xs leading-5 text-ink-500">
          Drag to draw a frame; drag a corner to resize. Arrow keys nudge {nudgeStep}px (Shift ×10).
        </p>

        <div className="mt-2 grid grid-cols-2 gap-2 sm:grid-cols-4">
          {(Object.keys(CROP_FIELD_NAME) as (keyof CropRect)[]).map((key) => (
            <div key={key} className="grid gap-1">
              <label className="text-xs font-medium text-ink-900" htmlFor={`${fieldId}-crop-${key}`}>
                {CROP_FIELD_NAME[key]}
              </label>
              <input
                id={`${fieldId}-crop-${key}`}
                type="number"
                className="field-input"
                inputMode="numeric"
                min={key === "width" || key === "height" ? Math.min(CROP_MIN_EDGE_PX, pixels.width) : 0}
                max={key === "x" || key === "width" ? pixels.width : pixels.height}
                step={1}
                value={draft !== null && draft.key === key ? draft.text : crop[key]}
                disabled={disabled}
                onChange={(event) => typeInto(key, event.target.value)}
                onBlur={(event) => commitDraft(key, event.target.value)}
                onKeyDown={(event) => {
                  if (event.key === "Enter") {
                    event.preventDefault();
                    commitDraft(key, event.currentTarget.value);
                  } else if (event.key === "Escape") {
                    event.preventDefault();
                    setDraft(null);
                    setClampNote(null);
                  }
                }}
              />
            </div>
          ))}
        </div>

        <p
          role="status"
          className={clampNote !== null ? "mt-2 text-xs leading-5 text-amber-800 dark:text-amber-200" : "text-xs leading-5 text-amber-800 dark:text-amber-200"}
        >
          {clampNote}
        </p>

        <p className="mt-2 text-xs leading-5 text-ink-500">
          {isWhole
            ? `The whole photograph, ${pixels.width}x${pixels.height}.`
            : `${crop.width}x${crop.height} of ${pixels.width}x${pixels.height} — ${Math.round(
                (cropped / (pixels.width * pixels.height)) * 100
              )}% of the frame.`}
        </p>
      </div>

      <fieldset className="mt-3 rounded-md border border-line-200 bg-surface-50 p-3">
        <legend className="field-label px-1">Sharpen the photograph</legend>
        <p className="max-w-prose text-xs leading-5 text-ink-500">
          Runs at full size ({pixels.width}x{pixels.height}) so faint pencil survives. Adds to the trace’s own
          “Sharpen amount”.
        </p>

        <div className="mt-3 grid gap-3">
          <SharpenSlider
            id={`${fieldId}-amount`}
            label="Sharpen amount"
            hint="How much of the difference is added back. 0 is off. Above about 2 the edges start to show a bright halo."
            min={0}
            max={SHARPEN_AMOUNT_MAX}
            step={0.05}
            value={sharpen.amount}
            disabled={disabled}
            onChange={(amount) => setSharpen({ ...sharpen, amount })}
          />
          <SharpenSlider
            id={`${fieldId}-radius`}
            label="Sharpen radius"
            hint="The size of the detail being lifted, in pixels. Around 1 for a fine pencil line; larger for a thick marker or a photograph taken from further away."
            min={SHARPEN_RADIUS_MIN}
            max={SHARPEN_RADIUS_MAX}
            step={0.1}
            value={sharpen.radius}
            disabled={disabled || sharpen.amount === 0}
            onChange={(radius) => setSharpen({ ...sharpen, radius })}
          />
          <SharpenSlider
            id={`${fieldId}-threshold`}
            label="Leave flat areas alone"
            hint="Differences smaller than this are not touched, so the grain of the paper and the sensor noise in a dim photograph are not sharpened along with the drawing."
            min={0}
            max={SHARPEN_THRESHOLD_MAX}
            step={0.005}
            value={sharpen.threshold}
            disabled={disabled || sharpen.amount === 0}
            onChange={(threshold) => setSharpen({ ...sharpen, threshold })}
          />
        </div>

        {sharpen.amount > 0 ? (
          <p className="mt-3 text-xs leading-5 text-ink-500">
            {(cropped / 1_000_000).toFixed(1)} MP — may take a few seconds on a phone.
          </p>
        ) : null}

        {overCap ? (
          <p
            role="alert"
            className="mt-3 flex items-start gap-2 rounded-md border border-line-200 bg-amber-100 px-2 py-1.5 text-xs leading-4 text-amber-800"
          >
            <AlertTriangle className="mt-0.5 h-3.5 w-3.5 shrink-0" aria-hidden />
            <span>
              Too large to sharpen ({(cropped / 1_000_000).toFixed(1)} of {(SHARPEN_MAX_PIXELS / 1_000_000).toFixed(1)} MP max). Crop smaller, or use “Sharpen amount” instead.
            </span>
          </p>
        ) : null}
      </fieldset>

      {problem ? (
        <p
          role="alert"
          className="mt-3 flex items-start gap-2 rounded-md border border-red-200 bg-error-100 px-2 py-1.5 text-xs leading-4 text-red-800"
        >
          <AlertTriangle className="mt-0.5 h-3.5 w-3.5 shrink-0" aria-hidden />
          <span>{problem}</span>
        </p>
      ) : null}

      <ButtonRow align="start" className="mt-3">
        <button
          type="button"
          className="field-button-secondary"
          onClick={() => void apply()}
          disabled={disabled || busy || overCap || (nothingToDo && applied === null)}
        >
          {busy ? <Loader2 className="h-4 w-4 animate-spin" aria-hidden /> : <Sparkles className="h-4 w-4" aria-hidden />}
          Use this frame for the trace
        </button>
        {applied !== null || !nothingToDo ? (
          <button
            type="button"
            className="field-button-secondary"
            onClick={reset}
            disabled={disabled || busy}
          >
            Use the whole photograph
          </button>
        ) : null}
      </ButtonRow>

      <div aria-live="polite" className="mt-2 grid gap-1 text-xs leading-5 text-ink-500">
        {busy ? <p>Working on the photograph…</p> : null}
        {!busy && applied !== null && !stale ? (
          <p className="flex items-start gap-2">
            <Check className="mt-0.5 h-3.5 w-3.5 shrink-0 text-success-600" aria-hidden />
            <span>
              The trace is using {applied.crop.width}x{applied.crop.height}
              {applied.sharpen.amount > 0
                ? `, sharpened at amount ${round2(applied.sharpen.amount)} and radius ${round2(
                    applied.sharpen.radius
                  )}px`
                : ", unsharpened"}
              . Computed on this device in {Math.round(applied.millis)} ms.
            </span>
          </p>
        ) : null}
        {!busy && stale ? (
          <p className="flex items-start gap-2 text-amber-800 dark:text-amber-200">
            <AlertTriangle className="mt-0.5 h-3.5 w-3.5 shrink-0" aria-hidden />
            <span>
              Not applied yet — still tracing {applied?.crop.width}x{applied?.crop.height}. Press “Use this
              frame for the trace”.
            </span>
          </p>
        ) : null}
        {!busy && applied === null && !nothingToDo ? (
          <p>Tracing the whole photo.</p>
        ) : null}
      </div>
    </div>
  );
}

const CROP_FIELD_NAME: Record<keyof CropRect, string> = {
  x: "Left",
  y: "Top",
  width: "Width",
  height: "Height"
};

const CORNER_NAME: Record<FrameCorner, string> = {
  nw: "Top-left",
  ne: "Top-right",
  sw: "Bottom-left",
  se: "Bottom-right"
};

function round2(value: number): string {
  return (Math.round(value * 100) / 100).toString();
}

function SharpenSlider({
  id,
  label,
  hint,
  min,
  max,
  step,
  value,
  disabled,
  onChange
}: {
  id: string;
  label: string;
  hint: string;
  min: number;
  max: number;
  step: number;
  value: number;
  disabled?: boolean;
  onChange: (value: number) => void;
}) {
  return (
    <div>
      <div className="flex items-baseline justify-between gap-2">
        <label className="text-xs font-medium text-ink-900" htmlFor={id}>
          {label}
        </label>
        <output className="text-xs tabular-nums text-ink-500" htmlFor={id}>
          {round2(value)}
        </output>
      </div>
      <input
        id={id}
        type="range"
        className="mt-1 w-full accent-purple-700"
        min={min}
        max={max}
        step={step}
        value={value}
        disabled={disabled}
        aria-describedby={`${id}-hint`}
        onChange={(event) => onChange(Number(event.target.value))}
      />
      <p id={`${id}-hint`} className="mt-0.5 text-xs leading-4 text-ink-500">
        {hint}
      </p>
    </div>
  );
}
