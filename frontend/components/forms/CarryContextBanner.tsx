"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useSearchParams } from "next/navigation";
import { History, X } from "lucide-react";

import { useAuth } from "@/components/AuthProvider";
import {
  carryChainItems,
  carryContextFromParams,
  CARRY_NODE_LABEL,
  clearCarryContext,
  describeCarryAge,
  describeCarryImplied,
  hasCarryNode,
  isEmptyCarryContext,
  narrowestCarryNode,
  pruneCarryNode,
  pruneStoredCarryNode,
  readCarryContext,
  rememberCarryContext,
  sanitizeCarryContext,
  selectCarryNodes,
  type CarryContext,
  type CarryNode
} from "@/lib/carryContext";

export type CarryOfferSource = "handoff" | "resumed";

export type CarryOffer = {
  context: CarryContext;
  source: CarryOfferSource;
  savedAt: number;
  origin: CarryNode | null;
};

export type CarryScopeState = "pending" | "loaded" | "unavailable";

export type CarryScope = { node: CarryNode; state: CarryScopeState; ids: readonly string[] };

export function carryScope(node: CarryNode, state: CarryScopeState, rows: readonly { id: string }[]): CarryScope {
  return { node, state, ids: rows.map((row) => row.id) };
}

export type CarryContextState = {
  applied: CarryOffer | null;
  change: () => void;
  retire: () => void;
  prune: (node: CarryNode) => void;
  remember: (patch: Partial<CarryContext>, options?: { explicit?: boolean }) => void;
};

export function useCarryContext({
  enabled = true,
  scopes,
  applies,
  onApply
}: {
  enabled?: boolean;
  scopes: CarryScope[];
  applies?: readonly CarryNode[];
  onApply: (context: CarryContext) => void;
}): CarryContextState {
  const { user } = useAuth();
  const searchParams = useSearchParams();
  const userId = user?.id ?? null;
  const [applied, setApplied] = useState<CarryOffer | null>(null);
  const settled = useRef(false);
  const applyRef = useRef(onApply);
  const scopesRef = useRef(scopes);
  const appliesRef = useRef(applies);
  useEffect(() => {
    applyRef.current = onApply;
    scopesRef.current = scopes;
    appliesRef.current = applies;
  });
  const scopeSignature = scopes.map((scope) => `${scope.node}:${scope.state}:${scope.ids.length}`).join("|");

  useEffect(() => {
    if (!enabled || settled.current || !userId) return;

    const fromQuery = carryContextFromParams(searchParams);
    const handoff = !isEmptyCarryContext(fromQuery);
    const stored = handoff ? null : readCarryContext(userId);
    let context = handoff ? fromQuery : stored ? sanitizeCarryContext(stored) : null;
    if (!context) {
      settled.current = true;
      return;
    }

    const scopeList = scopesRef.current;
    if (scopeList.some((scope) => scope.state === "pending" && hasCarryNode(context, scope.node))) return;

    const unreachable: CarryNode[] = [];
    for (const scope of scopeList) {
      if (scope.state !== "loaded") continue;
      const id = context[`${scope.node}Id` as keyof CarryContext];
      if (id && !scope.ids.includes(id)) unreachable.push(scope.node);
    }
    for (const node of unreachable) context = pruneCarryNode(context, node);

    settled.current = true;
    if (handoff) rememberCarryContext(userId, context);
    else for (const node of unreachable) pruneStoredCarryNode(userId, node);
    const offered = appliesRef.current ? selectCarryNodes(context, appliesRef.current) : context;
    if (isEmptyCarryContext(offered)) {
      if (isEmptyCarryContext(context)) clearCarryContext(userId);
      return;
    }

    const origin =
      stored?.originNode && hasCarryNode(offered, stored.originNode) ? stored.originNode : narrowestCarryNode(offered);
    applyRef.current(offered);
    setApplied({ context: offered, source: handoff ? "handoff" : "resumed", savedAt: stored?.savedAt ?? Date.now(), origin });
  }, [enabled, userId, searchParams, scopeSignature]);

  const change = useCallback(() => {
    setApplied(null);
    clearCarryContext(userId);
  }, [userId]);

  const retire = useCallback(() => setApplied(null), []);

  const prune = useCallback(
    (node: CarryNode) => {
      pruneStoredCarryNode(userId, node);
      setApplied((current) => {
        if (!current || !hasCarryNode(current.context, node)) return current;
        const context = pruneCarryNode(current.context, node);
        if (isEmptyCarryContext(context)) return null;
        const origin = current.origin && hasCarryNode(context, current.origin) ? current.origin : narrowestCarryNode(context);
        return { ...current, context, origin };
      });
    },
    [userId]
  );

  const remember = useCallback(
    (patch: Partial<CarryContext>, options?: { explicit?: boolean }) => {
      rememberCarryContext(userId, patch);
      if (options?.explicit) setApplied(null);
    },
    [userId]
  );

  return { applied, change, retire, prune, remember };
}

export function CarryContextBanner({
  offer,
  onChange,
  changeLabel = "Change"
}: {
  offer: CarryOffer | null;
  onChange: () => void;
  changeLabel?: string;
}) {
  const chain = useMemo(() => (offer ? carryChainItems(offer.context) : []), [offer]);
  if (!offer) return null;
  const headline =
    offer.context.artisanName ??
    offer.context.productName ??
    offer.context.toolName ??
    offer.context.craftName ??
    "the last record";
  const originLabel = offer.origin ? CARRY_NODE_LABEL[offer.origin] : "record";
  const provenance =
    offer.source === "handoff"
      ? `Carried from the ${originLabel} you just saved.`
      : `From the ${originLabel} you documented ${describeCarryAge(offer.savedAt)}.`;
  const implied = describeCarryImplied(offer.context, offer.origin);

  return (
    <div
      role="status"
      className="flex flex-wrap items-start gap-3 rounded-md border border-purple-200 bg-purple-50 px-3 py-2.5 dark:border-purple-900 dark:bg-purple-950/40"
    >
      <span className="grid h-8 w-8 shrink-0 place-items-center rounded-sm bg-purple-100 text-purple-700 dark:bg-purple-900/60 dark:text-purple-200">
        <History className="h-4 w-4" aria-hidden />
      </span>
      <div className="min-w-0 flex-1">
        <p className="text-sm font-medium text-ink-900">
          Continuing with <span className="font-semibold">{headline}</span>
        </p>
        {chain.length ? (
          <ul className="mt-1 flex flex-wrap gap-1.5">
            {chain.map((item) => (
              <li
                key={item.node}
                className="inline-flex max-w-full items-baseline gap-1 rounded-sm border border-purple-200 bg-card/70 px-1.5 py-0.5 text-xs dark:border-purple-800 dark:bg-purple-950/60"
              >
                <span className="shrink-0 text-[10px] font-semibold uppercase tracking-wide text-purple-700 dark:text-purple-300">
                  {item.label}
                </span>
                <span className="truncate text-ink-700">{item.value}</span>
              </li>
            ))}
          </ul>
        ) : null}
        <p className="mt-1 text-xs text-ink-500">
          {provenance} {implied ? `${implied} ` : ""}Prefilled below — check it before saving.
        </p>
      </div>
      <button
        type="button"
        onClick={onChange}
        className="inline-flex shrink-0 items-center gap-1 rounded-sm border border-purple-200 bg-card px-2.5 py-1.5 text-xs font-medium text-purple-700 transition hover:bg-purple-100 focus-visible:outline-hidden focus-visible:ring-2 focus-visible:ring-purple-700 dark:border-purple-800 dark:text-purple-200 dark:hover:bg-purple-900/60 dark:focus-visible:ring-purple-300"
      >
        <X className="h-3.5 w-3.5" aria-hidden />
        {changeLabel}
      </button>
    </div>
  );
}
