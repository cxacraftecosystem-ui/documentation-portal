# `lib/trace` — the vendored line-art and vectorisation engine

`engine/` and `worker/` are **not written for this repository**. They are a verbatim copy of another
codebase, taken once, and everything in this file exists so that the next person can tell what they
are looking at, update them safely, and know exactly which of the upstream's promises still hold
here and which do not.

---

## 1. Provenance

| | |
| --- | --- |
| **Origin** | `D:/Offline-Tracer` — `web/src/engine/` and `web/src/worker/` |
| **Taken on** | 2026-08-22 |
| **Upstream state** | **No commit hash exists.** `D:/Offline-Tracer` is not a git repository — `git log` there answers `fatal: not a git repository`. The newest source file at the time of the copy was `engine/pipeline.ts`, modified 2026-08-04 04:25; the oldest were written 2026-08-03. |
| **Identity of the copy** | `UPSTREAM-MANIFEST.txt` beside this file: a SHA-256 per file. It is the only thing that can answer "has this drifted?", precisely because there is no revision to compare against. |
| **Permission** | The owner of this portal authored `D:/Offline-Tracer` and has confirmed in writing that it may be reused here. |
| **What was taken** | 43 files from `engine/` (16,557 lines, 613,139 bytes) and 3 from `worker/` (650 lines, 28,632 bytes). Nothing else — no UI, no state, no styles, no build config. |
| **Changes made to them** | **None.** `diff -r` against the source tree was clean for both directories at the moment of the copy. |

### The prose inside the vendored files points at the upstream, not at this repository

Verbatim means their comments came too, and those comments cite documents and files that **do not
exist here** — all nine of them in `worker/`, none in `engine/` (checked with
`grep -rn "APP-CONTRACT\|DESIGN\.md\|traceController\|tsconfig.app" .`): `docs/APP-CONTRACT.md`
§1.2/§2.1/§5/§5.1 (`protocol.ts:2,78`, `trace.worker.ts:8,19,311`), `DESIGN.md` §9
(`trace.worker.ts:116`), and the upstream's own UI-side `state/traceController.ts` and
`tsconfig.app.json` (`protocol.ts:4,33`, `trace.worker.ts:51`). None of them was copied and none of
them will be: the contract and design documents describe the upstream's *product*, and
`traceController.ts` is the file `traceClient.ts` replaces.

They were **left unedited on purpose** — an edited comment is a diff against the upstream, and §1's
whole argument is that `diff -r` must stay meaningful. But this repository's house rule is "read the
file header before changing a file", so the next reader has to be told: a header in `engine/` or
`worker/` that sends you to a document is sending you to the other repository. The two files that are
ours (`traceClient.ts`, `spawnTraceWorker.ts`) cite only things that exist here.

### Why the file names and the directory shape were kept

`engine/index.ts` imports `./buffers`, `worker/trace.worker.ts` imports `../engine`. Renaming a file
or flattening the two directories into one would rewrite those specifiers and make a future
`diff -r D:/Offline-Tracer/web/src/engine engine` meaningless — which would mean that the day the
upstream fixes a bug in its curve fitter, nobody here could tell which of the 43 files to take.
The structure is inconvenient in exactly one place (this repository's other `lib/` modules are flat
files) and that inconvenience is the price of being able to update.

### How to check for drift, or take a newer copy

```bash
cd D:/Offline-Tracer/web/src && find engine worker -type f | sort | xargs sha256sum | tr -d '*'
```

Compare that against `UPSTREAM-MANIFEST.txt`. Any line that differs is a file the upstream has moved
on from — or one this copy has edited, which nothing should have. After taking a newer copy,
regenerate the manifest, then run `npx playwright test trace-engine-unit --reporter=line`: that spec
drives the pipeline on bitmaps whose right answer is arithmetic, so it fails on a copy that
typechecks and no longer computes.

### Why the copy is safe to make at all

Verified before copying, not assumed:

```bash
cd D:/Offline-Tracer/web && grep -rnE "from\s+['\"][^.][^'\"]*['\"]" src/engine   # (none)
grep -rn "import(" src/engine ; grep -rn "require(" src/engine                    # (none)
```

Every specifier in `engine/` is relative. It depends on no package, so vendoring it added **zero**
dependencies to `frontend/package.json` — that file is untouched by this work. `worker/` imports
only `../engine` and its own two siblings.

### What this does to the repository's own volume figures

`docs/tools/check-docs.mjs` walks `frontend/lib` for `.ts`/`.tsx` and publishes the totals as the
`frontend/lib` row of the Code volume table in `docs/REPO_FACTS.md`. This directory is **48 files and
17,792 lines** by that walk's own counting method (measured 2026-08-22 by running its `walk` over
`lib/trace`), and 46 of those files are not this portal's code. Once they are committed the row's
`frontend/lib` figure roughly grows by half, with nothing in the table marking two-thirds of it as
vendored.

So: **when anyone quotes `frontend/lib` as the size of this portal's frontend library, subtract this
directory.** The clean fix is a labelled row of its own in `volumeFacts()`; that generator is shared,
was already being edited by another workstream during this wave, and is not this unit's file, so it
is left as a decision for wave integration rather than changed here.

---

## 2. What deliberately did **not** come with it, and the guarantee that is weaker here

The upstream is an **offline** application, and it does not merely claim that — it makes the
platform enforce it. Two mechanisms do that, and **neither one was copied**:

- `web/index.html` carries a `Content-Security-Policy` whose comment says why:

  > "This app performs no network requests after load, by design. The CSP is the enforcement of
  > that claim, and it is the browser equivalent of the Android build omitting INTERNET
  > permission: `connect-src 'none'` means a fetch/XHR/WebSocket cannot leave, even accidentally,
  > even from a dependency."

- `android/app/src/main/AndroidManifest.xml` holds no `INTERNET` permission, for the same stated
  reason:

  > "the guarantee 'everything stays on this device' is only worth making if the platform enforces
  > it, and a missing INTERNET permission is enforcement."

**Neither can hold in this portal, and no attempt was made to make them hold.** Every authenticated
page here calls the API (`lib/api.ts` → `${NEXT_PUBLIC_API_URL}/api/...`), uploads media to signed S3
URLs and loads map tiles. `connect-src 'none'` on such a page is not a hardened build, it is a broken
one. `next.config.ts` sets `frame-ancestors 'none'` and nothing else in the CSP family, and this work
did not change that.

**So write it down plainly: the guarantee is weaker here.**

| | Upstream | This portal |
| --- | --- | --- |
| Does the *engine* make network calls? | No | No — same code, and the spec that drives it runs in Node with no server at all |
| Is that *enforced* by the platform? | Yes, `connect-src 'none'` | **No.** The page around it must be allowed to reach the API |
| What could send a traced image somewhere? | Nothing — the browser would refuse | Any other code on the same page |

The honest claim this portal can make is **"the tracing is computed on the device"** — which is true,
and is the claim that matters for a designer four days from a connection. The claim it must **not**
make is "the image cannot leave the device", because nothing here stops that. Anyone tempted to put
the stronger sentence in front of a user should read this paragraph first.

Also not copied: the upstream's React UI (`src/ui`, `src/state`, `src/App.tsx`), its IndexedDB
session store, its export-to-file plumbing and its Vite config. Those are its product, not its
engine.

---

## 3. The shape of what is here

```
lib/trace/
  engine/               43 files, verbatim. Pure arithmetic over typed arrays. No DOM, no network.
  worker/               3 files, verbatim. The Web Worker host and its message protocol.
  traceClient.ts        OURS. The only door: starts the worker, speaks the protocol, returns a promise.
  spawnTraceWorker.ts   OURS. The one `new Worker(new URL(...))` expression, in a file of its own.
  UPSTREAM-MANIFEST.txt SHA-256 per vendored file.
  README.md             this file.
```

**Import `traceClient.ts` and nothing else.** Outside this directory, `engine/` and `worker/` are
named in exactly one file: `e2e/trace-engine-unit.spec.ts`. Inside it they are named by
`spawnTraceWorker.ts` (the worker's path), and by `traceClient.ts` — which takes `import type` from
both, plus one `await import("./engine/params")`, and no other engine value at all. The upstream's
worker header calls itself "the only place the engine is imported outside tests"; keeping that true
is what stops the engine from being pulled onto the page thread by a component that only wanted one
function out of it.

The two files that are ours carry their own headers explaining the decisions inside them. In
particular, `spawnTraceWorker.ts` exists as a separate 37-line file for a reason that is not
stylistic and is written out there in full.

### The worker is not an optimisation

There is no main-thread path into the engine, on purpose. `Pipeline.run` is straight loops over typed
arrays; a 12 MP trace is seconds of solid CPU, and on the page thread that is a frozen tab. A browser
that will not start a module worker gets a `TraceUnavailableError` carrying a sentence to show. Do not
add a fallback that runs the pipeline on the page "just for small images" — every image is small until
somebody photographs a sheet of paper with a modern phone.

---

## 4. Weight: the rule, and the measured numbers

`.claude/skills/gsap/SKILL.md` §2 is titled **"The dynamic import is not optional"** and states the
rule this repository already enforces on its one heavy library:

> "GSAP is ~70 KB. Only this one component needs it, so it must not sit in the bundle every protected
> page loads … A top-level `import { gsap } from "gsap"` ships it to every route that transitively
> imports the component. Keep the value import inside the effect and the namespace import type-only."

`components/guide/useGsapHeadline.ts` obeys it in the same words. For scale: `node_modules/gsap/dist/
gsap.min.js` is 72,927 bytes raw and **28,268 gzipped**. The trace engine is bigger, so the rule
applies with more force, and it is obeyed three times over — see `traceClient.ts`'s header.

### What a production Turbopack build actually emits

Measured on 2026-08-22, Next.js **16.2.9 (Turbopack)**, `NODE_ENV=production npx next build`, against
an isolated probe app that used this repository's own `next.config.ts`, `tsconfig.json` and
`node_modules` — isolated only so the build could not be broken by, or break, four other agents
working in the tree at the time. Gzip figures are `gzip -9`.

| Emitted chunk | raw | gzip | what is in it |
| --- | ---: | ---: | --- |
| the worker's engine chunk | 109,923 | 39,526 | `engine/` + the worker host |
| the worker's Turbopack runtime | 10,514 | 4,152 | emitted by the bundler, not by us |
| the worker bootstrap | 818 | 575 | refuses to run outside a worker, then `importScripts` the list |
| `engine/params` | 8,282 | 3,199 | shared by the worker and by `loadTraceParams()` |
| `traceClient.ts` | 3,633 | 1,513 | |
| `spawnTraceWorker.ts` | 496 | 358 | |
| **total** | **133,666** | **49,323** | |

Split by where it runs: **5,070 gzipped on the main thread** (client + spawn + params) and **44,253
gzipped inside the worker**. The main-thread half is a fifth of GSAP's 28,268; the heavy half never
touches the page thread's parse budget at all.

Chunk file names are content-hashed and change every build, so they are not reproduced here — the
sizes are what to compare against.

### And the proof it stays off a page that does not use it

The probe app had two pages: one that reaches the tracer through `await import()`, and one that
imports nothing from `lib/trace`. Driving the built app in a real Chromium:

- The tracer page's **initial** script set was six chunks. **None of them was any of the six above.**
- Clicking the button fetched, in this order: `traceClient`, `engine/params`, `spawnTraceWorker`, the
  worker bootstrap, the worker runtime, the engine chunk.
- Chromium reported a real `Worker` whose URL was the emitted bootstrap, and the page rendered the
  worker's answer.

**What that last line does and does not prove.** The answer the page rendered was "0 shapes" — the
probe traced a blank buffer. So the built app proves the chunk graph: the worker starts, the engine
parses inside it, the protocol round-trips and a serialized result comes back. It does **not** prove
the arithmetic under Turbopack's emit, and "0 shapes" is exactly what an engine broken by a different
transpiler would also say. The arithmetic is proved only under Playwright's transpiler, by the three
pipeline cases in `e2e/trace-engine-unit.spec.ts` — whose own header names this failure mode: "a
golden captured from the engine's own output would go green against an engine that had started
returning a constant". The gap is not academic: this repository compiles with `target: ES2017` and
`useDefineForClassFields` unset, and the upstream built under its own `tsconfig.app.json`.

Closing it is one job for the UI wave, which has to run `next build` on the real app anyway: trace a
drawn disc through the built worker and assert a **non-zero** shape count on a bounding box, the way
the spec's first case does. Until then, do not quote the built-app run as evidence that the emitted
engine computes correctly.

So the engine is fetched when somebody traces something, and not before. **The later UI wave must
keep it that way**: reach `traceClient` through `await import("@/lib/trace/traceClient")`, never with
a top-level import in a stage component.

### Do not rewrite the worker construction

Turbopack matches `new Worker(new URL("…", import.meta.url), { type: "module" })` **syntactically**.
Hoist the URL into a variable, build the specifier by concatenation, or wrap the construction in a
helper that takes a path argument, and the bundler has nothing left to analyse: the worker silently
stops being emitted and the app 404s at run time on a file the build never made. There is no build
error for this. That is why the expression is written out longhand in `spawnTraceWorker.ts` and why
its header says so too.

---

## 5. The test

`e2e/trace-engine-unit.spec.ts` — seven cases, no browser, no server, no API. It runs under
`npm run test:unit`.

It drives the real pipeline on bitmaps it draws itself and asserts properties that follow from the
drawing rather than from a captured golden: a filled disc traces to exactly one closed outline whose
bounding box is the disc's bounding box; a preview at a quarter scale still answers in source
coordinates; a blank sheet produces no paths and says so. The last three cases drive `traceClient.ts`
with a stand-in worker that runs Node's own `structuredClone` with the real transfer list, so the
pixel buffer is genuinely detached and the transfer contract is exercised rather than described. The
last of them covers supersession, which is the one place the client has to do work the worker will
not: `worker/trace.worker.ts` cancels a running trace when a second arrives and then posts nothing at
all for it, so `Tracer.trace` settles the older promise itself. Removing that and re-running the case
times the test out rather than merely failing an assertion — which is what the leak looked like.

That spec can run in Node only because `engine/index.ts`'s claim — "Nothing in this tree touches the
DOM … so the whole engine runs unchanged inside a Web Worker" — is true. If somebody ever reaches for
`document` inside `engine/`, this spec stops running, which is the point.

---

## 6. What this wave did not build

No page, no tab, no component, no registry field, no upload path. Nothing in `frontend/` imports
`lib/trace` except its own spec. Deciding where a traced result is stored is a later decision, and it
has a natural home already: `lib/sketchRectify.ts`'s header records that stage 11's
`sketch.lineArtFile` is declared as "An SVG or vector export", and notes that clean black line art on
white "is exactly the input such a thing wants" — while carefully not enabling it. This directory is
the thing that connection was waiting for; it is still not connected.
