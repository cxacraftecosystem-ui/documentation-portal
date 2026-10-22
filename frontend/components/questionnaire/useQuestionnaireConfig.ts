"use client";

import { useEffect, useState } from "react";

import { CONFIG_FALLBACK, questionnaireApi, type QuestionnaireConfig } from "@/lib/questionnaire/api";

let cached: QuestionnaireConfig | null = null;
let inflight: Promise<QuestionnaireConfig> | null = null;

/** Reads `GET /questionnaire/config` once per page load. */
export function loadQuestionnaireConfig(): Promise<QuestionnaireConfig> {
  if (cached) return Promise.resolve(cached);
  inflight ??= questionnaireApi
    .config()
    .then((config) => (cached = { ...CONFIG_FALLBACK, ...config }))
    .catch(() => CONFIG_FALLBACK)
    .finally(() => {
      inflight = null;
    });
  return inflight;
}

/**
 * `GET /questionnaire/config`, read once. `null` while asking; a server without the route (v2 not
 * deployed) answers the fallback, which keeps the old screens and treats transcription as paused.
 */
export function useQuestionnaireConfig(): QuestionnaireConfig | null {
  const [config, setConfig] = useState<QuestionnaireConfig | null>(cached);
  useEffect(() => {
    // The answer may have landed between this render and this effect (another screen asked): take
    // it now, or this one stays `null` for good.
    if (cached) {
      const known = cached;
      setConfig((current) => current ?? known);
      return;
    }
    let cancelled = false;
    void loadQuestionnaireConfig().then((value) => {
      if (!cancelled) setConfig(value);
    });
    return () => {
      cancelled = true;
    };
  }, []);
  return config;
}
