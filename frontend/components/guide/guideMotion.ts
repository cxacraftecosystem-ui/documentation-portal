import type { Transition, Variants } from "framer-motion";

export const EASE_OUT = [0.16, 1, 0.3, 1] as const;

export function springy(reduce: boolean): Transition {
  return reduce ? { duration: 0 } : { type: "spring", stiffness: 380, damping: 30, mass: 0.7 };
}

export function layoutSpring(reduce: boolean): Transition {
  return reduce ? { duration: 0 } : { type: "spring", stiffness: 260, damping: 32, mass: 0.9 };
}

export function staggerParent(reduce: boolean, stagger = 0.06): Variants {
  return {
    hidden: {},
    show: {
      transition: reduce ? { duration: 0 } : { staggerChildren: stagger, delayChildren: 0.05 }
    }
  };
}

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

export function scrollToStep(id: string, reduce: boolean): void {
  if (typeof document === "undefined") return;
  document.getElementById(id)?.scrollIntoView({ behavior: reduce ? "auto" : "smooth", block: "start" });
}

export function swapVariants(reduce: boolean): Variants {
  return {
    hidden: { opacity: 0, y: reduce ? 0 : 8 },
    show: { opacity: 1, y: 0, transition: { duration: reduce ? 0 : 0.28, ease: EASE_OUT } },
    exit: { opacity: 0, y: reduce ? 0 : -8, transition: { duration: reduce ? 0 : 0.16, ease: "easeIn" } }
  };
}
