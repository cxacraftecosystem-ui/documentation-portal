"use client";

import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";

import { useQuestionnaireConfig } from "@/components/questionnaire/useQuestionnaireConfig";
import { questionnaireApi } from "@/lib/questionnaire/api";

export function sessionViewHref(id: string): string {
  return `/questionnaire/sessions/${encodeURIComponent(id)}/view`;
}

export async function interviewDestination(id: string, v2Enabled: boolean, loadSession: (id: string) => Promise<unknown>): Promise<string | null> {
  if (!v2Enabled) return null;
  try {
    await loadSession(id);
    return sessionViewHref(id);
  } catch {
    return null;
  }
}

export type InterviewRoute = "resolving" | "record";

export function useInterviewRoute(id: string): InterviewRoute {
  const router = useRouter();
  const config = useQuestionnaireConfig();
  const [route, setRoute] = useState<{ id: string; value: InterviewRoute } | null>(null);
  useEffect(() => {
    if (!config) return;
    let cancelled = false;
    void interviewDestination(id, Boolean(config.v2Enabled), (sessionId) => questionnaireApi.getSession(sessionId)).then((target) => {
      if (cancelled) return;
      if (target) router.replace(target);
      else setRoute({ id, value: "record" });
    });
    return () => {
      cancelled = true;
    };
  }, [config, id, router]);
  return route && route.id === id ? route.value : "resolving";
}
