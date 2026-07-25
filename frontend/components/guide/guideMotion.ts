import type { Transition, Variants } from "framer-motion";

/**
 * Shared motion vocabulary for the walkthrough.
 *
 * Every factory here takes the `reduce` flag from `useAppReducedMotion()` — the OS
 * `prefers-reduced-motion` query OR the app's own Settings toggle — and collapses to a
 * zero-duration, zero-displacement version of itself when it is set. Gating happens at the
 * source rather than at each call site so a new animation in this folder cannot accidentally
 * ship without honouring the preference. Never call framer-motion's `useReducedMotion()`
 * directly here: it can only see the OS half.
 *
 * The easing curve `[0.16, 1, 0.3, 1]` is the design system's `ease-out`
 * (tailwind.config.ts → transitionTimingFunction.out); the spring below matches the feel of
 * the `ease-spring` curve used elsewhere in the app.
 */
export const EASE_OUT = [0.16, 1, 0.3, 1] as const;

/** The one spring used for every interactive/press response in the guide. */
export function springy(reduce: boolean): Transition {
  return reduce ? { duration: 0 } : { type: "spring", stiffness: 380, damping: 30, mass: 0.7 };
}

/** A softer spring for layout changes (a card growing when it expands). */
export function layoutSpring(reduce: boolean): Transition {
  return reduce ? { duration: 0 } : { type: "spring", stiffness: 260, damping: 32, mass: 0.9 };
}

/**
 * Parent variant: holds children back and releases them one after another.
 * `delayChildren` gives the card itself a moment to settle before its contents arrive.
 */
export function staggerParent(reduce: boolean, stagger = 0.06): Variants {
  return {
    hidden: {},
    show: {
      transition: reduce ? { duration: 0 } : { staggerChildren: stagger, delayChildren: 0.05 }
    }
  };
}

/** Child variant: the standard rise-and-fade used for every revealed element. */
export function riseItem(reduce: boolean, distance = 14): Variants {
  return {
    hidden: { opacity: 0, y: reduce ? 0 : distance },
    show: {
      opacity: 1,
      y: 0,
      transition: { duration: reduce ? 0 : 0.5, ease: EASE_OUT }
    }
  };
}

/** Horizontal variant — used where a rise would fight the reading direction (chips, rails). */
export function slideItem(reduce: boolean, distance = 12): Variants {
  return {
    hidden: { opacity: 0, x: reduce ? 0 : -distance },
    show: {
      opacity: 1,
      x: 0,
      transition: { duration: reduce ? 0 : 0.4, ease: EASE_OUT }
    }
  };
}

/**
 * Scroll a step into view. Smooth scrolling is itself motion, so it is gated too — under either
 * reduced-motion switch the jump is instant. The `scroll-mt-*` on each card supplies the
 * clearance for the floating header pill.
 */
export function scrollToStep(id: string, reduce: boolean): void {
  if (typeof document === "undefined") return;
  document.getElementById(id)?.scrollIntoView({ behavior: reduce ? "auto" : "smooth", block: "start" });
}

/** Enter/exit for content swapped inside an AnimatePresence (the rail's active-step readout). */
export function swapVariants(reduce: boolean): Variants {
  return {
    hidden: { opacity: 0, y: reduce ? 0 : 8 },
    show: { opacity: 1, y: 0, transition: { duration: reduce ? 0 : 0.28, ease: EASE_OUT } },
    exit: { opacity: 0, y: reduce ? 0 : -8, transition: { duration: reduce ? 0 : 0.16, ease: "easeIn" } }
  };
}
