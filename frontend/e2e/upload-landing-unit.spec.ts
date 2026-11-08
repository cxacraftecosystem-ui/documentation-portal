import { expect, test } from "@playwright/test";

import { ApiError } from "@/lib/api";
import { uploadNeverLanded } from "@/lib/media";

test("a completion that finds no file in storage asks the client to upload again", () => {
  expect(uploadNeverLanded(new ApiError(409, "The file didn't reach storage. Upload it again.", { detail: "The file didn't reach storage. Upload it again." }))).toBe(true);
});

test("other refusals are never mistaken for a missing upload", () => {
  for (const status of [400, 403, 404, 413, 422, 500, 503]) {
    expect(uploadNeverLanded(new ApiError(status, "refused", { detail: "refused" }))).toBe(false);
  }
  expect(uploadNeverLanded(new TypeError("Failed to fetch"))).toBe(false);
});
