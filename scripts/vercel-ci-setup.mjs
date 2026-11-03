#!/usr/bin/env node

import { execFileSync } from "node:child_process";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import path from "node:path";
import sodium from "libsodium-wrappers";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = path.resolve(HERE, "..");
const REPO = "cxacraftecosystem-ui/documentation-portal";

const VERCEL_TOKEN = process.env.VERCEL_TOKEN;
if (!VERCEL_TOKEN) {
  console.error("VERCEL_TOKEN is not set.\n");
  console.error("  1. https://vercel.com/account/tokens -> Create Token (scope: the team, not Personal)");
  console.error("  2. VERCEL_TOKEN=<the token> node scripts/vercel-ci-setup.mjs\n");
  process.exit(1);
}

const link = JSON.parse(readFileSync(path.join(REPO_ROOT, "frontend", ".vercel", "project.json"), "utf8"));
const { projectId, orgId } = link;
console.log(`Project ${link.projectName}  (${projectId} in ${orgId})`);

function githubToken() {
  if (process.env.GITHUB_TOKEN) return process.env.GITHUB_TOKEN;
  const out = execFileSync("git", ["credential", "fill"], {
    input: "protocol=https\nhost=github.com\n\n",
    encoding: "utf8",
    cwd: REPO_ROOT
  });
  const line = out.split("\n").find((l) => l.startsWith("password="));
  if (!line) throw new Error("No GitHub credential found. Set GITHUB_TOKEN and re-run.");
  return line.slice("password=".length).trim();
}

async function vercel(method, url, body) {
  const response = await fetch(`https://api.vercel.com${url}${url.includes("?") ? "&" : "?"}teamId=${orgId}`, {
    method,
    headers: { Authorization: `Bearer ${VERCEL_TOKEN}`, "Content-Type": "application/json" },
    body: body ? JSON.stringify(body) : undefined
  });
  const text = await response.text();
  if (!response.ok) throw new Error(`Vercel ${method} ${url} -> ${response.status} ${text.slice(0, 400)}`);
  return text ? JSON.parse(text) : {};
}

console.log("\n[1/3] Root Directory -> frontend, framework -> nextjs");
const project = await vercel("PATCH", `/v9/projects/${projectId}`, {
  rootDirectory: "frontend",
  framework: "nextjs"
});
console.log(`      rootDirectory is now: ${JSON.stringify(project.rootDirectory)}`);

console.log("[2/3] Git-triggered deployments -> disabled (GitHub Actions becomes the only publisher)");
await vercel("PATCH", `/v9/projects/${projectId}`, {
  gitProviderOptions: { createDeployments: "disabled" }
});
console.log("      done");

console.log("[3/3] Writing Actions secrets");
await sodium.ready;
const gh = githubToken();

async function github(method, url, body) {
  const response = await fetch(`https://api.github.com${url}`, {
    method,
    headers: {
      Authorization: `Bearer ${gh}`,
      Accept: "application/vnd.github+json",
      "X-GitHub-Api-Version": "2022-11-28",
      "Content-Type": "application/json"
    },
    body: body ? JSON.stringify(body) : undefined
  });
  if (!response.ok && response.status !== 204) {
    throw new Error(`GitHub ${method} ${url} -> ${response.status} ${(await response.text()).slice(0, 300)}`);
  }
  const text = await response.text();
  return text ? JSON.parse(text) : {};
}

const key = await github("GET", `/repos/${REPO}/actions/secrets/public-key`);
const publicKey = sodium.from_base64(key.key, sodium.base64_variants.ORIGINAL);

for (const [name, value] of [
  ["VERCEL_TOKEN", VERCEL_TOKEN],
  ["VERCEL_ORG_ID", orgId],
  ["VERCEL_PROJECT_ID", projectId]
]) {
  const sealed = sodium.crypto_box_seal(sodium.from_string(value), publicKey);
  await github("PUT", `/repos/${REPO}/actions/secrets/${name}`, {
    encrypted_value: sodium.to_base64(sealed, sodium.base64_variants.ORIGINAL),
    key_id: key.key_id
  });
  console.log(`      ${name} set`);
}

console.log("\nDone. Push anything to main (or re-run the backend workflow) and all three stages");
console.log("should go green with the web app actually published.");
