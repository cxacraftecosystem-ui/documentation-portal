import { useEffect, useState } from "react";

import { ApiError, apiFetchWithHeaders } from "@/lib/api";
import { BUNDLED_RULES, pickRules, type CompletenessRules } from "@/lib/completenessCore";

export * from "@/lib/completenessCore";

/**
 * The rules in force on this browser — the pure evaluator lives in `completenessCore.ts`, so a unit
 * spec can run it without the API client.
 */
const STORE_KEY = "field_repo_completeness_rules";

type Stored = { etag: string; rules: CompletenessRules };

function readStored(): Stored | null {
  try {
    const raw = window.localStorage.getItem(STORE_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as Stored;
    return parsed && typeof parsed.etag === "string" && parsed.rules ? parsed : null;
  } catch {
    return null;
  }
}

function writeStored(value: Stored) {
  try {
    window.localStorage.setItem(STORE_KEY, JSON.stringify(value));
  } catch {
    // Blocked storage: the bundled table still answers.
  }
}

let current: CompletenessRules = BUNDLED_RULES;
let refresh: Promise<CompletenessRules> | null = null;

async function refreshRules(): Promise<CompletenessRules> {
  const stored = readStored();
  if (stored) current = pickRules(BUNDLED_RULES, stored.rules);
  try {
    const headers: Record<string, string> = stored ? { "If-None-Match": stored.etag } : {};
    const answer = await apiFetchWithHeaders<CompletenessRules>("/records/completeness-rules", { headers });
    const etag = answer.headers.get("etag");
    if (etag) writeStored({ etag, rules: answer.body });
    current = pickRules(BUNDLED_RULES, answer.body);
  } catch (err) {
    // 304 (unchanged), offline, or signed out: what is stored, or the bundled copy, stands.
    if (!(err instanceof ApiError) || err.status !== 304) return current;
  }
  return current;
}

/** The rules in force: the bundled copy, swapped for the server's when its version is higher. */
export function useCompletenessRules(): CompletenessRules {
  const [rules, setRules] = useState<CompletenessRules>(current);
  useEffect(() => {
    let cancelled = false;
    refresh ??= refreshRules();
    void refresh.then((next) => {
      if (!cancelled) setRules(next);
    });
    return () => {
      cancelled = true;
    };
  }, []);
  return rules;
}
