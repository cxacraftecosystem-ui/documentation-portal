"use client";

import { ChevronUp, Expand, Play } from "lucide-react";
import { lazy, Suspense, useCallback, useEffect, useState, type ReactNode } from "react";

import { PreviewDialog, PreviewInline } from "@/components/dataExplorer/PreviewDialog";
import { queuedBlob } from "@/components/questionnaire/useSessionQueue";
import type { TreeEntry } from "@/lib/dataExplorer/contract";
import type { DownloadRequest } from "@/lib/dataExplorer/downloads";
import { qs } from "@/lib/questionnaire/qs";
import { fullEntry, localTakeEntry, nextOpen, regionId, takeEntry, type OpenTake, type TakeItem } from "@/lib/questionnaire/takeInPlace";

export type { TakeItem };

const TakeDownloads = lazy(() => import("@/components/questionnaire/TakeDownloads"));

export type TakePlayer = {
  isOpen: (slot: string, key: string) => boolean;
  toggle: (slot: string, key: string) => void;
  show: (slot: string, key: string) => void;
  close: () => void;
  showFull: (entry: TreeEntry) => void;
  onDownload: (request: DownloadRequest) => void;
  overlay: ReactNode;
};

export function useTakePlayer(collapse: unknown): TakePlayer {
  const [open, setOpen] = useState<OpenTake | null>(null);
  const [full, setFull] = useState<TreeEntry | null>(null);
  const [held, setHeld] = useState(collapse);
  const [requests, setRequests] = useState<DownloadRequest[]>([]);
  if (held !== collapse) {
    setHeld(collapse);
    if (collapse) setOpen(null);
  }
  const isOpen = useCallback((slot: string, key: string) => Boolean(open && open.slot === slot && open.key === key), [open]);
  const toggle = useCallback((slot: string, key: string) => setOpen((current) => nextOpen(current, slot, key)), []);
  const show = useCallback((slot: string, key: string) => setOpen({ slot, key }), []);
  const close = useCallback(() => setOpen(null), []);
  const showFull = useCallback((entry: TreeEntry) => {
    setOpen(null);
    setFull(entry);
  }, []);
  const onDownload = useCallback((request: DownloadRequest) => setRequests((list) => [...list, request]), []);
  const overlay = (
    <>
      {requests.length ? (
        <Suspense fallback={null}>
          <TakeDownloads requests={requests} />
        </Suspense>
      ) : null}
      <PreviewDialog entry={full} siblings={full ? [full] : []} onNavigate={() => undefined} onClose={() => setFull(null)} recordHref={null} onDownload={onDownload} />
    </>
  );
  return { isOpen, toggle, show, close, showFull, onDownload, overlay };
}

function useLocalUrl(blobKey: string | null | undefined): string | null | undefined {
  const [found, setFound] = useState<{ key: string; url: string | null } | null>(null);
  useEffect(() => {
    if (!blobKey) return;
    let cancelled = false;
    let made: string | null = null;
    queuedBlob(blobKey)
      .then((blob) => {
        if (cancelled) return;
        made = blob ? URL.createObjectURL(blob) : null;
        setFound({ key: blobKey, url: made });
      })
      .catch(() => {
        if (!cancelled) setFound({ key: blobKey, url: null });
      });
    return () => {
      cancelled = true;
      if (made) URL.revokeObjectURL(made);
    };
  }, [blobKey]);
  if (!blobKey) return null;
  return found && found.key === blobKey ? found.url : undefined;
}

function TakePreview({ item, player }: { item: TakeItem; player: TakePlayer }) {
  const local = useLocalUrl(item.recording ? null : item.blobKey);
  if (item.recording) return <PreviewInline entry={takeEntry(item.recording)} onDownload={player.onDownload} />;
  if (local === undefined) return <p className="p-4 text-sm text-ink-500">{qs("loading")}</p>;
  if (!local) return <p className="p-4 text-sm text-ink-500">{qs("transcriptAfterUpload")}</p>;
  return <PreviewInline entry={localTakeEntry(item, local)} onDownload={player.onDownload} emptyText={qs("transcriptAfterUpload")} />;
}

export function TakeRow({ player, slot, item, lead, playLabel }: { player: TakePlayer; slot: string; item: TakeItem; lead: ReactNode; playLabel?: string }) {
  const open = player.isOpen(slot, item.key);
  const region = regionId(slot, item.key);
  const full = fullEntry(item);
  return (
    <div className="flex flex-wrap items-center gap-x-2 gap-y-1" data-take-row-in-place={item.key} data-take-open={open ? "" : undefined}>
      <div className="min-w-0">{lead}</div>
      <span className="flex-auto" />
      {open ? (
        <>
          {full ? (
            <button type="button" className="field-button-secondary min-h-11 font-semibold" onClick={() => player.showFull(full)} data-take-full>
              <Expand className="h-4 w-4" aria-hidden />
              {qs("openFullView")}
            </button>
          ) : null}
          <button
            type="button"
            className="grid h-11 w-11 shrink-0 place-items-center rounded-full border border-line-200 bg-card text-ink-700 transition hover:border-purple-300"
            aria-label={qs("collapseTake")}
            aria-expanded
            aria-controls={region}
            onClick={() => player.toggle(slot, item.key)}
            data-take-collapse
          >
            <ChevronUp className="h-4.5 w-4.5" aria-hidden />
          </button>
        </>
      ) : (
        <button
          type="button"
          className="field-button-secondary min-h-11 font-semibold"
          aria-expanded={false}
          aria-controls={region}
          aria-label={playLabel ? `${qs("playTake")} · ${playLabel}` : undefined}
          onClick={() => player.toggle(slot, item.key)}
          data-take-play
        >
          <Play className="h-4 w-4" aria-hidden />
          {qs("playTake")}
        </button>
      )}
    </div>
  );
}

export function TakeRegion({ player, slot, item, className = "" }: { player: TakePlayer; slot: string; item: TakeItem; className?: string }) {
  if (!player.isOpen(slot, item.key)) return null;
  return (
    <div
      id={regionId(slot, item.key)}
      role="region"
      aria-label={item.label}
      className={`min-w-0 overflow-hidden rounded-lg border border-line-200 bg-card ${className}`}
      data-take-region={item.key}
    >
      <TakePreview item={item} player={player} />
    </div>
  );
}

export function TakeInPlace({ player, slot, item, lead, playLabel }: { player: TakePlayer; slot: string; item: TakeItem; lead: ReactNode; playLabel?: string }) {
  return (
    <div className="grid grid-cols-[minmax(0,1fr)] gap-2" data-take-in-place={item.key}>
      <TakeRow player={player} slot={slot} item={item} lead={lead} playLabel={playLabel} />
      <TakeRegion player={player} slot={slot} item={item} />
    </div>
  );
}
