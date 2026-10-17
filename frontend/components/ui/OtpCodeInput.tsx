"use client";

/**
 * `OtpCodeInput` — the ONE way to type a 6-digit authenticator code, on every surface.
 *
 * Owner's ruling (2026-10-17): six separate boxes, digits only, in Plus Jakarta Sans with tabular
 * figures, large. The rules live in `lib/otp.ts` (pure, unit-tested); this file is the drawing and
 * the keyboard.
 *
 * - Each box is `inputMode="numeric"`, `pattern="[0-9]*"`; the FIRST carries
 *   `autoComplete="one-time-code"` so a phone's SMS/authenticator autofill lands somewhere, and a
 *   whole code arriving in any one box (autofill, paste) fills all six.
 * - Typing a digit advances; Backspace on an empty box steps back; ArrowLeft/Right move; anything that
 *   is not 0-9 is refused at the keystroke AND stripped from whatever input event slips past.
 * - `onComplete` fires the moment the sixth digit lands, with the code.
 * - `error` paints all six boxes red and shakes the row once. The shake is motion; the red border and
 *   `aria-invalid` are the static half that a reduced-motion reader still gets. Both reduced-motion
 *   switches (the OS and the app's own `data-reduced-motion`) are read at the moment of the shake, so
 *   this works on /login, which has no ThemeProvider.
 * - Recovery codes are NOT typed here. They are a different shape; the caller offers a separate text
 *   field behind a "Use a recovery code instead" switch.
 */

import { useEffect, useId, useRef, useState } from "react";

import {
  OTP_LENGTH,
  backspaceAt,
  digitLabel,
  enterDigits,
  joinDigits,
  onlyDigits,
  splitCode,
  type OtpEdit
} from "@/lib/otp";
import { cn } from "@/lib/utils";

export type OtpCodeInputProps = {
  /** The digits typed so far (0-6 of them). Controlled. */
  value: string;
  onChange: (code: string) => void;
  /** Fired once all six boxes hold a digit. */
  onComplete?: (code: string) => void;
  /** The group's visible label. */
  label?: string;
  /** Hide the visible label (the surrounding copy already says it); it stays as the group's name. */
  hideLabel?: boolean;
  /** Ids of hint / error text describing the group. */
  describedBy?: string;
  /** Red boxes + one shake. Pass a new `errorNonce` to shake again for the same message. */
  error?: boolean;
  errorNonce?: number;
  disabled?: boolean;
  autoFocus?: boolean;
  /** Submit the code with a form under this name. */
  name?: string;
  className?: string;
};

function prefersLessMotion(): boolean {
  if (typeof window === "undefined") return true;
  return (
    document.documentElement.getAttribute("data-reduced-motion") === "true" ||
    window.matchMedia?.("(prefers-reduced-motion: reduce)").matches === true
  );
}

export function OtpCodeInput({
  value,
  onChange,
  onComplete,
  label = "6-digit code",
  hideLabel = false,
  describedBy,
  error = false,
  errorNonce = 0,
  disabled = false,
  autoFocus = false,
  name,
  className
}: OtpCodeInputProps) {
  const baseId = useId();
  const labelId = `${baseId}-label`;
  const boxes = useRef<Array<HTMLInputElement | null>>([]);
  const group = useRef<HTMLDivElement | null>(null);

  // The boxes are local state (a gap in the middle cannot be expressed in a plain string), re-seeded
  // whenever the caller's value stops matching what was last reported — a cleared code after a wrong
  // attempt, a reset form.
  const [digits, setDigits] = useState<string[]>(() => splitCode(value));
  const [reported, setReported] = useState(value);
  if (value !== reported) {
    setReported(value);
    setDigits(splitCode(value));
  }

  useEffect(() => {
    if (autoFocus) boxes.current[0]?.focus();
  }, [autoFocus]);

  // A focus request after a clear from outside: the first empty box is where the next digit goes.
  useEffect(() => {
    if (value === "" && autoFocus && group.current?.contains(document.activeElement)) {
      boxes.current[0]?.focus();
    }
  }, [value, autoFocus]);

  // The shake: once per error (and per nonce), never under reduced motion.
  useEffect(() => {
    if (!error || !group.current || prefersLessMotion()) return;
    group.current.animate?.(
      [
        { transform: "translateX(0)" },
        { transform: "translateX(-6px)" },
        { transform: "translateX(6px)" },
        { transform: "translateX(-4px)" },
        { transform: "translateX(4px)" },
        { transform: "translateX(0)" }
      ],
      { duration: 360, easing: "cubic-bezier(0.16, 1, 0.3, 1)" }
    );
  }, [error, errorNonce]);

  function apply(edit: OtpEdit) {
    const code = joinDigits(edit.digits);
    setDigits(edit.digits);
    setReported(code);
    onChange(code);
    const box = boxes.current[edit.focus];
    if (box && document.activeElement !== box) box.focus();
    box?.select();
    if (edit.digits.every(Boolean) && code.length === OTP_LENGTH) onComplete?.(code);
  }

  function handleInput(index: number, raw: string) {
    const clean = onlyDigits(raw);
    if (!clean) {
      // Everything typed was refused; put the box back to the digit it held.
      setDigits([...digits]);
      return;
    }
    if (clean.length >= OTP_LENGTH) {
      apply(enterDigits(digits, 0, clean));
      return;
    }
    // The box's old digit is still in `raw` when the caret sat beside it; keep only what is new.
    const previous = digits[index];
    const typed = previous && clean.length > 1 && clean.startsWith(previous) ? clean.slice(1) : clean;
    apply(enterDigits(digits, index, typed));
  }

  function handleKeyDown(index: number, event: React.KeyboardEvent<HTMLInputElement>) {
    if (event.key === "Backspace") {
      event.preventDefault();
      apply(backspaceAt(digits, index));
      return;
    }
    if (event.key === "Delete") {
      event.preventDefault();
      const next = [...digits];
      next[index] = "";
      apply({ digits: next, focus: index });
      return;
    }
    if (event.key === "ArrowLeft") {
      event.preventDefault();
      boxes.current[Math.max(0, index - 1)]?.focus();
      return;
    }
    if (event.key === "ArrowRight") {
      event.preventDefault();
      boxes.current[Math.min(OTP_LENGTH - 1, index + 1)]?.focus();
      return;
    }
    if (event.key === "Home") {
      event.preventDefault();
      boxes.current[0]?.focus();
      return;
    }
    if (event.key === "End") {
      event.preventDefault();
      boxes.current[OTP_LENGTH - 1]?.focus();
      return;
    }
    // A printable key that is not a digit never reaches the box.
    if (event.key.length === 1 && !/[0-9]/.test(event.key) && !event.ctrlKey && !event.metaKey && !event.altKey) {
      event.preventDefault();
    }
  }

  function handlePaste(index: number, event: React.ClipboardEvent<HTMLInputElement>) {
    event.preventDefault();
    apply(enterDigits(digits, index, event.clipboardData.getData("text")));
  }

  return (
    <div className={cn("grid gap-2", className)}>
      <span id={labelId} className={hideLabel ? "sr-only" : "text-sm font-medium text-ink-900"}>
        {label}
      </span>
      {name ? <input type="hidden" name={name} value={joinDigits(digits)} /> : null}
      <div
        ref={group}
        role="group"
        aria-labelledby={labelId}
        aria-describedby={describedBy}
        data-otp-code-input=""
        className="flex items-center gap-2 sm:gap-2.5"
      >
        {digits.map((digit, index) => (
          <input
            key={index}
            ref={(node) => {
              boxes.current[index] = node;
            }}
            type="text"
            inputMode="numeric"
            pattern="[0-9]*"
            autoComplete={index === 0 ? "one-time-code" : "off"}
            autoCorrect="off"
            spellCheck={false}
            enterKeyHint={index === OTP_LENGTH - 1 ? "done" : "next"}
            aria-label={digitLabel(index)}
            aria-invalid={error || undefined}
            disabled={disabled}
            value={digit}
            onChange={(event) => handleInput(index, event.target.value)}
            onKeyDown={(event) => handleKeyDown(index, event)}
            onPaste={(event) => handlePaste(index, event)}
            onFocus={(event) => event.target.select()}
            data-otp-box=""
            className={cn(
              "h-14 w-11 min-w-0 rounded-md border-2 bg-card text-center font-display text-2xl font-semibold tabular-nums text-ink-900 outline-hidden transition sm:h-16 sm:w-12 sm:text-3xl",
              "focus:ring-4 disabled:cursor-not-allowed disabled:opacity-60",
              error
                ? "border-error-600 focus:border-error-600 focus:ring-error-600/20"
                : digit
                  ? "border-purple-600 focus:border-purple-700 focus:ring-purple-600/15"
                  : "border-line-200 focus:border-purple-600 focus:ring-purple-600/15"
            )}
          />
        ))}
      </div>
    </div>
  );
}
