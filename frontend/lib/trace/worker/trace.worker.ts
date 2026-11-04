import { CancellationToken, CancelledError, Pipeline, RgbaImage } from '../engine';
import type { TraceResult, VecShape, VecStyle } from '../engine';
import { VERB_CUBIC, VERB_LINE, VERB_QUAD, transfersOfResult } from './protocol';
import type { FromWorker, SerializedGeometry, SerializedTraceResult, ToWorker } from './protocol';
import { installUnthrottledTimers } from './unthrottledTimers';

installUnthrottledTimers();

const PREVIEW_LONG_EDGE = 720;

interface WorkerScope {
  postMessage(message: FromWorker, options?: { transfer: ArrayBuffer[] }): void;
  addEventListener(type: 'message', listener: (event: MessageEvent) => void): void;
}

const scope = globalThis as unknown as WorkerScope;

let active: { readonly id: number; readonly token: CancellationToken } | null = null;

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
      ?
        await Pipeline.runPreview(src, msg.params, PREVIEW_LONG_EDGE, token)
      : await Pipeline.run(
          src,
          msg.params,
          (stageId: string, label: string, fraction: number) => {
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

    if (token.isCancelled) return;

    const serialized = serialize(result);
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
    if (err instanceof CancelledError || token.isCancelled) return;
    scope.postMessage({ type: 'error', requestId: msg.requestId, message: sentenceFor(err) });
  } finally {
    active = null;
  }
}

function decode(width: number, height: number, data: ArrayBuffer): RgbaImage {
  const w = Math.trunc(width);
  const h = Math.trunc(height);
  if (!(w > 0) || !(h > 0)) {
    throw new Error('That image has no pixels, so there is nothing to trace.');
  }
  const bytes = new Uint8ClampedArray(data);
  if (bytes.length < w * h * 4) {
    throw new Error('The image data arrived incomplete. Reload the page and open the image again.');
  }
  return RgbaImage.fromImageData({ data: bytes, width: w, height: h });
}

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
    notes: result.notes.slice(),
    profile: result.profile === null ? null : { ...result.profile },
    appliedParams: result.appliedParams,
    autoSubjectId: result.autoSubjectId,
  };
}

function sentenceFor(err: unknown): string {
  if (err instanceof RangeError) {
    return (
      'That image needs more memory than this browser will give the page. Lower the trace resolution, ' +
      'or open a smaller copy of the image.'
    );
  }
  const detail = err instanceof Error && typeof err.message === 'string' ? err.message.trim() : '';
  if (detail.length > 0 && detail.length <= 160) return `The trace could not finish: ${detail}`;
  return 'The trace could not finish. Try a different edge engine, or a lower trace resolution.';
}
