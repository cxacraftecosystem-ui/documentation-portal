"use client";

import { Plus, X } from "lucide-react";
import { useId, useRef, useState } from "react";

import { RequiredMark } from "@/components/ui/RequiredMark";

function splitNumbered(value: string | null | undefined): string[] {
  const rows = (value ?? "")
    .split("\n")
    .map((row) => row.trim())
    .filter(Boolean);
  return rows.length ? rows : [""];
}

function joinNumbered(items: string[]): string {
  return items
    .map((row) => row.trim())
    .filter(Boolean)
    .join("\n");
}

export function DosDontsField({
  name,
  label,
  helper,
  defaultValue,
  required = true,
  fieldKey
}: {
  name: string;
  fieldKey?: string;
  label: string;
  helper?: string;
  defaultValue?: string | null;
  required?: boolean;
}) {
  const [items, setItems] = useState<string[]>(() => splitNumbered(defaultValue));
  const rowRefs = useRef<Array<HTMLInputElement | null>>([]);
  const joined = joinNumbered(items);

  function update(next: string[]) {
    setItems(next.length ? next : [""]);
  }

  function focusRow(index: number) {
    requestAnimationFrame(() => rowRefs.current[index]?.focus());
  }

  function changeRow(index: number, raw: string) {
    if (raw.includes("\n")) {
      const segments = raw.split("\n");
      const next = [...items];
      next[index] = segments[0].trim();
      next.splice(index + 1, 0, ...segments.slice(1).map((segment) => segment.trim()));
      update(next);
    } else {
      update(items.map((item, j) => (j === index ? raw : item)));
    }
  }

  function addRowAfter(index: number) {
    const next = [...items];
    next.splice(index + 1, 0, "");
    update(next);
    focusRow(index + 1);
  }

  const groupId = useId();
  const helperId = `${groupId}-helper`;

  return (
    <div data-field={fieldKey} className="relative grid content-start gap-1.5" role="group" aria-labelledby={groupId} aria-describedby={helper ? helperId : undefined}>
      <span id={groupId} className="field-label">
        {label}
        <RequiredMark when={required} />
      </span>
      {helper ? (
        <p id={helperId} className="text-xs text-ink-muted">
          {helper}
        </p>
      ) : null}
      {items.map((item, index) => (
        <div key={index} className="flex items-center gap-2">
          <span className="w-5 shrink-0 text-right text-sm text-ink-muted">{index + 1}.</span>
          <input
            ref={(el) => {
              rowRefs.current[index] = el;
            }}
            className="field-input min-w-0 flex-1"
            type="text"
            aria-label={`Point ${index + 1}`}
            value={item}
            onChange={(event) => changeRow(index, event.target.value)}
            onKeyDown={(event) => {
              if (event.key === "Enter") {
                event.preventDefault();
                addRowAfter(index);
              }
            }}
          />
          {items.length > 1 ? (
            <button
              type="button"
              aria-label={`Remove point ${index + 1}`}
              className="grid shrink-0 place-items-center rounded-md p-2 text-ink-muted pointer-coarse:min-h-11 pointer-coarse:min-w-11 transition hover:bg-field-100 hover:text-error-600"
              onClick={() => update(items.filter((_, j) => j !== index))}
            >
              <X className="h-4 w-4" aria-hidden />
            </button>
          ) : null}
        </div>
      ))}
      <button
        type="button"
        className="field-button-secondary inline-flex items-center gap-1 justify-self-start"
        onClick={() => addRowAfter(items.length - 1)}
      >
        <Plus className="h-4 w-4" aria-hidden />
        Add point
      </button>
      <textarea
        name={name}
        value={joined}
        required={required}
        onChange={() => undefined}
        tabIndex={-1}
        aria-hidden="true"
        className="pointer-events-none absolute h-0 w-0 resize-none border-0 p-0 opacity-0"
      />
    </div>
  );
}
