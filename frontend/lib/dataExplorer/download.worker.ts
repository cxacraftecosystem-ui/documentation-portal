import type { FromWorker, ToWorker } from "@/lib/dataExplorer/protocol";
import { measureJob, runJob } from "@/lib/dataExplorer/runJob";

const scope = self as unknown as {
  postMessage(message: FromWorker): void;
  addEventListener(type: "message", listener: (event: MessageEvent<ToWorker>) => void): void;
};

const running = new Map<string, AbortController>();

scope.addEventListener("message", (event) => {
  const message = event.data;
  if (message.type === "cancel") {
    running.get(message.id)?.abort();
    return;
  }
  const controller = new AbortController();
  running.set(message.id, controller);
  const work = message.type === "measure" ? measureJob(message, controller.signal, (out) => scope.postMessage(out)) : runJob(message, controller.signal, (out) => scope.postMessage(out));
  void work.finally(() => running.delete(message.id));
});
