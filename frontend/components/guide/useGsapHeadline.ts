"use client";

/**
 * The walkthrough headline's entrance, built as a GSAP timeline.
 *
 * WHY GSAP HERE AND FRAMER-MOTION EVERYWHERE ELSE. The rest of this page is declarative — a
 * component enters, a value tracks scroll, an element springs on hover — and framer-motion states
 * all of that better than an imperative library would. What it does NOT state well is a timeline
 * whose tweens deliberately OVERLAP: framer's `staggerChildren` is a fixed delay between siblings
 * that each run their own transition, so every word's rise begins only after the previous word's
 * delay has elapsed. What the headline wants is each word starting while the one before it is
 * still moving — a negative relative offset — which is a timeline primitive, and GSAP's
 * position parameter (`"<0.28"`) expresses it in one argument.
 *
 * So GSAP owns exactly one thing on this page: this timeline. Reaching for it anywhere else would
 * mean two animation systems fighting over the same properties.
 *
 * ACCESSIBILITY. Under reduced motion — the OS preference OR the app's own Settings toggle — no
 * timeline is built at all and the words are left in their final position, rather than being
 * animated quickly. The words are wrapped in spans by this hook, so the heading's text content and
 * accessible name are unchanged; a screen reader reads the sentence, not the pieces.
 */

import { useEffect, useRef } from "react";

import type { gsap as GsapNamespace } from "gsap";

export function useGsapHeadline<T extends HTMLElement>(reduce: boolean) {
  const ref = useRef<T>(null);

  useEffect(() => {
    const node = ref.current;
    if (!node) return;

    // Split into words ONCE. Re-splitting on every run would nest spans inside spans.
    if (!node.dataset.split) {
      const words = (node.textContent ?? "").split(/\s+/).filter(Boolean);
      node.textContent = "";
      words.forEach((word, index) => {
        const span = document.createElement("span");
        span.textContent = word;
        // inline-block so a transform applies; the trailing space stays OUTSIDE the span so the
        // line still breaks and copies as normal prose.
        span.style.display = "inline-block";
        span.style.willChange = "transform, opacity";
        node.appendChild(span);
        if (index < words.length - 1) node.appendChild(document.createTextNode(" "));
      });
      node.dataset.split = "true";
    }

    const words = Array.from(node.querySelectorAll("span"));
    if (reduce || words.length === 0) {
      // Leave them exactly as rendered. No timeline, no flash of transformed text.
      words.forEach((word) => {
        word.style.transform = "";
        word.style.opacity = "";
      });
      return;
    }

    let timeline: ReturnType<typeof GsapNamespace.timeline> | null = null;
    let cancelled = false;

    // Dynamic import: GSAP is ~70 KB and only this one component needs it, so it must not sit in
    // the bundle every protected page loads.
    import("gsap").then(({ gsap }) => {
      if (cancelled) return;
      timeline = gsap.timeline({ defaults: { ease: "power3.out", duration: 0.62 } });
      words.forEach((word, index) => {
        timeline!.fromTo(
          word,
          { yPercent: 108, opacity: 0, rotate: 1.5 },
          { yPercent: 0, opacity: 1, rotate: 0 },
          // The overlap this hook exists for: each word starts 0.28s after the previous STARTED,
          // while the previous is still 0.34s from finishing.
          index === 0 ? 0 : "<0.28"
        );
      });
    });

    return () => {
      cancelled = true;
      timeline?.kill();
    };
  }, [reduce]);

  return ref;
}
