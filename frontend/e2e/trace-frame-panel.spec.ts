import { readFileSync } from "node:fs";
import { join } from "node:path";
import { deflateSync } from "node:zlib";

import { expect, test, type Page } from "@playwright/test";
import * as ts from "typescript";

/**
 * `TracePanel` AND ITS FRAME CHOOSER, RENDERED — in a real browser, by real React, with its real
 * effects running.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * WHY THIS FILE EXISTS, AND WHY THE CLAIM THAT IT COULD NOT WAS WRONG
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * When the tracer was ported to this repository the designer portal's rendered panel spec was dropped,
 * and the reason given was that "there is no React renderer in this repository's devDependencies".
 * That is true and it is not a reason: **the designer portal has none either.** Its
 * `e2e/sketch-trace-panel.spec.ts` renders the same panel in a real browser through Playwright, in
 * twenty-two cases, and says so in its own header. This file is that approach, here.
 *
 * The cost of not having it is specific rather than theoretical. The crop tool's arithmetic can be —
 * and now is — checked without a browser (`e2e/trace-frame-geometry-unit.spec.ts`), but the arithmetic
 * was never the half that broke. What broke was the WIRING: a drag on the picture that did nothing at
 * all, a corner handle whose gesture reset the frame to the whole photograph, arrow keys that stepped
 * a ninth of a drawn pixel. Every one of those lives in the render-and-effect layer, and a suite that
 * renders nothing coexisted with all of them while staying green.
 *
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 * HOW A COMPONENT GETS RENDERED HERE WITH NO COMPONENT-TEST RUNNER
 * ══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * There is none installed — no `@playwright/experimental-ct-react`, no jsdom, no bundler beyond Next's
 * own, and `package.json` is not this lane's file to edit. What IS installed is TypeScript and React,
 * so this file uses them directly:
 *
 *  1. `ts.transpileModule` compiles each real source file to CommonJS, one file at a time. No type
 *     checking, which is `npx tsc --noEmit`'s job and not this file's.
 *  2. A twelve-line CommonJS registry in the page resolves `require` by the specifier string exactly
 *     as written, so `"./frameGeometry"` is a key rather than a path.
 *  3. React, `react/jsx-runtime`, `react-dom`, `react-dom/client` and `scheduler` are registered from
 *     their PRODUCTION CJS builds in `node_modules`.
 *
 * WHAT IS REAL AND WHAT IS STOOD IN FOR, because a harness that quietly replaces the thing under test
 * proves nothing:
 *
 *  · REAL: `TracePanel.tsx`, `FramePanel.tsx` and `frameGeometry.ts` — the three files this port
 *    added or changed — plus `traceParamTable.ts`, `decodeToPixels.ts` (so the browser really decodes
 *    the image), `traceExport.ts`, `geometryToSvg.ts`, `comparisonPlates.ts`, `TraceCompare.tsx`,
 *    `compareTransform.ts`, `traceStages.ts`, `saveToDevice.ts`, `lib/utils.ts` and the engine's own
 *    `lib/trace/engine/params.ts` — so every slider is drawn from the real defaults and every patch
 *    goes through the real `sanitizeTraceParams`.
 *  · STOOD IN FOR: `traceRuntime.ts`, `imageEditRuntime.ts`, `lucide-react` and
 *    `@/components/ui/Dropdown`. The two runtime stubs are the seams this spec turns: the real ones
 *    dynamic-import 40-odd engine files and start module workers, neither of which this registry can
 *    resolve, and neither of which is what these cases are about. `e2e/trace-frame-sharpen-unit.spec.ts`
 *    is where the crop-and-sharpen arithmetic itself is exercised, against the real module.
 *
 * WHY THE LAST STAND-IN EXISTS, since `Dropdown` is a real file that could have been compiled: it is
 * three adapters over `SearchableSelect`, which pulls `AnchoredPopover` and a small tree behind it.
 * None of that is what any case here asserts, and a module the registry does not hold is a `require`
 * that throws AT MOUNT — so every case would fail with the harness's own message rather than with
 * anything about the panel. The stub is a native `<select>` carrying the same `aria-label`.
 *
 * WHY IT IS NOT NAMED `-unit`. `npm run test:unit` runs `.*-unit\.spec\.ts`, and this spec asks for
 * the `page` fixture, so carrying the suffix would make that job need a browser. It needs a BROWSER
 * and nothing else: no dev server, no API, no database, no credentials. One command runs it:
 *
 *     cd frontend && npx playwright test trace-frame-panel --reporter=line
 */

const TRACE_DIR = join(__dirname, "..", "components", "trace");
const NODE_MODULES = join(__dirname, "..", "node_modules");
const ENGINE_PARAMS = join(__dirname, "..", "lib", "trace", "engine", "params.ts");
const UTILS = join(__dirname, "..", "lib", "utils.ts");

/**
 * The runtime-load delay every case mounts with.
 *
 * NOT AN ARBITRARY NUMBER. It is longer than one React commit and far shorter than a real chunk fetch
 * on a field hotspot, which is the case that has to work. The engine-load bug this approach was
 * written against — an effect that listed a piece of state it wrote in its own dependency array —
 * happened to pass with a 0 ms stand-in, because the load won the race against its own teardown, which
 * is exactly how it survived being tried by hand.
 */
const RUNTIME_LOAD_MS = 60;

/** The card's own name, which is the closed panel's whole trigger. */
const CARD_TITLE = "Trace this image into line art";

/* ────────────────────────────────────────────────────────────────────────────
 * Compiling the real sources
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * One file, to CommonJS, with the JSX transform React 19 actually uses.
 *
 * No `paths` resolution and no module graph walking: every specifier these files use is either a
 * sibling (`"./frameGeometry"`) or a bare/aliased name, and the registry keys on the string as
 * written. `import type` statements are erased by the transpiler, which is why the `@/lib/trace/*`
 * type imports in `imageEditRuntime.ts` and `traceParamTable.ts` need no entry at all.
 */
function compile(file: string): string {
  return ts.transpileModule(readFileSync(file, "utf8"), {
    fileName: file,
    compilerOptions: {
      target: ts.ScriptTarget.ES2020,
      module: ts.ModuleKind.CommonJS,
      jsx: ts.JsxEmit.ReactJSX
    }
  }).outputText;
}

/** The CommonJS registry, and `process` for anything that sniffs for it. Injected before everything. */
const LOADER = `
window.process = window.process || { env: { NODE_ENV: "production" } };
window.__mods = {};
window.__define = function (id, factory) { window.__mods[id] = { factory: factory, mod: null }; };
window.__require = function (id) {
  var entry = window.__mods[id];
  if (!entry) throw new Error("The harness has no module named " + id);
  if (entry.mod === null) {
    entry.mod = { exports: {} };
    entry.factory(entry.mod, entry.mod.exports, window.__require);
  }
  return entry.mod.exports;
};
`;

/** Wrap compiled CommonJS as a registry entry. */
function define(id: string, code: string): string {
  return `window.__define(${JSON.stringify(id)}, function (module, exports, require) {\n${code}\n});`;
}

/* ────────────────────────────────────────────────────────────────────────────
 * The stubs
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * Every lucide icon, rendering nothing.
 *
 * The two panels use ten of them and they carry `aria-hidden` on every use, so they contribute nothing
 * to the accessible tree these cases assert on. A `<svg>` per icon would only add noise.
 */
const LUCIDE_STUB = `
window.__define("lucide-react", function (module, exports, require) {
  module.exports = new Proxy({}, {
    get: function (target, key) {
      if (key === "__esModule") return true;
      return function Icon() { return null; };
    }
  });
});
`;

/**
 * `@/components/ui/Dropdown`, as a native `<select>`.
 *
 * The panel's Style and Subject pickers are the only callers. They pass `value`, `onChange`,
 * `disabled`, `ariaLabel` and `options`, and every case here reaches them (if at all) by that
 * accessible name — so a `<select>` carrying the same name and the same options is behaviourally the
 * part under test.
 */
const DROPDOWN_STUB = `
window.__define("@/components/ui/Dropdown", function (module, exports, require) {
  var React = require("react");
  module.exports.Dropdown = function (props) {
    return React.createElement(
      "select",
      {
        value: props.value,
        disabled: !!props.disabled,
        "aria-label": props.ariaLabel,
        onChange: function (event) { props.onChange(event.target.value); }
      },
      (props.options || []).map(function (option) {
        return React.createElement("option", { key: option.value, value: option.value }, option.label);
      })
    );
  };
});
`;

/**
 * `traceRuntime.ts`, stood in for — and the knobs this whole file turns.
 *
 * `window.__runtimeMs` is how long the engine takes to arrive and `window.__traces` records every
 * trace the panel asked for: its size and whether it was a preview. That record is the assertion that
 * matters for a crop tool — "the frame reached the tracer" is a fact about what was handed over, not
 * about a sentence the panel wrote concerning itself.
 *
 * The result geometry is one two-segment open stroke, which is enough for the real `paintGeometry` to
 * paint and the real `buildSvg` to write. Document coordinates are the traced frame's, and a PREVIEW
 * reports half that as its working size, because that difference is what the panel states on screen
 * and what makes attaching re-trace at full resolution.
 *
 * NO BACKTICKS ANYWHERE IN THIS STUB. It is the body of a template literal in this spec file, so one
 * backtick in a comment closes the string and the whole file stops parsing.
 */
const RUNTIME_STUB = `
window.__define("./traceRuntime", function (module, exports, require) {
  var engine = require("@/lib/trace/engine/params");
  var delay = function (ms) { return new Promise(function (r) { setTimeout(r, ms); }); };

  function Cancelled() { var e = new Error("The trace was cancelled."); e.__cancelled = true; return e; }
  function Unavailable(m) { var e = new Error(m); e.__unavailable = true; return e; }

  function result(width, height, preview, params) {
    return {
      geometry: {
        coords: new Float32Array([2, 2, width - 2, 2, width - 2, height - 2]),
        verbs: new Uint8Array([0, 0]),
        verbStarts: new Uint32Array([0, 2]),
        coordStarts: new Uint32Array([0, 6]),
        closed: new Uint8Array([0]),
        styleTable: [{
          stroke: 0xff000000, strokeWidth: 1.5, fill: null, fillRule: "NONZERO",
          cap: "ROUND", join: "ROUND", miterLimit: 4, opacity: 1
        }],
        styleIndex: new Uint32Array([0])
      },
      background: null,
      width: width,
      height: height,
      workingWidth: preview ? Math.max(1, Math.round(width / 2)) : width,
      workingHeight: preview ? Math.max(1, Math.round(height / 2)) : height,
      shapeCount: 1,
      nodeCount: 3,
      stages: [],
      totalMillis: 12,
      notes: ["A stub engine traced this."],
      profile: null,
      appliedParams: params,
      autoSubjectId: ""
    };
  }

  function Tracer() {}
  Tracer.prototype.trace = function (request) {
    window.__traces.push({
      width: request.image.width,
      height: request.image.height,
      preview: !!request.preview
    });
    return delay(window.__traceMs).then(function () {
      if (request.signal && request.signal.aborted) throw Cancelled();
      return result(request.image.width, request.image.height, !!request.preview, request.params);
    });
  };
  Tracer.prototype.dispose = function () { window.__disposals += 1; };

  var runtime = {
    Tracer: Tracer,
    transferableFrom: function (source) {
      return { data: source.data.slice(), width: source.width, height: source.height };
    },
    defaults: engine.defaultTraceParams(),
    sanitize: engine.sanitizeTraceParams,
    isUnavailable: function (error) { return !!(error && error.__unavailable); },
    isCancelled: function (error) { return !!(error && error.__cancelled); }
  };

  module.exports.loadTraceRuntime = function () {
    window.__runtimeLoads += 1;
    return delay(window.__runtimeMs).then(function () {
      if (window.__runtimeFails) {
        throw Unavailable("The tracing engine could not be loaded. Check your connection and reload the page.");
      }
      return runtime;
    });
  };

  module.exports.loadTracePresets = function () {
    return delay(0).then(function () {
      return {
        styles: [{
          id: "ink", name: "Ink line", description: "A stub style.", group: "Line",
          params: runtime.defaults
        }],
        styleGroups: ["Line"],
        subjects: [{ id: "pencil", name: "Pencil on paper", hint: "A stub subject.", adjust: function (p) { return p; } }]
      };
    });
  };
});
`;

/**
 * `imageEditRuntime.ts`, stood in for — and the stand-in really crops.
 *
 * `window.__edits` records every request, so a case can assert that pressing "Use this frame for the
 * trace" is what changes the size the tracer is handed, rather than asserting on a sentence the panel
 * wrote about itself. The real door starts a module worker and imports `engine/contrast`, neither of
 * which this registry can resolve.
 *
 * THE NOTE COMES BACK FROM THE EDITOR, and this stand-in deliberately says something the real function
 * would NOT. The real sentence is built inside the worker by `lib/trace/imageEdit.describeEdit` — the
 * only side of the bundle boundary that can call it — and a panel that quietly rebuilt that sentence
 * itself would satisfy a stub that agreed with it, and fails against this one.
 *
 * NO BACKTICKS ANYWHERE IN THIS STUB, per `RUNTIME_STUB`'s warning.
 */
const EDIT_RUNTIME_STUB = `
window.__define("./imageEditRuntime", function (module, exports, require) {
  var delay = function (ms) { return new Promise(function (r) { setTimeout(r, ms); }); };

  function ImageEditor() {}
  ImageEditor.prototype.edit = function (call) {
    window.__edits.push({
      width: call.pixels.width,
      height: call.pixels.height,
      crop: call.crop,
      sharpen: call.sharpen
    });
    return delay(window.__editMs).then(function () {
      if (window.__editFails) throw new Error("This device would not process that photograph.");
      var w = call.crop.width;
      var h = call.crop.height;
      var out = new Uint8ClampedArray(w * h * 4);
      for (var row = 0; row < h; row++) {
        var from = ((call.crop.y + row) * call.pixels.width + call.crop.x) * 4;
        out.set(call.pixels.data.subarray(from, from + w * 4), row * w * 4);
      }
      var note = "Cropped on the device to " + w + "x" + h + " at (" + call.crop.x + ", " + call.crop.y +
        ") of " + call.pixels.width + "x" + call.pixels.height + ". [from the editor]";
      return { data: out, width: w, height: h, note: note, millis: 7 };
    });
  };
  ImageEditor.prototype.dispose = function () { window.__editorDisposals += 1; };

  module.exports.loadImageEditor = function () {
    return delay(0).then(function () {
      return {
        ImageEditor: ImageEditor,
        isUnavailable: function () { return false; },
        isCancelled: function (error) { return !!(error && error.name === "ImageEditCancelledError"); }
      };
    });
  };
});
`;

/**
 * The handful of utility classes that carry GEOMETRY rather than appearance.
 *
 * ── WHY ANY CSS AT ALL, WHEN NONE OF THESE CASES IS ABOUT HOW THE PANEL LOOKS ──────────────────
 *
 * Because the frame overlay is POSITIONED, and a pointer gesture has to aim at where a control
 * actually is. Tailwind is not compiled in this harness, so `absolute`, `relative`, `inset-0` and
 * `h-4 w-4` are inert strings: measured on the first run of the drag case, all four corner handles
 * fell out of the overlay into normal document flow — 16×6 buttons stacked 340 px BELOW the picture
 * they are supposed to sit on — and a drag computed from the picture's corner landed on nothing. The
 * failure looked like a broken panel and was a missing stylesheet.
 *
 * ── WHAT IS AND IS NOT BEING SUBSTITUTED ──────────────────────────────────────────────────────
 *
 * Only the layout primitives, and every one of them is Tailwind's own definition. The POSITIONS
 * themselves are the component's, written as inline percentage styles (`left: "50%"`), which is
 * exactly the thing under test: `FramePanel` positions the box and the handles in percentages of the
 * picture precisely so they cannot disagree with whatever size the browser drew it at. Nothing here
 * substitutes for that arithmetic — it makes it observable.
 *
 * No colours, no spacing, no typography: a case that asserted on those would be asserting on a
 * stylesheet this harness does not have.
 */
const GEOMETRY_CSS = `
  * { box-sizing: border-box; }
  button { padding: 0; margin: 0; }
  .relative { position: relative; }
  .absolute { position: absolute; }
  .inset-0 { top: 0; right: 0; bottom: 0; left: 0; }
  .block { display: block; }
  .w-full { width: 100%; }
  .h-full { height: 100%; }
  .pointer-events-none { pointer-events: none; }
  .h-4 { height: 1rem; }
  .w-4 { width: 1rem; }
  .border-2 { border-width: 2px; }
  .-translate-x-1\\/2.-translate-y-1\\/2 { transform: translate(-50%, -50%); }
`;

/* ────────────────────────────────────────────────────────────────────────────
 * An image, made here
 * ──────────────────────────────────────────────────────────────────────────── */

const CRC_TABLE = (() => {
  const table = new Uint32Array(256);
  for (let n = 0; n < 256; n += 1) {
    let c = n;
    for (let k = 0; k < 8; k += 1) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    table[n] = c >>> 0;
  }
  return table;
})();

function crc32(buffer: Buffer): number {
  let c = 0xffffffff;
  for (const byte of buffer) c = CRC_TABLE[(c ^ byte) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}

function chunk(type: string, data: Buffer): Buffer {
  const length = Buffer.alloc(4);
  length.writeUInt32BE(data.length, 0);
  const body = Buffer.concat([Buffer.from(type, "ascii"), data]);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(body), 0);
  return Buffer.concat([length, body, crc]);
}

/**
 * A flat 8-bit RGB PNG of exactly this size.
 *
 * Built here rather than checked in for one reason: these cases assert on the SIZE the panel decoded
 * and traced, and a fixture whose dimensions live in another file is an assertion whose subject can be
 * changed by somebody who never opened this one. Nothing about the picture matters — the tracer is
 * stubbed — only its dimensions and its name.
 */
function png(width: number, height: number, grey = 200): Buffer {
  const stride = width * 3 + 1;
  const raw = Buffer.alloc(stride * height, grey);
  for (let y = 0; y < height; y += 1) raw[y * stride] = 0; // filter type 0, per scanline
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(width, 0);
  ihdr.writeUInt32BE(height, 4);
  ihdr[8] = 8; // bit depth
  ihdr[9] = 2; // colour type 2 = truecolour RGB
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk("IHDR", ihdr),
    chunk("IDAT", deflateSync(raw)),
    chunk("IEND", Buffer.alloc(0))
  ]);
}

/* ────────────────────────────────────────────────────────────────────────────
 * Mounting
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * Mount the panel over one image, and hand back the page with the knobs set.
 *
 * THE IMAGE IS BUILT ONCE, IN THE PAGE, AND HELD ON `window`. `TracePanelProps.image` is watched by
 * IDENTITY — "passing a NEW `File` object for the same bytes on every render would therefore reset the
 * panel on every render", as that prop's own note says — so the harness holds one object and passes
 * the same one.
 */
async function mount(
  page: Page,
  size: { width: number; height: number },
  options: { runtimeMs?: number; traceMs?: number; name?: string } = {}
) {
  const name = options.name ?? "sheet.png";
  await page.setContent('<div id="root"></div>');
  await page.addStyleTag({ content: GEOMETRY_CSS });
  await page.addScriptTag({ content: LOADER });
  await page.evaluate(
    ([runtimeMs, traceMs]) => {
      const w = window as unknown as Record<string, unknown>;
      w.__runtimeMs = runtimeMs;
      w.__traceMs = traceMs;
      w.__runtimeLoads = 0;
      w.__disposals = 0;
      w.__runtimeFails = false;
      w.__traces = [];
      w.__attached = [];
      w.__edits = [];
      w.__editMs = 0;
      w.__editFails = false;
      w.__editorDisposals = 0;
    },
    [options.runtimeMs ?? RUNTIME_LOAD_MS, options.traceMs ?? 0]
  );

  await page.evaluate(
    ([base64, fileName]) => {
      const binary = atob(base64);
      const bytes = new Uint8Array(binary.length);
      for (let i = 0; i < binary.length; i += 1) bytes[i] = binary.charCodeAt(i);
      (window as unknown as { __image: File }).__image = new File([bytes], fileName, { type: "image/png" });
    },
    [png(size.width, size.height).toString("base64"), name]
  );

  for (const [id, file] of [
    ["react", join(NODE_MODULES, "react", "cjs", "react.production.js")],
    ["react/jsx-runtime", join(NODE_MODULES, "react", "cjs", "react-jsx-runtime.production.js")],
    ["scheduler", join(NODE_MODULES, "scheduler", "cjs", "scheduler.production.js")],
    ["react-dom", join(NODE_MODULES, "react-dom", "cjs", "react-dom.production.js")],
    ["react-dom/client", join(NODE_MODULES, "react-dom", "cjs", "react-dom-client.production.js")]
  ] as const) {
    await page.addScriptTag({ content: define(id, readFileSync(file, "utf8")) });
  }

  await page.addScriptTag({ content: LUCIDE_STUB });
  await page.addScriptTag({ content: DROPDOWN_STUB });
  await page.addScriptTag({ content: define("@/lib/trace/engine/params", compile(ENGINE_PARAMS)) });
  await page.addScriptTag({ content: define("@/lib/utils", compile(UTILS)) });
  await page.addScriptTag({ content: RUNTIME_STUB });
  await page.addScriptTag({ content: EDIT_RUNTIME_STUB });

  /*
    ORDER DOES NOT MATTER HERE — the registry is lazy, and a factory only runs on the first `require`
    — but COMPLETENESS DOES. A module a compiled file requires and this registry does not hold is a
    `require` that throws AT MOUNT, so every case in this file would fail with the harness's own
    message rather than with anything about the panel. `frameGeometry.ts` and `FramePanel.tsx` are the
    two this port added; they are listed with the rest so that nobody has to remember they are special.
  */
  for (const file of [
    "geometryToSvg.ts",
    "traceParamTable.ts",
    "decodeToPixels.ts",
    "traceExport.ts",
    "comparisonPlates.ts",
    "traceStages.ts",
    "compareTransform.ts",
    "saveToDevice.ts",
    "TraceCompare.tsx",
    "frameGeometry.ts",
    "FramePanel.tsx"
  ]) {
    // `.tsx` has to be stripped of the WHOLE extension rather than of `.ts` —
    // `"TraceCompare.tsx".replace(/\.ts$/, "")` is `"TraceCompare.tsx"`, which is a registry key
    // nothing requires and a `require` that throws at mount.
    await page.addScriptTag({
      content: define(`./${file.replace(/\.tsx?$/, "")}`, compile(join(TRACE_DIR, file)))
    });
  }
  await page.addScriptTag({ content: define("./TracePanel", compile(join(TRACE_DIR, "TracePanel.tsx"))) });

  await page.addScriptTag({
    content: `
      (function () {
        var React = window.__require("react");
        var client = window.__require("react-dom/client");
        var panel = window.__require("./TracePanel");
        client.createRoot(document.getElementById("root")).render(
          React.createElement(panel.TracePanel, {
            image: window.__image,
            imageName: ${JSON.stringify(name)},
            onAttach: function (file) { window.__attached.push(file); }
          })
        );
      })();
    `
  });

  // The trigger is the whole of the closed panel, so its arrival is the proof React mounted at all —
  // and a harness that silently rendered nothing would otherwise fail later, as a mystery.
  await expect(page.getByRole("button", { name: CARD_TITLE })).toBeVisible();
}

/** Every trace the panel asked the stub for: the size it handed over, and whether it was a preview. */
interface TraceRecord {
  readonly width: number;
  readonly height: number;
  readonly preview: boolean;
}

function traces(page: Page): Promise<TraceRecord[]> {
  return page.evaluate(() => (window as unknown as { __traces: TraceRecord[] }).__traces);
}

interface EditRecord {
  readonly width: number;
  readonly height: number;
  readonly crop: { x: number; y: number; width: number; height: number };
  readonly sharpen: { amount: number; radius: number; threshold: number };
}

function edits(page: Page): Promise<EditRecord[]> {
  return page.evaluate(() => (window as unknown as { __edits: EditRecord[] }).__edits);
}

/**
 * Open the panel and the frame chooser, and answer with the picture the frame is drawn on.
 *
 * "Choose a frame" IS ALSO THE READINESS GATE: the row it sits in appears as soon as the decode lands,
 * which is what every frame case would otherwise have to wait for by other means.
 */
async function openFrameChooser(page: Page) {
  await page.getByRole("button", { name: CARD_TITLE }).click();
  const chooseFrame = page.getByRole("button", { name: "Choose a frame" });
  await expect(chooseFrame).toBeVisible({ timeout: 15_000 });
  await chooseFrame.click();
  const picture = page.getByLabel("The photograph, with the frame that will be traced drawn over it");
  await expect(picture).toBeVisible();
  return picture;
}

/**
 * Wait until an element has stopped moving on the page.
 *
 * `page.mouse` dispatches at VIEWPORT coordinates, so a gesture is measured at one moment and
 * delivered at another — and this panel moves under itself twice after the frame chooser opens: the
 * debounced preview settles, and then the comparison plates finish building and the comparator appears
 * ABOVE the disclosure, pushing the framing preview down the page. Measured on the first run of the
 * drag case, that pushed the picture far enough that a drag computed as "past the bottom-right corner"
 * arrived as a drag UPWARDS, and the frame collapsed to the minimum edge instead of growing. The
 * failure blamed the panel for a race in the harness.
 */
async function settled(page: Page, selectorLabel: string) {
  await expect
    .poll(
      async () => {
        const first = await page.getByLabel(selectorLabel).boundingBox();
        await page.waitForTimeout(150);
        const second = await page.getByLabel(selectorLabel).boundingBox();
        if (first === null || second === null) return "gone";
        return first.x === second.x && first.y === second.y ? "stable" : "moving";
      },
      { timeout: 20_000 }
    )
    .toBe("stable");
}

/* ────────────────────────────────────────────────────────────────────────────
 * 1. The tool exists at all, which is the reduction this port is closing
 * ──────────────────────────────────────────────────────────────────────────── */

test("the panel carries a frame tool: a line on the primary path, and a chooser one press away", async ({ page }) => {
  await mount(page, { width: 200, height: 160 });
  await page.getByRole("button", { name: CARD_TITLE }).click();

  // THE ONE LINE THAT MAY NEVER BE INVISIBLE. A crop is destructive, so what the trace is framed to is
  // stated whether or not the chooser is open — the handset makes the same argument beside its own
  // copy of this row.
  await expect(page.getByText("The part of the photograph to trace")).toBeVisible({ timeout: 15_000 });
  await expect(page.getByText("The whole photograph.", { exact: false })).toBeVisible();

  const chooseFrame = page.getByRole("button", { name: "Choose a frame" });
  await expect(chooseFrame).toHaveAttribute("aria-expanded", "false");
  await chooseFrame.click();
  // THE BUTTON BECOMES "Done" — the handset's own pair of words for one control
  // (`TraceCropPanel.kt:199`), so the row says whether the chooser is open in words as well as in
  // `aria-expanded`. A control that keeps one label in both states leaves a reader to guess.
  const done = page.getByRole("button", { name: "Done", exact: true });
  await expect(done).toHaveAttribute("aria-expanded", "true");

  // The four routes the panel's header promises are all present: the presets, the four numbers, the
  // four handles and the picture a marquee is drawn on.
  for (const preset of ["The whole photograph", "Middle two-thirds", "Middle square", "Top half", "Bottom half", "Left half", "Right half"]) {
    await expect(page.getByRole("button", { name: preset, exact: true })).toBeVisible();
  }
  for (const box of ["Left", "Top", "Width", "Height"]) {
    await expect(page.getByLabel(box, { exact: true })).toBeVisible();
  }
  for (const corner of ["Top-left", "Top-right", "Bottom-left", "Bottom-right"]) {
    await expect(page.getByRole("button", { name: new RegExp(`^${corner} corner of the frame`) })).toBeVisible();
  }
  await expect(page.getByLabel("The photograph, with the frame that will be traced drawn over it")).toBeVisible();

  // …and the sharpening half, which is the other request this panel answers.
  await expect(page.getByLabel("Sharpen amount", { exact: true }).first()).toBeVisible();
});

/* ────────────────────────────────────────────────────────────────────────────
 * 2. A frame is a trace input, and only when it is committed
 * ──────────────────────────────────────────────────────────────────────────── */

test("a frame reaches the tracer only when it is committed, and the panel says so until then", async ({ page }) => {
  await mount(page, { width: 64, height: 48 });
  await openFrameChooser(page);

  // The first preview runs on the whole decode.
  await expect.poll(async () => (await traces(page)).length, { timeout: 15_000 }).toBeGreaterThan(0);
  expect((await traces(page))[0]).toMatchObject({ width: 64, height: 48 });

  /*
    `exact: true` IS LOAD-BEARING: `getByLabel` matches by substring, and the Geometry group's "Stroke
    width" slider also contains "width", so the loose form is a strict-mode violation naming two
    controls. Better to be exact than to reach for `.first()`, which would silently pick whichever the
    DOM happened to order first the next time a control moved.
  */
  const frameWidth = page.getByLabel("Width", { exact: true });
  await frameWidth.fill("32");
  await expect(
    page.getByText("Nothing has been applied yet: the trace is still using the whole photograph.")
  ).toBeVisible();
  // …and NOTHING has been traced from it yet. A crop that re-traced on every keystroke would also
  // re-sharpen on every keystroke, which is seconds of work per character.
  expect((await traces(page)).every((entry) => entry.width === 64)).toBe(true);
  expect(await edits(page)).toHaveLength(0);

  await page.getByRole("button", { name: "Use this frame for the trace" }).click();

  // The editor was asked for exactly the frame that was typed…
  await expect.poll(async () => (await edits(page)).length, { timeout: 15_000 }).toBe(1);
  expect((await edits(page))[0].crop).toMatchObject({ x: 0, y: 0, width: 32, height: 48 });
  // …from the WHOLE decode, never from a previous crop. Widening the frame back out has to stay
  // possible, which it is not once the panel has replaced its own source with a cropped one.
  expect((await edits(page))[0]).toMatchObject({ width: 64, height: 48 });

  // …and the tracer then received it, at that size, without anybody pressing anything else.
  await expect.poll(async () => (await traces(page)).at(-1)?.width, { timeout: 15_000 }).toBe(32);
  await expect(page.getByText(/The trace is using 32x48/)).toBeVisible();
  // The primary-path line agrees, in the handset's typography.
  await expect(page.getByText("32×48 of 64×48.")).toBeVisible();

  // Moving the frame again makes the panel say plainly that what is on screen is no longer what is
  // being traced — the alternative is a control that has silently stopped mattering.
  await frameWidth.fill("20");
  await expect(page.getByText(/The frame on screen is not the one being traced/)).toBeVisible();
});

/* ────────────────────────────────────────────────────────────────────────────
 * 3. The gesture that was broken, through the route that broke it
 * ──────────────────────────────────────────────────────────────────────────── */

/*
  THE DEFECT THIS SECTION EXISTS FOR IS UNREACHABLE FROM A UNIT SPEC. `moveCropCorner`'s arithmetic is
  pinned in `e2e/trace-frame-geometry-unit.spec.ts`; what is pinned HERE is that the handle is WIRED to
  it — that a pointer drag on a real button, with real pointer capture, real percentage positions and a
  real measured element, produces the same answer. The shipped version of this tool had correct
  arithmetic available to it and a drag that reset the frame to the whole photograph anyway, because
  the wiring divided by a laid-out scale and clamped the finished rectangle.
*/

test("a pointer drag on a corner handle moves its own two edges and nothing else", async ({ page }) => {
  await mount(page, { width: 200, height: 160 });
  const picture = await openFrameChooser(page);

  // Start from a frame that is NOT the whole photograph, because that is the state in which a corner
  // drag has somewhere to go and an anchored corner worth watching.
  await page.getByLabel("Width", { exact: true }).fill("60");
  await page.getByLabel("Height", { exact: true }).fill("50");
  await page.getByLabel("Left", { exact: true }).fill("40");
  await page.getByLabel("Top", { exact: true }).fill("30");
  await expect(page.getByText("60x50 of 200x160", { exact: false })).toBeVisible();

  /*
    SCROLLED INTO THE MIDDLE FIRST, AND THIS IS NOT FUSSINESS. `page.mouse` dispatches at VIEWPORT
    coordinates, and this panel is tall: measured on the first run of this case, the framing preview
    sat at y = -417 with the handle clipped to 6px of its 16, so every synthesised event landed outside
    the viewport and the drag did nothing — a green-looking failure that says nothing about the panel.
    `block: "center"` also leaves room BELOW the picture for the gesture to end in.
  */
  await settled(page, "The photograph, with the frame that will be traced drawn over it");
  await picture.evaluate((element) => element.scrollIntoView({ block: "center" }));

  const box = await picture.boundingBox();
  expect(box, "the framing preview has no layout").not.toBeNull();
  const frame = box as { x: number; y: number; width: number; height: number };

  const handle = page.getByRole("button", { name: /^Bottom-right corner of the frame/ });
  const grip = await handle.boundingBox();
  expect(grip).not.toBeNull();
  const from = grip as { x: number; y: number; width: number; height: number };

  // The gesture has to END inside the viewport, or the events are dispatched at coordinates the page
  // never sees. 120 drawn pixels past the corner is more than enough: the picture is drawn at its own
  // size here, so one drawn pixel is one photograph pixel and the corner is pushed well past 200.
  const viewport = page.viewportSize();
  expect(viewport).not.toBeNull();
  const toX = frame.x + frame.width + 120;
  const toY = frame.y + frame.height + 120;
  expect(toX, "the drag would end outside the viewport").toBeLessThan((viewport as { width: number }).width);
  expect(toY, "the drag would end outside the viewport").toBeLessThan((viewport as { height: number }).height);

  // Pulled WELL past the bottom-right corner of the picture — the commonest gesture this tool has, and
  // the one that used to answer "the whole photograph".
  await page.mouse.move(from.x + from.width / 2, from.y + from.height / 2);
  await page.mouse.down();
  await page.mouse.move(toX, toY, { steps: 8 });
  await page.mouse.up();

  // The two edges the handle owns went to the frame; the two it does not own did not move at all.
  await expect(page.getByLabel("Left", { exact: true })).toHaveValue("40");
  await expect(page.getByLabel("Top", { exact: true })).toHaveValue("30");
  await expect(page.getByLabel("Width", { exact: true })).toHaveValue("160");
  await expect(page.getByLabel("Height", { exact: true })).toHaveValue("130");
  // And the readout is not "the whole photograph", which is what the defect produced.
  await expect(page.getByText("160x130 of 200x160", { exact: false })).toBeVisible();
});

test("the corner handles are tab stops, and one arrow press moves a drawn pixel", async ({ page }) => {
  await mount(page, { width: 200, height: 160 });
  await openFrameChooser(page);

  await page.getByLabel("Width", { exact: true }).fill("60");
  await page.getByLabel("Height", { exact: true }).fill("50");

  /*
    THE KEYBOARD ROUTE, WHICH THE PANEL'S HEADER CALLS PRIMARY. It read as dead because the step was
    one PHOTOGRAPH pixel: on a large sheet drawn small that is a fraction of a drawn pixel, so thirty
    presses showed nothing. The step is derived from the magnification now, and the handle's own
    accessible name states it — a screen-reader user has no other way to know how far a press went.
  */
  const handle = page.getByRole("button", { name: /^Bottom-right corner of the frame/ });
  await expect(handle).toHaveAttribute(
    "aria-label",
    /Arrow keys move it by \d+ pixels? of the photograph; hold Shift for \d+\./
  );

  await handle.focus();
  await expect(handle).toBeFocused();
  const before = Number(await page.getByLabel("Width", { exact: true }).inputValue());
  await handle.press("ArrowRight");
  const afterOne = Number(await page.getByLabel("Width", { exact: true }).inputValue());
  expect(afterOne, "an arrow press did nothing").toBeGreaterThan(before);

  // Shift is ten of them, which is the other half of the promise the label makes.
  await handle.press("Shift+ArrowRight");
  const afterShift = Number(await page.getByLabel("Width", { exact: true }).inputValue());
  expect(afterShift - afterOne).toBe((afterOne - before) * 10);
});

/* ────────────────────────────────────────────────────────────────────────────
 * 4. Typing, which is the route that works with no pointer at all
 * ──────────────────────────────────────────────────────────────────────────── */

test("a half-typed number survives being typed, and a clamp that moves one says so", async ({ page }) => {
  // 300 wide, so "150" passes through a first character far below the 16px minimum edge.
  await mount(page, { width: 300, height: 200 });
  await openFrameChooser(page);

  const width = page.getByLabel("Width", { exact: true });
  const left = page.getByLabel("Left", { exact: true });

  /*
    TYPED CHARACTER BY CHARACTER, WHICH IS THE WHOLE POINT. `fill` sets the value in one shot and would
    pass against the old code: 150 is legal, so one clamp of the finished number is the number. The
    defect lived in the intermediate states — "1" became 16, and the "5" that followed landed on it, so
    typing 150 gave 1516.
  */
  await width.click();
  await width.press("Control+a");
  await width.pressSequentially("150", { delay: 20 });
  await expect(width).toHaveValue("150");
  // …and the frame really is 150 wide: this sentence is drawn from the committed rectangle itself.
  await expect(page.getByText("150x200 of 300x200", { exact: false })).toBeVisible();

  /*
    LEFT, WHILE THE FRAME IS STILL THE FULL WIDTH — the box that could not be typed into at all. `x` is
    clamped to `min(width - crop.width, …)`, which is 0 for as long as the crop is whole, so every
    keystroke was discarded and nothing on screen said why.
  */
  await page.getByRole("button", { name: "Use the whole photograph" }).click();
  await left.click();
  await left.press("Control+a");
  await left.pressSequentially("40", { delay: 20 });
  // The characters are still there while the box has focus…
  await expect(left).toHaveValue("40");
  await left.press("Enter");
  // …and committing is when the impossible one is refused — with the reason, and with the way out.
  await expect(left).toHaveValue("0");
  await expect(page.getByText("Left cannot be 40", { exact: false })).toBeVisible();
  await expect(page.getByText("Reduce Width first", { exact: false })).toBeVisible();

  // With the frame narrowed the same 40 is legal and is taken, so the sentence above was true.
  await width.click();
  await width.press("Control+a");
  await width.pressSequentially("200", { delay: 20 });
  await left.click();
  await left.press("Control+a");
  await left.pressSequentially("40", { delay: 20 });
  await left.press("Enter");
  await expect(left).toHaveValue("40");
  await expect(page.getByText("Left cannot be 40", { exact: false })).toHaveCount(0);
  await expect(page.getByText("200x200 of 300x200", { exact: false })).toBeVisible();

  // And the frame that reaches the editor is the one the boxes say it is.
  await page.getByRole("button", { name: "Use this frame for the trace" }).click();
  await expect.poll(async () => (await edits(page)).length, { timeout: 15_000 }).toBe(1);
  expect((await edits(page))[0].crop).toMatchObject({ x: 40, y: 0, width: 200, height: 200 });
});

/* ────────────────────────────────────────────────────────────────────────────
 * 5. "Choose a frame" — the preset row
 * ──────────────────────────────────────────────────────────────────────────── */

test("a preset writes the frame in the photograph's own pixels, and the row says which is showing", async ({
  page
}) => {
  await mount(page, { width: 300, height: 200 });
  await openFrameChooser(page);

  const topHalf = page.getByRole("button", { name: "Top half", exact: true });
  await expect(topHalf).toHaveAttribute("aria-pressed", "false");
  await topHalf.click();

  // THE PRESET'S PIXELS ARE THE PHOTOGRAPH'S, not the preview's — a preset that wrote in the preview's
  // scale would disagree with the boxes and with the drag on every image big enough to be reduced.
  await expect(page.getByLabel("Width", { exact: true })).toHaveValue("300");
  await expect(page.getByLabel("Height", { exact: true })).toHaveValue("100");
  await expect(topHalf).toHaveAttribute("aria-pressed", "true");
  // The chosen row's sentence is under the control, because a `title` needs a pointer that hovers.
  await expect(page.getByText("For two drawings on one sheet, or a sheet with notes below it.")).toBeVisible();

  // A frame that is then nudged is honestly none of them, and the row stops claiming otherwise.
  await page.getByLabel("Height", { exact: true }).fill("99");
  await expect(topHalf).toHaveAttribute("aria-pressed", "false");
  await expect(page.getByText("A frame of your own. Pick a shape above to start from a common one.")).toBeVisible();
});

/* ────────────────────────────────────────────────────────────────────────────
 * 6. Declining costs one press, inside the crop as well as outside it
 * ──────────────────────────────────────────────────────────────────────────── */

test("'Use the whole photograph' is one press and puts the trace back on the whole decode", async ({ page }) => {
  await mount(page, { width: 120, height: 90 });
  await openFrameChooser(page);

  await page.getByLabel("Width", { exact: true }).fill("60");
  await page.getByRole("button", { name: "Use this frame for the trace" }).click();
  await expect(page.getByText(/The trace is using 60x90/)).toBeVisible({ timeout: 15_000 });
  await expect.poll(async () => (await traces(page)).at(-1)?.width, { timeout: 15_000 }).toBe(60);

  /*
    ONE PRESS, NO DIALOG, NO EXPLANATION ASKED FOR — `TracePanel`'s fourth property arriving inside the
    crop tool. The frame goes back to the whole photograph, the trace re-runs on the whole decode, and
    the primary-path line says so again.
  */
  await page.getByRole("button", { name: "Use the whole photograph" }).click();
  await expect.poll(async () => (await traces(page)).at(-1)?.width, { timeout: 15_000 }).toBe(120);
  await expect(page.getByText("The whole photograph.", { exact: false }).first()).toBeVisible();
});

/* ────────────────────────────────────────────────────────────────────────────
 * 7. The provenance sentence that actually reaches the file
 * ──────────────────────────────────────────────────────────────────────────── */

test("the frame's provenance sentence reaches the attached SVG from the editor that made the pixels", async ({
  page
}) => {
  /*
    `lib/trace/imageEdit.describeEdit` is on the far side of the bundle boundary, which is the only side
    that can call it — so the sentence has to be CARRIED up from the worker rather than rebuilt in the
    component. A panel that rebuilt it would write something plausible instead, and the stub's
    deliberately different wording is the only assertion that can tell the two apart.
  */
  await mount(page, { width: 64, height: 48 });
  await openFrameChooser(page);

  await page.getByLabel("Width", { exact: true }).fill("32");
  await page.getByRole("button", { name: "Use this frame for the trace" }).click();
  await expect(page.getByText(/The trace is using 32x48/)).toBeVisible({ timeout: 15_000 });

  await page.getByRole("button", { name: "Add the line art" }).click();
  await expect
    .poll(async () => page.evaluate(() => (window as unknown as { __attached: File[] }).__attached.length), {
      timeout: 15_000
    })
    .toBe(1);

  const svg = await page.evaluate(async () => await (window as unknown as { __attached: File[] }).__attached[0].text());
  expect(svg).toContain("[from the editor]");
  expect(svg).toContain("Cropped on the device to 32x48 at (0, 0) of 64x48.");
  // …carried inside the ordinary provenance note rather than instead of it.
  expect(svg).toContain("Traced on the device from sheet.png");

  // AND NOTHING ELSE LEFT THE PANEL. One file, ever — the crop produces no second one, which is the
  // property the whole tool is allowed to exist under.
  expect(await page.evaluate(() => (window as unknown as { __attached: File[] }).__attached.length)).toBe(1);
});
