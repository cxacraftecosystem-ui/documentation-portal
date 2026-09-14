import { expect, test } from "@playwright/test";

import {
  PROGRESS_UNMEASURED_NOTE,
  TRACE_STAGES,
  TRACE_STAGE_COUNT,
  UNWEIGHTED,
  fractionAt,
  progressWeights,
  traceProgressSentence,
  traceStageIndex
} from "@/components/trace/traceStages";
import { stageIds } from "@/lib/trace/engine/pipeline";

/**
 * THE PROGRESS BAR — the one place a transcription of somebody else's table is checked against the
 * table it transcribes.
 *
 * WHY `traceStages.ts` HOLDS A COPY AT ALL. `TracePanel.tsx`'s third property forbids a top-level
 * import from `@/lib/trace/*` in that file — the engine must not be on a page's bundle until somebody
 * traces something — so the panel cannot read the stage list off the engine at render time. What it
 * imports instead is plain numbers and strings.
 *
 * AND A TRANSCRIPTION THAT NOTHING CHECKS IS A TRANSCRIPTION THAT IS ALREADY WRONG. This file is the
 * check: it is a spec, so `node` has no bundle and it may import both sides. A vendored engine update
 * that inserts a thirteenth stage, renames one, or reorders two fails HERE rather than silently
 * mis-numbering a sentence a researcher reads while they are deciding whether the app has frozen.
 *
 * ── THE CLAIM THE WEIGHTING EXISTS FOR ────────────────────────────────────────────────────────
 *
 * The engine posts `index / 12` at the START of each stage. So the events are 0.000, 0.083 … 0.917
 * whatever the stages actually cost, the last one ever sent is 0.917, and the two stages that dominate
 * a real trace (`edge` and `vectorize`) are worth several of the others put together. A bar driven
 * straight off that fraction rushes to a half and then appears to hang for most of the run — which
 * reads as a crash rather than as work. `progressWeights` re-weights the boundaries by what THIS
 * machine measured on its last completed trace, and until there has been one it says so in words.
 */

test.describe("the stage table is the engine's, transcribed", () => {
  test("the ids and their order are exactly engine/pipeline.stageIds()", () => {
    expect(TRACE_STAGES.map((stage) => stage.id)).toEqual(stageIds());
  });

  test("there are twelve of them, counted from the table rather than remembered", () => {
    expect(TRACE_STAGE_COUNT).toBe(TRACE_STAGES.length);
    expect(TRACE_STAGE_COUNT).toBe(stageIds().length);
  });

  test("every stage carries a label, because the weighting is keyed on the id and the test on both", () => {
    for (const stage of TRACE_STAGES) {
      expect(stage.id.length, "a stage with no id").toBeGreaterThan(0);
      expect(stage.label.length, `${stage.id} has no label`).toBeGreaterThan(0);
    }
  });

  test("traceStageIndex answers -1 for an id this build has never heard of", () => {
    expect(traceStageIndex("prepare")).toBe(0);
    expect(traceStageIndex("document")).toBe(TRACE_STAGE_COUNT - 1);
    expect(traceStageIndex("a-thirteenth-stage")).toBe(-1);
  });
});

test.describe("the sentence beside the spinner", () => {
  test("the ENGINE's label is passed through, never this table's", () => {
    // Re-typing engine wording in a client is how two clients end up describing one operation
    // differently. The table's label exists for the weighting and for the test above; what a
    // researcher reads is whatever arrived on the event.
    const sentence = traceProgressSentence("edge", "Detecting edges in a newer way");
    expect(sentence).toContain("Detecting edges in a newer way");
    expect(sentence).not.toContain("Detecting edges.");
  });

  test("it counts stages rather than quoting a percentage", () => {
    // The percentage is the thing `traceStages.ts` spends a page explaining is not a time estimate.
    expect(traceProgressSentence("edge", "Detecting edges")).toBe("Detecting edges. Stage 7 of 12.");
    expect(traceProgressSentence("prepare", "Preparing image")).toContain("Stage 1 of 12");
  });

  test("an unknown stage is described by its label alone rather than by a wrong number", () => {
    expect(traceProgressSentence("thirteenth", "Doing something new")).toBe("Doing something new");
  });
});

test.describe("the weights, and what they are before a machine has measured any", () => {
  test("UNWEIGHTED is the engine's own even spacing and admits it", () => {
    expect(UNWEIGHTED.measured).toBe(false);
    expect(UNWEIGHTED.startFractions.prepare).toBe(0);
    expect(UNWEIGHTED.startFractions.edge).toBeCloseTo(6 / 12, 10);
    // THE ENGINE'S FRACTION NEVER REACHES 1. The last stage STARTS at 11/12, which is the whole reason
    // a bar driven off it appears to stop short of the end.
    expect(UNWEIGHTED.startFractions.document).toBeCloseTo(11 / 12, 10);
    expect(Math.max(...Object.values(UNWEIGHTED.startFractions))).toBeLessThan(1);
  });

  test("a completed run's timings become boundaries that sum to the run", () => {
    const weights = progressWeights([
      { id: "prepare", millis: 10 },
      { id: "edge", millis: 70 },
      { id: "document", millis: 20 }
    ]);
    expect(weights.measured).toBe(true);
    expect(weights.startFractions.prepare).toBeCloseTo(0, 10);
    expect(weights.startFractions.edge).toBeCloseTo(0.1, 10);
    expect(weights.startFractions.document).toBeCloseTo(0.8, 10);
  });

  test("THE POINT: a dominant stage gets the width it actually takes", () => {
    // This is the difference between a bar that stalls at a half for most of a trace and a bar that
    // moves through the long stage at the rate the long stage takes.
    const weights = progressWeights([
      { id: "prepare", millis: 5 },
      { id: "edge", millis: 900 },
      { id: "vectorize", millis: 90 },
      { id: "document", millis: 5 }
    ]);
    const edgeStart = weights.startFractions.edge;
    const vectorizeStart = weights.startFractions.vectorize;
    expect(vectorizeStart - edgeStart).toBeGreaterThan(0.85);
    // Against the engine's own spacing the same span would be one twelfth.
    expect(UNWEIGHTED.startFractions.vectorize - UNWEIGHTED.startFractions.edge).toBeCloseTo(4 / 12, 10);
  });

  test("no timings, and timings that sum to nothing, both answer UNWEIGHTED rather than dividing by zero", () => {
    // Both are real states: a preview reports no timings at all, and a trace of a blank sheet can
    // finish fast enough for every stage to round to zero.
    expect(progressWeights([])).toBe(UNWEIGHTED);
    expect(progressWeights([{ id: "prepare", millis: 0 }, { id: "edge", millis: 0 }])).toBe(UNWEIGHTED);
    expect(progressWeights([{ id: "prepare", millis: Number.NaN }])).toBe(UNWEIGHTED);
  });

  test("a negative or non-finite timing is clamped rather than poisoning the whole bar", () => {
    const weights = progressWeights([
      { id: "prepare", millis: -50 },
      { id: "edge", millis: 100 },
      { id: "document", millis: Number.POSITIVE_INFINITY }
    ]);
    expect(weights.measured).toBe(true);
    for (const value of Object.values(weights.startFractions)) {
      expect(Number.isFinite(value)).toBe(true);
      expect(value).toBeGreaterThanOrEqual(0);
      expect(value).toBeLessThanOrEqual(1);
    }
  });
});

test.describe("fractionAt is what the bar is actually drawn from", () => {
  test("a stage the weights know wins over the fraction the engine sent", () => {
    const weights = progressWeights([
      { id: "prepare", millis: 10 },
      { id: "edge", millis: 90 }
    ]);
    expect(fractionAt(weights, "edge", 0.5)).toBeCloseTo(0.1, 10);
  });

  test("a stage the weights have never seen falls back to the engine's own fraction", () => {
    // Which happens on the very first event of a machine's first trace and after a vendored update.
    // Better a slightly wrong bar than none.
    expect(fractionAt(progressWeights([{ id: "prepare", millis: 10 }]), "edge", 0.5)).toBeCloseTo(0.5, 10);
  });

  test("the answer is always a drawable 0..1, whatever arrives", () => {
    expect(fractionAt(UNWEIGHTED, "nope", Number.NaN)).toBe(0);
    expect(fractionAt(UNWEIGHTED, "nope", -3)).toBe(0);
    expect(fractionAt(UNWEIGHTED, "nope", 7)).toBe(1);
  });
});

test("an unmeasured bar says so rather than stalling with no explanation", () => {
  // One line costs less than a researcher deciding the panel has frozen. The sentence names the
  // BROWSER because that is the platform it is on — the handset's counterpart says "this phone".
  expect(PROGRESS_UNMEASURED_NOTE).toContain("counts stages, not time");
  expect(PROGRESS_UNMEASURED_NOTE).toContain("browser");
  expect(PROGRESS_UNMEASURED_NOTE).not.toContain("phone");
});
