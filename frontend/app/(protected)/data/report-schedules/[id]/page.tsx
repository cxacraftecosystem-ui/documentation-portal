"use client";

import { FileSpreadsheet } from "lucide-react";
import { use } from "react";

import { ScheduledReportDownload } from "@/components/dataExplorer/ScheduledReportPanels";
import { PageHeader } from "@/components/PageHeader";

export default function ScheduledReportPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  return (
    <>
      <PageHeader title="Scheduled report" icon={<FileSpreadsheet className="h-5 w-5" aria-hidden />} />
      <ScheduledReportDownload scheduleId={id} />
    </>
  );
}
