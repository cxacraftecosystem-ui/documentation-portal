/**
 * SHA-256 of a Blob without holding the whole file in memory (DESIGN §6.2): hash-wasm's streaming
 * hasher, fed 4 MiB at a time. Used before upload so an identical file is caught by P5 and never sent.
 */

import { createSHA256 } from "hash-wasm";

const SLICE = 4 * 1024 * 1024;

export async function sha256Hex(blob: Blob, onProgress?: (fraction: number) => void): Promise<string> {
  const hasher = await createSHA256();
  hasher.init();
  for (let offset = 0; offset < blob.size; offset += SLICE) {
    const chunk = new Uint8Array(await blob.slice(offset, offset + SLICE).arrayBuffer());
    hasher.update(chunk);
    onProgress?.(Math.min(1, (offset + chunk.length) / Math.max(1, blob.size)));
  }
  return hasher.digest("hex");
}
