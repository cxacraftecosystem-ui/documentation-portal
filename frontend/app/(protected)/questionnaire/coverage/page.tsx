"use client";

import { Grid3x3 } from "lucide-react";

import { PageHeader } from "@/components/PageHeader";
import { WorkshopCoverage } from "@/components/questionnaire/WorkshopCoverage";
import { qs } from "@/lib/strings/qs";

export default function QuestionnaireCoveragePage() {
  return (
    <>
      <PageHeader title={qs("coverageTitle")} description={qs("coverageDescription")} icon={<Grid3x3 className="h-5 w-5" aria-hidden />} />
      <WorkshopCoverage />
    </>
  );
}
