"use client";

/**
 * Scan a code — a session QR or code, or an artisan card. Its body is the top-bar scanner's own
 * panel (`components/scan`), so this page and the header button answer every code through one
 * dispatcher (`lib/scanDispatch.ts`, shared with the Designer Repository). Reached from Browse →
 * "Scan a code". No `ROUTE_GUARDS` row: every lookup behind it is scoped per viewer by the API.
 */

import { QrCode } from "lucide-react";

import { PageHeader } from "@/components/PageHeader";
import { ScanPanel } from "@/components/scan/ScanSheet";

export default function ScanCodePage() {
  return (
    <>
      <PageHeader
        title="Scan a code"
        description="A session code or QR, or an artisan card."
        icon={<QrCode className="h-5 w-5" aria-hidden />}
      />
      <section className="panel mx-auto max-w-2xl p-4">
        <ScanPanel />
      </section>
    </>
  );
}
