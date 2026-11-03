"use client";

import { useParams } from "next/navigation";

import { SessionWorkspace } from "@/components/questionnaire/SessionWorkspace";

export default function SessionWorkspacePage() {
  const params = useParams<{ id: string }>();
  return <SessionWorkspace id={decodeURIComponent(params.id)} />;
}
