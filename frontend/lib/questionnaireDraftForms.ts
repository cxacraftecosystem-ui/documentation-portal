import { apiFetch } from "@/lib/api";
import type { JsonFetcher } from "@/lib/questionnaireApi";

export const DRAFT_FORMS_KEY = "field_repo_form_drafts";
export const DRAFT_PREFIX = "draft-";

export type DraftQuestion = { tempId: string; prompt: string; isRequired: boolean; serverId?: string };
export type DraftSection = { tempId: string; code: string; title: string; questions: DraftQuestion[]; serverId?: string };
export type DraftForm = {
  tempId: string;
  owner: string;
  title: string;
  description: string | null;
  kind: string | null;
  createdAt: string;
  sections: DraftSection[];
  serverId?: string;
  error?: string;
};

type KeyStore = Pick<Storage, "getItem" | "setItem" | "removeItem">;

function browserStore(): KeyStore | null {
  try {
    return typeof window === "undefined" ? null : window.localStorage;
  } catch {
    return null;
  }
}

function tempId(kind: string): string {
  const bytes = new Uint8Array(8);
  crypto.getRandomValues(bytes);
  return `${DRAFT_PREFIX}${kind}-${Array.from(bytes, (b) => b.toString(16).padStart(2, "0")).join("")}`;
}

export function isDraftId(id: string | null | undefined): boolean {
  return Boolean(id && id.startsWith(DRAFT_PREFIX));
}

function readAll(store: KeyStore | null): DraftForm[] {
  if (!store) return [];
  try {
    const raw = store.getItem(DRAFT_FORMS_KEY);
    return raw ? (JSON.parse(raw) as DraftForm[]) : [];
  } catch {
    return [];
  }
}

function writeAll(drafts: DraftForm[], store: KeyStore | null): void {
  if (!store) return;
  try {
    if (drafts.length) store.setItem(DRAFT_FORMS_KEY, JSON.stringify(drafts));
    else store.removeItem(DRAFT_FORMS_KEY);
  } catch {
  }
}

export function listDrafts(owner: string | null | undefined, store: KeyStore | null = browserStore()): DraftForm[] {
  if (!owner) return [];
  return readAll(store).filter((d) => d.owner === owner);
}

export function getDraft(owner: string | null | undefined, id: string, store: KeyStore | null = browserStore()): DraftForm | null {
  return listDrafts(owner, store).find((d) => d.tempId === id) ?? null;
}

export function saveDraft(draft: DraftForm, store: KeyStore | null = browserStore()): DraftForm {
  const all = readAll(store).filter((d) => d.tempId !== draft.tempId);
  writeAll([...all, draft], store);
  return draft;
}

export function removeDraft(owner: string, id: string, store: KeyStore | null = browserStore()): void {
  writeAll(readAll(store).filter((d) => !(d.owner === owner && d.tempId === id)), store);
}

export function newDraft(
  owner: string,
  input: { title: string; description?: string | null; kind?: string | null },
  store: KeyStore | null = browserStore(),
  now: Date = new Date()
): DraftForm {
  return saveDraft(
    { tempId: tempId("form"), owner, title: input.title.trim(), description: input.description ?? null, kind: input.kind ?? null, createdAt: now.toISOString(), sections: [] },
    store
  );
}

export function addDraftSection(draft: DraftForm, code: string, title: string, store: KeyStore | null = browserStore()): DraftForm {
  return saveDraft({ ...draft, sections: [...draft.sections, { tempId: tempId("section"), code: code.trim(), title: title.trim(), questions: [] }] }, store);
}

export function addDraftQuestion(draft: DraftForm, sectionTempId: string, prompt: string, isRequired: boolean, store: KeyStore | null = browserStore()): DraftForm {
  return saveDraft(
    {
      ...draft,
      sections: draft.sections.map((s) =>
        s.tempId === sectionTempId ? { ...s, questions: [...s.questions, { tempId: tempId("question"), prompt: prompt.trim(), isRequired }] } : s
      )
    },
    store
  );
}

export function draftSummary(draft: DraftForm): string {
  const questions = draft.sections.reduce((n, s) => n + s.questions.length, 0);
  return `${draft.sections.length} ${draft.sections.length === 1 ? "section" : "sections"} · ${questions} ${questions === 1 ? "question" : "questions"}`;
}

export async function sendDraft(
  draft: DraftForm,
  fetchJson: JsonFetcher = apiFetch,
  store: KeyStore | null = browserStore()
): Promise<string | null> {
  let current: DraftForm = { ...draft, error: undefined };
  const keep = () => saveDraft(current, store);
  try {
    if (!current.serverId) {
      const form = await fetchJson<{ id: string }>("/questionnaires", {
        method: "POST",
        body: JSON.stringify({ title: current.title, description: current.description, kind: current.kind }),
        headers: { "Idempotency-Key": current.tempId }
      });
      current = { ...current, serverId: form.id };
      keep();
    }
    for (let si = 0; si < current.sections.length; si += 1) {
      let section = current.sections[si];
      if (!section.serverId) {
        const made = await fetchJson<{ id: string }>("/questionnaire/sections", {
          method: "POST",
          body: JSON.stringify({ questionnaireId: current.serverId, code: section.code, title: section.title })
        });
        section = { ...section, serverId: made.id };
        current = { ...current, sections: current.sections.map((s, i) => (i === si ? section : s)) };
        keep();
      }
      for (let qi = 0; qi < section.questions.length; qi += 1) {
        const question = section.questions[qi];
        if (question.serverId) continue;
        const made = await fetchJson<{ id: string }>("/questionnaire/questions", {
          method: "POST",
          body: JSON.stringify({ sectionId: section.serverId, prompt: question.prompt, isRequired: question.isRequired })
        });
        section = { ...section, questions: section.questions.map((q, i) => (i === qi ? { ...q, serverId: made.id } : q)) };
        current = { ...current, sections: current.sections.map((s, i) => (i === si ? section : s)) };
        keep();
      }
    }
  } catch (err) {
    const status = (err as { status?: number })?.status;
    if (typeof status === "number" && status >= 400 && status < 500) {
      current = { ...current, error: err instanceof Error ? err.message : "Not accepted." };
      keep();
    }
    return null;
  }
  removeDraft(current.owner, current.tempId, store);
  return current.serverId ?? null;
}
