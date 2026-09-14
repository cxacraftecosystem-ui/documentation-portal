/**
 * The worker message contract, exactly as `docs/APP-CONTRACT.md` §5.1 specifies it.
 *
 * This module is imported by **both** sides — `state/traceController.ts` on the UI thread and
 * `worker/trace.worker.ts` inside the worker — so the two cannot drift. It is also the reason this
 * file imports nothing from the engine except *types*: a runtime import here would pull the whole
 * engine into the UI bundle, which is precisely what putting the engine in a worker was for.
 */

import type { TraceParams } from '../engine/params';
import type { VecStyle } from '../engine/path';

/**
 * A decoded image on its way to the worker.
 *
 * `data` is RGBA8 in `ImageData` byte order — the exact input of `RgbaImage.fromImageData`, so the
 * one permitted ARGB↔RGBA byte shuffle stays inside `engine/buffers.ts` and is never reimplemented
 * on the UI thread. It is an `ArrayBuffer` rather than a typed array because it crosses as a
 * **transferable**: see {@link transferOf}.
 */
export interface TransferableImage {
  readonly width: number;
  readonly height: number;
  readonly data: ArrayBuffer;
}

/**
 * The transfer list for a `trace` message.
 *
 * Transferring rather than cloning is the whole point of the protocol: structured-cloning a 12 MP
 * buffer on every slider tick is the difference between a responsive slider and a locked tab. The
 * consequence is that the sender's `ArrayBuffer` is **detached** afterwards, which is why the UI
 * thread sends a clone of its pixel buffer and never the original — see `traceController.ts`.
 */
export function transferOf(image: TransferableImage): ArrayBuffer[] {
  return [image.data];
}

export type ToWorker =
  | {
      readonly type: 'trace';
      readonly requestId: number;
      readonly image: TransferableImage;
      readonly params: TraceParams;
      readonly preview: boolean;
    }
  | { readonly type: 'cancel'; readonly requestId: number };

/** One entry of `TraceResult.stages`, flattened to a structured-cloneable object. */
export interface SerializedStage {
  readonly id: string;
  readonly label: string;
  readonly millis: number;
}

/** `Classify.SourceProfile`, flattened. Present only for a full trace; previews skip classification. */
export interface SerializedProfile {
  readonly bimodality: number;
  readonly edgeDensity: number;
  readonly orientationEntropy: number;
  readonly saturationSpread: number;
  readonly isLineArt: boolean;
  readonly isHighTexture: boolean;
  readonly isFlatGraphic: boolean;
  readonly suggestion: string;
}

/** Segment kind codes for {@link SerializedGeometry.verbs}. Two coordinates per code, four, or six. */
export const VERB_LINE = 0;
export const VERB_QUAD = 1;
export const VERB_CUBIC = 2;

/**
 * The traced geometry, flattened into typed arrays.
 *
 * ## Why geometry and not SVG text
 *
 * `APP-CONTRACT.md` §2.1 requires the editor to hold a `VecDocument` — node editing, hit-testing and
 * selection are all operations on paths, and none of them can be performed on a string. So the worker
 * hands over the paths themselves.
 *
 * ## Why typed arrays and not an array of objects
 *
 * A 50 000-path trace is roughly a million coordinates. As `{x, y}` objects that is a million
 * allocations to structured-clone on **every preview frame of a slider drag**, which is the same
 * mistake as cloning the pixel buffer. Six flat arrays clone as six memory copies — and because they
 * are typed arrays they can be **transferred** instead, which is what {@link transfersOfResult} is
 * for, so the geometry crosses at zero copy cost.
 *
 * `Float32Array` for coordinates rather than `Float64Array`: document coordinates run to 4096 with
 * sub-pixel precision, which float32 resolves to about a thousandth of a pixel, and it halves the
 * traffic. The engine's own `VecPath.bounds()` returns float32 for the same reason.
 *
 * @property coords every coordinate of every shape, concatenated. A shape's own run starts at
 *   `coordStarts[i]` and begins with its start point.
 * @property verbs one byte per segment across all shapes; shape `i` owns
 *   `verbs[verbStarts[i] .. verbStarts[i + 1])`.
 * @property verbStarts, coordStarts length `shapeCount + 1`, so a shape's extent is a subtraction and
 *   the last entry is the total.
 * @property closed one byte per shape, 1 when the path is closed.
 * @property styleTable distinct styles, de-duplicated. The pipeline gives every shape in a run the same
 *   style, so this is almost always one or two entries instead of 50 000 identical objects.
 * @property styleIndex one index into `styleTable` per shape.
 */
export interface SerializedGeometry {
  readonly coords: Float32Array;
  readonly verbs: Uint8Array;
  readonly verbStarts: Uint32Array;
  readonly coordStarts: Uint32Array;
  readonly closed: Uint8Array;
  readonly styleTable: readonly VecStyle[];
  readonly styleIndex: Uint32Array;
}

/**
 * What the UI thread receives instead of a `TraceResult`.
 *
 * @property width, height the document's own coordinate frame: the source image's size, or the
 *   rectified page when perspective correction ran. Preview and full runs agree on this, because the
 *   pipeline scales its geometry back up from working resolution.
 * @property workingWidth, workingHeight the resolution the trace actually ran at. For a preview this
 *   is much smaller than `width`/`height`, and that difference is the thing the UI must state.
 * @property notes every sentence of `TraceResult.notes`, rendered without exception (§0.4).
 */
export interface SerializedTraceResult {
  readonly geometry: SerializedGeometry;
  /** The document background, packed ARGB, or null for transparent. */
  readonly background: number | null;
  readonly width: number;
  readonly height: number;
  readonly workingWidth: number;
  readonly workingHeight: number;
  readonly shapeCount: number;
  readonly nodeCount: number;
  readonly stages: readonly SerializedStage[];
  readonly totalMillis: number;
  readonly notes: readonly string[];
  readonly profile: SerializedProfile | null;
  /**
   * The parameters the stages **actually ran with**, which is not always the ones that were sent.
   *
   * The engine applies auto-detection before the first stage, so a request and its result can differ.
   * `TraceResult.appliedParams` exists precisely so a client can show what ran rather than what was
   * asked for, and dropping it at this boundary would leave the web client with a dock that says one
   * thing and a drawing produced by another — the exact failure auto-detection invites.
   *
   * A plain tree of numbers, booleans and string enums, so it structured-clones with the message.
   */
  readonly appliedParams: TraceParams;
  /** The subject auto-detection applied, or empty when it applied none. Stated on screen (§6). */
  readonly autoSubjectId: string;
}

/**
 * The transfer list for a `done` message.
 *
 * The worker has no further use for these buffers — they were built for this one message — so handing
 * ownership over costs nothing and saves copying a million coordinates per preview frame. Any buffer
 * listed here is detached in the worker the moment `postMessage` returns, which is why nothing may
 * read the geometry after posting it.
 */
export function transfersOfResult(result: SerializedTraceResult): ArrayBuffer[] {
  const g = result.geometry;
  return [
    g.coords.buffer as ArrayBuffer,
    g.verbs.buffer as ArrayBuffer,
    g.verbStarts.buffer as ArrayBuffer,
    g.coordStarts.buffer as ArrayBuffer,
    g.closed.buffer as ArrayBuffer,
    g.styleIndex.buffer as ArrayBuffer,
  ];
}

export type FromWorker =
  | {
      readonly type: 'progress';
      readonly requestId: number;
      readonly stageId: string;
      readonly label: string;
      readonly fraction: number;
    }
  | {
      readonly type: 'done';
      readonly requestId: number;
      readonly result: SerializedTraceResult;
      readonly preview: boolean;
    }
  | { readonly type: 'error'; readonly requestId: number; readonly message: string };
