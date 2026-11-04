"use client";

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
