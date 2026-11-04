export function spawnTraceWorker(): Worker {
  return new Worker(new URL("./worker/trace.worker.ts", import.meta.url), { type: "module" });
}
