"use client";

import { useState } from "react";

import { FieldDialog } from "@/components/dialogs/FieldDialog";
import { ButtonRow } from "@/components/ui/ButtonRow";
import { SearchableMultiSelect } from "@/components/ui/SearchableSelect";
import { qs } from "@/lib/questionnaire/qs";
import { coverCodes, coverHelper, coverIds, coverOptions } from "@/lib/questionnaire/sectionCover";

export type ChooserSection = { id: string; code: string; title: string };

export function SectionsChooser({
  sections,
  values,
  onChange,
  disabled = false,
  label = null,
  showEmpty = false
}: {
  sections: ChooserSection[];
  values: string[];
  onChange: (ids: string[]) => void;
  disabled?: boolean;
  label?: string | null;
  showEmpty?: boolean;
}) {
  const codes = coverCodes(sections, values);
  return (
    <div className="grid gap-2" data-sections-chooser>
      {label ? <span className="field-label">{label}</span> : null}
      <SearchableMultiSelect
        values={values}
        onChange={(next) => onChange(coverIds(sections, next))}
        options={coverOptions(sections)}
        searchable
        disabled={disabled}
        ariaLabel={qs("attachSections")}
      />
      {codes.length || showEmpty ? (
        <span className="text-sm text-ink-500" data-sections-helper>
          {coverHelper(codes)}
        </span>
      ) : null}
    </div>
  );
}

export function ChangeSectionsDialog({
  name,
  sections,
  initial,
  notice,
  onClose,
  onSave
}: {
  name: string;
  sections: ChooserSection[];
  initial: string[];
  notice: string | null;
  onClose: () => void;
  onSave: (ids: string[]) => Promise<void>;
}) {
  const [chosen, setChosen] = useState<string[]>(initial);
  const [busy, setBusy] = useState(false);
  return (
    <FieldDialog open onClose={onClose} title={qs("changeSections")} className="max-w-xl" busy={busy}>
      <div className="grid gap-4" data-change-sections>
        <p className="min-w-0 truncate text-sm text-ink-700" title={name}>
          {name}
        </p>
        <SectionsChooser sections={sections} values={chosen} onChange={setChosen} disabled={busy} label={qs("attachSections")} showEmpty />
        {notice ? (
          <p role="status" className="rounded-md bg-amber-100 px-3 py-2 text-sm font-medium text-amber-800 dark:bg-amber-900/40 dark:text-amber-100" data-change-sections-notice>
            {notice}
          </p>
        ) : null}
        <ButtonRow className="pt-1" fill>
          <button type="button" className="field-button-secondary" onClick={onClose} disabled={busy}>
            {qs("cancel")}
          </button>
          <button
            type="button"
            className="field-button"
            disabled={busy}
            onClick={() => {
              setBusy(true);
              void onSave(chosen).finally(() => setBusy(false));
            }}
            data-change-sections-save
          >
            {qs("save")}
          </button>
        </ButtonRow>
      </div>
    </FieldDialog>
  );
}
