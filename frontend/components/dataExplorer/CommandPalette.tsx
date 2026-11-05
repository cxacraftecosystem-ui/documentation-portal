"use client";

import { CornerDownLeft, Search } from "lucide-react";
import { useEffect, useId, useMemo, useRef, useState, type KeyboardEvent as ReactKeyboardEvent, type ReactNode } from "react";

import { typeWord } from "@/components/dataExplorer/paletteSearch";
import { FieldDialog } from "@/components/dialogs/FieldDialog";
import {
  CHORD_MS,
  highlightRanges,
  PALETTE_LIMIT,
  paletteEntries,
  shortcutAction,
  SHORTCUTS,
  type PaletteAction,
  type PaletteEntry,
  type PaletteHit,
  type ShortcutName
} from "@/lib/dataExplorer/preview";

export type { PaletteAction, PaletteHit, ShortcutName };

const PALETTE_DEBOUNCE_MS = 150;

const TYPING = "input, textarea, select, [contenteditable=''], [contenteditable='true']";
const PRESSABLE = "a, button, summary, [role='button'], [role='treeitem'], [role='tab'], [role='switch'], [role='checkbox'], [role='option'], video, audio";

export function isTypingTarget(target: EventTarget | null): boolean {
  if (!(target instanceof Element)) return false;
  return Boolean(target.closest(TYPING)) || (target instanceof HTMLElement && target.isContentEditable);
}

export function useExplorerShortcuts(handlers: Partial<Record<ShortcutName, () => void>>): void {
  const latest = useRef(handlers);
  useEffect(() => {
    latest.current = handlers;
  });
  useEffect(() => {
    let chordAt = 0;
    const onKey = (event: KeyboardEvent) => {
      if (event.defaultPrevented || event.isComposing) return;
      const typing = isTypingTarget(event.target);
      const inDialog = Boolean(document.querySelector("[data-field-dialog]"));
      const chord = chordAt > 0 && Date.now() - chordAt < CHORD_MS;
      const action = shortcutAction(event, typing || inDialog, chord);
      chordAt = action === "chord" ? Date.now() : 0;
      if (!action || action === "chord") return;
      if (action === "palette" && inDialog && !document.querySelector("[data-command-palette]")) return;
      if (action === "open" && event.target instanceof Element && event.target.closest(PRESSABLE)) return;
      const run = latest.current[action];
      if (!run) return;
      event.preventDefault();
      run();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, []);
}

function Kbd({ children }: { children: ReactNode }) {
  return <kbd className="inline-grid h-[22px] min-w-[22px] place-items-center rounded-[6px] border border-line-200 bg-surface-50 px-1.5 font-sans text-[11px] text-ink-700">{children}</kbd>;
}

function Marked({ text, needle }: { text: string; needle: string }) {
  return (
    <>
      {highlightRanges(text, needle).map((part, index) =>
        part.hit ? (
          <mark key={index} className="rounded-[4px] bg-purple-100 px-0.5 text-purple-800 dark:bg-purple-900 dark:text-purple-100">
            {part.text}
          </mark>
        ) : (
          <span key={index}>{part.text}</span>
        )
      )}
    </>
  );
}

function shortSubtitle(hit: PaletteHit): string {
  const lead = `${typeWord(hit.type)} · `;
  const text = hit.subtitle ?? "";
  return text.startsWith(lead) ? text.slice(lead.length) : text === typeWord(hit.type) ? "" : text;
}

function typeInitial(type: string): string {
  return (type.trim().charAt(0) || "·").toUpperCase();
}

export function CommandPalette({
  open,
  onClose,
  search,
  onOpen,
  actions,
  onSearchEverything
}: {
  open: boolean;
  onClose: () => void;
  search: (q: string, signal: AbortSignal) => Promise<PaletteHit[]>;
  onOpen: (hit: PaletteHit) => void;
  actions: PaletteAction[];
  onSearchEverything: (q: string) => void;
}) {
  const inputRef = useRef<HTMLInputElement | null>(null);
  const listId = useId();
  const [query, setQuery] = useState("");
  const [answer, setAnswer] = useState<{ q: string; hits: PaletteHit[]; error: boolean } | null>(null);
  const [active, setActive] = useState(0);
  const searchRef = useRef(search);
  useEffect(() => {
    searchRef.current = search;
  });

  const needle = query.trim();
  useEffect(() => {
    if (!open || !needle) return;
    const abort = new AbortController();
    const timer = window.setTimeout(() => {
      searchRef
        .current(needle, abort.signal)
        .then((hits) => {
          if (!abort.signal.aborted) setAnswer({ q: needle, hits, error: false });
        })
        .catch(() => {
          if (!abort.signal.aborted) setAnswer({ q: needle, hits: [], error: true });
        });
    }, PALETTE_DEBOUNCE_MS);
    return () => {
      abort.abort();
      window.clearTimeout(timer);
    };
  }, [open, needle]);

  const hits = useMemo(() => (needle && answer ? answer.hits : []), [needle, answer]);
  const searching = Boolean(needle) && answer?.q !== needle;
  const entries = useMemo(() => paletteEntries(hits, actions, needle), [hits, actions, needle]);
  const current = Math.min(active, Math.max(0, entries.length - 1));
  const top = hits[0] ?? null;

  const close = () => {
    setQuery("");
    setAnswer(null);
    setActive(0);
    onClose();
  };

  const run = (entry: PaletteEntry | undefined) => {
    if (!entry) return;
    if (entry.kind === "hit") onOpen(entry.hit);
    else if (entry.kind === "action" && top) entry.action.run(top);
    else if (entry.kind === "everything") onSearchEverything(needle);
    close();
  };

  const onKey = (event: ReactKeyboardEvent<HTMLInputElement>) => {
    if (event.key === "ArrowDown") {
      event.preventDefault();
      setActive(entries.length ? (current + 1) % entries.length : 0);
    } else if (event.key === "ArrowUp") {
      event.preventDefault();
      setActive(entries.length ? (current - 1 + entries.length) % entries.length : 0);
    } else if (event.key === "Enter") {
      event.preventDefault();
      if (event.shiftKey) {
        if (needle) {
          onSearchEverything(needle);
          close();
        }
        return;
      }
      run(entries[current]);
    } else if (event.altKey && top && !event.ctrlKey && !event.metaKey) {
      const action = actions.find((candidate) => candidate.key.toLowerCase() === event.key.toLowerCase() || `Key${candidate.key.toUpperCase()}` === (event.nativeEvent as KeyboardEvent).code);
      if (action) {
        event.preventDefault();
        action.run(top);
        close();
      }
    }
  };

  const shownHits = Math.min(hits.length, PALETTE_LIMIT);
  const shownActions = top ? actions.length : 0;
  const option = (at: number, entry: PaletteEntry, body: ReactNode, hint: ReactNode, key: string) => {
    return (
      <li
        key={key}
        id={`${listId}-${at}`}
        role="option"
        aria-selected={at === current}
        onMouseEnter={() => setActive(at)}
        onClick={() => run(entry)}
        className={`flex min-h-11 cursor-pointer items-center gap-3 px-4 py-2.5 text-sm text-ink-900 ${at === current ? "bg-purple-100 dark:bg-purple-950/60" : ""}`}
      >
        {body}
        <span className="ml-auto flex shrink-0 items-center gap-1 text-xs text-ink-500">{hint}</span>
      </li>
    );
  };

  const chip = (text: string) => (
    <span aria-hidden className="grid h-7 w-7 shrink-0 place-items-center rounded-sm border border-line-200 bg-surface-50 text-xs text-ink-700">
      {text}
    </span>
  );

  return (
    <FieldDialog open={open} onClose={close} title={<span className="sr-only">Quick jump</span>} className="max-w-[640px]! self-start overflow-hidden rounded-[20px]! border-0! p-0! shadow-[0_24px_64px_rgba(30,27,46,.35)]! sm:mt-[80px]" showClose={false} initialFocusRef={inputRef}>
      <div className="grid" data-command-palette="">
        <div className="flex items-center gap-2.5 border-b border-line-200 px-4 py-2">
          <Search className="h-[18px] w-[18px] shrink-0 text-ink-500" aria-hidden />
          <input
            ref={inputRef}
            value={query}
            onChange={(event) => {
              setQuery(event.target.value);
              setActive(0);
            }}
            onKeyDown={onKey}
            role="combobox"
            aria-expanded={entries.length > 0}
            aria-controls={listId}
            aria-activedescendant={entries.length ? `${listId}-${current}` : undefined}
            aria-label="Jump to anything"
            placeholder="Jump to a record, folder or action"
            className="min-h-10 min-w-0 flex-1 bg-transparent text-[17px] text-ink-900 outline-hidden placeholder:text-ink-300"
          />
          <Kbd>Esc</Kbd>
        </div>
        <ul id={listId} role="listbox" aria-label="Results" className="grid max-h-[55vh] overflow-y-auto">
          {hits.length ? <li role="presentation" className="px-4 pt-2.5 pb-1 text-[11px] font-semibold tracking-[0.06em] text-ink-500 uppercase">Records</li> : null}
          {hits.slice(0, PALETTE_LIMIT).map((hit, index) =>
            option(
              index,
              { kind: "hit", hit },
              <>
                {chip(typeInitial(hit.type))}
                <span className="min-w-0 truncate">
                  <Marked text={hit.title} needle={needle} />
                  {shortSubtitle(hit) ? <span className="text-xs text-ink-500"> · {shortSubtitle(hit)}</span> : null}
                </span>
              </>,
              index === 0 ? <Kbd><CornerDownLeft className="h-3 w-3" aria-label="Enter" /></Kbd> : null,
              `hit-${hit.type}-${hit.id}`
            )
          )}
          {top && actions.length ? <li role="presentation" className="truncate px-4 pt-2.5 pb-1 text-[11px] font-semibold tracking-[0.06em] text-ink-500 uppercase">Actions on {top.title}</li> : null}
          {top
            ? actions.map((action, index) =>
                option(
                  shownHits + index,
                  { kind: "action", action },
                  <>
                    {chip(action.icon ?? "›")}
                    <span className="min-w-0 truncate">{action.label}</span>
                  </>,
                  <>
                    <Kbd>Alt</Kbd>
                    <Kbd>{action.key.toUpperCase()}</Kbd>
                  </>,
                  `action-${action.key}`
                )
              )
            : null}
          {needle ? <li role="presentation" className="px-4 pt-2.5 pb-1 text-[11px] font-semibold tracking-[0.06em] text-ink-500 uppercase">Search</li> : null}
          {needle
            ? option(
                shownHits + shownActions,
                { kind: "everything" },
                <>
                  {chip("⌕")}
                  <span className="min-w-0 truncate">Search “{needle}” in every transcript, tool and process</span>
                </>,
                <>
                  <Kbd>⇧</Kbd>
                  <Kbd>↵</Kbd>
                </>,
                "everything"
              )
            : null}
        </ul>
        <p className="px-4 text-xs text-ink-500 empty:hidden" role="status" aria-live="polite">
          {!needle ? "Type to jump." : searching ? "Searching…" : answer?.error ? "Search isn't reachable right now." : hits.length ? "" : `No records match “${needle}”.`}
        </p>
        <div className="hidden flex-wrap gap-4 border-t border-line-200 px-4 py-2.5 text-xs text-ink-500 sm:flex" aria-hidden>
          <span className="flex items-center gap-1">
            <Kbd>↑</Kbd>
            <Kbd>↓</Kbd> move
          </span>
          <span className="flex items-center gap-1">
            <Kbd>↵</Kbd> open
          </span>
          <span className="flex items-center gap-1">
            <Kbd>Ctrl</Kbd>
            <Kbd>K</Kbd> anywhere
          </span>
        </div>
      </div>
    </FieldDialog>
  );
}

export function ShortcutsSheet({ open, onClose }: { open: boolean; onClose: () => void }) {
  return (
    <FieldDialog
      open={open}
      onClose={onClose}
      title={
        <span className="font-sans text-base font-bold tracking-normal">
          Shortcuts <span className="text-[13px] font-normal text-ink-500">press ?</span>
        </span>
      }
      className="max-w-[360px]! self-start rounded-[20px]! border-0! p-[18px]! shadow-[0_24px_64px_rgba(30,27,46,.25)]! sm:mt-[80px]"
    >
      <dl className="mt-2.5 grid grid-cols-[minmax(0,1fr)_auto] items-center gap-x-3 gap-y-2.5 text-[13px]" data-testid="shortcuts-sheet">
        {SHORTCUTS.map((shortcut) => (
          <div key={shortcut.label} className="contents">
            <dt className="text-ink-900">{shortcut.label}</dt>
            <dd className="flex items-center justify-end gap-1">
              {shortcut.keys.map((key) => (
                <Kbd key={key}>{key}</Kbd>
              ))}
            </dd>
          </div>
        ))}
      </dl>
    </FieldDialog>
  );
}
