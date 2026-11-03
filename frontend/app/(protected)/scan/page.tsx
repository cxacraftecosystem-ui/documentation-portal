"use client";

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
