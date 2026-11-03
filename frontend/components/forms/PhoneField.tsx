"use client";

import { useId, useMemo, useState } from "react";

import { Dropdown } from "@/components/ui/Dropdown";
import { useConfirm } from "@/components/dialogs";
import {
  COUNTRIES,
  DEFAULT_DIAL_CODE,
  DEFAULT_ISO2,
  countryByIso2,
  flagEmoji
} from "@/lib/countries";
import { isPhoneNumberShaped, phoneValidationError, splitStoredPhone } from "@/lib/textFormats";

const FOREIGN_CONFIRM = "This marks the artisan as a foreign resident. Continue?";

export function PhoneField({
  name = "phone",
  defaultValue,
  onValueChange,
  disabled,
  mirror = true,
  required = false,
  countryCode,
  countryCodeName
}: {
  name?: string;
  defaultValue?: string | null;
  countryCode?: string | null;
  countryCodeName?: string;
  onValueChange?: (value: string) => void;
  disabled?: boolean;
  mirror?: boolean;
  required?: boolean;
}) {
  const [{ iso2, digits }, setState] = useState(() =>
    splitStoredPhone(
      countryCode && defaultValue && !defaultValue.trim().startsWith("+")
        ? `${countryCode} ${defaultValue.trim()}`
        : defaultValue
    )
  );
  const [edited, setEdited] = useState(false);
  const errorId = useId();
  const country = countryByIso2(iso2) ?? countryByIso2(DEFAULT_ISO2)!;
  const dialCode = country.dialCode;
  const combined = digits ? `${dialCode} ${digits}` : "";

  const stored = (defaultValue ?? "").trim();
  const unshown = !edited && stored !== "" && !isPhoneNumberShaped(stored) ? stored : "";

  const error = phoneValidationError(unshown || combined);

  const options = useMemo(
    () =>
      COUNTRIES.map((entry) => ({
        value: entry.iso2,
        label: `${flagEmoji(entry.iso2)} ${entry.dialCode}`,
        hint: entry.name
      })),
    []
  );

  function emit(next: { iso2: string; digits: string }) {
    setState(next);
    setEdited(true);
    const nextCountry = countryByIso2(next.iso2);
    onValueChange?.(next.digits && nextCountry ? `${nextCountry.dialCode} ${next.digits}` : "");
  }

  const confirm = useConfirm();
  async function handleCountry(nextIso2: string) {
    if (nextIso2 === iso2) return;
    const next = countryByIso2(nextIso2);
    if (!next) return;
    if (dialCode === DEFAULT_DIAL_CODE && next.dialCode !== DEFAULT_DIAL_CODE) {
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
          disabled={disabled}
          ariaLabel="Country dial code"
        />
        <input
          className="field-input min-w-0 flex-1"
          type="text"
          inputMode="numeric"
          autoComplete="tel-national"
          placeholder={dialCode === DEFAULT_DIAL_CODE ? "10-digit mobile number" : "Phone number"}
          value={digits}
          aria-label="Phone number"
          required={required}
          aria-invalid={!!error}
          aria-describedby={error ? errorId : undefined}
          disabled={disabled}
          onChange={(event) => emit({ iso2, digits: event.target.value.replace(/\D/g, "").slice(0, 15) })}
        />
      </div>
      {error ? (
        <p id={errorId} role="alert" className="text-xs text-error-600">
          {error}
        </p>
      ) : null}
      {unshown ? (
        <p className="text-xs leading-5 text-ink-500">
          This field holds “{unshown}”, which is not a phone number this box can show. Type the number to replace
          it.
        </p>
      ) : null}
      {mirror ? (
        countryCodeName ? (
          <>
            <input
              type="text"
              name={name}
              value={digits}
              pattern={digits ? (dialCode === DEFAULT_DIAL_CODE ? "\\d{10}" : "\\d{4,14}") : undefined}
              onChange={() => undefined}
              tabIndex={-1}
              aria-hidden="true"
              className="pointer-events-none absolute h-0 w-0 border-0 p-0 opacity-0"
            />
            <input type="hidden" name={countryCodeName} value={digits ? dialCode : ""} />
          </>
        ) : (
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
        )
      ) : null}
    </div>
  );
}
