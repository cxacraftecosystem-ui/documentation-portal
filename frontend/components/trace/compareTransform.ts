/**
 * The arithmetic behind `TraceCompare.tsx`'s magnifier — extracted so it can be asserted without a
 * browser.
 *
 * ── WHY THIS IS A SEPARATE FILE AT ALL ────────────────────────────────────────────────────────
 *
 * `TraceCompare.tsx` is a `"use client"` component and the only way to exercise it is to render it.
 * This repository has no React renderer in its devDependencies, so a component's behaviour cannot be
 * asserted at all; the zoom and pan rules are pure functions of numbers, so they live here and
 * `e2e/trace-compare-unit.spec.ts` calls them directly, with no React, no DOM and no browser. Same
 * arrangement `comparisonPlates.ts` already has with `resampleRgba`.
 *
 * ── THE ONE INVARIANT EVERY FUNCTION HERE SERVES ──────────────────────────────────────────────
 *
 * **There is ONE transform, and both stacked layers are drawn through it.** A comparator whose two
 * layers are scaled or panned independently does not fail loudly — it shows a drawing that appears to
 * have drifted off its own photograph, and the researcher attributes the drift to the trace. So
 * nothing here returns a per-layer anything: a {@link RevealTransform} is one zoom and one
 * translation, applied to one wrapper element that contains both pictures.
 *
 * The handset's comparator states the same rule and computes the same three numbers; the only
 * difference is that Compose bakes them into `dstOffset`/`dstSize` while a browser writes them as a
 * CSS `transform`.
 *
 * ── THE TWO COORDINATE SPACES, BECAUSE MIXING THEM IS THE BUG THIS FILE INVITES ───────────────
 *
 * · **FRAME SPACE** is the box on screen. The seam, the grip and the two corner badges live here: a
 *   seam that panned away with the picture would leave the frame entirely at 2x, and the researcher
 *   would have no way to get it back.
 * · **WRAPPER SPACE** is inside the transform. The two images live here.
 *
 * The seam is decided in frame space and the layer it clips is in wrapper space, so exactly one
 * conversion is needed and {@link wrapperPercent} is it. Doing that conversion in the component, at
 * the point of use, is how a `50` that means one thing gets compared with a `50` that means the other.
 */

/**
 * How long a pointer must stay still and down before the frame peeks at the before layer.
 *
 * 220 ms, the same number and for the same reason as the handset's peek hold: a peek that began on
 * contact would fire at the start of every pinch, because a two-finger gesture puts one finger down
 * first. It is well under a long-press threshold because this is not a long press — the researcher is
 * holding to look, not holding to open a menu.
 */
export const REVEAL_PEEK_HOLD_MS = 220;

/**
 * How far a pointer may travel before the gesture is a drag rather than a press.
 *
 * Without it a hand-held pointer's own jitter cancels the peek before it starts, and a press that
 * moves one pixel writes the seam somewhere the researcher did not ask for. Three CSS pixels is under
 * the smallest deliberate movement anybody makes and over the largest accidental one.
 */
export const REVEAL_DRAG_SLOP_PX = 3;

/** One zoom and one translation, in frame pixels. Applied to ONE wrapper — see the file note. */
export interface RevealTransform {
  readonly zoom: number;
  readonly panX: number;
  readonly panY: number;
}

/** Fit-to-frame: the whole picture, centred, nothing magnified. */
export const REVEAL_AT_FIT: RevealTransform = { zoom: 1, panX: 0, panY: 0 };

/** True when this transform is doing nothing, so the component can skip the "reset" affordance. */
export function isAtFit(transform: RevealTransform): boolean {
  return transform.zoom <= 1.001 && transform.panX === 0 && transform.panY === 0;
}

const finite = (value: number, fallback: number) => (Number.isFinite(value) ? value : fallback);

/**
 * Hold a zoom between 1 (the whole picture) and `maxZoom`.
 *
 * NEVER BELOW 1. Zooming out past fit would letterbox the picture inside a frame that is already the
 * picture's own ratio, which is a smaller drawing and nothing else. The cap is the caller's, because
 * this component has no opinion about how big its caller's pictures are.
 *
 * `Infinity` MEANS "NO CEILING", AND THAT IS LOAD-BEARING RATHER THAN A CURIOSITY. {@link clampPan} and
 * {@link wrapperPercent} both need "hold this at or above 1 and otherwise leave it alone", and they
 * ask for it that way. Running the ceiling through the same non-finite fallback as the zoom made
 * `Infinity` become 1, so every one of those calls silently flattened a magnified transform back to
 * fit — a magnifier that did nothing at all. `e2e/trace-compare-unit.spec.ts` is where that is caught.
 */
export function clampZoom(zoom: number, maxZoom: number): number {
  const ceiling = Math.max(1, Number.isNaN(maxZoom) ? 1 : maxZoom);
  return Math.min(ceiling, Math.max(1, finite(zoom, 1)));
}

/**
 * Hold a translation inside the picture's own overhang, so a plate can never be flicked off the frame.
 *
 * THE OVERHANG IS EXACTLY WHAT THE ZOOM ADDED. The wrapper is `absolute inset-0`, so at zoom 1 it is
 * the frame; at zoom z it is `frame * z` and hangs over each edge by `frame * (z - 1) / 2`. That is
 * the handset's `slackX`/`slackY` with `fit` folded in, because on the web the fit is already done by
 * `object-cover` inside a frame the caller sized to the source.
 *
 * A frame that has not been laid out yet is zero-sized, and every slack is then zero — which pins the
 * picture centred rather than dividing by nothing.
 */
export function clampPan(transform: RevealTransform, frameWidth: number, frameHeight: number): RevealTransform {
  const zoom = clampZoom(transform.zoom, Number.POSITIVE_INFINITY);
  const slackX = Math.max(0, (finite(frameWidth, 0) * (zoom - 1)) / 2);
  const slackY = Math.max(0, (finite(frameHeight, 0) * (zoom - 1)) / 2);
  return {
    zoom,
    panX: Math.max(-slackX, Math.min(slackX, finite(transform.panX, 0))),
    panY: Math.max(-slackY, Math.min(slackY, finite(transform.panY, 0)))
  };
}

/** Move the picture under the pointer, then put it back inside its overhang. */
export function panBy(
  transform: RevealTransform,
  dx: number,
  dy: number,
  frameWidth: number,
  frameHeight: number
): RevealTransform {
  return clampPan(
    { zoom: transform.zoom, panX: transform.panX + finite(dx, 0), panY: transform.panY + finite(dy, 0) },
    frameWidth,
    frameHeight
  );
}

/**
 * Magnify by `factor`, keeping the point under the pointer where it is.
 *
 * WHY ABOUT THE POINTER AND NOT ABOUT THE CENTRE. The reason this control exists is that a pencil line
 * on a 1024 px plate shown in a card a few hundred pixels wide is sub-pixel — so the researcher has
 * already found the line they are suspicious of before they zoom. Magnifying about the centre would
 * throw it off screen and make them hunt for it again at every step.
 *
 * THE ARITHMETIC, which is four lines and worth writing out because it is the part that goes wrong.
 * A point sits at frame offset `d` from the centre and at wrapper offset `w`, related by
 * `d = w * zoom + pan`. Holding `w` fixed across a zoom change gives `pan' = d - (d - pan) * z' / z`,
 * which at `z' === z` returns `pan` exactly — the property that keeps a no-op wheel event from
 * drifting the picture.
 *
 * @param pointerX horizontal distance of the pointer from the frame's LEFT edge, in CSS pixels.
 * @param pointerY the same from the frame's TOP edge.
 */
export function zoomAbout(
  transform: RevealTransform,
  factor: number,
  pointerX: number,
  pointerY: number,
  frameWidth: number,
  frameHeight: number,
  maxZoom: number
): RevealTransform {
  const from = clampZoom(transform.zoom, maxZoom);
  const to = clampZoom(from * finite(factor, 1), maxZoom);
  const width = finite(frameWidth, 0);
  const height = finite(frameHeight, 0);
  // Measured from the frame's CENTRE, which is where `transform-origin: center` puts the fixed point
  // of the scale. Measuring from the left edge instead is the mistake that makes a zoom drift left.
  const dx = finite(pointerX, width / 2) - width / 2;
  const dy = finite(pointerY, height / 2) - height / 2;
  const ratio = from === 0 ? 1 : to / from;
  return clampPan(
    { zoom: to, panX: dx - (dx - transform.panX) * ratio, panY: dy - (dy - transform.panY) * ratio },
    width,
    height
  );
}

/**
 * Convert a seam expressed as a percentage of the FRAME into a percentage of the WRAPPER.
 *
 * ONE AXIS AT A TIME — the caller hands the pan and the frame length that belong to the axis the seam
 * runs along. Taking a whole {@link RevealTransform} would mean this function choosing between `panX`
 * and `panY`, and it has no way to know which one the caller's orientation means.
 *
 * WHY THE CONVERSION EXISTS. The clipped layer is inside the transform and the seam is not — see the
 * file note. Clipping the layer at the raw seam percentage would make the join slide across the
 * drawing as the picture is panned, so the two halves on screen would stop meeting at the white line
 * that claims to divide them.
 *
 * THE ANSWER IS DELIBERATELY NOT CLAMPED to 0..100, and it does not need to be. A pan that has been
 * through {@link clampPan} always leaves the frame entirely inside the wrapper — the overhang is what
 * the zoom added, and the clamp is exactly that overhang — so every seam a researcher can reach comes
 * back inside the range. What the missing clamp buys is TOTALITY: handed an unclamped transform this
 * still answers a number, and `inset()` renders it correctly either way, a negative inset clipping
 * nothing and one over 100% clipping everything. Clamping here would instead pin the join to the
 * wrapper's edge and show half a picture that should have been whole.
 */
export function wrapperPercent(seamPercent: number, zoom: number, pan: number, frameLength: number): number {
  const length = finite(frameLength, 0);
  const magnification = clampZoom(zoom, Number.POSITIVE_INFINITY);
  if (length <= 0) return finite(seamPercent, 0);
  const panPercent = (finite(pan, 0) / length) * 100;
  return (finite(seamPercent, 0) - 50 - panPercent) / magnification + 50;
}

/** How the magnification is written on screen: one decimal, and a multiplication sign, as the handset does. */
export function zoomLabel(zoom: number): string {
  return `${Math.round(clampZoom(zoom, Number.POSITIVE_INFINITY) * 10) / 10}×`;
}
