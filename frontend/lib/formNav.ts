const FOCUSABLE = [
  "input:not([type='hidden']):not([disabled]):not([tabindex='-1'])",
  "select:not([disabled]):not([tabindex='-1'])",
  "textarea:not([disabled]):not([tabindex='-1'])",
  "button[data-form-field]:not([disabled])",
  "[data-form-field]:not([disabled]):not([tabindex='-1'])",
].join(",");

function isVisible(el: HTMLElement): boolean {
  if (el.hidden || el.getAttribute("aria-hidden") === "true") return false;
  if (el.offsetParent === null && getComputedStyle(el).position !== "fixed") return false;
  const rect = el.getBoundingClientRect();
  return rect.width > 0 || rect.height > 0;
}

function scopeOf(el: HTMLElement): HTMLElement {
  return (el.closest("form") as HTMLElement | null) ?? document.body;
}

export function focusableFields(el: HTMLElement): HTMLElement[] {
  return Array.from(scopeOf(el).querySelectorAll<HTMLElement>(FOCUSABLE)).filter(isVisible);
}

export function focusNextField(from: HTMLElement | null | undefined): boolean {
  if (!from) return false;
  const fields = focusableFields(from);
  let index = fields.indexOf(from);
  if (index === -1) index = fields.findIndex((f) => f === from || f.contains(from) || from.contains(f));
  if (index === -1) return false;

  const next = fields[index + 1];
  if (!next) return false;
  next.focus();
  if (next instanceof HTMLInputElement && next.type !== "checkbox" && next.type !== "radio") {
    const length = next.value.length;
    try {
      next.setSelectionRange(length, length);
    } catch {
    }
  }
  return true;
}

export function isAdvanceableInput(el: EventTarget | null): el is HTMLInputElement {
  if (!(el instanceof HTMLInputElement)) return false;
  if (el.disabled || el.readOnly) return false;
  const type = (el.type || "text").toLowerCase();
  return !["checkbox", "radio", "button", "submit", "reset", "file", "image"].includes(type);
}

export function handleFormEnter(event: React.KeyboardEvent<HTMLFormElement>): void {
  if (event.key !== "Enter" || event.shiftKey || event.altKey || event.ctrlKey || event.metaKey) return;
  if (event.nativeEvent.isComposing) return;
  const target = event.target;
  if (!isAdvanceableInput(target)) return;
  if (focusNextField(target)) event.preventDefault();
}
