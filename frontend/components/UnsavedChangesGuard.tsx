"use client";

import { createContext, useCallback, useContext, useEffect, useMemo, useRef } from "react";

/**
 * Lets the round back control in `PageHeader` ask the form below it whether leaving is safe.
 *
 * The two are siblings, not parent and child: the page renders `<PageHeader />` and then
 * `<ArtisanForm />`, so the header cannot see the form's `dirty` flag and the form cannot reach the
 * header's button. That gap is why every form used to carry its own rounded "Back" pill — the pill
 * lived inside the form, where the flag was, so it was the only control that could raise the
 * "Unsaved changes" prompt. The result was two back controls stacked on the same screen, one of
 * which silently discarded work.
 *
 * A context closes the gap the other way round: the form registers an interceptor, the back control
 * calls it, and the pill is no longer needed for anything.
 */

/**
 * Returns true when it has TAKEN RESPONSIBILITY for the navigation — the form is dirty and has put
 * its own dialog on screen — in which case the caller must not navigate. False means "nothing to
 * save, go ahead".
 */
export type LeaveInterceptor = () => boolean;

type GuardApi = {
  register: (interceptor: LeaveInterceptor | null) => void;
  intercept: () => boolean;
};

const GuardContext = createContext<GuardApi | null>(null);

export function UnsavedChangesProvider({ children }: { children: React.ReactNode }) {
  // A ref, not state: registering must not re-render the whole protected subtree, and only one form
  // is ever on screen at a time so a single slot is enough.
  const interceptor = useRef<LeaveInterceptor | null>(null);

  const register = useCallback((next: LeaveInterceptor | null) => {
    interceptor.current = next;
  }, []);
  const intercept = useCallback(() => interceptor.current?.() ?? false, []);
  const value = useMemo(() => ({ register, intercept }), [register, intercept]);

  return <GuardContext.Provider value={value}>{children}</GuardContext.Provider>;
}

/**
 * For the back control. Call before navigating; if it returns true a form has raised its prompt and
 * the navigation must be abandoned.
 *
 * Returns false when there is no provider, so a back control outside the protected shell — the
 * landing and login pages — still just navigates.
 */
export function useLeaveInterceptor(): () => boolean {
  const ctx = useContext(GuardContext);
  return useCallback(() => ctx?.intercept() ?? false, [ctx]);
}

/**
 * For a form. While `dirty` is true, an attempt to leave via the back control calls `onBlocked`
 * instead of navigating — that is the form's cue to open its `UnsavedChangesDialog`.
 *
 * `dirty` and `onBlocked` are read through a ref so that typing into the form does not re-register
 * on every keystroke; the effect depends only on the context identity, which is stable.
 */
export function useLeaveGuard(dirty: boolean, onBlocked: () => void) {
  const ctx = useContext(GuardContext);
  const latest = useRef({ dirty, onBlocked });

  // Refreshed in an effect rather than assigned during render: a render can be thrown away under
  // concurrent rendering, and a ref written on a discarded render would leave the interceptor
  // reading state that never committed. No dependency array, so it tracks every commit.
  useEffect(() => {
    latest.current = { dirty, onBlocked };
  });

  useEffect(() => {
    if (!ctx) return;
    ctx.register(() => {
      if (!latest.current.dirty) return false;
      latest.current.onBlocked();
      return true;
    });
    // Unregistering on unmount is what stops a saved-and-navigated form from blocking the NEXT
    // page's back button with a prompt about work that no longer exists.
    return () => ctx.register(null);
  }, [ctx]);
}
