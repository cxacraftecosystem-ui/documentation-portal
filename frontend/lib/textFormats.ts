import {
  DEFAULT_DIAL_CODE,
  DEFAULT_ISO2,
  countryByIso2,
  countryForDialCode,
  splitDialPrefix
} from "@/lib/countries";

export const EMAIL_PATTERN = "[^\\s@]+@[^\\s@]+\\.[^\\s@]+";

export const EMAIL_RE = new RegExp(`^${EMAIL_PATTERN}$`);

export const EMAIL_MESSAGE = "Enter a valid email address (name@example.com).";

export function emailValidationError(value: string | null | undefined): string | null {
  const trimmed = (value ?? "").trim();
  if (!trimmed) return null;
  return EMAIL_RE.test(trimmed) ? null : EMAIL_MESSAGE;
}

export function splitStoredPhone(raw: string | null | undefined): { iso2: string; digits: string } {
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
  return { iso2: DEFAULT_ISO2, digits: value.replace(/\D/g, "") };
}

const PHONE_SEPARATORS = /[ \t\r\n\u00a0\u2010-\u2015-]+/g;
const PHONE_COMPACT = /^\+?[0-9]+$/;

export function isPhoneNumberShaped(stored: string | null | undefined): boolean {
  const compact = (stored ?? "").trim().replace(PHONE_SEPARATORS, "");
  return compact !== "" && PHONE_COMPACT.test(compact);
}

export const PHONE_IN_MESSAGE = "Enter a 10-digit number for +91.";
export const PHONE_INTL_MESSAGE = "Enter a valid phone number (4–14 digits).";

export function phoneValidationError(stored: string | null | undefined): string | null {
  if (!(stored ?? "").trim()) return null;
  const { iso2, digits } = splitStoredPhone(stored);
  const shaped = isPhoneNumberShaped(stored);
  const dialCode = (countryByIso2(iso2) ?? countryByIso2(DEFAULT_ISO2)!).dialCode;
  if (dialCode === DEFAULT_DIAL_CODE) return shaped && digits.length === 10 ? null : PHONE_IN_MESSAGE;
  return shaped && digits.length >= 4 && digits.length <= 14 ? null : PHONE_INTL_MESSAGE;
}
