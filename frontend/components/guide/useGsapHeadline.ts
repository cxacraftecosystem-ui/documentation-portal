"use client";

import { useEffect, useRef } from "react";

import type { gsap as GsapNamespace } from "gsap";

export function useGsapHeadline<T extends HTMLElement>(reduce: boolean) {
  const ref = useRef<T>(null);

  useEffect(() => {
    const node = ref.current;
    if (!node) return;

    if (!node.dataset.split) {
      const words = (node.textContent ?? "").split(/\s+/).filter(Boolean);
      node.textContent = "";
      words.forEach((word, index) => {
        const span = document.createElement("span");
        span.textContent = word;
        span.style.display = "inline-block";
        span.style.willChange = "transform, opacity";
        node.appendChild(span);
        if (index < words.length - 1) node.appendChild(document.createTextNode(" "));
      });
      node.dataset.split = "true";
    }

    const words = Array.from(node.querySelectorAll("span"));
    if (reduce || words.length === 0) {
      words.forEach((word) => {
        word.style.transform = "";
        word.style.opacity = "";
      });
      return;
    }

    let timeline: ReturnType<typeof GsapNamespace.timeline> | null = null;
    let cancelled = false;

    import("gsap").then(({ gsap }) => {
      if (cancelled) return;
      timeline = gsap.timeline({ defaults: { ease: "power3.out", duration: 0.62 } });
      words.forEach((word, index) => {
        timeline!.fromTo(
          word,
          { yPercent: 108, opacity: 0, rotate: 1.5 },
          { yPercent: 0, opacity: 1, rotate: 0 },
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
