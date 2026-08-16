/**
 * Rich text in a column that is, and stays, `String?`.
 *
 * THE CONSTRAINT, WHICH IS NOT NEGOTIABLE HERE. `Artisan.notes`, `Artisan.address`,
 * `ProductDocumentation.remarks/rawMaterialsUsed/mainToolsUsed/productFunctionUse`,
 * `ToolDocumentation.remarks/suggestionsForToolImprovement` are `String?` in
 * `backend/prisma/schema.prisma` and `str | None` in `backend/app/schemas/records.py`. There is no
 * migration in this change and there must not be one. Four kinds of reader are already pointed at
 * those exact columns and every one of them treats the value as prose:
 *
 *   - FREE-TEXT SEARCH, which is a raw Prisma `contains` against the column itself —
 *     `artisans.py:229` (`notes`), `products.py:89-91` (`rawMaterialsUsed`, `mainToolsUsed`,
 *     `remarks`), `tools.py:96-99` (`remarks`). A JSON document in one of those makes the search box
 *     match the word "PARAGRAPH" and miss any phrase that happens to span a span boundary.
 *   - THE EXPORTS, through one three-line function: `cell()` at `record_fields.py:102` is
 *     `"" if value is None else str(value).strip()`, and it feeds the data-browser panel, the
 *     `/data/report` workbook, `details.txt` in the dataset zip and the products/tools CSVs.
 *   - THE REVIEW PANEL, `components/review/reviewEditFields.ts`, which renders `notes`, `address`
 *     and the two `remarks` as plain multiline text.
 *   - THE ANDROID FORMS, which read the same strings into `OutlinedTextField`s.
 *
 * THE TRAP THIS FILE EXISTS TO AVOID, AND IT IS SHARPER IN THIS REPOSITORY THAN IN THE ONE THIS CODE
 * CAME FROM. There, a document stringified into a `String?` column at least met readers that OWN a
 * rich-text model and could be taught to flatten it (`rich_text.py`, `RichText.kt`). Here, the model
 * arrived only with this change:
 *
 *   - `frontend/lib/richText.ts` — this browser, ported with the editor.
 *   - `android/…/ui/richtext/RichText.kt` and `ui/RecordProseText.kt` — the handset, ported in the
 *     same batch of work and implementing the SAME plain-when-plain rule, deliberately. The two
 *     surfaces edit the same columns and whichever wrote last wins, so if the two rules ever
 *     disagree, a field edited on a phone reads as changed the instant a browser reopens it. Change
 *     one and change the other in the same breath.
 *   - **THE BACKEND STILL HAS NONE.** There is no `rich_text.py` and no report model in
 *     `backend/app/services/`. `cell()` will hand a stored document to a CSV verbatim.
 *
 * So a document written into one of these columns is not "temporarily ugly in an export" — for the
 * exports and the review panel it is prose that has left the building. And `fromStored` in
 * `lib/richText.ts` reads a bare `str` as PLAIN PROSE with no JSON attempt (the comment there says
 * why: a string IS the pre-promotion value, and re-reading it as JSON would blank everything written
 * before the field was promoted), so even this editor only recovers a document via
 * {@link decodeStoredRichText} below. Silent, not a crash — the repository's favourite kind of
 * defect.
 *
 * THE RULE, THEREFORE: **a document is only ever stringified when it is not expressible as plain
 * text.** {@link encodeStoredRichText} flattens an unformatted document with `toPlain` and writes
 * the prose; it writes JSON only once the researcher has actually applied a mark, a heading, a list,
 * a quote, an alignment, a table or an inline photograph. The consequences of that split are worth
 * being explicit about, because somebody will "simplify" it into an unconditional `JSON.stringify`:
 *
 *   - The overwhelming majority of records are typed and dictated plainly. Those columns keep
 *     EXACTLY the bytes they keep today — byte-identical, not merely similar — so search, exports,
 *     the review panel and the Android app see no change at all and need no coordinated release.
 *     That is what makes shipping the editor without a backend change a safe move rather than a
 *     migration.
 *   - A record where somebody bolded a word stores a document. The web editor and the Android
 *     record forms both read it back correctly; the CSV exports, the XLSX workbook and the review
 *     panel show braces for THAT ONE FIELD of THAT ONE RECORD until the model reaches the backend.
 *     That is the known, bounded cost, and it is bounded precisely because this rule exists; making
 *     the encode unconditional would move the cost from "the fields somebody deliberately
 *     formatted" to "every field on every record".
 *   - {@link decodeStoredRichText} is the counterpart and MUST be used on the way in, or the editor
 *     itself becomes one of those braces-showing readers the first time a formatted record is
 *     re-opened.
 *
 * `toPlain` flattens list markers and table cells too, so "unformatted" here means the round trip is
 * lossless, not merely close.
 */

import {
  fromStored,
  isEmptyDoc,
  toPlain,
  toStored,
  type RichBlock,
  type StoredRichDoc
} from "@/lib/richText";

/**
 * How the blocks of an UNFORMATTED document are joined when it is written back as prose.
 *
 * "line" matches `toPlain` exactly and is right for a narrative column. "paragraph" joins with a
 * blank line and exists for one reason: `Artisan.notes` and the process form's step notes have a
 * settled `"\n\n"`-separated contract — `MultiNoteField` here and `MultiNoteInput` in Android's
 * `MainActivity.kt` both SPLIT on blank lines to rebuild the note rows. Writing single newlines into
 * one of those columns would silently collapse four notes into one the next time it is opened in a
 * multi-note control. Reading is symmetric either way, because `fromPlainText` drops blank lines.
 */
export type PlainBlockJoin = "line" | "paragraph";

/**
 * Whether every block in this document survives a trip through plain text unchanged.
 *
 * Deliberately strict, and deliberately a whitelist of "nothing interesting is set" rather than a
 * blacklist of known-lossy features: a new block kind or a new mark added to `lib/richText.ts` must
 * default to "store as JSON", never to "quietly drop it". The failure of the other polarity is
 * invisible — a researcher's table would flatten to pipe-separated lines on save and they would not
 * find out until somebody opened the record again a week later.
 */
function isPlainProse(blocks: readonly RichBlock[]): boolean {
  return blocks.every(
    (block) =>
      block.kind === "PARAGRAPH" &&
      block.level === 0 &&
      block.align === "LEFT" &&
      !block.media &&
      !block.rows.length &&
      block.spans.every((span) => !span.marks.length)
  );
}

/**
 * What the editor should be handed, from what the API returned.
 *
 * A column holds one of three things and all three genuinely occur: `null`, prose (everything
 * written before this change, and everything written since that nobody formatted), or the JSON
 * encoding of a document. Only the third needs unpicking, and it is recognised by SHAPE rather than
 * by a marker byte — a `{"blocks":[…]}` object is not a thing a researcher types into a notes box,
 * whereas a sentinel prefix would be one more thing every other reader would have to know about.
 *
 * A string that parses as JSON but is NOT a block document — `"42"`, `"[1,2,3]"`, a pasted config
 * snippet — falls through to prose, which is what it is.
 */
export function decodeStoredRichText(raw: string | null | undefined): unknown {
  if (raw === null || raw === undefined) return null;
  const trimmed = raw.trim();
  if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return raw;
  try {
    const parsed: unknown = JSON.parse(trimmed);
    if (parsed && typeof parsed === "object" && Array.isArray((parsed as { blocks?: unknown }).blocks)) {
      return parsed;
    }
  } catch {
    /* Prose that merely begins with a brace. Falls through and is read as what it is. */
  }
  return raw;
}

/**
 * What goes in the hidden input the form submits — see the file header for the whole argument.
 *
 * Returns `""` and never `null` because this feeds an `<input value=…>`; the forms' own
 * `textValue`/`requiredText` helpers turn an empty string into the null the API expects, exactly as
 * they do for an empty `<textarea>` today.
 */
export function encodeStoredRichText(stored: StoredRichDoc | null, join: PlainBlockJoin = "line"): string {
  if (!stored) return "";
  const doc = fromStored(stored);
  if (isEmptyDoc(doc)) return "";
  if (!isPlainProse(doc.blocks)) return JSON.stringify(toStored(doc));
  const plain = toPlain(doc);
  return join === "paragraph" ? plain.split("\n").join("\n\n") : plain;
}

/**
 * Append a machine-written paragraph to a value that may be prose or may be a document.
 *
 * THIS IS NOT DECORATION — IT IS THE BUG THAT WOULD OTHERWISE SHIP. `appendRemarksWithExif` in
 * `lib/media.ts` joins the EXIF summary onto the end of `Artisan.notes`, `ProductDocumentation.
 * remarks` and `ToolDocumentation.remarks` at submit time. Concatenating `"\n\nPhoto 1 taken at…"`
 * onto a JSON string produces a value that is neither valid JSON nor readable prose: the editor
 * would fail to parse it and show the researcher raw braces followed by their EXIF note, and every
 * downstream reader would show the same. So the three forms that do this call THIS function instead,
 * which appends INTO the document when there is one.
 *
 * The prose branch is byte-for-byte what `appendRemarksWithExif` does, blank-line join included, so
 * an unformatted record is unchanged.
 */
export function appendStoredParagraph(
  stored: string | null | undefined,
  addition: string,
  join: PlainBlockJoin = "line"
): string | null {
  const base = stored?.trim() ?? "";
  if (!addition) return base || null;
  const decoded = decodeStoredRichText(base || null);
  if (decoded === null || typeof decoded === "string") {
    return [decoded ?? "", addition].filter(Boolean).join("\n\n") || null;
  }
  const doc = fromStored(decoded);
  const appended = fromStored({
    blocks: [
      ...toStored(doc).blocks,
      // One paragraph per line of the addition, matching `fromPlainText`. The EXIF summary is
      // multi-line, and a single span containing "\n" is the one thing `fromStored` has to repair
      // (it replaces the newline with a space) — which would run two photographs' notes together.
      ...addition
        .split("\n")
        .filter((line) => line.trim())
        .map((line) => ({ kind: "PARAGRAPH" as const, spans: [{ text: line.trim() }] }))
    ]
  });
  return encodeStoredRichText(toStored(appended), join) || null;
}
