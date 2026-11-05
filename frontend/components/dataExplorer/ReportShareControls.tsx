"use client";

import { Check, Copy, Eye, Link2, Loader2 } from "lucide-react";
import { useCallback, useEffect, useState } from "react";

import { ActionGroup } from "@/components/ui/ActionGroup";
import { SearchableSelect } from "@/components/ui/SearchableSelect";
import { ApiError } from "@/lib/api";
import type { ReportOptions } from "@/lib/dataExplorer/contract";
import {
  createShare,
  daysLabel,
  deviceOf,
  expiryLine,
  fetchShareViews,
  mergeShareViews,
  filtersOf,
  formatWhen,
  listShares,
  revokeShare,
  SHARE_DAYS,
  SHARE_DAYS_DEFAULT,
  viewerLabel,
  viewsLabel,
  type ReportShare,
  type ShareViews
} from "@/lib/dataExplorer/reportShare";
import type { QueryParams } from "@/lib/dataExplorer/wire";

const DAY_OPTIONS = SHARE_DAYS.map((days) => ({ value: String(days), label: daysLabel(days) }));

function message(error: unknown, fallback: string): string {
  if (!(error instanceof ApiError)) return fallback;
  const detail = error.payload && typeof error.payload === "object" ? (error.payload as { detail?: unknown }).detail : undefined;
  return detail !== undefined && error.message.trim() ? error.message : fallback;
}

function ViewsList({ shareId }: { shareId: string }) {
  const [views, setViews] = useState<ShareViews | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loadingMore, setLoadingMore] = useState(false);

  async function more() {
    if (!views) return;
    setLoadingMore(true);
    try {
      const next = await fetchShareViews(shareId, views.items.length);
      setViews((current) => (current ? mergeShareViews(current, next) : current));
    } catch (err) {
      setError(message(err, "Couldn't load who opened it."));
    } finally {
      setLoadingMore(false);
    }
  }

  useEffect(() => {
    let live = true;
    fetchShareViews(shareId)
      .then((answer) => {
        if (live) setViews(answer);
      })
      .catch((err) => {
        if (live) setError(message(err, "Couldn't load who opened it."));
      });
    return () => {
      live = false;
    };
  }, [shareId]);

  if (error) return <p className="text-xs text-error-600">{error}</p>;
  if (!views) return <p className="text-xs text-ink-500">Loading…</p>;
  if (!views.items.length) return <p className="text-xs text-ink-500">Nobody has opened it yet.</p>;
  return (
    <div className="grid gap-1">
      <ul className="grid max-h-56 gap-1 overflow-y-auto">
        {views.items.map((view) => (
          <li key={view.id} className="flex flex-wrap items-baseline justify-between gap-x-3 rounded-sm bg-surface-50 px-2.5 py-1.5 text-xs">
            <span className="font-medium text-ink-900">{viewerLabel(view)}</span>
            <span className="text-ink-500">
              {deviceOf(view.userAgent)} · {formatWhen(view.openedAt)}
            </span>
          </li>
        ))}
      </ul>
      {views.truncated ? (
        <div className="flex flex-wrap items-center justify-between gap-2">
          <p className="text-xs text-ink-500">
            {views.items.length.toLocaleString("en-IN")} of {views.total.toLocaleString("en-IN")}
          </p>
          <button type="button" className="field-button-secondary min-h-9 px-3 text-xs" data-testid="share-views-more" disabled={loadingMore} onClick={() => void more()}>
            {loadingMore ? "Loading…" : "Show older"}
          </button>
        </div>
      ) : null}
    </div>
  );
}

function ShareRow({ share, onRevoked }: { share: ReportShare; onRevoked: (id: string) => void }) {
  const [copied, setCopied] = useState(false);
  const [showViews, setShowViews] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const active = share.state === "active";

  async function copy() {
    try {
      await navigator.clipboard.writeText(share.url);
      setCopied(true);
      window.setTimeout(() => setCopied(false), 2000);
    } catch {
      setError("Couldn't copy. Select the link and copy it.");
    }
  }

  async function revoke() {
    setBusy(true);
    setError(null);
    try {
      await revokeShare(share.id);
      setConfirming(false);
      onRevoked(share.id);
    } catch (err) {
      setError(message(err, "Couldn't turn the link off. Try again."));
    } finally {
      setBusy(false);
    }
  }

  return (
    <li className={`grid gap-2 rounded-md border p-3 ${active ? "border-line-200 bg-card" : "border-line-200 bg-surface-50"}`}>
      <div className="flex flex-wrap items-baseline justify-between gap-x-3 gap-y-1">
        <span className="text-sm font-medium text-ink-900">{expiryLine(share.state, share.expiresAt)}</span>
        <span className="text-xs text-ink-500">
          {viewsLabel(share.views)} · made {formatWhen(share.createdAt)}
        </span>
      </div>
      {active ? (
        <input
          readOnly
          value={share.url}
          aria-label="Live link"
          onFocus={(event) => event.currentTarget.select()}
          className="field-input py-1.5 font-mono text-xs"
        />
      ) : null}
      {confirming ? (
        <div className="grid gap-2">
          <p className="text-xs text-ink-700">Anyone with the link loses access.</p>
          <ActionGroup align="end">
            <button type="button" className="field-button-secondary" disabled={busy} onClick={() => setConfirming(false)}>
              Keep it
            </button>
            <button type="button" className="field-danger inline-flex min-h-10 items-center justify-center gap-2 rounded-md px-4 py-2 text-sm font-medium" data-danger disabled={busy} onClick={() => void revoke()}>
              {busy ? <Loader2 className="h-4 w-4 animate-spin" aria-hidden /> : null}
              Turn off
            </button>
          </ActionGroup>
        </div>
      ) : (
        <ActionGroup align="end" inline>
          {active ? (
            <button type="button" className="field-button-secondary" onClick={() => void copy()}>
              {copied ? <Check className="h-4 w-4" aria-hidden /> : <Copy className="h-4 w-4" aria-hidden />}
              {copied ? "Copied" : "Copy link"}
            </button>
          ) : null}
          <button type="button" className="field-button-secondary" aria-expanded={showViews} onClick={() => setShowViews((v) => !v)}>
            <Eye className="h-4 w-4" aria-hidden />
            {showViews ? "Hide opens" : "Who opened it"}
          </button>
          {active ? (
            <button type="button" className="field-danger inline-flex min-h-10 items-center justify-center gap-2 rounded-md px-4 py-2 text-sm font-medium" data-danger onClick={() => setConfirming(true)}>
              Turn off
            </button>
          ) : null}
        </ActionGroup>
      )}
      {error ? <p className="text-xs text-error-600" role="alert">{error}</p> : null}
      {showViews ? <ViewsList shareId={share.id} /> : null}
    </li>
  );
}

export function ReportShareControls({ path, options, extra }: { path: string; options: ReportOptions | Record<string, unknown>; extra?: QueryParams }) {
  const [days, setDays] = useState(String(SHARE_DAYS_DEFAULT));
  const [shares, setShares] = useState<ReportShare[] | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      const items = await listShares(path);
      setShares(items);
      setLoadError(null);
    } catch (err) {
      setShares((current) => current ?? []);
      setLoadError(message(err, "Couldn't load your links."));
    }
  }, [path]);

  useEffect(() => {
    let live = true;
    listShares(path)
      .then((items) => {
        if (live) {
          setShares(items);
          setLoadError(null);
        }
      })
      .catch((err) => {
        if (live) {
          setShares((current) => current ?? []);
          setLoadError(message(err, "Couldn't load your links."));
        }
      });
    return () => {
      live = false;
    };
  }, [path]);

  async function create() {
    setBusy(true);
    setError(null);
    try {
      const made = await createShare({ path, options: { ...options }, filters: filtersOf(extra), expiresInDays: Number(days) });
      setShares((current) => [made, ...(current ?? []).filter((s) => s.id !== made.id)]);
    } catch (err) {
      setError(message(err, "Couldn't make the link. Try again."));
    } finally {
      setBusy(false);
    }
  }

  function revoked(id: string) {
    setShares((current) => (current ?? []).map((s) => (s.id === id ? { ...s, state: "revoked", revokedAt: new Date().toISOString() } : s)));
    void load();
  }

  const active = (shares ?? []).filter((s) => s.state === "active");
  const ended = (shares ?? []).filter((s) => s.state !== "active");

  return (
    <section className="grid gap-3" aria-labelledby="report-share-heading">
      <div className="grid gap-1">
        <h3 id="report-share-heading" className="flex items-center gap-2 text-sm font-semibold text-ink-900">
          <Link2 className="h-4 w-4 text-purple-700" aria-hidden />
          Live link
        </h3>
        <p className="text-xs text-ink-500">A read-only page with the cover and charts. Anyone with the link can open it.</p>
      </div>
      <div className="flex flex-wrap items-end gap-2">
        <div className="grid min-w-0 flex-1 gap-1 sm:max-w-48">
          <span className="field-label">Expires after</span>
          <SearchableSelect value={days} onChange={setDays} options={DAY_OPTIONS} ariaLabel="Expires after" advanceOnSelect={false} />
        </div>
        <button type="button" className="field-button" disabled={busy} onClick={() => void create()}>
          {busy ? <Loader2 className="h-4 w-4 animate-spin" aria-hidden /> : <Link2 className="h-4 w-4" aria-hidden />}
          {busy ? "Making link…" : "Make link"}
        </button>
      </div>
      {error ? <p className="text-xs text-error-600" role="alert">{error}</p> : null}
      {loadError ? <p className="text-xs text-error-600">{loadError}</p> : null}
      {shares === null ? <p className="text-xs text-ink-500">Loading your links…</p> : null}
      {active.length ? (
        <ul className="grid gap-2">
          {active.map((share) => (
            <ShareRow key={share.id} share={share} onRevoked={revoked} />
          ))}
        </ul>
      ) : null}
      {ended.length ? (
        <details className="text-xs">
          <summary className="cursor-pointer text-ink-500">Ended links ({ended.length})</summary>
          <ul className="mt-2 grid gap-2">
            {ended.map((share) => (
              <ShareRow key={share.id} share={share} onRevoked={revoked} />
            ))}
          </ul>
        </details>
      ) : null}
    </section>
  );
}
