"use client";

import { createContext, useCallback, useContext, useEffect, useMemo, useRef } from "react";

export type LeaveInterceptor = () => boolean;

type GuardApi = {
  register: (interceptor: LeaveInterceptor | null) => void;
  intercept: () => boolean;
};

const GuardContext = createContext<GuardApi | null>(null);

export function UnsavedChangesProvider({ children }: { children: React.ReactNode }) {
  const interceptor = useRef<LeaveInterceptor | null>(null);

  const register = useCallback((next: LeaveInterceptor | null) => {
    interceptor.current = next;
  }, []);
  const intercept = useCallback(() => interceptor.current?.() ?? false, []);
  const value = useMemo(() => ({ register, intercept }), [register, intercept]);

  return <GuardContext.Provider value={value}>{children}</GuardContext.Provider>;
}

export function useLeaveInterceptor(): () => boolean {
  const ctx = useContext(GuardContext);
  return useCallback(() => ctx?.intercept() ?? false, [ctx]);
}

export function useLeaveGuard(dirty: boolean, onBlocked: () => void) {
  const ctx = useContext(GuardContext);
  const latest = useRef({ dirty, onBlocked });

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
    return () => ctx.register(null);
  }, [ctx]);
}
