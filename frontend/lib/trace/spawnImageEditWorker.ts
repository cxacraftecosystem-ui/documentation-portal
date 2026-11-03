export function spawnImageEditWorker(): Worker {
  return new Worker(new URL("./imageEdit.worker.ts", import.meta.url), { type: "module" });
}
