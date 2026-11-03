"use client";

import { useRouter } from "next/navigation";
import { useEffect } from "react";

export default function QuestionnaireWorkbooksRedirect() {
  const router = useRouter();
  useEffect(() => {
    router.replace("/questionnaires");
  }, [router]);
  return null;
}
