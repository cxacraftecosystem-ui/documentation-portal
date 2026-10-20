import { existsSync, readFileSync } from "node:fs";
import { join, resolve } from "node:path";

import { expect, test } from "@playwright/test";

/**
 * The top-bar scanner's shared files (REQUIREMENTS_2026-10-20 §6) are the same in both portals:
 * byte-identical on the web, identical apart from the Kotlin package on Android. This file is itself
 * identical in both repositories. Point `CROSS_PORTAL_PEER_REPO` at a checkout of the other one to
 * compare across them; without it only the in-repository rules run.
 */

const REPO = resolve(__dirname, "..", "..");

const WEB_SHARED = [
  "frontend/lib/scanDispatch.ts",
  "frontend/lib/qrDecode.ts",
  "frontend/lib/qrImageDecode.ts",
  "frontend/components/scan/TopBarScanButton.tsx",
  "frontend/e2e/scan-dispatch-unit.spec.ts",
  "frontend/e2e/scan-parity-unit.spec.ts",
  "frontend/e2e/fixtures/scan-dispatch.json"
];

const KOTLIN_SHARED = ["data/ScanDispatch.kt", "ui/TopBarScanSheet.kt"];

const PACKAGES = ["com/designprototype/workshop", "com/fieldrepository/app"];

function read(path: string): string {
  return readFileSync(path, "utf8").replace(/\r\n/g, "\n");
}

/** The repository's Android source root, whichever app this is. */
function kotlinRoot(repo: string): string {
  for (const pkg of PACKAGES) {
    const root = join(repo, "android", "app", "src", "main", "java", pkg);
    if (existsSync(root)) return root;
  }
  throw new Error(`no Android source root under ${repo}`);
}

/** The Kotlin text with its own package spelled as a placeholder. */
function packageless(text: string): string {
  return text.replace(/com\.designprototype\.workshop|com\.fieldrepository\.app/g, "PKG");
}

test("the Android test reads the web's fixture, byte for byte", () => {
  expect(read(join(REPO, "android/app/src/test/resources/scan-dispatch.json"))).toBe(
    read(join(REPO, "frontend/e2e/fixtures/scan-dispatch.json"))
  );
});

test("the shared files are the other portal's", () => {
  const peer = (process.env.CROSS_PORTAL_PEER_REPO ?? "").trim();
  test.skip(!peer, "CROSS_PORTAL_PEER_REPO is not set");
  for (const path of WEB_SHARED) {
    expect(read(join(REPO, path)), `${path} differs between the portals`).toBe(read(join(peer, path)));
  }
  for (const path of KOTLIN_SHARED) {
    expect(packageless(read(join(kotlinRoot(REPO), path))), `${path} differs between the apps`).toBe(
      packageless(read(join(kotlinRoot(peer), path)))
    );
  }
});
