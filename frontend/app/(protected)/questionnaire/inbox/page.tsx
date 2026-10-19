"use client";

import { Inbox } from "lucide-react";

import { InboxList } from "@/components/questionnaire/InboxList";
import { PageHeader } from "@/components/PageHeader";
import { qs } from "@/lib/strings/qs";

export default function QuestionnaireInboxPage() {
  return (
    <>
      <PageHeader title={qs("inboxTitle")} icon={<Inbox className="h-5 w-5" aria-hidden />} />
      <InboxList />
    </>
  );
}
