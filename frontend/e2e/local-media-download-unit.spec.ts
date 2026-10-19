import { expect, test } from "@playwright/test";

import { extensionForMimeType, fileStamp, localMediaFileName } from "@/lib/localMediaDownload";

/**
 * The name a file is saved under when somebody presses "Download" on an upload or queue row.
 *
 * The same table is driven against the Android twin in
 * `android/app/src/test/java/com/fieldrepository/app/MediaDownloadsTest.kt` — a voice note saved
 * from the phone and the same one saved from the laptop must land under the same name. Add a row
 * here, add it there.
 */

// 2026-03-07 09:05:02 local time — single-digit month, day, hour and minute so padding is exercised.
const NOW = new Date(2026, 2, 7, 9, 5, 2);

test.describe("extensionForMimeType", () => {
  const rows: Array<[string | null | undefined, string]> = [
    ["audio/webm", "webm"],
    ["audio/webm;codecs=opus", "webm"],
    ["AUDIO/MP4", "m4a"],
    ["audio/mp4; codecs=mp4a.40.2", "m4a"],
    ["audio/ogg", "ogg"],
    ["audio/mpeg", "mp3"],
    ["audio/x-wav", "wav"],
    ["audio/amr", "amr"],
    ["image/jpeg", "jpg"],
    ["image/png", "png"],
    ["image/heic", "heic"],
    ["video/mp4", "mp4"],
    ["video/quicktime", "mov"],
    ["application/pdf", "pdf"],
    ["application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx"],
    ["image/x-icon", "x-icon"],
    ["application/ld+json", "bin"],
    ["", "bin"],
    [null, "bin"],
    [undefined, "bin"],
    ["garbage", "bin"]
  ];
  for (const [mime, expected] of rows) {
    test(`${String(mime)} -> ${expected}`, () => {
      expect(extensionForMimeType(mime)).toBe(expected);
    });
  }
});

test("fileStamp pads every field and is local time", () => {
  expect(fileStamp(NOW)).toBe("2026-03-07-090502");
});

test.describe("localMediaFileName", () => {
  const rows: Array<[string | null | undefined, string | null | undefined, string]> = [
    // The original name always wins.
    ["field-recording-1757000000000.webm", "audio/webm", "field-recording-1757000000000.webm"],
    ["IMG_0042.JPG", "image/jpeg", "IMG_0042.JPG"],
    ["Interview notes.pdf", "application/pdf", "Interview notes.pdf"],
    // A name with no extension gains one from the MIME type…
    ["Section B clip", "audio/mp4", "Section B clip.m4a"],
    // …unless the MIME type says nothing useful.
    ["mystery", "", "mystery"],
    ["trailing.", "image/png", "trailing..png"],
    // Illegal characters are replaced, and a leading dot cannot make the file hidden.
    ["a/b:c*d?.jpg", "image/jpeg", "a_b_c_d_.jpg"],
    [".hidden.png", "image/png", "hidden.png"],
  ];
  for (const [name, mime, expected] of rows) {
    test(`${JSON.stringify(name)} + ${JSON.stringify(mime)} -> ${expected}`, () => {
      expect(localMediaFileName(name, mime, NOW)).toBe(expected);
    });
  }

  // No name at all: the NAMING 2.1 name, dated and timed in IST (asserted by shape, because the
  // runner's zone is not IST, and exactly for an instant whose IST reading is fixed).
  const dated = (what: string, ext: string) => new RegExp(`^FieldRepository_\\d{4}-\\d{2}-\\d{2}_\\d{4}_${what}\\.${ext}$`);
  test("an unnamed file gets the conventional dated name", () => {
    expect(localMediaFileName("", "audio/webm;codecs=opus", NOW)).toMatch(dated("Recording", "webm"));
    expect(localMediaFileName(null, "audio/mp4", NOW)).toMatch(dated("Recording", "m4a"));
    expect(localMediaFileName("   ", "image/jpeg", NOW)).toMatch(dated("Photo", "jpg"));
    expect(localMediaFileName(undefined, undefined, NOW)).toMatch(dated("File", "bin"));
    expect(localMediaFileName("", "audio/webm", new Date(Date.UTC(2026, 2, 7, 3, 35)))).toBe("FieldRepository_2026-03-07_0905_Recording.webm");
  });
});
