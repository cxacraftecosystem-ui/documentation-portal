"use client";

import { ArrowDown, ArrowUp, FolderTree } from "lucide-react";
import { useState } from "react";

import { deleteConfirm, useConfirm } from "@/components/dialogs/ConfirmDialog";
import { FieldDialog } from "@/components/dialogs/FieldDialog";
import { FieldBlock } from "@/components/tasks/TaskPrimitives";
import { ButtonRow } from "@/components/ui/ButtonRow";
import { Dropdown } from "@/components/ui/Dropdown";
import { RowActions, rowAction } from "@/components/RowActions";
import {
  createCraftCategory,
  createCraftSubCategory,
  deleteCraftCategory,
  deleteCraftSubCategory,
  reorderMove,
  updateCraftCategory,
  updateCraftSubCategory
} from "@/components/crafts/craftAdmin";
import type { CraftCatalogue, CraftCatalogueCategory, CraftCatalogueSubCategory } from "@/lib/craftCatalogue";
import { countNoun } from "@/lib/format";

/** What the one dialog on this tab is doing. */
type Edit =
  | { kind: "addCategory" }
  | { kind: "renameCategory"; category: CraftCatalogueCategory }
  | { kind: "addSub"; categoryId: string }
  | { kind: "renameSub"; sub: CraftCatalogueSubCategory }
  | { kind: "moveSub"; sub: CraftCatalogueSubCategory; categoryId: string };

/**
 * Categories and their sub-categories (REQUIREMENTS_2026-10-20 §2.6): rename, move, delete, add,
 * and reorder by up and down buttons — no drag-only control. Web only, in both portals. The same
 * component, name and props as the Designer Repository's; only the button-row primitive differs.
 */
export function CraftCategoriesTab({
  catalogue,
  canEdit,
  canDelete,
  onChanged
}: {
  catalogue: CraftCatalogue;
  /** Content admins manage the taxonomy; everyone else reads it. */
  canEdit: boolean;
  /** Delete is drawn in admin view only (§5). */
  canDelete: boolean;
  onChanged: () => void;
}) {
  const confirm = useConfirm();
  const [edit, setEdit] = useState<Edit | null>(null);
  const [name, setName] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const categories = [...catalogue.categories].sort((a, b) => a.sortOrder - b.sortOrder);

  function open(next: Edit, initialName = "") {
    setEdit(next);
    setName(initialName);
    setError(null);
  }

  async function run(action: () => Promise<unknown>) {
    setBusy(true);
    setError(null);
    try {
      await action();
      setEdit(null);
      onChanged();
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : "That didn't save. Try again.");
    } finally {
      setBusy(false);
    }
  }

  function save() {
    if (!edit) return;
    const trimmed = name.trim();
    if (edit.kind !== "moveSub" && !trimmed) {
      setError("Enter a name.");
      return;
    }
    void run(async () => {
      switch (edit.kind) {
        case "addCategory":
          return createCraftCategory(trimmed);
        case "renameCategory":
          return updateCraftCategory(edit.category.id, { name: trimmed });
        case "addSub":
          return createCraftSubCategory(trimmed, edit.categoryId);
        case "renameSub":
          return updateCraftSubCategory(edit.sub.id, { name: trimmed });
        case "moveSub":
          return updateCraftSubCategory(edit.sub.id, { categoryId: edit.categoryId });
      }
    });
  }

  async function reorder(kind: "category" | "sub", items: readonly { id: string }[], index: number, step: -1 | 1) {
    const move = reorderMove(items, index, step);
    if (!move) return;
    await run(() =>
      kind === "category"
        ? updateCraftCategory(move.id, { sortOrder: move.sortOrder })
        : updateCraftSubCategory(move.id, { sortOrder: move.sortOrder })
    );
  }

  async function removeCategory(category: CraftCatalogueCategory) {
    const ok = await confirm(deleteConfirm(`Delete “${category.name}”?`, "This can’t be undone."));
    if (ok) await run(() => deleteCraftCategory(category.id));
  }

  async function removeSub(sub: CraftCatalogueSubCategory) {
    const ok = await confirm(deleteConfirm(`Delete “${sub.name}”?`, "This can’t be undone."));
    if (ok) await run(() => deleteCraftSubCategory(sub.id));
  }

  const title =
    edit?.kind === "addCategory"
      ? "Add category"
      : edit?.kind === "addSub"
        ? "Add sub-category"
        : edit?.kind === "moveSub"
          ? `Move “${edit.sub.name}”`
          : "Rename";

  return (
    <section className="grid gap-4" aria-label="Categories">
      {canEdit ? (
        <ButtonRow>
          <button type="button" className="field-button" onClick={() => open({ kind: "addCategory" })}>
            Add category
          </button>
        </ButtonRow>
      ) : null}
      {error && !edit ? (
        <p role="alert" className="rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">
          {error}
        </p>
      ) : null}
      <ol className="grid gap-3">
        {categories.map((category, categoryAt) => {
          const subs = [...category.subCategories].sort((a, b) => a.sortOrder - b.sortOrder);
          const crafts = subs.reduce((sum, sub) => sum + sub.craftCount, 0);
          return (
            <li key={category.id} className="panel p-4">
              <div className="flex flex-wrap items-start justify-between gap-3">
                <div className="flex min-w-0 items-start gap-3">
                  <span className="grid h-9 w-9 shrink-0 place-items-center rounded-md bg-purple-50 text-purple-700">
                    <FolderTree className="h-4 w-4" aria-hidden />
                  </span>
                  <div className="min-w-0">
                    <h3 className="font-display text-base font-semibold text-ink-900">{category.name}</h3>
                    <p className="text-xs text-ink-500">
                      {countNoun(subs.length, "sub-category", "sub-categories")} · {countNoun(crafts, "craft")}
                    </p>
                  </div>
                </div>
                {canEdit ? (
                  <RowActions
                    destructive={
                      canDelete ? (
                        <button type="button" data-danger className={rowAction("danger")} onClick={() => void removeCategory(category)}>
                          Delete
                        </button>
                      ) : undefined
                    }
                  >
                    <button
                      type="button"
                      className={rowAction("neutral")}
                      aria-label={`Move ${category.name} up`}
                      disabled={busy || categoryAt === 0}
                      onClick={() => void reorder("category", categories, categoryAt, -1)}
                    >
                      <ArrowUp className="h-4 w-4" aria-hidden />
                    </button>
                    <button
                      type="button"
                      className={rowAction("neutral")}
                      aria-label={`Move ${category.name} down`}
                      disabled={busy || categoryAt === categories.length - 1}
                      onClick={() => void reorder("category", categories, categoryAt, 1)}
                    >
                      <ArrowDown className="h-4 w-4" aria-hidden />
                    </button>
                    <button type="button" className={rowAction("edit")} onClick={() => open({ kind: "renameCategory", category }, category.name)}>
                      Rename
                    </button>
                    <button type="button" className={rowAction("neutral")} onClick={() => open({ kind: "addSub", categoryId: category.id })}>
                      Add sub-category
                    </button>
                  </RowActions>
                ) : null}
              </div>
              {subs.length ? (
                <ul className="mt-3 divide-y divide-line-200 rounded-md border border-line-200">
                  {subs.map((sub, subAt) => (
                    <li key={sub.id} className="flex flex-wrap items-center justify-between gap-2 px-3 py-2">
                      <span className="min-w-0 text-sm text-ink-900">
                        {sub.name} <span className="text-xs text-ink-500">· {countNoun(sub.craftCount ?? 0, "craft")}</span>
                      </span>
                      {canEdit ? (
                        <RowActions
                          destructive={
                            canDelete ? (
                              <button type="button" data-danger className={rowAction("danger")} onClick={() => void removeSub(sub)}>
                                Delete
                              </button>
                            ) : undefined
                          }
                        >
                          <button
                            type="button"
                            className={rowAction("neutral")}
                            aria-label={`Move ${sub.name} up`}
                            disabled={busy || subAt === 0}
                            onClick={() => void reorder("sub", subs, subAt, -1)}
                          >
                            <ArrowUp className="h-4 w-4" aria-hidden />
                          </button>
                          <button
                            type="button"
                            className={rowAction("neutral")}
                            aria-label={`Move ${sub.name} down`}
                            disabled={busy || subAt === subs.length - 1}
                            onClick={() => void reorder("sub", subs, subAt, 1)}
                          >
                            <ArrowDown className="h-4 w-4" aria-hidden />
                          </button>
                          <button type="button" className={rowAction("edit")} onClick={() => open({ kind: "renameSub", sub }, sub.name)}>
                            Rename
                          </button>
                          <button
                            type="button"
                            className={rowAction("neutral")}
                            onClick={() => open({ kind: "moveSub", sub, categoryId: category.id })}
                          >
                            Move
                          </button>
                        </RowActions>
                      ) : null}
                    </li>
                  ))}
                </ul>
              ) : (
                <p className="mt-3 text-sm text-ink-500">No sub-categories yet.</p>
              )}
            </li>
          );
        })}
      </ol>

      <FieldDialog
        open={edit !== null}
        onClose={() => (busy ? undefined : setEdit(null))}
        busy={busy}
        title={title}
        footer={
          <>
            <button type="button" className="field-button-secondary" disabled={busy} onClick={() => setEdit(null)}>
              Cancel
            </button>
            <button type="button" className="field-button" disabled={busy} onClick={save}>
              {busy ? "Saving…" : "Save"}
            </button>
          </>
        }
      >
        {edit?.kind === "moveSub" ? (
          <FieldBlock label="Category">
            <Dropdown
              ariaLabel="Category"
              value={edit.categoryId}
              advanceOnSelect={false}
              onChange={(categoryId) => setEdit({ ...edit, categoryId })}
              options={categories.map((category) => ({ value: category.id, label: category.name }))}
            />
          </FieldBlock>
        ) : edit ? (
          <label className="grid gap-1">
            <span className="field-label">Name</span>
            <input
              className="field-input"
              value={name}
              maxLength={160}
              autoFocus
              onChange={(event) => setName(event.target.value)}
              onKeyDown={(event) => {
                if (event.key === "Enter") {
                  event.preventDefault();
                  save();
                }
              }}
            />
          </label>
        ) : null}
        {error && edit ? (
          <p role="alert" className="mt-2 text-sm text-red-700">
            {error}
          </p>
        ) : null}
      </FieldDialog>
    </section>
  );
}
