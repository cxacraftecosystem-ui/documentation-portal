"use client";

import { useParams, useRouter } from "next/navigation";
import { useEffect } from "react";

/** An old `/questionnaire/workbooks/[id]` link lands on the form in the library (UNIFY U3). */
export default function QuestionnaireWorkbookRedirect() {
  const router = useRouter();
  const params = useParams<{ id: string }>();
  useEffect(() => {
    router.replace(`/questionnaires/${encodeURIComponent(params.id)}`);
  }, [router, params.id]);
  return null;
}
