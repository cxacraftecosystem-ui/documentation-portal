import { createHash } from "node:crypto";
import { existsSync, readdirSync, readFileSync, statSync } from "node:fs";
import { dirname, join, relative, resolve } from "node:path";

import { expect, test } from "@playwright/test";

const FRONTEND = join(__dirname, "..");
const REPO = join(FRONTEND, "..");
const RULES = JSON.parse(readFileSync(join(REPO, "shared", "data-explorer-web-imports.json"), "utf8")) as {
  trees: string[];
  packages: string[];
  modules: string[];
};

function files(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) return files(path);
    return /\.(ts|tsx)$/.test(name) ? [path] : [];
  });
}

const TREE_DIRS = [join(FRONTEND, "lib", "dataExplorer"), join(FRONTEND, "components", "dataExplorer")];
const SHARED = TREE_DIRS.flatMap(files);

test("the shared trees carry the explorer", () => {
  const names = SHARED.map((path) => relative(FRONTEND, path).split("\\").join("/"));
  for (const expected of [
    "lib/dataExplorer/contract.ts",
    "lib/dataExplorer/zip.ts",
    "lib/dataExplorer/engine.ts",
    "lib/dataExplorer/download.worker.ts",
    "components/dataExplorer/DataExplorer.tsx",
    "components/dataExplorer/DownloadsPanel.tsx",
    "components/dataExplorer/FolderTree.tsx"
  ]) {
    expect(names).toContain(expected);
  }
});

test("a shared file imports only what both portals carry", () => {
  const offenders: string[] = [];
  for (const path of SHARED) {
    const source = readFileSync(path, "utf8");
    for (const match of source.matchAll(/from\s+["']([^"']+)["']|import\(\s*["']([^"']+)["']\s*\)/g)) {
      const target = match[1] ?? match[2];
      if (target.startsWith(".")) {
        const landed = resolve(dirname(path), target);
        if (!TREE_DIRS.some((dir) => landed.startsWith(dir))) offenders.push(`${relative(FRONTEND, path)} imports ${target} (outside the trees)`);
        continue;
      }
      const ok = RULES.trees.some((tree) => target.startsWith(tree)) || RULES.packages.includes(target) || RULES.modules.includes(target);
      if (!ok) offenders.push(`${relative(FRONTEND, path)} imports ${target}`);
    }
  }
  expect(offenders, "add a module to shared/data-explorer-web-imports.json only if both portals carry it").toEqual([]);
});

test("every listed module exists in this portal", () => {
  for (const name of RULES.modules) {
    const base = join(FRONTEND, name.replace(/^@\//, ""));
    expect([".ts", ".tsx"].some((ext) => existsSync(base + ext)), name).toBe(true);
  }
});

test("the stream service worker ships beside the explorer", () => {
  const worker = readFileSync(join(FRONTEND, "public", "download-stream-sw.js"), "utf8");
  expect(worker).toContain('const PREFIX = "/download-stream/"');
  expect(worker).toContain("respondWith(new Response(streamFor(entry)");
});

function manifestLines(root: string): string[] {
  return readFileSync(join(root, "shared", "data-explorer-web.sha256"), "utf8").split("\r\n").join("\n").trim().split("\n");
}

test("the manifest describes the files on disk", () => {
  const listed = manifestLines(REPO).map((line) => {
    const [hash, path] = line.split(/\s{2}/);
    return { hash, path };
  });
  const drift: string[] = [];
  for (const { hash, path } of listed) {
    const full = join(REPO, path);
    if (!existsSync(full)) {
      drift.push(`${path} is listed but missing`);
      continue;
    }
    const text = readFileSync(full).toString("binary").split("\r\n").join("\n");
    const actual = createHash("sha256").update(Buffer.from(text, "binary")).digest("hex");
    if (actual !== hash) drift.push(`${path} differs from the manifest`);
  }
  const onDisk = TREE_DIRS.flatMap((dir) =>
    readdirSync(dir, { recursive: true })
      .map(String)
      .filter((name) => /\.(ts|tsx|json)$/.test(name))
      .map((name) => relative(REPO, join(dir, name)).split("\\").join("/"))
  );
  for (const path of onDisk) if (!listed.some((entry) => entry.path === path)) drift.push(`${path} is not in the manifest`);
  expect(drift, "run python scripts/sync_data_explorer.py --to ../documentation-portal from designer-portal").toEqual([]);
});

test("both portals carry the same data explorer", () => {
  const peer = process.env.SIBLING_REPO ?? process.env.CROSS_PORTAL_PEER_REPO;
  test.skip(!peer || !existsSync(join(peer, "shared", "data-explorer-web.sha256")), "the other portal is not checked out beside this one");
  expect(manifestLines(peer as string)).toEqual(manifestLines(REPO));
});
