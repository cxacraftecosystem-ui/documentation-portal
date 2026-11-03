"use client";

import { useRouter } from "next/navigation";
import { useEffect, type ReactNode } from "react";

import { useQuestionnaireConfig } from "@/components/questionnaire/useQuestionnaireConfig";
import { qs } from "@/lib/questionnaire/qs";
import { FORMS_HREF } from "@/lib/questionnairePortal";

export function V2Gate({ children }: { children: ReactNode }) {
  const router = useRouter();
  const config = useQuestionnaireConfig();
  const off = config !== null && !config.v2Enabled;
  useEffect(() => {
    if (off) router.replace(FORMS_HREF);
  }, [off, router]);
  if (config === null || off) return <div className="panel p-4 text-sm text-ink-500">{qs("loading")}</div>;
  return <>{children}</>;
}
