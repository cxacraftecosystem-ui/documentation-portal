const PREFIX = "/download-stream/";
const waiting = new Map();

globalThis.addEventListener("install", () => {
  globalThis.skipWaiting();
});

globalThis.addEventListener("activate", (event) => {
  event.waitUntil(globalThis.clients.claim());
});

globalThis.addEventListener("message", (event) => {
  const data = event.data;
  if (!data || typeof data !== "object") return;
  if (data.type === "ping" && event.ports[0]) {
    event.ports[0].postMessage({ type: "pong" });
    return;
  }
  if (data.type === "register" && typeof data.id === "string" && event.ports[0]) {
    waiting.set(data.id, { port: event.ports[0], name: String(data.name || "download.zip"), size: Number(data.size) || 0 });
    event.ports[0].postMessage({ type: "registered" });
  }
});

function asciiName(name) {
  const plain = name.replace(/[^\x20-\x7e]/g, "_").replace(/["\\]/g, "_");
  return plain || "download.zip";
}

function streamFor(entry) {
  const port = entry.port;
  let pending = null;
  return new ReadableStream(
    {
      start(controller) {
        port.onmessage = (event) => {
          const data = event.data || {};
          if (data.type === "chunk" && data.chunk) {
            controller.enqueue(new Uint8Array(data.chunk));
          } else if (data.type === "end") {
            controller.close();
          } else if (data.type === "abort") {
            controller.error(new Error("cancelled"));
          }
          if (pending) {
            const resolve = pending;
            pending = null;
            resolve();
          }
        };
      },
      pull() {
        return new Promise((resolve) => {
          pending = resolve;
          port.postMessage({ type: "pull" });
        });
      },
      cancel() {
        port.postMessage({ type: "cancel" });
      }
    },
    { highWaterMark: 4 }
  );
}

globalThis.addEventListener("fetch", (event) => {
  const url = new URL(event.request.url);
  if (url.origin !== globalThis.location.origin || !url.pathname.startsWith(PREFIX)) return;
  const id = url.pathname.slice(PREFIX.length).split("/")[0];
  const entry = waiting.get(id);
  if (!entry) {
    event.respondWith(new Response("This download has expired. Start it again.", { status: 410, headers: { "Content-Type": "text/plain; charset=utf-8" } }));
    return;
  }
  waiting.delete(id);
  const headers = new Headers({
    "Content-Type": "application/zip",
    "Content-Disposition": `attachment; filename="${asciiName(entry.name)}"; filename*=UTF-8''${encodeURIComponent(entry.name)}`,
    "Cache-Control": "no-store",
    "X-Content-Type-Options": "nosniff"
  });
  event.respondWith(new Response(streamFor(entry), { headers }));
});
