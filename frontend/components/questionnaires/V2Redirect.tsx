"use client";

import { useRouter } from "next/navigation";
import { useEffect } from "react";

import { useQuestionnaireConfig } from "@/components/questionnaire/useQuestionnaireConfig";

export function useV2Redirect(target: string): boolean {
  const router = useRouter();
  const config = useQuestionnaireConfig();
  const redirect = Boolean(config?.v2Enabled);
  useEffect(() => {
    if (redirect) router.replace(target);
  }, [redirect, router, target]);
  return config === null || redirect;
}
