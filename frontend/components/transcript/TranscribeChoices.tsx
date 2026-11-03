"use client";

import { Languages } from "lucide-react";
import { useId } from "react";

import { Toggle } from "@/components/ui/Toggle";
import { ts } from "@/lib/transcript/strings";
import {
  lockedByPolicy,
  setTranscribeChoice,
  setTranscribeChoices,
  transcribableKind,
  transcribeChoice,
  useUploadChoiceVersion,
  useUploadPolicy
} from "@/lib/transcript/uploadChoice";

type ChoiceFile = Pick<File, "type" | "name" | "size" | "lastModified">;

export type TranscribeChoicesProps = {
  files: readonly ChoiceFile[];
  nameOf?: (file: ChoiceFile, index: number) => string;
  disabled?: boolean;
  className?: string;
};

export function TranscribeChoices({ files, nameOf, disabled = false, className = "" }: TranscribeChoicesProps) {
  useUploadChoiceVersion();
  const policy = useUploadPolicy(files.length > 0);
  const baseId = useId();
  const rows = files
    .map((file, index) => ({ file, index, kind: transcribableKind(file) }))
    .filter((row) => row.kind !== null);
  if (!rows.length) return null;

  const lockedRows = rows.filter((row) => lockedByPolicy(row.kind, policy));
  const freeRows = rows.filter((row) => !lockedByPolicy(row.kind, policy));
  const allOn = freeRows.length > 0 && freeRows.every((row) => transcribeChoice(row.file));
  const lockedVideo = lockedRows.some((row) => row.kind === "VIDEO");
  const lockedAudio = lockedRows.some((row) => row.kind === "AUDIO");
  const note = lockedVideo && lockedAudio ? ts("uploadAutoBoth") : lockedVideo ? ts("uploadAutoVideo") : lockedAudio ? ts("uploadAutoAudio") : null;

  return (
    <div
      className={`grid gap-2 rounded-md border border-line-200 bg-surface-50 p-3 ${className}`}
      data-testid="transcribe-choices"
    >
      {freeRows.length >= 2 ? (
        <div data-testid="transcribe-all">
          <Toggle
            label={ts("uploadTranscribeAll")}
            checked={allOn}
            disabled={disabled}
            onChange={(on) => setTranscribeChoices(freeRows.map((row) => row.file), on)}
          />
        </div>
      ) : null}
      {note ? (
        <p className="flex items-start gap-2 text-xs leading-5 text-ink-500" data-testid="transcribe-auto-note">
          <Languages className="mt-0.5 h-3.5 w-3.5 shrink-0" aria-hidden />
          {note}
        </p>
      ) : null}
      <ul className="grid gap-1">
        {rows.map((row) => {
          const locked = lockedByPolicy(row.kind, policy);
          const checked = locked || transcribeChoice(row.file);
          const id = `${baseId}-${row.index}`;
          const name = nameOf ? nameOf(row.file, row.index) : row.file.name;
          return (
            <li key={id}>
              <label
                htmlFor={id}
                className={`flex min-h-11 items-center gap-3 rounded-md px-1 py-1.5 ${
                  locked || disabled ? "cursor-not-allowed" : "cursor-pointer hover:bg-card"
                }`}
              >
                <input
                  id={id}
                  type="checkbox"
                  className="h-4 w-4 shrink-0 accent-purple-700"
                  checked={checked}
                  disabled={locked || disabled}
                  data-testid="transcribe-choice"
                  data-file={row.file.name}
                  data-locked={locked ? "true" : undefined}
                  onChange={(event) => setTranscribeChoice(row.file, event.target.checked)}
                />
                <span className="min-w-0 flex-1">
                  <span className="block text-sm font-medium text-ink-900">{ts("uploadTranscribe")}</span>
                  <span className="block truncate text-xs text-ink-500">{name}</span>
                </span>
              </label>
            </li>
          );
        })}
      </ul>
    </div>
  );
}
