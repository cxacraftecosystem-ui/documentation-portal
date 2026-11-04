export const STREAM_WORKER_URL = "/download-stream-sw.js";
export const STREAM_SCOPE = "/download-stream/";
const READY_MS = 4000;

type Registration = { active: ServiceWorker | null; installing: ServiceWorker | null; waiting: ServiceWorker | null };

async function activeWorker(): Promise<ServiceWorker | null> {
  if (typeof navigator === "undefined" || !("serviceWorker" in navigator)) return null;
  const registration = (await navigator.serviceWorker.register(STREAM_WORKER_URL, { scope: STREAM_SCOPE })) as unknown as Registration;
  if (registration.active) return registration.active;
  const pending = registration.installing ?? registration.waiting;
  if (!pending) return null;
  return new Promise<ServiceWorker | null>((resolve) => {
    const settle = () => {
      if (pending.state === "activated") resolve(pending);
      else if (pending.state === "redundant") resolve(null);
    };
    pending.addEventListener("statechange", settle);
    settle();
  });
}

function ping(worker: ServiceWorker): Promise<boolean> {
  return new Promise<boolean>((resolve) => {
    const channel = new MessageChannel();
    const timer = setTimeout(() => resolve(false), READY_MS);
    channel.port1.onmessage = (event) => {
      if ((event.data as { type?: string } | null)?.type === "pong") {
        clearTimeout(timer);
        channel.port1.onmessage = null;
        resolve(true);
      }
    };
    worker.postMessage({ type: "ping" }, [channel.port2]);
  });
}

export function streamHandoffSupported(): boolean {
  if (typeof window === "undefined" || typeof navigator === "undefined") return false;
  if (!("serviceWorker" in navigator) || !window.isSecureContext) return false;
  return /Firefox\//i.test(navigator.userAgent);
}

export async function openStreamHandoff(name: string, size: number | null): Promise<MessagePort | null> {
  try {
    const worker = await activeWorker();
    if (!worker) return null;
    if (!(await ping(worker))) return null;
    const channel = new MessageChannel();
    const id = `${Date.now().toString(36)}${Math.random().toString(36).slice(2)}`;
    const registered = new Promise<boolean>((resolve) => {
      const timer = setTimeout(() => resolve(false), READY_MS);
      channel.port1.onmessage = (event) => {
        if ((event.data as { type?: string } | null)?.type === "registered") {
          clearTimeout(timer);
          channel.port1.onmessage = null;
          resolve(true);
        }
      };
    });
    worker.postMessage({ type: "register", id, name, size: size ?? 0 }, [channel.port2]);
    if (!(await registered)) return null;
    const frame = document.createElement("iframe");
    frame.hidden = true;
    frame.src = `${STREAM_SCOPE}${id}/${encodeURIComponent(name)}`;
    document.body.appendChild(frame);
    setTimeout(() => frame.remove(), 60_000);
    return channel.port1;
  } catch {
    return null;
  }
}
