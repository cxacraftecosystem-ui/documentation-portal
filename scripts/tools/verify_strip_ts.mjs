import { createRequire } from "node:module";
import { existsSync, readFileSync, readdirSync, statSync } from "node:fs";
import { dirname, join, relative, resolve, sep } from "node:path";
import { fileURLToPath } from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..", "..");
const ts = createRequire(join(root, "frontend", "package.json"))("typescript");
const outDir = resolve(process.argv[2] ?? "");
const suffixes = [".ts", ".mts", ".cts", ".tsx", ".js", ".jsx", ".mjs", ".cjs"];

function walk(dir) {
  const found = [];
  for (const name of readdirSync(dir)) {
    const full = join(dir, name);
    if (statSync(full).isDirectory()) found.push(...walk(full));
    else if (suffixes.some((s) => name.endsWith(s))) found.push(full);
  }
  return found;
}

function scriptKind(file) {
  if (file.endsWith(".tsx")) return ts.ScriptKind.TSX;
  if (file.endsWith(".jsx") || file.endsWith(".js")) return ts.ScriptKind.JSX;
  if (file.endsWith(".mjs") || file.endsWith(".cjs")) return ts.ScriptKind.JS;
  return ts.ScriptKind.TS;
}

function jsxText(text) {
  return text.replace(/\s+/g, " ").trim();
}

function tokens(file, text) {
  const sf = ts.createSourceFile(file, text, ts.ScriptTarget.Latest, true, scriptKind(file));
  const out = [];
  const isDoc = (node) => node.kind >= ts.SyntaxKind.FirstJSDocNode && node.kind <= ts.SyntaxKind.LastJSDocNode;
  const visit = (node) => {
    if (isDoc(node)) return;
    if (ts.isJsxExpression(node) && !node.expression && !node.dotDotDotToken) return;
    if (node.kind === ts.SyntaxKind.JsxText) {
      const value = jsxText(node.text);
      const last = out[out.length - 1];
      if (last && last[0] === "jsx") last[1] = jsxText(`${last[1]} ${value}`);
      else out.push(["jsx", value]);
      return;
    }
    const kids = node.getChildren(sf).filter((kid) => !isDoc(kid));
    if (kids.length === 0) {
      out.push([node.kind, node.getText(sf)]);
      return;
    }
    for (const kid of kids) visit(kid);
  };
  visit(sf);
  return {
    list: out.filter((t) => !(t[0] === "jsx" && t[1] === "")),
    errors: sf.parseDiagnostics?.length ?? 0,
  };
}

const files = existsSync(outDir) ? walk(outDir) : [];
let failures = 0;
for (const stripped of files) {
  const rel = relative(outDir, stripped).split(sep).join("/");
  const original = join(root, rel);
  const before = tokens(rel, readFileSync(original, "utf8"));
  const after = tokens(rel, readFileSync(stripped, "utf8"));
  const a = JSON.stringify(before.list);
  const b = JSON.stringify(after.list);
  if (a !== b || after.errors > before.errors) {
    failures += 1;
    let at = 0;
    while (at < before.list.length && JSON.stringify(before.list[at]) === JSON.stringify(after.list[at])) at += 1;
    console.error(`${rel}: token stream differs at token ${at}: ${JSON.stringify(before.list[at])} vs ${JSON.stringify(after.list[at])}; parse errors ${before.errors} -> ${after.errors}`);
  }
}
console.log(`${files.length} stripped JS/TS file(s) compared with the TypeScript ${ts.version} parser; ${failures} differ`);
process.exit(failures ? 1 : 0);
