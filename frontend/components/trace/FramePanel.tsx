"use client";

/**
 * "Frame and sharpen the photograph" — the crop tool and the sharpening control.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * WHAT THIS CHANGES, AND — SAID FIRST, BECAUSE IT IS THE PART THAT MATTERS — WHAT IT DOES NOT
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * **THE PHOTOGRAPH IS NEVER ALTERED, NEVER RE-ENCODED, AND NEVER REPLACED.** This panel changes
 * exactly one thing: which pixels the TRACE is run on. The image the host handed `TracePanel` is
 * untouched from the moment it was chosen to whenever somebody opens it again, byte for byte, with its
 * EXIF, its original resolution and its own checksum.
 *
 * That is a design constraint rather than a preference, and it comes from three places that agree:
 *
 *  1. `docs/MEDIA_PIPELINE.md` §5 forbids exactly the operation a "crop the photograph" tool would
 *     otherwise perform, as a silent default: "the original file *is* the artifact, and re-encoding
 *     through a canvas destroys full resolution and strips the EXIF the app deliberately preserves …
 *     If it is ever wanted, it should be an explicit, off-by-default … mode that transplants the
 *     original EXIF onto the resized JPEG and records `extraMetadata.downscaledFrom` — never a silent
 *     default."
 *  2. `TracePanel`'s first property is that it never uploads anything and never touches the original:
 *     its whole output is ONE derived file handed to `onAttach`. A cropped photograph would be a
 *     SECOND file, arriving at a host that was told to expect line art — and what the host does with a
 *     file is the host's field, its cardinality and its decision, none of which this panel can see.
 *  3. A second image slot for a derivative is a change to somebody else's registry, which this lane is
 *     explicitly not permitted to make.
 *
 * So the honest half is built: **the crop and the sharpen are TRACE INPUTS.** They are computed on
 * this device, they change the drawing that lands in the line-art field, they are recorded in that
 * drawing's provenance note, and they produce no file of their own. Nothing here can displace anything,
 * because nothing here can produce something the upload door would take — `lib/trace/imageEdit.ts`'s
 * header states the same property from the arithmetic's side, and `TraceCrop.kt`'s header states it
 * for the handset.
 *
 * **RE-CROPPING A PHOTOGRAPH THAT IS ALREADY IN OBJECT STORAGE IS NOT BUILT, and it is not a rounding
 * error.** That is a different operation: the bytes would have to be fetched back, decoded, cropped,
 * re-encoded and then filed — and filed WHERE is the question with no answer today. Over the original,
 * which §5 refuses; or into a second image slot, which does not exist. It is reported rather than
 * half-built here. What IS built is the whole of the flow this panel is in: frame it, sharpen it,
 * trace it, attach the drawing. Reopening the panel and moving the frame traces again from the same
 * decode, because nothing here ever replaces that decode with a cropped one.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * THE INTERACTION, AND WHY IT IS NOT ONLY A DRAG
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * A drag is a pointer gesture and is unreachable from a keyboard, from a switch device and from a
 * screen reader, so drag ALONE would put the one judgement this feature exists to record behind a
 * mouse. `components/media/RecordPhotoMeasure.tsx` answers that for its measuring marks — "Drag it, or
 * use the arrow keys to nudge it" — and this follows the same rule, with the numeric route primary:
 *
 *  · FOUR NUMBER INPUTS — Left, Top, Width, Height, in the photograph's own pixels, each a real
 *    `<input type="number">` with a real `<label htmlFor>`. This is the route that works on every
 *    device and for every input method, and it is the one that can be read out.
 *  · FOUR CORNER HANDLES — real `<button>`s, so they are tab stops with the global focus ring, and
 *    they take the arrow keys (one drawn pixel, or ten with Shift) as well as a pointer drag.
 *  · A POINTER DRAG ON THE PICTURE to draw a frame, and on the box to move it.
 *  · A ROW OF PRESET SHAPES — "Choose a frame", the handset's own control (`TraceCropPanel.kt:199`).
 *
 * Pointer events rather than the HTML5 drag API, for the measured reason the handset's own crop panel
 * records: `dragstart`/`dragover` do not fire for touch at all on Android Chrome.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * WHY ONE BUTTON APPLIES BOTH, INSTEAD OF THE CROP BEING LIVE
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * A crop is a row-wise `memcpy` and could be live. A sharpen is two separable Gaussian passes over up
 * to eight million pixels and cannot be — and once a sharpen has been applied, moving the crop would
 * silently re-run it. A live crop would therefore mean either a multi-second job started by a drag, or
 * two different rules for two controls sitting side by side, one of which recomputes and one of which
 * does not. So: the overlay is the feedback for the frame, one button commits both, and the panel says
 * plainly when what is on screen is no longer what the trace is using. A control whose effect has
 * silently gone stale is indistinguishable from a control that does nothing.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * THE FOUR FAILURES THIS TOOL SHIPPED WITH ONCE, WHICH ARE WHY THE ARITHMETIC IS NOT IN HERE
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * The designer portal's owner reported the frame selection as non-functional on 2026-08-28. The
 * numeric route worked throughout; everything anybody reaches for FIRST did not, and each failure was
 * silent:
 *
 *  1. **A DRAG ON THE PHOTOGRAPH DID NOTHING AT ALL.** The frame opens as the whole photograph, so the
 *     frame's own box covered the entire picture and every press on it was read as "move this box" —
 *     a box that cannot move, because it already fills the frame. Pressing and pulling out a rectangle,
 *     which is what a crop tool IS to most people, was a gesture with no possible effect. A drag on
 *     the picture now DRAWS (see `boxGesture` and the draw layer in the render).
 *
 *  2. **DRAGGING A CORNER OUTWARDS SILENTLY RESET THE CROP TO THE WHOLE PHOTOGRAPH.** `moveCorner`
 *     built a rectangle and clamped the FINISHED thing, and a clamp slides a too-large box back inside
 *     the frame — which moves the corner nobody is touching. Measured on the shipped code, 1000x800
 *     frame, crop {100,100,200,200}: one pixel past the edge gave {99,99,901,701} (the anchored corner
 *     moved), and a real drag gave {0,0,1000,800} — the crop gone. `frameGeometry.moveCropCorner`
 *     clamps the two edges the corner OWNS, which is `TraceCrop.traceMoveCorner`'s rule and its
 *     argument.
 *
 *  3. **THE ARROW KEYS MOVED ONE PHOTOGRAPH PIXEL A PRESS.** On a 4032px sheet drawn 360px wide that
 *     is a ninth of a drawn pixel, so the keyboard route — the one this header calls primary — showed
 *     nothing for thirty presses. The step is now one DRAWN pixel, and the handle says what that is.
 *
 *  4. **THE POINTER MATHS DIVIDED BY AN ASSUMPTION.** A delta was converted with the scale the preview
 *     was laid out at, which is only the scale it is drawn at while nothing constrains its width. It
 *     is now measured off the element, and the overlay is positioned in percentages — which is also
 *     what lets the picture be `max-w-full` and stop scrolling a 360px handset sideways.
 *
 * The arithmetic that was wrong now lives in `frameGeometry.ts` with no React around it, because the
 * comment that used to sit above it said exactly why nothing caught this: "it is not exported and this
 * spec suite has no React renderer to reach it through." `e2e/trace-frame-geometry-unit.spec.ts` is the
 * check that comment said could not exist, and `e2e/trace-frame-panel.spec.ts` renders this component
 * in a real browser, which is the other half.
 */

import { useCallback, useEffect, useId, useMemo, useRef, useState } from "react";
import { AlertTriangle, Check, Crop, Loader2, Sparkles } from "lucide-react";

import { resampleRgbaInBands } from "./comparisonPlates";
import type { DecodedPixels } from "./decodeToPixels";
/*
  THE RECTANGLE'S ARITHMETIC, IN A MODULE OF ITS OWN SO SOMETHING CAN TEST IT.

  Every function below used to be a closure in this component, and the comment above the one that
  mattered said what that cost: "WHAT KEEPS THE TWO HONEST IS NOT A TEST OF THIS FUNCTION — it is not
  exported and this spec suite has no React renderer to reach it through." `moveCorner` had been
  shipping a defect the whole time, nothing could see it, and the owner reported the crop as
  non-functional. `frameGeometry.ts`'s header names the three measured failures and
  `e2e/trace-frame-geometry-unit.spec.ts` pins them.

  It imports nothing at run time, so this stays inside the bundle rule the constants below obey.
*/
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

/** The longest edge the framing preview is drawn at. Big enough to aim a crop, small enough to be free. */
const PREVIEW_BOX_PX = 360;

/**
 * The pure arithmetic this panel drives, re-declared as constants rather than imported.
 *
 * `lib/trace/imageEdit.ts` imports `engine/contrast` and `engine/convolve`, so importing it at the top
 * of a component would put the convolution code on the bundle of every page that mounts the tracer,
 * for every visitor who never touches a photograph — `TracePanel.tsx`'s third property, and the rule
 * `lib/trace/README.md` §4 already quotes measured figures for. The arithmetic is reached through
 * {@link loadImageEditor}'s worker; the numbers a slider needs in order to draw itself are copied here,
 * and `e2e/trace-frame-sharpen-unit.spec.ts` asserts they still match the module's own exports so the
 * copy cannot drift.
 */
const SHARPEN_AMOUNT_MAX = 5;
const SHARPEN_RADIUS_MIN = 0.3;
const SHARPEN_RADIUS_MAX = 8;
const SHARPEN_THRESHOLD_MAX = 0.2;
const SHARPEN_MAX_PIXELS = 8_000_000;
const CROP_MIN_EDGE_PX = 16;

/** Off, matching `imageEdit.NO_SHARPEN` and the engine's own `unsharpAmount` default of 0. */
const NO_SHARPEN: SharpenSettings = { amount: 0, radius: 1.5, threshold: 0 };

/** What the panel hands back: the pixels to trace, and the sentence that says what was done to them. */
export interface EditedFrame {
  readonly data: Uint8ClampedArray;
  readonly width: number;
  readonly height: number;
  readonly crop: CropRect;
  readonly sharpen: SharpenSettings;
  /** For the exported SVG's provenance note. Empty when nothing was done. */
  readonly note: string;
  /** Measured on this device. Never an estimate. */
  readonly millis: number;
}

export interface FramePanelProps {
  /** The whole decoded photograph. Never modified. */
  pixels: DecodedPixels;
  disabled?: boolean;
  /**
   * The frame the trace should use, or `null` for "the whole photograph as decoded".
   *
   * Called on apply and on reset, and once with `null` whenever the photograph changes — a frame
   * chosen on one sheet is meaningless on the next, and leaving it applied would trace a region of a
   * photograph nobody framed.
   */
  onEdited: (frame: EditedFrame | null) => void;
}

/**
 * What a pointer gesture on the picture is doing.
 *
 * "draw" IS THE ONE THAT WAS MISSING, and its absence is half of why this tool read as broken. The
 * frame opens as the whole photograph, so the frame's own box covers the entire picture, and every
 * press on it was "move this box" — a box that cannot move, because it already fills the frame. The
 * first gesture anybody makes on a crop tool therefore did nothing at all and said nothing about why.
 */
type DragMode = FrameCorner | "move" | "draw";

interface Drag {
  readonly pointerId: number;
  readonly mode: DragMode;
  /** Where the gesture started, in client pixels. Every move is measured from here, never accumulated. */
  readonly startX: number;
  readonly startY: number;
  readonly start: CropRect;
  /**
   * The picture's own box at pointerdown, and the conversion out of it.
   *
   * SNAPSHOTTED. Re-measuring mid-gesture feeds the layout the gesture is changing back into the
   * measurement. It is also the fix for the second half of the coordinate defect: the delta used to be
   * divided by the scale the preview was LAID OUT at, which is only the scale it is DRAWN at while
   * nothing constrains its width — and the preview is `max-w-full`, so on a handset it is drawn
   * smaller than it was laid out and every drag framed a different region from the one under the
   * finger.
   */
  readonly originX: number;
  readonly originY: number;
  readonly perPxX: number;
  readonly perPxY: number;
}

/** How far a "draw" gesture must travel before it replaces the frame, in drawn pixels. */
const DRAW_SLOP_PX = 3;

export function FramePanel({ pixels, disabled, onEdited }: FramePanelProps) {
  const fieldId = useId();
  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  /**
   * The box the picture is DRAWN in, which is the only authority on how big a drawn pixel is.
   *
   * Read at pointerdown for a drag and on demand for an arrow press, and watched by a `ResizeObserver`
   * for the two labels that have to print the step. Never inferred from `PREVIEW_BOX_PX`: that is what
   * the preview was ASKED to be, and a `max-w-full` picture on a 360px handset is not it.
   */
  const pictureRef = useRef<HTMLDivElement | null>(null);
  const editorRef = useRef<ImageEditor | null>(null);
  const goneRef = useRef(false);

  /*
    KEYED ON THE `pixels` OBJECT, NOT ON ITS TWO NUMBERS.

    `[pixels.width, pixels.height]` made this identity — and therefore the reset effect below — say
    "a photograph of a different SIZE arrived", when what has to be reset is "a different PHOTOGRAPH
    arrived". Two sheets shot on the same phone decode to the same numbers, so the whole safety of the
    reset would rest on the host setting `pixels` to null in between and unmounting this panel. That
    holds today and is one `await` away from not holding: a decode whose continuation batches with the
    pick's own state writes would commit the second photograph with no null between, and this panel
    would keep the first sheet's crop while its own live region read out the applied frame — with
    `setEdited(null)` upstream meaning the trace was running on the whole new sheet. The draw effect
    one block down already keys on `pixels` identity; so does this, and the two agree.
  */
  const whole = useMemo<CropRect>(
    () => ({ x: 0, y: 0, width: pixels.width, height: pixels.height }),
    [pixels]
  );

  const [crop, setCrop] = useState<CropRect>(whole);
  /**
   * The box being typed into, and the characters in it — held as WRITTEN, not as a clamped number.
   *
   * WHY THE FOUR INPUTS CANNOT CLAMP PER KEYSTROKE, which is what they used to do. `clamp` is total:
   * it always returns a legal rectangle, so it always returns something to write back, so every
   * keystroke replaced what was typed with the answer to a half-finished number.
   *
   *  · LEFT AND TOP WERE INERT while the frame was whole. On a 3000px sheet with the crop at full
   *    width, `x` is clamped to `min(3000 - 3000, …)` = 0 — so typing 5 wrote 0, typing 50 wrote 0,
   *    and the box could not be typed into at all until Width had been reduced first. Nothing on
   *    screen said so.
   *  · WIDTH AND HEIGHT RETURNED A DIFFERENT NUMBER FROM THE ONE TYPED. Select-all and type 800: the
   *    first character is 8, `max(16, 8)` is 16, and the box now reads "16" — so the 0 that follows
   *    lands on it as 160 and the next as 1600. Typing 800 gave 1600.
   *
   * `RecordPhotoMeasure`'s reference length is held as a raw string and coerced later for the same
   * reason; this is that pattern, narrowed to the one box that has focus. One draft is enough because
   * only one box can be focused, and leaving a box blurs it — which is where the clamp belongs.
   */
  const [draft, setDraft] = useState<{ key: keyof CropRect; text: string } | null>(null);
  /** Said out loud when a commit had to move a typed number, and why. Never silently. */
  const [clampNote, setClampNote] = useState<string | null>(null);
  const [sharpen, setSharpen] = useState<SharpenSettings>(NO_SHARPEN);
  /** What was last committed, so the panel can say when the controls have moved past it. */
  const [applied, setApplied] = useState<{ crop: CropRect; sharpen: SharpenSettings; millis: number } | null>(null);
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  const [drag, setDrag] = useState<Drag | null>(null);
  /**
   * How wide the picture is actually drawn, in CSS pixels, or 0 before it has been measured.
   *
   * IN STATE AS WELL AS IN A REF BECAUSE TWO SENTENCES PRINT IT. The handles' spoken labels and the
   * hint under the picture both name the arrow-key step in photograph pixels, and that number is a
   * function of the magnification — so it has to be a value a render can read. The gestures themselves
   * measure at pointerdown and never read this, for the snapshot reason on {@link Drag}.
   */
  const [shownWidth, setShownWidth] = useState(0);

  /**
   * A NEW PHOTOGRAPH RESETS THE FRAME, AND SAYS SO UPWARDS.
   *
   * `whole` changes identity when the decoded PIXELS do — one decode, one object — which is exactly
   * "a different photograph" (or the same one re-decoded). Keeping a 1200x900 crop across a pick would
   * trace a corner of the new sheet, and `clampCrop` would make that legal rather than obvious.
   */
  useEffect(() => {
    setCrop(whole);
    setDraft(null);
    setClampNote(null);
    setSharpen(NO_SHARPEN);
    setApplied(null);
    setProblem(null);
    onEdited(null);
  }, [whole, onEdited]);

  /** A worker outlives the component that forgot it. Same contract as the tracer's. */
  useEffect(() => {
    return () => {
      goneRef.current = true;
      editorRef.current?.dispose();
      editorRef.current = null;
    };
  }, []);

  /* ────────────────────────────────────────────────────────────────────────────
   * Drawing the photograph
   * ──────────────────────────────────────────────────────────────────────────── */

  /*
    `scale` SIZES THE BITMAP AND NOTHING ELSE.

    It used to be the conversion a drag divided by as well, which made it a claim about how big the
    picture is DRAWN — true only while nothing constrains the preview's width. The preview is
    `max-w-full` (a fixed 360px box inside a padded card overflows a 360px handset and scrolls the page
    sideways, which the responsive rule forbids), so the drawn size and the laid-out size are different
    numbers on exactly the devices this application is used from. Everything that converts between
    pointer and photograph measures the element: `measurePicture` for a gesture, the observer below for
    the two sentences that print the step, and PERCENTAGES for the overlay, which needs no conversion
    at all.
  */
  const scale = Math.min(1, PREVIEW_BOX_PX / Math.max(pixels.width, pixels.height));
  const boxWidth = Math.max(1, Math.round(pixels.width * scale));
  const boxHeight = Math.max(1, Math.round(pixels.height * scale));

  /** Photograph pixels per arrow press, from the size the picture is actually drawn at. */
  const nudgeStep = nudgeStepFor(pixels.width, shownWidth > 0 ? shownWidth : boxWidth);

  /**
   * Watch how wide the picture is drawn, so the printed step is the real one.
   *
   * A `ResizeObserver` rather than a window `resize` listener: the picture shrinks whenever its
   * COLUMN does — the panel is inside a card inside a page that reflows at three breakpoints, and a
   * disclosure opening above it changes nothing about the window. Guarded because the constructor is
   * absent in some test environments, and the fallback (`boxWidth`) is the laid-out size, which is
   * right everywhere the picture is not constrained.
   */
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
    // Asked for BEFORE the work, so a browser that will not give this page a 2d surface costs nothing
    // rather than sixteen million reads followed by a shrug. The continuation asks again, on the
    // element it finds there then — see the note inside it.
    if (canvas.getContext("2d") === null) return;
    let cancelled = false;
    /*
      DOWNSCALED IN ARITHMETIC, NOT BY THE CANVAS, AND IT IS `comparisonPlates` DOING IT.

      `putImageData` cannot scale, so the canvas route needs the FULL-SIZE plane on a second surface
      first — up to 32 MB of backing store for an 8 MP photograph, on the device least able to afford
      it. That file's own header makes the same argument for the same reason and adds the one this
      preview needs most: a nearest-neighbour reduction of a photograph of a pencil sketch drops the
      pencil, aliasing fine lines into a dotted mess, so somebody aiming a crop at a line would be
      aiming at an artefact. One box filter, one shared implementation, no second opinion about how
      this photograph looks small.

      IN BANDS, WITH THE PAGE THREAD GIVEN A TURN BETWEEN THEM — `resampleRgbaInBands` and not the
      synchronous `resampleRgba`. The cost of this pass is the SOURCE's size and not the 360px
      preview's: a 4096px photograph is 16.7 million pixels read once each, which is a long task on the
      page thread, and it lands at the worst possible moment — the commit that first shows this panel,
      straight after a decode, while somebody is waiting to see what they picked. Once per photograph
      is not often; a frozen frame is still a frozen frame.

      `createImageData` rather than `new ImageData(plane, w, h)` for the reason `comparisonPlates.ts`
      records: the constructor's typed-array parameter is declared over `ArrayBuffer` while a
      `Uint8ClampedArray` is declared over `ArrayBufferLike`, so the direct call needs a cast — over
      the one thing worth checking, which is that the array and the dimensions agree.
    */
    void (async () => {
      const small = await resampleRgbaInBands(
        pixels.data,
        pixels.width,
        pixels.height,
        boxWidth,
        boxHeight,
        () => cancelled || goneRef.current
      );
      // A NEW PHOTOGRAPH — OR AN UNMOUNT — WHILE THE OLD ONE WAS STILL BEING REDUCED. The canvas is
      // re-read rather than closed over: this continuation can resume after the element is gone, and
      // painting the previous photograph into the current preview is the one outcome worth guarding.
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

  /* ────────────────────────────────────────────────────────────────────────────
   * Moving the frame
   * ──────────────────────────────────────────────────────────────────────────── */

  /**
   * Clamp a rectangle to the photograph, in whole pixels, at least {@link CROP_MIN_EDGE_PX} on a side.
   *
   * A CALL RATHER THAN A CLOSURE, and that is the whole of what made the defect above invisible. The
   * arithmetic lives in `frameGeometry.ts`, which imports nothing at run time — so it stays inside the
   * bundle rule the constants above obey — and `e2e/trace-frame-geometry-unit.spec.ts` runs a table of
   * rectangles through it AND through the real `imageEdit.clampCrop` and requires identical answers.
   *
   * The minimum edge is passed in rather than imported so that this file keeps the literal the source
   * check in `e2e/trace-frame-sharpen-unit.spec.ts` reads. One number, checked against the module that
   * owns it, in the file that spec looks in.
   */
  const clamp = useCallback(
    (rect: CropRect): CropRect => clampCropRect(rect, pixels, CROP_MIN_EDGE_PX),
    [pixels]
  );

  /**
   * Take what is in one number box and make it the frame — clamping ONCE, at the end, out loud.
   *
   * Called on blur and on Enter, which is the moment a number has stopped being half-typed. When the
   * clamp had to move the value, {@link clampNote} says which box, what it became and what to do about
   * it: "Left was set to 0" with no explanation is the silence this whole draft mechanism exists to
   * end, and Left really is unmovable until Width is reduced. The four sentences are
   * `TraceCrop.traceCropClampNote`'s four, so the two clients explain one clamp the same way.
   */
  const commitDraft = useCallback(
    (key: keyof CropRect, text: string) => {
      setDraft(null);
      const typed = Math.round(Number(text));
      // AN EMPTY OR UNREADABLE BOX RESTORES THE FRAME, and says nothing: clearing a field to retype it
      // is not a mistake to report, and `Number("")` is 0, which would silently jump the frame.
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
            } photograph, so it would hang off the edge. It was set to ${next[key]}. Reduce ${
              key === "x" ? "Width" : "Height"
            } first to move it further ${key === "x" ? "right" : "down"}.`
          : `${CROP_FIELD_NAME[key]} cannot be ${typed}: the frame is between ${minEdge} and ${
              key === "width" ? pixels.width : pixels.height
            } pixels. It was set to ${next[key]}.`
      );
    },
    [clamp, crop, pixels.width, pixels.height]
  );

  /**
   * A keystroke: kept as text, unless the number it already spells needs no clamping at all.
   *
   * COMMITTING THE LEGAL ONES LIVE is what keeps the overlay following the typing — the frame moves as
   * somebody types 20, 200, 2000 while each of those is a legal width — and it is what keeps this box
   * behaving like the sliders beside it. Only the values the clamp would have to CHANGE are held back,
   * which is exactly the set that was being destroyed mid-number.
   */
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

  /**
   * How many photograph pixels one drawn pixel is worth, RIGHT NOW.
   *
   * Reads the element rather than the layout constant, and falls back to the laid-out box only when
   * there is nothing to read — before the first paint, or in a test renderer with no layout. See
   * {@link Drag}'s note on why the drag snapshots this instead of calling it per move.
   */
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
    /*
      `preventDefault` STOPS THE BROWSER'S OWN TEXT SELECTION, which otherwise drags a blue smear
      across the preview and, on touch, turns the gesture into a long-press selection instead. And the
      focus is then taken back BY HAND, because that same call is what stops the browser focusing the
      handle: without it somebody who dragged a corner once could not then nudge it with the arrow
      keys, so the two routes would stop being interchangeable exactly where somebody switched.
    */
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

    // A LOCAL, SO THE NARROWING BELOW IS THE COMPILER'S RATHER THAN A CAST. Read once off the
    // snapshot: three branches test it and the last one needs it to BE a corner.
    const mode = drag.mode;

    if (mode === "draw") {
      /*
        A CLICK IS NOT A ZERO-SIZED FRAME. Without the slop, a press on the picture that moved by a
        pixel would replace the frame with the smallest legal box wherever the finger happened to land
        — so somebody who touched the photograph to look at it would find it cropped to a 16px square.
        Below the slop the frame is left exactly as it was, which is what a click means.
      */
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

    // The corner the finger is on, moved to where the finger is. `moveCropCorner` clamps the two edges
    // that corner OWNS — never the finished rectangle — which is the fix for the defect
    // `frameGeometry.ts`'s header measures: the old order slid the whole box back inside the frame,
    // so an outward drag moved the corner nobody was touching and eventually reset the crop to the
    // whole photograph.
    const heldX = mode === "nw" || mode === "sw" ? drag.start.x : drag.start.x + drag.start.width;
    const heldY = mode === "nw" || mode === "ne" ? drag.start.y : drag.start.y + drag.start.height;
    setCrop(moveCropCorner(drag.start, mode, heldX + dx, heldY + dy, pixels, CROP_MIN_EDGE_PX));
  }

  function endDrag(event: React.PointerEvent<HTMLElement>) {
    if (drag !== null && drag.pointerId === event.pointerId) setDrag(null);
  }

  /**
   * The gesture ended somewhere this component never heard about.
   *
   * `onPointerMove` fires on a bare hover as well as during a drag, so a `drag` left set after the
   * pointer went away turns the next mouse movement across the picture into a resize with no button
   * held. Pointer capture normally guarantees the `pointerup`, and `lostpointercapture` is what fires
   * when it does not — a browser taking the capture back, an element removed mid-gesture, a touch the
   * system claimed for a scroll it decided was happening.
   */
  function releaseDrag() {
    setDrag(null);
  }

  /**
   * Arrow keys on a corner handle — ONE DRAWN PIXEL a press, ten with Shift.
   *
   * IT USED TO BE ONE PHOTOGRAPH PIXEL, WHICH IS WHY THE KEYBOARD ROUTE READ AS DEAD. A 4032px sheet
   * drawn 360px wide is 11.2 photograph pixels per drawn pixel, so one press moved the handle by one
   * eleventh of a pixel and Shift's ten moved it by nine tenths of one: thirty presses produced no
   * visible change at all, on the route this panel's own header calls "the one that works on every
   * device and for every input method". The step is now derived from the magnification, so a press
   * always moves the handle exactly one pixel of the picture in front of somebody — and the handle's
   * spoken label says how many photograph pixels that is, because on a large sheet it is not one and a
   * screen-reader user has no other way to know.
   */
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

  /* ────────────────────────────────────────────────────────────────────────────
   * Committing
   * ──────────────────────────────────────────────────────────────────────────── */

  const cropped = crop.width * crop.height;
  const overCap = sharpen.amount > 0 && cropped > SHARPEN_MAX_PIXELS;
  // The shared predicate rather than a fourth hand-written comparison: `frameGeometry` answers it,
  // `imageEdit.isWholeFrame` answers it for the pixels the worker copies, and the unit spec requires
  // the two to agree. A local `&&` chain is how "whole" comes to mean two things on one screen.
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
      /*
        THE PROVENANCE SENTENCE COMES BACK FROM THE WORKER, and rebuilding it here is the mistake.

        A local copy would be a hand transcription of `lib/trace/imageEdit.describeEdit` — which this
        component genuinely cannot import (see the constants above: that module pulls `engine/convolve`
        onto the page's bundle). So the module's version would have no caller outside its spec, and the
        spec would pin the sentence nobody shipped: "The original photograph is unchanged." — the
        promise the whole feature rests on — could be edited out of the shipped path with the suite
        still green.

        The worker already imports that module, so it is the one place both halves are true: it is on
        the far side of the bundle boundary AND it holds the real `clampCrop`. Which fixes the second
        half of the same problem — a local copy would report the crop as REQUESTED, so if this file's
        `clamp` and `imageEdit.clampCrop` ever disagreed, the note would be the thing that lied about
        which pixels were read. `edited.note` describes the rectangle the pixels actually came from.
      */
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
      // A SUPERSEDED REQUEST IS NOT A FAILURE, and this is the one place it can arrive: two presses in
      // a row. The class test needs the module, which a failed load does not give us, so the message
      // is not shown for the cancelled case — the second press's answer is about to arrive and will
      // say everything there is to say.
      if (error instanceof Error && error.name === "ImageEditCancelledError") return;
      setProblem(
        runtimeKnown.length > 0
          ? runtimeKnown
          : "The photograph could not be processed on this device. The photograph itself is untouched."
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

  /* ────────────────────────────────────────────────────────────────────────────
   * Render
   * ──────────────────────────────────────────────────────────────────────────── */

  /*
    THE OVERLAY IS POSITIONED IN PERCENTAGES OF THE PICTURE, NOT IN PIXELS OF A SCALE.

    A percentage is the one expression of the frame that cannot disagree with the picture: it is
    correct at whatever size the browser decided to draw it, before the first measurement has been
    taken, and while the column is mid-reflow. The pixel version had to be recomputed from a `scale`
    that was only ever an assumption — the same assumption that made every drag frame the wrong region
    once the preview stopped being exactly `PREVIEW_BOX_PX` wide.
  */
  const pct = (value: number, total: number): string => `${(value / Math.max(1, total)) * 100}%`;
  const leftPct = pct(crop.x, pixels.width);
  const topPct = pct(crop.y, pixels.height);
  const widthPct = pct(crop.width, pixels.width);
  const heightPct = pct(crop.height, pixels.height);
  const rightPct = pct(crop.x + crop.width, pixels.width);
  const bottomPct = pct(crop.y + crop.height, pixels.height);
  const activePresetId = matchingPresetId(crop, pixels, CROP_MIN_EDGE_PX);
  /*
    A DRAG INSIDE THE BOX DRAWS A NEW FRAME WHILE THE BOX IS THE WHOLE PHOTOGRAPH, AND MOVES IT
    AFTERWARDS. Not a mode switch anybody has to know about: while the frame is everything there is
    nothing to move, so "move" is a gesture with no possible effect — which is exactly what this panel
    shipped, and exactly what "the cropping is non-functional" meant. Once the frame is smaller than
    the photograph, moving it is a real act and the picture around it is where a new one is drawn.
  */
  const boxGesture: DragMode = isWhole ? "draw" : "move";
  const taps = 2 * Math.max(1, Math.ceil(3 * sharpen.radius)) + 1;

  return (
    <div className="mb-3 rounded-md border border-line-200 bg-card p-3">
      <div className="flex items-start gap-2">
        <span className="mt-0.5 grid h-8 w-8 shrink-0 place-items-center rounded-lg bg-field-200 text-field-600">
          <Crop className="h-4 w-4" aria-hidden />
        </span>
        <div className="min-w-0">
          <h5 className="text-sm font-semibold text-ink-900">Frame and sharpen the photograph</h5>
          <p className="mt-1 max-w-prose text-xs leading-5 text-ink-500">
            Both are arithmetic on this device and both change only what is TRACED. The photograph is
            never altered, never re-encoded and never replaced — it stays exactly as it was taken, with
            its full resolution and its own EXIF, whatever you do here. Reopening this panel after
            attaching lets you re-frame and trace again.
          </p>
        </div>
      </div>

      {/* ── The frame ──────────────────────────────────────────────────────── */}
      <div className="mt-3">
        {/* THE HANDSET'S WORD FOR THIS CONTROL (`TraceCropPanel.kt:199`). The SUMMARY of this section,
            up on the panel's primary path, carries the handset's other phrase — "The part of the
            photograph to trace" (`TraceCropPanel.kt:174`) — so the two are the handset's two, in the
            handset's two places, and neither surface repeats the other's heading. */}
        <span className="field-label" id={`${fieldId}-frame-label`}>
          Choose a frame
        </span>

        {/*
          ── "CHOOSE A FRAME": THE ROW OF PRESETS ────────────────────────────────────────────────
          Every other route asks somebody to place four numbers or aim a 16px handle; a photograph of a
          sheet on a table is a shape this application can guess at in one press, and the guess is a
          STARTING POINT — a preset writes the same `crop` state the drag and the boxes do, so it can
          then be pulled about.

          `aria-pressed` rather than a radio group, matching the "Attach as" and comparator chip rows
          in `TracePanel`: these are seven ways to move one rectangle, not seven values of a field. The
          pressed row is an exact match on the rectangle (`matchingPresetId`) — a frame nudged by one
          pixel is honestly no longer "Top half", and a chip that stayed lit would be the panel
          rounding somebody's aim off for them.
        */}
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
              {/* THE TICK IS NOT DECORATION. Colour never carries meaning alone, so the purple that
                  says "this is the shape showing" is paired with a mark. `aria-pressed` already says
                  it to a reader; this says it to everybody who cannot rely on a tint. */}
              {activePresetId === preset.id ? <Check className="h-3 w-3" aria-hidden /> : null}
              {preset.label}
            </button>
          ))}
        </div>
        {/* THE PRESSED ROW'S OWN SENTENCE, BECAUSE `title` IS NOT A ROUTE. A tooltip needs a pointer
            that hovers — which a handset does not have and a keyboard does not do — so the reason to
            press a shape has to be readable without one. Same arrangement as the subject picker in
            `TracePanel`, which shows the chosen preset's hint under the control for the same reason:
            the option row is gone by the time somebody wonders what they picked. */}
        <p className="mt-1 text-xs leading-5 text-ink-500">
          {activePresetId === null
            ? "A frame of your own. Pick a shape above to start from a common one."
            : (FRAME_PRESETS.find((preset) => preset.id === activePresetId)?.hint ?? "")}
        </p>

        {/*
          `touch-none` ON THE PREVIEW, so a drag inside it is a crop rather than a page scroll on a
          handset. Not `overflow-hidden`: the corner handles are `<button>`s and the global focus ring
          is an `outline` drawn OUTSIDE the border box, so clipping here would erase the ring of any
          handle sitting on an edge — which is all four of them once the frame is the whole photograph.

          `max-w-full` AND AN ASPECT RATIO RATHER THAN A FIXED PIXEL BOX. A fixed 360px box sits inside
          a card inside `px-4` page padding, so on a 360px handset — the device this fieldwork is done
          on — it pushes the page into a horizontal scroll. The ratio is the photograph's own, so the
          picture is never letterboxed or stretched, and every position over it is a percentage, so
          nothing has to be recomputed when it shrinks.
        */}
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

          {/* The dimmed surround, as FOUR rectangles rather than one giant box-shadow, because a
              9999px shadow needs an `overflow-hidden` parent and that parent would clip the handles'
              focus rings. `rgb(var(--ink-900) / …)` inverts with the theme; a literal black would not. */}
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

          {/*
            THE DRAW LAYER — the surface a NEW rectangle is pulled out on.

            It covers the whole picture and sits UNDER the frame box, so while a frame is drawn the box
            keeps the drag that moves it and the photograph around the box is where another one starts.
            `aria-hidden` and no tabindex: it is a second way to do what the four number boxes and the
            four handles already do reachably, and a bare div announcing nothing would be a tab stop
            that wastes a keyboard user's press.
          */}
          <div
            aria-hidden
            className="absolute inset-0 cursor-crosshair"
            onPointerDown={(event) => beginDrag(event, "draw")}
            onPointerMove={continueDrag}
            onPointerUp={endDrag}
            onPointerCancel={endDrag}
            onLostPointerCapture={releaseDrag}
          />

          {/* The frame itself. A plain div with pointer handlers and no tabindex — the keyboard route is
              the handles and the four number inputs below, and a focusable box around them would be a
              tab stop that announces nothing. See `boxGesture` for why a press on it means two
              different things depending on whether there is anything to move. */}
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
              /* THE SPOKEN STEP IS THE REAL ONE. A screen-reader user pressing an arrow key has no way
                 to see how far the handle went, and on a large sheet one drawn pixel is a dozen
                 photograph pixels — so the number is derived from the magnification rather than being
                 the "ten pixels" this label claimed while a press moved a ninth of one. */
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

        {/* THE GESTURES, WRITTEN OUT, BECAUSE NONE OF THEM IS DISCOVERABLE — a hint nobody can see is a
            feature nobody can reach, which is the rule `TracePanel`'s comparator hint follows for its
            own three. The keyboard half is here because it exists: the handles are real buttons and
            answer to the arrow keys. */}
        <p className="mt-2 text-xs leading-5 text-ink-500">
          Drag across the photograph to draw a frame. Once there is one, drag inside it to move it and
          drag a corner to resize it. Each corner is also a button: focus it and the arrow keys move it
          {" "}
          {nudgeStep}
          {nudgeStep === 1 ? " pixel" : " pixels"} of the photograph at a time — one pixel of the
          picture as it is drawn here — or {nudgeStep * 10} with Shift held. The four boxes below take
          the numbers directly, and the row above jumps to a common shape.
        </p>

        {/* THE NUMBERS ARE THE PRIMARY ROUTE, NOT A FALLBACK. Every one is a real labelled input, so
            the frame is reachable and readable without a pointer at all — which is why what they used
            to do to a half-typed number was the worst place in this panel for it to happen. See
            `draft` and `commitDraft`. */}
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
                // The characters being typed if this is the box being typed into; otherwise the frame.
                value={draft !== null && draft.key === key ? draft.text : crop[key]}
                disabled={disabled}
                onChange={(event) => typeInto(key, event.target.value)}
                // BOTH ENDINGS COMMIT. Blur is the ordinary one — moving to the next box, pressing the
                // apply button — and Enter is the one somebody filling in four numbers expects to work
                // without leaving the field. Escape abandons the draft and puts the frame back, which
                // is the only way out that does not have to guess at an unfinished number.
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

        {/*
          A CLAMP THAT MOVED A TYPED NUMBER IS ANNOUNCED, not left as a box that ignored the typing.

          `role="status"` rather than `alert`: it is the consequence of an ordinary edit, and an
          assertive interruption every time a number is committed would be worse than the silence it
          replaces.

          ALWAYS RENDERED, EMPTY WHEN THERE IS NOTHING TO SAY — the same rule as every other live region
          in this feature: a reader announces a live region's CHANGES rather than its arrival, so a
          region mounted in the same commit as its first sentence is silent exactly when it matters.
        */}
        <p
          role="status"
          // The margin, and not the element, is what comes and goes: the same node stays in the
          // document — so the region is announced — without leaving a gap under the boxes when it is
          // empty. Changing a class does not remount it.
          className={clampNote !== null ? "mt-2 text-xs leading-5 text-amber-800" : "text-xs leading-5 text-amber-800"}
        >
          {clampNote}
        </p>

        <p className="mt-2 text-xs leading-5 text-ink-500">
          {isWhole
            ? `The whole photograph, ${pixels.width}x${pixels.height}.`
            : `${crop.width}x${crop.height} of ${pixels.width}x${pixels.height} — ${Math.round(
                (cropped / (pixels.width * pixels.height)) * 100
              )}% of the frame. Everything outside it is absent from the drawing.`}{" "}
          The engine may still narrow the frame further on its own when it finds a subject it is
          confident about; the notes under the traced result say when it did.
        </p>
      </div>

      {/* ── Sharpening ─────────────────────────────────────────────────────── */}
      <fieldset className="mt-3 rounded-md border border-line-200 bg-surface-50 p-3">
        <legend className="field-label px-1">Sharpen the photograph</legend>
        <p className="max-w-prose text-xs leading-5 text-ink-500">
          An unsharp mask — the same one the engine uses — over the photograph&apos;s luminance, run
          here at up to {pixels.width}x{pixels.height} rather than at the smaller size the trace runs
          at. That is the whole difference between this and the “Sharpen amount” control in the
          Sharpening group of the trace settings: this one lifts detail BEFORE the engine reduces the
          image, so faint pencil survives the reduction. Both use the same arithmetic, and using both
          compounds them.
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

        {/*
          THE COST, STATED BEFORE THE PRESS, IN ARITHMETIC RATHER THAN IN PREDICTED SECONDS.

          A figure like "about 2 seconds" that turns out to be nine on a five-year-old handset is worse
          than no figure, and this repository's rule is that a measured number is quoted only by
          whoever measured it. So this says what the work IS — megapixels, taps, passes — says that a
          large photograph takes seconds on a phone, and says where it runs. The MEASURED time of the
          run that actually happened is printed underneath, afterwards.
        */}
        {sharpen.amount > 0 ? (
          <p className="mt-3 text-xs leading-5 text-ink-500">
            {(cropped / 1_000_000).toFixed(1)} megapixels through a {taps}-tap kernel, in two passes —
            about {Math.round((cropped * taps * 2) / 1_000_000)} million multiply-adds. On a laptop that
            is under a second; on a phone a full-size photograph can take several seconds. It runs in a
            worker, so the page stays usable while it does, and nothing is recomputed until you press
            the button below.
          </p>
        ) : null}

        {overCap ? (
          <p
            role="alert"
            className="mt-3 flex items-start gap-2 rounded-md border border-line-200 bg-amber-100 px-2 py-1.5 text-xs leading-4 text-amber-800"
          >
            <AlertTriangle className="mt-0.5 h-3.5 w-3.5 shrink-0" aria-hidden />
            <span>
              Sharpening runs on frames up to {(SHARPEN_MAX_PIXELS / 1_000_000).toFixed(1)} megapixels
              on this device, and this frame is {(cropped / 1_000_000).toFixed(1)}. Crop it smaller —
              the frame above is what does that — or leave this at 0 and use the “Sharpen amount”
              control in the Sharpening group, which works on the smaller plane the trace itself runs
              at.
            </span>
          </p>
        ) : null}
      </fieldset>

      {problem ? (
        <p
          role="alert"
          className="mt-3 flex items-start gap-2 rounded-md border border-red-200 bg-error-100 px-2 py-1.5 text-xs leading-4 text-error-600"
        >
          <AlertTriangle className="mt-0.5 h-3.5 w-3.5 shrink-0" aria-hidden />
          <span>{problem}</span>
        </p>
      ) : null}

      <div className="mt-3 flex flex-wrap items-center gap-2">
        <button
          type="button"
          className="field-button-secondary"
          onClick={() => void apply()}
          disabled={disabled || busy || overCap || (nothingToDo && applied === null)}
        >
          {busy ? <Loader2 className="h-4 w-4 animate-spin" aria-hidden /> : <Sparkles className="h-4 w-4" aria-hidden />}
          Use this frame for the trace
        </button>
        {/*
          DECLINING COSTS ONE PRESS, WHICH IS `TracePanel`'S FOURTH PROPERTY ARRIVING INSIDE THIS TOOL.
          "Use the whole photograph" un-applies the frame outright — no dialog, no explanation asked
          for — and the trace goes straight back to the entire decode. It is the handset's own button
          (`TraceCropPanel.kt`), with the handset's own condition for showing it: there has to be
          something to undo.
        */}
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
      </div>

      {/*
        THE LIVE REGION IS OUTSIDE THE CONDITIONS, ALWAYS RENDERED. A reader announces a live region's
        CHANGES rather than its arrival, so a region mounted in the same commit as its first sentence
        is silent exactly when it matters — `TracePanel`'s wrapper carries the same note for the same
        reason.
      */}
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
          <p className="flex items-start gap-2 text-amber-800">
            <AlertTriangle className="mt-0.5 h-3.5 w-3.5 shrink-0" aria-hidden />
            <span>
              The frame on screen is not the one being traced. The trace is still using{" "}
              {applied?.crop.width}x{applied?.crop.height} from the last press — press “Use this frame
              for the trace” to catch it up.
            </span>
          </p>
        ) : null}
        {!busy && applied === null && !nothingToDo ? (
          <p>Nothing has been applied yet: the trace is still using the whole photograph.</p>
        ) : null}
      </div>
    </div>
  );
}

/**
 * The four number boxes, in the order they are drawn, and the words on their labels.
 *
 * One declaration for the labels, the keys and the order: the render maps over it, `commitDraft` names
 * the box it moved out of it, and there is no second list to fall out of step with the first. The four
 * words are `TraceCrop.kt`'s `TRACE_CROP_FIELDS`, so the two clients label one rectangle identically.
 */
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

/**
 * One sharpening slider.
 *
 * The same anatomy as `TracePanel`'s `SliderRow` — label, `<output>`, range, hint, all bound by id —
 * rather than a shared component, because that one reads its value out of a `TraceParams` tree through
 * a `SliderSpec` and these three do not have one. Copying the shape is what keeps the two sets of
 * sliders looking and announcing identically; copying the plumbing would mean inventing a spec for
 * parameters that are not the engine's.
 */
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
