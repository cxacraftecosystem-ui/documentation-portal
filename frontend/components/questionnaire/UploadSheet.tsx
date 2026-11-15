"use client";

import { AlertTriangle, Check, Upload, X } from "lucide-react";
import { useEffect, useId, useRef, useState } from "react";

import { FieldDialog } from "@/components/dialogs/FieldDialog";
import { ButtonRow } from "@/components/ui/ButtonRow";
import { SearchableSelect } from "@/components/ui/SearchableSelect";
import { questionnaireApi, mintKey, type ShaMatch } from "@/lib/questionnaire/api";
import { sha256Hex } from "@/lib/questionnaire/hashFile";
import { shortDate } from "@/lib/questionnaire/prompts";
import { qs } from "@/lib/questionnaire/qs";
import { flagDuplicates, recordedAtOf, toUpload, type UploadRow } from "@/lib/questionnaire/uploads";

export type UploadTarget = { kind: "SECTION"; sectionId: string } | { kind: "QUESTION"; lineageId: string } | { kind: "SESSION" };

export type UploadSection = { id: string; code: string; title: string; questions: { lineageId: string; displayCode: string; prompt: string }[] };

function clock(ms: number | null): string {
  if (!ms || ms <= 0) return "";
  const s = Math.floor(ms / 1000);
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const rest = String(s % 60).padStart(2, "0");
  return h > 0 ? `${h}:${String(m).padStart(2, "0")}:${rest}` : `${m}:${rest}`;
}

function stampLabel(iso: string): string {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return "";
  const time = date.toLocaleTimeString("en-IN", { hour: "numeric", minute: "2-digit", hour12: true, timeZone: "Asia/Kolkata" }).toLowerCase();
  return `${shortDate(iso)}, ${time}`;
}

export function mediaDuration(file: File): Promise<number | null> {
  if (typeof document === "undefined" || typeof URL === "undefined" || typeof URL.createObjectURL !== "function") return Promise.resolve(null);
  return new Promise((resolve) => {
    const url = URL.createObjectURL(file);
    const media = document.createElement(file.type.startsWith("video/") ? "video" : "audio");
    const done = (value: number | null) => {
      window.clearTimeout(timer);
      media.removeAttribute("src");
      URL.revokeObjectURL(url);
      resolve(value);
    };
    const timer = window.setTimeout(() => done(null), 8000);
    media.preload = "metadata";
    media.onloadedmetadata = () => done(Number.isFinite(media.duration) && media.duration > 0 ? Math.round(media.duration * 1000) : null);
    media.onerror = () => done(null);
    media.src = url;
  });
}

export function uploadButtonLabel(count: number): string {
  return count === 1 ? qs("uploadOneFile") : qs("uploadFiles", { count });
}

export function UploadSheet({
  sessionId,
  sessionDate,
  sections,
  sectionId,
  defaultTarget,
  queuedShas,
  onClose,
  onUpload
}: {
  sessionId: string | null;
  sessionDate: string | null;
  sections: UploadSection[];
  sectionId: string | null;
  defaultTarget: UploadTarget;
  queuedShas: Record<string, string>;
  onClose: () => void;
  onUpload: (rows: UploadRow[], target: UploadTarget) => void;
}) {
  const inputId = useId();
  const labelId = useId();
  const inputRef = useRef<HTMLInputElement | null>(null);
  const [picked, setRows] = useState<UploadRow[]>([]);
  const [target, setTarget] = useState<UploadTarget>(defaultTarget);
  const [question, setQuestion] = useState<string>(defaultTarget.kind === "QUESTION" ? defaultTarget.lineageId : "");
  const [dragging, setDragging] = useState(false);
  const [found, setFound] = useState<{ key: string; matches: ShaMatch[] }>({ key: "", matches: [] });
  const shaKey = [...new Set(picked.map((r) => r.sha).filter((sha): sha is string => Boolean(sha)))].sort().join(",");
  const section = sections.find((s) => s.id === sectionId) ?? null;
  const questions = section ? section.questions : sections.flatMap((s) => s.questions);

  useEffect(() => {
    if (!shaKey) return;
    if (typeof navigator !== "undefined" && navigator.onLine === false) return;
    let cancelled = false;
    questionnaireApi
      .shaLookup(shaKey.split(","), sessionId)
      .then((matches) => {
        if (!cancelled) setFound({ key: shaKey, matches: matches.filter((m) => !m.pending) });
      })
      .catch(() => undefined);
    return () => {
      cancelled = true;
    };
  }, [shaKey, sessionId]);

  const flagged = flagDuplicates(picked, found.matches, queuedShas, sessionDate);
  const rows = picked.map((r) => ({ ...r, duplicateOn: flagged[r.key] ?? null }));

  async function add(files: FileList | File[] | null) {
    const picked = Array.from(files ?? []).filter((f) => f.type.startsWith("audio/") || f.type.startsWith("video/") || !f.type);
    if (!picked.length) return;
    const fresh: UploadRow[] = picked.map((file) => {
      const when = recordedAtOf(file, sessionDate);
      return { key: mintKey("pick"), file, durationMs: null, recordedAt: when.at, source: when.source, sha: null, duplicateOn: null };
    });
    setRows((current) => [...current, ...fresh]);
    for (const row of fresh) {
      void Promise.all([mediaDuration(row.file), sha256Hex(row.file).catch(() => "")]).then(([durationMs, sha]) =>
        setRows((current) => current.map((r) => (r.key === row.key ? { ...r, durationMs, sha } : r)))
      );
    }
  }

  const ready = toUpload(rows).filter((r) => r.sha !== null);
  const hashing = rows.some((r) => r.sha === null);
  const chosen: UploadTarget = target.kind === "QUESTION" ? { kind: "QUESTION", lineageId: question } : target;
  const blocked = !ready.length || hashing || (chosen.kind === "QUESTION" && !chosen.lineageId);
  const options: { kind: UploadTarget["kind"]; label: string }[] = [
    ...(section ? [{ kind: "SECTION" as const, label: qs("chipSection", { code: section.code }) }] : []),
    { kind: "QUESTION", label: qs("attachQuestion") },
    { kind: "SESSION", label: qs("wholeSession") }
  ];

  return (
    <FieldDialog open onClose={onClose} title={qs("uploadRecordings")} className="max-w-xl">
      <div className="grid gap-5" data-upload-sheet>
        <div
          className={`flex flex-col items-center gap-3 rounded-lg border-2 border-dashed px-4 py-6 text-center ${
            dragging ? "border-purple-700 bg-purple-100 dark:bg-purple-700/25" : "border-line-200 bg-surface-50"
          }`}
          onDragOver={(event) => {
            event.preventDefault();
            setDragging(true);
          }}
          onDragLeave={() => setDragging(false)}
          onDrop={(event) => {
            event.preventDefault();
            setDragging(false);
            void add(event.dataTransfer.files);
          }}
        >
          <Upload className="h-7 w-7 text-purple-700 dark:text-purple-300" aria-hidden />
          <span className="text-[0.9375rem] font-medium text-ink-700">
            <span className="pointer-coarse:hidden">{qs("uploadDropHint")}</span>
            <span className="hidden pointer-coarse:inline">{qs("uploadPickHint")}</span>
          </span>
          <label htmlFor={inputId} className="field-button-secondary min-h-11 cursor-pointer font-semibold">
            {qs("chooseFiles")}
          </label>
          <input
            ref={inputRef}
            id={inputId}
            type="file"
            multiple
            accept="audio/*,video/*"
            className="sr-only"
            data-upload-input
            onChange={(event) => {
              void add(event.target.files);
              event.target.value = "";
            }}
          />
        </div>

        {rows.length ? (
          <ul className="grid gap-2.5" data-upload-rows>
            {rows.map((row) => (
              <li key={row.key} className="grid gap-2.5 rounded-md border border-line-200 py-3 pl-3.5 pr-2" data-upload-row={row.file.name} data-duplicate={row.duplicateOn ? "" : undefined}>
                <div className="flex items-center gap-3">
                  <span
                    aria-hidden
                    className={`flex h-9 w-9 shrink-0 items-center justify-center rounded-sm ${
                      row.duplicateOn ? "bg-amber-100 text-amber-800 dark:bg-amber-900/40 dark:text-amber-100" : "bg-success-100 text-success-600 dark:bg-success-600/25 dark:text-green-400"
                    }`}
                  >
                    {row.duplicateOn ? <AlertTriangle className="h-4.5 w-4.5" /> : <Check className="h-4.5 w-4.5" />}
                  </span>
                  <span className="grid min-w-0 flex-1 gap-0.5">
                    <span className="truncate text-[0.9375rem] font-semibold text-ink-900">{row.file.name}</span>
                    <span className="text-[0.8125rem] text-ink-500">
                      {clock(row.durationMs) ? qs("uploadFileMeta", { duration: clock(row.durationMs), date: stampLabel(row.recordedAt) }) : stampLabel(row.recordedAt)}
                    </span>
                  </span>
                  {row.duplicateOn ? (
                    <button type="button" className="field-button-secondary min-h-11 font-semibold" onClick={() => setRows(rows.filter((r) => r.key !== row.key))}>
                      {qs("remove")}
                    </button>
                  ) : (
                    <button
                      type="button"
                      aria-label={qs("removeNamed", { name: row.file.name })}
                      className="flex h-11 w-11 shrink-0 items-center justify-center rounded-full text-ink-700 hover:bg-field-100"
                      onClick={() => setRows(rows.filter((r) => r.key !== row.key))}
                    >
                      <X className="h-4.5 w-4.5" aria-hidden />
                    </button>
                  )}
                </div>
                {row.duplicateOn ? (
                  <span className="ml-12 w-fit rounded-sm bg-amber-100 px-2.5 py-1 text-[0.8125rem] font-semibold text-amber-800 dark:bg-amber-900/40 dark:text-amber-100" data-already-uploaded>
                    {qs("alreadyUploadedOn", { date: shortDate(row.duplicateOn) })}
                  </span>
                ) : null}
              </li>
            ))}
          </ul>
        ) : null}

        <div className="grid gap-2">
          <span id={labelId} className="field-label">
            {qs("attachTo")}
          </span>
          <div role="radiogroup" aria-labelledby={labelId} className="flex gap-1 rounded-md border border-line-200 bg-surface-50 p-1">
            {options.map((option) => {
              const on = target.kind === option.kind;
              return (
                <button
                  key={option.kind}
                  type="button"
                  role="radio"
                  aria-checked={on}
                  className={`min-h-11 flex-1 rounded-sm px-1.5 text-sm ${on ? "bg-purple-700 font-semibold text-white" : "font-medium text-ink-700 hover:bg-field-100"}`}
                  onClick={() => setTarget(option.kind === "SECTION" && section ? { kind: "SECTION", sectionId: section.id } : option.kind === "QUESTION" ? { kind: "QUESTION", lineageId: question } : { kind: "SESSION" })}
                >
                  {option.label}
                </button>
              );
            })}
          </div>
          {target.kind === "SECTION" && section ? (
            <span className="text-sm text-ink-500">{qs("countsForAllQuestions", { count: section.questions.length, code: section.code })}</span>
          ) : null}
          {target.kind === "QUESTION" ? (
            <SearchableSelect
              value={question}
              onChange={setQuestion}
              options={questions.map((q) => ({ value: q.lineageId, label: `${q.displayCode} ${q.prompt}` }))}
              searchable
              advanceOnSelect={false}
              ariaLabel={qs("attachQuestion")}
            />
          ) : null}
        </div>

        <ButtonRow className="pt-1" fill>
          <button type="button" className="field-button-secondary" onClick={onClose}>
            {qs("cancel")}
          </button>
          <button type="button" className="field-button" disabled={blocked} onClick={() => onUpload(ready, chosen)} data-upload-confirm>
            {uploadButtonLabel(ready.length)}
          </button>
        </ButtonRow>
      </div>
    </FieldDialog>
  );
}
