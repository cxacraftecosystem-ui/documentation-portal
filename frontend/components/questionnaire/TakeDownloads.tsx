"use client";

import { useEffect, useRef } from "react";

import { DownloadsTray } from "@/components/dataExplorer/DownloadsTray";
import { useDownloads } from "@/components/dataExplorer/useDownloads";
import type { DownloadRequest } from "@/lib/dataExplorer/downloads";

export default function TakeDownloads({ requests }: { requests: DownloadRequest[] }) {
  const downloads = useDownloads();
  const sent = useRef(0);
  const { enqueue } = downloads;
  useEffect(() => {
    for (const request of requests.slice(sent.current)) enqueue(request);
    sent.current = requests.length;
  }, [requests, enqueue]);
  return <DownloadsTray downloads={downloads} />;
}
