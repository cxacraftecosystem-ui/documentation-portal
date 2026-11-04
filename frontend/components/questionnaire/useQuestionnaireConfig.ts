"use client";

import { useEffect, useState } from "react";

import { CONFIG_FALLBACK, questionnaireApi, type QuestionnaireConfig } from "@/lib/questionnaire/api";

let cached: QuestionnaireConfig | null = null;
let inflight: Promise<QuestionnaireConfig> | null = null;

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

export function useQuestionnaireConfig(): QuestionnaireConfig | null {
  const [config, setConfig] = useState<QuestionnaireConfig | null>(cached);
  useEffect(() => {
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
