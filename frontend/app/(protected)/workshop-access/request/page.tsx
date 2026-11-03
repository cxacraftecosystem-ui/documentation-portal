"use client";

import { KeyRound } from "lucide-react";

import { PageHeader } from "@/components/PageHeader";
import { WorkshopAccessRequestPanel } from "@/components/settings/WorkshopAccessRequestPanel";

export default function WorkshopAccessRequestPage() {
  return (
    <>
      <PageHeader
        title="Workshop access"
        description="Request access and track your requests."
        icon={<KeyRound className="h-5 w-5" aria-hidden />}
      />
      <WorkshopAccessRequestPanel />
    </>
  );
}
