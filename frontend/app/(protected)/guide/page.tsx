"use client";

import { Compass } from "lucide-react";

import { GuideHero } from "@/components/guide/GuideHero";
import { GuideJourney } from "@/components/guide/GuideJourney";
import { GuideOutro } from "@/components/guide/GuideOutro";
import { scrollToStep } from "@/components/guide/guideMotion";
import { GUIDE_STEPS } from "@/components/guide/steps";
import { useAppReducedMotion } from "@/components/guide/useAppReducedMotion";
import { PageHeader } from "@/components/PageHeader";

/**
 * Walkthrough — the in-app guide that teaches a new researcher the entire documentation
 * process, in the order it happens in the field.
 *
 * The page is a single scroll: an opening band, the ten steps threaded onto a scroll-linked
 * spine (`components/guide/GuideJourney`), and a closing checklist. Every step names the real
 * screen it teaches, uses the Android-parity feature name for it, and links straight there —
 * so the guide is a launcher as well as a lesson.
 *
 * The same content in prose lives at `docs/WALKTHROUGH.md`, for handing to a researcher who is
 * not sitting at a screen.
 */
export default function GuidePage() {
  // Both switches, OR-ed: the OS media query and the app's own Settings toggle. Smooth scrolling
  // is motion too, so "Start at step 1" jumps instantly under either.
  const reduce = useAppReducedMotion();

  return (
    <>
      <PageHeader
        title="Walkthrough"
        description="How a craft gets documented, from opening a workshop to exporting the dataset. Ten steps, in the order you do them."
        icon={<Compass className="h-5 w-5" aria-hidden />}
      />

      <GuideHero stepCount={GUIDE_STEPS.length} onStart={() => scrollToStep(GUIDE_STEPS[0].id, reduce)} />

      <GuideJourney />

      <GuideOutro />
    </>
  );
}
