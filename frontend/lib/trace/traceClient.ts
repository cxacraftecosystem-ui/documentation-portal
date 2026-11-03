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

export interface TraceWorkerLike {
  postMessage(message: ToWorker, options?: { transfer: Transferable[] }): void;
  terminate(): void;
  onmessage: ((event: MessageEvent) => void) | null;
  onerror: ((event: ErrorEvent) => void) | null;
  onmessageerror: ((event: MessageEvent) => void) | null;
}

export class TraceUnavailableError extends Error {
  constructor(message: string, options?: ErrorOptions) {
    super(message, options);
    this.name = "TraceUnavailableError";
  }
}

export class TraceCancelledError extends Error {
  constructor(message = "The trace was cancelled.") {
    super(message);
    this.name = "TraceCancelledError";
  }
}

export interface TraceProgress {
  readonly stageId: string;
  readonly label: string;
  readonly fraction: number;
}

export interface TraceRequest {
  readonly image: TransferableImage;
  readonly params: TraceParams | TraceParamsInput;
  readonly preview?: boolean;
  readonly onProgress?: (progress: TraceProgress) => void;
  readonly signal?: AbortSignal;
}

export interface TracerOptions {
  readonly spawn?: () => TraceWorkerLike;
}

export class Tracer {
  private readonly spawn: (() => TraceWorkerLike) | null;
  private worker: TraceWorkerLike | null = null;

  private starting: Promise<TraceWorkerLike> | null = null;

  private nextId = 0;
  private disposed = false;

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

  get busy(): boolean {
    return this.inFlight.size > 0;
  }

  async trace(request: TraceRequest): Promise<SerializedTraceResult> {
    if (aborted(request.signal)) throw new TraceCancelledError();
    const worker = await this.ensureWorker();
    if (this.disposed) throw new TraceCancelledError("This tracer has been disposed.");
    if (aborted(request.signal)) throw new TraceCancelledError();

    const requestId = ++this.nextId;

    for (const entry of Array.from(this.inFlight.values())) {
      entry.reject(new TraceCancelledError("Superseded by a newer trace."));
    }
    this.inFlight.clear();

    return await new Promise<SerializedTraceResult>((resolve, reject) => {
      const onAbort = () => {
        if (!this.inFlight.delete(requestId)) return;
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
        params: request.params as TraceParams,
        preview: request.preview === true
      };

      try {
        worker.postMessage(message, { transfer: [request.image.data] });
      } catch {
        this.inFlight.delete(requestId);
        request.signal?.removeEventListener("abort", onAbort);
        reject(
          new Error(
            "That image could not be handed to the tracing engine. It may already have been used " +
              "for a trace — pass a fresh copy of the pixels."
          )
        );
      }
    });
  }

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
      if (this.starting === starting) this.starting = null;
    }
  }

  private async startWorker(): Promise<TraceWorkerLike> {
    const spawn = this.spawn ?? (await loadSpawn());

    let worker: TraceWorkerLike;
    try {
      worker = spawn();
    } catch (err) {
      throw new TraceUnavailableError(
        "This browser would not start a background worker, and tracing is never run on the page " +
          "thread. Try a current version of Chrome, Edge, Firefox or Safari.",
        { cause: err }
      );
    }

    if (this.disposed) {
      worker.terminate();
      throw new TraceCancelledError("This tracer has been disposed.");
    }

    worker.onmessage = (event: MessageEvent) => this.onMessage(event.data as FromWorker);
    onceOnly(worker, "onerror", (event) => {
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
    this.worker?.terminate();
    this.worker = null;
    for (const entry of entries) entry.reject(new TraceUnavailableError(sentence));
  }

  private post(message: ToWorker): void {
    try {
      this.worker?.postMessage(message);
    } catch {
    }
  }
}

function aborted(signal: AbortSignal | undefined): boolean {
  return signal !== undefined && signal.aborted;
}

async function loadSpawn(): Promise<() => TraceWorkerLike> {
  try {
    return (await import("./spawnTraceWorker")).spawnTraceWorker;
  } catch (err) {
    throw new TraceUnavailableError(
      "Tracing engine didn’t load. Reload the page.",
      { cause: err }
    );
  }
}

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

export async function loadTraceParams(): Promise<{
  defaults: TraceParams;
  sanitize: (input: TraceParamsInput) => TraceParams;
}> {
  const params = await import("./engine/params");
  return { defaults: params.defaultTraceParams(), sanitize: params.sanitizeTraceParams };
}

export function transferableFrom(source: {
  readonly data: Uint8ClampedArray | Uint8Array;
  readonly width: number;
  readonly height: number;
}): TransferableImage {
  const clone = source.data.slice();
  return {
    width: source.width,
    height: source.height,
    data: clone.buffer as ArrayBuffer
  };
}
