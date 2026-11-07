import { expect, test } from "@playwright/test";

import { saveUrlToDevice } from "@/lib/saveToDevice";

type FakeAnchor = { href: string; download: string; target: string; rel: string; clicked: boolean; click: () => void; remove: () => void };

function withFakeDocument(run: (anchors: FakeAnchor[]) => void): void {
  const anchors: FakeAnchor[] = [];
  const holder = globalThis as unknown as { document?: unknown; window?: unknown };
  const previous = holder.document;
  holder.document = {
    createElement: () => {
      const anchor: FakeAnchor = {
        href: "", download: "", target: "", rel: "", clicked: false,
        click() { this.clicked = true; },
        remove() {}
      };
      anchors.push(anchor);
      return anchor;
    },
    body: { appendChild: () => undefined }
  };
  try {
    run(anchors);
  } finally {
    holder.document = previous;
  }
}

for (const hostile of ["javascript:alert(document.cookie)", " JavaScript:alert(1)", "data:text/html,<script>alert(1)</script>", "vbscript:msgbox(1)"]) {
  test(`a stored media url of ${JSON.stringify(hostile)} never reaches a clicked anchor`, () => {
    withFakeDocument((anchors) => {
      saveUrlToDevice(hostile, "recording.m4a", { newTab: true });
      expect(anchors.some((anchor) => anchor.clicked)).toBe(false);
    });
  });
}

for (const safe of ["https://bucket.s3.ap-south-1.amazonaws.com/media/u/k/a.m4a?X-Amz-Signature=abc", "blob:https://portal.example/1234"]) {
  test(`a ${safe.split(":")[0]} url still downloads`, () => {
    withFakeDocument((anchors) => {
      saveUrlToDevice(safe, "recording.m4a");
      expect(anchors.map((anchor) => [anchor.href, anchor.clicked])).toEqual([[safe, true]]);
    });
  });
}
