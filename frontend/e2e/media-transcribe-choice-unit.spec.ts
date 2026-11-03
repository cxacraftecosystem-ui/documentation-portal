import { readFileSync, readdirSync, statSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { transcribeFlagsFor, withTranscribeFlags, type OutboxMediaBatch } from "@/lib/offline";
import {
  TRANSCRIPTION_REQUEST,
  lockedByPolicy,
  processingFor,
  resetUploadChoicesForTests,
  setTranscribeChoice,
  setTranscribeChoices,
  transcribableKind,
  transcribeChoice,
  transcribeFlags,
  wantsTranscription
} from "@/lib/transcript/uploadChoice";

const FRONTEND = join(__dirname, "..");

const file = (name: string, type: string, size = 10, lastModified = 1_700_000_000_000) =>
  new File([new Uint8Array(size)], name, { type, lastModified });

function sources(dir: string): string[] {
  let out: string[] = [];
  let entries: string[] = [];
  try {
    entries = readdirSync(dir);
  } catch {
    return out;
  }
  for (const name of entries) {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) out = out.concat(sources(path));
    else if (/\.tsx?$/.test(name)) out.push(path);
  }
  return out;
}

test.beforeEach(() => resetUploadChoicesForTests());

test("@qf:F2 only audio and video get a box, it starts off, and only a ticked file asks for transcription", () => {
  const audio = file("talk.m4a", "audio/mp4");
  const video = file("demo.mp4", "video/mp4", 20);
  const photo = file("pot.jpg", "image/jpeg", 30);
  expect(transcribableKind(audio)).toBe("AUDIO");
  expect(transcribableKind(video)).toBe("VIDEO");
  expect(transcribableKind(photo)).toBeNull();
  expect(transcribeChoice(audio)).toBe(false);
  expect(processingFor(audio, undefined)).toEqual([]);
  expect(processingFor(video, ["MEASUREMENT"])).toEqual(["MEASUREMENT"]);
  setTranscribeChoice(video, true);
  expect(processingFor(video, undefined)).toEqual([TRANSCRIPTION_REQUEST]);
  expect(processingFor(audio, undefined)).toEqual([]);
  setTranscribeChoice(photo, true);
  expect(processingFor(photo, undefined)).toEqual([]);
});

test("@qf:F2 a renamed copy of a ticked file keeps its choice", () => {
  const original = file("VID_0001.mp4", "video/mp4", 44);
  setTranscribeChoice(original, true);
  const renamed = new File([original], "Workshop_Video_1.mp4", { type: original.type, lastModified: original.lastModified });
  expect(wantsTranscription(renamed, null)).toBe(true);
});

test("@qf:F3 Transcribe and translate all ticks every file, and a box can be changed after it", () => {
  const files = [file("a.m4a", "audio/mp4", 1), file("b.mp4", "video/mp4", 2), file("c.pdf", "application/pdf", 3)];
  setTranscribeChoices(files, true);
  expect(transcribeFlags(files)).toEqual([true, true, false]);
  setTranscribeChoice(files[0], false);
  expect(transcribeFlags(files)).toEqual([false, true, false]);
});

test("@qf:F4 an admin's auto switch ticks and locks the kind it names, whatever the box says", () => {
  const audio = file("a.m4a", "audio/mp4", 5);
  const video = file("b.mp4", "video/mp4", 6);
  const policy = { autoTranscribeVideo: true, autoTranscribeAudio: false };
  expect(lockedByPolicy("VIDEO", policy)).toBe(true);
  expect(lockedByPolicy("AUDIO", policy)).toBe(false);
  expect(wantsTranscription(video, policy)).toBe(true);
  expect(wantsTranscription(audio, policy)).toBe(false);
  const component = readFileSync(join(FRONTEND, "components", "transcript", "TranscribeChoices.tsx"), "utf8");
  expect(component).toContain("disabled={locked || disabled}");
  expect(component).toContain('ts("uploadAutoVideo")');
  const panel = readFileSync(join(FRONTEND, "components", "settings", "TranscriptionPanel.tsx"), "utf8");
  expect(panel).toContain("autoTranscribeVideo: on");
  expect(panel).toContain("autoTranscribeAudio: on");
  expect(panel.indexOf("autoTranscribeVideo")).toBeGreaterThan(panel.indexOf("audioIsolation: on"));
});

test("@qf:F5 the backfill is switched on only with the estimate confirmed", () => {
  const panel = readFileSync(join(FRONTEND, "components", "settings", "TranscriptionPanel.tsx"), "utf8");
  expect(panel).toContain('"/settings/transcription/backfill"');
  expect(panel.match(/transcriptBackfill: true/g)).toHaveLength(1);
  expect(panel).toContain("{ transcriptBackfill: true, confirmBackfill: true }");
});

test("@qf:F6 a queued upload carries each file's choice and replays it, even after the batch is narrowed", () => {
  const audio = file("a.m4a", "audio/mp4", 7);
  const video = file("b.mp4", "video/mp4", 8);
  const photo = file("c.jpg", "image/jpeg", 9);
  setTranscribeChoice(video, true);
  const queued = withTranscribeFlags({ files: [audio, video, photo], linkedRecordType: "product" } as OutboxMediaBatch);
  expect(queued.transcribe).toEqual([false, true, false]);
  resetUploadChoicesForTests();
  expect(transcribeFlagsFor(queued, [video, photo])).toEqual([true, false]);
  expect(processingFor(video, undefined, { flag: transcribeFlagsFor(queued, [video])[0] })).toEqual([TRANSCRIPTION_REQUEST]);
  expect(processingFor(audio, undefined, { flag: transcribeFlagsFor(queued, [audio])[0] })).toEqual([]);
  const kept = withTranscribeFlags({ files: [audio], linkedRecordType: "product", transcribe: [true] } as OutboxMediaBatch);
  expect(kept.transcribe).toEqual([true]);
});

test("@qf:F7 a questionnaire take shows no box and is queued for transcription", () => {
  const take = file("take.webm", "audio/webm", 11);
  expect(transcribeChoice(take)).toBe(false);
  expect(processingFor(take, undefined, { always: true })).toEqual([TRANSCRIPTION_REQUEST]);
  const questionnaire = [
    ...sources(join(FRONTEND, "app", "(protected)", "questionnaire")),
    ...sources(join(FRONTEND, "components", "questionnaire"))
  ];
  expect(questionnaire.length).toBeGreaterThan(0);
  for (const path of questionnaire) {
    const source = readFileSync(path, "utf8");
    expect(source, path).not.toContain("TranscribeChoices");
    const fields = source.match(/<MediaCaptureField[\s\S]*?\/>/g) ?? [];
    for (const field of fields) expect(field, path).toContain("transcribeChoice={false}");
  }
});
