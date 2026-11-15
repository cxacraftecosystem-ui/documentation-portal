import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { qs } from "@/lib/questionnaire/qs";
import { fullEntry, localTakeEntry, nextOpen, questionTake, regionId, sectionTakes, takeEntry, takeMedia, withLanded } from "@/lib/questionnaire/takeInPlace";

const ROOT = join(__dirname, "..");
const read = (path: string) => readFileSync(join(ROOT, path), "utf8");

const take = (id: string, extra: Partial<{ clientClipKey: string; capturedAt: string; removedAt: string | null; targets: { sectionId?: string | null; questionLineageId?: string | null }[] }> = {}) => ({
  id,
  mediaFileId: `m-${id}`,
  downloadName: `Take_${id}.m4a`,
  clientClipKey: `clip-${id}`,
  capturedAt: "2026-11-15T10:42:00Z",
  removedAt: null,
  targets: [{ sectionId: "s-e" }],
  ...extra
});

test("a recorded take becomes the explorer's own media entry, so the explorer previews it from its own routes", () => {
  const entry = takeEntry(take("r1"));
  expect(entry).toEqual({
    name: "Take_r1.m4a",
    path: "media:m-r1",
    kind: "file",
    mediaId: "m-r1",
    mediaType: "AUDIO",
    downloadPath: "/questionnaire/recordings/r1/download",
    capturedAt: "2026-11-15T10:42:00Z",
    transcriptAvailable: true
  });
  expect(entry.previewPath).toBeUndefined();
  expect(entry.url).toBeUndefined();
  expect(fullEntry({ key: "r1", label: "Take", recording: take("r1") })).toEqual(entry);
});

test("a video take opens in the explorer's video stage, from the server and from this device", () => {
  expect(takeEntry({ ...take("v1"), mediaType: "VIDEO" }).mediaType).toBe("VIDEO");
  expect(takeEntry({ ...take("a1"), mediaType: "AUDIO" }).mediaType).toBe("AUDIO");
  expect(localTakeEntry({ key: "c1", label: "Section E", mediaType: takeMedia("video/mp4") }, "blob:take").mediaType).toBe("VIDEO");
  expect(localTakeEntry({ key: "c2", label: "Section E", mediaType: takeMedia("audio/webm;codecs=opus") }, "blob:take").mediaType).toBe("AUDIO");
  expect([takeMedia("VIDEO"), takeMedia("Video/QuickTime"), takeMedia(null), takeMedia("audio/mp4")]).toEqual(["VIDEO", "VIDEO", "AUDIO", "AUDIO"]);
});

test("a take that just uploaded stays listed and keeps its key until the session reloads with it", () => {
  const before = [take("old")];
  const landed = [take("new", { clientClipKey: "clip-new" })];
  expect(withLanded(before, landed).map((r) => r.id)).toEqual(["old", "new"]);
  expect(withLanded([...before, take("new", { clientClipKey: "clip-new" })], landed).map((r) => r.id)).toEqual(["old", "new"]);
  const outbox = [{ clientClipKey: "clip-new", targets: [{ sectionId: "s-e" }] }];
  expect(sectionTakes("s-e", [], outbox).local.map((s) => s.clientClipKey)).toEqual(["clip-new"]);
  expect(sectionTakes("s-e", withLanded([], landed), outbox).server.map((r) => r.clientClipKey)).toEqual(["clip-new"]);
  const workspace = read("components/questionnaire/SessionWorkspace.tsx");
  expect(workspace).toContain("key: s.clientClipKey,");
  expect(workspace).toContain("key: r.clientClipKey, label: takeRow(r, soloArtisan)");
  expect(workspace).toContain("withLanded(detail.recordings, landed)");
  expect(read("components/questionnaire/TakeInPlace.tsx")).toContain("const [heldBlob] = useState(item.recording ? null : (item.blobKey ?? null));");
});

test("a take still on this device plays from its own file and has no full view", () => {
  const entry = localTakeEntry({ key: "c1", label: "Section E · 15 Nov · Waiting for signal", capturedAt: "2026-11-15T10:42:00Z" }, "blob:take");
  expect(entry).toMatchObject({ path: "take:c1", url: "blob:take", mediaType: "AUDIO" });
  expect(entry.mediaId).toBeUndefined();
  expect(fullEntry({ key: "c1", label: "x", blobKey: "b1" })).toBeNull();
  expect(qs("transcriptAfterUpload")).toBe("Transcript after upload");
});

test("one take is open at a time: opening another closes the first, and opening it again collapses it", () => {
  const first = nextOpen(null, "S:e", "r1");
  expect(first).toEqual({ slot: "S:e", key: "r1" });
  expect(nextOpen(first, "Q:e2", "r2")).toEqual({ slot: "Q:e2", key: "r2" });
  expect(nextOpen(first, "Q:e1", "r1")).toEqual({ slot: "Q:e1", key: "r1" });
  expect(nextOpen(first, "S:e", "r1")).toBeNull();
  expect(regionId("Q:l-2", "local:c 1")).toBe("take-Q-l-2-local-c-1");
});

test("a section lists its takes oldest first with the device's unsent ones after, and never a removed take or a landed copy", () => {
  const recordings = [
    take("late", { capturedAt: "2026-11-15T11:20:00Z" }),
    take("early", { capturedAt: "2026-11-15T10:42:00Z" }),
    take("gone", { removedAt: "2026-11-15T12:00:00Z" }),
    take("other", { targets: [{ sectionId: "s-f" }] }),
    take("q", { targets: [{ questionLineageId: "l-e2" }] })
  ];
  const outbox = [
    { clientClipKey: "clip-late", targets: [{ sectionId: "s-e" }] },
    { clientClipKey: "clip-new", targets: [{ sectionId: "s-e" }] },
    { clientClipKey: "clip-e3", targets: [{ questionLineageId: "l-e3" }] }
  ];
  const found = sectionTakes("s-e", recordings, outbox);
  expect(found.server.map((r) => r.id)).toEqual(["early", "late"]);
  expect(found.local.map((s) => s.clientClipKey)).toEqual(["clip-new"]);
  expect(questionTake("l-e2", "q", recordings, outbox)).toMatchObject({ id: "q" });
  expect(questionTake("l-e2", "gone", recordings, outbox)).toBeNull();
  expect(questionTake("l-e3", null, recordings, outbox)).toMatchObject({ clientClipKey: "clip-e3" });
});

test("the questionnaire reuses the Data explorer's preview and adds no player or transcript viewer of its own", () => {
  const glue = read("components/questionnaire/TakeInPlace.tsx");
  expect(glue).toContain('import { PreviewDialog, PreviewInline } from "@/components/dataExplorer/PreviewDialog";');
  expect(glue).not.toMatch(/<audio|<video|AudioPlayer|TranscriptViewer|Waveform/);
  const readView = read("components/questionnaire/SessionReadView.tsx");
  expect(readView).toContain("TakeRegion");
  expect(readView).not.toMatch(/AudioPlayer|TranscriptViewer/);
  const workspace = read("components/questionnaire/SessionWorkspace.tsx");
  expect(workspace).toContain("useTakePlayer(recording)");
  expect(workspace).toContain("TakeRegion");
  const sheet = read("components/questionnaire/TakeSheet.tsx");
  expect(sheet).toContain("TakeRegion");
  expect(sheet).not.toMatch(/<audio|<video|AudioPlayer|TranscriptViewer|Waveform/);
  expect(workspace).toMatch(/const openTakes = \(\) => \{\s+takePlayer\.close\(\);\s+setSheet\(/);
  expect(workspace).toMatch(/const closeSheet = \(\) => \{\s+takePlayer\.close\(\);\s+setSheet\(null\);/);
  expect(workspace.match(/setSheet\(null\)/g)).toHaveLength(1);
});

test("the explorer's own dialog renders the preview unchanged and only the embed drops its chrome", () => {
  const preview = read("components/dataExplorer/PreviewDialog.tsx");
  expect(preview).toContain('<div className={embedded ? EMBED_WIDE : "flex h-[calc(100dvh-5rem)] flex-col"} data-testid="preview-body">');
  expect(preview).toContain('<div className={embedded ? EMBED_PHONE : "flex h-dvh flex-col bg-card"} data-testid="preview-body">');
  expect(preview).toContain("{embedded ? null : header}");
  expect(preview).toContain("{embedded ? null : phoneBar}");
  const inline = preview.slice(preview.indexOf("export function PreviewInline("));
  expect(inline).toContain("embedded");
  const dialog = preview.slice(preview.indexOf("export function PreviewDialog("), preview.indexOf("export function PreviewInline("));
  expect(dialog).not.toContain("embedded");
});
