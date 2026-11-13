import { expect, test } from "@playwright/test";

import { writeBody } from "@/lib/writeBody";

test("a create drops every blank or whitespace text field, nested objects included", () => {
  const body = writeBody(
    {
      toolkitName: "Chisel",
      localName: "",
      englishName: "   ",
      remarks: null,
      yearsInUse: 0,
      craftIds: [],
      location: { latitude: 27.17, longitude: 78.04, state: "Uttar Pradesh", village: " " }
    },
    "POST"
  );
  expect(body).toEqual({
    toolkitName: "Chisel",
    remarks: null,
    yearsInUse: 0,
    craftIds: [],
    location: { latitude: 27.17, longitude: 78.04, state: "Uttar Pradesh" }
  });
});

test("an update keeps an explicit blank so a cleared field stays cleared", () => {
  expect(writeBody({ localName: "", remarks: null }, "PATCH")).toEqual({ localName: "", remarks: null });
});

test("a derivable name is never sent blank when its id is set, on any method", () => {
  for (const method of ["POST", "PATCH", "PUT"] as const) {
    const body = writeBody(
      { craftName: "", craftId: "craft-1", artisanName: " ", artisanIds: ["artisan-1"], place: "", artisanId: "artisan-1" },
      method
    );
    expect(body).toEqual({ craftId: "craft-1", artisanIds: ["artisan-1"], artisanId: "artisan-1" });
  }
  expect(writeBody({ craftName: null, craftIds: ["craft-1"] }, "PATCH")).toEqual({ craftIds: ["craft-1"] });
});

test("a derivable name stays when nothing could derive it", () => {
  expect(writeBody({ craftName: "", craftId: null, craftIds: [] }, "PATCH")).toEqual({ craftName: "", craftId: null, craftIds: [] });
  expect(writeBody({ place: "", artisanIds: ["artisan-1"] }, "PATCH")).toEqual({ place: "", artisanIds: ["artisan-1"] });
  expect(writeBody({ craftName: "Dabu", craftId: "craft-1" }, "POST")).toEqual({ craftName: "Dabu", craftId: "craft-1" });
});

test("non-object bodies pass through untouched", () => {
  expect(writeBody(["a", ""], "POST")).toEqual(["a", ""]);
  expect(writeBody(null, "POST")).toBeNull();
});
