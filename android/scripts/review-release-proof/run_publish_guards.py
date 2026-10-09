#!/usr/bin/env python3
"""
TEMPORARY. Part of the adversarial review of upgrade/doc-android, and deleted again by the commit
after the one that adds it, together with .github/workflows/android-release-proof.yml.

Runs the build-and-verify steps of .github/workflows/publish-android.yml VERBATIM: each step's own
`run:` text, its own `env:`, the workflow's top-level `env:`, in the workflow's order, from the job's
working directory (`android/`), with GITHUB_ENV carried from step to step as the runner carries it.
The only inputs that differ from a real tag push are the secrets, which here are a THROWAWAY key made
earlier in this job (never the release key, which this job cannot see), and the expected signer,
which is that throwaway key's own certificate digest in keytool's colon-separated upper-case form.

So this answers "would a tag push get through the guards on this tree, with build-tools 37.0.0 and
AGP 9.4.1's signing?" for everything a guard reads from the tree and the artefact. Nothing that
touches production runs: not the secrets gate, GUARD 3, the sign-in, GUARD 4, the upload, the publish
or GUARD 6.

Then the negative controls, because a guard that cannot fail proves nothing:
  - GUARD 2 against a digest that is not the signer's must refuse;
  - the API-base assertion must refuse a release built against http://127.0.0.1:8000/api/, which is
    also the APK the emulator then runs (through `adb reverse`, against a stub on the runner).

Outputs: $RUNNER_TEMP/app-release-prod.apk and $RUNNER_TEMP/app-release-stub.apk, both signed with
the throwaway key, and a table in the step summary.
"""

import base64
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
ANDROID = os.path.join(ROOT, "android")
WORKFLOW = os.path.join(ROOT, ".github", "workflows", "publish-android.yml")
TEMP = os.environ.get("RUNNER_TEMP", tempfile.gettempdir())
KEY_DIR = os.environ["RP_KEY_DIR"]
STUB_BASE = "http://127.0.0.1:8000/api/"

results = []


def load_workflow():
    try:
        import yaml  # noqa: PLC0415
    except ImportError:
        subprocess.run([sys.executable, "-m", "pip", "install", "--user", "--quiet",
                        "--break-system-packages", "pyyaml"], check=True)
        import yaml  # noqa: PLC0415
    with open(WORKFLOW, encoding="utf-8") as fh:
        return yaml.safe_load(fh)


def read_key_file(name):
    with open(os.path.join(KEY_DIR, name), encoding="utf-8") as fh:
        return fh.read().strip()


def tree_version():
    with open(os.path.join(ANDROID, "app", "build.gradle.kts"), encoding="utf-8") as fh:
        found = re.findall(r'^val appVersionName = "([^"]*)"', fh.read(), re.M)
    if len(found) != 1:
        sys.exit(f"expected one appVersionName line, found {len(found)}")
    return found[0]


WF = load_workflow()
TOP_ENV = {k: str(v) for k, v in (WF.get("env") or {}).items()}
STEPS = {s["name"]: s for s in WF["jobs"]["publish"]["steps"] if "name" in s}
TAG = "v" + tree_version()
CERT = read_key_file("cert-sha256")          # e.g. "AB:CD:...", keytool's own spelling
SUBSTITUTIONS = {
    # A tag push: the run-mode step resolves PUBLISH and GUARD 1 compares the tag with the tree.
    "github.event_name": "push",
    "github.ref_type": "tag",
    "inputs.dry_run": "",
    "github.sha": os.environ.get("GITHUB_SHA", ""),
    "secrets.ANDROID_RELEASE_KEYSTORE_BASE64": read_key_file("keystore.b64"),
    "secrets.ANDROID_RELEASE_KEYSTORE_PASSWORD": read_key_file("password"),
    "secrets.ANDROID_RELEASE_KEY_ALIAS": read_key_file("alias"),
    # Absent in the real repository, on purpose (the key uses the store password).
    "secrets.ANDROID_RELEASE_KEY_PASSWORD": "",
    "secrets.ANDROID_RELEASE_CERT_SHA256": CERT,
}
state_env = {}


def substitute(text, overrides):
    def replace(match):
        expr = match.group(1).strip()
        if expr in overrides:
            return overrides[expr]
        if expr in SUBSTITUTIONS:
            return SUBSTITUTIONS[expr]
        env_ref = re.fullmatch(r"env\.([A-Za-z_][A-Za-z0-9_]*)", expr)
        if env_ref:
            name = env_ref.group(1)
            return state_env.get(name, TOP_ENV.get(name, ""))
        raise SystemExit(f"unhandled expression '${{{{ {expr} }}}}': refusing to run a step with a guess in it")
    return re.sub(r"\$\{\{(.*?)\}\}", replace, text)


def run_step(name, expect_success=True, label=None, overrides=None):
    step = STEPS[name]
    overrides = overrides or {}
    script = substitute(step["run"], overrides)
    env = dict(os.environ)
    env.update(TOP_ENV)
    env.update(state_env)
    for key, value in (step.get("env") or {}).items():
        env[key] = substitute(str(value), overrides)
    env.update({"GITHUB_REF_NAME": TAG, "GITHUB_REF": f"refs/tags/{TAG}", "GITHUB_REF_TYPE": "tag",
                "GITHUB_EVENT_NAME": "push"})
    scratch = tempfile.mkdtemp(prefix="guard-", dir=TEMP)
    for var in ("GITHUB_ENV", "GITHUB_OUTPUT", "GITHUB_STEP_SUMMARY"):
        env[var] = os.path.join(scratch, var.lower())
        open(env[var], "w").close()
    script_path = os.path.join(scratch, "step.sh")
    with open(script_path, "w", encoding="utf-8") as fh:
        fh.write(script)
    title = label or name
    print(f"::group::{title}", flush=True)
    # GitHub's default `run:` shell on Linux is `bash -e {0}`; every step sets pipefail itself.
    code = subprocess.run(["bash", "-e", script_path], cwd=ANDROID, env=env).returncode
    print("::endgroup::", flush=True)
    with open(env["GITHUB_ENV"], encoding="utf-8") as fh:
        for line in fh:
            if "=" in line:
                key, _, value = line.rstrip("\n").partition("=")
                state_env[key] = value
    shutil.rmtree(scratch, ignore_errors=True)
    passed = (code == 0) == expect_success
    verdict = "PASS" if passed else "FAIL"
    detail = f"exit {code}" + ("" if expect_success else " (a refusal was the expected answer)")
    results.append((title, verdict, detail))
    print(f"[guards] {verdict}  {title} — {detail}", flush=True)
    return code


def sh(command):
    return subprocess.run(command, cwd=ANDROID, shell=True, capture_output=True, text=True)


def dex_bytes(apk):
    out = subprocess.run(["unzip", "-p", apk, "classes*.dex"], capture_output=True)
    return out.stdout


def describe(apk, tag):
    """What the release artefact is made of, for the report: not a guard, a record."""
    tools = state_env["ANDROID_BUILD_TOOLS"]
    lines = []
    listing = sh(f"unzip -l {apk}").stdout
    dex = dex_bytes(apk)
    lines.append(f"size: {os.path.getsize(apk)} bytes")
    lines.append("dex files: " + ", ".join(sorted(set(re.findall(r"classes\d*\.dex", listing)))))
    # R8 renames classes; an unshrunk build keeps every descriptor below verbatim.
    for descriptor in ("Lcom/fieldrepository/app/data/TokenResponse;",
                       "Lcom/fieldrepository/app/data/TokenResponse$$serializer;",
                       "Lretrofit2/converter/kotlinx/serialization/Factory;",
                       "Lokhttp3/internal/platform/PlatformInitializer;",
                       "Lcoil3/network/okhttp/internal/OkHttpNetworkFetcherServiceLoaderTarget;"):
        lines.append(f"dex has {descriptor}: {descriptor.encode() in dex}")
    services = [l.split()[-1] for l in listing.splitlines() if "META-INF/services/" in l]
    lines.append("META-INF/services: " + (", ".join(services) or "NONE"))
    lines.append("native: " + ", ".join(l.split()[-1] for l in listing.splitlines() if l.strip().endswith(".so")))
    manifest = sh(f'"{tools}/aapt2" dump xmltree --file AndroidManifest.xml {apk}').stdout
    lines.append("manifest debuggable: " + ("true" if "android:debuggable" in manifest and
                                            re.search(r"debuggable.*=\(type 0x12\)0xffffffff", manifest) else "false/absent"))
    lines.append("androidx.startup initializers: " + ", ".join(
        re.findall(r'A: http://schemas.android.com/apk/res/android:name\(0x[0-9a-f]+\)="([^"]*Initializer)"', manifest)))
    badging = sh(f'"{tools}/aapt2" dump badging {apk}').stdout
    lines += [l for l in badging.splitlines() if l.startswith(("package:", "sdkVersion", "targetSdkVersion",
                                                             "uses-permission", "native-code"))]
    certs = sh(f'"{tools}/apksigner" verify --verbose --print-certs {apk}').stdout
    lines += [l for l in certs.splitlines() if l.startswith(("Verified using", "Number of signers", "Signer #1 certificate DN"))]
    text = "\n".join(lines)
    with open(os.path.join(TEMP, f"release-describe-{tag}.txt"), "w", encoding="utf-8") as fh:
        fh.write(text + "\n")
    print(f"::group::What the {tag} release APK is\n{text}\n::endgroup::", flush=True)
    return text


def main():
    print(f"Emulating a push of tag {TAG}; expected signer is the throwaway key {CERT}", flush=True)
    run_step("Resolve the run mode, and refuse to publish something no tag names")
    run_step("GUARD 1 — the tag must name the version this tree builds")
    run_step("Ensure the required Android SDK packages are installed")
    run_step("Materialise the release keystore outside the workspace")
    if run_step("Assemble the signed release APK") != 0:
        return finish()
    run_step("Assert the build produced a SIGNED artefact and said nothing to the contrary")
    run_step("GUARD 2 — the signer must be the expected key, and there must BE one")
    run_step("Assert the APK carries the tagged version and this app's identity")
    run_step("Assert the APK points at THIS product's API and not at a laptop")
    run_step("GUARD 5 — the APK must fit the single-PUT upload path")
    apk = os.path.join(ANDROID, TOP_ENV["APK"])
    shutil.copy(apk, os.path.join(TEMP, "app-release-prod.apk"))
    describe(apk, "production")

    # ── negative controls ──────────────────────────────────────────────────────────────────────────
    run_step("GUARD 2 — the signer must be the expected key, and there must BE one", expect_success=False,
             label="NEGATIVE: GUARD 2 against a digest that is not the signer's",
             overrides={"secrets.ANDROID_RELEASE_CERT_SHA256": "00" * 32})

    # The APK the emulator runs: the same release variant and the same throwaway signer, with the API
    # base pointed at the stub on the runner through `adb reverse` (loopback, which needs no
    # local-network permission and is cleartext-permitted by network_security_config.xml).
    with open(os.path.join(ANDROID, "local.properties"), "w", encoding="utf-8") as fh:
        fh.write(f"apiBaseUrl={STUB_BASE}\n")
    try:
        built = run_step("Assemble the signed release APK", label="Assemble the release APK against the stub (127.0.0.1)")
        if built == 0 and STUB_BASE.encode() not in dex_bytes(apk):
            # The API base is a compile-time constant inlined into its readers; an incremental build
            # that missed the change would carry the production URL and silently test nothing.
            print("::warning::the incremental build kept the old API base; rebuilding with --rerun-tasks", flush=True)
            env = dict(os.environ)
            env.update({
                "ANDROID_RELEASE_KEYSTORE": state_env["RELEASE_KEYSTORE_PATH"],
                "ANDROID_RELEASE_KEYSTORE_PASSWORD": SUBSTITUTIONS["secrets.ANDROID_RELEASE_KEYSTORE_PASSWORD"],
                "ANDROID_RELEASE_KEY_ALIAS": SUBSTITUTIONS["secrets.ANDROID_RELEASE_KEY_ALIAS"],
                "ANDROID_RELEASE_KEY_PASSWORD": "",
            })
            built = subprocess.run(["./gradlew", ":app:assembleRelease", "--console=plain", "--rerun-tasks"],
                                   cwd=ANDROID, env=env).returncode
        stub_ok = built == 0 and STUB_BASE.encode() in dex_bytes(apk) and \
            b"https://d2b34i3e92al6i.cloudfront.net/api/" not in dex_bytes(apk)
        results.append(("the stub APK carries http://127.0.0.1:8000/api/ and not the production base",
                        "PASS" if stub_ok else "FAIL", ""))
        if stub_ok:
            shutil.copy(apk, os.path.join(TEMP, "app-release-stub.apk"))
            describe(apk, "stub")
            run_step("Assert the APK points at THIS product's API and not at a laptop", expect_success=False,
                     label="NEGATIVE: the API-base assertion against the stub-pointed release")
    finally:
        os.remove(os.path.join(ANDROID, "local.properties"))
    return finish()


def finish():
    failed = [r for r in results if r[1] == "FAIL"]
    lines = ["### publish-android.yml's own steps, run verbatim with a throwaway key", "",
             "| step | result | detail |", "|---|---|---|"]
    lines += [f"| {n} | {v} | {d} |" for n, v, d in results]
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as fh:
            fh.write("\n".join(lines) + "\n\n")
    print("\n".join(lines), flush=True)
    with open(os.path.join(TEMP, "publish-guards.json"), "w", encoding="utf-8") as fh:
        json.dump(results, fh, indent=1)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
