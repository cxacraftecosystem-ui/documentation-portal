"use client";

import { ChevronDown, Loader2, Pencil, Plus, RefreshCw, Sparkles, X } from "lucide-react";
import { useState } from "react";

import { useOptionalConfirm } from "@/components/dialogs";
import { ButtonRow } from "@/components/ui/ButtonRow";
import { ApiError } from "@/lib/api";
import { formatDate } from "@/lib/format";
import {
  QUOTES_MAX,
  SUMMARY_LINE_MAX,
  cleanQuotes,
  cleanSummaryLines,
  editRows,
  fetchInsight,
  isWorking,
  modelLabel,
  needsRegenerateConfirm,
  patchInsight,
  regenerateInsight,
  useTranscriptInsight
} from "@/lib/transcriptInsights";
import type { InsightQuote, TranscriptInsightView } from "@/lib/types";

/**
 * Summary, key quotes and English translation under a transcript. Draws nothing while loading,
 * when the feature is off (404), or when there is no transcript. `readOnly` hides Edit and
 * Regenerate (the lightbox).
 */
export function TranscriptInsights({ mediaId, readOnly = false }: { mediaId: string; readOnly?: boolean }) {
  const { view, replace } = useTranscriptInsight(mediaId);
  if (!view || view.state === "NO_TRANSCRIPT") return null;
  if (view.state === "NOT_GENERATED" && !view.canRegenerate) return null;
  return <InsightsPanel view={view} replace={replace} readOnly={readOnly} />;
}

function InsightsPanel({
  view,
  replace,
  readOnly
}: {
  view: TranscriptInsightView;
  replace: (next: TranscriptInsightView) => void;
  readOnly: boolean;
}) {
  const confirm = useOptionalConfirm();
  const [editing, setEditing] = useState(false);
  const [askReplace, setAskReplace] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const insight = view.insight;
  const working = isWorking(view);
  const mayRegenerate = !readOnly && view.canRegenerate && !working;
  // No editing while a new version is being written: it would supersede the edit.
  const mayEdit = !readOnly && view.canEdit && Boolean(insight) && !working;

  async function regenerate() {
    setError(null);
    if (!needsRegenerateConfirm(view)) return start(false);
    // A page without the confirm dialog asks inline instead.
    if (!confirm) return setAskReplace(true);
    const ok = await confirm({
      title: "Regenerating replaces your edits.",
      confirmLabel: "Replace",
      cancelLabel: "Keep",
      tone: "warning"
    });
    if (ok) await start(true);
  }

  async function start(discardEdits: boolean) {
    setAskReplace(false);
    setBusy(true);
    try {
      replace(await regenerateInsight(view.mediaId, discardEdits));
    } catch (err) {
      setError(err instanceof Error ? err.message : "Couldn't start. Try again.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <section aria-label="Summary" className="mt-2 rounded-md border border-line-200 bg-card p-3">
      <div className="mb-2 flex flex-wrap items-center gap-2">
        <Sparkles className="h-3.5 w-3.5 text-purple-700" aria-hidden />
        <span className="text-xs font-semibold uppercase tracking-wide text-ink-500">Summary</span>
        {insight ? <ProvenanceLine view={view} /> : null}
      </div>

      {working ? (
        <div className="flex items-center gap-2 text-sm text-ink-500" role="status">
          <Loader2 className="h-4 w-4 animate-spin" aria-hidden />
          Summarising…
        </div>
      ) : null}

      {view.state === "FAILED" && view.error ? <p className="text-sm text-red-700">{view.error}</p> : null}
      {view.stale && insight ? (
        <p className="mb-2 text-xs text-amber-800">The transcript changed after this summary.</p>
      ) : null}

      {insight && editing ? (
        <InsightEditor
          view={view}
          onCancel={() => setEditing(false)}
          onSaved={(next) => {
            replace(next);
            setEditing(false);
          }}
        />
      ) : insight ? (
        <InsightBody key={insight.version} view={view} />
      ) : null}

      {error ? <p className="mt-2 text-sm text-red-700">{error}</p> : null}

      {askReplace ? (
        <div className="mt-3 grid gap-2" role="group" aria-label="Regenerate">
          <p className="text-sm text-ink-700">Regenerating replaces your edits.</p>
          <ButtonRow align="end" size="sm">
            <button type="button" className="field-button-secondary" onClick={() => setAskReplace(false)} disabled={busy}>
              Keep
            </button>
            <button type="button" className="field-button" onClick={() => void start(true)} disabled={busy}>
              Replace
            </button>
          </ButtonRow>
        </div>
      ) : !editing && (mayEdit || mayRegenerate) ? (
        <ButtonRow align="end" size="sm" className="mt-3">
          {mayEdit ? (
            <button type="button" className="field-button-secondary" onClick={() => setEditing(true)} disabled={busy}>
              <Pencil className="h-4 w-4" aria-hidden />
              Edit
            </button>
          ) : null}
          {mayRegenerate ? (
            <button type="button" className="field-button-secondary" onClick={() => void regenerate()} disabled={busy}>
              <RefreshCw className={`h-4 w-4${busy ? " animate-spin" : ""}`} aria-hidden />
              {insight ? "Regenerate" : "Summarise"}
            </button>
          ) : null}
        </ButtonRow>
      ) : null}
    </section>
  );
}

function ProvenanceLine({ view }: { view: TranscriptInsightView }) {
  const insight = view.insight!;
  if (insight.edited) {
    return (
      <span className="inline-flex items-center gap-1 rounded-full bg-purple-50 px-2 py-0.5 text-[0.6875rem] font-medium text-purple-700">
        <Pencil className="h-3 w-3" aria-hidden />
        Edited by a researcher · {formatDate(insight.edited.at)}
      </span>
    );
  }
  return (
    <span className="text-[0.6875rem] text-ink-500">
      Machine-generated · {modelLabel(view)} · {formatDate(insight.provenance.generatedAt)}
    </span>
  );
}

function InsightBody({ view }: { view: TranscriptInsightView }) {
  const insight = view.insight!;
  const [open, setOpen] = useState(false);
  const [text, setText] = useState<string | null>(insight.translation.text);
  const [loading, setLoading] = useState(false);
  const translation = insight.translation;
  const panelId = `insight-translation-${view.mediaId}`;

  async function toggle() {
    const next = !open;
    setOpen(next);
    if (next && text === null && (translation.status === "COMPLETED" || translation.status === "PARTIAL")) {
      setLoading(true);
      try {
        const full = await fetchInsight(view.mediaId);
        setText(full.insight?.translation.text ?? "");
      } catch {
        setText("");
      } finally {
        setLoading(false);
      }
    }
  }

  return (
    <div className="grid gap-3">
      <ul className="grid list-disc gap-1 pl-5 text-sm text-ink-700">
        {insight.summaryLines.map((line, index) => (
          <li key={index}>{line}</li>
        ))}
      </ul>

      {insight.quotes.length > 0 ? (
        <div>
          <div className="mb-1 text-xs font-semibold uppercase tracking-wide text-ink-500">Key quotes</div>
          <ul className="grid gap-2">
            {insight.quotes.map((quote, index) => (
              <li key={index} className="border-l-2 border-purple-200 pl-3 text-sm">
                <p className="text-ink-900">
                  {quote.speaker ? <span className="font-medium">{quote.speaker}: </span> : null}“{quote.original}”
                </p>
                {quote.english && quote.english !== quote.original ? (
                  <p className="mt-0.5 text-ink-500">{quote.english}</p>
                ) : null}
              </li>
            ))}
          </ul>
        </div>
      ) : null}

      {translation.status === "NOT_NEEDED" ? null : translation.status === "FAILED" ? (
        <p className="text-xs text-ink-500">{translation.note ?? "Translation failed."}</p>
      ) : (
        <div>
          <button
            type="button"
            onClick={() => void toggle()}
            aria-expanded={open}
            aria-controls={open ? panelId : undefined}
            className="inline-flex items-center gap-1 text-sm font-medium text-purple-700 hover:text-purple-800"
          >
            <ChevronDown className={`h-4 w-4 transition-transform${open ? " rotate-180" : ""}`} aria-hidden />
            English translation
          </button>
          {open ? (
            <div id={panelId} className="mt-2 rounded-md bg-surface-50 p-3 text-sm text-ink-700">
              {loading ? (
                <Loader2 className="h-4 w-4 animate-spin text-ink-500" aria-label="Loading" />
              ) : (
                <p className="whitespace-pre-wrap">{text}</p>
              )}
              {translation.note ? <p className="mt-2 text-xs text-ink-500">{translation.note}</p> : null}
            </div>
          ) : null}
        </div>
      )}
    </div>
  );
}

function InsightEditor({
  view,
  onCancel,
  onSaved
}: {
  view: TranscriptInsightView;
  onCancel: () => void;
  onSaved: (next: TranscriptInsightView) => void;
}) {
  const insight = view.insight!;
  const translatable = insight.translation.status !== "NOT_NEEDED";
  const [lines, setLines] = useState<string[]>(() => editRows(insight.summaryLines));
  const [quotes, setQuotes] = useState<InsightQuote[]>(() => insight.quotes.map((q) => ({ ...q })));
  const [translation, setTranslation] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function loadTranslation() {
    if (translation !== null) return;
    try {
      const full = await fetchInsight(view.mediaId);
      setTranslation(full.insight?.translation.text ?? "");
    } catch {
      setTranslation("");
    }
  }

  async function save() {
    const summaryLines = cleanSummaryLines(lines);
    if (summaryLines.length === 0) {
      setError("Add at least one line.");
      return;
    }
    setSaving(true);
    setError(null);
    try {
      const next = await patchInsight(view.mediaId, {
        version: insight.version,
        summaryLines,
        quotes: cleanQuotes(quotes),
        ...(translation !== null ? { translationText: translation } : {})
      });
      onSaved(next);
    } catch (err) {
      setError(err instanceof ApiError || err instanceof Error ? err.message : "Couldn't save. Try again.");
    } finally {
      setSaving(false);
    }
  }

  const setQuote = (index: number, patch: Partial<InsightQuote>) =>
    setQuotes((current) => current.map((q, i) => (i === index ? { ...q, ...patch } : q)));

  return (
    <div className="grid gap-3">
      <fieldset className="grid gap-2">
        <legend className="field-label mb-1">Summary</legend>
        {lines.map((line, index) => (
          <input
            key={index}
            className="field-input"
            value={line}
            maxLength={SUMMARY_LINE_MAX}
            aria-label={`Line ${index + 1}`}
            onChange={(event) => setLines((current) => current.map((l, i) => (i === index ? event.target.value : l)))}
          />
        ))}
      </fieldset>

      <fieldset className="grid gap-2">
        <legend className="field-label mb-1">Key quotes</legend>
        {quotes.map((quote, index) => (
          <div key={index} className="grid gap-2 rounded-md border border-line-200 p-2">
            <textarea
              className="field-input"
              rows={2}
              value={quote.original}
              aria-label={`Quote ${index + 1}, original`}
              placeholder="Original words"
              onChange={(event) => setQuote(index, { original: event.target.value })}
            />
            <textarea
              className="field-input"
              rows={2}
              value={quote.english}
              aria-label={`Quote ${index + 1}, English`}
              placeholder="English"
              onChange={(event) => setQuote(index, { english: event.target.value })}
            />
            <ButtonRow align="end" size="sm">
              <button
                type="button"
                className="field-button-secondary"
                onClick={() => setQuotes((current) => current.filter((_, i) => i !== index))}
              >
                <X className="h-4 w-4" aria-hidden />
                Remove
              </button>
            </ButtonRow>
          </div>
        ))}
        {quotes.length < QUOTES_MAX ? (
          <ButtonRow>
            <button
              type="button"
              className="field-button-secondary"
              onClick={() => setQuotes((current) => [...current, { original: "", english: "", speaker: null }])}
            >
              <Plus className="h-4 w-4" aria-hidden />
              Add quote
            </button>
          </ButtonRow>
        ) : null}
      </fieldset>

      {translatable ? (
        <div className="grid gap-1">
          <label htmlFor={`insight-translation-edit-${view.mediaId}`} className="field-label">
            English translation
          </label>
          {translation === null ? (
            <ButtonRow>
              <button type="button" className="field-button-secondary" onClick={() => void loadTranslation()}>
                Edit translation
              </button>
            </ButtonRow>
          ) : (
            <textarea
              id={`insight-translation-edit-${view.mediaId}`}
              className="field-input"
              rows={8}
              value={translation}
              onChange={(event) => setTranslation(event.target.value)}
            />
          )}
        </div>
      ) : null}

      {error ? <p className="text-sm text-red-700">{error}</p> : null}

      <ButtonRow align="end">
        <button type="button" className="field-button-secondary" onClick={onCancel} disabled={saving}>
          Cancel
        </button>
        <button type="button" className="field-button" onClick={() => void save()} disabled={saving}>
          {saving ? "Saving…" : "Save"}
        </button>
      </ButtonRow>
    </div>
  );
}
