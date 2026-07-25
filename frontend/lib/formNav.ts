/**
 * Keyboard flow between form fields.
 *
 * Field researchers fill these forms with dozens of short values in a row, often one-handed on a
 * phone or fast on a laptop, and the default web behaviour fights them: a native Enter submits the
 * whole form halfway through, and a custom dropdown leaves focus stranded on its trigger button so
 * the next Tab goes somewhere unexpected. Three rules fix that, and they live here so every form
 * gets the same behaviour instead of each one re-inventing it:
 *
 * - Choosing a single-select value closes the menu and moves to the next field.
 * - Enter in a short input moves to the next field instead of submitting.
 * - A multi-select stays open while you pick, then its Confirm button advances you.
 *
 * The one rule that must never break: **Enter in a textarea still types a newline**, and Enter on
 * the last field still submits. Notes, remarks and transcripts are the fields where a swallowed
 * newline would be most infuriating, so :func:`isAdvanceableInput` opts textareas out explicitly
 * rather than trying to detect them by size or name.
 */

/**
 * Controls that count as "a field the user is trying to reach".
 *
 * `[tabindex="-1"]` is excluded deliberately and is load-bearing: every themed control in this repo
 * (FormControls.Select, PhoneField, AadhaarField, DosDontsField) submits its value through a
 * zero-size mirror `<input tabindex="-1" aria-hidden>` sitting right next to the visible control. A
 * naive walker lands on that invisible twin, focus vanishes, and the form looks broken.
 */
const FOCUSABLE = [
  "input:not([type='hidden']):not([disabled]):not([tabindex='-1'])",
  "select:not([disabled]):not([tabindex='-1'])",
  "textarea:not([disabled]):not([tabindex='-1'])",
  "button[data-form-field]:not([disabled])",
  "[data-form-field]:not([disabled]):not([tabindex='-1'])",
].join(",");

/** Is this element actually on screen? `offsetParent` is null for `display:none` and detached nodes. */
function isVisible(el: HTMLElement): boolean {
  if (el.hidden || el.getAttribute("aria-hidden") === "true") return false;
  if (el.offsetParent === null && getComputedStyle(el).position !== "fixed") return false;
  const rect = el.getBoundingClientRect();
  return rect.width > 0 || rect.height > 0;
}

/** The scope to walk: the containing form when there is one, else the whole document. */
function scopeOf(el: HTMLElement): HTMLElement {
  return (el.closest("form") as HTMLElement | null) ?? document.body;
}

/** Every reachable field inside `el`'s form, in DOM order. */
export function focusableFields(el: HTMLElement): HTMLElement[] {
  return Array.from(scopeOf(el).querySelectorAll<HTMLElement>(FOCUSABLE)).filter(isVisible);
}

/**
 * Move focus to the field after `from`.
 *
 * Returns false when there is nothing after it — the caller uses that to decide whether to fall
 * back to the default behaviour (submitting the form), so the last Enter in a form still works.
 */
export function focusNextField(from: HTMLElement | null | undefined): boolean {
  if (!from) return false;
  const fields = focusableFields(from);
  // `from` may be a wrapper (a dropdown's container) rather than a field itself, so fall back to
  // the first field that contains it.
  let index = fields.indexOf(from);
  if (index === -1) index = fields.findIndex((f) => f === from || f.contains(from) || from.contains(f));
  if (index === -1) return false;

  const next = fields[index + 1];
  if (!next) return false;
  next.focus();
  // Land the caret at the end rather than selecting everything, so typing appends instead of
  // silently replacing a value the researcher meant to keep.
  if (next instanceof HTMLInputElement && next.type !== "checkbox" && next.type !== "radio") {
    const length = next.value.length;
    try {
      next.setSelectionRange(length, length);
    } catch {
      // Number/date inputs throw on setSelectionRange in some browsers; focus alone is enough.
    }
  }
  return true;
}

/**
 * Should Enter in this element advance instead of submitting?
 *
 * Textareas are excluded so multi-line notes keep working. Buttons are excluded so Enter still
 * activates them. Everything else short — text, number, email, tel, date, search — advances.
 */
export function isAdvanceableInput(el: EventTarget | null): el is HTMLInputElement {
  if (!(el instanceof HTMLInputElement)) return false;
  if (el.disabled || el.readOnly) return false;
  const type = (el.type || "text").toLowerCase();
  return !["checkbox", "radio", "button", "submit", "reset", "file", "image"].includes(type);
}

/**
 * `onKeyDown` for a `<form>`: Enter moves to the next field rather than submitting early.
 *
 * Attach once on the form, not per input. Submission still happens from the submit button, and from
 * Enter on the final field (where there is nothing to advance to). IME composition is respected —
 * pressing Enter to commit a Devanagari or Gujarati candidate must not jump the cursor away.
 */
export function handleFormEnter(event: React.KeyboardEvent<HTMLFormElement>): void {
  if (event.key !== "Enter" || event.shiftKey || event.altKey || event.ctrlKey || event.metaKey) return;
  if (event.nativeEvent.isComposing) return;
  const target = event.target;
  if (!isAdvanceableInput(target)) return;
  // Ctrl/Cmd+Enter is the conventional "submit anyway" escape hatch, handled by the guard above.
  if (focusNextField(target)) event.preventDefault();
}
