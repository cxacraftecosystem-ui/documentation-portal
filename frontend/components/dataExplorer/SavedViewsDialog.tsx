"use client";

import { Bookmark, CheckCircle2, Link2, Trash2 } from "lucide-react";
import { useCallback, useEffect, useState } from "react";

import { Problem, Skeleton } from "@/components/dataExplorer/explorerUi";
import { FieldDialog } from "@/components/dialogs/FieldDialog";
import { Toggle } from "@/components/ui/Toggle";
import { createSavedView, deleteSavedView, fetchSavedViews, updateSavedView } from "@/lib/dataExplorer/api";
import type { SavedView, SavedViewState } from "@/lib/dataExplorer/contract";
import { viewLink, viewSummary } from "@/lib/dataExplorer/explorerView";

export function SavedViewsDialog({
  open,
  onClose,
  current,
  folderName,
  onOpenView
}: {
  open: boolean;
  onClose: () => void;
  current: { path: string; state: SavedViewState };
  folderName: string;
  onOpenView: (view: SavedView) => void;
}) {
  const [views, setViews] = useState<{ mine: SavedView[]; shared: SavedView[] } | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [name, setName] = useState("");
  const [shared, setShared] = useState(false);
  const [busy, setBusy] = useState(false);
  const [copied, setCopied] = useState<string | null>(null);

  const load = useCallback(async () => {
    setError(null);
    try {
      setViews((await fetchSavedViews()) ?? { mine: [], shared: [] });
    } catch (err) {
      setError(err instanceof Error && err.message ? err.message : "Saved views didn't load.");
    }
  }, []);

  useEffect(() => {
    if (!open) return;
    setName(folderName);
    setCopied(null);
    void load();
  }, [open, folderName, load]);

  const copy = async (id: string) => {
    try {
      await navigator.clipboard.writeText(viewLink(id));
      setCopied(id);
    } catch {
      setError("The link couldn't be copied. Copy it from the address bar instead.");
    }
  };

  const save = async () => {
    if (!name.trim()) return;
    setBusy(true);
    setError(null);
    try {
      const made = await createSavedView({ name: name.trim(), path: current.path, state: current.state, shared });
      await copy(made.id);
      await load();
    } catch (err) {
      setError(err instanceof Error && err.message ? err.message : "The view wasn't saved.");
    } finally {
      setBusy(false);
    }
  };

  const remove = async (id: string) => {
    setBusy(true);
    try {
      await deleteSavedView(id);
      await load();
    } catch (err) {
      setError(err instanceof Error && err.message ? err.message : "The view wasn't removed.");
    } finally {
      setBusy(false);
    }
  };

  const share = async (view: SavedView, next: boolean) => {
    try {
      await updateSavedView(view.id, { shared: next });
      await load();
    } catch (err) {
      setError(err instanceof Error && err.message ? err.message : "The view wasn't updated.");
    }
  };

  const rows = views ? [...views.mine, ...views.shared.filter((view) => !view.mine)] : [];

  return (
    <FieldDialog
      open={open}
      onClose={onClose}
      title="Saved views"
      description="A view keeps the folder, filters, search and tab. Anyone with access lands on the same screen."
      icon={<Bookmark className="h-5 w-5" aria-hidden />}
      footer={
        <button type="button" className="field-button-secondary" onClick={onClose}>
          Close
        </button>
      }
    >
      <div className="grid gap-4">
        <div className="grid gap-2 rounded-md border border-line-200 bg-surface-50 p-3">
          <label htmlFor="view-name" className="field-label">
            Save this screen as
          </label>
          <input id="view-name" className="field-input" value={name} onChange={(event) => setName(event.target.value)} maxLength={120} />
          <p className="text-xs text-ink-500">{viewSummary(current, folderName)}</p>
          <Toggle label="Share with everyone who can see this folder" checked={shared} onChange={setShared} />
          <button type="button" className="field-button justify-self-start" disabled={busy || !name.trim()} onClick={() => void save()}>
            <Link2 className="h-4 w-4" aria-hidden />
            Save and copy link
          </button>
        </div>
        {error ? <Problem onRetry={() => void load()}>{error}</Problem> : null}
        {!views ? (
          <div className="grid gap-2">
            <Skeleton className="h-12" />
            <Skeleton className="h-12" />
          </div>
        ) : rows.length === 0 ? (
          <p className="text-sm text-ink-500">No saved views yet.</p>
        ) : (
          <ul className="grid gap-2" aria-label="Saved views">
            {rows.map((view) => (
              <li key={view.id} className="grid gap-2 rounded-md border border-line-200 p-3">
                <div className="flex flex-wrap items-start gap-2">
                  <button type="button" className="min-w-0 flex-1 text-left" onClick={() => onOpenView(view)}>
                    <span className="block truncate text-sm font-semibold text-ink-900 hover:text-purple-700">{view.name}</span>
                    <span className="block text-xs text-ink-500">{viewSummary(view)}</span>
                  </button>
                  <span className="rounded-full bg-surface-50 px-2 py-0.5 text-xs text-ink-500">{view.mine ? (view.shared ? "Yours · shared" : "Yours") : `Shared${view.ownerName ? ` by ${view.ownerName}` : ""}`}</span>
                </div>
                <div className="flex flex-wrap items-center gap-2">
                  <button type="button" className="field-button-secondary min-h-9 px-3 text-xs" onClick={() => void copy(view.id)}>
                    {copied === view.id ? <CheckCircle2 className="h-3.5 w-3.5" aria-hidden /> : <Link2 className="h-3.5 w-3.5" aria-hidden />}
                    {copied === view.id ? "Link copied" : "Copy link"}
                  </button>
                  {view.mine ? (
                    <>
                      <button type="button" className="field-button-secondary min-h-9 px-3 text-xs" onClick={() => void share(view, !view.shared)}>
                        {view.shared ? "Stop sharing" : "Share"}
                      </button>
                      <button type="button" className="field-danger inline-flex min-h-9 items-center gap-1.5 rounded-md px-3 text-xs" data-danger="" disabled={busy} onClick={() => void remove(view.id)}>
                        <Trash2 className="h-3.5 w-3.5" aria-hidden />
                        Remove
                      </button>
                    </>
                  ) : null}
                </div>
              </li>
            ))}
          </ul>
        )}
      </div>
    </FieldDialog>
  );
}
