"use client";

import { useEffect, useState } from "react";
import { Languages, Loader2, Sparkles } from "lucide-react";

import { useAuth } from "@/components/AuthProvider";
import { Markdown } from "@/components/Markdown";
import { FieldDialog } from "@/components/dialogs/FieldDialog";
import { ButtonRow } from "@/components/ui/ButtonRow";
import { Toggle } from "@/components/ui/Toggle";
import {
  REFINE_BUSY,
  REFINE_COST_TITLE,
  REFINE_LABEL,
  REFINE_REPLACE_QUESTION,
  REFINE_SAVED,
  REFINE_TRANSLATE_LABEL,
  mayRefine,
  refineCostSentence,
  refineOutcome,
  refineTranscript,
  refinedHeading,
  reminderSuppressed,
  saveRefinedTranscript,
  suppressReminder
} from "@/lib/transcriptRefine";
import type { MediaFile } from "@/lib/types";

type Props = {
  media: MediaFile;
  readOnly?: boolean;
  onUpdated?: (updated: MediaFile) => void;
};

export function TranscriptRefine({ media, readOnly = false, onUpdated }: Props) {
  const { user } = useAuth();
  const [refining, setRefining] = useState(false);
  const [refined, setRefined] = useState<string | null>(null);
  const [translated, setTranslated] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [saved, setSaved] = useState(false);
  const [pending, setPending] = useState<boolean | null>(null);
  const [dontRemind, setDontRemind] = useState(false);

  useEffect(() => {
    setRefined(null);
    setError(null);
    setSaved(false);
  }, [media.id]);

  if (readOnly || !mayRefine(user, media)) return null;

  async function run(translate: boolean) {
    setRefining(true);
    setError(null);
    setSaved(false);
    try {
      const outcome = refineOutcome(await refineTranscript(media.id, translate));
      if (outcome.kind === "refined") {
        setRefined(outcome.text);
        setTranslated(translate);
      } else {
        setError(outcome.text);
      }
    } catch (err) {
      setError(err instanceof Error && err.message ? err.message : "Couldn't refine the transcript.");
    } finally {
      setRefining(false);
    }
  }

  function ask(translate: boolean) {
    if (reminderSuppressed()) void run(translate);
    else {
      setDontRemind(false);
      setPending(translate);
    }
  }

  function confirm() {
    const mode = pending;
    if (mode === null) return;
    if (dontRemind) suppressReminder();
    setPending(null);
    void run(mode);
  }

  async function approve() {
    if (!refined) return;
    setSaving(true);
    setError(null);
    try {
      const updated = await saveRefinedTranscript(media.id, refined);
      setRefined(null);
      setSaved(true);
      onUpdated?.(updated);
    } catch (err) {
      setError(err instanceof Error && err.message ? err.message : "Couldn't save the refined transcript.");
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className="mt-3 grid gap-2" data-transcript-refine>
      <ButtonRow align="start" size="sm">
        <button type="button" className="field-button-secondary" onClick={() => ask(false)} disabled={refining || saving} data-testid="refine-transcript">
          <Sparkles className="h-4 w-4" aria-hidden />
          {REFINE_LABEL}
        </button>
        <button type="button" className="field-button-secondary" onClick={() => ask(true)} disabled={refining || saving} data-testid="refine-translate">
          <Languages className="h-4 w-4" aria-hidden />
          {REFINE_TRANSLATE_LABEL}
        </button>
      </ButtonRow>
      <div aria-live="polite" className="grid gap-2">
        {refining ? (
          <p className="flex items-center gap-2 text-sm text-ink-500">
            <Loader2 className="h-4 w-4 animate-spin" aria-hidden />
            {REFINE_BUSY}
          </p>
        ) : null}
        {error ? (
          <p className="rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700" data-refine-error>
            {error}
          </p>
        ) : null}
        {saved ? <p className="text-sm text-success-600">{REFINE_SAVED}</p> : null}
      </div>
      {refined ? (
        <section className="panel grid gap-2 p-3" aria-label={refinedHeading(translated)} data-refined>
          <h4 className="field-label">{refinedHeading(translated)}</h4>
          <Markdown text={refined} />
          <p className="text-sm text-ink-700">{REFINE_REPLACE_QUESTION}</p>
          <ButtonRow
            align="start"
            size="sm"
            destructive={
              <button type="button" className="field-button-secondary" onClick={() => setRefined(null)} disabled={saving}>
                Reject
              </button>
            }
          >
            <button type="button" className="field-button" onClick={approve} disabled={saving}>
              {saving ? "Saving…" : "Approve"}
            </button>
          </ButtonRow>
        </section>
      ) : null}
      <FieldDialog
        open={pending !== null}
        onClose={() => setPending(null)}
        title={REFINE_COST_TITLE}
        description={pending === null ? undefined : refineCostSentence(pending)}
        footer={
          <ButtonRow align="end">
            <button type="button" className="field-button-secondary" onClick={() => setPending(null)}>
              Cancel
            </button>
            <button type="button" className="field-button" onClick={confirm}>
              Continue
            </button>
          </ButtonRow>
        }
      >
        <Toggle label="Do not remind me again" checked={dontRemind} onChange={setDontRemind} />
      </FieldDialog>
    </div>
  );
}
