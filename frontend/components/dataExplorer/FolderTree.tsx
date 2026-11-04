"use client";

import { ChevronRight, MoreHorizontal, RefreshCw } from "lucide-react";
import { useMemo, useRef, useState, type KeyboardEvent } from "react";

import { RecordIcon } from "@/components/dataExplorer/explorerUi";
import type { TreeStore } from "@/components/dataExplorer/useExplorerTree";

type Row =
  | { kind: "folder"; path: string; name: string; recordType: string | null; level: number; parent: string | null; size: number; position: number }
  | { kind: "more"; path: string; name: string; level: number; parent: string; size: number; position: number }
  | { kind: "loading"; path: string; level: number; parent: string };

export function visibleRows(store: Pick<TreeStore, "trees" | "expanded" | "loading">, rootName: string): Row[] {
  const rows: Row[] = [{ kind: "folder", path: "", name: rootName, recordType: null, level: 1, parent: null, size: 1, position: 1 }];
  const walk = (parent: string, level: number) => {
    if (!store.expanded.has(parent)) return;
    const tree = store.trees[parent];
    if (!tree) {
      if (store.loading.has(parent)) rows.push({ kind: "loading", path: `${parent}#loading`, level, parent });
      return;
    }
    const folders = tree.entries.filter((entry) => entry.kind === "folder");
    const size = folders.length + (tree.nextCursor ? 1 : 0);
    folders.forEach((folder, index) => {
      rows.push({ kind: "folder", path: folder.path, name: folder.name, recordType: folder.recordType ?? null, level, parent, size, position: index + 1 });
      walk(folder.path, level + 1);
    });
    if (tree.nextCursor) rows.push({ kind: "more", path: `${parent}#more`, name: "Load more", level, parent, size, position: size });
  };
  walk("", 2);
  return rows;
}

export function FolderTree({
  store,
  selectedPath,
  rootName,
  onSelect
}: {
  store: TreeStore;
  selectedPath: string;
  rootName: string;
  onSelect: (path: string) => void;
}) {
  const rows = useMemo(() => visibleRows(store, rootName), [store, rootName]);
  const focusable = rows.filter((row) => row.kind !== "loading");
  const [focused, setFocused] = useState<string | null>(null);
  const activePath = focusable.some((row) => row.path === focused) ? focused : selectedPath;
  const refs = useRef(new Map<string, HTMLDivElement>());

  const focusRow = (path: string) => {
    setFocused(path);
    refs.current.get(path)?.focus();
  };

  const activate = (row: Row) => {
    if (row.kind === "more") void store.more(row.parent);
    else if (row.kind === "folder") onSelect(row.path);
  };

  const onKey = (event: KeyboardEvent<HTMLDivElement>, row: Row) => {
    const index = focusable.findIndex((candidate) => candidate.path === row.path);
    const go = (target: Row | undefined) => {
      if (!target) return;
      event.preventDefault();
      focusRow(target.path);
    };
    switch (event.key) {
      case "ArrowDown":
        go(focusable[index + 1]);
        break;
      case "ArrowUp":
        go(focusable[index - 1]);
        break;
      case "Home":
        go(focusable[0]);
        break;
      case "End":
        go(focusable[focusable.length - 1]);
        break;
      case "ArrowRight":
        if (row.kind !== "folder") break;
        event.preventDefault();
        if (!store.expanded.has(row.path)) store.toggle(row.path);
        else if (focusable[index + 1]?.level === row.level + 1) focusRow(focusable[index + 1].path);
        break;
      case "ArrowLeft":
        event.preventDefault();
        if (row.kind === "folder" && store.expanded.has(row.path) && row.path !== "") store.toggle(row.path);
        else if (row.kind !== "loading" && row.parent !== null) focusRow(row.parent);
        break;
      case "Enter":
      case " ":
        event.preventDefault();
        activate(row);
        break;
      default:
        break;
    }
  };

  return (
    <div role="tree" aria-label="Folders" className="grid gap-0.5" data-testid="folder-tree">
      {rows.map((row) => {
        const indent = { paddingLeft: `${(row.level - 1) * 14 + 4}px` };
        if (row.kind === "loading") {
          return (
            <div key={row.path} role="none" className="flex min-h-8 items-center gap-2 text-xs text-ink-500" style={indent}>
              <RefreshCw className="h-3 w-3 animate-spin" aria-hidden />
              Loading…
            </div>
          );
        }
        const selected = row.kind === "folder" && row.path === selectedPath;
        const expanded = row.kind === "folder" ? store.expanded.has(row.path) : undefined;
        const busy = row.kind === "folder" && store.loading.has(row.path);
        return (
          <div
            key={row.path}
            ref={(node) => {
              if (node) refs.current.set(row.path, node);
              else refs.current.delete(row.path);
            }}
            role="treeitem"
            aria-level={row.level}
            aria-setsize={row.size}
            aria-posinset={row.position}
            aria-expanded={row.kind === "folder" && row.path !== "" ? expanded : undefined}
            aria-selected={row.kind === "folder" ? selected : undefined}
            aria-busy={busy || undefined}
            tabIndex={row.path === activePath ? 0 : -1}
            onFocus={() => setFocused(row.path)}
            onKeyDown={(event) => onKey(event, row)}
            onClick={() => activate(row)}
            data-path={row.path}
            className={`group flex min-h-9 cursor-pointer select-none items-center gap-1.5 rounded-md pr-2 text-sm transition-colors pointer-coarse:min-h-11 ${
              selected ? "bg-purple-100 font-semibold text-purple-800 dark:bg-purple-950/60 dark:text-purple-200" : "text-ink-700 hover:bg-purple-50 dark:hover:bg-purple-950/30"
            }`}
            style={indent}
          >
            {row.kind === "folder" && row.path !== "" ? (
              <span
                aria-hidden
                className="grid h-7 w-7 shrink-0 place-items-center rounded hover:bg-purple-100 dark:hover:bg-purple-900/50 pointer-coarse:h-9 pointer-coarse:w-9"
                onClick={(event) => {
                  event.stopPropagation();
                  store.toggle(row.path);
                }}
              >
                <ChevronRight className={`h-3.5 w-3.5 transition-transform ${expanded ? "rotate-90" : ""}`} />
              </span>
            ) : (
              <span aria-hidden className="w-1 shrink-0" />
            )}
            {row.kind === "more" ? (
              <MoreHorizontal className="h-4 w-4 shrink-0 text-purple-700 dark:text-purple-300" aria-hidden />
            ) : (
              <RecordIcon recordType={row.recordType} root={row.path === ""} className="h-4 w-4 shrink-0 text-purple-700 dark:text-purple-300" />
            )}
            <span className="min-w-0 flex-1 truncate">{row.name}</span>
            {busy ? <RefreshCw className="h-3 w-3 shrink-0 animate-spin text-ink-500" aria-hidden /> : null}
          </div>
        );
      })}
    </div>
  );
}
