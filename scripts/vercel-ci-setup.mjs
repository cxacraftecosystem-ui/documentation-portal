#!/usr/bin/env node
/**
 * One-shot setup so the release pipeline publishes the web app without anyone's laptop.
 *
 * Run it once, with a Vercel token:
 *
 *     VERCEL_TOKEN=xxxxxxxx node scripts/vercel-ci-setup.mjs
 *
 * Create the token at https://vercel.com/account/tokens → Create Token, scoped to the TEAM that
 * owns `field-repository` (not "Personal Account", or every call below 403s). This script never
 * prints it and never writes it to disk.
 *
 * What it does, and why each step is needed:
 *
 *  1. Sets the project's **Root Directory to `frontend`**. This is the actual cause of the
 *     "No Next.js version detected" build failure: with it unset, Vercel builds the repository
 *     root, whose package.json has no `next` in it. It is also what `vercel build` in
 *     .github/workflows/deploy-frontend.yml reads, so the same setting fixes both paths at once.
 *     It cannot be set from vercel.json — it is a project setting, so it has to be this or the
 *     dashboard.
 *
 *  2. **Turns off Git-triggered deployments.** Two publishers for one site is the bug: Vercel's
 *     own Git integration starts building the moment `main` moves, which is *before* the backend
 *     has deployed and migrated, so the live site spends that window calling endpoints that answer
 *     404. GitHub Actions becomes the single publisher and the backend→frontend order holds.
 *     (`vercel.json`'s `ignoreCommand` already does this belt-and-braces for Git builds; this makes
 *     it explicit at the project level. CLI deploys — which is what Actions does — are unaffected.)
 *
 *  3. Writes **VERCEL_TOKEN / VERCEL_ORG_ID / VERCEL_PROJECT_ID** into the repository's Actions
 *     secrets, encrypted with the repo's public key (libsodium sealed box), so stage 2 of the
 *     pipeline stops skipping and actually publishes.
 *
 * For step 3 it needs a GitHub token with `repo` scope. It reuses the one in your git credential
 * helper automatically — the same credential `git push` uses — or you can pass GITHUB_TOKEN.
 */

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

// projectId/orgId are identifiers, not credentials — they live in the linked project file.
const link = JSON.parse(readFileSync(path.join(REPO_ROOT, "frontend", ".vercel", "project.json"), "utf8"));
const { projectId, orgId } = link;
console.log(`Project ${link.projectName}  (${projectId} in ${orgId})`);

// --- GitHub token: whatever `git push` already uses -----------------------------------------
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

// --- 1 + 2: project settings ------------------------------------------------------------------
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

// --- 3: GitHub Actions secrets ----------------------------------------------------------------
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
