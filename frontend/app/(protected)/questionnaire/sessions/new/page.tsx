"use client";

import { Mic } from "lucide-react";
import { useSearchParams } from "next/navigation";
import { Suspense } from "react";

import { PageHeader } from "@/components/PageHeader";
import { SessionStartSheet } from "@/components/questionnaire/SessionStartSheet";
import { qs } from "@/lib/strings/qs";

/** The start sheet (DESIGN §4.2). `?artisanId=` (repeatable) preselects from the artisan record. */
export default function NewSessionPage() {
  return (
    <>
      <PageHeader title={qs("startSheetTitle")} icon={<Mic className="h-5 w-5" aria-hidden />} />
      <Suspense fallback={<div className="panel p-4 text-sm text-ink-500">{qs("loading")}</div>}>
        <Body />
      </Suspense>
    </>
  );
}

function Body() {
  const params = useSearchParams();
  return <SessionStartSheet initialArtisanIds={params.getAll("artisanId").filter(Boolean)} />;
}
