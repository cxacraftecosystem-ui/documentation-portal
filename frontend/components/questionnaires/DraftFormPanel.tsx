"use client";

/**
 * A form written on this device with no connection (UNIFY A15): its sections and questions, added
 * here with temporary ids, and Send now once there is a connection. Sending lands the form, then its
 * sections, then its questions, and opens the real form.
 */

import { CloudUpload, ClipboardList, Plus } from "lucide-react";
import { useRouter } from "next/navigation";
import { useState } from "react";

import { useAuth } from "@/components/AuthProvider";
import { Field, TextInput } from "@/components/FormControls";
import { PageHeader } from "@/components/PageHeader";
import { NEEDS_CONNECTION, useOnline } from "@/components/questionnaires/useOnline";
import { ButtonRow } from "@/components/ui/ButtonRow";
import { Toggle } from "@/components/ui/Toggle";
import { addDraftQuestion, addDraftSection, draftSummary, getDraft, sendDraft, type DraftForm } from "@/lib/questionnaireDraftForms";
import { kindLabel } from "@/lib/questionnaireLibrary";

export function DraftFormPanel({ id }: { id: string }) {
  const { user } = useAuth();
  const router = useRouter();
  const online = useOnline();
  // Read from this device on every render (the account is known only once signed in); `revision`
  // re-renders after each change the panel writes.
  const [revision, setRevision] = useState(0);
  const draft: DraftForm | null = user ? getDraft(user.id, id) : null;
  void revision;
  const refresh = () => setRevision((n) => n + 1);
  const [code, setCode] = useState("");
  const [title, setTitle] = useState("");
  const [prompts, setPrompts] = useState<Record<string, string>>({});
  const [required, setRequired] = useState<Record<string, boolean>>({});
  const [sending, setSending] = useState(false);

  if (!draft) {
    return (
      <>
        <PageHeader title="Questionnaire" icon={<ClipboardList className="h-5 w-5" aria-hidden />} />
        <p className="panel p-4 text-sm text-ink-700">This draft is not on this device.</p>
      </>
    );
  }

  async function send() {
    if (!draft) return;
    setSending(true);
    try {
      const realId = await sendDraft(draft);
      if (realId) router.replace(`/questionnaires/${encodeURIComponent(realId)}`);
      else refresh();
    } finally {
      setSending(false);
    }
  }

  return (
    <>
      <PageHeader
        title={draft.title}
        description={`${kindLabel(draft.kind)} · ${draftSummary(draft)} · On this device`}
        icon={<ClipboardList className="h-5 w-5" aria-hidden />}
        actions={
          <button type="button" className="field-button" disabled={!online || sending} title={online ? undefined : NEEDS_CONNECTION} onClick={() => void send()} data-send-draft>
            <CloudUpload className="h-4 w-4" aria-hidden /> {sending ? "Sending…" : "Send now"}
          </button>
        }
      />
      {draft.error ? <p className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{draft.error}</p> : null}
      {!online ? <p className="mb-4 text-sm text-ink-500">Saved on this device. It sends when you are back online.</p> : null}

      <div className="grid gap-5" data-draft-form>
        {draft.sections.map((section) => (
          <section key={section.tempId} className="panel grid gap-3 p-4" data-draft-section={section.code}>
            <h2 className="font-display text-lg font-bold text-ink-900">
              {section.code} · {section.title}
            </h2>
            {section.questions.length ? (
              <ol className="grid list-decimal gap-1 pl-5 text-sm text-ink-700">
                {section.questions.map((q) => (
                  <li key={q.tempId}>
                    {q.prompt}
                    {q.isRequired ? <span className="text-ink-500"> · Required</span> : null}
                  </li>
                ))}
              </ol>
            ) : null}
            <Field label="New question">
              <TextInput value={prompts[section.tempId] ?? ""} onChange={(e) => setPrompts({ ...prompts, [section.tempId]: e.target.value })} />
            </Field>
            <Toggle label="Required" checked={Boolean(required[section.tempId])} onChange={(next) => setRequired({ ...required, [section.tempId]: next })} />
            <ButtonRow align="end">
              <button
                type="button"
                className="field-button-secondary"
                disabled={!(prompts[section.tempId] ?? "").trim()}
                onClick={() => {
                  addDraftQuestion(draft, section.tempId, prompts[section.tempId] ?? "", Boolean(required[section.tempId]));
                  refresh();
                  setPrompts({ ...prompts, [section.tempId]: "" });
                }}
              >
                <Plus className="h-4 w-4" aria-hidden /> Add question
              </button>
            </ButtonRow>
          </section>
        ))}

        <section className="panel grid gap-3 p-4" aria-label="New section">
          <div className="grid gap-3 sm:grid-cols-[8rem_1fr]">
            <Field label="Code">
              <TextInput value={code} maxLength={24} onChange={(e) => setCode(e.target.value)} />
            </Field>
            <Field label="Section title">
              <TextInput value={title} maxLength={220} onChange={(e) => setTitle(e.target.value)} />
            </Field>
          </div>
          <ButtonRow align="end">
            <button
              type="button"
              className="field-button-secondary"
              disabled={!code.trim() || !title.trim()}
              onClick={() => {
                addDraftSection(draft, code, title);
                refresh();
                setCode("");
                setTitle("");
              }}
            >
              <Plus className="h-4 w-4" aria-hidden /> Add section
            </button>
          </ButtonRow>
        </section>
      </div>
    </>
  );
}
