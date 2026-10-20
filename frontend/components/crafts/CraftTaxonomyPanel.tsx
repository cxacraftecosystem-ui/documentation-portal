"use client";

/**
 * The Categories tab of `/crafts`: the nine categories and their sub-categories, each with Rename,
 * Move (a sub-category) and Delete, plus Add category / Add sub-category and up/down reordering.
 * Web only, in both portals (REQUIREMENTS_2026-10-20 §2.6). Content admins write; deletes need
 * admin view. Every write bumps the catalogue, so the pickers refresh through `onChanged`.
 */

import { ArrowDown, ArrowUp } from "lucide-react";
import { useCallback, useEffect, useState } from "react";

import { deleteConfirm, useConfirm } from "@/components/dialogs/ConfirmDialog";
import { RowActions, rowAction } from "@/components/RowActions";
import { ButtonRow } from "@/components/ui/ButtonRow";
import { Dropdown } from "@/components/ui/Dropdown";
import { apiFetch } from "@/lib/api";
import { invalidateCraftCatalogue, type CraftCatalogueCategory, type CraftCatalogueSubCategory } from "@/lib/craftCatalogue";
import { countNoun } from "@/lib/format";

type Editing =
  | { kind: "rename-category"; id: string; name: string }
  | { kind: "rename-sub"; id: string; name: string }
  | { kind: "move-sub"; id: string; categoryId: string }
  | { kind: "add-category"; name: string }
  | { kind: "add-sub"; categoryId: string; name: string };

function categoriesOf(body: unknown): CraftCatalogueCategory[] {
  const list = Array.isArray(body) ? body : (body as { categories?: unknown } | null)?.categories;
  return Array.isArray(list) ? (list as CraftCatalogueCategory[]) : [];
}

const bySort = <T extends { sortOrder: number }>(a: T, b: T) => a.sortOrder - b.sortOrder;

export function CraftTaxonomyPanel({
  canManage,
  canDelete,
  onChanged
}: {
  /** Content admins add, rename, move and reorder. */
  canManage: boolean;
  /** Content admins in admin view also delete. */
  canDelete: boolean;
  onChanged: () => void;
}) {
  const confirm = useConfirm();
  const [categories, setCategories] = useState<CraftCatalogueCategory[] | null>(null);
  const [editing, setEditing] = useState<Editing | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      const body = await apiFetch<unknown>("/craft-categories");
      setCategories(
        categoriesOf(body)
          .map((category) => ({ ...category, subCategories: [...category.subCategories].sort(bySort) }))
          .sort(bySort)
      );
      setError(null);
    } catch (err) {
      setCategories((current) => current ?? []);
      setError(err instanceof Error ? err.message : "Couldn't load the categories.");
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  async function write(action: () => Promise<unknown>) {
    setBusy(true);
    try {
      await action();
      invalidateCraftCatalogue();
      setEditing(null);
      setError(null);
      onChanged();
      await load();
    } catch (err) {
      setError(err instanceof Error ? err.message : "That didn't save. Try again.");
    } finally {
      setBusy(false);
    }
  }

  const send = (path: string, method: string, body?: unknown) =>
    apiFetch(path, { method, body: body === undefined ? undefined : JSON.stringify(body) });

  function swap<T extends { id: string; sortOrder: number }>(rows: T[], index: number, delta: number, base: string) {
    const other = rows[index + delta];
    const row = rows[index];
    if (!other || !row) return;
    void write(async () => {
      await send(`${base}/${row.id}`, "PATCH", { sortOrder: other.sortOrder });
      await send(`${base}/${other.id}`, "PATCH", { sortOrder: row.sortOrder });
    });
  }

  async function removeCategory(category: CraftCatalogueCategory) {
    const ok = await confirm(deleteConfirm(`Delete “${category.name}”?`, "This can’t be undone."));
    if (ok) void write(() => send(`/craft-categories/${category.id}`, "DELETE"));
  }

  async function removeSub(sub: CraftCatalogueSubCategory) {
    const ok = await confirm(deleteConfirm(`Delete “${sub.name}”?`, "This can’t be undone."));
    if (ok) void write(() => send(`/craft-sub-categories/${sub.id}`, "DELETE"));
  }

  function nameForm(value: string, onValue: (next: string) => void, onSave: () => void, label: string) {
    return (
      <form
        className="grid gap-2 sm:flex sm:items-center"
        onSubmit={(event) => {
          event.preventDefault();
          if (value.trim()) onSave();
        }}
      >
        <input
          className="field-input sm:max-w-sm"
          aria-label={label}
          autoFocus
          required
          value={value}
          onChange={(event) => onValue(event.target.value)}
        />
        <ButtonRow align="start">
          <button className="field-button" disabled={busy || !value.trim()}>
            {busy ? "Saving…" : "Save"}
          </button>
          <button type="button" className="field-button-secondary" onClick={() => setEditing(null)}>
            Cancel
          </button>
        </ButtonRow>
      </form>
    );
  }

  if (!categories) return <div className="panel p-4 text-sm text-ink-500">Loading…</div>;

  return (
    <section className="grid gap-4" aria-label="Categories">
      {error ? (
        <div role="alert" className="rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">
          {error}
        </div>
      ) : null}
      {canManage ? (
        <div className="panel p-4">
          {editing?.kind === "add-category" ? (
            nameForm(
              editing.name,
              (name) => setEditing({ kind: "add-category", name }),
              () => void write(() => send("/craft-categories", "POST", { name: editing.name.trim() })),
              "New category name"
            )
          ) : editing?.kind === "add-sub" ? (
            <div className="grid gap-2">
              <Dropdown
                ariaLabel="Category"
                value={editing.categoryId}
                advanceOnSelect={false}
                onChange={(categoryId) => setEditing({ ...editing, categoryId })}
                options={categories.map((category) => ({ value: category.id, label: category.name }))}
                placeholder="Choose a category"
              />
              {nameForm(
                editing.name,
                (name) => setEditing({ ...editing, name }),
                () =>
                  void write(() =>
                    send("/craft-sub-categories", "POST", { name: editing.name.trim(), categoryId: editing.categoryId })
                  ),
                "New sub-category name"
              )}
            </div>
          ) : (
            <ButtonRow>
              <button type="button" className="field-button" onClick={() => setEditing({ kind: "add-category", name: "" })}>
                Add category
              </button>
              <button
                type="button"
                className="field-button-secondary"
                disabled={categories.length === 0}
                onClick={() => setEditing({ kind: "add-sub", categoryId: categories[0]?.id ?? "", name: "" })}
              >
                Add sub-category
              </button>
            </ButtonRow>
          )}
        </div>
      ) : null}
      {categories.length === 0 ? <div className="panel p-4 text-sm text-ink-500">No categories yet.</div> : null}
      <ol className="grid gap-3">
        {categories.map((category, categoryIndex) => {
          const total = category.subCategories.reduce((sum, sub) => sum + (sub.craftCount ?? 0), 0);
          return (
            <li key={category.id} className="panel p-4">
              <div className="flex flex-col gap-2 sm:flex-row sm:items-center sm:justify-between">
                {editing?.kind === "rename-category" && editing.id === category.id ? (
                  nameForm(
                    editing.name,
                    (name) => setEditing({ ...editing, name }),
                    () => void write(() => send(`/craft-categories/${category.id}`, "PATCH", { name: editing.name.trim() })),
                    "Category name"
                  )
                ) : (
                  <div className="min-w-0">
                    <h3 className="font-display text-base font-semibold text-ink-900">{category.name}</h3>
                    <p className="text-xs text-ink-500">
                      {countNoun(category.subCategories.length, "sub-category", "sub-categories")} · {countNoun(total, "craft")}
                    </p>
                  </div>
                )}
                {canManage && editing?.kind !== "rename-category" ? (
                  <RowActions
                    destructive={
                      canDelete ? (
                        <button type="button" className={rowAction("danger")} data-danger onClick={() => void removeCategory(category)}>
                          Delete
                        </button>
                      ) : undefined
                    }
                  >
                    <button
                      type="button"
                      className={rowAction("neutral")}
                      aria-label={`Move ${category.name} up`}
                      disabled={busy || categoryIndex === 0}
                      onClick={() => swap(categories, categoryIndex, -1, "/craft-categories")}
                    >
                      <ArrowUp className="h-3.5 w-3.5" aria-hidden />
                    </button>
                    <button
                      type="button"
                      className={rowAction("neutral")}
                      aria-label={`Move ${category.name} down`}
                      disabled={busy || categoryIndex === categories.length - 1}
                      onClick={() => swap(categories, categoryIndex, 1, "/craft-categories")}
                    >
                      <ArrowDown className="h-3.5 w-3.5" aria-hidden />
                    </button>
                    <button
                      type="button"
                      className={rowAction("edit")}
                      onClick={() => setEditing({ kind: "rename-category", id: category.id, name: category.name })}
                    >
                      Rename
                    </button>
                  </RowActions>
                ) : null}
              </div>
              <ol className="mt-3 divide-y divide-line-200 rounded-md border border-line-200">
                {category.subCategories.map((sub, subIndex) => (
                  <li key={sub.id} className="flex flex-col gap-2 px-3 py-2.5 sm:flex-row sm:items-center sm:justify-between">
                    {editing?.kind === "rename-sub" && editing.id === sub.id ? (
                      nameForm(
                        editing.name,
                        (name) => setEditing({ ...editing, name }),
                        () => void write(() => send(`/craft-sub-categories/${sub.id}`, "PATCH", { name: editing.name.trim() })),
                        "Sub-category name"
                      )
                    ) : editing?.kind === "move-sub" && editing.id === sub.id ? (
                      <div className="grid gap-2 sm:flex sm:items-center">
                        <Dropdown
                          ariaLabel={`Move ${sub.name} to`}
                          value={editing.categoryId}
                          advanceOnSelect={false}
                          onChange={(categoryId) => setEditing({ ...editing, categoryId })}
                          options={categories.map((row) => ({ value: row.id, label: row.name }))}
                        />
                        <ButtonRow align="start">
                          <button
                            type="button"
                            className="field-button"
                            disabled={busy || editing.categoryId === category.id}
                            onClick={() =>
                              void write(() => send(`/craft-sub-categories/${sub.id}`, "PATCH", { categoryId: editing.categoryId }))
                            }
                          >
                            Move
                          </button>
                          <button type="button" className="field-button-secondary" onClick={() => setEditing(null)}>
                            Cancel
                          </button>
                        </ButtonRow>
                      </div>
                    ) : (
                      <span className="min-w-0 text-sm text-ink-900">
                        {sub.name} <span className="text-xs text-ink-500">· {countNoun(sub.craftCount ?? 0, "craft")}</span>
                      </span>
                    )}
                    {canManage && !(editing && "id" in editing && editing.id === sub.id) ? (
                      <RowActions
                        destructive={
                          canDelete ? (
                            <button type="button" className={rowAction("danger")} data-danger onClick={() => void removeSub(sub)}>
                              Delete
                            </button>
                          ) : undefined
                        }
                      >
                        <button
                          type="button"
                          className={rowAction("neutral")}
                          aria-label={`Move ${sub.name} up`}
                          disabled={busy || subIndex === 0}
                          onClick={() => swap(category.subCategories, subIndex, -1, "/craft-sub-categories")}
                        >
                          <ArrowUp className="h-3.5 w-3.5" aria-hidden />
                        </button>
                        <button
                          type="button"
                          className={rowAction("neutral")}
                          aria-label={`Move ${sub.name} down`}
                          disabled={busy || subIndex === category.subCategories.length - 1}
                          onClick={() => swap(category.subCategories, subIndex, 1, "/craft-sub-categories")}
                        >
                          <ArrowDown className="h-3.5 w-3.5" aria-hidden />
                        </button>
                        <button
                          type="button"
                          className={rowAction("edit")}
                          onClick={() => setEditing({ kind: "rename-sub", id: sub.id, name: sub.name })}
                        >
                          Rename
                        </button>
                        <button
                          type="button"
                          className={rowAction("neutral")}
                          onClick={() => setEditing({ kind: "move-sub", id: sub.id, categoryId: category.id })}
                        >
                          Move
                        </button>
                      </RowActions>
                    ) : null}
                  </li>
                ))}
                {category.subCategories.length === 0 ? <li className="px-3 py-2.5 text-sm text-ink-500">No sub-categories.</li> : null}
              </ol>
            </li>
          );
        })}
      </ol>
    </section>
  );
}
