"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";

import { fetchTreePage } from "@/lib/dataExplorer/api";
import type { TreeResponse } from "@/lib/dataExplorer/contract";
import { followingCursor } from "@/lib/dataExplorer/explorerView";
import type { QueryParams } from "@/lib/dataExplorer/wire";

export type TreeStore = {
  trees: Record<string, TreeResponse>;
  loading: ReadonlySet<string>;
  errors: Record<string, string>;
  expanded: ReadonlySet<string>;
  load(path: string): Promise<void>;
  more(path: string): Promise<void>;
  toggle(path: string): void;
  expand(paths: string[]): void;
};

function message(error: unknown, fallback: string): string {
  return error instanceof Error && error.message ? error.message : fallback;
}

export function useExplorerTree(extra: QueryParams, enabled: boolean): TreeStore {
  const [trees, setTrees] = useState<Record<string, TreeResponse>>({});
  const [loading, setLoading] = useState<ReadonlySet<string>>(new Set());
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [expanded, setExpanded] = useState<ReadonlySet<string>>(new Set([""]));
  const extraKey = JSON.stringify(extra);
  const generation = useRef(0);
  const extraRef = useRef(extra);
  const treesRef = useRef(trees);
  const expandedRef = useRef(expanded);
  const inFlight = useRef(new Map<string, Promise<void>>());
  const cursorsSeen = useRef(new Map<string, Set<string>>());

  useEffect(() => {
    treesRef.current = trees;
    expandedRef.current = expanded;
  });

  const mark = useCallback((path: string, on: boolean) => {
    setLoading((previous) => {
      const next = new Set(previous);
      if (on) next.add(path);
      else next.delete(path);
      return next;
    });
  }, []);

  const load = useCallback(
    (path: string) => {
      const key = `${generation.current}:${path}`;
      const running = inFlight.current.get(key);
      if (running) return running;
      const asked = generation.current;
      mark(path, true);
      const work = (async () => {
        try {
          const page = await fetchTreePage(path, extraRef.current);
          if (asked !== generation.current) return;
          cursorsSeen.current.delete(path);
          setTrees((previous) => ({ ...previous, [path]: page }));
          setErrors((previous) => {
            if (!(path in previous)) return previous;
            const next = { ...previous };
            delete next[path];
            return next;
          });
        } catch (error) {
          if (asked !== generation.current) return;
          setErrors((previous) => ({ ...previous, [path]: message(error, "This folder could not be loaded.") }));
        } finally {
          inFlight.current.delete(key);
          if (asked === generation.current) mark(path, false);
        }
      })();
      inFlight.current.set(key, work);
      return work;
    },
    [mark]
  );

  const more = useCallback(
    async (path: string) => {
      const current = treesRef.current[path];
      if (!current?.nextCursor) return;
      const asked = generation.current;
      mark(path, true);
      try {
        const asking = current.nextCursor;
        const page = await fetchTreePage(path, extraRef.current, asking);
        if (asked !== generation.current) return;
        const seen = cursorsSeen.current.get(path) ?? new Set<string>();
        seen.add(asking);
        cursorsSeen.current.set(path, seen);
        const following = followingCursor(asking, page.nextCursor, seen);
        const repeated = Boolean(page.nextCursor) && following === null;
        setTrees((previous) => {
          const base = previous[path];
          if (!base || base.nextCursor !== asking) return previous;
          return { ...previous, [path]: { ...base, entries: [...base.entries, ...page.entries], nextCursor: following, truncated: page.truncated || repeated } };
        });
      } catch (error) {
        if (asked === generation.current) setErrors((previous) => ({ ...previous, [path]: message(error, "More entries didn't load.") }));
      } finally {
        if (asked === generation.current) mark(path, false);
      }
    },
    [mark]
  );

  useEffect(() => {
    if (JSON.stringify(extraRef.current) === extraKey) return;
    extraRef.current = JSON.parse(extraKey) as QueryParams;
    generation.current += 1;
    inFlight.current.clear();
    cursorsSeen.current.clear();
    setLoading(new Set());
    for (const path of Object.keys(treesRef.current)) void load(path);
  }, [extraKey, load]);

  useEffect(() => {
    if (enabled && !("" in treesRef.current)) void load("");
  }, [enabled, load]);

  const toggle = useCallback(
    (path: string) => {
      setExpanded((previous) => {
        const next = new Set(previous);
        if (next.has(path)) next.delete(path);
        else next.add(path);
        return next;
      });
      if (!(path in treesRef.current)) void load(path);
    },
    [load]
  );

  const expand = useCallback(
    (paths: string[]) => {
      setExpanded((previous) => {
        const next = new Set(previous);
        paths.forEach((path) => next.add(path));
        return next;
      });
      for (const path of paths) if (!(path in treesRef.current)) void load(path);
    },
    [load]
  );

  return useMemo(() => ({ trees, loading, errors, expanded, load, more, toggle, expand }), [trees, loading, errors, expanded, load, more, toggle, expand]);
}
