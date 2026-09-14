"use client";

import { useMemo } from "react";

import { TracePanel } from "@/components/trace/TracePanel";

/**
 * The tracer, mounted under a `MediaCaptureField`, on whichever photograph was captured last.
 *
 * ── WHY THIS EXISTS RATHER THAN THE JSX BEING REPEATED IN EACH FORM ───────────────────────────
 *
 * Six `MediaCaptureField`s across four record forms would otherwise each carry the same inline
 * "find the image to trace" expression. That is not a style objection: THE CHOICE OF WHICH IMAGE IS
 * A REAL DECISION with a defensible answer and a wrong one, and six copies of it drift the first
 * time somebody changes their mind about it in one form. It lives here, once, argued below.
 *
 * ── WHICH IMAGE, AND WHY THE LAST ONE ─────────────────────────────────────────────────────────
 *
 * The most recently added image, and nothing else offered. A researcher photographs a sketch and
 * then wants it traced — the thing they just did is the thing they mean. The alternatives are worse:
 *
 *   * A PICKER over every attached image puts a control in front of the common case where there is
 *     exactly one, and makes the panel's first press a decision rather than the trace itself.
 *   * THE FIRST image is stable but wrong: it is whatever was attached earliest, which on a record
 *     that has been edited twice is the least likely to be the sketch in hand.
 *
 * Re-tracing an older attachment is not lost — it is reached from `ExistingMedia`, where a record's
 * uploaded images are listed and the panel takes a URL instead of a `File`. This mount is for the
 * just-captured case only.
 *
 * ── NOTHING HERE UPLOADS, AND THAT IS THE PANEL'S FIRST PROPERTY ──────────────────────────────
 *
 * `onAttach` appends the derived file to the SAME array `MediaCaptureField` is already holding, so
 * the traced plate goes in through the ordinary door: eager pre-upload, multipart, per-file retry,
 * orphan cleanup and the offline draft store all apply to it unchanged, because it is not a special
 * kind of file. A panel that uploaded its own output would be a second upload path to keep working
 * offline, in a product whose whole point is working offline.
 *
 * ── IT RENDERS EVEN WITH NO IMAGE, AND THE FIRST VERSION OF THIS FILE GOT THAT WRONG ──────────
 *
 * This wrapper used to `return null` when no image was attached. The reasoning was that a disabled
 * control invites "why can't I press this", and the answer is obvious from the empty capture field
 * above it. IT IS NOT OBVIOUS, because there is nothing there to be obvious ABOUT: a researcher
 * opening a tool or a product form saw no tracer at all and reported the feature missing from those
 * pages. A control nobody can find has not been shipped.
 *
 * `TracePanel` was built for this and says so at {@link TracePanelProps.image}: "`null` IS A STATE
 * AND NOT AN ERROR: the host has nothing chosen yet. The panel says where the one picker is rather
 * than drawing a second one." Its collapsed trigger is a self-contained card — title, description,
 * chevron — that needs no image; the image is only read when the panel is OPENED. So the honest
 * thing is to hand it the null and let it explain itself, which is what it already knows how to do.
 *
 * The panel is therefore present on every form that can take a photograph, whether or not one has
 * been taken yet — which is the whole of the requirement.
 */
export function TraceFromCapture({
  files,
  onFilesChange,
  disabled
}: {
  files: File[];
  onFilesChange: (files: File[]) => void;
  disabled?: boolean;
}) {
  const image = useMemo(() => {
    // Walked backwards rather than `.filter().at(-1)`, so a long attachment list stops at the first
    // hit instead of building an array to throw away.
    for (let index = files.length - 1; index >= 0; index -= 1) {
      const candidate = files[index];
      // `type` is the browser's sniff of the picked file. An empty string happens — some Android
      // pickers hand back a blank MIME for a JPEG — and the extension is the fallback the rest of
      // this codebase uses in the same situation.
      if (candidate.type.startsWith("image/")) return candidate;
      if (candidate.type === "" && /\.(jpe?g|png|webp|bmp|gif)$/i.test(candidate.name)) return candidate;
    }
    return null;
  }, [files]);

  return (
    <TracePanel
      image={image}
      imageName={image?.name}
      disabled={disabled}
      onAttach={(file) => {
        onFilesChange([...files, file]);
      }}
      // `disabled` is the HOST's answer to "may this form be edited at all", never this component's
      // answer to "is there a photograph". The panel handles the second itself; overloading the
      // first with it is how the card disappears again under a different name.
    />
  );
}
