"use client";

import { useEffect, useId, useRef, useState } from "react";
import { motion, useMotionValueEvent, useScroll, useSpring, type Variants } from "framer-motion";

import { BUTI_FILL_RULE, BUTI_PATH, BUTI_VIEWBOX } from "@/components/hero/buti";
import {
  CENSUS_ROWS,
  CORPUS_CENSUS,
  censusCaption,
  formatCensusDate,
  type CorpusCensus
} from "@/components/hero/corpusCensus";
import {
  CLOTH_CELL,
  CLOTH_COLS,
  CLOTH_MASK,
  INDIA_HEIGHT,
  INDIA_PATH,
  INDIA_VIEWBOX,
  INDIA_WIDTH
} from "@/components/hero/indiaOutline";
import { useHeroReducedMotion } from "@/components/hero/useHeroMotion";

function mulberry32(seed: number): () => number {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

const JITTER = { shift: 0.055, rotate: 5, scaleMin: 0.9, scaleRange: 0.14, inkMin: 0.075, inkRange: 0.155 };

const MOTIF_SCALE = CLOTH_CELL / 24;

type Impression = {
  cx: number;
  cy: number;
  rot: number;
  scale: number;
  ink: number;
};

const IMPRESSIONS: readonly Impression[] = (() => {
  const rand = mulberry32(925_074_016);
  const out: Impression[] = [];
  for (let index = 0; index < CLOTH_MASK.length; index += 1) {
    if (CLOTH_MASK[index] !== "1") continue;
    const col = index % CLOTH_COLS;
    const row = Math.floor(index / CLOTH_COLS);
    out.push({
      cx: Number(((col + 0.5 + (rand() * 2 - 1) * JITTER.shift) * CLOTH_CELL).toFixed(3)),
      cy: Number(((row + 0.5 + (rand() * 2 - 1) * JITTER.shift) * CLOTH_CELL).toFixed(3)),
      rot: Number(((rand() * 2 - 1) * JITTER.rotate).toFixed(2)),
      scale: Number((JITTER.scaleMin + rand() * JITTER.scaleRange).toFixed(3)),
      ink: Number((JITTER.inkMin + rand() * JITTER.inkRange).toFixed(3))
    });
  }
  return out;
})();

const TOTAL = IMPRESSIONS.length;

const columnOf = (index: number) => index % CLOTH_COLS;

function Cloth({ printed, spriteId }: { printed: number; spriteId: string }) {
  return (
    <>
      {IMPRESSIONS.map((im, index) => {
        const on = index < printed;
        return (
          <g
            key={index}
            transform={`translate(${im.cx} ${im.cy}) rotate(${im.rot}) scale(${(im.scale * MOTIF_SCALE).toFixed(4)}) translate(-12 -12)`}
          >
            <use
              href={`#${spriteId}`}
              className="fr-buti"
              style={{
                opacity: on ? im.ink : 0,
                transform: on ? "scale(1)" : "scale(1.07)",
                transitionDelay: `${columnOf(index) * 16}ms`,
                ["--fr-ink" as string]: im.ink
              }}
            />
          </g>
        );
      })}
    </>
  );
}

export default function PrintingBed({ census = CORPUS_CENSUS }: { census?: CorpusCensus }) {
  const reduce = useHeroReducedMotion();
  const sectionRef = useRef<HTMLElement>(null);

  const uid = useId().replace(/[^a-zA-Z0-9]/g, "");
  const spriteId = `fr-buti-${uid}`;
  const clipId = `fr-india-${uid}`;
  const glowId = `fr-glow-${uid}`;

  const { scrollYProgress } = useScroll({ target: sectionRef, offset: ["start 65%", "end 65%"] });
  const smoothed = useSpring(scrollYProgress, { stiffness: 140, damping: 30, mass: 0.4 });
  const progress = reduce ? scrollYProgress : smoothed;

  const [printed, setPrinted] = useState(0);

  useMotionValueEvent(progress, "change", (latest) => {
    if (reduce) return;
    const next = Math.max(0, Math.min(TOTAL, Math.round(latest * TOTAL)));
    setPrinted((current) => (current === next ? current : next));
  });

  useEffect(() => {
    if (reduce) setPrinted(TOTAL);
  }, [reduce]);

  const item: Variants = {
    hidden: { opacity: 0, y: 16 },
    show: { opacity: 1, y: 0, transition: { duration: reduce ? 0 : 0.5, ease: [0.16, 1, 0.3, 1] } }
  };

  const asOf = formatCensusDate(census.asOf);

  return (
    <section
      ref={sectionRef}
      className="relative overflow-hidden grad-brand"
      aria-label="The pilot collection"
    >
      <div
        aria-hidden
        className="pointer-events-none absolute inset-0 opacity-60"
        style={{
          background:
            "radial-gradient(32rem 32rem at 88% 10%, oklch(0.7 0.145 80 / 0.14), transparent 60%), radial-gradient(36rem 36rem at 8% 95%, oklch(0.255 0.108 305 / 0.55), transparent 62%)"
        }}
      />

      <motion.div
        initial="hidden"
        whileInView="show"
        viewport={{ once: true, amount: 0.2 }}
        variants={{ hidden: {}, show: { transition: { staggerChildren: reduce ? 0 : 0.08 } } }}
        className="relative mx-auto grid max-w-6xl items-center gap-12 px-6 py-24 lg:grid-cols-[0.95fr_1.05fr] lg:gap-16"
      >
        <div className="fr-ledger">
          <motion.p variants={item} className="eyebrow mb-3 text-gold-300!">
            The pilot collection
          </motion.p>
          <motion.h2
            variants={item}
            className="font-display text-3xl font-bold tracking-tight text-white sm:text-4xl"
          >
            What the archive holds today.
          </motion.h2>
          <motion.p variants={item} className="mt-4 text-sm leading-relaxed text-white/60">
            {censusCaption(census, asOf)}
          </motion.p>

          <motion.dl variants={item} className="mt-8 grid grid-cols-2 gap-x-5 sm:gap-x-8">
            {CENSUS_ROWS.map(({ key, one, many }) => {
              const value = census.counts[key];
              return (
                <div
                  key={key}
                  className="flex items-baseline justify-between gap-3 border-t border-white/10 py-2.5"
                >
                  <dt className="text-sm text-white/60">{value === 1 ? one : many}</dt>
                  <dd className="font-display text-base font-semibold tabular-nums text-white">{value}</dd>
                </div>
              );
            })}
          </motion.dl>
        </div>

        <motion.figure variants={item} className="fr-cloth relative m-0">
          <svg
            viewBox={INDIA_VIEWBOX}
            className="block w-full overflow-visible"
            style={{ aspectRatio: `${INDIA_WIDTH} / ${INDIA_HEIGHT}` }}
            aria-hidden
            focusable="false"
          >
            <defs>
              <path id={spriteId} d={BUTI_PATH} fillRule={BUTI_FILL_RULE} />
              <clipPath id={clipId}>
                <path d={INDIA_PATH} clipRule="evenodd" />
              </clipPath>
              <radialGradient id={glowId}>
                <stop offset="0%" stopColor="oklch(0.85 0.11 86)" stopOpacity="0.34" />
                <stop offset="100%" stopColor="oklch(0.85 0.11 86)" stopOpacity="0" />
              </radialGradient>
            </defs>

            <path d={INDIA_PATH} fillRule="evenodd" fill="rgba(255,255,255,0.045)" />

            <g clipPath={`url(#${clipId})`} fill="#ffffff">
              <Cloth printed={printed} spriteId={spriteId} />
            </g>

            <path
              d={INDIA_PATH}
              fillRule="evenodd"
              fill="none"
              stroke="rgba(255,255,255,0.28)"
              strokeWidth={1}
              strokeLinejoin="round"
              vectorEffect="non-scaling-stroke"
            />

          </svg>


          <noscript>
            <style>{`.fr-buti{opacity:var(--fr-ink)!important;transform:none!important}.fr-mark{opacity:1!important}.fr-ledger>*,.fr-cloth{opacity:1!important;transform:none!important}`}</style>
          </noscript>
        </motion.figure>
      </motion.div>
    </section>
  );
}
