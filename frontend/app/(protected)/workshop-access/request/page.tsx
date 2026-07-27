"use client";

import { KeyRound } from "lucide-react";

import { PageHeader } from "@/components/PageHeader";
import { WorkshopAccessRequestPanel } from "@/components/settings/WorkshopAccessRequestPanel";

/**
 * /workshop-access/request — the page every signed-in account owns, whatever their tier.
 *
 * Ungated on purpose, and the API agrees: `GET /workshops/requestable`, `POST
 * /workshops/access-requests` and `GET /workshops/access-requests/mine` all ask for nothing but
 * `get_current_user`. Asking is not an admin act — a crowdsource volunteer on their first day needs
 * this page more than an admin does, since an admin can simply grant themselves the row.
 *
 * The panel is the one already on /settings, rendered here unchanged so there is a single
 * implementation of the request form and the "Your requests" history behind it — Android's
 * "Request workshop access" and "My workshop access" cards, in the same order.
 */
export default function WorkshopAccessRequestPage() {
  return (
    <>
      <PageHeader
        title="Workshop access"
        description="Ask to work in a workshop, and follow what happened to every request you have made."
        icon={<KeyRound className="h-5 w-5" aria-hidden />}
      />
      <WorkshopAccessRequestPanel />
    </>
  );
}
