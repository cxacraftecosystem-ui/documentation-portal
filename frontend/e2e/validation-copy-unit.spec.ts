import { expect, test } from "@playwright/test";

import { describeApiDetail, validationSentence } from "@/lib/api";

const BAD_EMAIL = {
  detail: [
    {
      type: "value_error",
      loc: ["body", "email"],
      msg: "value is not a valid email address: An email address must have an @-sign.",
      input: "someone"
    }
  ]
};

test("a mistyped email reads as a short sentence, never the validator's words", () => {
  const shown = describeApiDetail(BAD_EMAIL.detail, "unused");
  expect(shown).toBe("Enter a valid email address.");
  expect(shown).not.toMatch(/email:|@-sign|value is not/);
});

test("the validator's stock messages become plain copy", () => {
  expect(validationSentence("email", "Field required")).toBe("Enter your email address.");
  expect(validationSentence("password", "Field required")).toBe("Password is required.");
  expect(validationSentence("newPassword", "String should have at least 8 characters")).toBe("New password needs at least 8 characters.");
  expect(validationSentence("display_name", "String should have at most 120 characters")).toBe("Display name can be at most 120 characters.");
  expect(validationSentence("code", "Input should be a valid string")).toBe("Check code.");
  expect(validationSentence(null, "Field required")).toBe("This field is required.");
});

test("a server's own sentence still passes through as before", () => {
  expect(describeApiDetail([{ msg: "Value error, Unknown dimension", loc: ["query", "dimension"] }], "unused")).toBe("dimension: Unknown dimension");
  expect(describeApiDetail("Invalid email or password", "unused")).toBe("Invalid email or password");
  expect(validationSentence("dimension", "Unknown dimension")).toBeNull();
});
