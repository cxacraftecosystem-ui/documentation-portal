"use client";

import { useParams } from "next/navigation";

import { SessionReadView } from "@/components/questionnaire/SessionReadView";

export default function SessionViewPage() {
  const params = useParams<{ id: string }>();
  return <SessionReadView id={decodeURIComponent(params.id)} />;
}
