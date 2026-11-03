import type { CropRect, SharpenSettings } from "@/lib/trace/imageEdit";
import type { EditCall, EditedPixels, ImageEditor } from "@/lib/trace/imageEditClient";

export interface ImageEditRuntime {
  readonly ImageEditor: new () => ImageEditor;
  readonly isUnavailable: (error: unknown) => boolean;
  readonly isCancelled: (error: unknown) => boolean;
}

let editorPromise: Promise<ImageEditRuntime> | null = null;

export async function loadImageEditor(): Promise<ImageEditRuntime> {
  if (editorPromise !== null) return await editorPromise;
  const started = (async (): Promise<ImageEditRuntime> => {
    let client: typeof import("@/lib/trace/imageEditClient");
    try {
      client = await import("@/lib/trace/imageEditClient");
    } catch (error) {
      throw new Error(
        "The cropping and sharpening tools could not be loaded on this device. The photograph and " +
          "the trace are unaffected — the trace's own “Sharpen amount” control still works.",
        { cause: error }
      );
    }
    return {
      ImageEditor: client.ImageEditor,
      isUnavailable: (error) => error instanceof client.ImageEditUnavailableError,
      isCancelled: (error) => error instanceof client.ImageEditCancelledError
    };
  })();
  editorPromise = started;
  try {
    return await started;
  } catch (error) {
    if (editorPromise === started) editorPromise = null;
    throw error;
  }
}

export type { CropRect, EditCall, EditedPixels, ImageEditor, SharpenSettings };
