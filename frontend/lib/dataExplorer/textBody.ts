export type FieldRow = { field: string; value: string };

export type TextBody = { kind: "fields"; rows: FieldRow[]; title?: string } | { kind: "prose"; paragraphs: string[] } | { kind: "empty" };

const FIELD_LINE = /^\s*([^:\n]{1,48}?)\s*:\s?(.*)$/;
const URL_START = /^[a-z][a-z0-9+.-]*:\/\//i;
const TITLE_RULES = /^[\s—–=*#-]+|[\s—–=*#-]+$/g;

function fieldOf(line: string): FieldRow | null {
  if (URL_START.test(line.trim())) return null;
  const match = FIELD_LINE.exec(line);
  if (!match) return null;
  const field = match[1].trim();
  if (!field || /^\d+$/.test(field) || field.split(/\s+/).length > 6 || match[2].startsWith("//")) return null;
  return { field, value: match[2].trim() };
}

export function parseTextBody(text: string | null | undefined): TextBody {
  const source = (text ?? "").replace(/\r\n?/g, "\n");
  const lines = source.split("\n");
  const filled = lines.filter((line) => line.trim() !== "");
  if (filled.length === 0) return { kind: "empty" };
  const matched = filled.filter((line) => fieldOf(line) !== null).length;
  if (matched >= 2 && matched / filled.length >= 0.6) {
    const rows: FieldRow[] = [];
    const lead: string[] = [];
    for (const line of lines) {
      if (line.trim() === "") continue;
      const row = fieldOf(line);
      if (row) rows.push(row);
      else if (rows.length) rows[rows.length - 1] = { ...rows[rows.length - 1], value: `${rows[rows.length - 1].value}\n${line.trim()}`.trim() };
      else lead.push(line.trim().replace(TITLE_RULES, ""));
    }
    const title = lead.filter(Boolean).join(" ");
    return title ? { kind: "fields", rows, title } : { kind: "fields", rows };
  }
  const paragraphs = source
    .split(/\n\s*\n/)
    .map((block) => block.trim())
    .filter(Boolean);
  return { kind: "prose", paragraphs };
}
