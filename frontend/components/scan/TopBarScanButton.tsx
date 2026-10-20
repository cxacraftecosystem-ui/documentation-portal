"use client";

/**
 * The scan button at the leading edge of the mobile/tablet header (REQUIREMENTS_2026-10-20 §6.2).
 * Shown for every signed-in user, whether or not artisan cards are on. Frozen signature: the nav
 * streams render `<TopBarScanButton />` and nothing else about it.
 */

import { useState } from "react";
import { ScanLine } from "lucide-react";

import { ScanSheet } from "@/components/scan/ScanSheet";

export function TopBarScanButton({ className }: { className?: string }) {
  const [open, setOpen] = useState(false);
  return (
    <>
      <button
        type="button"
        aria-label="Scan a code"
        title="Scan a code"
        data-testid="top-bar-scan"
        onClick={(event) => {
          // The header pill opens search on any press; the scan button is its own target.
          event.stopPropagation();
          setOpen(true);
        }}
        className={
          "grid h-12 w-12 place-items-center rounded-full text-purple-700 transition hover:bg-purple-50" +
          (className ? ` ${className}` : "")
        }
      >
        <ScanLine className="h-5 w-5" aria-hidden />
      </button>
      <ScanSheet open={open} onClose={() => setOpen(false)} />
    </>
  );
}
