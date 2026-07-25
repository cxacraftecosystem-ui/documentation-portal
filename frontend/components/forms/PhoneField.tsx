"use client";

import { useMemo, useState } from "react";

import { Dropdown } from "@/components/ui/Dropdown";
import { useConfirm } from "@/components/dialogs";
import {
  COUNTRIES,
  DEFAULT_DIAL_CODE,
  DEFAULT_ISO2,
  countryByIso2,
  countryForDialCode,
  flagEmoji,
  splitDialPrefix
} from "@/lib/countries";

const FOREIGN_CONFIRM = "This marks the artisan as a foreign resident. Continue?";

/** Split a stored phone ("+CC rest", "+CCrest", or bare 10 digits) into prefix + national digits. */
function parsePhone(raw: string | null | undefined): { iso2: string; digits: string } {
  const value = (raw ?? "").trim();
  if (!value) return { iso2: DEFAULT_ISO2, digits: "" };
  if (value.startsWith("+")) {
    const space = value.indexOf(" ");
    if (space > 0) {
      const country = countryForDialCode(value.slice(0, space));
      if (country) return { iso2: country.iso2, digits: value.slice(space + 1).replace(/\D/g, "") };
    }
    const match = splitDialPrefix(value);
    if (match) return { iso2: match.country.iso2, digits: match.rest };
    return { iso2: DEFAULT_ISO2, digits: value.replace(/\D/g, "") };
  }
  // Bare numbers (legacy rows) are Indian nationals: 10 digits under +91.
  return { iso2: DEFAULT_ISO2, digits: value.replace(/\D/g, "") };
}

/**
 * Phone input with an ISD-prefix picker (flag emoji + dial code, default +91). Submits ONE string
 * ("+91 9876543210") under the existing `phone` field name via a zero-size mirror input, so the
 * FormData handlers and API payload are unchanged. +91 requires exactly 10 digits; any other code
 * 4-14 digits (both blocked natively via the mirror's pattern AND surfaced as an inline error).
 * Switching away from +91 asks for confirmation — it marks the artisan as a foreign resident.
 */
export function PhoneField({
  name = "phone",
  defaultValue,
  onValueChange
}: {
  name?: string;
  defaultValue?: string | null;
  onValueChange?: (value: string) => void;
}) {
  const [{ iso2, digits }, setState] = useState(() => parsePhone(defaultValue));
  const country = countryByIso2(iso2) ?? countryByIso2(DEFAULT_ISO2)!;
  const dialCode = country.dialCode;
  const combined = digits ? `${dialCode} ${digits}` : "";

  // Android parity (MainActivity.kt phoneValidationError): same rule, same two sentences, so a
  // researcher who corrects a number on the phone reads the same instruction on the laptop.
  const error = !digits
    ? null
    : dialCode === DEFAULT_DIAL_CODE && digits.length !== 10
      ? "Enter a 10-digit number for +91."
      : dialCode !== DEFAULT_DIAL_CODE && (digits.length < 4 || digits.length > 14)
        ? "Enter a valid phone number (4–14 digits)."
        : null;

  const options = useMemo(
    () => COUNTRIES.map((entry) => ({ value: entry.iso2, label: `${flagEmoji(entry.iso2)} ${entry.dialCode}` })),
    []
  );

  function emit(next: { iso2: string; digits: string }) {
    setState(next);
    const nextCountry = countryByIso2(next.iso2);
    onValueChange?.(next.digits && nextCountry ? `${nextCountry.dialCode} ${next.digits}` : "");
  }

  const confirm = useConfirm();
  async function handleCountry(nextIso2: string) {
    if (nextIso2 === iso2) return;
    const next = countryByIso2(nextIso2);
    if (!next) return;
    if (dialCode === DEFAULT_DIAL_CODE && next.dialCode !== DEFAULT_DIAL_CODE) {
      // Not destructive — a "did you mean" check — so this warns rather than alarms.
      // Cancel keeps the +91 prefix untouched.
      const ok = await confirm({
        title: "Use an international number?",
        body: FOREIGN_CONFIRM,
        confirmLabel: `Use ${next.dialCode}`,
        cancelLabel: "Keep +91",
        tone: "warning"
      });
      if (!ok) return;
    }
    emit({ iso2: nextIso2, digits });
  }

  return (
    <div className="relative grid gap-1">
      <div className="flex gap-2">
        <Dropdown
          className="w-36 shrink-0"
          value={iso2}
          onChange={handleCountry}
          options={options}
          ariaLabel="Country dial code"
        />
        <input
          className="field-input min-w-0 flex-1"
          type="text"
          inputMode="numeric"
          autoComplete="tel-national"
          placeholder={dialCode === DEFAULT_DIAL_CODE ? "10-digit mobile number" : "Phone number"}
          value={digits}
          aria-invalid={!!error}
          onChange={(event) => emit({ iso2, digits: event.target.value.replace(/\D/g, "").slice(0, 15) })}
        />
      </div>
      {error ? <p className="text-xs text-error-600">{error}</p> : null}
      {/* Zero-size (not hidden) mirror input: submits the single combined value under the existing
          field name AND participates in native constraint validation via the pattern. */}
      <input
        type="text"
        name={name}
        value={combined}
        pattern={digits ? (dialCode === DEFAULT_DIAL_CODE ? "\\+91 \\d{10}" : "\\+\\d{1,4} \\d{4,14}") : undefined}
        onChange={() => undefined}
        tabIndex={-1}
        aria-hidden="true"
        className="pointer-events-none absolute h-0 w-0 border-0 p-0 opacity-0"
      />
    </div>
  );
}
