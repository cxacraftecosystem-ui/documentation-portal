import type { Metadata } from "next";
import type { ReactNode } from "react";

export const metadata: Metadata = {
  title: "Live report",
  referrer: "no-referrer",
  robots: { index: false, follow: false }
};

export default function ReportShareLayout({ children }: { children: ReactNode }) {
  return children;
}
