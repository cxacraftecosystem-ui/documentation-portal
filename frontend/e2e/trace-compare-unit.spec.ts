import { expect, test } from "@playwright/test";

import {
  BAND_SOURCE_PIXELS,
  COMPARISON_DIFFERENCE_ALT,
  COMPARISON_DIFFERENCE_BADGE,
  COMPARISON_DIFFERENCE_NOTE,
  COMPARISON_DIFFERENCE_PENDING,
  COMPARISON_DIFFERENCE_REFUSAL,
  COMPARISON_LONG_EDGE_PX,
  comparisonStatusFor,
  differenceRgba,
  isComparable,
  isDifference,
  resampleRgba,
  resampleRgbaInBands
} from "@/components/trace/comparisonPlates";
import {
  REVEAL_AT_FIT,
  REVEAL_DRAG_SLOP_PX,
  REVEAL_PEEK_HOLD_MS,
  clampPan,
  clampZoom,
  isAtFit,
  panBy,
  wrapperPercent,
  zoomAbout,
  zoomLabel
} from "@/components/trace/compareTransform";
import { DECODE_MAX_EDGE_PX, nameFromUrl, workingSizeFor } from "@/components/trace/decodeToPixels";

test.describe("clampZoom", () => {
  test("never below 1, because zooming out past fit only letterboxes the picture", () => {
    expect(clampZoom(0.2, 6)).toBe(1);
    expect(clampZoom(-4, 6)).toBe(1);
    expect(clampZoom(1, 6)).toBe(1);
  });

  test("never above the caller's ceiling", () => {
    expect(clampZoom(99, 6)).toBe(6);
    expect(clampZoom(6, 6)).toBe(6);
    expect(clampZoom(3.5, 6)).toBeCloseTo(3.5, 10);
  });

  test("THE ONE THAT NEARLY SHIPPED: Infinity means no ceiling, not a ceiling of 1", () => {
    expect(clampZoom(4, Number.POSITIVE_INFINITY)).toBe(4);
    expect(clampZoom(1000, Number.POSITIVE_INFINITY)).toBe(1000);
  });

  test("a non-finite zoom lands on FIT rather than on the ceiling or on NaN", () => {
    expect(clampZoom(Number.NaN, 6)).toBe(1);
    expect(clampZoom(Number.POSITIVE_INFINITY, 6)).toBe(1);
    expect(clampZoom(2, Number.NaN)).toBe(1);
  });
});

test.describe("clampPan holds the picture inside its own overhang", () => {
  test("at fit there is no overhang, so the picture is pinned centred", () => {
    const clamped = clampPan({ zoom: 1, panX: 200, panY: -200 }, 400, 300);
    expect(clamped.zoom).toBe(1);
    expect(clamped.panX).toBeCloseTo(0, 10);
    expect(clamped.panY).toBeCloseTo(0, 10);
  });

  test("at 2x the overhang is exactly half the frame in each axis", () => {
    const clamped = clampPan({ zoom: 2, panX: 999, panY: -999 }, 400, 300);
    expect(clamped.panX).toBe(200);
    expect(clamped.panY).toBe(-150);
  });

  test("a pan inside the overhang is left exactly where it was", () => {
    expect(clampPan({ zoom: 2, panX: 50, panY: -20 }, 400, 300)).toEqual({ zoom: 2, panX: 50, panY: -20 });
  });

  test("a frame that has not been laid out yet pins rather than dividing by nothing", () => {
    expect(clampPan({ zoom: 3, panX: 40, panY: 40 }, 0, 0)).toEqual({ zoom: 3, panX: 0, panY: 0 });
    const nanFrame = clampPan({ zoom: 3, panX: 40, panY: 40 }, Number.NaN, Number.NaN);
    expect(Number.isFinite(nanFrame.panX)).toBe(true);
    expect(Number.isFinite(nanFrame.panY)).toBe(true);
  });

  test("panBy moves and then re-clamps, so a flick can never throw a plate off the frame", () => {
    const moved = panBy({ zoom: 2, panX: 0, panY: 0 }, 10_000, 10_000, 400, 300);
    expect(moved.panX).toBe(200);
    expect(moved.panY).toBe(150);
    expect(panBy({ zoom: 2, panX: 10, panY: 10 }, Number.NaN, Number.NaN, 400, 300)).toEqual({
      zoom: 2,
      panX: 10,
      panY: 10
    });
  });
});

test.describe("zoomAbout keeps the point under the pointer where it is", () => {
  test("a no-op zoom does not drift the picture", () => {
    const at = { zoom: 2, panX: 30, panY: -10 };
    expect(zoomAbout(at, 1, 120, 90, 400, 300, 6)).toEqual(at);
  });

  test("zooming about the CENTRE leaves a centred picture centred", () => {
    const out = zoomAbout(REVEAL_AT_FIT, 2, 200, 150, 400, 300, 6);
    expect(out.zoom).toBe(2);
    expect(out.panX).toBeCloseTo(0, 10);
    expect(out.panY).toBeCloseTo(0, 10);
  });

  test("zooming about a corner moves the picture so that corner stays put", () => {
    const out = zoomAbout(REVEAL_AT_FIT, 2, 0, 0, 400, 300, 6);
    expect(out.zoom).toBe(2);
    expect(out.panX).toBeCloseTo(200, 6);
    expect(out.panY).toBeCloseTo(150, 6);
  });

  test("the result is always inside the overhang, whatever the pointer was", () => {
    for (const [x, y] of [[0, 0], [400, 300], [-50, 900], [200, 150]]) {
      const out = zoomAbout(REVEAL_AT_FIT, 4, x, y, 400, 300, 6);
      expect(Math.abs(out.panX)).toBeLessThanOrEqual((400 * (out.zoom - 1)) / 2 + 1e-9);
      expect(Math.abs(out.panY)).toBeLessThanOrEqual((300 * (out.zoom - 1)) / 2 + 1e-9);
    }
  });

  test("the ceiling is honoured and zooming out cannot go past fit", () => {
    expect(zoomAbout(REVEAL_AT_FIT, 100, 200, 150, 400, 300, 6).zoom).toBe(6);
    expect(zoomAbout({ zoom: 2, panX: 100, panY: 0 }, 0.01, 200, 150, 400, 300, 6)).toEqual(REVEAL_AT_FIT);
  });

  test("a non-finite pointer is read as the centre rather than as NaN", () => {
    const out = zoomAbout(REVEAL_AT_FIT, 2, Number.NaN, Number.NaN, 400, 300, 6);
    expect(Number.isFinite(out.panX)).toBe(true);
    expect(Number.isFinite(out.panY)).toBe(true);
  });
});

test.describe("isAtFit decides whether the reset affordance is drawn at all", () => {
  test("fit, and a hair above it, are both fit", () => {
    expect(isAtFit(REVEAL_AT_FIT)).toBe(true);
    expect(isAtFit({ zoom: 1.0005, panX: 0, panY: 0 })).toBe(true);
  });

  test("a real magnification, or any pan, is not", () => {
    expect(isAtFit({ zoom: 1.2, panX: 0, panY: 0 })).toBe(false);
    expect(isAtFit({ zoom: 1, panX: 5, panY: 0 })).toBe(false);
  });
});

test.describe("wrapperPercent, the one place the two coordinate spaces meet", () => {
  test("at fit it is the identity, so an un-magnified comparator emits the seam it was given", () => {
    for (const seam of [0, 25, 50, 75, 100]) {
      expect(wrapperPercent(seam, 1, 0, 400)).toBeCloseTo(seam, 10);
    }
  });

  test("a frame with no length yet answers the seam unchanged rather than dividing by zero", () => {
    expect(wrapperPercent(30, 2, 10, 0)).toBe(30);
    expect(wrapperPercent(30, 2, 10, Number.NaN)).toBe(30);
  });

  test("magnified, the seam converts so the join stays on the drawing", () => {
    expect(wrapperPercent(50, 2, 0, 400)).toBeCloseTo(50, 10);
    expect(wrapperPercent(0, 2, 0, 400)).toBeCloseTo(25, 10);
    expect(wrapperPercent(100, 2, 0, 400)).toBeCloseTo(75, 10);
  });

  test("a pan shifts the conversion by exactly the pan", () => {
    expect(wrapperPercent(50, 2, 50, 400)).toBeCloseTo(50 - 12.5 / 2, 10);
  });

  test("the answer is deliberately NOT clamped to 0..100", () => {
    expect(wrapperPercent(100, 4, -700, 400)).toBeGreaterThan(100);
    expect(wrapperPercent(0, 4, 700, 400)).toBeLessThan(0);
  });
});

test.describe("the two gesture constants", () => {
  test("the peek hold is well under a long press, because this is holding to look", () => {
    expect(REVEAL_PEEK_HOLD_MS).toBe(220);
    expect(REVEAL_PEEK_HOLD_MS).toBeLessThan(500);
  });

  test("the drag slop is under the smallest deliberate movement and over the largest accidental one", () => {
    expect(REVEAL_DRAG_SLOP_PX).toBeGreaterThan(0);
    expect(REVEAL_DRAG_SLOP_PX).toBeLessThanOrEqual(5);
  });

  test("the magnification readout is one decimal and a multiplication sign", () => {
    expect(zoomLabel(1)).toBe("1×");
    expect(zoomLabel(2.349)).toBe("2.3×");
    expect(zoomLabel(Number.NaN)).toBe("1×");
  });
});

function ramp(width: number, height: number, value: (x: number, y: number) => number): Uint8ClampedArray {
  const out = new Uint8ClampedArray(width * height * 4);
  for (let y = 0; y < height; y += 1) {
    for (let x = 0; x < width; x += 1) {
      const at = (y * width + x) * 4;
      const v = value(x, y);
      out[at] = v;
      out[at + 1] = v;
      out[at + 2] = v;
      out[at + 3] = 255;
    }
  }
  return out;
}

test.describe("resampleRgba is a box filter, not a nearest-neighbour drop", () => {
  test("a 2x2 box averages its four source pixels", () => {
    const source = ramp(2, 2, (x, y) => (y * 2 + x) * 40);
    const out = resampleRgba(source, 2, 2, 1, 1);
    expect(out).toHaveLength(4);
    expect(out[0]).toBe(60);
    expect(out[3]).toBe(255);
  });

  test("consecutive boxes TILE the source — no pixel read twice, none skipped", () => {
    const source = ramp(4, 1, (x) => x * 10);
    const out = resampleRgba(source, 4, 1, 2, 1);
    expect(out[0]).toBe(5);
    expect(out[4]).toBe(25);
  });

  test("it never upscales: asking for more pixels than there are returns the source size", () => {
    const source = ramp(2, 2, () => 77);
    const out = resampleRgba(source, 2, 2, 8, 8);
    expect(out).toHaveLength(2 * 2 * 4);
  });

  test("asking for the size it already is copies rather than re-averaging", () => {
    const source = ramp(3, 3, (x, y) => x * 9 + y);
    expect(Array.from(resampleRgba(source, 3, 3, 3, 3))).toEqual(Array.from(source));
  });

  test("a degenerate target of zero still produces a drawable one-pixel plane", () => {
    const out = resampleRgba(ramp(4, 4, () => 12), 4, 4, 0, 0);
    expect(out).toHaveLength(4);
  });
});

test.describe("resampleRgbaInBands is the same arithmetic, interruptible", () => {
  test("it agrees with the synchronous one, band boundaries and all", async () => {
    const width = 64;
    const height = Math.max(8, Math.ceil((BAND_SOURCE_PIXELS / width) * 2.5));
    const source = ramp(width, height, (x, y) => (x * 7 + y * 13) % 256);
    const banded = await resampleRgbaInBands(source, width, height, 16, 16);
    const sync = resampleRgba(source, width, height, 16, 16);
    expect(banded).not.toBeNull();
    expect(Array.from(banded!)).toEqual(Array.from(sync));
  });

  test("shouldStop abandons the plane rather than finishing work nobody will see", async () => {
    const width = 64;
    const height = Math.max(8, Math.ceil((BAND_SOURCE_PIXELS / width) * 2.5));
    const source = ramp(width, height, () => 100);
    let asked = 0;
    const out = await resampleRgbaInBands(source, width, height, 16, 16, () => {
      asked += 1;
      return asked > 1;
    });
    expect(out).toBeNull();
  });

  test("a caller that is already stale pays for nothing at all", async () => {
    const source = ramp(64, 64, () => 100);
    expect(await resampleRgbaInBands(source, 64, 64, 16, 16, () => true)).toBeNull();
  });

  test("a plane that needs no resampling comes back without ever asking shouldStop", async () => {
    let asked = false;
    const source = ramp(4, 4, () => 9);
    const out = await resampleRgbaInBands(source, 4, 4, 4, 4, () => {
      asked = true;
      return true;
    });
    expect(out).not.toBeNull();
    expect(asked).toBe(false);
  });
});

test.describe("differenceRgba — the one definition both clients implement", () => {
  test("absolute difference per channel, never a luminance difference", () => {
    const photograph = new Uint8ClampedArray([200, 100, 50, 255]);
    const trace = new Uint8ClampedArray([50, 150, 50, 255]);
    expect(Array.from(differenceRgba(photograph, trace))).toEqual([150, 50, 0, 255]);
  });

  test("agreement reads as black, which is the whole content of the view", () => {
    const same = new Uint8ClampedArray([30, 30, 30, 255]);
    expect(Array.from(differenceRgba(same, same))).toEqual([0, 0, 0, 255]);
  });

  test("alpha is FORCED opaque rather than subtracted", () => {
    const a = new Uint8ClampedArray([10, 10, 10, 255]);
    const b = new Uint8ClampedArray([10, 10, 10, 255]);
    expect(differenceRgba(a, b)[3]).toBe(255);
  });

  test("mismatched planes give a short answer rather than reading off the end of one", () => {
    const long = new Uint8ClampedArray(16);
    const short = new Uint8ClampedArray(8);
    expect(differenceRgba(long, short)).toHaveLength(8);
    expect(differenceRgba(short, long)).toHaveLength(8);
  });
});

test.describe("the caps, and the one that is deliberately smaller than the export's", () => {
  test("a comparison plate is capped well below a decode", () => {
    expect(COMPARISON_LONG_EDGE_PX).toBeLessThan(DECODE_MAX_EDGE_PX);
    expect(COMPARISON_LONG_EDGE_PX).toBe(1024);
  });

  test("workingSizeFor leaves a source that is already inside the cap alone", () => {
    expect(workingSizeFor(2480, 3508, DECODE_MAX_EDGE_PX)).toEqual({ width: 2480, height: 3508 });
  });

  test("and shrinks the long edge to the cap, keeping the ratio", () => {
    expect(workingSizeFor(8000, 4000, 4096)).toEqual({ width: 4096, height: 2048 });
    expect(workingSizeFor(4000, 8000, 1024)).toEqual({ width: 512, height: 1024 });
  });

  test("a shrink never produces a zero-pixel axis", () => {
    expect(workingSizeFor(10_000, 3, 100).height).toBeGreaterThanOrEqual(1);
  });
});

const QUIET = {
  haveCompare: false,
  compareProblem: null,
  tracing: false,
  progress: null,
  traceProblem: null,
  haveResult: false
};

test.describe("comparisonStatusFor answers all six absences separately", () => {
  test("a comparison on screen and nothing running says NOTHING", () => {
    expect(comparisonStatusFor({ ...QUIET, haveCompare: true, haveResult: true })).toBe("");
  });

  test("a comparison on screen with a trace running says it is being updated", () => {
    expect(comparisonStatusFor({ ...QUIET, haveCompare: true, haveResult: true, tracing: true })).toBe("Updating…");
  });

  test("nothing traced yet says when the comparison will appear", () => {
    expect(comparisonStatusFor(QUIET)).toContain("as soon as the first trace finishes");
  });

  test("a trace running shows the ENGINE's own progress sentence when there is one", () => {
    expect(comparisonStatusFor({ ...QUIET, tracing: true, progress: "Detecting edges. Stage 7 of 12." })).toBe(
      "Detecting edges. Stage 7 of 12."
    );
    expect(comparisonStatusFor({ ...QUIET, tracing: true })).toBe("Tracing…");
  });

  test("a comparison refusal is shown verbatim, because it was written to be read", () => {
    const refusal = "This browser would not give the page a drawing surface.";
    expect(comparisonStatusFor({ ...QUIET, compareProblem: refusal, haveResult: true })).toBe(refusal);
  });

  test("a failed trace POINTS AT the red message rather than restating it", () => {
    const said = comparisonStatusFor({ ...QUIET, traceProblem: "That image could not be traced." });
    expect(said).toContain("see the reason above");
    expect(said).not.toContain("That image could not be traced.");
  });

  test("a finished trace with no plates yet says it is preparing them", () => {
    expect(comparisonStatusFor({ ...QUIET, haveResult: true })).toBe("Preparing the comparison…");
  });

  test("the order is the content: a live comparison outranks every explanation", () => {
    expect(
      comparisonStatusFor({
        haveCompare: true,
        compareProblem: "an old refusal",
        tracing: false,
        progress: null,
        traceProblem: "an old failure",
        haveResult: true
      })
    ).toBe("");
  });
});

test.describe("the difference view's wording, which both clients share", () => {
  test("the chip and the badge are one word, so a press and a picture cannot disagree", () => {
    expect(COMPARISON_DIFFERENCE_BADGE).toBe("Difference");
  });

  test("the note explains what dark and bright MEAN, not what the operation is", () => {
    expect(COMPARISON_DIFFERENCE_NOTE).toContain("Black where they match");
    expect(COMPARISON_DIFFERENCE_NOTE).toContain("bright where they");
  });

  test("the alt text says what the picture means, for a reader who cannot see it", () => {
    expect(COMPARISON_DIFFERENCE_ALT).toContain("Dark where they agree");
    expect(COMPARISON_DIFFERENCE_ALT).toContain("bright");
  });

  test("the refusal names the BROWSER and promises that nothing else was lost", () => {
    expect(COMPARISON_DIFFERENCE_REFUSAL).toContain("browser");
    expect(COMPARISON_DIFFERENCE_REFUSAL).not.toContain("phone");
    expect(COMPARISON_DIFFERENCE_REFUSAL).toContain("drawing is unaffected");
  });

  test("the pending sentence names no device, because a wait is a wait on both clients", () => {
    expect(COMPARISON_DIFFERENCE_PENDING).toBe("Working out the difference picture…");
    expect(COMPARISON_DIFFERENCE_PENDING).not.toContain("browser");
  });
});

test("the two narrowing helpers read as questions rather than as key tests", () => {
  const blob = new Blob(["x"]);
  expect(isComparable({ trace: blob, original: blob, width: 10, height: 10, reduced: false })).toBe(true);
  expect(isComparable({ reason: "no" })).toBe(false);
  expect(isDifference({ plate: blob })).toBe(true);
  expect(isDifference({ reason: "no" })).toBe(false);
});

test.describe("nameFromUrl, for the host that passes a stored object rather than a File", () => {
  test("the last path segment, without the query string", () => {
    expect(nameFromUrl("https://bucket.s3.example/media/block-print.jpg?X-Amz-Signature=abc&expires=1")).toBe(
      "block-print.jpg"
    );
  });

  test("a percent-encoded name comes back readable", () => {
    expect(nameFromUrl("https://x/media/block%20print.jpg")).toBe("block print.jpg");
  });

  test("a name that is not valid percent-encoding is still used rather than thrown away", () => {
    expect(nameFromUrl("https://x/media/100%-cotton.jpg")).toBe("100%-cotton.jpg");
  });

  test("a relative path works, because a host may legitimately pass one", () => {
    expect(nameFromUrl("/api/media/42/download.png")).toBe("download.png");
  });

  test("a URL with nothing usable in it falls back to a stem rather than an empty name", () => {
    expect(nameFromUrl("https://bucket.s3.example/")).toBe("image");
    expect(nameFromUrl("")).toBe("image");
  });
});
