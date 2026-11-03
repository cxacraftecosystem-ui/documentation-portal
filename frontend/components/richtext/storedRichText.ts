import {
  fromStored,
  isEmptyDoc,
  toPlain,
  toStored,
  type RichBlock,
  type StoredRichDoc
} from "@/lib/richText";

export type PlainBlockJoin = "line" | "paragraph";

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
  }
  return raw;
}

export function encodeStoredRichText(stored: StoredRichDoc | null, join: PlainBlockJoin = "line"): string {
  if (!stored) return "";
  const doc = fromStored(stored);
  if (isEmptyDoc(doc)) return "";
  if (!isPlainProse(doc.blocks)) return JSON.stringify(toStored(doc));
  const plain = toPlain(doc);
  return join === "paragraph" ? plain.split("\n").join("\n\n") : plain;
}

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
      ...addition
        .split("\n")
        .filter((line) => line.trim())
        .map((line) => ({ kind: "PARAGRAPH" as const, spans: [{ text: line.trim() }] }))
    ]
  });
  return encodeStoredRichText(toStored(appended), join) || null;
}
