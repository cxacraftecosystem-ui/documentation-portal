"use client";

import Link from "next/link";
import { motion, type Variants } from "framer-motion";
import { ChevronDown } from "lucide-react";

import { useHeroReducedMotion } from "@/components/hero/useHeroMotion";

/**
 * ── EVERY ANSWER HERE IS A CLAIM SOMEBODY CAN CHECK, AND THREE OF THEM HAD STOPPED BEING TRUE ───
 *
 * This list is the page's most load-bearing prose: it is where a reader goes when the marketing
 * bands have not answered the thing they actually wanted to know, so a stale sentence here is not
 * a stale adjective, it is a wrong answer to a direct question. Three had gone stale by 2026-09-14
 * and the notes beside each say what replaced them and where the replacement is checkable.
 *
 * ⚠ THE RULE FOR EDITING THIS ARRAY: name the behaviour a module actually implements, not the
 * behaviour the roadmap intends. Every sentence below can be traced to a file, and the comments
 * carry the trace so the next editor can re-check it rather than trusting it.
 */
const FAQS = [
  {
    q: "Who can sign in?",
    a: "Only addresses an administrator has admitted. If yours isn’t on the list, signing in sends them a request."
  },
  {
    q: "I signed in with Google and was told I need approval. Why?",
    a: "Google confirms who you are, not that you’re approved. Your request has gone to an administrator; you can sign in once they approve it."
  },
  {
    q: "What happens to my recordings?",
    a: "They’re transcribed, translated into English and linked to their artisan, craft and workshop."
  },
  {
    // ⚠ THE SECOND HALF IS NEW AND IS NOT A RESTATEMENT OF THE FIRST. Records and assigned TASKS
    // are two different ladders, and until 2026-09-14 only one of them existed: an assignee's
    // "Mark done" wrote DONE and the row left their board unreviewed. It now lands on SUBMITTED
    // (`backend/app/api/routes/tasks.py:105`), which `STATUS_LABELS` prints to every client as
    // "Under review" (`:132`) and `OUTSTANDING_STATUSES` keeps on the assignee's own list (`:124`)
    // until the creator or an admin writes DONE — the `is_manager` test at `:1645`. "Under review"
    // and "Approved" are quoted here because the SERVER owns that vocabulary for all three clients;
    // inventing a synonym on this page would put a fourth wording into circulation.
    q: "How does review work?",
    a: "Each tier reviews the tier below. A reviewer approves, rejects or sends work back with comments. Finished tasks wait for approval too."
  },
  {
    // DICTATION, ADDED 2026-09-14 — the sweep in `4f0ce0d` (web) and `9e989fd` (Android). The set of
    // boxes that get a microphone is DATA on the handset, not a pattern:
    // `android/.../ui/RecordDictationFields.kt` holds `RECORD_DICTATED` and `RECORD_NOT_DICTATED`,
    // every exclusion carries a written reason, and `RecordDictationParityTest` reads the web's own
    // `.tsx` call sites and holds the two clients to a stated relationship. The exclusions named
    // below are that table's, verbatim in substance: closed pickers, calendar dates, every numeric
    // and money box, and the two regulated identity numbers.
    //
    // ⚠ SAY "RECORD FORMS CARRY A MICROPHONE ON THEIR FREE-TEXT BOXES", NEVER "EVERY FREE-TEXT BOX
    // ON BOTH CLIENTS". One box is genuinely still one-sided: the shared address card's village box
    // is dictated in the browser (`components/forms/LocationFields.tsx`) and is still a bare
    // `OutlinedTextField` on the handset, which `RecordDictationFields.kt` records as the first item
    // of its hand-off list. Strengthening this sentence to the universal would make the landing page
    // the only surface in the product that does not know about that gap.
    //
    // THE LAST SENTENCE IS THE ONE THAT MATTERS MOST AND IT IS ENFORCED BY TEST, not by habit:
    // `components/richtext/DictatedTextInput.tsx` has no `MediaRecorder`, no `getUserMedia`, no
    // `fetch` and in particular no `transcribeMediaFile`, and `e2e/record-form-dictation-unit.spec.ts`
    // §1 asserts that absence by reading the source. An artisan who was not asked for a recording
    // must not get one.
    q: "Can a researcher speak instead of type?",
    a: "Yes — free-text boxes have a microphone on web and Android. Number and ID fields don’t. Dictation runs on the device; nothing is recorded or uploaded."
  },
  {
    q: "Who can download the data?",
    a: "Professors and above, or anyone granted dataset access. Researchers can also share their own records to download, comment or edit."
  },
  {
    q: "What can a brand-new account actually do?",
    a: "Take interviews, upload media and comment. An admin raises your tier as your role grows."
  },
  {
    // ⚠ THIS ANSWER SAID THE BROWSER DOES NOT — AND THE BAND FOUR SECTIONS ABOVE ALREADY SAID IT
    // DOES. `HowItWorks.tsx`'s first step reads "on the Android app or the web — with or without a
    // signal", so the page was answering its own direct question with the opposite of its own
    // headline claim, which is worse than either sentence being wrong alone. The browser's outbox is
    // `lib/offline.ts`: one IndexedDB entry per attempted save, the attached `File` objects stored
    // by structured clone so the bytes survive a browser restart, replayed oldest-first on the next
    // `online` event. The triage sentence is that module's own distinction — a transient failure
    // (no connection, 5xx, timeout) stops the pass and keeps everything queued; a 4xx is marked with
    // the server's reason and left visible rather than stranding every entry behind it.
    //
    // THE PHONE KEEPS THE FIRST SENTENCE ANYWAY, and that is not diplomacy: it holds the camera, the
    // GPS and the questionnaire recorder, and `components/settings/GetTheAppPanel.tsx` says the same
    // thing in the same order to a signed-in user. Two surfaces, one sentence.
    q: "Does it work offline?",
    a: "Yes. Both the Android app and the website save offline and send when you’re back online."
  },
  {
    // "THE ANDROID APP IS OFFLINE-FIRST" HAS BEEN ON THIS PAGE SINCE IT WAS WRITTEN, AND THE PAGE
    // NEVER SAID HOW TO GET IT. Answered here because the answer is genuinely non-obvious and every
    // part of it is a decision somebody made in code:
    //   · Not Google Play. `backend/app/api/routes/app_release.py` serves the APK itself.
    //   · Behind sign-in, but not behind a tier: `GetTheAppPanel` is mounted unconditionally on
    //     /settings (`app/(protected)/settings/page.tsx:121`), above the admin-only block, and the
    //     page's own header says the route "stays open to everyone".
    //   · "Whichever build is current when you press it" is the literal design: the button's href is
    //     the fixed, versionless `/api/app/download`, which re-resolves server-side on every click
    //     precisely so a tab left open across a release cannot hand out yesterday's APK. That route
    //     is deliberately unauthenticated because a plain link navigation cannot carry a token.
    //   · The browser permission prompt is Android's, and `GetTheAppPanel` warns about it in the
    //     same words — a first-time sideload that fails silently reads as a broken download.
    //   · Self-update: `GET /app/release/latest` is what an installed handset checks, resolved from
    //     the same row (`_latest_release`), so the phone and this button can never disagree.
    // ⚠ DO NOT NAME A VERSION HERE. The current one is on the card itself, resolved live; a number
    // typed into this page would be wrong by the next release and nothing would report it.
    q: "How do I get the Android app?",
    a: "Sign in and open Settings → Get the Android app. Allow installs from your browser when asked; updates arrive automatically."
  },
  {
    q: "What about privacy?",
    // Aadhaar: only a keyed fingerprint and the last four digits are stored (4a25ab16), so nobody
    // can see a full number. Consent (f7edfefd): recordings go to outside transcription and
    // translation unless a refusal is recorded, and anyone can record one.
    a: "Access follows your role, sharing is opt-in and every edit is audited. Media is private. Aadhaar is never stored: records keep only the last four digits (XXXX XXXX 9012). Recordings may be sent for transcription and translation unless the artisan declines; anyone can switch that off."
  }
];

/**
 * Marketing FAQ — native <details>/<summary> accordion styled to the tokens,
 * so it works with zero JavaScript and no extra dependencies.
 */
export default function HeroFAQ() {
  const reduce = useHeroReducedMotion();

  const container: Variants = {
    hidden: {},
    show: { transition: { staggerChildren: reduce ? 0 : 0.06 } }
  };
  const item: Variants = {
    hidden: { opacity: 0, y: 14 },
    show: { opacity: 1, y: 0, transition: { duration: reduce ? 0 : 0.45, ease: [0.16, 1, 0.3, 1] } }
  };

  return (
    <section id="faq" className="mx-auto max-w-3xl px-6 py-24" aria-label="Frequently asked questions">
      <motion.div initial="hidden" whileInView="show" viewport={{ once: true, amount: 0.15 }} variants={container}>
        <motion.p variants={item} className="eyebrow mb-3 text-center">
          Questions
        </motion.p>
        <motion.h2
          variants={item}
          className="text-center font-display text-3xl font-bold tracking-tight text-ink-900 sm:text-4xl"
        >
          Answered before you ask.
        </motion.h2>

        <motion.div variants={item} className="mt-10 rounded-lg border border-line-200 bg-card px-6 shadow-sm">
          {FAQS.map((faq) => (
            <details key={faq.q} className="group border-b border-line-200 last:border-b-0">
              <summary className="flex cursor-pointer list-none items-center justify-between gap-4 py-5 font-display text-base font-semibold text-ink-900 transition hover:text-purple-700 [&::-webkit-details-marker]:hidden">
                {faq.q}
                <ChevronDown
                  className="h-4 w-4 shrink-0 text-ink-500 transition-transform duration-200 group-open:rotate-180"
                  aria-hidden
                />
              </summary>
              <p className="pb-5 text-sm leading-relaxed text-ink-700">{faq.a}</p>
            </details>
          ))}
        </motion.div>

        <motion.p variants={item} className="mt-6 text-center text-sm text-ink-500">
          Still unsure where to start?{" "}
          <Link href="/guide" className="font-medium text-purple-700 dark:text-purple-300 underline-offset-2 hover:underline">
            The walkthrough
          </Link>{" "}
          covers every screen in the order you will meet them.
        </motion.p>
      </motion.div>
    </section>
  );
}
