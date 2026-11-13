import { expect, test } from "@playwright/test";

import { ApiError, apiErrorMessage, apiFieldErrors, describeApiBody, describeApiDetail, statusFallback } from "@/lib/api";

const NEW_SHAPE = {
  detail: [
    {
      type: "string_too_short",
      loc: ["body", "craftName"],
      field: "craftName",
      label: "Craft name",
      msg: "Craft name can't be empty."
    },
    {
      type: "missing",
      loc: ["body", "toolkitName"],
      field: "toolkitName",
      label: "Tool name",
      msg: "Tool name is required."
    }
  ],
  message: "Craft name can't be empty.",
  code: "validation_failed"
};

const OLD_SHAPE = {
  detail: [
    { type: "string_too_short", loc: ["body", "craftName"], msg: "String should have at least 1 character", input: "" },
    { type: "string_too_short", loc: ["body", "artisanName"], msg: "String should have at least 1 character", input: "" },
    { type: "value_error", loc: ["body", "replacementCost"], msg: "Value error, must be positive", input: -2 }
  ]
};

const RAW = /status code|HTTP \d|\[object Object\]|[{}[\]]|"loc"|string_too_short/;

test("the top-level message wins on the new validation envelope", () => {
  expect(describeApiBody(NEW_SHAPE, "unused")).toBe("Craft name can't be empty.");
  expect(apiErrorMessage(new ApiError(422, "ignored", NEW_SHAPE), "unused")).toBe("Craft name can't be empty.");
});

test("new-shape entries without a top-level message read as their own sentences", () => {
  expect(describeApiDetail(NEW_SHAPE.detail, "unused")).toBe("Craft name can't be empty. Tool name is required.");
});

test("old pydantic entries get a readable field label from loc", () => {
  const shown = describeApiDetail(OLD_SHAPE.detail, "unused");
  expect(shown).toBe("Craft name can't be empty. Artisan name can't be empty. Replacement cost: must be positive");
  expect(shown).not.toMatch(RAW);
});

test("repeated sentences are said once", () => {
  const twice = [OLD_SHAPE.detail[0], OLD_SHAPE.detail[0]];
  expect(describeApiDetail(twice, "unused")).toBe("Craft name can't be empty.");
});

test("a sentence or a coded object in detail passes through", () => {
  expect(describeApiBody({ detail: "This craft already exists." }, "unused")).toBe("This craft already exists.");
  expect(describeApiBody({ detail: { code: "duplicate", message: "That name is taken." } }, "unused")).toBe("That name is taken.");
});

test("an unreadable body falls back to a sentence for the status, never the code", () => {
  for (const status of [400, 409, 413, 422]) {
    const sentence = statusFallback(status, "Something went wrong. Try again.");
    expect(sentence).not.toMatch(RAW);
    expect(describeApiBody({ detail: [{ msg: { nested: true } }] }, sentence)).toBe(sentence);
    expect(describeApiBody("<html>bad gateway</html>", sentence)).toBe(sentence);
  }
  expect(statusFallback(500, "Something went wrong. Try again.")).toBe("Something went wrong. Try again.");
});

test("apiErrorMessage never shows a status line, raw JSON or an object", () => {
  expect(apiErrorMessage(new Error("Request failed with status code 422"), "Not saved.")).toBe("Not saved.");
  expect(apiErrorMessage(new Error("HTTP 422"), "Not saved.")).toBe("Not saved.");
  expect(apiErrorMessage(new Error("[object Object]"), "Not saved.")).toBe("Not saved.");
  expect(apiErrorMessage(new Error('{"detail":[]}'), "Not saved.")).toBe("Not saved.");
  expect(apiErrorMessage(new ApiError(422, "Some details weren't accepted.", null), "Not saved.")).toBe("Some details weren't accepted.");
  expect(apiErrorMessage(new Error("You're offline."), "Not saved.")).toBe("You're offline.");
});

test("field errors map to the field each entry names, in both shapes", () => {
  expect(apiFieldErrors(new ApiError(422, "x", NEW_SHAPE))).toEqual({
    craftName: "Craft name can't be empty.",
    toolkitName: "Tool name is required."
  });
  expect(apiFieldErrors(new ApiError(422, "x", OLD_SHAPE))).toEqual({
    craftName: "Craft name can't be empty.",
    artisanName: "Artisan name can't be empty.",
    replacementCost: "Replacement cost: must be positive"
  });
  expect(apiFieldErrors(new ApiError(409, "x", { detail: "Taken." }))).toEqual({});
  expect(apiFieldErrors(new Error("x"))).toEqual({});
});
