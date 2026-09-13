/**
 * THE MANDATORY-FIELD ASTERISK, IN ONE PLACE.
 *
 * ── WHY A COMPONENT FOR ONE CHARACTER ───────────────────────────────────────────────────────────
 *
 * Because it was written out by hand SEVEN times. The same conditional string literal appeared in
 * seven labels — seven copies, and no way to change how a required field is marked without finding
 * all seven and hoping there is not an eighth. The dictation sweep was about to add the eighth and
 * the ninth (`DictatedTextInput`, `DictatedTextArea`), which is what made this worth owning: a
 * register written down twice goes stale, and this one was already written down seven times.
 *
 * ALL SEVEN NOW MOUNT THIS COMPONENT. Four were converted by the dictation sweep —
 * `components/forms/AadhaarField.tsx`, `components/forms/ArtisanForm.tsx` (the Pehchan label),
 * `components/forms/DosDontsField.tsx` and `components/forms/LocationFields.tsx` (the group
 * heading). The remaining three were outside the set of files that sweep was permitted to touch and
 * are converted by the record-parity sweep: `components/FormControls.tsx` (which is `Field`, so it
 * is most of the required marks in the product), `components/review/ReviewEditPanel.tsx` and
 * `components/tasks/TaskPrimitives.tsx`.
 *
 * THE CENSUS IS NOT THE CONDITION `required`. That pattern — the obvious one, and the one the first
 * draft of this sweep used — finds five of the seven. `ArtisanForm` tests `available` (the artisan
 * holds a Pehchan card) and `LocationFields` tests `stateRequired`. Census on the CONSEQUENT, the
 * asterisk string itself, or the two that are easiest to break are exactly the two that are missed.
 * `e2e/record-form-dictation-unit.spec.ts` runs that census over all seven files and is what caught
 * this the first time.
 *
 * ── THE COLOUR, AND WHY IT ARRIVED SECOND ───────────────────────────────────────────────────────
 *
 * `text-error-600 dark:text-red-400`. The argument, which the sibling application has shipped from
 * the start: a plain-ink `*` beside plain-ink label text is a character a reader has to hunt for,
 * and hunting for it on a thirty-field artisan form is exactly the moment a required box gets missed
 * and the save is refused by a browser bubble naming a field nobody can see.
 *
 * IT DID NOT LAND WITH THE COMPONENT, and the reason was arithmetic rather than taste. Three of the
 * seven call sites were outside the dictation sweep's reach, so converting only the four reachable
 * ones and turning the mark red would have put a RED asterisk on Name, Place, Aadhaar and State and
 * an INK one on Craft, Gender and Status — on the same artisan form, at the same time. Two colours
 * of required mark on one screen is worse than one colour that is merely quiet: it reads as two
 * different kinds of requirement, which is a thing this product does not have. So the mark inherited
 * until the census came back empty, and the assertion in `e2e/record-form-dictation-unit.spec.ts`
 * ("the required asterisk has one owner, and one colour on screen at a time") FLIPS on that census:
 * while any hand-written mark survives it demands `text-inherit`, and the moment the last one goes
 * it demands exactly the two classes below. Neither half can be shipped without the other.
 *
 * `text-error-600` is `#dc2626` (`tailwind.config.ts:93`); it is a literal status colour and does
 * not invert, which on `--card` in dark lands near 4:1 — thin for a mark whose whole job is to be
 * caught out of the corner of an eye, and that is what `dark:text-red-400` is for. `red-400` is
 * stock Tailwind `#f87171` and resolves because `tailwind.config.ts:51` extends rather than replaces
 * the default palette. It is deliberately not introduced as a project token: the only thing in the
 * product that needs it is this one glyph.
 *
 * ── IT IS NOT `aria-hidden`, AND THAT IS A DELIBERATE NON-CHANGE ────────────────────────────────
 *
 * The obvious tidy-up is to hide the asterisk from assistive technology, since every control it
 * marks also carries `required` / `aria-required` and a screen reader announces "required" from
 * that. It is not done here. Seven labels changing their announced accessible name in one commit is
 * a change to how seven forms read aloud, made as a side effect of a refactor, and this component is
 * not the place to decide it. The mark is announced exactly as it always has been — and it was
 * announced that way before the colour landed too, which is why the colour is not a behaviour change
 * for a screen-reader user at all.
 *
 * ── THE LEADING SPACE IS PART OF THE MARK ───────────────────────────────────────────────────────
 *
 * Every call site wrote the asterisk with a leading space, and JSX drops the leading whitespace of a
 * text node that follows an element. So the space lives INSIDE this span rather than being left to
 * the caller to remember, and `Label *` cannot become `Label*` by somebody tidying a template
 * literal.
 */
export function RequiredMark({ when = true }: { when?: boolean }) {
  if (!when) return null;
  return <span className="text-error-600 dark:text-red-400"> *</span>;
}
