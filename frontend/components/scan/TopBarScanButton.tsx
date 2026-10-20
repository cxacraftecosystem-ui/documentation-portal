"use client";

import Link from "next/link";
import type { JSX } from "react";
import { ScanQrCode } from "lucide-react";

/**
 * The search header's scan button — PLACEHOLDER owned by the nav stream until the QR stream lands
 * its own file (REQUIREMENTS_2026-10-20 §8.2, item 3). It carries the frozen signature exactly and
 * opens the `/scan` page; the QR stream's version opens `ScanSheet` in place and replaces this file
 * whole at integration.
 */
export function TopBarScanButton({ className }: { className?: string }): JSX.Element {
  return (
    <Link
      href="/scan"
      aria-label="Scan a code"
      title="Scan a code"
      className={
        "inline-flex h-12 w-12 items-center justify-center rounded-full text-purple-700 transition hover:bg-purple-50 " +
        (className ?? "")
      }
    >
      <ScanQrCode className="h-5 w-5" aria-hidden />
    </Link>
  );
}
