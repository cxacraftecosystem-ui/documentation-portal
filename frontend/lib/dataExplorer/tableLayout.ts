export const TABLE_COLUMN = { min: 160, max: 420, narrow: 112, charPx: 7.5, pad: 28, sample: 60 } as const;

export function textLength(value: unknown): number {
  if (value === null || value === undefined) return 1;
  const text = String(value);
  let longest = 0;
  for (const line of text.split("\n")) longest = Math.max(longest, [...line].length);
  return Math.max(1, longest);
}

export function columnWidth(header: string, values: readonly unknown[]): number {
  const sample = values.slice(0, TABLE_COLUMN.sample);
  const widest = Math.max(textLength(header), ...sample.map(textLength));
  const wanted = Math.round(widest * TABLE_COLUMN.charPx + TABLE_COLUMN.pad);
  if (widest <= 10 && textLength(header) <= 12) return Math.max(TABLE_COLUMN.narrow, Math.min(TABLE_COLUMN.min, wanted));
  return Math.max(TABLE_COLUMN.min, Math.min(TABLE_COLUMN.max, wanted));
}

export function columnWidths(columns: readonly string[], rows: readonly (readonly unknown[])[]): number[] {
  return columns.map((column, index) => columnWidth(column, rows.map((row) => row[index])));
}
