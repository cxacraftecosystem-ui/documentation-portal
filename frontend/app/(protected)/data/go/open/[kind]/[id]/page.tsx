"use client";

import { use } from "react";

import { GoOpenRecord } from "@/components/dataExplorer/PortalLinkPages";

export default function GoOpenPage({ params }: { params: Promise<{ kind: string; id: string }> }) {
  const { kind, id } = use(params);
  return <GoOpenRecord kind={decodeURIComponent(kind)} id={decodeURIComponent(id)} />;
}
