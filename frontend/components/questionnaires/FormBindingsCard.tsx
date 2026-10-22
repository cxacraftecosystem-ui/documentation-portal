"use client";

/**
 * Which workshops a form is bound to, and which of them it is the PRIMARY form for (UNIFY U5): an
 * admin act. A workshop has at most one primary form; making this one primary replaces the old
 * primary there. Several forms may be bound to one workshop; the start sheet offers the primary.
 */

import { Link2 } from "lucide-react";
import { useCallback, useEffect, useState } from "react";

import { ButtonRow } from "@/components/ui/ButtonRow";
import { SearchableMultiSelect } from "@/components/ui/SearchableSelect";
import { useToast } from "@/components/ui/Toast";
import { bindingsFor, library, libraryError } from "@/lib/questionnaireLibrary";
import { NEEDS_CONNECTION } from "@/components/questionnaires/useOnline";

export function FormBindingsCard({
  questionnaireId,
  workshops,
  canBind,
  online
}: {
  questionnaireId: string;
  workshops: { id: string; title: string }[];
  canBind: boolean;
  online: boolean;
}) {
  const { toast } = useToast();
  const [bound, setBound] = useState<string[] | null>(null);
  const [primary, setPrimary] = useState<string[]>([]);
  const [dirty, setDirty] = useState(false);
  const [saving, setSaving] = useState(false);

  const load = useCallback(async () => {
    try {
      const answer = await library.bindings(questionnaireId);
      const rows = answer.bindings.filter((row) => row.workshopId);
      setBound(rows.map((row) => row.workshopId as string));
      setPrimary(rows.filter((row) => row.isPrimary).map((row) => row.workshopId as string));
      setDirty(false);
    } catch (err) {
      setBound([]);
      toast({ tone: "error", title: "Couldn't load the workshops", description: libraryError(err) });
    }
  }, [questionnaireId, toast]);

  useEffect(() => {
    void load();
  }, [load]);

  async function save() {
    if (!bound) return;
    setSaving(true);
    try {
      await library.putBindings(questionnaireId, bindingsFor(bound, primary));
      toast({ tone: "success", title: "Workshops saved" });
      await load();
    } catch (err) {
      toast({ tone: "error", title: "Not saved", description: libraryError(err) });
    } finally {
      setSaving(false);
    }
  }

  const titleOf = (id: string) => workshops.find((w) => w.id === id)?.title ?? id;
  const options = workshops.map((w) => ({ value: w.id, label: w.title }));

  return (
    <section className="panel grid gap-3 p-4" aria-labelledby="bindings-heading" data-form-bindings>
      <h2 id="bindings-heading" className="flex items-center gap-2 font-display text-lg font-bold text-ink-900">
        <Link2 className="h-4 w-4" aria-hidden /> Workshops
      </h2>
      {bound === null ? (
        <p className="text-sm text-ink-500">Loading…</p>
      ) : canBind ? (
        <>
          <div className="grid gap-1">
            <span className="field-label">Bound to</span>
            <SearchableMultiSelect
              values={bound}
              onChange={(values) => {
                setBound(values);
                setPrimary((current) => current.filter((id) => values.includes(id)));
                setDirty(true);
              }}
              options={options}
              ariaLabel="Workshops this form is bound to"
              searchable
              confirmOnSelect={false}
              disabled={!online || saving}
            />
          </div>
          {bound.length ? (
            <div className="grid gap-1">
              <span className="field-label">Primary form for</span>
              <SearchableMultiSelect
                values={primary}
                onChange={(values) => {
                  setPrimary(values);
                  setDirty(true);
                }}
                options={bound.map((id) => ({ value: id, label: titleOf(id) }))}
                ariaLabel="Workshops this form is the primary form for"
                confirmOnSelect={false}
                disabled={!online || saving}
              />
            </div>
          ) : null}
          <ButtonRow align="end">
            <button type="button" className="field-button" disabled={!dirty || saving || !online} title={online ? undefined : NEEDS_CONNECTION} onClick={save}>
              {saving ? "Saving…" : "Save workshops"}
            </button>
          </ButtonRow>
        </>
      ) : bound.length ? (
        <ul className="grid gap-1 text-sm text-ink-700">
          {bound.map((id) => (
            <li key={id}>
              {titleOf(id)}
              {primary.includes(id) ? <span className="text-ink-500"> · Primary</span> : null}
            </li>
          ))}
        </ul>
      ) : (
        <p className="text-sm text-ink-500">Not bound to a workshop.</p>
      )}
    </section>
  );
}
