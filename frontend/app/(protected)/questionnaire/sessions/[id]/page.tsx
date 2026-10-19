"use client";

import { useParams } from "next/navigation";

import { SessionWorkspace } from "@/components/questionnaire/SessionWorkspace";

/** The capture workspace. The id is the server's, or the clientKey of a session still on this device. */
export default function SessionWorkspacePage() {
  const params = useParams<{ id: string }>();
  return <SessionWorkspace id={decodeURIComponent(params.id)} />;
}
