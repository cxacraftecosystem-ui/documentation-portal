"use client";

/**
 * The forms library's three small dialogs (UNIFY 4.2): New form, Reuse and Open bundle. Each one
 * creates a form of the caller's own and hands it back; the page opens it.
 *
 * Creating needs a connection (UNIFY 4.3): the buttons that open these are disabled offline.
 */

import { FilePlus2, Package, Copy } from "lucide-react";
import { useRef, useState } from "react";

import { FieldDialog } from "@/components/dialogs";
import { Field, TextArea, TextInput } from "@/components/FormControls";
import { FieldBlock } from "@/components/tasks/TaskPrimitives";
import { newDraft } from "@/lib/questionnaireDraftForms";
import { SearchableSelect } from "@/components/ui/SearchableSelect";
import {
  decodeBundleBytes,
  isBundleName,
  kindOptions,
  library,
  libraryError,
  p17,
  summariseBundle,
  type BundleSummary,
  type LibraryForm
} from "@/lib/questionnaireLibrary";

export function NewFormDialog({
  open,
  onClose,
  onCreated,
  online = true,
  owner = null
}: {
  open: boolean;
  onClose: () => void;
  onCreated: (form: Pick<LibraryForm, "id">) => void;
  /** Offline, the form is written on this device and sent later (A15). */
  online?: boolean;
  owner?: string | null;
}) {
  const [title, setTitle] = useState("");
  const [description, setDescription] = useState("");
  const [kind, setKind] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  function close() {
    if (busy) return;
    setTitle("");
    setDescription("");
    setKind("");
    setError(null);
    onClose();
  }

  async function submit() {
    if (!title.trim()) return;
    setBusy(true);
    setError(null);
    try {
      if (!online && owner) {
        const draft = newDraft(owner, { title, description: description.trim() || null, kind: kind || null });
        setTitle("");
        setDescription("");
        setKind("");
        onCreated({ id: draft.tempId });
        return;
      }
      const form = await library.create({ title: title.trim(), description: description.trim() || null, kind: kind || null });
      setTitle("");
      setDescription("");
      setKind("");
      onCreated(form);
    } catch (err) {
      setError(libraryError(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <FieldDialog
      open={open}
      onClose={close}
      busy={busy}
      title="New form"
      icon={<FilePlus2 className="h-5 w-5" aria-hidden />}
      footer={
        <>
          <button type="button" className="field-button-secondary" onClick={close} disabled={busy}>
            Cancel
          </button>
          <button type="button" className="field-button" onClick={submit} disabled={!title.trim() || busy}>
            {busy ? "Creating…" : "Create"}
          </button>
        </>
      }
    >
      <div className="grid gap-4">
        {error ? <p className="rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</p> : null}
        <Field label="Title" required>
          <TextInput value={title} maxLength={220} onChange={(event) => setTitle(event.target.value)} />
        </Field>
        <FieldBlock label="Kind">
          <SearchableSelect value={kind} onChange={setKind} options={kindOptions()} ariaLabel="Kind" advanceOnSelect={false} />
        </FieldBlock>
        <Field label="Description">
          <TextArea value={description} maxLength={2000} rows={2} onChange={(event) => setDescription(event.target.value)} />
        </Field>
      </div>
    </FieldDialog>
  );
}

export function ReuseFormDialog({
  source,
  onClose,
  onCreated
}: {
  source: LibraryForm | null;
  onClose: () => void;
  onCreated: (form: LibraryForm) => void;
}) {
  const [title, setTitle] = useState("");
  const [kind, setKind] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  function close() {
    if (busy) return;
    setTitle("");
    setKind(null);
    setError(null);
    onClose();
  }

  async function submit() {
    if (!source) return;
    setBusy(true);
    setError(null);
    try {
      const body: { title?: string | null; kind?: string | null } = { title: title.trim() || null };
      if (kind !== null) body.kind = kind || null;
      const form = await library.reuse(source.id, body);
      setTitle("");
      setKind(null);
      onCreated(form);
    } catch (err) {
      setError(libraryError(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <FieldDialog
      open={source !== null}
      onClose={close}
      busy={busy}
      title="Reuse this form"
      description="Copies its questions into a new form of yours."
      icon={<Copy className="h-5 w-5" aria-hidden />}
      footer={
        <>
          <button type="button" className="field-button-secondary" onClick={close} disabled={busy}>
            Cancel
          </button>
          <button type="button" className="field-button" onClick={submit} disabled={busy}>
            {busy ? "Copying…" : "Reuse"}
          </button>
        </>
      }
    >
      <div className="grid gap-4">
        {error ? <p className="rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</p> : null}
        <Field label="Title">
          <TextInput
            value={title}
            maxLength={220}
            placeholder={source ? `${source.title} (copy)` : undefined}
            onChange={(event) => setTitle(event.target.value)}
          />
        </Field>
        <FieldBlock label="Kind">
          <SearchableSelect
            value={kind ?? source?.kind ?? ""}
            onChange={setKind}
            options={kindOptions()}
            ariaLabel="Kind"
            advanceOnSelect={false}
          />
        </FieldBlock>
      </div>
    </FieldDialog>
  );
}

/**
 * Open bundle: read a `.frwq` or `.dpwq` file in the browser, show what it holds (P17), and add it
 * as a form of your own. A bundle from a newer build is refused before anything is sent.
 */
export function OpenBundleDialog({
  open,
  onClose,
  onImported
}: {
  open: boolean;
  onClose: () => void;
  onImported: (form: LibraryForm, created: boolean) => void;
}) {
  const [document, setDocument] = useState<unknown>(null);
  const [summary, setSummary] = useState<BundleSummary | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const input = useRef<HTMLInputElement | null>(null);

  function reset() {
    setDocument(null);
    setSummary(null);
    setError(null);
    if (input.current) input.current.value = "";
  }

  function close() {
    if (busy) return;
    reset();
    onClose();
  }

  async function choose(file: File | null) {
    reset();
    if (!file) return;
    if (!isBundleName(file.name)) {
      setError("Choose a .frwq or .dpwq file.");
      return;
    }
    try {
      const parsed = await decodeBundleBytes(new Uint8Array(await file.arrayBuffer()));
      const found = summariseBundle(parsed);
      if (!found || !found.title) {
        setError("This file isn't a questionnaire bundle.");
        return;
      }
      if (found.tooNew) {
        setError("This file was made by a newer version of the app. Update, then open it again.");
        return;
      }
      setDocument(parsed);
      setSummary(found);
    } catch (err) {
      setError(libraryError(err, "This file isn't a questionnaire bundle."));
    }
  }

  async function add() {
    if (!document) return;
    setBusy(true);
    setError(null);
    try {
      const result = await library.importBundle(document);
      reset();
      onImported(result.questionnaire, result.created);
    } catch (err) {
      setError(libraryError(err));
    } finally {
      setBusy(false);
    }
  }

  const prompt = summary ? p17({ title: summary.title, sections: summary.sections }) : null;

  return (
    <FieldDialog
      open={open}
      onClose={close}
      busy={busy}
      title={prompt ? prompt.title : "Open bundle"}
      icon={<Package className="h-5 w-5" aria-hidden />}
      footer={
        prompt ? (
          <>
            <button type="button" className="field-button-secondary" onClick={close} disabled={busy}>
              {prompt.buttons[0]}
            </button>
            <button type="button" className="field-button" onClick={add} disabled={busy}>
              {busy ? "Adding…" : prompt.buttons[1]}
            </button>
          </>
        ) : (
          <button type="button" className="field-button-secondary" onClick={close}>
            Cancel
          </button>
        )
      }
    >
      <div className="grid gap-4" data-prompt={prompt ? "P17" : undefined}>
        {error ? <p className="rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</p> : null}
        {prompt ? (
          <p className="text-sm leading-6 text-ink-700" data-testid="bundle-summary">
            {prompt.body}
          </p>
        ) : (
          <label className="file-trigger">
            <Package className="h-4 w-4" aria-hidden />
            Choose a .frwq or .dpwq file
            <input
              ref={input}
              type="file"
              className="sr-only"
              accept=".frwq,.dpwq,application/gzip,application/json"
              data-bundle-input
              onChange={(event) => void choose(event.target.files?.[0] ?? null)}
            />
          </label>
        )}
      </div>
    </FieldDialog>
  );
}
