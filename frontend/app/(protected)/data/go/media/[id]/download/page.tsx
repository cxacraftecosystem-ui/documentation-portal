"use client";

import { use } from "react";

import { GoMediaDownload } from "@/components/dataExplorer/PortalLinkPages";

export default function GoMediaDownloadPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  return <GoMediaDownload id={decodeURIComponent(id)} />;
}
