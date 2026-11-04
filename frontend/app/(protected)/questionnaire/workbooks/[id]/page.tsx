"use client";

import { useParams, useRouter } from "next/navigation";
import { useEffect } from "react";

export default function QuestionnaireWorkbookRedirect() {
  const router = useRouter();
  const params = useParams<{ id: string }>();
  useEffect(() => {
    router.replace(`/questionnaires/${encodeURIComponent(params.id)}`);
  }, [router, params.id]);
  return null;
}
