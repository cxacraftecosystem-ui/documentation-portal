"use client";

import { Mic, Upload } from "lucide-react";

import { ButtonRow } from "@/components/ui/ButtonRow";
import { qs } from "@/lib/questionnaire/qs";

export const TONAL_BUTTON =
  "inline-flex items-center justify-center gap-2 rounded-md bg-purple-100 px-4 py-2 text-sm font-semibold text-purple-800 transition hover:bg-purple-200 disabled:cursor-not-allowed disabled:opacity-60 dark:bg-purple-700/25 dark:text-purple-200 dark:hover:bg-purple-700/40";

export function RecordUploadPair({
  recordLabel,
  onRecord,
  onUpload,
  size = "lg",
  showUpload = true,
  disabled = false,
  label
}: {
  recordLabel: string;
  onRecord: () => void;
  onUpload: () => void;
  size?: "lg" | "sm";
  showUpload?: boolean;
  disabled?: boolean;
  label?: string;
}) {
  const height = size === "lg" ? "min-h-13! text-[0.9375rem] max-sm:gap-1.5 max-sm:px-2 max-sm:text-sm" : "min-h-11! max-sm:gap-1.5 max-sm:px-2";
  return (
    <ButtonRow fill label={label}>
      <button type="button" className={`field-button ${height} font-semibold`} disabled={disabled} onClick={onRecord} data-record-button>
        <Mic className="h-5 w-5 shrink-0" aria-hidden />
        {recordLabel}
      </button>
      {showUpload ? (
        <button type="button" className={`${TONAL_BUTTON} ${height}`} disabled={disabled} onClick={onUpload} data-upload-button>
          <Upload className="h-5 w-5 shrink-0" aria-hidden />
          {qs("upload")}
        </button>
      ) : null}
    </ButtonRow>
  );
}
