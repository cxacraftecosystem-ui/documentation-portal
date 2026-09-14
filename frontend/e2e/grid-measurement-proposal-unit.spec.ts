import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import {
  EMPTY_GRID_STATE,
  GRID_ANALYZING_STATUS,
  GRID_DISCARDED_STATUS,
  GRID_FAILED_STATUS,
  GRID_GROUP_HINTS,
  GRID_SECTION_HINT,
  GRID_UNREADABLE_STATUS,
  formatGridInches,
  gridFailureStatus,
  gridReduce,
  type GridEvent,
  type GridState,
  type GridWrite
} from "@/components/media/gridProposal";
import { ApiError, ApiUnconfiguredError } from "@/lib/api";
import { classifyMeasurementFailure, MEASUREMENT_FALLBACKS } from "@/lib/measurementFailure";
import type { MeasurementAnalysisResponse } from "@/lib/media";

/**
 * A VISION MODEL'S NUMBER MUST NOT REACH A FORM FIELD UNTIL A PERSON ACCEPTS IT — and this file is
 * the point of the change it guards.
 *
 * ── THE DEFECT ─────────────────────────────────────────────────────────────────────────────────
 *
 * `GridMeasurement.tsx` called `onLengthBreadth(...)` / `onHeight(...)` from inside the
 * `analyzeMeasurementImage` success block. So a vision model's estimate of an object photographed on
 * a sheet of graph paper landed in `ProductForm`'s and `ToolForm`'s `lengthInches` / `breadthInches`
 * / `heightInches` state with nobody's consent — and `records.merge_field_provenance` then stamped
 * that field with the `{by, byName, at}` of whoever pressed Save, because the dimension columns are
 * not in its `PROVENANCE_SKIP_FIELDS`. The stored row therefore asserted that a NAMED HUMAN had
 * measured the object. That number is printed as a documented dimension by
 * `services/record_fields.py`, is read by somebody costing a production run, and is carried into the
 * report a Development Commissioner's office receives.
 *
 * ── WHY THIS IS A NODE SPEC AND NOT A BROWSER ONE ──────────────────────────────────────────────
 *
 * There is no React renderer in this repository's devDependencies — Playwright is the whole of it —
 * so a judgement written inside JSX is only ever exercised by somebody looking at a screen, and this
 * is the judgement least suited to that: the broken state looks EXACTLY like the working one (a
 * number in a box) until a ministry reads the document. `components/media/gridProposal.ts` therefore
 * holds the whole decision as a pure state machine that RETURNS the write rather than performing one,
 * and the tests below drive it.
 *
 * The half that cannot be driven — that the component has no second door into form state — is a
 * source read.
 *
 * WHAT THESE DO NOT PROVE: that a browser paints the accept button, and that the marker survives into
 * the request body. The second is driven from `e2e/record-photo-measure-unit.spec.ts`, whose subject
 * is `components/forms/measurementMethods.ts`. What is true either way: a save with no marker is
 * recorded as `UNRECORDED`, which is honest and is never the false human claim.
 */

const ROOT = join(__dirname, "..");
// Line endings normalised for the same reason `record-parity-fields-unit.spec.ts` does it: the tree
// is CRLF on the machines this is developed on and LF in the repository, and every assertion below
// is an `indexOf` over raw text.
const read = (...parts: string[]) => readFileSync(join(ROOT, ...parts), "utf8").replace(/\r\n/g, "\n");

/** A response shaped exactly as the provenance payload builds it, around a real reading. */
function answered(analysis: MeasurementAnalysisResponse["analysis"]): MeasurementAnalysisResponse {
  return {
    available: true,
    status: "COMPLETED",
    analysis,
    method: "VISION_MODEL",
    provider: "gemini",
    modelId: "gemini-2.5-flash-lite",
    selfReportedConfidence: 0.8,
    confidenceIsCalibrated: false,
    requiresAcceptance: true,
    methodMarker: {
      method: "VISION_MODEL",
      provider: "gemini",
      modelId: "gemini-2.5-flash-lite",
      selfReportedConfidence: 0.8
    }
  };
}

/** Drive the machine through a list of events, collecting every write it produced. */
function run(events: GridEvent[], from: GridState = EMPTY_GRID_STATE) {
  let state = from;
  const writes: GridWrite[] = [];
  for (const event of events) {
    const step = gridReduce(state, event);
    state = step.state;
    if (step.write) writes.push(step.write);
  }
  return { state, writes };
}

test("a reading does not enter form state until it is accepted", () => {
  // Capture, then the model answers. This is the entire sequence the old code auto-filled from.
  const offered = run([
    { type: "CAPTURE", group: "lengthBreadth" },
    { type: "ANALYSIS", group: "lengthBreadth", response: answered({ lengthInches: 3.5, breadthInches: 2 }) }
  ]);

  // THE ASSERTION THE WHOLE CHANGE EXISTS FOR: the model has answered, the figure is on screen, and
  // nothing has been written anywhere.
  expect(offered.writes).toEqual([]);
  const proposal = offered.state.proposals.lengthBreadth;
  expect(proposal, "the reading must be held as an offer, not dropped").toBeTruthy();
  expect(proposal?.readings.map((reading) => reading.label)).toEqual(['L 3.50"', 'B 2.00"']);
  expect(offered.state.status.lengthBreadth).toBe('Read L 3.50" · B 2.00" — check it against the object');

  // Only the button writes, and it writes exactly what it printed.
  const accepted = run([{ type: "ACCEPT", group: "lengthBreadth" }], offered.state);
  expect(accepted.writes).toHaveLength(1);
  expect(accepted.writes[0].readings.map((reading) => [reading.dimension, reading.value])).toEqual([
    ["length", "3.50"],
    ["breadth", "2.00"]
  ]);
  expect(accepted.state.status.lengthBreadth).toBe('Filled L 3.50" · B 2.00" — still editable');
});

test("the accepted value carries the method that produced it, so the human stamp stops lying", () => {
  const { writes } = run([
    { type: "CAPTURE", group: "height" },
    { type: "ANALYSIS", group: "height", response: answered({ valueInches: 4 }) },
    { type: "ACCEPT", group: "height" }
  ]);
  // Echoed back VERBATIM as it arrived — the server writes it beside `{by, byName, at}` so the row
  // reads "a vision model estimated this, and this person accepted it", rather than as a measurement
  // that person took.
  expect(writes[0].marker).toEqual({
    method: "VISION_MODEL",
    provider: "gemini",
    modelId: "gemini-2.5-flash-lite",
    selfReportedConfidence: 0.8
  });
  expect(writes[0].readings[0].label).toBe("4.00 in");
});

test("an absent marker stays absent — it is never defaulted to TYPED", () => {
  // An older server that has not deployed the provenance payload sends no marker. A save carrying
  // none is recorded as UNRECORDED, which is honest and distinguishable. Reading the absence as
  // "typed" would be the original defect with a new spelling: it would assert a human measured a
  // number a machine guessed, for exactly the rows where the assertion is false.
  const legacy: MeasurementAnalysisResponse = { available: true, status: "COMPLETED", analysis: { valueInches: 4 } };
  const { writes } = run([
    { type: "ANALYSIS", group: "height", response: legacy },
    { type: "ACCEPT", group: "height" }
  ]);
  expect(writes[0].marker).toBeNull();
});

test("the proposal rule is UNCONDITIONAL — `requiresAcceptance` cannot switch it off", () => {
  /*
    A SERVER THAT FORGOT THE FLAG, OR ONE THAT PREDATES IT, MUST NOT BE ABLE TO TURN A PROPOSAL BACK
    INTO AN AUTO-FILL. The rule belongs to this client, not to the deployment — the human stamp that
    an auto-fill makes into a lie is written by the server whatever a response body says. So the flag
    is declared on the wire type and read by nothing: the reading below is held as an offer with
    `requiresAcceptance` explicitly false.
  */
  const disclaims: MeasurementAnalysisResponse = { ...answered({ valueInches: 4 }), requiresAcceptance: false };
  const { writes, state } = run([{ type: "ANALYSIS", group: "height", response: disclaims }]);
  expect(writes).toEqual([]);
  expect(state.proposals.height, "still an offer, not a write").toBeTruthy();
});

test("every way an offer can end without acceptance ends with nothing written", () => {
  const offered = run([
    { type: "CAPTURE", group: "lengthBreadth" },
    { type: "ANALYSIS", group: "lengthBreadth", response: answered({ lengthInches: 3.5, breadthInches: 2 }) }
  ]).state;

  // The researcher refuses the reading.
  const discarded = run([{ type: "DISCARD", group: "lengthBreadth" }], offered);
  expect(discarded.writes).toEqual([]);
  expect(discarded.state.proposals.lengthBreadth).toBeUndefined();
  expect(discarded.state.status.lengthBreadth).toBe(GRID_DISCARDED_STATUS);

  // Unchecking the dimension takes the status line with it — the row must come back blank rather
  // than carrying the last thing said about a photograph the form no longer has.
  const unchecked = run([{ type: "DISCARD", group: "lengthBreadth", clearStatus: true }], offered);
  expect(unchecked.writes).toEqual([]);
  expect(unchecked.state.status.lengthBreadth).toBeUndefined();

  // A re-capture retracts the previous offer BEFORE it asks for a new one. Leaving it standing would
  // put an accept button under a figure belonging to a photograph that has just been replaced.
  const recaptured = run([{ type: "CAPTURE", group: "lengthBreadth" }], offered);
  expect(recaptured.writes).toEqual([]);
  expect(recaptured.state.proposals.lengthBreadth).toBeUndefined();
  expect(recaptured.state.status.lengthBreadth).toBe(GRID_ANALYZING_STATUS);

  // And a failure cannot leave a stale offer behind either.
  const failed = run([{ type: "FAILURE", group: "lengthBreadth", error: new Error("boom") }], offered);
  expect(failed.writes).toEqual([]);
  expect(failed.state.proposals.lengthBreadth).toBeUndefined();
});

test("the offer is spent when it is taken, so the button cannot write twice", () => {
  const { writes, state } = run([
    { type: "ANALYSIS", group: "height", response: answered({ valueInches: 4 }) },
    { type: "ACCEPT", group: "height" },
    { type: "ACCEPT", group: "height" }
  ]);
  expect(writes).toHaveLength(1);
  expect(state.proposals.height).toBeUndefined();
});

test("an accept with nothing on offer writes nothing at all", () => {
  // A double press, a stale render, a keyboard activation after the card has gone. It must not
  // resurrect a figure from anywhere.
  const { writes, state } = run([{ type: "ACCEPT", group: "lengthBreadth" }]);
  expect(writes).toEqual([]);
  expect(state).toEqual(EMPTY_GRID_STATE);
});

test("half a reading is still an offer, and no reading at all is not an error", () => {
  // One photograph, two dimensions: a top-down shot the model read a length from and no breadth is an
  // ordinary outcome and must be acceptable for the half it did read.
  const half = run([
    { type: "ANALYSIS", group: "lengthBreadth", response: answered({ lengthInches: 3.5, breadthInches: null }) },
    { type: "ACCEPT", group: "lengthBreadth" }
  ]);
  expect(half.writes[0].readings.map((reading) => reading.dimension)).toEqual(["length"]);

  // Nothing readable: the provider was reached and looked, so this is not worded as a failure.
  const none = run([{ type: "ANALYSIS", group: "lengthBreadth", response: answered({}) }]);
  expect(none.writes).toEqual([]);
  expect(none.state.proposals.lengthBreadth).toBeUndefined();
  expect(none.state.status.lengthBreadth).toBe(GRID_UNREADABLE_STATUS);
});

test("an old server's 200 with available:false keeps the sentence that names the missing key", () => {
  // Collapsing this into "couldn't read a value" is precisely the confusion the separate status was
  // introduced to end: a researcher re-photographs the object in better light for ever while the real
  // answer is that nobody has configured a vision provider.
  const message = "Grid measurement is unavailable because no Gemini API key is configured.";
  const { state, writes } = run([
    { type: "ANALYSIS", group: "height", response: { available: false, status: "UNAVAILABLE", analysis: null, message } }
  ]);
  expect(writes).toEqual([]);
  expect(state.status.height).toBe(message);
});

test("a 200 whose status is FAILED is the PROVIDER failing, not an unreadable grid", () => {
  /*
    `analysis` is null on this path, so a reader that looks for readings first finds none and reaches
    the "couldn't read a value" branch before anything has asked why — telling a researcher to
    re-photograph a perfectly good object while the server is holding a sentence saying the provider
    rate-limited. ORDER IS THE WHOLE FIX: the body is asked about before the numbers.
  */
  const message = "The measurement provider refused the request: rate limit exceeded.";
  const { state, writes } = run([
    { type: "ANALYSIS", group: "height", response: { available: true, status: "FAILED", analysis: null, message } }
  ]);
  expect(writes).toEqual([]);
  expect(state.status.height).toBe(message);
  expect(state.status.height).not.toBe(GRID_UNREADABLE_STATUS);
});

test("the value written matches the two decimals the button prints and the box accepts", () => {
  // `ProductForm`/`ToolForm` declare every dimension as `type="number" step="0.01"`, and the forms
  // carry no `noValidate` — so a value with more decimals is a `stepMismatch` and the browser refuses
  // the submit with a bubble on a field the researcher never touched. `String(value)` used to write
  // exactly that.
  expect(formatGridInches(3.4967)).toBe("3.50");
  expect(formatGridInches("2")).toBe("2.00");

  // Not a dimension of anything — and `0.00` in a documented measurement is a confident answer about
  // nothing, which is worse than no answer.
  expect(formatGridInches(0.004)).toBeNull();
  expect(formatGridInches(0)).toBeNull();
  expect(formatGridInches(-1)).toBeNull();
  expect(formatGridInches(null)).toBeNull();
  expect(formatGridInches("about four inches")).toBeNull();
});

test("a failed read says WHICH failure it was, because they need different things done about them", () => {
  /*
    THE ERRORS ARE BUILT THE WAY `apiFetch` BUILDS THEM, PAYLOAD AND ALL, and that is not decoration.
    `apiFetch` sets `message = describeApiDetail(detail, statusText || "The server refused the request
    (HTTP ${status}).")` and `payload = body`, so the BODY is the only place "the server put a sentence
    here" is visible — `statusText` is EMPTY over HTTP/2, which every deployed request is. An error
    constructed as `new ApiError(503, sentence, null)` is a shape `apiFetch` cannot produce, and a test
    written that way could not catch a classifier that quotes a fabricated message.
  */
  const fromServer = (status: number, detail: string) => new ApiError(status, detail, { detail });

  // 1. Nothing reached the server. The section hint has already warned that this control needs one,
  //    so this reads as the stated limit rather than as a fault.
  expect(gridFailureStatus(new TypeError("Failed to fetch"))).toContain("No connection");

  // 2. The server answered and refused, in its own words — the sentence names the setting, and no
  //    client could have guessed it. A generic "Analysis failed" here is the defect: it sends a
  //    researcher back out to re-photograph an object over a problem only an administrator can fix.
  const unconfigured =
    "Grid measurement is unavailable because no Gemini API key is configured. Measure the object and " +
    "type the value in, or ask whoever administers the server to add GEMINI_API_KEY in the Settings hub.";
  expect(gridFailureStatus(fromServer(503, unconfigured))).toBe(unconfigured);
  expect(gridFailureStatus(fromServer(413, "The image is larger than the 8 MB limit."))).toContain("8 MB");

  // 3. THIS BUILD has no API address, so no request was ever made. Its own sentence is about a
  //    redeploy and has nothing to do with a vision provider — read as the provider's 503 it would
  //    send an operator to add a key that changes nothing. `ApiUnconfiguredError` IS an `ApiError`
  //    with status 503, so this is the guard that has to be explicit rather than ordering-dependent.
  const appUnconfigured = gridFailureStatus(new ApiUnconfiguredError());
  expect(appUnconfigured).toContain("address of its data service");
  expect(classifyMeasurementFailure(new ApiUnconfiguredError()).kind).toBe("app-unconfigured");
  expect(classifyMeasurementFailure(new ApiUnconfiguredError()).serverSaidIt, "no server said this").toBe(false);

  // 4. AND A 503 WITH NO BODY BEHIND IT IS NOT THE UNCONFIGURED SENTENCE. A gateway in a deploy window
  //    answers with no `detail`, so `ApiError.message` is `apiFetch`'s own last resort — the literal
  //    "The server refused the request (HTTP 503)." Printing that would show a status code on a screen
  //    whose whole promise is that it never does, dressed as the server naming a missing key.
  const bodyless = new ApiError(503, "The server refused the request (HTTP 503).", null);
  expect(gridFailureStatus(bodyless)).not.toContain("HTTP 503");
  expect(gridFailureStatus(bodyless)).not.toBe(GRID_FAILED_STATUS);
  expect(classifyMeasurementFailure(bodyless).serverSaidIt, "the reply carried no words").toBe(false);
  // It still says the true thing — nobody has switched this on here, and it is not the photograph.
  expect(gridFailureStatus(bodyless)).toContain("administers the server");

  // And they are genuinely distinguishable, which is the whole claim.
  const said = [
    gridFailureStatus(new TypeError("Failed to fetch")),
    gridFailureStatus(fromServer(503, unconfigured)),
    gridFailureStatus(fromServer(413, "The image is larger than the 8 MB limit.")),
    appUnconfigured,
    gridFailureStatus(bodyless)
  ];
  expect(new Set(said).size).toBe(5);
});

test("no sentence this can produce blames the photograph", () => {
  /*
    THE GUARANTEE THE TYPE CARRIES, CHECKED. `MeasurementRemedy` has no "take another photograph"
    value, and its absence is the point: nothing the classifier produces is a judgement about what is
    in the picture — its lighting, its framing, whether the grid is visible. Every verdict is the
    deployment, the connection, or the request as a request. The one genuine "the model looked and
    could not read it" outcome is a 2xx with no numbers in it, and that is `GRID_UNREADABLE_STATUS`,
    which is not routed through here at all.
  */
  const failures = [
    new TypeError("Failed to fetch"),
    new ApiUnconfiguredError(),
    new ApiError(503, "", null),
    new ApiError(408, "", null),
    new ApiError(429, "", null),
    new ApiError(500, "", null),
    new ApiError(415, "", null)
  ];
  for (const error of failures) {
    const failure = classifyMeasurementFailure(error);
    expect(failure.remedy, `${failure.kind} must name who clears it`).not.toBe("another-photograph");
    expect(failure.sentence, `${failure.kind} must not print a status code`).not.toMatch(/HTTP \d/);
  }

  /*
    AND EVERY SENTENCE WRITTEN ON THIS SIDE ENDS AT SOMETHING THAT WORKS WITH NO CONNECTION, because
    there always is one: the tape measure and the box beside it. Asserted over the FALLBACK TABLE
    rather than over produced sentences, and the distinction is real —

    `app-unconfigured` borrows `ApiUnconfiguredError`'s own message, which is composed in `lib/api.ts`
    for every screen in the app and correctly names the redeploy without mentioning measuring by hand.
    Quoting it is still the right call (it is specific, already written, and true), but it means the
    "offer the manual route" rule holds of what this module WRITES, not of what it QUOTES. Recorded
    here rather than papered over: a one-clause addition to that sentence in `lib/api.ts` would close
    it, and that file belongs to the fetch layer rather than to this feature.
  */
  for (const [kind, sentence] of Object.entries(MEASUREMENT_FALLBACKS)) {
    expect(sentence.toLowerCase(), `${kind} offers the manual route`).toContain("manually");
    expect(sentence, `${kind} must not print a status code`).not.toMatch(/HTTP \d/);
    // None of them guesses at a provider's setting name: that is a server fact this bundle cannot see,
    // and the day the provider changes it would send an administrator to a setting that is gone.
    expect(sentence, `${kind} must not name a setting`).not.toMatch(/[A-Z_]{4,}_(KEY|URL|TOKEN)/);
  }
  expect(
    classifyMeasurementFailure(new ApiUnconfiguredError()).sentence,
    "the borrowed sentence is the app's own, verbatim"
  ).toBe(new ApiUnconfiguredError().message);
  // And a 408 is told apart from "you have no connection": one sends a researcher out of the
  // building looking for signal, and the other does not.
  expect(classifyMeasurementFailure(new ApiError(408, "", null)).kind).toBe("timed-out");
  expect(classifyMeasurementFailure(new TypeError("Failed to fetch")).kind).toBe("offline");
});

test("the copy says the control needs a connection, and no longer promises a fill", () => {
  /*
    THE HINT MOVED WITH THE BEHAVIOUR, IN THE SAME EDIT. It used to read "The measured inches
    auto-fill the matching field(s) (still editable)" — a hint that describes a write the control no
    longer makes reads as a broken control rather than a deliberate one.

    AND IT NOW SAYS IT NEEDS A CONNECTION, which is the half this client was missing outright:
    `POST /media/analyze-measurement` has no queue, no outbox and no retry, so in a courtyard with no
    signal this control fails every time and the failure used to be a surprise at the end.
  */
  for (const clause of [
    "Reading the photo needs a connection",
    "offered for you to check and accept",
    "nothing is written into a",
    "field until you press the button"
  ]) {
    expect(GRID_SECTION_HINT).toContain(clause);
  }
  expect(GRID_SECTION_HINT).not.toContain("auto-fill");

  // The per-group hints moved with it, and say "offers" rather than "fills".
  for (const hint of Object.values(GRID_GROUP_HINTS)) {
    expect(hint).toContain("offers");
    expect(hint).not.toContain("fills");
  }
});

test("the component has exactly one door into form state, and it is the accept button", () => {
  // The source read, because this is the part no pure function can hold: that nothing ELSE in the
  // component calls the write callbacks. Comments are stripped first so the prose above the code —
  // which names both callbacks repeatedly, on purpose — cannot satisfy or break the count.
  const source = read("components", "media", "GridMeasurement.tsx")
    .replace(/\/\*[\s\S]*?\*\//g, "")
    .replace(/^\s*\/\/.*$/gm, "");

  expect(source.match(/onLengthBreadth\(/g) ?? []).toHaveLength(1);
  expect(source.match(/onHeight\(/g) ?? []).toHaveLength(1);

  // …and both of them are inside `accept`, which is the only function that runs from the button.
  // `renderGroup` is the next declaration, so it bounds the slice.
  const from = source.indexOf("function accept(");
  const to = source.indexOf("function renderGroup(");
  expect(from, "accept() not found — has the component been restructured?").toBeGreaterThan(-1);
  expect(to).toBeGreaterThan(from);
  const acceptBody = source.slice(from, to);
  expect(acceptBody).toContain("onLengthBreadth(");
  expect(acceptBody).toContain("onHeight(");

  // The regression witness: the analysis handler must not write. If a future edit puts a callback
  // back into the response path, the count above fails first — this names what it would mean.
  const pickFrom = source.indexOf("async function pick(");
  const pickTo = source.indexOf("function accept(");
  const pickBody = source.slice(pickFrom, pickTo);
  expect(pickBody).not.toContain("onLengthBreadth(");
  expect(pickBody).not.toContain("onHeight(");
});
