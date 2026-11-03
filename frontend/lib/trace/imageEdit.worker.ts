/// <reference lib="webworker" />

import { applyEdit, describeEdit, planSharpen, type CropRect, type SharpenSettings } from "./imageEdit";

interface EditRequestMessage {
  readonly id: number;
  readonly data: Uint8ClampedArray;
  readonly width: number;
  readonly height: number;
  readonly crop: CropRect;
  readonly sharpen: SharpenSettings;
}

type EditReplyMessage =
  | {
      readonly id: number;
      readonly ok: true;
      readonly data: Uint8ClampedArray;
      readonly width: number;
      readonly height: number;
      readonly note: string;
      readonly millis: number;
    }
  | { readonly id: number; readonly ok: false; readonly reason: string };

const scope = self as unknown as DedicatedWorkerGlobalScope;

scope.onmessage = (event: MessageEvent<EditRequestMessage>) => {
  const request = event.data;
  const startedAt = performance.now();
  try {
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
      note: describeEdit({ crop: request.crop, sharpen: request.sharpen }, request.width, request.height),
      millis: performance.now() - startedAt
    };
    scope.postMessage(reply, [edited.data.buffer]);
  } catch (error) {
    const reply: EditReplyMessage = {
      id: request.id,
      ok: false,
      reason:
        error instanceof Error && error.message
          ? `The photograph could not be processed on this device: ${error.message}`
          : "Couldn’t process the photo. Try a smaller crop."
    };
    scope.postMessage(reply);
  }
};

export type { EditRequestMessage, EditReplyMessage };
