#!/usr/bin/env python3
"""
Drive the debug APK through what targetSdk 37 changed, on an emulator, and say what happened.

Run by .github/workflows/android-emulator.yml from inside reactivecircus/android-emulator-runner, with
`android/` as the working directory and an emulator already booted. Nothing here signs in, so nothing
here can reach production data; the one network exchange it causes is with a stub on the runner.

WHAT IT CHECKS, AND WHY EACH IS A DEVICE QUESTION RATHER THAN A UNIT TEST
  • install + cold launch, and the installed package really targets 37 (`dumpsys package`);
  • EDGE-TO-EDGE: no piece of the app's text or controls intersects a visible status bar,
    navigation bar or keyboard — read from `uiautomator dump` against the system's own inset frames
    (`dumpsys window`) — in portrait, in landscape, at a large-screen size (sw >= 600dp, where
    Android 16+ ignores orientation and resizability limits), and with the keyboard up;
  • rotation and resizing do not restart or crash the activity (it handles its own configChanges);
  • BACK at the root and HOME, then resuming, do not crash (predictive back: Android 16+ no longer
    calls onBackPressed() for targetSdk 36+);
  • LOCAL-NETWORK PERMISSION (only with SMOKE_LAN_APK, a debug build pointed at http://10.0.2.2:8000):
    on API 37+ the app asks for ACCESS_LOCAL_NETWORK at launch; "Don't allow" is answered on one
    clean launch and "Allow" on another, and only after Allow must its sign-in request reach a stub
    server on the runner. Whether the platform blocked the request after the refusal is recorded as
    information, not as a failure: that is the platform's behaviour, not the app's. Below 37 the app
    must NOT prompt, and the request must get through with no grant at all.

Every hard check is PASS or FAIL; the exit status is 1 if any FAILed. Screenshots, UI dumps, the
system's inset frames, `dumpsys package` and the full logcat land in SMOKE_OUT for the artifact.
"""

import http.server
import json
import os
import re
import subprocess
import sys
import threading
import time
import xml.etree.ElementTree as ET

PKG = "com.fieldrepository.app"
ACTIVITY = f"{PKG}/.MainActivity"
OUT = os.environ.get("SMOKE_OUT", "build/emulator-smoke")
PROD_APK = os.environ.get("SMOKE_PROD_APK", "app/build/outputs/apk/debug/app-debug.apk")
LAN_APK = os.environ.get("SMOKE_LAN_APK", "")
STUB_PORT = 8000
LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"
PERMISSION_UI = ("com.google.android.permissioncontroller", "com.android.permissioncontroller")

results = []


def log(msg):
    print(f"[smoke] {msg}", flush=True)


def record(name, status, detail=""):
    """status is PASS, FAIL or INFO. INFO never fails the run."""
    results.append((name, status, detail))
    log(f"{status:4s}  {name}" + (f" — {detail}" if detail else ""))


def adb(*args, timeout=120, check=True, binary=False):
    proc = subprocess.run(["adb", *args], capture_output=True, timeout=timeout)
    if check and proc.returncode != 0:
        raise RuntimeError(f"adb {' '.join(args)} exited {proc.returncode}: "
                           f"{proc.stderr.decode(errors='replace').strip()}")
    return proc.stdout if binary else proc.stdout.decode(errors="replace")


def sh(command, **kw):
    return adb("shell", command, **kw)


def save(name, text):
    with open(os.path.join(OUT, name), "w", encoding="utf-8") as fh:
        fh.write(text)


def screenshot(name):
    try:
        png = adb("exec-out", "screencap", "-p", binary=True, timeout=60)
        with open(os.path.join(OUT, f"{name}.png"), "wb") as fh:
            fh.write(png)
    except Exception as exc:  # a missing picture is not a verdict
        log(f"screenshot {name} failed: {exc}")


def settle(seconds=3.0):
    time.sleep(seconds)


def ui_dump(name):
    """The UI hierarchy as XML, or None. Retried: uiautomator refuses while anything animates."""
    for attempt in range(4):
        out = sh("uiautomator dump /sdcard/smoke-ui.xml", check=False, timeout=60)
        if "dumped to" in out:
            xml = sh("cat /sdcard/smoke-ui.xml", timeout=60)
            save(f"{name}.xml", xml)
            try:
                return ET.fromstring(xml)
            except ET.ParseError as exc:
                log(f"ui dump {name} did not parse: {exc}")
                return None
        settle(2.0)
    log(f"ui dump {name} failed: {out.strip()}")
    return None


BOUNDS = re.compile(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]")


def rect(text):
    m = BOUNDS.search(text or "")
    return tuple(int(v) for v in m.groups()) if m else None


def nodes(root, package=None):
    for node in root.iter("node"):
        if package is None or node.get("package") == package:
            yield node


def app_content(root):
    """
    Every node of the app that SHOWS something: text, a description, or a control to press. A node
    as large as the window is a container (a clickable backdrop, a scrim), not content, and is meant
    to run under the bars, so it is left out.
    """
    window = None
    for node in nodes(root, PKG):
        window = rect(node.get("bounds"))
        break
    for node in nodes(root, PKG):
        r = rect(node.get("bounds"))
        if not r or r[2] <= r[0] or r[3] <= r[1]:
            continue
        if window and (r[2] - r[0]) >= (window[2] - window[0]) and                 (r[3] - r[1]) >= 0.9 * (window[3] - window[1]):
            continue
        if (node.get("text") or node.get("content-desc")
                or node.get("clickable") == "true" or node.get("class", "").endswith("EditText")):
            yield node, r


INSET = re.compile(
    r"\b[mM]?[tT]ype=(statusBars|navigationBars|ime)\b[^\n]*?\b[mM]?[fF]rame=\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]"
    r"[^\n]*?\b[mM]?[vV]isible=(true|false)")


def system_bars(name):
    """Visible status/navigation/IME frames, as the window manager reports them right now."""
    dump = sh("dumpsys window", timeout=60)
    save(f"{name}.dumpsys-window.txt", dump)
    frames = set()
    for kind, l, t, r, b, visible in INSET.findall(dump):
        box = (int(l), int(t), int(r), int(b))
        if visible == "true" and box[2] > box[0] and box[3] > box[1]:
            frames.add((kind, box))
    return sorted(frames)


def intersects(a, b):
    return a[0] < b[2] and b[0] < a[2] and a[1] < b[3] and b[1] < a[3]


def check_insets(name, kinds=("statusBars", "navigationBars")):
    """Record whether any app content sits under a visible bar; return the hierarchy it measured."""
    root = ui_dump(name)
    screenshot(name)
    if root is None:
        record(f"insets: {name}", "FAIL", "no UI hierarchy to measure")
        return None
    bars = [(k, box) for k, box in system_bars(name) if k in kinds]
    if not bars:
        record(f"insets: {name}", "INFO",
               "no visible bar frame could be read from dumpsys window; see the screenshot")
        return root
    content = list(app_content(root))
    if not content:
        record(f"insets: {name}", "FAIL", "the app showed nothing measurable")
        return root
    clashes = []
    for node, r in content:
        for kind, box in bars:
            if intersects(r, box):
                label = node.get("text") or node.get("content-desc") or node.get("class")
                clashes.append(f"'{label[:40]}' {r} under {kind} {box}")
    if clashes:
        record(f"insets: {name}", "FAIL", "; ".join(clashes[:4]))
    else:
        record(f"insets: {name}", "PASS",
               f"{len(content)} app nodes clear of {', '.join(f'{k} {b}' for k, b in bars)}")
    return root


def alive():
    return bool(sh(f"pidof {PKG}", check=False).strip())


def crashes():
    text = adb("logcat", "-d", "-b", "crash", check=False, timeout=60)
    return [block for block in text.split("--------- beginning of") if f"Process: {PKG}" in block]


def check_alive(name):
    crash = crashes()
    if crash:
        save(f"{name}.crash.txt", "\n".join(crash))
        record(name, "FAIL", "crash buffer names this app — see the .crash.txt file")
    elif not alive():
        record(name, "FAIL", "the process is gone and the crash buffer says nothing")
    else:
        record(name, "PASS")


def launch():
    return sh(f"am start -W -n {ACTIVITY}", timeout=120)


def find(root, pattern, package=None):
    rx = re.compile(pattern, re.I)
    for node in nodes(root, package):
        if rx.search(node.get("text") or "") or rx.search(node.get("content-desc") or ""):
            r = rect(node.get("bounds"))
            if r:
                return node, r
    return None


def tap(r):
    sh(f"input tap {(r[0] + r[2]) // 2} {(r[1] + r[3]) // 2}")


def install(apk):
    out = adb("install", "-r", apk, timeout=300, check=False)
    return "Success" in out, out.strip()


# ── the stub backend for the local-network phase ───────────────────────────────────────────────────

requests_seen = []


class Stub(http.server.BaseHTTPRequestHandler):
    def _answer(self):
        requests_seen.append(f"{self.command} {self.path}")
        body = json.dumps({"detail": "smoke-test stub on the CI runner, not a real backend"}).encode()
        self.send_response(401)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    do_GET = do_POST = do_PUT = do_PATCH = do_DELETE = _answer

    def log_message(self, fmt, *args):
        log("stub: " + fmt % args)


def wait_for_request(seconds):
    deadline = time.time() + seconds
    while time.time() < deadline:
        if any("/api/auth/login" in r for r in requests_seen):
            return True
        time.sleep(1)
    return False


def text_fields(root):
    return [r for n, r in app_content(root) if n.get("class", "").endswith("EditText")]


def enclosing_clickable(root, inner):
    """The smallest clickable app node whose bounds contain [inner]: a button around its label."""
    best = None
    for node in nodes(root, PKG):
        r = rect(node.get("bounds"))
        if node.get("clickable") != "true" or not r:
            continue
        if r[0] <= inner[0] and r[1] <= inner[1] and r[2] >= inner[2] and r[3] >= inner[3]:
            area = (r[2] - r[0]) * (r[3] - r[1])
            if best is None or area < best[2]:
                best = (node, r, area)
    return (best[0], best[1]) if best else None


def sign_in_attempt(tag):
    """
    Fill both boxes and press Login. True only once the press has landed on an ENABLED button.

    Every position is read afresh after every focus change. The first version of this tapped the
    password box where it had been BEFORE the keyboard opened; the keyboard had moved the form up
    (adjustResize doing its job), the tap landed between two buttons, the password went into the
    email box, Login stayed disabled, and no request was ever made.
    """
    root = ui_dump(f"{tag}-form")
    fields = text_fields(root) if root is not None else []
    if len(fields) < 2:
        log(f"{tag}: found {len(fields)} text fields, expected email and password")
        return False
    tap(fields[0])
    settle(1.5)
    sh("input text 'smoke@example.test'")
    # The keyboard is up now: the IME is the third bar the app must keep its content clear of.
    root = check_insets(f"{tag}-keyboard-open", kinds=("statusBars", "navigationBars", "ime"))
    fields = text_fields(root) if root is not None else []
    if len(fields) < 2:
        log(f"{tag}: the password box is not on screen with the keyboard up")
        return False
    tap(fields[1])
    settle(1.0)
    sh("input text 'not-a-real-password'")
    sh("input keyevent KEYCODE_BACK")  # hide the keyboard so the whole form is on screen
    settle(1.5)
    root = ui_dump(f"{tag}-filled")
    label = find(root, r"^Login$", PKG) if root is not None else None
    button = enclosing_clickable(root, label[1]) if label else None
    if not button:
        log(f"{tag}: no Login button in the hierarchy")
        return False
    if button[0].get("enabled") != "true":
        log(f"{tag}: Login is disabled, so a box is still empty; see {tag}-filled.xml")
        return False
    tap(button[1])
    return True


def answer_prompt(tag, answer):
    """
    Launch from a clean slate and answer the Nearby devices prompt. Returns whether it appeared.

    `pm clear` empties the app, and clearing the permission flags forgets any earlier refusal, so
    the prompt is asked again exactly as on a first launch.
    """
    sh(f"pm clear {PKG}", check=False)
    sh(f"pm revoke {PKG} {LOCAL_NETWORK}", check=False)
    sh(f"pm clear-permission-flags {PKG} {LOCAL_NETWORK} user-set user-fixed", check=False)
    launch()
    settle(5)
    root = ui_dump(f"{tag}-prompt")
    screenshot(f"{tag}-prompt")
    prompt = root is not None and any(n.get("package") in PERMISSION_UI for n in nodes(root))
    if prompt:
        hit = find(root, answer, None)
        if hit:
            tap(hit[1])
            settle(2)
        else:
            log(f"{tag}: the prompt has no button matching {answer!r}")
    return prompt


def local_network_phase(sdk):
    ok, out = install(LAN_APK)
    if not ok:
        record("local network: install the 10.0.2.2 debug build", "FAIL", out)
        return
    server = http.server.ThreadingHTTPServer(("127.0.0.1", STUB_PORT), Stub)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    try:
        if sdk < 37:
            # Below Android 17 every app holding INTERNET is granted local-network access
            # implicitly, and the app must not ask: ui/LocalNetworkAccess.kt only prompts from 37.
            prompt = answer_prompt("lan-01", r"^allow$")
            record(f"local network: no Nearby devices prompt on API {sdk}",
                   "FAIL" if prompt else "PASS",
                   "the app asked for a permission this Android version does not have" if prompt else "")
            requests_seen.clear()
            if sign_in_attempt("lan-02-implicit"):
                reached = wait_for_request(30)
                record(f"local network: sign-in reaches 10.0.2.2 on API {sdk} with no grant",
                       "PASS" if reached else "FAIL",
                       ", ".join(requests_seen) if reached else "nothing reached the stub in 30 s")
            else:
                record("local network: sign-in", "FAIL", "could not submit the form")
            settle(3)
            screenshot("lan-02-implicit-after")
            check_alive("local network: the app survived the round trip")
            return

        # 1. Refuse, then sign in. Whether the request still leaves is the PLATFORM's answer, so it
        #    is recorded as information; it is what says the permission is needed at all.
        prompt = answer_prompt("lan-01", r"don.?t allow")
        record("local network: the debug build asks for Nearby devices at launch",
               "PASS" if prompt else "FAIL",
               "" if prompt else "no permission-controller window after launch; see lan-01-prompt.png")
        requests_seen.clear()
        if sign_in_attempt("lan-02-denied"):
            reached = wait_for_request(20)
            record("local network: without the grant, sign-in to 10.0.2.2 is blocked", "INFO",
                   "blocked: the request never reached the stub in 20 s" if not reached else
                   "NOT blocked on this image: the request reached the stub without the permission")
        else:
            record("local network: sign-in without the grant", "FAIL", "could not submit the form")
        screenshot("lan-02-denied-after")
        sh(f"am force-stop {PKG}", check=False)
        settle(2)

        # 2. Allow, by tapping Allow as a person would, and sign in again. The app was stopped
        #    first, so a request still pending from the refused attempt cannot arrive late and
        #    count as this one.
        prompt = answer_prompt("lan-03", r"^allow$")
        if not prompt:
            sh(f"pm grant {PKG} {LOCAL_NETWORK}", check=False)
        package = sh(f"dumpsys package {PKG}", timeout=60)
        save("lan-dumpsys-package.txt", package)
        granted = re.search(re.escape(LOCAL_NETWORK) + r": granted=true", package) is not None
        record("local network: tapping Allow grants ACCESS_LOCAL_NETWORK to the debug build",
               "PASS" if granted and prompt else "FAIL",
               "" if prompt else "the prompt did not come back; granted with pm instead")
        requests_seen.clear()
        if sign_in_attempt("lan-04-granted"):
            reached = wait_for_request(30)
            record("local network: with the grant, sign-in reaches the backend at 10.0.2.2",
                   "PASS" if reached else "FAIL",
                   ", ".join(requests_seen) if reached else "nothing reached the stub in 30 s")
        else:
            record("local network: sign-in with the grant", "FAIL", "could not submit the form")
        settle(3)
        screenshot("lan-04-granted-after")
        check_alive("local network: the app survived the round trip")
    finally:
        server.shutdown()


def main():
    os.makedirs(OUT, exist_ok=True)
    facts = {k: sh(f"getprop {k}", check=False).strip() for k in
             ("ro.build.version.sdk", "ro.build.version.release", "ro.product.model")}
    facts["page_size"] = sh("getconf PAGE_SIZE", check=False).strip()
    facts["wm_size"] = sh("wm size", check=False).strip()
    facts["wm_density"] = sh("wm density", check=False).strip()
    facts["navigation_mode"] = sh("settings get secure navigation_mode", check=False).strip()
    save("device.json", json.dumps(facts, indent=2))
    log(f"device: {facts}")
    adb("logcat", "-c", check=False)
    adb("logcat", "-b", "crash", "-c", check=False)
    sh("input keyevent KEYCODE_WAKEUP", check=False)
    sh("wm dismiss-keyguard", check=False)
    sh("settings put system accelerometer_rotation 0", check=False)
    sh("settings put system user_rotation 0", check=False)

    ok, out = install(PROD_APK)
    record("install the debug APK android-build.yml uploads", "PASS" if ok else "FAIL", "" if ok else out)
    if not ok:
        return finish()
    package = sh(f"dumpsys package {PKG}", timeout=60)
    save("dumpsys-package.txt", package)
    target = re.search(r"targetSdk=(\d+)", package)
    record("the installed package targets SDK 37",
           "PASS" if target and target.group(1) == "37" else "FAIL",
           f"targetSdk={target.group(1) if target else '?'}")

    started = launch()
    save("01-am-start.txt", started)
    record("cold launch", "PASS" if "Status: ok" in started else "FAIL",
           (re.search(r"TotalTime: \d+", started) or [""])[0])
    settle(6)
    check_alive("alive after launch")
    check_insets("01-portrait")

    sh("settings put system user_rotation 1")
    settle(4)
    check_alive("alive after rotating to landscape")
    check_insets("02-landscape")
    sh("settings put system user_rotation 0")
    settle(3)

    sh("wm size 1600x2560")
    sh("wm density 320")  # 1600 px / 2.0 = 800 dp: a large screen
    settle(5)
    check_alive("alive at a large-screen size (sw 800dp)")
    check_insets("03-large-screen")
    sh("wm size reset")
    sh("wm density reset")
    settle(4)
    check_alive("alive after returning to the phone size")

    sh("input keyevent KEYCODE_BACK")
    settle(3)
    screenshot("04-after-back")
    crash = crashes()
    record("back at the root does not crash", "FAIL" if crash else "PASS")
    launch()
    settle(4)
    check_alive("relaunch after back")

    sh("input keyevent KEYCODE_HOME")
    settle(4)
    launch()
    settle(4)
    check_alive("resume after home")
    check_insets("05-after-resume")

    if LAN_APK:
        local_network_phase(int(facts["ro.build.version.sdk"] or "0"))
    else:
        record("local network", "INFO", "skipped: SMOKE_LAN_APK not given")
    return finish()


def finish():
    save("logcat.txt", adb("logcat", "-d", check=False, timeout=120))
    failed = [r for r in results if r[1] == "FAIL"]
    lines = ["| check | result | detail |", "|---|---|---|"]
    lines += [f"| {n} | {s} | {d.replace('|', '/')} |" for n, s, d in results]
    save("summary.md", "\n".join(lines) + "\n")
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as fh:
            fh.write("### Emulator smoke\n\n" + "\n".join(lines) + "\n")
    log(f"{len(results)} checks, {len(failed)} failed")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
