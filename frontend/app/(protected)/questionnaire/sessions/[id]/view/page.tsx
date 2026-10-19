"use client";

import { useParams } from "next/navigation";

import { SessionReadView } from "@/components/questionnaire/SessionReadView";

/** A session, read-only, with the review controls for a reviewer (DESIGN §4.8). */
export default function SessionViewPage() {
  const params = useParams<{ id: string }>();
  return <SessionReadView id={decodeURIComponent(params.id)} />;
}
