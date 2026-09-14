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
 * ── RENDERS NOTHING WHEN THERE IS NOTHING TO TRACE ────────────────────────────────────────────
 *
 * No image attached means no panel, rather than a disabled one. A disabled control invites the
 * question "why can't I press this", and the answer — "attach a photograph first" — is already
 * obvious from the empty capture field directly above it.
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

  if (!image) return null;

  return (
    <TracePanel
      image={image}
      imageName={image.name}
      disabled={disabled}
      onAttach={(file) => {
        onFilesChange([...files, file]);
      }}
    />
  );
}
