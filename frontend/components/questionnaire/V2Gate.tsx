"use client";

import { useRouter } from "next/navigation";
import { useEffect, type ReactNode } from "react";

import { useQuestionnaireConfig } from "@/components/questionnaire/useQuestionnaireConfig";
import { qs } from "@/lib/questionnaire/qs";
import { FORMS_HREF } from "@/lib/questionnairePortal";

/**
 * The session screens draw only while the server serves them (`GET /questionnaire/config`,
 * `v2Enabled`). With `QUESTIONNAIRE_V2` off there are no sessions, and the forms library is where the
 * work is, so the page goes there instead of drawing a list that can never fill.
 */
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
