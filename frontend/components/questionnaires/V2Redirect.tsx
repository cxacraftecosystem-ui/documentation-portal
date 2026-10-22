"use client";

import { useRouter } from "next/navigation";
import { useEffect } from "react";

import { useQuestionnaireConfig } from "@/components/questionnaire/useQuestionnaireConfig";

/**
 * Old questionnaire URLs under v2 (DESIGN §4.1): `/questionnaire/interviews/[id]` → the session,
 * `/questionnaire/consolidated/[artisanId]` → the artisan's Questionnaire tab. With v2 off the old
 * page renders as before. Returns true while a redirect is pending, so the caller draws nothing.
 */
export function useV2Redirect(target: string): boolean {
  const router = useRouter();
  const config = useQuestionnaireConfig();
  const redirect = Boolean(config?.v2Enabled);
  useEffect(() => {
    if (redirect) router.replace(target);
  }, [redirect, router, target]);
  return config === null || redirect;
}
