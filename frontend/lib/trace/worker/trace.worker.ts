import { CancellationToken, CancelledError, Pipeline, RgbaImage } from '../engine';
import type { TraceResult, VecShape, VecStyle } from '../engine';
import { VERB_CUBIC, VERB_LINE, VERB_QUAD, transfersOfResult } from './protocol';
import type { FromWorker, SerializedGeometry, SerializedTraceResult, ToWorker } from './protocol';
import { installUnthrottledTimers } from './unthrottledTimers';

/**
 * The worker. **The only place the engine is imported outside tests** (APP-CONTRACT §5).
 *
 * Everything expensive happens here, which is the point: `Pipeline.run` is a straight run of loops over
 * typed arrays and a 12 MP trace is seconds of solid CPU. On the page thread that is a frozen tab, and
 * there is deliberately no synchronous path from the UI to the engine for anyone to accidentally take.
 *
 * Three things this file is responsible for beyond calling the pipeline:
 *
 *  - **serialising the result.** A `VecDocument` is a graph of class instances; structured clone would
 *    strip every method off it. So the geometry is flattened into the typed arrays `protocol.ts`
 *    describes, transferred rather than copied, and rebuilt into real `VecPath`s on the other side. The
 *    UI needs paths and not a picture: `APP-CONTRACT.md` §2.1 puts a `VecDocument` in the editor, and
 *    node editing, hit-testing and selection are all operations no string supports.
 *  - **honouring cancel between stages.** `Pipeline.run` is `async` and yields a macrotask at every
 *    stage boundary, which is what lets this file's `message` handler run *during* a trace. That is the
 *    only reason a cancel can take effect at all: a synchronous engine in a single-threaded worker
 *    could not observe a message until it had finished.
 *  - **serialising the runs themselves.** Because the pipeline yields, a second `trace` message can be
 *    dequeued in the middle of the first run. Starting it there would leave two traces interleaved
 *    through the same event loop, each halving the other's throughput and doubling peak memory. So a
 *    new request cancels the current one and waits in a single-slot queue.
 */

/**
 * **First statement of the module, and it has to stay first.**
 *
 * `Pipeline.run` yields between stages with `setTimeout(resolve, 0)`. A hidden tab throttles chained
 * zero-delay timers to one per second, then to one per *minute*, and that policy reaches into the
 * page's dedicated workers — so the one mechanism that makes the engine cancellable is also the one
 * that makes a backgrounded trace crawl. `unthrottledTimers.ts` reroutes exactly the zero-delay case
 * through a `MessagePort`, which is a genuine macrotask on a task queue nobody throttles.
 *
 * It patches the global rather than the engine because *when* a stage boundary yields is a property of
 * the host: no number the pipeline computes changes, so cross-engine parity is untouched.
 */
installUnthrottledTimers();

/** §0.3: the working long edge a preview runs at. The stage code is shared; only this differs. */
const PREVIEW_LONG_EDGE = 720;

/**
 * The worker global, narrowed to the two members this file uses.
 *
 * `tsconfig.app.json` loads both the `DOM` and `WebWorker` libs, so the type of the ambient `self` is
 * whichever of the two declarations wins — which is not something to build a message protocol on. A
 * local interface makes both calls unambiguous and costs one cast.
 */
interface WorkerScope {
  postMessage(message: FromWorker, options?: { transfer: ArrayBuffer[] }): void;
  addEventListener(type: 'message', listener: (event: MessageEvent) => void): void;
}

const scope = globalThis as unknown as WorkerScope;

/** The request currently running, with the token that stops it. */
let active: { readonly id: number; readonly token: CancellationToken } | null = null;

/**
 * The one queued request. A single slot rather than a queue: if two requests arrive while one runs, the
 * older of the two is already superseded and running it would only delay the one the user is waiting
 * for.
 */
let pending: Extract<ToWorker, { type: 'trace' }> | null = null;

let draining = false;

scope.addEventListener('message', (event: MessageEvent) => {
  const msg = event.data as ToWorker;
  if (msg === null || typeof msg !== 'object') return;

  if (msg.type === 'cancel') {
    if (active !== null && active.id === msg.requestId) active.token.cancel();
    if (pending !== null && pending.requestId === msg.requestId) pending = null;
    return;
  }

  if (msg.type === 'trace') {
    // Supersede: the in-flight trace is told to stop at its next stage boundary, and the newcomer
    // takes the single pending slot. Any request already sitting there never ran and never will —
    // the UI discards results by requestId, so nothing has to be reported for it.
    if (active !== null) active.token.cancel();
    pending = msg;
    if (!draining) void drain();
  }
});

async function drain(): Promise<void> {
  draining = true;
  try {
    while (pending !== null) {
      const msg = pending;
      pending = null;
      await runOne(msg);
    }
  } finally {
    draining = false;
  }
}

async function runOne(msg: Extract<ToWorker, { type: 'trace' }>): Promise<void> {
  const token = new CancellationToken();
  active = { id: msg.requestId, token };
  try {
    const src = decode(msg.image.width, msg.image.height, msg.image.data);

    const result = msg.preview
      ? // `runPreview` reuses every stage of `run` and prepends the sentence that says so. Rebuilding
        // it here with a smaller working edge would work and would also re-type engine wording in a
        // client, which DESIGN.md §9 forbids for exactly the reason it forbids it: the two clients
        // would eventually describe one operation differently.
        await Pipeline.runPreview(src, msg.params, PREVIEW_LONG_EDGE, token)
      : await Pipeline.run(
          src,
          msg.params,
          (stageId: string, label: string, fraction: number) => {
            // Reporting progress for a request that has already been superseded would fight the UI's
            // own state for the newer one.
            if (token.isCancelled) return;
            scope.postMessage({
              type: 'progress',
              requestId: msg.requestId,
              stageId,
              label,
              fraction,
            });
          },
          token,
          true,
        );

    // The cancel may have landed inside the last stage, after the final yield. Sending the result
    // anyway would be correct-but-pointless work for the UI to throw away, and the UI's own requestId
    // check is the backstop rather than the plan.
    if (token.isCancelled) return;

    const serialized = serialize(result);
    // The geometry buffers are handed over, not copied — see `transfersOfResult`. Nothing may read
    // `serialized` after this call: every one of those arrays is detached the moment it returns.
    scope.postMessage(
      {
        type: 'done',
        requestId: msg.requestId,
        result: serialized,
        preview: msg.preview,
      },
      { transfer: transfersOfResult(serialized) },
    );
  } catch (err) {
    // A cancel unwinds `run` by throwing. That is not a failure and must never reach the user as one.
    if (err instanceof CancelledError || token.isCancelled) return;
    scope.postMessage({ type: 'error', requestId: msg.requestId, message: sentenceFor(err) });
  } finally {
    active = null;
  }
}

/**
 * Rebuilds the source image from the transferred buffer.
 *
 * The bytes arrive in `ImageData` order and are handed straight to `RgbaImage.fromImageData`, which is
 * one of the only two functions in the engine allowed to know about the RGBA↔packed-ARGB byte order.
 * Doing the shuffle on the UI thread instead would put a second copy of that knowledge in the codebase,
 * and the two would drift.
 */
function decode(width: number, height: number, data: ArrayBuffer): RgbaImage {
  const w = Math.trunc(width);
  const h = Math.trunc(height);
  if (!(w > 0) || !(h > 0)) {
    throw new Error('That image has no pixels, so there is nothing to trace.');
  }
  // A `Uint8ClampedArray` over a transferred buffer is a view, not a copy: the worker now owns these
  // bytes outright and nobody else can be looking at them.
  const bytes = new Uint8ClampedArray(data);
  if (bytes.length < w * h * 4) {
    throw new Error('The image data arrived incomplete. Reload the page and open the image again.');
  }
  return RgbaImage.fromImageData({ data: bytes, width: w, height: h });
}

/**
 * Flattens every shape of every layer into the typed arrays of {@link SerializedGeometry}.
 *
 * Two passes. The first counts segments and coordinates so each array is allocated exactly once — a
 * growing `number[]` for a million coordinates reallocates about twenty times and leaves the garbage
 * collector holding every intermediate. The second fills them.
 *
 * Styles are de-duplicated through a string key. The pipeline gives every shape in a run the same style
 * object, so in practice this collapses 50 000 references to one or two table entries; the key is built
 * from the fields rather than from object identity because a style is a plain record and two runs can
 * produce equal-but-distinct ones.
 */
function serializeGeometry(result: TraceResult): SerializedGeometry {
  const shapes: VecShape[] = [];
  for (const layer of result.document.layers) {
    for (const shape of layer.shapes) shapes.push(shape);
  }

  let segTotal = 0;
  let coordTotal = 0;
  for (const shape of shapes) {
    const segs = shape.path.segments;
    segTotal += segs.length;
    // Two for the start point, then two, four or six per segment.
    coordTotal += 2;
    for (let i = 0; i < segs.length; i++) {
      const kind = segs[i].kind;
      coordTotal += kind === 'line' ? 2 : kind === 'quad' ? 4 : 6;
    }
  }

  const coords = new Float32Array(coordTotal);
  const verbs = new Uint8Array(segTotal);
  const verbStarts = new Uint32Array(shapes.length + 1);
  const coordStarts = new Uint32Array(shapes.length + 1);
  const closed = new Uint8Array(shapes.length);
  const styleIndex = new Uint32Array(shapes.length);
  const styleTable: VecStyle[] = [];
  const styleKeys = new Map<string, number>();

  let v = 0;
  let c = 0;
  for (let s = 0; s < shapes.length; s++) {
    const shape = shapes[s];
    verbStarts[s] = v;
    coordStarts[s] = c;
    closed[s] = shape.path.closed ? 1 : 0;

    const key = styleKeyOf(shape.style);
    let index = styleKeys.get(key);
    if (index === undefined) {
      index = styleTable.length;
      styleTable.push(shape.style);
      styleKeys.set(key, index);
    }
    styleIndex[s] = index;

    coords[c++] = shape.path.start.x;
    coords[c++] = shape.path.start.y;
    for (const seg of shape.path.segments) {
      if (seg.kind === 'line') {
        verbs[v++] = VERB_LINE;
        coords[c++] = seg.to.x;
        coords[c++] = seg.to.y;
      } else if (seg.kind === 'quad') {
        verbs[v++] = VERB_QUAD;
        coords[c++] = seg.c.x;
        coords[c++] = seg.c.y;
        coords[c++] = seg.to.x;
        coords[c++] = seg.to.y;
      } else {
        verbs[v++] = VERB_CUBIC;
        coords[c++] = seg.c1.x;
        coords[c++] = seg.c1.y;
        coords[c++] = seg.c2.x;
        coords[c++] = seg.c2.y;
        coords[c++] = seg.to.x;
        coords[c++] = seg.to.y;
      }
    }
  }
  verbStarts[shapes.length] = v;
  coordStarts[shapes.length] = c;

  return { coords, verbs, verbStarts, coordStarts, closed, styleTable, styleIndex };
}

function styleKeyOf(style: VecStyle): string {
  return [
    style.stroke,
    style.strokeWidth,
    style.fill,
    style.fillRule,
    style.cap,
    style.join,
    style.miterLimit,
    style.opacity,
  ].join('|');
}

function serialize(result: TraceResult): SerializedTraceResult {
  const doc = result.document;
  return {
    geometry: serializeGeometry(result),
    background: doc.background,
    width: doc.width,
    height: doc.height,
    workingWidth: result.workingWidth,
    workingHeight: result.workingHeight,
    shapeCount: doc.shapeCount(),
    nodeCount: doc.nodeCount(),
    stages: result.stages.map((s) => ({ id: s.id, label: s.label, millis: s.millis })),
    totalMillis: result.totalMillis,
    // Copied into a plain array: the pipeline's own array must not stay reachable from the UI's state.
    notes: result.notes.slice(),
    profile: result.profile === null ? null : { ...result.profile },
    // Carried across unchanged. The engine may have rewritten these before the first stage — see
    // `SerializedTraceResult.appliedParams` — and the client cannot tell what ran without them.
    appliedParams: result.appliedParams,
    autoSubjectId: result.autoSubjectId,
  };
}

/**
 * @returns a sentence fit to show a user — never a stack trace, never "null" (APP-CONTRACT §1.2).
 *
 * The engine promises not to throw for any legal image, so anything arriving here is either a genuine
 * allocation failure or a bug. Both deserve a sentence the user can act on rather than an apology.
 */
function sentenceFor(err: unknown): string {
  if (err instanceof RangeError) {
    // What `new Float32Array(n)` throws when the allocation fails. A trace needs several float planes
    // at working resolution simultaneously, so this is the honest failure of a too-large image on a
    // small device — the web counterpart of the OutOfMemoryError rule in §1.2.
    return (
      'That image needs more memory than this browser will give the page. Lower the trace resolution, ' +
      'or open a smaller copy of the image.'
    );
  }
  const detail = err instanceof Error && typeof err.message === 'string' ? err.message.trim() : '';
  if (detail.length > 0 && detail.length <= 160) return `The trace could not finish: ${detail}`;
  return 'The trace could not finish. Try a different edge engine, or a lower trace resolution.';
}
