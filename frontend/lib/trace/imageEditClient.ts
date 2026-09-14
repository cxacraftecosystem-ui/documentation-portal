/**
 * The page-side half of the crop-and-sharpen worker.
 *
 * Shaped after `traceClient.ts` on purpose — one class per surface, `dispose()` owed by whoever built
 * it, the worker started lazily on first use, and the `new Worker(...)` expression reached through a
 * dynamic import so this module stays plain TypeScript. Read that file's header for the reasoning; the
 * three differences are below.
 *
 * ── ONE REQUEST AT A TIME, AND THE NEWEST WINS ──────────────────────────────────────────────────
 *
 * The trace client supersedes a running trace when a newer one arrives because a slider drag issues
 * dozens. This one is driven by a button, so it never has a queue to manage — but it can be pressed
 * twice, and it must not answer the first press with the second press's pixels. Every request carries
 * an id and the reply is matched on it; a superseded request is settled with
 * {@link ImageEditCancelledError}, which callers treat as "the designer replaced this", not as a
 * failure. Same shape, same reason, as `SketchTraceField`'s `abortRef`.
 *
 * ── THE INPUT BUFFER IS COPIED HERE, NOT TRANSFERRED FROM THE CALLER ────────────────────────────
 *
 * `postMessage` transfers the request's buffer, which detaches it in this thread. The caller's buffer
 * is the decoded photograph, and the panel has to be able to crop it again — a designer who narrows a
 * crop and widens it back must not find the second attempt reading a detached array. So {@link edit}
 * slices what it is given. The engine's own client learned the opposite half of this the hard way:
 * `SketchTraceField` notes that transferring the original made "the second trace produces a blank
 * image", which surfaces as a rendering bug rather than as an error.
 *
 * ── A DEAD WORKER IS A SENTENCE, NOT A HANG ─────────────────────────────────────────────────────
 *
 * The realistic failure here is an allocation a 2 GB handset will not make, and a worker killed for
 * memory does not send a message: it fires `error` — or nothing at all. Both `onerror` and
 * `onmessageerror` therefore settle every pending request, and the worker is dropped so the next press
 * starts a fresh one. Without that, the promise never settles and the panel's spinner never stops,
 * which is the failure mode this repository catalogues as "an effect guarded by a ref".
 */

import type { CropRect, EditablePixels, SharpenSettings } from "./imageEdit";
import type { EditReplyMessage, EditRequestMessage } from "./imageEdit.worker";

/** "This device cannot do it at all" — a browser with no module workers, or a chunk that never came. */
export class ImageEditUnavailableError extends Error {
  constructor(message: string, options?: { cause?: unknown }) {
    super(message, options);
    this.name = "ImageEditUnavailableError";
  }
}

/** "The designer replaced this request." Never shown to anybody. */
export class ImageEditCancelledError extends Error {
  constructor() {
    super("The image edit was superseded.");
    this.name = "ImageEditCancelledError";
  }
}

/**
 * The one sentence for "the worker did not arrive".
 *
 * Worded to sit beside `traceRuntime.ENGINE_UNAVAILABLE` without contradicting it: the trace can be
 * available while this is not (a browser that starts one module worker starts both, but a dropped
 * chunk request is per chunk), and the difference has to be describable.
 */
const UNAVAILABLE =
  "The cropping and sharpening tools could not be loaded on this device. The photograph and the " +
  "trace are unaffected — the trace's own “Sharpen amount” control still works.";

/** What comes back from a completed edit. `data`'s buffer belongs to the caller. */
export interface EditedPixels extends EditablePixels {
  /**
   * What was done, as the clause the exported SVG's provenance note carries. Empty when nothing was.
   *
   * PASSED THROUGH FROM THE WORKER RATHER THAN REBUILT BY THE CALLER. `imageEdit.describeEdit` writes
   * it beside the arithmetic that produced the pixels — see that function's own header, and the reply
   * type's note on why no component can call it.
   */
  readonly note: string;
  /** Measured on this device, for this frame. Shown on screen; never estimated. */
  readonly millis: number;
}

export interface EditCall {
  readonly pixels: EditablePixels;
  readonly crop: CropRect;
  readonly sharpen: SharpenSettings;
}

type Pending = {
  readonly resolve: (value: EditedPixels) => void;
  readonly reject: (error: unknown) => void;
};

export class ImageEditor {
  private worker: Worker | null = null;
  private starting: Promise<Worker> | null = null;
  private nextId = 1;
  private pending = new Map<number, Pending>();
  private disposed = false;

  /**
   * Crop and sharpen one frame.
   *
   * @throws {ImageEditUnavailableError} when no worker can be started.
   * @throws {ImageEditCancelledError} when a later call superseded this one.
   * @throws {Error} with the worker's own sentence when the frame was refused — over the pixel cap,
   *   or an allocation this device declined.
   */
  async edit(call: EditCall): Promise<EditedPixels> {
    if (this.disposed) throw new ImageEditCancelledError();
    const worker = await this.ensureWorker();
    if (this.disposed) throw new ImageEditCancelledError();

    // SUPERSEDE EVERYTHING STILL IN FLIGHT. One button, one answer: a second press means the first
    // request's pixels are no longer the ones anybody asked for, and delivering them would put a
    // stale frame under a fresh readout.
    this.settleAll(new ImageEditCancelledError());

    const id = this.nextId;
    this.nextId += 1;
    // SLICED, NOT TRANSFERRED — see the header. The copy is 4 bytes per pixel and it is what keeps
    // the caller's decoded photograph alive for the next crop.
    const data = call.pixels.data.slice();
    const message: EditRequestMessage = {
      id,
      data,
      width: call.pixels.width,
      height: call.pixels.height,
      crop: call.crop,
      sharpen: call.sharpen
    };
    const answer = new Promise<EditedPixels>((resolve, reject) => {
      this.pending.set(id, { resolve, reject });
    });
    worker.postMessage(message, [data.buffer]);
    return await answer;
  }

  /** Stop the worker and settle anything outstanding. Owed by whoever constructed this. */
  dispose(): void {
    this.disposed = true;
    this.settleAll(new ImageEditCancelledError());
    this.worker?.terminate();
    this.worker = null;
    this.starting = null;
  }

  private async ensureWorker(): Promise<Worker> {
    if (this.worker !== null) return this.worker;
    if (this.starting !== null) return await this.starting;
    const started = (async (): Promise<Worker> => {
      if (typeof Worker === "undefined") {
        throw new ImageEditUnavailableError(UNAVAILABLE);
      }
      let worker: Worker;
      try {
        // The dynamic import is the weight boundary AND the CommonJS escape hatch. See
        // `spawnImageEditWorker.ts`.
        const { spawnImageEditWorker } = await import("./spawnImageEditWorker");
        worker = spawnImageEditWorker();
      } catch (error) {
        throw new ImageEditUnavailableError(UNAVAILABLE, { cause: error });
      }
      worker.onmessage = (event: MessageEvent<EditReplyMessage>) => this.receive(event.data);
      // BOTH FAILURE EVENTS, BECAUSE THEY MEAN DIFFERENT THINGS AND BOTH LEAVE A PROMISE PENDING.
      // `error` is the worker throwing or dying; `messageerror` is a reply that could not be
      // deserialised. Either way the worker is dropped, so the next press builds a fresh one rather
      // than posting into a corpse.
      worker.onerror = () => this.crash();
      worker.onmessageerror = () => this.crash();
      return worker;
    })();
    this.starting = started;
    try {
      const worker = await started;
      if (this.disposed) {
        worker.terminate();
        throw new ImageEditCancelledError();
      }
      this.worker = worker;
      return worker;
    } finally {
      if (this.starting === started) this.starting = null;
    }
  }

  private receive(reply: EditReplyMessage): void {
    const entry = this.pending.get(reply.id);
    if (!entry) return;
    this.pending.delete(reply.id);
    if (reply.ok) {
      entry.resolve({
        data: reply.data,
        width: reply.width,
        height: reply.height,
        note: reply.note,
        millis: reply.millis
      });
      return;
    }
    entry.reject(new Error(reply.reason));
  }

  private crash(): void {
    this.settleAll(
      new Error(
        "The photograph could not be processed on this device — it ran out of memory partway " +
          "through. A smaller crop is the way through; the photograph itself is untouched."
      )
    );
    this.worker?.terminate();
    this.worker = null;
  }

  private settleAll(error: unknown): void {
    const entries = [...this.pending.values()];
    this.pending.clear();
    for (const entry of entries) entry.reject(error);
  }
}
