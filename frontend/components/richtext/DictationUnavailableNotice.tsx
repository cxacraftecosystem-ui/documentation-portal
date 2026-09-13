"use client";

/**
 * The "this browser cannot dictate" sentence, said ONCE for a whole form.
 *
 * ── THE PROBLEM IT SOLVES, WHICH IS ARITHMETIC ─────────────────────────────────────────────────
 *
 * `OnDeviceDictationButton` draws no button where the browser has no `SpeechRecognition` — Firefox
 * implements none at all, and neither do some locked-down Chromium builds — and prints a sentence
 * instead, because a feature present on one researcher's laptop and absent on another's with no
 * explanation gets reported as a broken build. That is exactly right for a screen carrying one or
 * two microphones, which is all these forms carried before the record-page sweep.
 *
 * The sweep put a microphone under every free-text box on eight surfaces, which is six to eleven per
 * form — `ToolForm` alone carries eleven. Eleven copies of the same paragraph down one form is not
 * honesty: it is the reader learning to skip a block of grey text, and then skipping the one place
 * it mattered. The button's own `explainWhenUnavailable` prop already names the way out and the
 * obligation that comes with it — "Where it is set false, the SURROUNDING control must carry the
 * explanation once" (`components/dictation/OnDeviceDictationButton.tsx:67-75`). This component IS
 * that surrounding control, so the obligation is discharged by one line at the top of the form
 * rather than by a rule somebody has to remember.
 *
 * IT IS NOT OPTIONAL WHERE A FORM PASSES `false`. A form that passes the flag and mounts no notice
 * is strictly worse than one that never passed it: on Firefox the microphones simply vanish and
 * nothing anywhere says why. (The sibling application shipped exactly that on one screen — every
 * microphone silenced, no notice anywhere in the file. Do not reproduce it: every dictated form in
 * this repository mounts this component, and the spec asserts the count is exactly one —
 * `e2e/record-form-dictation-unit.spec.ts`, "every form that silences its microphones says it once
 * instead".)
 *
 * ── WHY IT DETECTS RATHER THAN BEING TOLD ──────────────────────────────────────────────────────
 *
 * It calls `speechRecognitionConstructor()` — the same exported detector the button itself uses, out
 * of the same module (`components/dictation/onDeviceSpeech.ts:66`) — instead of taking a prop. A
 * prop would mean the form deciding, and the form has no way to know: the answer is a fact about the
 * browser, it is identical for every microphone on the page, and a second opinion about it would be
 * a second thing that can be wrong. Nothing here touches the recogniser lifecycle; this is one
 * feature test and a paragraph.
 *
 * "unknown" IS THE SSR AND FIRST-PAINT STATE AND IT DRAWS NOTHING, for the reason the button states
 * at length: `window` does not exist on the server, so a component that decided during render would
 * produce different markup on the two sides and React would throw a hydration mismatch across the
 * whole form. It is decided in an effect — one frame with no sentence, invisible in practice, and
 * far better than a paragraph that flickers away.
 *
 * DELIBERATELY NOT `role="alert"` / `aria-live`, and this matches the button: nothing has gone
 * wrong. It is a standing fact about the browser, present before the reader has done anything, so it
 * is read in document order like any other sentence on the page rather than interrupting.
 */

import { useEffect, useState } from "react";

import { NO_RECOGNISER_SENTENCE, speechRecognitionConstructor } from "@/components/dictation/onDeviceSpeech";

export function DictationUnavailableNotice({
  /**
   * Grid placement, e.g. `md:col-span-2 lg:col-span-4` on a form whose root IS the field grid.
   *
   * It reaches the `<p>` itself, which is the whole element this component renders — unlike
   * `DictatedTextArea`, where `className` lands on the `<textarea>` inside a hardcoded wrapper. A
   * notice mounted as a child of a four-column grid without this spans one cell and wraps to five
   * lines of grey text in a corner.
   */
  className
}: {
  className?: string;
}) {
  const [absent, setAbsent] = useState<boolean | null>(null);

  useEffect(() => {
    setAbsent(!speechRecognitionConstructor());
  }, []);

  // `!== true` and not `!absent`: null is "not decided yet", which must draw nothing rather than
  // being read as "the recogniser is present" — the two are the same on screen but not in intent,
  // and the next reader of this line needs the difference to survive.
  if (absent !== true) return null;

  return <p className={`text-xs leading-5 text-ink-500 ${className ?? ""}`}>{NO_RECOGNISER_SENTENCE}</p>;
}
