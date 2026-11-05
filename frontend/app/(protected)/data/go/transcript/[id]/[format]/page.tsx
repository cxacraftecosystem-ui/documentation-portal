"use client";

import { use } from "react";

import { GoTranscriptFile } from "@/components/dataExplorer/PortalLinkPages";

export default function GoTranscriptPage({ params }: { params: Promise<{ id: string; format: string }> }) {
  const { id, format } = use(params);
  return <GoTranscriptFile id={decodeURIComponent(id)} format={decodeURIComponent(format)} />;
}
