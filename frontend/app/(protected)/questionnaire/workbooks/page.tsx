"use client";

import { useRouter } from "next/navigation";
import { useEffect } from "react";

/**
 * `/questionnaire/workbooks` moved into the forms library (UNIFY U3): the pro-forma, workbook upload
 * and each form's workbook live on `/questionnaires` and `/questionnaires/[id]`. Old links land there.
 */
export default function QuestionnaireWorkbooksRedirect() {
  const router = useRouter();
  useEffect(() => {
    router.replace("/questionnaires");
  }, [router]);
  return null;
}
