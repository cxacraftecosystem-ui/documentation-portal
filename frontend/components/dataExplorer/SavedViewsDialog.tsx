"use client";

import { Check } from "lucide-react";
import { useCallback, useEffect, useState } from "react";

import { Problem, Skeleton } from "@/components/dataExplorer/explorerUi";
import { ReportScheduleControls } from "@/components/dataExplorer/ReportScheduleControls";
import { FieldDialog } from "@/components/dialogs/FieldDialog";
import { Toggle } from "@/components/ui/Toggle";
import { createSavedView, deleteSavedView, fetchSavedViews, updateSavedView } from "@/lib/dataExplorer/api";
import type { SavedView, SavedViewState } from "@/lib/dataExplorer/contract";
import { viewLink, viewSummary } from "@/lib/dataExplorer/explorerView";

const LINK = "min-h-8 rounded-sm px-2 text-xs font-semibold text-purple-700 hover:bg-white/60 disabled:opacity-60 dark:text-purple-300 pointer-coarse:min-h-11";

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
  const [selected, setSelected] = useState<string | null>(null);

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
    setSelected(null);
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
      setSelected(made.id);
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
  const chosen = rows.find((view) => view.id === selected) ?? null;

  return (
    <FieldDialog
      open={open}
      onClose={onClose}
      title={<span className="font-sans text-base font-bold tracking-normal">Saved views</span>}
      description={<span className="text-xs leading-[18px] text-ink-500">A view keeps the folder, filters, search and tab. Share the link and others land on exactly the same screen, if they have access.</span>}
      className="max-w-[420px]! rounded-[20px]! border-0! p-[18px]! shadow-[0_24px_64px_rgba(30,27,46,.25)]!"
    >
      <div className="mt-3 grid gap-3">
        {error ? <Problem onRetry={() => void load()}>{error}</Problem> : null}
        {!views ? (
          <div className="grid gap-2">
            <Skeleton className="h-[46px]" />
            <Skeleton className="h-[46px]" />
          </div>
        ) : rows.length === 0 ? (
          <p className="text-sm text-ink-500">No saved views yet.</p>
        ) : (
          <ul className="grid gap-3" aria-label="Saved views">
            {rows.map((view) => {
              const on = view.id === selected;
              return (
                <li key={view.id} className={`grid gap-1.5 rounded-md border px-3 py-1.5 ${on ? "border-[#d6b6fb] bg-purple-100 dark:border-purple-800 dark:bg-purple-950/60" : "border-line-200 bg-card"}`}>
                  <div className="flex items-center gap-2">
                    <button type="button" className="min-w-0 flex-1 text-left" aria-pressed={on} onClick={() => setSelected(view.id)} onDoubleClick={() => onOpenView(view)}>
                      <span className="block truncate text-sm font-bold text-ink-900">{view.name}</span>
                      <span className="block truncate text-xs text-ink-500">{viewSummary(view)}</span>
                    </button>
                    <span className="shrink-0 text-xs text-ink-500">{view.mine ? (view.shared ? "Yours · shared" : "Yours") : `Shared${view.ownerName ? ` by ${view.ownerName}` : ""}`}</span>
                  </div>
                  {on ? (
                    <div className="flex flex-wrap gap-1 pb-1">
                      <button type="button" className={LINK} onClick={() => onOpenView(view)}>
                        Open
                      </button>
                      {view.mine ? (
                        <>
                          <button type="button" className={LINK} onClick={() => void share(view, !view.shared)}>
                            {view.shared ? "Stop sharing" : "Share"}
                          </button>
                          <button type="button" className={`${LINK} text-red-700 dark:text-red-300`} data-danger="" disabled={busy} onClick={() => void remove(view.id)}>
                            Remove
                          </button>
                        </>
                      ) : null}
                    </div>
                  ) : null}
                </li>
              );
            })}
          </ul>
        )}
        <div className="grid gap-1.5">
          <label htmlFor="view-name" className="text-xs text-ink-500">
            Save this screen as
          </label>
          <input id="view-name" className="min-h-10 rounded-md border border-line-200 bg-card px-3 text-sm text-ink-900 outline-hidden focus:border-purple-600" value={name} onChange={(event) => setName(event.target.value)} maxLength={120} />
          <Toggle label="Share with everyone who can see this folder" checked={shared} onChange={setShared} />
        </div>
        <div className="flex gap-2">
          <button type="button" className="min-h-11 flex-1 rounded-md border border-line-200 bg-card text-sm text-ink-900 hover:border-purple-300 disabled:opacity-60" disabled={busy || !name.trim()} onClick={() => void save()}>
            Save current view
          </button>
          <button type="button" className="min-h-11 flex-1 rounded-md bg-purple-700 text-sm font-semibold text-white hover:bg-purple-800 disabled:opacity-60 dark:bg-purple-300 dark:text-purple-950" disabled={!chosen} onClick={() => chosen && void copy(chosen.id)}>
            Copy link
          </button>
        </div>
        {copied ? (
          <p role="status" className="flex items-center gap-1.5 rounded-[10px] bg-success-100 px-2.5 py-2 text-xs text-green-900 dark:bg-[#123b25] dark:text-green-100">
            <Check className="h-3.5 w-3.5 shrink-0" aria-hidden />
            Link copied
          </p>
        ) : null}
        {views ? <ReportScheduleControls savedViews={rows} /> : null}
      </div>
    </FieldDialog>
  );
}
