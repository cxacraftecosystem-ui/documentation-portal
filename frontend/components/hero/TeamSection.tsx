"use client";

import { motion, type Variants } from "framer-motion";
import { Check, Compass, Microscope, ShieldCheck } from "lucide-react";

import { useHeroReducedMotion } from "@/components/hero/useHeroMotion";

/**
 * ── FOUR POINTS PER CARD, AND THE COUNT IS PART OF THE LAYOUT ──────────────────────────────────
 *
 * These three cards are one `md:grid-cols-3` row, so grid stretch makes them all as tall as the
 * longest list. Three points against four is a card with a visible band of dead space under its
 * rule, which reads as a card somebody forgot to finish rather than as a shorter list. So a point
 * added to one card is a point owed by the other two — and each of the three added on 2026-09-14 is
 * a capability that genuinely landed, not filler written to square the row.
 *
 * ⚠ EVERY LINE IS CHECKABLE, AND THE COMMENTS BELOW CARRY THE TRACE. The bands on this page are
 * what an editor reads and updates; a bullet list is what they skim past, which is exactly why the
 * stale one here ("Assigned tasks with due dates", written when an assignee closed their own work)
 * survived the change that made it wrong.
 */
const AUDIENCES = [
  {
    icon: Compass,
    title: "Field teams",
    copy: "Capture everything where the work actually happens — no signal required.",
    points: [
      // WAS "Offline capture that syncs later", WHICH IMPLIED ONE CLIENT. `lib/offline.ts` is the
      // browser's own outbox — the web's port of the Android `OfflineOutbox` — so the phone is no
      // longer the only place a save survives a dead signal. Naming both is what stops a reader
      // concluding the portal must not be used away from a desk.
      "Offline capture in the app and the browser",
      // The 2026-09-14 dictation sweep. "Free-text" and not "every": dates, numbers, money, closed
      // pickers and the two identity numbers are excluded by a table with a written reason each
      // (`android/.../ui/RecordDictationFields.kt`). The FAQ carries the full list.
      "Dictation on the free-text boxes",
      "GPS tagging on every record",
      "Audio, video and photo media"
    ]
  },
  {
    icon: Microscope,
    title: "Researchers",
    copy: "Turn raw field material into structured, shareable research.",
    points: [
      "Structured questionnaires per artisan set",
      // The 2026-09-13 instrument work: a `Questionnaire` container owns sections, questions and
      // sittings, and `Workshop.questionnaireId` binds one to a workshop. "Chosen by an admin" is
      // the gate on `PUT /workshops/{id}/questionnaire`, and it is stated because a researcher
      // reading this must not go looking for the switch on their own capture page.
      "One questionnaire per workshop, chosen by an admin",
      "Collaboration requests and grants",
      "Download, comment and edit sharing tiers"
    ]
  },
  {
    icon: ShieldCheck,
    title: "Administrators",
    copy: "Keep quality and access moving through the same ladder.",
    points: [
      "Review ladder — approve, reject, revise",
      // WAS "Assigned tasks with due dates". True, and no longer the interesting half: an assignee's
      // "Mark done" now lands on SUBMITTED and only the creator or an admin writes DONE
      // (`backend/app/api/routes/tasks.py:1645`), so the due date is the smaller of the two facts
      // this rung owns. The wording tracks the server's own labels — "Under review" then "Approved".
      "Assigned tasks, handed in for approval",
      // The allow-list, which the hero's closing paragraph and two FAQ answers already describe. It
      // belongs on this card because it is the one power here that decides who exists at all:
      // `/admin/access-roster`, `canManageAccessRoster`, admins and the master admin only.
      "The roster of who may sign in at all",
      "Grantable dataset downloads"
    ]
  }
];

/** Three audience cards — who the repository serves and what each tier gets. */
export default function TeamSection() {
  const reduce = useHeroReducedMotion();

  const container: Variants = {
    hidden: {},
    show: { transition: { staggerChildren: reduce ? 0 : 0.08 } }
  };
  const item: Variants = {
    hidden: { opacity: 0, y: 16 },
    show: { opacity: 1, y: 0, transition: { duration: reduce ? 0 : 0.45, ease: [0.16, 1, 0.3, 1] } }
  };

  return (
    <section className="mx-auto max-w-6xl px-6 py-24" aria-label="Built for the whole team">
      <motion.div initial="hidden" whileInView="show" viewport={{ once: true, amount: 0.2 }} variants={container}>
        {/* The ladder itself is the section above; this one is about WHO the repository serves. */}
        <motion.p variants={item} className="eyebrow mb-3">
          Who it is for
        </motion.p>
        <motion.h2
          variants={item}
          className="max-w-2xl font-display text-3xl font-bold tracking-tight text-ink-900 sm:text-4xl"
        >
          Built for the whole team.
        </motion.h2>

        <div className="mt-12 grid gap-5 md:grid-cols-3">
          {AUDIENCES.map((audience) => (
            <motion.div
              key={audience.title}
              variants={item}
              className="rounded-lg border border-line-200 bg-card p-6 shadow-sm transition hover:-translate-y-0.5 hover:shadow-md"
            >
              <span className="mb-4 flex h-11 w-11 items-center justify-center rounded-md bg-purple-700 text-white">
                <audience.icon className="h-5 w-5" aria-hidden />
              </span>
              <h3 className="font-display text-lg font-bold text-ink-900">{audience.title}</h3>
              <p className="mt-1.5 text-sm leading-relaxed text-ink-500">{audience.copy}</p>
              <ul className="mt-5 space-y-2.5 border-t border-line-200 pt-5">
                {audience.points.map((point) => (
                  <li key={point} className="flex items-start gap-2.5 text-sm leading-relaxed text-ink-700">
                    <Check className="mt-0.5 h-4 w-4 shrink-0 text-purple-700" aria-hidden />
                    {point}
                  </li>
                ))}
              </ul>
            </motion.div>
          ))}
        </div>
      </motion.div>
    </section>
  );
}
