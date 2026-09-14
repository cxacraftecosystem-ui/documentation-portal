/// <reference lib="webworker" />

/**
 * The worker that crops and sharpens. Fifty lines of plumbing around `imageEdit.ts`.
 *
 * WHY A WORKER FOR SOMETHING THAT IS ONE BUTTON PRESS. Because of what the arithmetic is. A separable
 * Gaussian at radius 1.5 is eleven taps in each of two passes: for an 8 MP frame that is 176 million
 * multiply-adds in a straight TypeScript loop, plus a luminance pass and a recombination pass over 8
 * million pixels each. On the page thread that is a frozen tab — not a slow one, a frozen one, with no
 * spinner able to animate and no way for the designer to cancel — which is the same reason
 * `SketchTraceField`'s second property gives for the trace itself living in a worker.
 *
 * It is deliberately NOT the same worker as the trace: see `spawnImageEditWorker.ts` on why the
 * vendored files stay byte-identical.
 *
 * ── THE BUFFERS ARE TRANSFERRED IN BOTH DIRECTIONS, AND THE CALLER KNOWS IT ─────────────────────
 *
 * An 8 MP RGBA frame is 32 MB. Structured-cloning it in and out is 64 MB of copying either side of the
 * work, on a device whose problem is memory. So the request's buffer arrives transferred — detached in
 * the caller the moment `postMessage` returns — and the answer's buffer goes back the same way.
 * `imageEditClient.ts` states that contract at its own boundary and hands over a copy, because the
 * page keeps the decoded photograph and must be able to crop it again.
 */

import { applyEdit, describeEdit, planSharpen, type CropRect, type SharpenSettings } from "./imageEdit";

/** What the client posts in. `data`'s buffer is transferred. */
interface EditRequestMessage {
  readonly id: number;
  readonly data: Uint8ClampedArray;
  readonly width: number;
  readonly height: number;
  readonly crop: CropRect;
  readonly sharpen: SharpenSettings;
}

/** What comes back. `data`'s buffer is transferred on the success branch. */
type EditReplyMessage =
  | {
      readonly id: number;
      readonly ok: true;
      readonly data: Uint8ClampedArray;
      readonly width: number;
      readonly height: number;
      /**
       * What was done to the pixels, as the clause the exported SVG carries. Empty when nothing was.
       *
       * BUILT HERE BECAUSE THIS IS THE ONLY SIDE THAT CAN. `imageEdit.describeEdit` owns the wording
       * and `imageEdit.clampCrop` owns the rectangle, and no component can import either — that module
       * pulls `engine/convolve` onto the page's bundle, which `SketchTraceField`'s fourth property
       * forbids. A component-side copy of the sentence is what shipped before, and it was an untested
       * transcription that reported the REQUESTED crop rather than the one the pixels came from.
       */
      readonly note: string;
      /** Measured, on this device, for this frame. The only timing figure the panel ever shows. */
      readonly millis: number;
    }
  | { readonly id: number; readonly ok: false; readonly reason: string };

const scope = self as unknown as DedicatedWorkerGlobalScope;

scope.onmessage = (event: MessageEvent<EditRequestMessage>) => {
  const request = event.data;
  const startedAt = performance.now();
  try {
    /*
      THE CAP IS CHECKED HERE AS WELL AS ON THE PAGE, AND THAT IS NOT BELT-AND-BRACES.

      The page checks it to draw a sentence and disable a button; this checks it because this is the
      side that would allocate the memory. A caller that got the arithmetic wrong — or a future caller
      that forgot the check existed — otherwise reaches an out-of-memory kill, which arrives at the
      client as a dead worker with no message at all. `planSharpen` owns the number and the sentence,
      so both sides refuse for the same reason in the same words.
    */
    const plan = planSharpen(request.crop.width * request.crop.height, request.sharpen.radius);
    if (plan.refusal !== null && request.sharpen.amount > 0) {
      const refusal: EditReplyMessage = { id: request.id, ok: false, reason: plan.refusal };
      scope.postMessage(refusal);
      return;
    }

    const edited = applyEdit(
      { data: request.data, width: request.width, height: request.height },
      { crop: request.crop, sharpen: request.sharpen }
    );
    const reply: EditReplyMessage = {
      id: request.id,
      ok: true,
      data: edited.data,
      width: edited.width,
      height: edited.height,
      // The SAME `clampCrop` `applyEdit` just read the pixels through, so the sentence and the
      // rectangle cannot disagree about what was cropped.
      note: describeEdit({ crop: request.crop, sharpen: request.sharpen }, request.width, request.height),
      millis: performance.now() - startedAt
    };
    scope.postMessage(reply, [edited.data.buffer]);
  } catch (error) {
    // A REFUSAL, NOT A THROW. An uncaught throw inside a worker reaches the page as an `error` event
    // with a browser-written message, and the client would have to invent a sentence about a failure
    // it cannot see. The commonest real cause here is an allocation the device would not make, so the
    // sentence says that and says what to do about it.
    const reply: EditReplyMessage = {
      id: request.id,
      ok: false,
      reason:
        error instanceof Error && error.message
          ? `The photograph could not be processed on this device: ${error.message}`
          : "The photograph could not be processed on this device. Try a smaller crop."
    };
    scope.postMessage(reply);
  }
};

export type { EditRequestMessage, EditReplyMessage };
