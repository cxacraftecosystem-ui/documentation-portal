"use client";

import { Compass } from "lucide-react";

import { GuideHero } from "@/components/guide/GuideHero";
import { GuideJourney } from "@/components/guide/GuideJourney";
import { GuideOutro } from "@/components/guide/GuideOutro";
import { scrollToStep } from "@/components/guide/guideMotion";
import { GUIDE_STEPS } from "@/components/guide/steps";
import { useAppReducedMotion } from "@/components/guide/useAppReducedMotion";
import { PageHeader } from "@/components/PageHeader";

export default function GuidePage() {
  const reduce = useAppReducedMotion();

  return (
    <>
      <PageHeader
        title="Walkthrough"
        description="Documenting a craft, step by step."
        icon={<Compass className="h-5 w-5" aria-hidden />}
      />

      <GuideHero stepCount={GUIDE_STEPS.length} onStart={() => scrollToStep(GUIDE_STEPS[0].id, reduce)} />

      <GuideJourney />

      <GuideOutro />
    </>
  );
}
