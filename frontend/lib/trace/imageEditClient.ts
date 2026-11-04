import type { CropRect, EditablePixels, SharpenSettings } from "./imageEdit";
import type { EditReplyMessage, EditRequestMessage } from "./imageEdit.worker";

export class ImageEditUnavailableError extends Error {
  constructor(message: string, options?: { cause?: unknown }) {
    super(message, options);
    this.name = "ImageEditUnavailableError";
  }
}

export class ImageEditCancelledError extends Error {
  constructor() {
    super("The image edit was superseded.");
    this.name = "ImageEditCancelledError";
  }
}

const UNAVAILABLE =
  "The cropping and sharpening tools could not be loaded on this device. The photograph and the " +
  "trace are unaffected — the trace's own “Sharpen amount” control still works.";

export interface EditedPixels extends EditablePixels {
  readonly note: string;
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

  async edit(call: EditCall): Promise<EditedPixels> {
    if (this.disposed) throw new ImageEditCancelledError();
    const worker = await this.ensureWorker();
    if (this.disposed) throw new ImageEditCancelledError();

    this.settleAll(new ImageEditCancelledError());

    const id = this.nextId;
    this.nextId += 1;
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
        const { spawnImageEditWorker } = await import("./spawnImageEditWorker");
        worker = spawnImageEditWorker();
      } catch (error) {
        throw new ImageEditUnavailableError(UNAVAILABLE, { cause: error });
      }
      worker.onmessage = (event: MessageEvent<EditReplyMessage>) => this.receive(event.data);
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
        "Ran out of memory. Try a smaller crop."
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
