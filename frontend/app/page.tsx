import type { Metadata } from "next";

import HeroLanding from "@/components/hero/HeroLanding";

/**
 * The public landing page. Deliberately a server component with no data fetching so the route stays
 * statically prerendered — HeroLanding is the client island beneath it, and nothing here opts the
 * page into dynamic rendering.
 */
export const metadata: Metadata = {
  title: { absolute: "Field Repository — The interview ends. The knowledge is already preserved." },
  description:
    "A field documentation repository for artisan crafts: artisans, crafts, products, processes, tools, workshops, structured interviews with automatic transcription and translation, media, a peer-review ladder, tiered sharing, and research-ready export — captured offline, in the field."
};

export default function Home() {
  return <HeroLanding />;
}
