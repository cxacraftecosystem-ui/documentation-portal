"use client";

import Link from "next/link";
import { AnimatePresence, motion } from "framer-motion";
import { ArrowUpRight, ChevronDown, Lightbulb, ListChecks, TriangleAlert } from "lucide-react";

import { layoutSpring, riseItem, slideItem, springy, staggerParent } from "@/components/guide/guideMotion";
import type { GuideStep } from "@/components/guide/steps";
import { useAppReducedMotion } from "@/components/guide/useAppReducedMotion";

/**
 * One step of the walkthrough.
 *
 * Three animations are doing real work here:
 *  - the card's contents stagger in the first time it scrolls into view, so the eye lands on
 *    the number, then the name, then the summary — the reading order, enforced in time;
 *  - `layout` on the list item lets the card grow into its expanded height instead of the
 *    page snapping, which keeps the reader's place on the spine;
 *  - `AnimatePresence` runs the detail panel's exit, so collapsing one card and opening
 *    another reads as a handover rather than a flicker.
 *
 * `onEnterView` reports upward when the card reaches the middle band of the viewport; the
 * page uses that to drive the rail. Reveal and tracking need different viewport thresholds,
 * so they live on two different elements.
 *
 * The card is deliberately NOT `overflow-hidden`. The toggle is a full-width button flush with
 * the card's edges, and the global keyboard focus ring (app/globals.css) is an `outline` drawn
 * OUTSIDE the border box at `outline-offset: 2px` — clipping the card would erase that ring on
 * three sides and leave the primary control of every step looking unfocused. The only thing that
 * ever needed clipping is the collapsible panel, which clips itself.
 */
export function GuideStepCard({
  step,
  index,
  expanded,
  onToggle,
  onEnterView
}: {
  step: GuideStep;
  index: number;
  expanded: boolean;
  onToggle: () => void;
  onEnterView: () => void;
}) {
  const reduce = useAppReducedMotion();
  const panelId = `guide-panel-${step.id}`;
  const Icon = step.icon;

  return (
    <motion.li
      id={step.id}
      layout={reduce ? false : "position"}
      transition={layoutSpring(reduce)}
      // Fires when the card occupies the middle band of the viewport — the same moment a
      // reader would say "I'm on this step now".
      viewport={{ margin: "-45% 0px -45% 0px", amount: "some" }}
      onViewportEnter={onEnterView}
      className="relative scroll-mt-28 pl-12 sm:pl-16"
    >
      {/* Spine node. Filled once the step has been reached, hollow before that. */}
      <motion.span
        aria-hidden
        initial={false}
        animate={{ scale: expanded && !reduce ? 1.12 : 1 }}
        transition={springy(reduce)}
        className="absolute left-4 top-6 z-10 grid h-8 w-8 -translate-x-1/2 place-items-center rounded-full grad-brand font-display text-xs font-bold text-white shadow-md sm:left-6"
      >
        {index + 1}
      </motion.span>

      <motion.div
        variants={staggerParent(reduce)}
        initial="hidden"
        whileInView="show"
        viewport={{ once: true, amount: 0.25 }}
        className="rounded-lg border border-line-200 bg-card shadow-sm"
      >
        <motion.button
          type="button"
          onClick={onToggle}
          aria-expanded={expanded}
          // Only while the panel is actually mounted: `AnimatePresence` removes it on collapse, and
          // an aria-controls pointing at an id that is not in the document is worse than none —
          // it tells a screen reader there is somewhere to go and then loses it.
          aria-controls={expanded ? panelId : undefined}
          whileHover={reduce ? undefined : { y: -2 }}
          whileTap={reduce ? undefined : { scale: 0.995 }}
          transition={springy(reduce)}
          className="flex w-full items-start gap-4 p-5 text-left"
        >
          <motion.span
            variants={riseItem(reduce, 10)}
            aria-hidden
            className="grid h-10 w-10 shrink-0 place-items-center rounded-md bg-purple-800"
          >
            <Icon className="h-5 w-5 text-white" aria-hidden />
          </motion.span>

          <span className="min-w-0 flex-1">
            <motion.span variants={riseItem(reduce, 10)} className="flex flex-wrap items-center gap-x-3 gap-y-1">
              <span className="font-display text-lg font-bold leading-tight text-ink-900">{step.label}</span>
              <span className="rounded-full bg-purple-50 px-2.5 py-0.5 text-xs font-medium text-purple-800">{step.action}</span>
            </motion.span>
            <motion.span variants={riseItem(reduce, 10)} className="mt-2 block text-sm leading-6 text-ink-700">
              {step.summary}
            </motion.span>
          </span>

          <motion.span
            variants={riseItem(reduce, 10)}
            aria-hidden
            className="mt-1 grid h-7 w-7 shrink-0 place-items-center rounded-full border border-line-200 text-ink-500"
          >
            <motion.span
              initial={false}
              animate={{ rotate: expanded ? 180 : 0 }}
              transition={springy(reduce)}
              className="grid place-items-center"
            >
              <ChevronDown className="h-4 w-4" aria-hidden />
            </motion.span>
          </motion.span>
        </motion.button>

        <AnimatePresence initial={false}>
          {expanded ? (
            <motion.div
              key="detail"
              id={panelId}
              initial={{ height: 0, opacity: 0 }}
              animate={{ height: "auto", opacity: 1 }}
              exit={{ height: 0, opacity: 0 }}
              transition={reduce ? { duration: 0 } : { height: layoutSpring(false), opacity: { duration: 0.18 } }}
              // Clips itself (the height animation needs it) and rounds its own bottom, so the
              // tinted body stays inside the card's corners without the card clipping the toggle's
              // focus ring.
              className="overflow-hidden rounded-b-lg"
            >
              <motion.div
                variants={staggerParent(reduce, 0.05)}
                initial="hidden"
                animate="show"
                className="grid gap-5 border-t border-line-200 bg-surface-50 p-5"
              >
                <motion.div variants={riseItem(reduce, 8)}>
                  <h3 className="flex items-center gap-2 font-display text-sm font-bold text-ink-900">
                    <Lightbulb className="h-4 w-4 text-purple-700" aria-hidden />
                    Why this step exists
                  </h3>
                  <p className="mt-2 text-sm leading-6 text-ink-700">{step.why}</p>
                </motion.div>

                <motion.div variants={riseItem(reduce, 8)}>
                  <h3 className="flex items-center gap-2 font-display text-sm font-bold text-ink-900">
                    <ListChecks className="h-4 w-4 text-purple-700" aria-hidden />
                    What the screen asks for
                  </h3>
                  <motion.ul variants={staggerParent(reduce, 0.025)} className="mt-2.5 flex flex-wrap gap-1.5">
                    {step.fields.map((field) => (
                      <motion.li
                        key={field}
                        variants={slideItem(reduce, 8)}
                        className="rounded-full border border-line-200 bg-card px-3 py-1 text-xs text-ink-700"
                      >
                        {field}
                      </motion.li>
                    ))}
                  </motion.ul>
                </motion.div>

                <motion.div variants={riseItem(reduce, 8)}>
                  <h3 className="flex items-center gap-2 font-display text-sm font-bold text-ink-900">
                    <TriangleAlert className="h-4 w-4 text-purple-700" aria-hidden />
                    Watch out for
                  </h3>
                  <ul className="mt-2 grid gap-2">
                    {step.watch.map((note) => (
                      <li key={note} className="flex gap-2.5 text-sm leading-6 text-ink-700">
                        <span aria-hidden className="mt-2.5 h-1 w-1 shrink-0 rounded-full bg-purple-700" />
                        <span>{note}</span>
                      </li>
                    ))}
                  </ul>
                </motion.div>

                <motion.div variants={riseItem(reduce, 8)}>
                  <Link className="field-button h-9 min-h-0 px-3.5 text-xs" href={step.href}>
                    Open {step.label}
                    <ArrowUpRight className="h-3.5 w-3.5" aria-hidden />
                  </Link>
                </motion.div>
              </motion.div>
            </motion.div>
          ) : null}
        </AnimatePresence>
      </motion.div>
    </motion.li>
  );
}
