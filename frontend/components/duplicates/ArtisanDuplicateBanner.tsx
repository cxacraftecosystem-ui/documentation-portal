"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { Users } from "lucide-react";

import { mergeHref, openPairFor, type DuplicatePair } from "@/lib/duplicates";

/** Admins only: an open likely-duplicate pair names this artisan. One line and a way to review it. */
export function ArtisanDuplicateBanner({ artisanId }: { artisanId: string }) {
  const [pair, setPair] = useState<DuplicatePair | null>(null);

  useEffect(() => {
    let cancelled = false;
    setPair(null);
    openPairFor(artisanId)
      .then((found) => {
        if (!cancelled) setPair(found);
      })
      .catch(() => undefined);
    return () => {
      cancelled = true;
    };
  }, [artisanId]);

  const href = pair ? mergeHref(pair) : null;
  if (!pair || !href) return null;
  return (
    <div
      role="status"
      className="mb-4 flex flex-wrap items-center justify-between gap-2 rounded-md border border-amber-500/40 bg-amber-100 px-3 py-2 text-sm text-amber-800"
    >
      <span className="flex items-center gap-2 font-medium">
        <Users className="h-4 w-4" aria-hidden />
        Possible duplicate
      </span>
      <Link className="font-semibold underline" href={href}>
        Review
      </Link>
    </div>
  );
}
