/**
 * The one expression in this repository that constructs a Web Worker.
 *
 * It is five lines and it lives in a file of its own for two reasons, both of them practical.
 *
 * FIRST: `import.meta.url` DECIDES HOW A FILE IS COMPILED, AND IT INFECTS THE WHOLE MODULE.
 *
 * `import.meta` is legal only in an ES module. Playwright transpiles a spec and everything it
 * imports to CommonJS, where the syntax does not exist — so a module containing this expression
 * cannot be loaded by a Node spec at all, and the failure is the unhelpful `ReferenceError: exports
 * is not defined` pointing at the file's first line. Measured, not assumed: that is exactly what
 * `traceClient.ts` did on 2026-08-22 while this expression still sat inside it.
 *
 * Keeping it here, behind the `await import()` in `traceClient.ts`, leaves that module plain
 * TypeScript — so `e2e/trace-engine-unit.spec.ts` can drive the whole client with a fake worker, on
 * a laptop, with no browser and no server, which is what the `-unit` suffix promises (see
 * `e2e/README.md`).
 *
 * SECOND: IT IS THE WEIGHT BOUNDARY MADE VISIBLE.
 *
 * `new Worker(new URL(...))` is what makes the bundler treat `worker/trace.worker.ts` as a build
 * entry point of its own and emit the engine as a separate chunk. Putting the expression in a leaf
 * module that only ever arrives through a dynamic import means even the *reference* to that chunk is
 * off the page graph until somebody traces something.
 *
 * DO NOT REWRITE THE EXPRESSION. Turbopack matches `new Worker(new URL("…", import.meta.url))`
 * syntactically. Hoist the URL into a variable, build the specifier by concatenation, or wrap the
 * construction in a helper that takes the path as an argument, and the bundler has nothing left to
 * analyse: the worker silently stops being emitted and the app 404s at run time on a file the build
 * never made. There is no build error for this — which is why it is written out longhand here rather
 * than parameterised.
 */

/** @returns a live module worker running the vendored trace engine. Throws if the browser refuses. */
export function spawnTraceWorker(): Worker {
  return new Worker(new URL("./worker/trace.worker.ts", import.meta.url), { type: "module" });
}
