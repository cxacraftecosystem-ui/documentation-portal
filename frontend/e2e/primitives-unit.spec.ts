/**
 * THE FIVE SHARED PRIMITIVES (owner, 2026-10-17) — Toggle, SearchableSelect, SearchableMultiSelect,
 * CalendarField, OtpCodeInput — and the two-step verification switch built from them.
 *
 * Pure functions from `lib/otp.ts` and `lib/twoFactor.ts`, plus source assertions for what lives in
 * JSX (there is no React renderer in devDependencies). Line-ending agnostic: `\s`, never `\n`.
 */

import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import {
  OTP_LENGTH,
  backspaceAt,
  digitLabel,
  emptyDigits,
  enterDigits,
  isCompleteCode,
  joinDigits,
  onlyDigits,
  splitCode
} from "@/lib/otp";
import { twoFactorStatusLine, twoFactorView, type TwoFactorStatus } from "@/lib/twoFactor";

const read = (...parts: string[]) => readFileSync(join(__dirname, "..", ...parts), "utf8");

const OTP = read("components", "ui", "OtpCodeInput.tsx");
const TOGGLE = read("components", "ui", "Toggle.tsx");
const SELECT = read("components", "ui", "SearchableSelect.tsx");
const CALENDAR = read("components", "ui", "CalendarField.tsx");
const CARD = read("components", "settings", "TwoStepVerificationCard.tsx");
const CHALLENGE = read("components", "TwoStepChallengeForm.tsx");
const PASSWORD = read("components", "settings", "ChangePasswordCard.tsx");
const ROUTES = read("..", "backend", "app", "api", "routes", "two_factor.py");

// --- OTP: digits only, six boxes, paste, backspace, completion ---------------------------------------

test("the code is exactly six digits", () => {
  expect(OTP_LENGTH).toBe(6);
  expect(emptyDigits()).toEqual(["", "", "", "", "", ""]);
  expect(isCompleteCode("123456")).toBe(true);
  expect(isCompleteCode("12345")).toBe(false);
  expect(isCompleteCode("1234567")).toBe(false);
  expect(isCompleteCode("12345a")).toBe(false);
  expect(isCompleteCode("12 456")).toBe(false);
});

test("only 0-9 ever lands in a box", () => {
  expect(onlyDigits("a1b2-c3 ٤")).toBe("123");
  expect(enterDigits(emptyDigits(), 0, "a")).toEqual({ digits: emptyDigits(), focus: 0 });
  expect(enterDigits(emptyDigits(), 2, "-").digits).toEqual(emptyDigits());
  expect(enterDigits(emptyDigits(), 0, "7").digits).toEqual(["7", "", "", "", "", ""]);
  expect(splitCode("12ab34")).toEqual(["1", "2", "3", "4", "", ""]);
});

test("typing advances one box at a time", () => {
  let state = { digits: emptyDigits(), focus: 0 };
  for (const digit of "4826") state = enterDigits(state.digits, state.focus, digit);
  expect(joinDigits(state.digits)).toBe("4826");
  expect(state.focus).toBe(4);
  // The last box keeps focus rather than walking off the end.
  state = enterDigits(state.digits, 4, "1");
  state = enterDigits(state.digits, state.focus, "9");
  expect(joinDigits(state.digits)).toBe("482619");
  expect(state.focus).toBe(5);
});

test("a pasted six-digit code fills every box from the first, whichever box had focus", () => {
  const pasted = enterDigits(emptyDigits(), 3, "123 456");
  expect(pasted.digits).toEqual(["1", "2", "3", "4", "5", "6"]);
  expect(pasted.focus).toBe(5);
  expect(isCompleteCode(joinDigits(pasted.digits))).toBe(true);
  // A longer string (an SMS line) still yields only six.
  expect(joinDigits(enterDigits(emptyDigits(), 0, "Your code is 987654, valid 30s").digits)).toBe("987654");
});

test("backspace empties a box, then walks back on an empty one", () => {
  const full = splitCode("123456");
  let edit = backspaceAt(full, 5);
  expect(edit).toEqual({ digits: ["1", "2", "3", "4", "5", ""], focus: 5 });
  edit = backspaceAt(edit.digits, 5);
  expect(edit).toEqual({ digits: ["1", "2", "3", "4", "", ""], focus: 4 });
  expect(backspaceAt(emptyDigits(), 0)).toEqual({ digits: emptyDigits(), focus: 0 });
});

test("each box is announced as digit n of 6", () => {
  expect(digitLabel(0)).toBe("Digit 1 of 6");
  expect(digitLabel(5)).toBe("Digit 6 of 6");
});

test("the OTP input is six numeric boxes in Plus Jakarta Sans, with autofill and reduced motion", () => {
  expect(OTP).toMatch(/inputMode="numeric"/);
  expect(OTP).toMatch(/pattern="\[0-9\]\*"/);
  expect(OTP).toMatch(/"one-time-code"/);
  expect(OTP).toMatch(/font-display/); // --font-jakarta, Plus Jakarta Sans
  expect(OTP).toMatch(/tabular-nums/);
  expect(OTP).toMatch(/onComplete\?\.\(code\)/);
  expect(OTP).toMatch(/prefers-reduced-motion: reduce/);
  expect(OTP).toMatch(/data-reduced-motion/);
  expect(OTP).toMatch(/aria-invalid/);
});

test("every 6-digit surface uses the OTP input, and recovery codes have their own field", () => {
  for (const source of [CARD, CHALLENGE, PASSWORD]) {
    expect(source).toMatch(/<OtpCodeInput/);
    expect(source).toMatch(/Use a recovery code instead/);
    // No single-box code input left behind.
    expect(source).not.toMatch(/placeholder="123456"/);
  }
});

// --- Toggle ------------------------------------------------------------------------------------------

test("the toggle is a real switch", () => {
  expect(TOGGLE).toMatch(/role="switch"/);
  expect(TOGGLE).toMatch(/aria-checked=\{on\}/);
  expect(TOGGLE).toMatch(/type="button"/);
  // Row shape names itself with the label and describes itself with the description.
  expect(TOGGLE).toMatch(/aria-labelledby=\{labelId\}/);
  expect(TOGGLE).toMatch(/aria-describedby=\{description \? descriptionId : undefined\}/);
  // A 44px target on a touch screen.
  expect(TOGGLE).toMatch(/pointer-coarse:h-11/);
  // Busy (waiting on the server) cannot be flipped.
  expect(TOGGLE).toMatch(/if \(disabled \|\| busy\) return;/);
});

test("no setting on the converted surfaces is a checkbox or ON/OFF words", () => {
  const files = [
    ["app", "(protected)", "users", "AddPersonForm.tsx"],
    ["app", "(protected)", "users", "AccountDialogs.tsx"],
    ["app", "(protected)", "users", "[id]", "page.tsx"],
    ["components", "forms", "ProcessForm.tsx"],
    ["components", "trace", "TracePanel.tsx"],
    ["components", "settings", "PersonalSettingsCards.tsx"],
    ["components", "settings", "EmailNotificationsCard.tsx"]
  ];
  for (const parts of files) {
    expect(read(...parts), parts.join("/")).not.toMatch(/type="checkbox"/);
  }
  expect(read("components", "DynamicIslandNav.tsx")).not.toMatch(/Admin view: ON|Admin view: OFF/);
});

// --- Selects and the calendar -------------------------------------------------------------------------

test("the searchable selects virtualise long lists, clear, and chip their picks", () => {
  expect(SELECT).toMatch(/const VIRTUALIZE_AT = 200;/);
  expect(SELECT).toMatch(/aria-setsize/);
  expect(SELECT).toMatch(/clearable\?: boolean/);
  expect(SELECT).toMatch(/aria-label=\{`Remove \$\{option\.label\}`\}/);
  expect(SELECT).toMatch(/role="combobox"/);
  expect(SELECT).toMatch(/role="listbox"/);
});

test("no native date input or select is left on the converted surfaces", () => {
  expect(read("components", "forms", "ArtisanForm.tsx")).not.toMatch(/type="date"/);
  for (const parts of [
    ["app", "(protected)", "users", "AddPersonForm.tsx"],
    ["app", "(protected)", "users", "[id]", "page.tsx"],
    ["components", "settings", "MyAiKeysPanel.tsx"],
    ["components", "trace", "TracePanel.tsx"]
  ]) {
    // A JSX element opens its own line; a comment that NAMES the native control does not count.
    expect(read(...parts), parts.join("/")).not.toMatch(/^\s*<select\b/m);
  }
  expect(CALENDAR).toMatch(/DateField/);
  expect(CALENDAR).toMatch(/Clear date/);
});

// --- Two-step verification: Off / Setting up / On ---------------------------------------------------

const off: TwoFactorStatus = { enabled: false, pending: false, recovery_codes_remaining: 0, enabled_at: null };
const pendingOnly: TwoFactorStatus = { ...off, pending: true };
const on: TwoFactorStatus = { enabled: true, pending: false, recovery_codes_remaining: 8, enabled_at: "2026-10-17T09:00:00Z" };

test("only a verified code turns the switch on", () => {
  expect(twoFactorView(off, false)).toBe("off");
  expect(twoFactorView(off, true)).toBe("setting-up");
  // A setup left over from an earlier visit is OFF, never on.
  expect(twoFactorView(pendingOnly, false)).toBe("off");
  expect(twoFactorView(on, false)).toBe("on");
  expect(twoFactorView(null, false)).toBe("off");
});

test("the status line says it in plain words", () => {
  const date = () => "17 Oct 2026";
  expect(twoFactorStatusLine(on, "on", date)).toBe("On since 17 Oct 2026 · 8 recovery codes left");
  expect(twoFactorStatusLine({ ...on, recovery_codes_remaining: 1 }, "on", date)).toBe(
    "On since 17 Oct 2026 · 1 recovery code left"
  );
  expect(twoFactorStatusLine(off, "setting-up", date)).toMatch(/^Setting up…/);
  expect(twoFactorStatusLine(pendingOnly, "off", date)).toMatch(/^Off\./);
});

test("the two-step card is a switch that discards an unfinished setup on every way out", () => {
  expect(CARD).toMatch(/<Toggle/);
  expect(CARD).toMatch(/label="Two-step verification"/);
  expect(CARD).toMatch(/checked=\{view === "on"\}/);
  expect(CARD).toMatch(/cancelTwoFactorSetup\(\{ keepalive: true \}\)/);
  expect(CARD).toMatch(/"pagehide"/);
  expect(CARD).toMatch(/visibilitychange/);
  // No bare "Off." sentence standing in for the state.
  expect(CARD).not.toMatch(/>Off\.<\/p>/);
  expect(ROUTES).toMatch(/@router\.post\("\/setup\/cancel"/);
  expect(ROUTES).toMatch(/"twoFactorEnabled": False/);
});
