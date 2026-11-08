"use client";

import { useEffect, useState } from "react";

export const E2E_FAULT_KEY = "portal_e2e_fault";

export type E2eFault = "render" | "global" | null;

export function useE2eFault(): { ready: boolean; fault: E2eFault } {
  const [state, setState] = useState<{ ready: boolean; fault: E2eFault }>({ ready: false, fault: null });
  useEffect(() => {
    let value: string | null = null;
    try {
      value = window.localStorage.getItem(E2E_FAULT_KEY);
    } catch {
      value = null;
    }
    setState({ ready: true, fault: value === "render" || value === "global" ? value : null });
  }, []);
  return state;
}

export function e2eRenderFault(): Error {
  return Object.assign(new Error("Forced render fault"), { digest: "e2e-4f2a9c1d" });
}
