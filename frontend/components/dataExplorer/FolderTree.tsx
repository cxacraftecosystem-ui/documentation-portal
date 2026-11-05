"use client";

import { RefreshCw } from "lucide-react";
import { useMemo, useRef, useState, type KeyboardEvent } from "react";

import type { TreeStore } from "@/components/dataExplorer/useExplorerTree";

type Row =
  | { kind: "folder"; path: string; name: string; recordType: string | null; level: number; parent: string | null; size: number; position: number; count: string | null }
  | { kind: "more"; path: string; name: string; level: number; parent: string; size: number; position: number }
  | { kind: "loading"; path: string; level: number; parent: string };

function parentOf(path: string): string {
  return path.split("/").slice(0, -1).join("/");
}

export function visibleRows(store: Pick<TreeStore, "trees" | "expanded" | "loading">, rootName: string, rootPath = ""): Row[] {
  const countOf = (path: string, known?: number | null): string | null => {
    if (typeof known === "number") return known.toLocaleString("en-IN");
    const tree = store.trees[path];
    if (!tree) return null;
    return `${tree.entries.length.toLocaleString("en-IN")}${tree.nextCursor ? "+" : ""}`;
  };
  const rootEntry = rootPath ? store.trees[parentOf(rootPath)]?.entries.find((entry) => entry.path === rootPath) : undefined;
  const rows: Row[] = [{ kind: "folder", path: rootPath, name: rootName, recordType: null, level: 1, parent: null, size: 1, position: 1, count: rootPath ? countOf(rootPath, rootEntry?.count) : null }];
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
      rows.push({ kind: "folder", path: folder.path, name: folder.name, recordType: folder.recordType ?? null, level, parent, size, position: index + 1, count: countOf(folder.path, folder.count) });
      walk(folder.path, level + 1);
    });
    if (tree.nextCursor) rows.push({ kind: "more", path: `${parent}#more`, name: "Load more", level, parent, size, position: size });
  };
  walk(rootPath, 2);
  return rows;
}

export function FolderTree({
  store,
  selectedPath,
  rootName,
  rootPath = "",
  onSelect
}: {
  store: TreeStore;
  selectedPath: string;
  rootName: string;
  rootPath?: string;
  onSelect: (path: string) => void;
}) {
  const rows = useMemo(() => visibleRows(store, rootName, rootPath), [store, rootName, rootPath]);
  const focusable = rows.filter((row) => row.kind !== "loading");
  const [focused, setFocused] = useState<string | null>(null);
  const activePath = focusable.some((row) => row.path === focused) ? focused : focusable.some((row) => row.path === selectedPath) ? selectedPath : rootPath;
  const refs = useRef(new Map<string, HTMLDivElement>());

  const focusRow = (path: string) => {
    setFocused(path);
    refs.current.get(path)?.focus();
  };

  const activate = (row: Row) => {
    if (row.kind === "more") void store.more(row.parent);
    else if (row.kind === "folder") onSelect(row.path);
  };

  const hasChildren = (path: string) => {
    const tree = store.trees[path];
    return tree ? tree.entries.some((entry) => entry.kind === "folder") || Boolean(tree.nextCursor) : true;
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
    <div role="tree" aria-label="Folders" className="flex flex-col gap-0.5 text-sm" data-testid="folder-tree">
      {rows.map((row) => {
        const indent = { paddingLeft: `${(row.level - 1) * 16 + 8}px` };
        if (row.kind === "loading") {
          return (
            <div key={row.path} role="none" className="flex min-h-10 items-center gap-2 text-xs text-ink-500" style={indent}>
              <RefreshCw className="h-3 w-3 animate-spin" aria-hidden />
              Loading…
            </div>
          );
        }
        const selected = row.kind === "folder" && row.path === selectedPath;
        const expanded = row.kind === "folder" ? store.expanded.has(row.path) : undefined;
        const busy = row.kind === "folder" && store.loading.has(row.path);
        const open = Boolean(expanded) && row.kind === "folder" && hasChildren(row.path);
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
            className={`group flex min-h-10 cursor-pointer select-none items-center gap-2 rounded-[10px] pr-2 transition-colors pointer-coarse:min-h-11 ${
              selected ? "bg-purple-100 font-semibold text-purple-800 dark:bg-purple-950/60 dark:text-purple-200" : "text-ink-900 hover:bg-purple-50 dark:hover:bg-purple-950/30"
            }`}
            style={indent}
          >
            {row.kind === "folder" ? (
              <span
                aria-hidden
                className={`grid w-5 shrink-0 place-items-center self-stretch text-[13px] ${selected ? "" : "text-ink-500"}`}
                onClick={(event) => {
                  if (row.path === "") return;
                  event.stopPropagation();
                  store.toggle(row.path);
                }}
              >
                {open ? "▾" : "▸"}
              </span>
            ) : (
              <span aria-hidden className="w-5 shrink-0 text-center text-ink-500">
                …
              </span>
            )}
            <span className="min-w-0 flex-1 truncate">{row.name}</span>
            {row.kind === "folder" && row.count !== null && !busy ? (
              <span className={`shrink-0 text-xs tabular-nums ${selected ? "" : "text-ink-500"}`} aria-hidden>
                {row.count}
              </span>
            ) : null}
            {busy ? <RefreshCw className="h-3 w-3 shrink-0 animate-spin text-ink-500" aria-hidden /> : null}
          </div>
        );
      })}
    </div>
  );
}
