"use client";

/**
 * Each workshop type's default form (owner, amendment 2 point 2): an admin act. A session on a
 * workshop with no primary binding starts on its type's default, then the global default, then the
 * picker. Clearing a type leaves the global default to answer.
 */

import { useCallback, useEffect, useState } from "react";

import { useToast } from "@/components/ui/Toast";
import { SearchableSelect } from "@/components/ui/SearchableSelect";
import { apiFetch } from "@/lib/api";
import {
  library,
  libraryError,
  WORKSHOP_TYPE_LABELS,
  WORKSHOP_TYPES,
  type LibraryForm,
  type TypeDefault,
  type WorkshopType
} from "@/lib/questionnaireLibrary";
import { NEEDS_CONNECTION } from "@/components/questionnaires/useOnline";

export function TypeDefaultsCard({ online }: { online: boolean }) {
  const { toast } = useToast();
  const [forms, setForms] = useState<LibraryForm[]>([]);
  const [defaults, setDefaults] = useState<TypeDefault[] | null>(null);
  const [saving, setSaving] = useState<WorkshopType | null>(null);

  const load = useCallback(async () => {
    try {
      const [all, current] = await Promise.all([apiFetch<LibraryForm[]>("/questionnaires"), library.typeDefaults()]);
      setForms(all);
      setDefaults(current.defaults);
    } catch (err) {
      setDefaults([]);
      toast({ tone: "error", title: "Couldn't load the type defaults", description: libraryError(err) });
    }
  }, [toast]);

  useEffect(() => {
    void load();
  }, [load]);

  async function choose(type: WorkshopType, questionnaireId: string) {
    setSaving(type);
    try {
      await library.putTypeDefault(type, questionnaireId || null);
      await load();
    } catch (err) {
      toast({ tone: "error", title: "Not saved", description: libraryError(err) });
    } finally {
      setSaving(null);
    }
  }

  return (
    <section className="panel grid gap-3 p-4" aria-labelledby="type-defaults-heading">
      <div>
        <h2 id="type-defaults-heading" className="font-display text-lg font-bold text-ink-900">
          Default form per workshop type
        </h2>
        <p className="mt-1 text-sm text-ink-500">Used when a workshop has no primary form.</p>
      </div>
      {defaults === null ? (
        <p className="text-sm text-ink-500">Loading…</p>
      ) : (
        <div className="grid gap-3 sm:grid-cols-2">
          {WORKSHOP_TYPES.map((type) => {
            const current = defaults.find((row) => row.workshopType === type)?.questionnaireId ?? "";
            return (
              <div key={type} className="grid gap-1">
                <span className="field-label">{WORKSHOP_TYPE_LABELS[type]}</span>
                <SearchableSelect
                  value={current}
                  onChange={(value) => void choose(type, value)}
                  options={forms.map((form) => ({ value: form.id, label: form.title }))}
                  placeholder="Global default"
                  ariaLabel={`Default form for ${WORKSHOP_TYPE_LABELS[type]}`}
                  disabled={!online || saving !== null}
                  searchable
                  clearable
                  clearLabel="Use the global default"
                  advanceOnSelect={false}
                />
                {!online ? <span className="text-xs text-ink-500">{NEEDS_CONNECTION}</span> : null}
              </div>
            );
          })}
        </div>
      )}
    </section>
  );
}
