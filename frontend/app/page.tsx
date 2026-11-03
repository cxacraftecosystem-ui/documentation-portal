import type { Metadata } from "next";

import { fetchCorpusCensus } from "@/components/hero/corpusCensus";
import HeroLanding from "@/components/hero/HeroLanding";

export const metadata: Metadata = {
  title: { absolute: "Field Repository — The interview ends. The knowledge is already preserved." },
  description:
    "Field documentation for artisan crafts — interviews transcribed and translated, records linked, reviewed and exported. Works offline on Android and the web."
};

export default async function Home() {
  const census = await fetchCorpusCensus(process.env.NEXT_PUBLIC_API_URL ?? "");
  return <HeroLanding census={census} />;
}
