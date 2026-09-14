/**
 * The second — and only other — expression in this repository that constructs a Web Worker.
 *
 * It is a copy of `spawnTraceWorker.ts` down to the shape, and that file's header is the reason this
 * one exists as a file rather than as a line inside `imageEditClient.ts`. Both halves of its argument
 * apply here unchanged:
 *
 *  1. `import.meta.url` is legal only in an ES module, so a module containing this expression cannot
 *     be loaded by a Playwright spec transpiled to CommonJS — the failure is `ReferenceError: exports
 *     is not defined` on the file's first line. Keeping it in a leaf behind an `await import()` leaves
 *     `imageEditClient.ts` and `imageEdit.ts` plain TypeScript, which is what lets
 *     `e2e/sketch-frame-sharpen-unit.spec.ts` drive the arithmetic on a laptop with no browser.
 *
 *  2. `new Worker(new URL(...))` is what makes the bundler treat the worker file as a build entry of
 *     its own and emit the engine's convolution code as a separate chunk. Reaching this module only
 *     through a dynamic import keeps even the *reference* to that chunk off the page graph until a
 *     designer sharpens something.
 *
 * **DO NOT REWRITE THE EXPRESSION.** Turbopack matches `new Worker(new URL("…", import.meta.url))`
 * syntactically. Hoisting the URL into a variable, concatenating the specifier, or wrapping the
 * construction in a helper that takes a path leaves the bundler nothing to analyse: the worker
 * silently stops being emitted and the app 404s at run time on a file the build never made. There is
 * no build error for this.
 *
 * WHY THIS IS NOT THE TRACE WORKER WITH ONE MORE MESSAGE TYPE, which is the obvious saving. The whole
 * value of `lib/trace/worker/` and `lib/trace/engine/` is that `diff -r` against `D:/Offline-Tracer`
 * stays clean — `UPSTREAM-MANIFEST.txt` is the record of it. Adding a message to
 * `worker/protocol.ts` or a branch to `worker/trace.worker.ts` spends that, permanently, for one
 * feature. A sibling worker of our own costs a second chunk and keeps the vendored 46 files
 * byte-identical.
 */

/** @returns a live module worker that crops and sharpens. Throws if the browser refuses. */
export function spawnImageEditWorker(): Worker {
  return new Worker(new URL("./imageEdit.worker.ts", import.meta.url), { type: "module" });
}
