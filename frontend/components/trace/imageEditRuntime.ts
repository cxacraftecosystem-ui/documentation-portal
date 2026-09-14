/**
 * The crop-and-sharpen worker, fetched only once a researcher has asked to frame something.
 *
 * ════════════════════════════════════════════════════════════════════════════════════════════════
 * WHY THIS IS A SEPARATE FILE, WHICH IS A CONSTRAINT AND NOT A DESIGN
 * ════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * `traceRuntime.ts` says, in its own header, exactly where this door belongs and what it must look
 * like: "The door belongs HERE, as a `loadImageEditor()` memoised on its promise and cleared on
 * rejection, shaped like {@link loadTraceRuntime} below — and NOT folded into it, because the two are
 * independent." That is right, and the designer portal — where this whole feature comes from — puts
 * it there: `components/sketches/upload/traceRuntime.ts` carries both doors in one file.
 *
 * IT IS NOT THERE HERE BECAUSE THIS LANE MAY NOT EDIT THAT FILE. The frame tool was dropped from this
 * port and is being put back under an instruction that permits exactly one existing file to change
 * (`TracePanel.tsx`); everything else has to arrive as a new module. So this file is that door, in a
 * file of its own, written to the shape `traceRuntime.ts` specified down to the memoisation and the
 * sentence. **When whoever owns `traceRuntime.ts` moves it in, delete this file and re-point the two
 * importers (`FramePanel.tsx` and `frameGeometry.ts`); do not keep both.** Two memos of one promise
 * would start two workers on a device that had trouble starting one.
 *
 * ── WHAT IT IS FOR: THE BUNDLE PROMISE, WHICH IS THE SAME ONE `traceRuntime.ts` KEEPS ───────────
 *
 * `TracePanel.tsx`'s third property is that the engine is not on a page's graph until somebody traces
 * something, and a component that imported `@/lib/trace/imageEditClient` at the top would put the
 * client, the worker reference and `engine/contrast` + `engine/convolve` on the bundle of every page
 * that mounts the panel — for every visitor who never touches a photograph. The import below is
 * inside a function body. Nothing in this module is reachable statically: the two `import type`s at
 * the top are erased by TypeScript outright.
 *
 * IT IS NOT FOLDED INTO `loadTraceRuntime` either, for that file's own stated reason. The two are
 * independent: a researcher who traces without cropping never fetches this chunk, and one who crops
 * on a browser whose trace worker failed still gets a useful sentence rather than one failure
 * standing in for the other.
 */

import type { CropRect, SharpenSettings } from "@/lib/trace/imageEdit";
import type { EditCall, EditedPixels, ImageEditor } from "@/lib/trace/imageEditClient";

/** The parts of `imageEditClient` this feature uses, resolved. */
export interface ImageEditRuntime {
  /**
   * A CONSTRUCTOR, not an instance, for `TraceRuntime.Tracer`'s reason: an editor owns a worker, a
   * worker is per-surface, and only the component knows when its surface goes away and `dispose()`
   * is owed.
   */
  readonly ImageEditor: new () => ImageEditor;
  /** True for "this device cannot crop or sharpen at all", as opposed to "this frame was refused". */
  readonly isUnavailable: (error: unknown) => boolean;
  /** True for a request the researcher replaced. Never shown to anybody. */
  readonly isCancelled: (error: unknown) => boolean;
}

let editorPromise: Promise<ImageEditRuntime> | null = null;

/**
 * Load the crop-and-sharpen client, once per tab.
 *
 * Memoised on the PROMISE and cleared on rejection, exactly as `loadTraceRuntime` is and for the same
 * measured reason: two panels mounting in one tick share a fetch, and a field hotspot that dropped
 * one chunk request must not have poisoned the feature for the rest of the session.
 */
export async function loadImageEditor(): Promise<ImageEditRuntime> {
  if (editorPromise !== null) return await editorPromise;
  const started = (async (): Promise<ImageEditRuntime> => {
    let client: typeof import("@/lib/trace/imageEditClient");
    try {
      client = await import("@/lib/trace/imageEditClient");
    } catch (error) {
      // WRAPPED, AND THE SENTENCE IS THE POINT — see `loadTraceRuntime`'s note on the same throw. An
      // unwrapped dynamic import puts "Failed to fetch dynamically imported module:
      // https://…/chunk-4b1e.js" on a researcher's screen: true, useless, and not written for a
      // reader.
      //
      // A PLAIN `Error`, DELIBERATELY, AND NOT `ImageEditUnavailableError`. That class lives in the
      // module that just failed to load, so it is not reachable from here — and reaching for it a
      // second time to build the error that says the first attempt failed would be a fiction. The
      // caller does not need the class either: this function has exactly one failure, so a rejection
      // from it IS "this device cannot crop or sharpen". `isUnavailable` is for the errors that come
      // back from an edit, where "the frame was refused" and "the tool is gone" are different things.
      throw new Error(
        "The cropping and sharpening tools could not be loaded on this device. The photograph and " +
          "the trace are unaffected — the trace's own “Sharpen amount” control still works.",
        { cause: error }
      );
    }
    return {
      ImageEditor: client.ImageEditor,
      // `instanceof` against the classes THIS module instance exported, so two copies of the module
      // cannot defeat the test — the failure a `name === "..."` string comparison hides.
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

/* ────────────────────────────────────────────────────────────────────────────
 * Re-exported types, so a component imports this file and not the engine
 * ──────────────────────────────────────────────────────────────────────────── */

export type { CropRect, EditCall, EditedPixels, ImageEditor, SharpenSettings };
