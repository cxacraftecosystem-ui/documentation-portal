import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import {
  REFINE_LABEL,
  REFINE_TRANSLATE_LABEL,
  mayRefine,
  refineCostSentence,
  refineOutcome,
  refinedHeading
} from "@/lib/transcriptRefine";
import type { MediaFile, User } from "@/lib/types";

const read = (...parts: string[]) => readFileSync(join(__dirname, "..", ...parts), "utf8");

const media = (patch: Partial<MediaFile> & { uploadedById?: string | null } = {}) =>
  ({ id: "m1", mediaType: "AUDIO", transcriptText: "Speaker 1: namaste", uploadedById: "u1", ...patch }) as unknown as MediaFile;

const user = (id: string, role: string) => ({ id, role }) as unknown as User;

test("@qf:R1 the uploader and content admins may refine a stored transcript, nobody else", () => {
  expect(mayRefine(user("u1", "RESEARCHER"), media())).toBe(true);
  expect(mayRefine(user("u2", "ADMIN"), media())).toBe(true);
  expect(mayRefine(user("u2", "MINISTRY_ADMIN"), media())).toBe(true);
  expect(mayRefine(user("u2", "RESEARCHER"), media())).toBe(false);
  expect(mayRefine(null, media())).toBe(false);
  expect(mayRefine(user("u1", "RESEARCHER"), media({ transcriptText: "  " }))).toBe(false);
  expect(mayRefine(user("u3", "RESEARCHER"), media({ uploadedById: null, uploadedBy: { id: "u3" } as User }))).toBe(true);
});

test("@qf:R1 the server's answer becomes the refined text or its own sentence", () => {
  expect(refineOutcome({ status: "COMPLETED", refined: " **Interviewer:** Hello " })).toEqual({ kind: "refined", text: "**Interviewer:** Hello" });
  expect(refineOutcome({ available: false, status: "UNAVAILABLE", message: "Refinement isn't available right now." })).toEqual({
    kind: "message",
    text: "Refinement isn't available right now."
  });
  expect(refineOutcome({ status: "EMPTY", refined: null, message: "There is no transcript text to refine yet." }).text).toBe(
    "There is no transcript text to refine yet."
  );
  expect(refineOutcome({ status: "FAILED", refined: null }).kind).toBe("message");
});

test("@qf:R1 the wording is Android's and names no model", () => {
  expect(REFINE_LABEL).toBe("Refine transcript");
  expect(REFINE_TRANSLATE_LABEL).toBe("Refine & translate");
  expect(refineCostSentence(false)).toBe("Refining uses an AI model at a small cost. Continue?");
  expect(refineCostSentence(true)).toBe("Refining and translating uses an AI model at a small cost. Continue?");
  expect(refinedHeading(true)).toBe("Refined conversation (English)");
  const component = read("components", "media", "TranscriptRefine.tsx");
  expect(component).not.toMatch(/gpt-|gemini-|claude-/);
  expect(component).toContain("Do not remind me again");
  const block = read("components", "media", "TranscriptBlock.tsx");
  expect(block).toContain("<TranscriptRefine");
});
