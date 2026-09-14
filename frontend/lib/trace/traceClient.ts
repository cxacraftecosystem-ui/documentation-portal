/**
 * The one door between this portal and the vendored trace engine.
 *
 * WHAT IT IS. `engine/` is a 43-file line-art and vectorisation engine copied from D:/Offline-Tracer
 * (see README.md in this directory for the provenance and for what deliberately did NOT come with
 * it). `worker/` is that engine's Web Worker host. This file is the only thing the app is meant to
 * import: it starts the worker, speaks its message protocol, and hands back a promise. Nothing else
 * in `frontend/` should ever name `./engine` or `./worker` directly.
 *
 * WHY A WORKER, AND WHY THERE IS NO MAIN-THREAD FALLBACK
 *
 * `Pipeline.run` is straight loops over typed arrays and a 12 MP trace is seconds of solid CPU. On
 * the page thread that is a frozen tab — not a slow one, a frozen one, with the nav sheet unable to
 * close and the save button unable to be clicked. So the engine runs in a worker and there is
 * deliberately no synchronous path from a component into it for anybody to reach for by accident. A
 * browser that refuses to construct a module worker gets {@link TraceUnavailableError} carrying a
 * sentence to show, not a trace that locks the tab. That refusal is inherited: the upstream's
 * `trace.worker.ts` header calls itself "the only place the engine is imported outside tests", and
 * this portal is the second codebase where that sentence has to stay true.
 *
 * THIS FILE IS THE WEIGHT BOUNDARY, AND THAT IS WHY IT IMPORTS NO ENGINE VALUE
 *
 * `.claude/skills/gsap/SKILL.md` §2, "The dynamic import is not optional", states the rule this
 * repository already enforces on its one heavy library:
 *
 *     "GSAP is ~70 KB. Only this one component needs it, so it must not sit in the bundle every
 *      protected page loads … A top-level `import { gsap } from "gsap"` ships it to every route that
 *      transitively imports the component. Keep the value import inside the effect and the namespace
 *      import type-only."
 *
 * `components/guide/useGsapHeadline.ts` obeys it in the same words. The trace engine is larger than
 * GSAP, so the rule applies with more force, and it is obeyed three times over:
 *
 *  1. **Every engine and worker import in this file is `import type`**, which TypeScript erases. The
 *     engine has no static path into any page graph at all.
 *  2. **The two things that would create such a path are both behind `await import()`** — the worker
 *     URL (`./spawnTraceWorker`, whose header explains why it is a separate file) and the parameter
 *     defaults (`./engine/params`, via {@link loadTraceParams}).
 *  3. **The later UI wave must still `await import("@/lib/trace/traceClient")`** rather than import
 *     this module at the top of a stage component. This module is small, but "small" is how a bundle
 *     grows, and the trace surface is one tab of one page.
 *
 * WHAT IS NOT HERE, ON PURPOSE
 *
 * No React, no component, no canvas, no `File`. Decoding a photograph into pixels is the caller's
 * job — `lib/imageQuality.ts` and `lib/sketchRectify.ts` already own that in this repository, and a
 * second decoder here would be a second opinion about EXIF orientation. This file takes pixels and
 * returns geometry, which is the part the engine is for.
 */

import type { TraceParams, TraceParamsInput } from "./engine/params";
import type {
  FromWorker,
  SerializedTraceResult,
  ToWorker,
  TransferableImage
} from "./worker/protocol";

export type { TraceParams, TraceParamsInput } from "./engine/params";
export type {
  SerializedGeometry,
  SerializedProfile,
  SerializedStage,
  SerializedTraceResult,
  TransferableImage
} from "./worker/protocol";

/**
 * The worker, narrowed to the five members this file touches.
 *
 * A real `Worker` satisfies it. Narrowing rather than using the DOM type is what lets
 * {@link TracerOptions.spawn} take a stand-in, so the whole of this file can be driven from a Node
 * spec — see `e2e/trace-engine-unit.spec.ts`. It is not an extension point for production code:
 * there is exactly one real implementation and it is `spawnTraceWorker`.
 */
export interface TraceWorkerLike {
  postMessage(message: ToWorker, options?: { transfer: Transferable[] }): void;
  terminate(): void;
  onmessage: ((event: MessageEvent) => void) | null;
  // Typed as the DOM declares them rather than as `unknown`: under `strictFunctionTypes` a
  // *property* holding a function is checked contravariantly, so widening the parameter here is
  // what makes a real `Worker` STOP being assignable to this interface. Measured — it was `unknown`
  // first, and `spawnTraceWorker` would not typecheck against it.
  onerror: ((event: ErrorEvent) => void) | null;
  onmessageerror: ((event: MessageEvent) => void) | null;
}

/**
 * Raised when this environment will not give the page a module worker.
 *
 * A distinct class rather than a bare `Error` because the caller has to be able to tell "this device
 * cannot do it at all" — where the honest UI is to hide the control — apart from "this one image
 * failed", where the honest UI is to let them try another. Its `message` is written to be shown.
 *
 * The `cause` is not decoration. `spawnTraceWorker.ts` records that a rewritten `new Worker(new
 * URL(...))` expression makes the worker "silently stop being emitted and the app 404 at run time on
 * a file the build never made. There is no build error for this" — so the run-time throw is the ONLY
 * evidence that failure ever produces, and swallowing it would leave the one failure this module has
 * no build-time signal for with no diagnostic at all. The `message` stays the sentence to show; the
 * cause is for whoever reads the console behind the screenshot.
 */
export class TraceUnavailableError extends Error {
  constructor(message: string, options?: ErrorOptions) {
    super(message, options);
    this.name = "TraceUnavailableError";
  }
}

/** Raised when the caller's `AbortSignal` fired, or the tracer was disposed, before an answer came. */
export class TraceCancelledError extends Error {
  constructor(message = "The trace was cancelled.") {
    super(message);
    this.name = "TraceCancelledError";
  }
}

/** Progress between stages: `fraction` runs 0..1 across the whole run, not within a stage. */
export interface TraceProgress {
  readonly stageId: string;
  readonly label: string;
  readonly fraction: number;
}

export interface TraceRequest {
  /**
   * Pixels in `ImageData` byte order — R,G,B,A per pixel, row-major, no padding.
   *
   * THE BUFFER IS TRANSFERRED, NOT COPIED, and is therefore **detached once this call has posted
   * it**: the caller's typed array over it becomes zero-length. That is the whole point of the
   * protocol — a 12 MP frame is 48 MB and structured-cloning it per request is the difference
   * between a responsive control and a locked tab — but it means a caller who wants to trace the
   * same image twice must hand over fresh pixels each time. {@link transferableFrom} exists to make
   * that safe by construction. Upstream learned it the hard way: transferring the original made "the
   * second trace produces a blank image", which surfaces as a rendering bug rather than an error.
   */
  readonly image: TransferableImage;
  /**
   * Engine parameters. A partial tree is fine — the worker sanitises and fills every field on
   * arrival — and {@link loadTraceParams} hands back the defaults to start from.
   */
  readonly params: TraceParams | TraceParamsInput;
  /**
   * Run the fast pass at ~720 px instead of full resolution.
   *
   * A preview is not a cheaper different pipeline; it is every stage of the same one at a smaller
   * working edge, and its geometry comes back in the SAME document coordinates as a full run. So it
   * is safe to tune against, and safe to draw in the same viewport. It reports no progress (it is a
   * few hundred milliseconds) and skips source classification.
   */
  readonly preview?: boolean;
  readonly onProgress?: (progress: TraceProgress) => void;
  readonly signal?: AbortSignal;
}

export interface TracerOptions {
  /**
   * How to start the worker. Omitted everywhere except in tests, where a stand-in stands in.
   *
   * The default is `spawnTraceWorker`, reached through `await import()` so that neither the worker
   * chunk nor `import.meta.url` is on this module's static graph.
   */
  readonly spawn?: () => TraceWorkerLike;
}

/**
 * A live worker. Construct one per surface, not one per trace.
 *
 * Starting a worker parses the whole engine, so a tracer built per keystroke would re-parse it per
 * keystroke. {@link Tracer.trace} may be called repeatedly on one instance. Call {@link dispose}
 * when the surface unmounts — a worker outlives the component that forgot it.
 *
 * A SECOND `trace` SUPERSEDES THE FIRST, AND THE FIRST REJECTS. The worker holds one running request
 * and one pending slot: a second `trace` message cancels whatever is running and drops whatever was
 * queued, and `runOne` returns without posting anything once its token is cancelled (see
 * `worker/trace.worker.ts`). So the worker will never answer the older request — nothing arrives to
 * settle its promise, remove its entry from {@link inFlight}, or drop the `abort` listener it
 * installed. This class therefore settles it here, at the moment the newer message is posted,
 * rejecting with {@link TraceCancelledError}. Passing an `AbortSignal` per request is still the
 * better shape for a slider — it also tells the worker to stop the *running* stage — but a caller who
 * does not is no longer leaking a promise, a progress closure and a listener per drag tick.
 *
 * The one race this accepts knowingly: a `done` the worker had already posted before the newer
 * request reached it is dropped, so a trace that had in fact finished still rejects as superseded. A
 * caller who has asked for a second trace has moved on from the first by construction, and the
 * alternative — waiting to see whether a result turns up — is the leak this replaced.
 */
export class Tracer {
  private readonly spawn: (() => TraceWorkerLike) | null;
  private worker: TraceWorkerLike | null = null;

  /**
   * The start that is in flight.
   *
   * Two `trace` calls in the same tick both find no worker; without this they would each await the
   * dynamic import and construct one, and the second would silently orphan the first — a live worker
   * holding the engine, never terminated, never messaged.
   */
  private starting: Promise<TraceWorkerLike> | null = null;

  private nextId = 0;
  private disposed = false;

  /** Every request the worker has not yet answered, by id. */
  private readonly inFlight = new Map<
    number,
    {
      readonly resolve: (result: SerializedTraceResult) => void;
      readonly reject: (error: Error) => void;
      readonly onProgress?: (progress: TraceProgress) => void;
    }
  >();

  constructor(options: TracerOptions = {}) {
    this.spawn = options.spawn ?? null;
  }

  /**
   * True while a request is outstanding — the enabled state of a Cancel control.
   *
   * Honest as a control's enabled state only because a superseded request is settled here rather
   * than abandoned: at most one entry is ever outstanding, so `busy` goes false again when the
   * newest trace answers. It is a `size > 0` test rather than a flag because {@link dispose} and
   * {@link failAll} clear the map wholesale.
   */
  get busy(): boolean {
    return this.inFlight.size > 0;
  }

  async trace(request: TraceRequest): Promise<SerializedTraceResult> {
    if (aborted(request.signal)) throw new TraceCancelledError();
    const worker = await this.ensureWorker();
    // The await above yields, so a dispose or an abort may have landed while the engine was loading.
    if (this.disposed) throw new TraceCancelledError("This tracer has been disposed.");
    if (aborted(request.signal)) throw new TraceCancelledError();

    const requestId = ++this.nextId;

    // Supersede everything older, here, before the newer message goes out. The worker's single-slot
    // semantics (see this class's header) mean no older request can ever answer once this one is
    // posted, so leaving its entry in the map would strand the caller's promise, their `onProgress`
    // closure and the `abort` listener installed for them — one leak per drag tick of a slider.
    // Nothing may await between here and the `postMessage` below, or a third call could interleave.
    for (const entry of Array.from(this.inFlight.values())) {
      entry.reject(new TraceCancelledError("Superseded by a newer trace."));
    }
    this.inFlight.clear();

    return await new Promise<SerializedTraceResult>((resolve, reject) => {
      const onAbort = () => {
        // `delete` returning false means the request has already been settled — a `done` that beat
        // the abort by a tick — and rejecting it now would settle a promise the caller has already
        // been handed a result from.
        if (!this.inFlight.delete(requestId)) return;
        // A `cancel` message is genuinely enough here, and it is worth saying why, because the
        // reflex for a busy worker is `terminate()`. `Pipeline.run` is async and yields a macrotask
        // at every stage boundary, so the worker drains its message queue mid-trace and reads the
        // token within one stage. Terminating would throw that working mechanism away and pay to
        // re-parse the whole engine on the next request.
        this.post({ type: "cancel", requestId });
        request.signal?.removeEventListener("abort", onAbort);
        reject(new TraceCancelledError());
      };
      request.signal?.addEventListener("abort", onAbort);

      this.inFlight.set(requestId, {
        resolve: (result) => {
          request.signal?.removeEventListener("abort", onAbort);
          resolve(result);
        },
        reject: (error) => {
          request.signal?.removeEventListener("abort", onAbort);
          reject(error);
        },
        onProgress: request.onProgress
      });

      const message: ToWorker = {
        type: "trace",
        requestId,
        image: request.image,
        // Cast rather than sanitised here: sanitising on this side would be a second copy of the
        // engine's defaulting rules, and the worker runs `sanitizeTraceParams` on arrival regardless.
        // `SerializedTraceResult.appliedParams` is what a caller should read back.
        params: request.params as TraceParams,
        preview: request.preview === true
      };

      try {
        worker.postMessage(message, { transfer: [request.image.data] });
      } catch {
        this.inFlight.delete(requestId);
        request.signal?.removeEventListener("abort", onAbort);
        // Either the buffer was already detached (the caller reused it) or the params tree carries
        // something unclonable. Both are caller errors, and both otherwise present as a trace that
        // never finishes rather than as a failure.
        reject(
          new Error(
            "That image could not be handed to the tracing engine. It may already have been used " +
              "for a trace — pass a fresh copy of the pixels."
          )
        );
      }
    });
  }

  /** Terminates the worker and rejects anything outstanding. Safe to call more than once. */
  dispose(): void {
    this.disposed = true;
    this.starting = null;
    const entries = Array.from(this.inFlight.values());
    this.inFlight.clear();
    this.worker?.terminate();
    this.worker = null;
    for (const entry of entries) entry.reject(new TraceCancelledError("This tracer has been disposed."));
  }

  private async ensureWorker(): Promise<TraceWorkerLike> {
    if (this.disposed) throw new TraceCancelledError("This tracer has been disposed.");
    const existing = this.worker;
    if (existing !== null) return existing;
    if (this.starting !== null) return await this.starting;

    const starting = this.startWorker();
    this.starting = starting;
    try {
      return await starting;
    } finally {
      // Cleared either way: on success `this.worker` is the cache, and on failure the next call
      // should be allowed to try again rather than await a promise that is already rejected.
      if (this.starting === starting) this.starting = null;
    }
  }

  private async startWorker(): Promise<TraceWorkerLike> {
    const spawn = this.spawn ?? (await loadSpawn());

    let worker: TraceWorkerLike;
    try {
      worker = spawn();
    } catch (err) {
      // The sentence is for the designer; the `cause` is for whoever reads the console behind their
      // screenshot. `new Worker` throws a SecurityError for a blocked or missing script as readily as
      // it does for a browser without module workers, and those two want opposite answers.
      throw new TraceUnavailableError(
        "This browser would not start a background worker, and tracing is never run on the page " +
          "thread. Try a current version of Chrome, Edge, Firefox or Safari.",
        { cause: err }
      );
    }

    // A dispose that landed while the dynamic import was in flight must not leave a live worker
    // holding the engine with nothing left to terminate it.
    if (this.disposed) {
      worker.terminate();
      throw new TraceCancelledError("This tracer has been disposed.");
    }

    worker.onmessage = (event: MessageEvent) => this.onMessage(event.data as FromWorker);
    // A module worker whose import graph fails to resolve fires `error`, and in some browsers
    // `messageerror` instead. Either way every outstanding promise has to be settled: an unhandled
    // worker failure otherwise presents as a spinner that never stops.
    onceOnly(worker, "onerror", (event) => {
      // Logged before the promises are settled, because the sentence the caller shows is deliberately
      // free of filenames and this is the only place the real one appears. `lib/api.ts` does the same
      // for a misbuilt API base: the user-facing wording stays plain, the console names the fault.
      console.error("[trace] the worker failed to start or threw at the top level", event);
      this.failAll("The tracing engine could not be started.");
    });
    onceOnly(worker, "onmessageerror", (event) => {
      console.error("[trace] the worker sent a message this page could not deserialize", event);
      this.failAll("The tracing engine sent something unreadable.");
    });

    this.worker = worker;
    return worker;
  }

  private onMessage(message: FromWorker): void {
    const entry = this.inFlight.get(message.requestId);
    // Aborted, disposed or superseded: its promise was settled when that happened, and a late
    // `done` for it must not be resolved onto a caller who has moved on.
    if (entry === undefined) return;
    if (message.type === "progress") {
      entry.onProgress?.({
        stageId: message.stageId,
        label: message.label,
        fraction: message.fraction
      });
      return;
    }
    this.inFlight.delete(message.requestId);
    if (message.type === "done") entry.resolve(message.result);
    else entry.reject(new Error(message.message));
  }

  private failAll(sentence: string): void {
    const entries = Array.from(this.inFlight.values());
    this.inFlight.clear();
    // The worker is in an unknown state, so it is dropped rather than reused. The next `trace` builds
    // a fresh one — the right answer for a transient failure, and merely the same error again for a
    // permanent one.
    this.worker?.terminate();
    this.worker = null;
    for (const entry of entries) entry.reject(new TraceUnavailableError(sentence));
  }

  private post(message: ToWorker): void {
    try {
      this.worker?.postMessage(message);
    } catch {
      /* The worker is already gone, so there is nothing left to cancel. */
    }
  }
}

/**
 * @returns whether the caller has already aborted.
 *
 * A function rather than `request.signal?.aborted === true` written twice, and the reason is a
 * TypeScript one worth stating: `AbortSignal.aborted` is declared `readonly boolean`, so the compiler
 * narrows it to `false` after the first check and calls the SECOND one — the one across the `await`,
 * the only one that can actually catch anything — an impossible comparison. Reading it through a call
 * is what keeps the post-await check compiling and doing its job.
 */
function aborted(signal: AbortSignal | undefined): boolean {
  return signal !== undefined && signal.aborted;
}

/** The real worker factory, loaded on demand. See `spawnTraceWorker.ts` for why it is a separate file. */
async function loadSpawn(): Promise<() => TraceWorkerLike> {
  try {
    return (await import("./spawnTraceWorker")).spawnTraceWorker;
  } catch (err) {
    // "Check your connection" is the right thing to say to a designer on a courtyard hotspot and the
    // wrong thing to leave as the only record: the same throw is what a chunk the build never emitted
    // produces, and that one is a deployment bug, not a network one. The cause tells them apart.
    throw new TraceUnavailableError(
      "The tracing engine could not be loaded. Check your connection and reload the page.",
      { cause: err }
    );
  }
}

/**
 * Assigns a handler that disarms itself, so one broken worker cannot fail the same promises twice.
 *
 * The event is passed through rather than dropped. An `ErrorEvent` from a module worker carries
 * `message`, `filename` and `lineno`, and for the failure `spawnTraceWorker.ts` warns about — a
 * worker chunk the build never emitted, which has no build-time signal at all — that event is the
 * only thing in the browser that names the file that 404ed.
 */
function onceOnly(
  worker: TraceWorkerLike,
  slot: "onerror" | "onmessageerror",
  handler: (event: Event) => void
): void {
  worker[slot] = (event: Event) => {
    worker.onerror = null;
    worker.onmessageerror = null;
    handler(event);
  };
}

/**
 * The engine's parameter defaults and sanitiser, fetched on demand.
 *
 * Async, and that is the point rather than an inconvenience: `engine/params.ts` is 843 lines and
 * imports nothing, so a static import of it here would put ~28 KB of source in the bundle of every
 * page that ever touches this module — the exact shape of the thing the GSAP rule quoted in this
 * file's header forbids. The awaited chunk arrives long before a designer has chosen an image.
 */
export async function loadTraceParams(): Promise<{
  defaults: TraceParams;
  sanitize: (input: TraceParamsInput) => TraceParams;
}> {
  const params = await import("./engine/params");
  return { defaults: params.defaultTraceParams(), sanitize: params.sanitizeTraceParams };
}

/**
 * Pixels ready to hand to {@link Tracer.trace}, cloned off the caller's buffer.
 *
 * A convenience with one job: making the detach documented on {@link TraceRequest.image} safe by
 * construction. Pass the `ImageData` from a canvas — or any `{data, width, height}` — and the clone
 * is what gets transferred, leaving the caller's own pixels intact for the next trace.
 */
export function transferableFrom(source: {
  readonly data: Uint8ClampedArray | Uint8Array;
  readonly width: number;
  readonly height: number;
}): TransferableImage {
  const clone = source.data.slice();
  return {
    width: source.width,
    height: source.height,
    // The buffer of a freshly sliced typed array is always a plain ArrayBuffer; the declared
    // `ArrayBufferLike` also admits SharedArrayBuffer, which is not transferable at all.
    data: clone.buffer as ArrayBuffer
  };
}
