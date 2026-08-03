"use client";

import { useEffect, useRef, useState, type RefObject } from "react";
import { useRouter, useSearchParams } from "next/navigation";

import { useAppReducedMotion } from "@/components/guide/useAppReducedMotion";
import { apiFetch } from "@/lib/api";

/**
 * "Open this record for editing" as a URL, for the list pages whose form is INLINE.
 *
 * WHAT THIS FIXES. Four screens offer to update an existing craft or workshop — the View Data
 * browser's Edit link, the dashboard's "Update" tile and its Recent submissions rows, and a search
 * result — and every one of them linked to the bare list route (`/crafts`, `/workshops`). The list
 * route renders its inline form in CREATE mode, so "Edit this workshop" and "New workshop" landed on
 * the identical blank form: the record the researcher had just clicked was nowhere on screen, and
 * filling the form in produced a SECOND record instead of updating the one they meant. The id was
 * being dropped at the link, so no amount of care on the page could recover it.
 *
 * Artisans, products and tools never had the problem because they have real `/[id]/edit` routes.
 * Crafts, workshops and processes are edited inline on their list page, so the intent has to travel
 * as a query parameter instead — this hook is the receiving half, and it is shared rather than
 * copied three times so the three pages cannot drift into three dialects of the same link.
 *
 * THE TWO PARAMETERS
 *
 * * `?edit=<id>` — load that record into the form. The record is fetched BY ID rather than looked up
 *   in the list already on screen, which is the whole point: the caller arrives from another page
 *   entirely and the record is very often not on page 1 of a 20-row list (and after
 *   `?edit=` the list may not have loaded at all yet). A missing or unreadable id reports itself and
 *   leaves the form in create mode — never a silently blank form that looks like the link worked.
 * * `?new=1` — an explicitly blank form. `/crafts?new=1` and `/workshops?new=1` were already being
 *   emitted by the dashboard tiles and the walkthrough steps and were silently ignored, which is why
 *   "New" and "Update" appeared to be the same button. It clears any record being edited and scrolls
 *   to the form, so the two tiles now land somewhere visibly different.
 *
 * BOTH ARE ONE-SHOT INTENTS, and the parameter is stripped with `router.replace` once it has been
 * applied. A parameter that survived would re-apply on every render that re-read the URL, and would
 * mean the browser's Back button walked back into an edit the researcher had already cancelled. The
 * `handled` ref keys on the raw parameter, so stripping cannot re-trigger the effect it came from.
 *
 * THE SCROLL IS DEFERRED ONE FRAME, and that is load-bearing rather than cosmetic. Applying a record
 * remounts the form (every caller keys it on `editing?.id`) and reveals blocks that only exist while
 * editing — the previously-uploaded media list, the field-provenance panel. Measuring in the same
 * commit measures the layout the form is about to leave. It also cannot be `window.scrollTo(0, 0)`:
 * on /workshops an admin has the workshop-mapping panel sitting ABOVE the form, so scrolling to the
 * top of the document lands on a different card than the one that just filled itself in.
 */

/** The record was asked for by id; the form should load it. */
export const EDIT_PARAM = "edit";
/** An explicitly blank form was asked for. */
export const NEW_PARAM = "new";

type EditDeepLinkOptions<T> = {
  /** API collection the record is read from, e.g. `/crafts` — `GET {endpoint}/{id}`. */
  endpoint: string;
  /** The page's own route, e.g. `/crafts`. Where the URL is rewritten to once the intent is spent. */
  basePath: string;
  /**
   * The element to bring into view once the intent has been applied — pass the form's own ref, so
   * the page scrolls to the form itself rather than to the top of the document.
   *
   * OMIT IT when the record's form REPLACES the page rather than sitting on it (`/processes`
   * swaps the whole list out for the form), and the top of the document is the form.
   */
  targetRef?: RefObject<HTMLElement | null>;
  /** Seed the form with a fetched record. Callers pass their existing `resetForm`. */
  onEdit: (record: T) => void;
  /**
   * Clear the form back to create mode, for `?new=1`.
   *
   * OMIT IT to leave `?new=1` entirely alone. That is not a convenience: on `/processes` the
   * parameter is a RENDER MODE the page derives on every render (`isNew`), not a one-shot intent, so
   * a hook that consumed and stripped it would close the create form the instant it opened.
   */
  onNew?: () => void;
  /** Report a failed load through the page's own error banner. */
  onError: (message: string) => void;
  /**
   * Whether this account may use the form at all (`canManageCrafts` / `canManageWorkshops`). A
   * parameter aimed at a form the caller cannot see is dropped rather than acted on — the same
   * treatment `/processes` gives `?new=1` for a non-creator, and for the same reason: a refresh or a
   * shared link must not keep asking for a page this account cannot have.
   *
   * IT MUST NOT BE A TRANSIENT `false`, and it is not one here: these predicates read
   * `useAuth().user`, which starts null, but `AppShell` returns its own loading frame while
   * `loading` is true and `null` when there is no user, so a protected page's body never renders
   * against an unresolved account. Were that to change, this hook would strip the parameter on the
   * first render and the deep link would silently do nothing for everyone — so a page calling it
   * outside `(protected)` must gate on its own "identity resolved" flag first.
   */
  allowed: boolean;
  /** What to say when the id does not resolve, e.g. "Unable to load that craft". */
  errorMessage: string;
};

export function useEditDeepLink<T>({
  endpoint,
  basePath,
  targetRef,
  onEdit,
  onNew,
  onError,
  allowed,
  errorMessage
}: EditDeepLinkOptions<T>): { loading: boolean } {
  const router = useRouter();
  const searchParams = useSearchParams();
  const reduce = useAppReducedMotion();
  const [loading, setLoading] = useState(false);

  // The intent already acted on, as the literal parameter value ("edit:<id>" / "new"). Keyed on the
  // parameter rather than a boolean so arriving at ?edit=B while B's predecessor A is still in the
  // form is a fresh intent and reloads, while the router.replace that strips A is not.
  const handled = useRef<string | null>(null);

  // The callbacks are read through a ref so the effect below depends only on the URL. Every caller
  // passes inline closures over page state (`resetForm`, `setError`), which are new on every render
  // — in the dependency array they would re-run the effect on each keystroke in the form beneath.
  //
  // Written in an effect with NO dependency array rather than during render, which is the same
  // discipline `useLeaveGuard` follows and is not a lint formality: a render can be discarded under
  // concurrent rendering, and a ref written on a discarded render would leave the intent handler
  // calling into state that never committed. Declared before the effect that reads it so it is also
  // committed first; on the very first commit `useRef`'s initial value is already correct.
  const callbacks = useRef({ onEdit, onNew, onError });
  useEffect(() => {
    callbacks.current = { onEdit, onNew, onError };
  });

  /**
   * Reduced motion is read through a ref for the SAME reason, and it is not a micro-optimisation.
   *
   * `useAppReducedMotion()` reads false on the server and on the first client render by design, then
   * flips to true a tick later once ThemeProvider has read the stored preferences (its own docstring
   * says so). As a DEPENDENCY that flip tears this effect down and re-runs it — mid-fetch — for every
   * account that has reduced motion switched on. It is only ever read inside the scroll, long after
   * the decision to load, so it has no business re-triggering the load.
   */
  const reduceRef = useRef(reduce);
  useEffect(() => {
    reduceRef.current = reduce;
  });

  // A page that did not supply `onNew` does not want `?new=1` touched — see the option's note.
  const handlesNew = onNew !== undefined;
  const editId = searchParams.get(EDIT_PARAM);
  const wantsNew = handlesNew && searchParams.get(NEW_PARAM) === "1";
  // Depended on as a STRING: the URLSearchParams object is a new identity on every navigation, and
  // the effect below rebuilds the query from it to preserve the parameters it does not consume.
  const searchString = searchParams.toString();

  useEffect(() => {
    const token = editId ? `edit:${editId}` : wantsNew ? "new" : null;
    if (!token) {
      // The URL carries no intent — either none ever did, or this is the render after `spend()`
      // stripped one. Forgetting it here is what lets the SAME record be opened twice: edit craft A,
      // press Cancel edit, click Edit on A again from the data browser. With the token remembered
      // across the clean URL that second click matched `handled` and did nothing at all.
      handled.current = null;
      return;
    }
    if (handled.current === token) return;
    handled.current = token;

    /** One frame, so the remounted form (and its edit-only blocks) has its final height. */
    const scrollToForm = () => {
      requestAnimationFrame(() => {
        const behavior: ScrollBehavior = reduceRef.current ? "auto" : "smooth";
        // No target element means the form IS the page (see `targetRef`), so the top of the
        // document is the right destination — the same place /processes' own row Edit scrolls to.
        if (targetRef?.current) targetRef.current.scrollIntoView({ behavior, block: "start" });
        else window.scrollTo({ top: 0, behavior });
      });
    };

    /**
     * Drop the parameters this hook consumed and leave every other one standing. Rebuilding the
     * query rather than replacing the whole URL with `basePath` matters because these routes carry
     * parameters the hook knows nothing about — `?new=1` on a page that owns it itself, and any
     * filter a list page grows later. `scroll: false` because this hook owns the scroll: Next's
     * default would jump to the top of the document, which on /workshops is the mapping panel.
     */
    const spend = () => {
      const next = new URLSearchParams(searchString);
      next.delete(EDIT_PARAM);
      if (handlesNew) next.delete(NEW_PARAM);
      const query = next.toString();
      router.replace(query ? `${basePath}?${query}` : basePath, { scroll: false });
    };

    if (!allowed) {
      spend();
      return;
    }

    if (wantsNew && !editId) {
      callbacks.current.onNew?.();
      spend();
      scrollToForm();
      return;
    }

    let cancelled = false;
    setLoading(true);
    apiFetch<T>(`${endpoint}/${editId}`)
      .then((record) => {
        if (cancelled) return;
        callbacks.current.onEdit(record);
        scrollToForm();
      })
      .catch((err) => {
        if (cancelled) return;
        callbacks.current.onError(err instanceof Error ? err.message : errorMessage);
      })
      .finally(() => {
        if (cancelled) return;
        setLoading(false);
        spend();
      });

    return () => {
      cancelled = true;
      /*
       * RELEASE THE TOKEN, so a torn-down attempt can be retried by the setup that replaces it.
       *
       * `handled` is claimed when the fetch is ISSUED, and every completion path above is gated on
       * `cancelled`. Without this line a teardown between "issued" and "resolved" left the token
       * claimed by an attempt that could no longer finish, so the re-run returned at the `handled`
       * guard and NOTHING ever completed: the banner said "Loading..." forever, the form stayed in
       * create mode, and `?edit=` stayed in the URL — the researcher then filled the blank form in
       * and created a SECOND record, which is the exact bug this hook exists to end.
       *
       * That is not a theoretical ordering. `reactStrictMode` is on (frontend/next.config.ts), so
       * React runs setup → cleanup → setup on every mount in development: it would have deadlocked
       * 100% of deep links locally. In production the trigger was the reduced-motion flip described
       * above, so it would have hit exactly the accounts that had asked for less motion.
       *
       * Guarded on the token still being ours, so a cleanup racing a LATER intent cannot release the
       * claim that intent has just made. Cost: one discarded GET per teardown.
       */
      if (handled.current === token) handled.current = null;
    };
  }, [
    editId,
    wantsNew,
    handlesNew,
    allowed,
    basePath,
    endpoint,
    errorMessage,
    router,
    searchString,
    targetRef
  ]);

  return { loading };
}
