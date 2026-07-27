"use client";

/**
 * "Continuing with Giriraj Prasad Chhipa · Bagru Block Printing" — the visible half of
 * lib/carryContext.
 *
 * THE RULE THIS COMPONENT EXISTS TO ENFORCE: a prefill the researcher does not notice is how a
 * product ends up attached to the wrong artisan, and in a research corpus that is a falsified row,
 * not an inconvenience. So a carried context is never quietly poured into the fields. Whenever
 * something was prefilled, this banner sits above the form naming EVERY record it brought — each
 * one labelled by what it is — where they came from and how old they are, with one control that
 * clears the lot.
 *
 * Naming every record is what makes the ladder in lib/carryContext safe to have. The bag implies
 * parents: pick up a product and its artisan and craft come with it. An implication nobody can see
 * is indistinguishable from a guess, so the chain is printed in full and the provenance line says
 * out loud which record was chosen and which ones tagged along.
 *
 * That is also why there is no "dismiss" that keeps the values. Hiding the banner while leaving the
 * fields filled would recreate the exact hazard it was put there to remove. The banner disappears
 * on one event only — the researcher picking a record themselves, at which point the values are
 * their choice rather than our guess.
 *
 * Android parity: `ui/CarryContextBanner.kt` renders the same lines and the same "Change".
 */

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

/**
 * Where an offer came from. `handoff` is a click straight through from CarryForwardCards — intent
 * is explicit and seconds old. `resumed` is the case this whole feature was built for: the form was
 * opened cold, from the dashboard or a bookmark, and the context is being volunteered.
 */
export type CarryOfferSource = "handoff" | "resumed";

export type CarryOffer = {
  context: CarryContext;
  source: CarryOfferSource;
  /** Epoch ms the context was recorded; equal to "now" for a handoff. */
  savedAt: number;
  /** The record the context was last built around — what the provenance sentence is about. */
  origin: CarryNode | null;
};

/** How a caller reports a list it loaded, because "not visible to me" and "no signal" differ. */
export type CarryScopeState = "pending" | "loaded" | "unavailable";

/**
 * One list the form has loaded, and therefore one record type it can vouch for. A carried record
 * absent from a loaded list was deleted or sits behind access the researcher has lost; either way
 * it is dropped rather than offered, along with everything that hung off it.
 */
export type CarryScope = { node: CarryNode; state: CarryScopeState; ids: readonly string[] };

/** `carryScope("artisan", artisanListState, artisans)` — the shape every call site actually has. */
export function carryScope(node: CarryNode, state: CarryScopeState, rows: readonly { id: string }[]): CarryScope {
  return { node, state, ids: rows.map((row) => row.id) };
}

export type CarryContextState = {
  /** The offer that was actually applied to the form, or null while nothing has been. */
  applied: CarryOffer | null;
  /**
   * Clear the prefill and forget the context. Wired to "Change".
   *
   * It empties the WHOLE bag, not only the records this form narrowed down to. "Change" is the
   * researcher saying the app has guessed wrong about where they are, and a rule that left some of
   * the guess behind — to resurface two forms later, on a screen that never showed it — would be a
   * dismissal that did not dismiss.
   */
  change: () => void;
  /**
   * Retire the banner while keeping the stored context — for the one case that is not a dismissal:
   * the researcher has replaced the selection with their own, so nothing on screen is a suggestion
   * any more and the banner would be describing a value that is no longer there.
   */
  retire: () => void;
  /**
   * Forget one carried record once the form learns it cannot be used — the list that would have
   * proved it reachable only arrives after the prefill (a process form cannot ask for an artisan's
   * products until it has the artisan). Its children go with it, and the banner stops claiming it.
   */
  prune: (node: CarryNode) => void;
  /**
   * Record the context the researcher is now in. Call on an explicit pick and after a save.
   * An explicit pick also retires the banner: the value stopped being a suggestion.
   */
  remember: (patch: Partial<CarryContext>, options?: { explicit?: boolean }) => void;
};

/**
 * Resolve a carried context for one create form and hand it to the caller once — never twice, and
 * never before the form can tell whether the records in it are still reachable.
 *
 * VISIBILITY AND STALENESS are one check, deliberately. Both "this artisan was deleted" and "this
 * artisan belongs to a workshop I lost access to" show up identically: the id is absent from the
 * list the form just loaded. Either way that record is dropped without a word — an error about a
 * record the researcher cannot see explains nothing and blocks a form they came here to fill in.
 * The rest of the bag survives: losing sight of one product is no reason to make them re-pick the
 * artisan too.
 *
 * When a list could not be loaded at all (offline, which is the normal state in Bagru) the offer
 * still stands. Suppressing the prefill exactly when the network is down would disable the feature
 * in the conditions it was written for, and the names are what get stored anyway.
 */
export function useCarryContext({
  enabled = true,
  scopes,
  applies,
  onApply
}: {
  enabled?: boolean;
  scopes: CarryScope[];
  /**
   * The record types this form has fields for. Everything else in the bag is left in storage
   * untouched but is neither applied nor named in the banner — a product form does not fill in a
   * process, so it must not claim to have. Omit to use the whole bag.
   */
  applies?: readonly CarryNode[];
  onApply: (context: CarryContext) => void;
}): CarryContextState {
  const { user } = useAuth();
  const searchParams = useSearchParams();
  const userId = user?.id ?? null;
  const [applied, setApplied] = useState<CarryOffer | null>(null);
  // The apply runs from an effect that also depends on the loaded lists, so it must not re-fire
  // when one of them refreshes — the researcher may have edited the fields since.
  const settled = useRef(false);
  // The callers pass inline arrays and arrow functions, so both are new objects every render.
  // Holding them in refs keeps them out of the effect's dependency list: the effect must fire on a
  // list resolving, not on the parent re-rendering.
  const applyRef = useRef(onApply);
  const scopesRef = useRef(scopes);
  const appliesRef = useRef(applies);
  useEffect(() => {
    applyRef.current = onApply;
    scopesRef.current = scopes;
    appliesRef.current = applies;
  });
  // What the effect DOES need to watch: a scope flipping out of "pending", or its list arriving.
  const scopeSignature = scopes.map((scope) => `${scope.node}:${scope.state}:${scope.ids.length}`).join("|");

  useEffect(() => {
    if (!enabled || settled.current || !userId) return;

    const fromQuery = carryContextFromParams(searchParams);
    const handoff = !isEmptyCarryContext(fromQuery);
    // A handoff is the freshest possible context, so it is banked immediately: navigate on to a
    // second record and the away-and-back path already knows where the researcher was.
    const stored = handoff ? null : readCarryContext(userId);
    let context = handoff ? fromQuery : stored ? sanitizeCarryContext(stored) : null;
    if (!context) {
      settled.current = true;
      return;
    }

    const scopeList = scopesRef.current;
    // Wait for the form to know whether it can see these records — but only while there is still an
    // answer coming for something the context actually names.
    if (scopeList.some((scope) => scope.state === "pending" && hasCarryNode(context, scope.node))) return;

    const unreachable: CarryNode[] = [];
    for (const scope of scopeList) {
      if (scope.state !== "loaded") continue;
      const id = context[`${scope.node}Id` as keyof CarryContext];
      if (id && !scope.ids.includes(id)) unreachable.push(scope.node);
    }
    for (const node of unreachable) context = pruneCarryNode(context, node);

    settled.current = true;
    // Storage keeps the whole bag; only the offer is narrowed to what this form can actually fill.
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
      // The researcher chose this themselves, so nothing on screen is a suggestion any more.
      if (options?.explicit) setApplied(null);
    },
    [userId]
  );

  return { applied, change, retire, prune, remember };
}

/**
 * The banner itself. Rendered as the first thing inside the form, above the workshop picker, so it
 * is read before any of the fields it filled in.
 */
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
  // The headline names the subject the researcher thinks in — the person — and falls back down the
  // ladder only when there is no artisan to name.
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
        // No ring OFFSET: the default offset colour is white, which would punch a white halo out of
        // the purple-950 banner in dark mode. A 2px ring straight on the border reads on both.
        className="inline-flex shrink-0 items-center gap-1 rounded-sm border border-purple-200 bg-card px-2.5 py-1.5 text-xs font-medium text-purple-700 transition hover:bg-purple-100 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-purple-700 dark:border-purple-800 dark:text-purple-200 dark:hover:bg-purple-900/60 dark:focus-visible:ring-purple-300"
      >
        <X className="h-3.5 w-3.5" aria-hidden />
        {changeLabel}
      </button>
    </div>
  );
}
