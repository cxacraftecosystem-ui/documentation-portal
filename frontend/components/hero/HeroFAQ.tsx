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
    a: "Only addresses an administrator has admitted. Signing in — by password or with Google — checks your address against the platform allow-list first; if it is not on the list, no account is created and your request goes to the administrators as a pending approval. Everyone already using the repository when the allow-list was introduced was carried onto it, so nothing changed for existing accounts. Once you are admitted you join the six-tier ladder, and an admin raises you up it (field contributor, researcher, professor, admin) as your role in the project grows."
  },
  {
    q: "I signed in with Google and was told I need approval. Why?",
    a: "Because a verified Google address is proof of who you are, not permission to be here. Google sign-in used to create an account for any address that could authenticate; now it is checked against the same allow-list as a password, so an address nobody has admitted gets no account and no token. Your request is queued for an administrator, and you will be able to sign in once they approve it. A refused password and an address awaiting approval are answered differently, so you are never left guessing which of the two you are looking at."
  },
  {
    q: "What happens to my recordings?",
    a: "They upload to secure storage and join the transcription queue, where a chain of three speech-to-text providers with automatic failover transcribes them and translates them into English. The finished transcript is linked back to the artisan, craft, and workshop it belongs to."
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
    a: "Every record enters a peer-review ladder. A reviewer can approve it, reject it, or send it back for revision with mandatory comments — and each tier reviews the work of those ranked below it, with the master admin able to review everyone's. Assigned work is settled the same way: marking a task done hands it in rather than closing it, so it stays on the assignee's own board as “Under review” until whoever assigned it, or an admin, approves it."
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
    a: "Yes. Record forms carry a microphone on their free-text boxes — names, places, addresses, descriptions, remarks — in the browser and on the Android app. Boxes that are not free text deliberately stay silent: closed dropdowns, calendar dates, every numeric and money box, and the Aadhaar and Pehchan fields, where one mis-heard digit would file an artisan under somebody else's number. Dictation runs on the device's own recogniser — nothing is recorded to a file, nothing is uploaded and nothing is stored. It is the keyboard's microphone, not a second recording of the interview."
  },
  {
    q: "Who can download the data?",
    a: "The full dataset opens at Professor and above. Anyone below that needs the dataset-download permission granted explicitly, or a per-record share from the owner. Sharing between researchers is tiered too: download, comment, or edit — requested by one side and granted, changed, or revoked by the other."
  },
  {
    q: "What can a brand-new account actually do?",
    a: "A newly admitted account starts at the bottom of the ladder — Crowdsource Volunteer unless the administrator who admitted it chose a higher tier — and can take interviews, upload media, and comment on existing records. Creating artisans, products, processes and tools begins at Field Contributor, and an admin raises the tier when the person's role in the project does."
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
    a: "Both clients do. The Android app is offline-first — interviews, media and GPS positions captured with no signal at all, queued until you are back in range — and the browser now keeps an outbox of the same kind: a save made with no connection is held locally with its attachments and replayed when the network returns, and a record the server refuses waits with the reason attached instead of blocking everything behind it. The phone is still the one that goes into the workshop, because it has the camera, the GPS and the questionnaire recorder; the portal is where reviewing, browsing and administration happen."
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
    a: "Sign in, open Settings, and the “Get the Android app” card downloads whichever build is current at the moment you press it. It is not on the Play Store — the repository serves the file itself — so Android will ask you to allow installing from your browser the first time. After that the app takes its own updates: when a new build is published, the phone is prompted to install it. Same account and same repository as the portal."
  },
  {
    q: "What about privacy?",
    a: "Access is governed by the six-tier role ladder, cross-researcher sharing is opt-in per grant, and every edit carries an audited revision history. Media lives in private cloud storage that only signed-in, authorized users can reach. National identifiers are masked wherever a record leaves its owner: an artisan's Aadhaar number is used to make sure the same person documented at two workshops becomes one record, not two, but it renders as XXXX XXXX 9012 on every shared and exported surface — the data browser, CSV, and the .xlsx report — and only the researcher who recorded that artisan, or a professor and above, can read it in full."
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
          <Link href="/guide" className="font-medium text-purple-700 underline-offset-2 hover:underline">
            The walkthrough
          </Link>{" "}
          covers every screen in the order you will meet them.
        </motion.p>
      </motion.div>
    </section>
  );
}
