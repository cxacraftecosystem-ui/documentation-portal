import type { Metadata } from "next";

import { fetchCorpusCensus } from "@/components/hero/corpusCensus";
import HeroLanding from "@/components/hero/HeroLanding";

/**
 * The public landing page. A server component whose only fetch is the corpus census, taken here
 * rather than in the client island below so the numbers are in the first paint: fetched in the
 * browser they would arrive after it, and the ledger would visibly swap its figures under a reader
 * who had already started reading them.
 *
 * The route stays statically prerendered. `fetchCorpusCensus` asks for `revalidate: 300`, matching
 * the backend's own five-minute cache, so the page is built once and refreshed in the background —
 * nobody waits on the API, and a cold or absent API cannot delay a paint. It never throws: every
 * failure path returns the dated snapshot, which is why this is not wrapped in a try.
 */
/**
 * ── THE DESCRIPTION IS PART OF THE PAGE, AND IT GOES STALE THE WAY METADATA ALWAYS DOES ────────
 *
 * This string is the ONLY thing a search result, a Slack unfurl or a shared link ever shows, and it
 * is the one piece of copy on this route that nobody scrolls past. The bands under
 * `components/hero/*` are what an editor reads and remembers to correct; `export const metadata` is
 * not, so it rots silently and rots hardest — a description is read by people who never open the
 * page and therefore never meet the corrected version. So when a capability lands in the bands, ask
 * whether it changes the one sentence that reaches everybody else.
 *
 * FOUR CHANGES ON 2026-09-14, EACH THE NARROWEST TRUE FORM OF ITSELF.
 *
 *   "captured offline in the field — on the handset or in the browser". The old string ended
 *   "captured offline, in the field" with no client named, which was written when only the Android
 *   app could do it. `lib/offline.ts` is now the browser's own outbox — the web's port of the
 *   Android `OfflineOutbox`, one IndexedDB entry per attempted save with its `File` attachments,
 *   replayed on the next `online` event. Naming both clients is the whole of the correction.
 *
 *   "a microphone on every free-text box". ⚠ NOT "every box", and NOT "on both clients": the set is
 *   a table with a written reason per exclusion (`android/.../ui/RecordDictationFields.kt`
 *   `RECORD_NOT_DICTATED`) — closed pickers, calendar dates, every numeric and money box, Aadhaar
 *   and Pehchan. And one free-text box is genuinely still one-sided (the shared address card's
 *   village box, dictated in the browser and not yet on the handset), which is why the phrase names
 *   the RULE and not a pair of clients. The sibling repository reached the identical wording from
 *   the identical trap; see its own `app/page.tsx` header.
 *
 *   "a questionnaire each workshop can be set to". The 2026-09-13 instrument work: `QuestionnaireSection`
 *   carried `code @unique` plus a global `@@unique([sortOrder])`, so the schema could hold exactly
 *   ONE instrument; a `Questionnaire` container now owns sections, questions and sittings and
 *   `Workshop.questionnaireId` binds one. Written as "can be set to" because binding is an admin's
 *   act on `PUT /workshops/{id}/questionnaire` and an unbound workshop still resolves to the
 *   default — a description implying every workshop has its own would promise an arrangement most
 *   of the corpus does not have.
 *
 *   "tasks that are handed in and approved". `backend/app/api/routes/tasks.py`: an assignee's "Mark
 *   done" lands on SUBMITTED and only the creator or an admin writes DONE. HANDED IN, never
 *   "completed" — the whole point of the split is that finishing is a claim and approval is a
 *   decision, and a metadata line has no room to re-argue that.
 *
 * The title is the page's headline and stays as it is; a headline that chased the feature list
 * would stop being a headline.
 */
export const metadata: Metadata = {
  title: { absolute: "Field Repository — The interview ends. The knowledge is already preserved." },
  description:
    "A field documentation repository for artisan crafts: artisans, crafts, products, processes, tools and workshops, with a microphone on every free-text box and structured interviews that transcribe and translate themselves against a questionnaire each workshop can be set to. Media, a peer-review ladder, tasks that are handed in and approved, tiered sharing and research-ready export — captured offline in the field, on the handset or in the browser."
};

export default async function Home() {
  // The public origin, not an internal one: this is the same base every client call uses, so there
  // is one answer to "where is the API" rather than a second that can rot unnoticed.
  const census = await fetchCorpusCensus(process.env.NEXT_PUBLIC_API_URL ?? "");
  return <HeroLanding census={census} />;
}
