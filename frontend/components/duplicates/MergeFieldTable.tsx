"use client";

import { Lock } from "lucide-react";

import { optionsFor, resultValue, type MergeChoice, type MergeField } from "@/lib/duplicates";

const CHOICE_LABEL: Record<MergeChoice, string> = { A: "A", B: "B", BOTH: "Both" };

/**
 * One row per field of the two records: what each holds, and which the merged record keeps.
 *
 * A side is a radio group (A, B, and Both where the field can hold both, joined on a new line). A
 * locked row — the Aadhaar and the Pehchan card — is read-only: it always comes from whichever record
 * holds it. Values are as the server sends them for this screen: masked identity, names for ids.
 */
export function MergeFieldTable({
  fields,
  survivor,
  choices,
  onChoice,
  disabled = false
}: {
  fields: MergeField[];
  survivor: "A" | "B";
  choices: Record<string, MergeChoice>;
  onChoice: (key: string, choice: MergeChoice) => void;
  disabled?: boolean;
}) {
  return (
    <div className="overflow-x-auto">
      <table className="w-full min-w-[640px] text-left text-sm">
        <thead className="bg-surface-50 text-xs uppercase text-ink-500">
          <tr>
            <th className="px-3 py-2 font-semibold">Field</th>
            <th className="px-3 py-2 font-semibold">A</th>
            <th className="px-3 py-2 font-semibold">B</th>
            <th className="px-3 py-2 font-semibold">Keep</th>
          </tr>
        </thead>
        <tbody className="divide-y divide-line-200">
          {fields.map((field) => {
            const choice = choices[field.key] ?? field.default;
            const options = optionsFor(field, survivor);
            const kept = resultValue(field, choice);
            return (
              <tr key={field.key} data-field={field.key} className="align-top">
                <th scope="row" className="px-3 py-2 font-medium text-ink-900">
                  {field.label}
                </th>
                <td className={`whitespace-pre-line px-3 py-2 ${choice === "A" || choice === "BOTH" ? "text-ink-900" : "text-ink-500"}`}>
                  {field.a ?? "—"}
                </td>
                <td className={`whitespace-pre-line px-3 py-2 ${choice === "B" || choice === "BOTH" ? "text-ink-900" : "text-ink-500"}`}>
                  {field.b ?? "—"}
                </td>
                <td className="px-3 py-2">
                  {field.locked ? (
                    <span className="inline-flex items-center gap-1 rounded-full border border-line-200 bg-surface-50 px-2 py-0.5 text-xs text-ink-700">
                      <Lock className="h-3 w-3" aria-hidden />
                      {kept ? `From ${CHOICE_LABEL[options[0]]}` : "None"}
                    </span>
                  ) : (
                    <fieldset className="flex flex-wrap gap-1" disabled={disabled}>
                      <legend className="sr-only">Keep {field.label} from</legend>
                      {options.map((option) => (
                        <label
                          key={option}
                          className={`cursor-pointer rounded-full border px-3 py-1 text-xs font-medium transition-colors ${
                            choice === option
                              ? "border-purple-700 bg-purple-700 text-white"
                              : "border-line-200 bg-surface-50 text-ink-700 hover:border-purple-300"
                          }`}
                        >
                          <input
                            type="radio"
                            className="sr-only"
                            name={`merge-${field.key}`}
                            value={option}
                            checked={choice === option}
                            onChange={() => onChoice(field.key, option)}
                          />
                          {CHOICE_LABEL[option]}
                        </label>
                      ))}
                    </fieldset>
                  )}
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
