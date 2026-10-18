import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { ApiError } from "@/lib/api";
import {
  acknowledgedIds,
  candidatesToShow,
  candidateSubtitle,
  checkInputFrom,
  checkKey,
  holdFromPayload,
  mergedSurvivorId,
  possibleDuplicateHold,
  type DuplicateCandidate
} from "@/lib/duplicates";

/**
 * The artisan form's "Already recorded?" card: which rows it shows, and what a create sends.
 *
 * The server is the only scorer; these tests pin the client's half — the card lists at most three
 * candidates at or above the show threshold, never one already dismissed, and every create carries
 * `duplicateAcknowledged` (`[]` when nothing was shown), because a body without it opts out of the
 * server's hold.
 */

const read = (...parts: string[]) => readFileSync(join(__dirname, "..", ...parts), "utf8").replace(/\r\n/g, "\n");

function candidate(id: string, score: number, extra: Partial<DuplicateCandidate["artisan"]> = {}): DuplicateCandidate {
  return {
    artisan: { id, name: `Artisan ${id}`, place: "Bhuj", craft: "Ajrakh", birthYear: 1971, ...extra },
    score,
    band: score >= 0.9 ? "VERY_LIKELY" : score >= 0.75 ? "LIKELY" : "POSSIBLE",
    reasons: [{ code: "NAME", label: "Names 92% alike" }]
  };
}

test("the card shows at most three rows, best first, at or above 0.60", () => {
  const shown = candidatesToShow([
    candidate("a", 0.61),
    candidate("b", 0.95),
    candidate("c", 0.59),
    candidate("d", 0.8),
    candidate("e", 0.7)
  ]);
  expect(shown.map((c) => c.artisan.id)).toEqual(["b", "d", "e"]);
});

test("a dismissed candidate is never shown again", () => {
  const shown = candidatesToShow([candidate("a", 0.9), candidate("b", 0.8)], ["a"]);
  expect(shown.map((c) => c.artisan.id)).toEqual(["b"]);
});

test("acknowledged ids are unique, capped at twenty, and an empty list rather than nothing", () => {
  expect(acknowledgedIds([])).toEqual([]);
  expect(acknowledgedIds(["a", "a", "b", ""])).toEqual(["a", "b"]);
  expect(acknowledgedIds(Array.from({ length: 30 }, (_, i) => `id${i}`))).toHaveLength(20);
});

test("no check is asked for until there is a name, and blanks travel as null", () => {
  expect(checkInputFrom({ name: " " })).toBeNull();
  const input = checkInputFrom({ name: " Giriraj ", district: "", phone: "98250 12345" });
  expect(input?.name).toBe("Giriraj");
  expect(input?.district).toBeNull();
  expect(input?.phone).toBe("98250 12345");
  expect(checkKey(input)).toBe(checkKey(checkInputFrom({ name: "Giriraj", phone: "98250 12345" })));
  expect(checkKey(null)).toBe("");
});

test("a candidate's line is place, craft and birth year — never a full date", () => {
  expect(candidateSubtitle(candidate("a", 0.9))).toBe("Bhuj · Ajrakh · b. 1971");
});

test("the 409 hold is told apart from the identity clash and from other errors", () => {
  const hold = {
    detail: {
      code: "artisan_possible_duplicate",
      message: "This may be Giriraj Prasaad (Bhuj), already recorded.",
      candidates: [candidate("a", 0.86)],
      existingArtisan: { id: "a", name: "Giriraj Prasaad" }
    }
  };
  expect(holdFromPayload(hold)?.candidates[0].artisan.id).toBe("a");
  expect(possibleDuplicateHold(new ApiError(409, "x", hold))?.code).toBe("artisan_possible_duplicate");
  expect(possibleDuplicateHold(new ApiError(409, "x", { detail: { code: "artisan_identity_conflict" } }))).toBeNull();
  expect(possibleDuplicateHold(new ApiError(422, "x", hold))).toBeNull();
});

test("a merged artisan's 404 names the survivor", () => {
  const error = new ApiError(404, "x", { detail: { code: "artisan_merged", survivorId: "s1" } });
  expect(mergedSurvivorId(error)).toBe("s1");
  expect(mergedSurvivorId(new ApiError(404, "x", { detail: "Record not found" }))).toBeNull();
});

test("the form sends the acknowledgement on create only and checks before saving", () => {
  const form = read("components", "forms", "ArtisanForm.tsx");
  expect(form).toContain("...(initial ? {} : { duplicateAcknowledged: acknowledgedIds(dismissedRef.current) })");
  expect(form).toContain("onBlur={scheduleDuplicateCheck}");
  expect(form).toMatch(/setTimeout\(\(\) => \{\s*void runDuplicateCheck/);
  expect(form).toContain("}, 600);");
  expect(form).toContain("possibleDuplicateHold(err)");
  // Offline: no check is attempted, and the queued create still carries the (empty) list.
  expect(form).toContain('if (typeof navigator !== "undefined" && navigator.onLine === false) return;');
});

test("the card's copy is the agreed wording", () => {
  const card = read("components", "duplicates", "PossibleDuplicates.tsx");
  expect(card).toContain("Already recorded?");
  expect(card).toContain("Different person");
  expect(card).toContain(">\n              Open\n");
});
