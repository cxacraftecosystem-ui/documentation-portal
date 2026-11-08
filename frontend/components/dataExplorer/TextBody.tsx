"use client";

import { parseTextBody, type FieldRow } from "@/lib/dataExplorer/textBody";

export function FieldTable({ rows, label = "Fields" }: { rows: FieldRow[]; label?: string }) {
  return (
    <div className="overflow-x-auto rounded-md border border-line-200" data-testid="field-table">
      <table className="w-full min-w-0 table-fixed border-collapse text-left text-sm" aria-label={label}>
        <colgroup>
          <col className="w-[34%] max-w-[260px]" />
          <col />
        </colgroup>
        <thead className="bg-surface-50 text-xs tracking-[0.04em] text-ink-500 uppercase">
          <tr>
            <th scope="col" className="px-3 py-2 font-semibold">
              Field
            </th>
            <th scope="col" className="px-3 py-2 font-semibold">
              Value
            </th>
          </tr>
        </thead>
        <tbody>
          {rows.map((row, index) => (
            <tr key={`${row.field}-${index}`} className="border-t border-line-200 align-top">
              <th scope="row" className="px-3 py-2 font-medium [overflow-wrap:anywhere] text-ink-700">
                {row.field || "-"}
              </th>
              <td className="px-3 py-2 leading-6 whitespace-pre-line [overflow-wrap:anywhere] text-ink-900">{row.value || "-"}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

export function TextBody({ text, label }: { text: string | null | undefined; label?: string }) {
  const body = parseTextBody(text);
  if (body.kind === "empty") return <p className="text-sm text-ink-500">Empty.</p>;
  if (body.kind === "fields") return <FieldTable rows={body.rows} label={label} />;
  return (
    <div className="grid gap-3 text-sm leading-6 text-ink-700" data-testid="text-paragraphs">
      {body.paragraphs.map((paragraph, index) => (
        <p key={index} className="whitespace-pre-line [overflow-wrap:anywhere]">
          {paragraph}
        </p>
      ))}
    </div>
  );
}
