/**
 * The three rules every dictated box in this app obeys, written down once.
 *
 * WHY THIS FILE EXISTS. The joiner rule below had been re-derived TWICE by the time the record-page
 * sweep started: `DictatedTextArea` and `ProcessForm`'s per-note microphone each carried their own
 * copy — at `DictatedTextArea.tsx:101` and `ProcessForm.tsx:240` in the commit BEFORE this one, both
 * now deleted, which is why those two line numbers resolve to nothing today — each with a comment
 * explaining the same defect, and the second one said so out loud ("the same rule `DictatedTextArea`
 * uses"). The sweep that puts a microphone under every free-text box on eight surfaces was about to
 * make it thirty. A rule copied thirty times is a rule that will be fixed in twenty-nine places, and
 * the fix is one function.
 *
 * `e2e/record-form-dictation-unit.spec.ts` pinned the INLINE literal in `DictatedTextArea`
 * (`const joiner = !value || /\s$/.test(value) ? "" : " ";`). That assertion moves here in the same
 * commit — it was pinning the rule, and the rule has not changed, only its address. It is also now
 * EXECUTED rather than read: the spec calls `appendDictatedPhrase` with the four inputs that matter,
 * which is a stronger guard than a regex over a source line ever was.
 *
 * NOTHING HERE TOUCHES THE RECOGNISER. The Web Speech lifecycle lives in
 * `components/dictation/onDeviceSpeech.ts`; this module is only about what a caller does with a
 * phrase once the recogniser has finished with it. Keeping the two apart is deliberate: a change to
 * how a phrase is COMMITTED must not be able to reach how the recogniser is STARTED, and vice versa.
 *
 * DO NOT ADD A NETWORK CALL TO THIS FILE. There is no transport anywhere on this path — see the
 * header of `components/dictation/OnDeviceDictationButton.tsx` for why a record form gets no server
 * transcription rung, and `e2e/record-form-dictation-unit.spec.ts` §1 for the test that reads this
 * file's source to prove it.
 */

/**
 * A finished phrase, appended to whatever is already in the box.
 *
 * APPENDS, NEVER REPLACES. The recogniser is stopped and started many times across a long answer —
 * every pause for breath ends one phrase and begins another — so a commit that overwrote the box
 * would delete everything already in it the moment somebody drew breath.
 *
 * THE SINGLE SPACE IS NOT COSMETIC. Without it a paragraph dictated in five goes comes out as
 * "…the warpis sized…": the recogniser hands back trimmed phrases, so nothing else supplies the
 * word break. It is skipped when the box is empty (no leading space on a fresh answer) and when the
 * box already ends in whitespace (a researcher who typed a newline meant that newline, and a space
 * appended after it would push the dictated sentence off the line they put it on).
 */
export function appendDictatedPhrase(current: string, phrase: string): string {
  const joiner = !current || /\s$/.test(current) ? "" : " ";
  return `${current}${joiner}${phrase}`;
}

/**
 * The value, cut to the column's ceiling — and the ceiling is enforced HERE, not only by the DOM
 * `maxLength` attribute.
 *
 * A DOM `maxLength` bounds TYPING and PASTING and has no opinion at all about a value written into
 * React state, which is exactly what a committed phrase is. So dictation is the one path that can
 * carry a value past its column's limit, and an over-long value 422s the WHOLE body — on the
 * questionnaire that is every answer in the interview lost because somebody spoke one sentence too
 * many, with the refusal naming a box that looks fine on screen. `saveOrQueue` will not queue a 4xx
 * either (`lib/offline.ts`), so the work is not banked for later: it is gone.
 *
 * `undefined` means the column has no declared ceiling and the value passes through untouched. Do
 * not invent one: a cap the API does not have is a cap that refuses an answer the API would store.
 */
export function clampToColumn(value: string, maxLength?: number): string {
  return maxLength === undefined ? value : value.slice(0, maxLength);
}

/**
 * The sentence a full box says about itself.
 *
 * SAID, NEVER SILENT. A box that quietly stops accepting words is indistinguishable from a
 * microphone that stopped working, and the researcher's next move in those two cases is completely
 * different. `ink-500` at the call sites rather than `error-600`, because nothing has gone wrong and
 * nothing was lost: what is in the box is exactly what will be saved.
 */
export function columnFullSentence(maxLength: number): string {
  return `This box is full — it holds ${maxLength.toLocaleString("en-IN")} characters, which is what the column stores. Anything spoken or typed beyond that is not added.`;
}
