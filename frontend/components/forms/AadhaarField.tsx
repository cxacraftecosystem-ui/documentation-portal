"use client";

import Link from "next/link";
import { useEffect, useId, useRef, useState } from "react";

import { RequiredMark } from "@/components/ui/RequiredMark";
import { apiFetch } from "@/lib/api";
import type { AadhaarLookupResult, ArtisanIdentityMatch } from "@/lib/types";

const AADHAAR_LENGTH = 12;

const VERHOEFF_D = [
  [0, 1, 2, 3, 4, 5, 6, 7, 8, 9],
  [1, 2, 3, 4, 0, 6, 7, 8, 9, 5],
  [2, 3, 4, 0, 1, 7, 8, 9, 5, 6],
  [3, 4, 0, 1, 2, 8, 9, 5, 6, 7],
  [4, 0, 1, 2, 3, 9, 5, 6, 7, 8],
  [5, 9, 8, 7, 6, 0, 4, 3, 2, 1],
  [6, 5, 9, 8, 7, 1, 0, 4, 3, 2],
  [7, 6, 5, 9, 8, 2, 1, 0, 4, 3],
  [8, 7, 6, 5, 9, 3, 2, 1, 0, 4],
  [9, 8, 7, 6, 5, 4, 3, 2, 1, 0]
];

const VERHOEFF_P = [
  [0, 1, 2, 3, 4, 5, 6, 7, 8, 9],
  [1, 5, 7, 6, 2, 8, 3, 0, 9, 4],
  [5, 8, 0, 3, 7, 9, 6, 1, 4, 2],
  [8, 9, 1, 6, 0, 4, 3, 5, 2, 7],
  [9, 4, 5, 3, 1, 2, 6, 8, 7, 0],
  [4, 2, 8, 6, 5, 7, 3, 9, 0, 1],
  [2, 7, 9, 3, 8, 0, 6, 4, 1, 5],
  [7, 0, 4, 6, 9, 1, 3, 2, 5, 8]
];

function verhoeffOk(digits: string): boolean {
  let checksum = 0;
  for (let index = 0; index < digits.length; index += 1) {
    const digit = digits.charCodeAt(digits.length - 1 - index) - 48;
    checksum = VERHOEFF_D[checksum][VERHOEFF_P[index % 8][digit]];
  }
  return checksum === 0;
}

export function aadhaarValidationError(value: string | null | undefined): string | null {
  const digits = (value ?? "").trim();
  if (!digits) return null;
  if (!/^\d+$/.test(digits)) return "Aadhaar must be 12 digits.";
  if (digits.length !== AADHAAR_LENGTH) {
    return `Aadhaar must be 12 digits (has ${digits.length}).`;
  }
  if (digits[0] === "0" || digits[0] === "1") {
    return "Aadhaar doesn’t start with 0 or 1.";
  }
  if (!verhoeffOk(digits)) {
    return "A digit is wrong — check the card.";
  }
  return null;
}

export function isMaskedIdentityNumber(value: string | null | undefined): boolean {
  return typeof value === "string" && /x/i.test(value);
}

const AADHAAR_REQUIRED =
  "Enter the 12-digit Aadhaar number.";

const LOOKUP_DEBOUNCE_MS = 400;

function digitsOnly(value: string | null | undefined) {
  return (value ?? "").replace(/\D/g, "").slice(0, AADHAAR_LENGTH);
}

function grouped(digits: string) {
  return digits.replace(/(\d{4})(?=\d)/g, "$1 ");
}

function describeMatch(match: ArtisanIdentityMatch) {
  return [match.name, match.place, match.craft, match.workshop].filter(Boolean).join(" · ");
}

export function AadhaarField({
  name = "aadhaarNumber",
  defaultValue,
  excludeArtisanId,
  required = false,
  onValueChange
}: {
  name?: string;
  defaultValue?: string | null;
  excludeArtisanId?: string | null;
  required?: boolean;
  onValueChange?: (digits: string) => void;
}) {
  const baseId = useId();
  const inputId = `${baseId}-aadhaar`;
  const hintId = `${baseId}-aadhaar-hint`;
  const replaceId = `${baseId}-aadhaar-replace`;
  const warningId = `${baseId}-aadhaar-warning`;
  const inputRef = useRef<HTMLInputElement>(null);
  const storedMask = isMaskedIdentityNumber(defaultValue) ? String(defaultValue).trim() : null;
  const [replacing, setReplacing] = useState(false);
  const [digits, setDigits] = useState(() => (isMaskedIdentityNumber(defaultValue) ? "" : digitsOnly(defaultValue)));
  const [match, setMatch] = useState<ArtisanIdentityMatch | null>(null);
  const [checking, setChecking] = useState(false);
  const [problemShown, setProblemShown] = useState(false);

  const keptMask = replacing ? null : storedMask;
  const complete = digits.length === AADHAAR_LENGTH;
  const problem = keptMask ? null : aadhaarValidationError(digits) ?? (required && !digits ? AADHAAR_REQUIRED : null);
  const lookupReady = complete && !problem;

  useEffect(() => {
    inputRef.current?.setCustomValidity(problem ?? "");
  }, [problem]);

  useEffect(() => {
    if (!lookupReady) {
      setMatch(null);
      setChecking(false);
      return;
    }
    setMatch(null);
    setChecking(true);
    const controller = new AbortController();
    const timer = setTimeout(() => {
      apiFetch<AadhaarLookupResult>("/artisans/lookup/aadhaar", {
        method: "POST",
        body: JSON.stringify({ number: digits }),
        signal: controller.signal
      })
        .then((result) => {
          const found = result.found ? (result.artisan ?? null) : null;
          setMatch(found && found.id !== excludeArtisanId ? found : null);
        })
        .catch(() => {
        })
        .finally(() => {
          if (!controller.signal.aborted) setChecking(false);
        });
    }, LOOKUP_DEBOUNCE_MS);
    return () => {
      clearTimeout(timer);
      controller.abort();
    };
  }, [digits, lookupReady, excludeArtisanId]);

  function replaceStoredNumber() {
    setReplacing(true);
    setDigits("");
    setProblemShown(false);
    inputRef.current?.focus();
  }

  function keepStoredNumber() {
    setReplacing(false);
    setDigits("");
    setProblemShown(false);
  }

  return (
    <div className="relative grid content-start gap-1">
      <label className="field-label" htmlFor={inputId}>
        Aadhaar number
        <RequiredMark when={required} />
      </label>
      <input
        ref={inputRef}
        id={inputId}
        className="field-input read-only:bg-surface-50 read-only:text-ink-500"
        type="text"
        inputMode="numeric"
        autoComplete="off"
        placeholder="1234 5678 9012"
        value={keptMask ?? grouped(digits)}
        readOnly={Boolean(keptMask)}
        aria-required={required || undefined}
        aria-invalid={problemShown && problem ? true : undefined}
        aria-describedby={[hintId, storedMask && replacing ? replaceId : null, match ? warningId : null]
          .filter(Boolean)
          .join(" ")}
        onInvalid={() => setProblemShown(true)}
        onChange={(event) => {
          const next = digitsOnly(event.target.value);
          setDigits(next);
          setProblemShown(false);
          onValueChange?.(next);
        }}
      />
      {problemShown && problem ? (
        <p id={hintId} className="text-xs text-error-600">
          {problem}
        </p>
      ) : keptMask ? (
        <p id={hintId} className="text-xs text-ink-muted">
          On file and hidden. Saving keeps it.{" "}
          <button type="button" className="font-semibold text-purple-700 dark:text-purple-300 underline" onClick={replaceStoredNumber}>
            Replace this number
          </button>
        </p>
      ) : (
        <p id={hintId} className="text-xs text-ink-muted">
          {required
            ? "12 digits from the card. Prevents duplicate records."
            : "12 digits from the card."}
          {digits.length > 0 && !complete ? ` ${digits.length} of ${AADHAAR_LENGTH} entered.` : ""}
        </p>
      )}
      {storedMask && replacing ? (
        <p id={replaceId} className="text-xs text-ink-muted">
          All 12 digits replace the number already on file.{" "}
          <button type="button" className="font-semibold text-purple-700 dark:text-purple-300 underline" onClick={keepStoredNumber}>
            Keep the stored number
          </button>
        </p>
      ) : null}
      {checking ? <p className="text-xs text-ink-muted">Checking for an existing artisan...</p> : null}
      {match ? (
        <div
          id={warningId}
          role="status"
          className="rounded-md border border-amber-200 bg-amber-50 px-3 py-2 text-sm text-amber-800"
        >
          <p className="font-medium">This Aadhaar number is already on an artisan record.</p>
          <p className="mt-0.5">{describeMatch(match)}</p>
          <Link className="mt-1 inline-block font-medium underline" href={`/artisans/${match.id}/edit`}>
            Open that artisan instead
          </Link>
          <p className="mt-1 text-xs">
            A true duplicate won’t be accepted.
          </p>
        </div>
      ) : null}
      <input
        type="text"
        name={name}
        value={keptMask ?? digits}
        onChange={() => undefined}
        tabIndex={-1}
        aria-hidden="true"
        className="pointer-events-none absolute h-0 w-0 border-0 p-0 opacity-0"
      />
    </div>
  );
}
