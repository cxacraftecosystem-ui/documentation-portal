import { PauseGate } from "@/lib/dataExplorer/engine";
import type { FromWorker, ToWorker } from "@/lib/dataExplorer/protocol";
import { measureJob, runJob } from "@/lib/dataExplorer/runJob";

const scope = self as unknown as {
  postMessage(message: FromWorker): void;
  addEventListener(type: "message", listener: (event: MessageEvent<ToWorker>) => void): void;
};

const running = new Map<string, AbortController>();
const gates = new Map<string, PauseGate>();

scope.addEventListener("message", (event) => {
  const message = event.data;
  if (message.type === "cancel") {
    running.get(message.id)?.abort();
    gates.get(message.id)?.resume();
    return;
  }
  if (message.type === "pause" || message.type === "resume") {
    const gate = gates.get(message.id);
    if (!gate) return;
    if (message.type === "pause") gate.pause();
    else gate.resume();
    scope.postMessage({ type: "paused", id: message.id, paused: gate.paused });
    return;
  }
  const controller = new AbortController();
  running.set(message.id, controller);
  const gate = new PauseGate();
  if (message.type === "start") gates.set(message.id, gate);
  const work =
    message.type === "measure"
      ? measureJob(message, controller.signal, (out) => scope.postMessage(out))
      : runJob(message as Extract<ToWorker, { type: "start" }>, controller.signal, (out) => scope.postMessage(out), undefined, undefined, gate);
  void work.finally(() => {
    running.delete(message.id);
    gates.delete(message.id);
  });
});
