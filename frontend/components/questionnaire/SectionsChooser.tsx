"use client";

import { useId, useState } from "react";

import { FieldDialog } from "@/components/dialogs/FieldDialog";
import { ButtonRow } from "@/components/ui/ButtonRow";
import { SearchableMultiSelect } from "@/components/ui/SearchableSelect";
import { qs } from "@/lib/questionnaire/qs";
import { coverCodes, coverHelper, coverIds, coverOptions } from "@/lib/questionnaire/sectionCover";

export type ChooserSection = { id: string; code: string; title: string };

export function SectionsChooser({ sections, values, onChange, disabled = false }: { sections: ChooserSection[]; values: string[]; onChange: (ids: string[]) => void; disabled?: boolean }) {
  const labelId = useId();
  const helperId = useId();
  const codes = coverCodes(sections, values);
  return (
    <div className="grid gap-2" data-sections-chooser>
      <span id={labelId} className="field-label">
        {qs("coversSections")}
      </span>
      <SearchableMultiSelect
        values={values}
        onChange={(next) => onChange(coverIds(sections, next))}
        options={coverOptions(sections)}
        searchable
        disabled={disabled}
        ariaLabel={qs("coversSections")}
      />
      <span id={helperId} className="text-sm text-ink-500" data-sections-helper>
        {coverHelper(codes)}
      </span>
    </div>
  );
}

export function MarkSectionsDialog({
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
    <FieldDialog open onClose={onClose} title={qs("markSections")} className="max-w-xl" busy={busy}>
      <div className="grid gap-4" data-mark-sections>
        <p className="min-w-0 truncate text-sm text-ink-700" title={name}>
          {name}
        </p>
        <SectionsChooser sections={sections} values={chosen} onChange={setChosen} disabled={busy} />
        {notice ? (
          <p role="status" className="rounded-md bg-amber-100 px-3 py-2 text-sm font-medium text-amber-800 dark:bg-amber-900/40 dark:text-amber-100" data-mark-sections-notice>
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
            data-mark-sections-save
          >
            {qs("save")}
          </button>
        </ButtonRow>
      </div>
    </FieldDialog>
  );
}
