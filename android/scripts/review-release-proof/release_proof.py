#!/usr/bin/env python3
"""
TEMPORARY. Part of the adversarial review of upgrade/doc-android; deleted again by the commit after
the one that adds it, with .github/workflows/android-release-proof.yml.

THE RELEASE VARIANT, RUN. android-emulator.yml installs only the debug APK, and nothing in CI had ever
launched the variant that ships. This installs the release build — signed with a throwaway key made
in this job, never the release key — on the emulator, and takes it as far as a stub on the runner
lets it go:

  phase R (release APK built against http://127.0.0.1:8000/api/, reached through `adb reverse`)
    install, not debuggable, targets 37, never asks for ACCESS_LOCAL_NETWORK; cold launch; the
    sign-in screen; "Sign in with Google" (Credential Manager + googleid, no account on the image);
    a password sign-in the stub REFUSES with ACCESS_PENDING (OkHttp 5 + Retrofit 3 error body, read
    by kotlinx-serialization's JsonElement parser); a password sign-in the stub ACCEPTS
    (TokenResponse through the first-party converter); the dashboard's own requests, answered with
    real DTO shapes, with one number from them found on screen; the walkthrough; the drawer; View
    Data, where Coil 3 draws an https:// thumbnail (www.google.com, through coil-network-okhttp,
    OkHttp 5 and Android 17's Certificate Transparency) and an http:// one served by the stub —
    both CHECKED IN THE SCREEN'S PIXELS, not assumed; the full-screen viewer (the custom loader in
    MediaPlayers.kt); a cold relaunch that must restore the session through GET /me; home + resume.
  phase P (the release APK built against PRODUCTION, i.e. the bytes that would ship, bar the key)
    a fresh install and a cold launch to the sign-in screen. Nothing is typed, so nothing reaches
    production.

Every step ends by reading the crash buffer; the run ends with a scan of the whole logcat for a FATAL
EXCEPTION, a native crash or an ANR in this app. Any of those is a FAIL.
"""

import http.server
import importlib.util
import json
import os
import re
import struct
import sys
import threading
import time
import zlib

HERE = os.path.dirname(os.path.abspath(__file__))
_spec = importlib.util.spec_from_file_location("smoke", os.path.join(HERE, "..", "emulator-smoke.py"))
smoke = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(smoke)

PKG = smoke.PKG
OUT = os.environ.get("RP_OUT", "build/release-proof")
smoke.OUT = OUT  # the borrowed helpers save into the module's OUT
STUB_APK = os.environ["RP_STUB_APK"]
PROD_APK = os.environ["RP_PROD_APK"]
PORT = 8000
HTTPS_IMAGE = "https://www.google.com/images/branding/googlelogo/2x/googlelogo_color_272x92dp.png"
GOOGLE_COLOURS = [(66, 133, 244), (234, 67, 53), (251, 188, 5), (52, 168, 83)]
MAGENTA = [(255, 0, 255)]
TOKEN = "release-proof-token"
record = smoke.record
sh = smoke.sh


def log(msg):
    print(f"[release-proof] {msg}", flush=True)


def settle(seconds):
    time.sleep(seconds)


# ── the stub backend ─────────────────────────────────────────────────────────────────────────────────

def png(width, height, rgb):
    raw = b"".join(b"\x00" + bytes(rgb) * width for _ in range(height))

    def chunk(kind, data):
        body = kind + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)

    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b""))


MAGENTA_PNG = png(64, 64, (255, 0, 255))
USER = {"id": "proof-user-1", "email": "professor@proof.test", "name": "Proof Professor", "role": "PROFESSOR",
        "canManageQuestionnaire": False, "canManageCrafts": False, "canManageWorkshops": False,
        "canReview": False, "canViewProvenance": False, "canDownloadDataset": True, "authProvider": "password"}
STATS = {"totalArtisans": 4242, "totalWorkshops": 37, "totalProductRecords": 1717, "totalToolRecords": 909,
         "totalMediaFiles": 31337, "pendingSubmissions": 3,
         "mine": {"totalArtisans": 1, "totalWorkshops": 0, "totalProductRecords": 2, "totalToolRecords": 0,
                  "totalMediaFiles": 5, "pendingSubmissions": 1},
         "recentSubmissions": [{"id": "proof-artisan-1", "type": "artisan", "status": "APPROVED",
                                "createdAt": "2026-10-01T10:00:00Z", "title": "Proof Artisan",
                                "place": "Kutch", "createdByName": "Proof Professor"}]}
EMPTY_PAGE = {"items": [], "total": 0, "page": 1, "pageSize": 100, "pages": 0}
PREFS = {"id": "proof-prefs-1", "userId": "proof-user-1", "updatedAt": "2026-10-09T00:00:00Z", "theme": "system",
         "reducedMotion": False, "largerText": False, "highContrast": False}
TAXONOMY = {"id": "by-workshop", "name": "By workshop", "path": "by-workshop",
            "description": "release-proof stub", "default": True}
ROOT_TREE = {"path": "", "crumbs": [{"name": "Data", "path": ""}], "entries": [], "taxonomies": [TAXONOMY],
             "taxonomy": None}
FOLDER_TREE = {"path": "by-workshop",
               "crumbs": [{"name": "Data", "path": ""}, {"name": "By workshop", "path": "by-workshop"}],
               "entries": [
                   {"name": "proof-https.png", "path": "by-workshop/proof-https.png", "kind": "file",
                    "mediaType": "IMAGE", "mediaId": "proof-media-1", "url": HTTPS_IMAGE, "sizeBytes": 13504},
                   {"name": "proof-http.png", "path": "by-workshop/proof-http.png", "kind": "file",
                    "mediaType": "IMAGE", "mediaId": "proof-media-2",
                    "url": f"http://127.0.0.1:{PORT}/proof/magenta.png", "sizeBytes": len(MAGENTA_PNG)}],
               "taxonomies": [TAXONOMY], "taxonomy": "by-workshop"}

seen = []
seen_lock = threading.Lock()


class Stub(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def _send(self, status, payload, content_type="application/json"):
        body = payload if isinstance(payload, bytes) else json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)
        with seen_lock:
            seen.append({"method": self.command, "path": self.path, "status": status,
                         "ua": self.headers.get("User-Agent", ""),
                         "auth": "Bearer" in (self.headers.get("Authorization") or "")})

    def _answer(self):
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length) if length else b""
        path, _, query = self.path.partition("?")
        if path == "/proof/magenta.png":
            return self._send(200, MAGENTA_PNG, "image/png")
        if path == "/api/auth/login" and self.command == "POST":
            try:
                email = (json.loads(raw or b"{}").get("email") or "").lower()
            except ValueError:
                email = ""
            if email.startswith("pending"):
                return self._send(403, {"detail": {
                    "code": "ACCESS_PENDING",
                    "message": "Release-proof stub: your access request is awaiting approval by an administrator."}})
            return self._send(200, {"accessToken": TOKEN, "tokenType": "bearer", "user": USER})
        if not (self.headers.get("Authorization") or "").endswith(TOKEN):
            return self._send(401, {"detail": "Not authenticated"})
        if path == "/api/me":
            return self._send(200, USER)
        if path == "/api/dashboard/stats":
            return self._send(200, STATS)
        if path in ("/api/crafts", "/api/artisans"):
            return self._send(200, EMPTY_PAGE)
        if path == "/api/questionnaire/sections":
            return self._send(200, [])
        if path == "/api/app/release/latest":
            return self._send(200, {"versionCode": 1, "versionName": "0.0.1", "url": None,
                                    "notes": "release-proof stub: older than anything installed"})
        if path == "/api/preferences/me":
            return self._send(200, PREFS)
        if path == "/api/data/tree":
            return self._send(200, FOLDER_TREE if "path=by-workshop" in query else ROOT_TREE)
        return self._send(404, {"detail": "Not served by the release-proof stub"})

    do_GET = do_POST = do_PUT = do_PATCH = do_DELETE = _answer

    def log_message(self, fmt, *args):
        log("stub: " + fmt % args)


def requested(path_prefix, method=None):
    with seen_lock:
        return [r for r in seen if r["path"].startswith(path_prefix) and (method is None or r["method"] == method)]


def wait_request(path_prefix, seconds, method=None):
    deadline = time.time() + seconds
    while time.time() < deadline:
        hits = requested(path_prefix, method)
        if hits:
            return hits
        settle(1)
    return []


# ── device helpers, on top of emulator-smoke.py's ─────────────────────────────────────────────────────

# Another app's sheet over this one (save-password, an account chooser) is waved away; a runtime
# permission prompt is REFUSED, which is the answer a screen must survive.
DISMISS = re.compile(r"^(not now|no thanks|never|cancel|dismiss|close|don.?t allow|deny)$", re.I)


def dump(name):
    """A hierarchy, with another app's save-password / account sheet waved away if one is on top."""
    root = smoke.ui_dump(name)
    if root is None:
        return None
    if not any(True for _ in smoke.nodes(root, PKG)):
        for node in smoke.nodes(root):
            if node.get("package") != PKG and DISMISS.match((node.get("text") or "").strip()):
                record(f"dismissed another app's sheet before '{name}'", "INFO",
                       f"{node.get('package')}: '{node.get('text')}'")
                smoke.screenshot(f"{name}-foreign-sheet")
                smoke.tap(smoke.rect(node.get("bounds")))
                settle(2)
                return smoke.ui_dump(name)
    return root


def wait_for(pattern, name, seconds=30, package=PKG, scroll=False):
    deadline = time.time() + seconds
    root = None
    swipes = 0
    while time.time() < deadline:
        root = dump(name)
        if root is not None:
            hit = smoke.find(root, pattern, package)
            if hit:
                return root, hit
            if scroll and swipes < 8:
                sh("input swipe 540 1700 540 700 400", check=False)
                swipes += 1
                settle(1.5)
                continue
        settle(2)
    return root, None


def tap_control(root, hit):
    """Tap the clickable around a label (a Compose button merges its label into itself)."""
    around = smoke.enclosing_clickable(root, hit[1])
    smoke.tap(around[1] if around else hit[1])


def crash_free(name):
    """FAIL if the crash buffer names this app (Java or native); PASS if the process is still up."""
    text = smoke.adb("logcat", "-d", "-b", "crash", check=False, timeout=60)
    ours = [b for b in text.split("--------- beginning of") if f"Process: {PKG}" in b or f">>> {PKG} <<<" in b]
    if ours:
        smoke.save(f"{name}.crash.txt", "\n".join(ours))
        record(name, "FAIL", "the crash buffer names this app; see the .crash.txt file")
        return False
    if not smoke.alive():
        record(name, "FAIL", "the process is gone and the crash buffer says nothing")
        return False
    record(name, "PASS")
    return True


def type_into(field_rect, text):
    smoke.tap(field_rect)
    settle(1.2)
    sh(f"input text '{text}'")
    settle(0.8)


def sign_in(tag, email):
    """Fill the form afresh (it is recreated after every attempt) and press an ENABLED Login."""
    root, _ = wait_for(r"^Login$", f"{tag}-form", 20)
    fields = smoke.text_fields(root) if root is not None else []
    if len(fields) < 2:
        log(f"{tag}: {len(fields)} text fields on screen")
        return False
    type_into(fields[0], email)
    root = dump(f"{tag}-typed-email")
    fields = smoke.text_fields(root) if root is not None else []
    if len(fields) < 2:
        return False
    type_into(fields[1], "release-proof-password")
    sh("input keyevent KEYCODE_BACK", check=False)  # the keyboard, so the whole form is on screen
    settle(1.5)
    root = dump(f"{tag}-filled")
    label = smoke.find(root, r"^Login$", PKG) if root is not None else None
    button = smoke.enclosing_clickable(root, label[1]) if label else None
    if not button or button[0].get("enabled") != "true":
        log(f"{tag}: no enabled Login button; see {tag}-filled.xml")
        return False
    smoke.tap(button[1])
    return True


# ── pixels: what the screen really shows ─────────────────────────────────────────────────────────────

def raw_screen(name):
    data = smoke.adb("exec-out", "screencap", binary=True, timeout=90)
    width, height, fmt = struct.unpack_from("<III", data, 0)
    header = len(data) - width * height * 4
    if fmt not in (1, 2, 5) or header not in (12, 16):
        raise RuntimeError(f"unexpected screencap layout: {width}x{height}, format {fmt}, header {header}")
    smoke.screenshot(name)
    return width, height, fmt == 5, memoryview(data)[header:]


def count_near(screen, box, colours, tolerance=60, step=1):
    width, height, bgra, px = screen
    left, top, right, bottom = max(0, box[0]), max(0, box[1]), min(width, box[2]), min(height, box[3])
    limit = tolerance * tolerance
    hits = 0
    for y in range(top, bottom, step):
        row = px[(y * width + left) * 4:(y * width + right) * 4]
        for i in range(0, len(row), 4 * step):
            r, g, b = (row[i + 2], row[i + 1], row[i]) if bgra else (row[i], row[i + 1], row[i + 2])
            for cr, cg, cb in colours:
                if (r - cr) ** 2 + (g - cg) ** 2 + (b - cb) ** 2 <= limit:
                    hits += 1
                    break
    return hits


def image_node(root, description):
    """The AsyncImage itself: the node whose content-desc (not text) is the file name."""
    if root is None:
        return None
    for node in smoke.nodes(root, PKG):
        if (node.get("content-desc") or "") == description:
            return smoke.rect(node.get("bounds"))
    return None


def pixels_show(name, box, colours, minimum, seconds=20, step=1):
    deadline = time.time() + seconds
    best = 0
    while True:
        screen = raw_screen(name)
        best = max(best, count_near(screen, box, colours, step=step))
        if best >= minimum or time.time() >= deadline:
            return best
        settle(2)


# ── phases ────────────────────────────────────────────────────────────────────────────────────────────

def package_facts(tag):
    package = sh(f"dumpsys package {PKG}", timeout=60)
    smoke.save(f"{tag}-dumpsys-package.txt", package)
    target = re.search(r"targetSdk=(\d+)", package)
    flags = re.search(r"pkgFlags=\[([^\]]*)\]", package)
    record(f"{tag}: installed package targets 37", "PASS" if target and target.group(1) == "37" else "FAIL",
           f"targetSdk={target.group(1) if target else '?'}")
    debuggable = flags is not None and "DEBUGGABLE" in flags.group(1)
    record(f"{tag}: installed package is NOT debuggable (the release variant)", "FAIL" if debuggable else "PASS",
           f"pkgFlags=[{flags.group(1).strip() if flags else '?'}]")
    asks = smoke.LOCAL_NETWORK in package
    record(f"{tag}: the release build never asks for ACCESS_LOCAL_NETWORK", "FAIL" if asks else "PASS")


def phase_release_against_stub(sdk):
    smoke.adb("uninstall", PKG, check=False)
    ok, out = smoke.install(STUB_APK)
    record("R: install the throwaway-signed release APK (stub base)", "PASS" if ok else "FAIL", "" if ok else out)
    if not ok:
        return
    package_facts("R")
    smoke.adb("reverse", "--remove-all", check=False)
    reversed_ok = "8000" in smoke.adb("reverse", f"tcp:{PORT}", f"tcp:{PORT}", check=False) + \
        smoke.adb("reverse", "--list", check=False)
    record("R: adb reverse tcp:8000 (loopback to the stub on the runner)", "PASS" if reversed_ok else "FAIL")

    started = smoke.launch()
    smoke.save("R-01-am-start.txt", started)
    record("R: cold launch of the release build", "PASS" if "Status: ok" in started else "FAIL",
           (re.search(r"TotalTime: \d+", started) or [""])[0])
    root, hit = wait_for(r"^Login$", "R-01-login", 30)
    record("R: the sign-in screen renders", "PASS" if hit else "FAIL")
    smoke.screenshot("R-01-login")
    crash_free("R: alive on the sign-in screen")

    # Google sign-in: Credential Manager and googleid in the release build. No account exists on the
    # image, so the honest outcomes are a system sheet or an error line — never a crash.
    root = dump("R-02-before-google")
    hit = smoke.find(root, r"^Sign in with Google$", PKG) if root is not None else None
    if hit:
        tap_control(root, hit)
        settle(8)
        root = dump("R-02-google")
        smoke.screenshot("R-02-google")
        foreign = sorted({n.get("package") for n in smoke.nodes(root)} - {PKG}) if root is not None else []
        for _ in range(3):
            root = dump("R-02-google-return")
            if root is not None and any(True for _ in smoke.nodes(root, PKG)) and smoke.find(root, r"^Login$", PKG):
                break
            sh("input keyevent KEYCODE_BACK", check=False)
            settle(3)
        texts = [n.get("text") for n in smoke.nodes(root, PKG) if n.get("text")] if root is not None else []
        error_line = next((t for t in texts if re.search(r"(?i)google|credential|sign-in|cancel", t)
                           and "Sign in with Google" not in t and "Researchers" not in t), "")
        record("R: Sign in with Google (no account on the image)", "INFO",
               f"other windows seen: {', '.join(foreign) or 'none'}; app says: {error_line or '(no error line)'}")
        crash_free("R: alive after the Google sign-in attempt")
    else:
        record("R: Sign in with Google button", "FAIL", "not found on the sign-in screen")

    # A refusal, read out of the error body.
    if sign_in("R-03-pending", "pending@proof.test"):
        reached = wait_request("/api/auth/login", 30, "POST")
        record("R: sign-in request reaches the stub over loopback (no local-network permission needed)",
               "PASS" if reached else "FAIL", f"API {sdk}" + ("" if reached else ": nothing arrived in 30 s"))
        root, hit = wait_for(r"Waiting for an administrator", "R-03-pending-result", 20)
        record("R: a 403 ACCESS_PENDING is parsed into the waiting state (OkHttp 5 + Retrofit 3 error body)",
               "PASS" if hit else "FAIL", "" if hit else "the screen does not say 'Waiting for an administrator'")
        smoke.screenshot("R-03-pending-result")
    else:
        record("R: pending sign-in", "FAIL", "could not submit the form")
    crash_free("R: alive after the refused sign-in")

    # An accepted sign-in: TokenResponse through the converter, then the dashboard's own requests.
    with seen_lock:
        seen.clear()
    if not sign_in("R-04-accepted", "professor@proof.test"):
        record("R: accepted sign-in", "FAIL", "could not submit the form")
        return
    if not wait_request("/api/dashboard/stats", 40, "GET"):
        record("R: after sign-in the dashboard asks for its stats", "FAIL",
               "no GET /api/dashboard/stats in 40 s: " + ", ".join(f"{r['method']} {r['path']} {r['status']}" for r in seen))
        smoke.screenshot("R-04-accepted-stuck")
        crash_free("R: alive after the accepted sign-in")
        return
    record("R: an accepted sign-in decodes TokenResponse and opens the signed-in app", "PASS",
           "GET /api/dashboard/stats followed the 200")
    settle(4)
    root, hit = wait_for(r"^Skip$", "R-05-walkthrough", 20)
    if hit:
        smoke.screenshot("R-05-walkthrough")
        tap_control(root, hit)
        settle(3)
        record("R: the first-run walkthrough opens and Skip closes it", "PASS")
    else:
        record("R: the first-run walkthrough", "INFO", "no Skip button seen; continuing")
    crash_free("R: alive on the dashboard")
    root, hit = wait_for(r"4[,.   ]?242", "R-06-dashboard-stats", 40, scroll=True)
    record("R: DashboardStats decoded and drawn (4242 artisans on screen)", "PASS" if hit else "FAIL")
    smoke.screenshot("R-06-dashboard-stats")
    with seen_lock:
        ua = sorted({r["ua"] for r in seen if r["path"].startswith("/api/")})
    record("R: the API client's User-Agent", "INFO", "; ".join(ua))

    # The drawer, View Data, and Coil.
    for _ in range(4):
        sh("input swipe 540 700 540 1900 300", check=False)  # back to the top, where the menu button is
    settle(1.5)
    root, hit = wait_for(r"^Open menu$", "R-07-menu-button", 15)
    if not hit:
        record("R: Open menu", "FAIL", "the menu button is not on screen")
        return
    tap_control(root, hit)
    settle(2)
    root, hit = wait_for(r"^View Data$", "R-07-drawer", 15)
    if not hit:
        sh("input swipe 300 1800 300 800 400", check=False)
        settle(1.5)
        root, hit = wait_for(r"^View Data$", "R-07-drawer-scrolled", 10)
    if not hit:
        record("R: View Data in the drawer", "FAIL", "not found")
        return
    tap_control(root, hit)
    tree = wait_request("/api/data/tree?path=by-workshop", 30, "GET")
    record("R: the data browser falls through into the default taxonomy (DataTreeDto decoded)",
           "PASS" if tree else "FAIL", "" if tree else ", ".join(f"{r['method']} {r['path']}" for r in seen[-6:]))
    root, hit = wait_for(r"^proof-https\.png$", "R-08-data-browser", 30, scroll=True)
    if not hit:
        record("R: the data browser lists the two image files", "FAIL")
        smoke.screenshot("R-08-data-browser")
        crash_free("R: alive in the data browser")
        return
    settle(5)
    root = dump("R-08-data-browser")
    https_box = image_node(root, "proof-https.png")
    http_box = image_node(root, "proof-http.png")
    if https_box:
        google = pixels_show("R-08-thumbnails", https_box, GOOGLE_COLOURS, 40)
        record("R: Coil 3 draws an https:// thumbnail (pixels of the Google logo in its bounds)",
               "PASS" if google >= 40 else "FAIL", f"{google} matching pixels in {https_box}")
    else:
        record("R: the https thumbnail", "FAIL", "no image node described 'proof-https.png'")
    hit_png = wait_request("/proof/magenta.png", 15, "GET")
    record("R: Coil's network fetcher (ServiceLoader-registered) fetched the http:// thumbnail",
           "PASS" if hit_png else "FAIL", f"User-Agent: {hit_png[0]['ua']}" if hit_png else "the stub never saw it")
    crash_free("R: alive with the thumbnails loaded")

    # The full-screen viewer: MediaViewerDialog's AsyncImage, from an android.net.Uri model.
    if https_box:
        smoke.tap(https_box)
        settle(3)
        root, hit = wait_for(r"^Image preview$", "R-09-viewer", 15)
        if hit:
            google = pixels_show("R-09-viewer", hit[1], GOOGLE_COLOURS, 1500, seconds=25, step=2)
            record("R: the full-screen viewer draws the https image", "PASS" if google >= 1500 else "FAIL",
                   f"{google} matching pixels (sampled every 2nd pixel) in {hit[1]}")
            close = smoke.find(root, r"^Close$", PKG)
            if close:
                tap_control(root, close)
            else:
                sh("input keyevent KEYCODE_BACK", check=False)
            settle(2)
        else:
            record("R: the full-screen viewer", "FAIL", "no 'Image preview' node after tapping the thumbnail")
        crash_free("R: alive after the viewer")

    # The http:// row is the second file and can sit below the fold, where uiautomator does not list
    # it at all: bring it on screen rather than skip it. (Run 37943651014 skipped this silently.)
    if not http_box:
        for _ in range(3):
            sh("input swipe 540 1700 540 1100 400", check=False)
            settle(2)
            scrolled = dump("R-08-data-browser-scrolled")
            http_box = image_node(scrolled, "proof-http.png") if scrolled is not None else None
            if http_box:
                break
    if http_box:
        magenta = pixels_show("R-08-thumbnails-http", http_box, MAGENTA, 300)
        record("R: ...and Coil drew the http:// thumbnail (magenta pixels in its bounds)",
               "PASS" if magenta >= 300 else "FAIL", f"{magenta} matching pixels in {http_box}")
    else:
        record("R: the http:// thumbnail on screen", "FAIL", "its row never came on screen to be measured")

    for step in (crawl, rich_text_and_back_guard):
        try:
            step()
        except Exception as exc:  # a harness fault in one sweep must not skip the checks after it
            record(f"{step.__name__} ran to its end", "FAIL", f"{type(exc).__name__}: {exc}")

    # Back out to the dashboard, then a cold start that must restore the session through GET /me.
    for _ in range(3):
        sh("input keyevent KEYCODE_BACK", check=False)
        settle(2)
    smoke.screenshot("R-10-after-back")
    with seen_lock:
        seen.clear()
    sh(f"am force-stop {PKG}", check=False)
    settle(2)
    started = smoke.launch()
    me = wait_request("/api/me", 30, "GET")
    record("R: a cold start restores the session (GET /me with the stored token, UserDto decoded)",
           "PASS" if me and me[0]["auth"] and "Status: ok" in started else "FAIL",
           "" if me else "no GET /api/me in 30 s")
    root, hit = wait_for(r"What would you like to do\?", "R-11-relaunch", 30, scroll=True)
    record("R: the relaunched app lands on the dashboard, signed in", "PASS" if hit else "FAIL")
    sh("input keyevent KEYCODE_HOME", check=False)
    settle(3)
    smoke.launch()
    settle(4)
    crash_free("R: alive after home and resume")


# Every destination a Professor's drawer offers, in the drawer's own order, opened from the drawer as
# a person would. Each one composes its screen under Compose 1.12 and material3 1.4 in the release
# build, and meets the stub's 404 for most of what it asks the server: a server error it must survive.
CRAWL = ["Record artisan", "Record product", "Document process", "Record tool", "Take interview",
         "Upload media", "Add craft", "Record workshop", "My Activity", "Tasks", "Browse records", "Map",
         "Consolidated questionnaire", "Share data access", "Assign tools to artisans", "Review",
         "Manage users", "Settings", "Give app feedback", "Walkthrough", "Dashboard"]


def open_from_drawer(label, tag):
    for attempt in range(4):
        for _ in range(3):
            sh("input swipe 540 700 540 1900 250", check=False)  # to the top, where the menu button is
        root, hit = wait_for(r"^Open menu$", f"{tag}-menu", 8)
        if hit:
            break
        # A screen with a header of its own (the data browser), a Save / Discard question, or anything
        # else modal stands between this screen and the menu: answer it, or go back one level.
        root = dump(f"{tag}-blocked")
        out = smoke.find(root, r"^(discard|leave|ok|close)$", PKG) if root is not None else None
        if out:
            tap_control(root, out)
        else:
            sh("input keyevent KEYCODE_BACK", check=False)
        settle(2)
    else:
        return "no menu button"
    tap_control(root, hit)
    settle(1.5)
    # The drawer keeps its scroll position between openings, so start from its top every time.
    for _ in range(4):
        sh("input swipe 540 700 540 2000 250", check=False)
    settle(1)
    pattern = rf"^{re.escape(label)}$"
    root, hit = wait_for(pattern, f"{tag}-drawer", 4)
    swipes = 0
    while not hit and swipes < 5:
        sh("input swipe 540 1900 540 900 400", check=False)
        swipes += 1
        settle(1)
        root, hit = wait_for(pattern, f"{tag}-drawer", 3)
    if not hit:
        sh("input keyevent KEYCODE_BACK", check=False)  # close the drawer again
        return "not in the drawer"
    tap_control(root, hit)
    return ""


def crawl():
    for i, label in enumerate(CRAWL):
        slug = f"C-{i:02d}-" + re.sub(r"[^a-z]+", "-", label.lower()).strip("-")
        problem = open_from_drawer(label, slug)
        if problem:
            record(f"C: open '{label}' from the drawer", "INFO" if problem == "not in the drawer" else "FAIL",
                   problem)
            continue
        settle(5)
        dump(slug)
        smoke.screenshot(slug)
        if label == "Walkthrough":
            root, skip = wait_for(r"^Skip$", f"{slug}-skip", 8)
            if skip:
                tap_control(root, skip)
                settle(2)
        if not crash_free(f"C: '{label}' opens and composes in the release build"):
            # Judge the next screen on its own: the crash is saved, and the final logcat scan still
            # reads the main buffer, which is not cleared.
            smoke.adb("logcat", "-b", "crash", "-c", check=False)
            smoke.launch()
            settle(6)


def rich_text_and_back_guard():
    """
    The RichTextEditor change (each block's FocusRequester remembered and published from a SideEffect,
    for the caret-moving LaunchedEffect to find) exercised for real, in the release build: Enter
    splits a block and the caret must FOLLOW into the new one; Backspace at the start of a block
    merges it back and the caret must land at the join. Then Back from the half-filled form, which on
    Android 16+ reaches the app only through the predictive-back path, must ask Save / Discard.
    """
    problem = open_from_drawer("Add craft", "T-00-add-craft")
    if problem:
        record("T: open the craft form", "FAIL", problem)
        return
    settle(4)
    root, label = None, None
    for _ in range(6):
        root = dump("T-01-craft-form")
        label = smoke.find(root, r"^Description$", PKG) if root is not None else None
        if label and label[1][1] < 1500:
            break
        sh("input swipe 540 1700 540 1100 400", check=False)
        settle(1.5)
    if not label:
        record("T: the craft form's rich-text Description", "FAIL", "no Description label on screen")
        return
    below = sorted((r for n, r in smoke.app_content(root)
                    if n.get("class", "").endswith("EditText") and r[1] >= label[1][3]), key=lambda r: r[1])
    if not below:
        record("T: the craft form's rich-text Description", "FAIL", "no editable block under the label")
        return
    smoke.tap(below[0])
    settle(1.5)
    sh("input text 'alpha'", check=False)
    settle(1)
    sh("input keyevent KEYCODE_ENTER", check=False)
    settle(2)
    sh("input text 'beta'", check=False)
    settle(1.5)
    root = dump("T-02-after-enter")
    smoke.screenshot("T-02-after-enter")
    edits = [n for n in smoke.nodes(root, PKG) if n.get("class", "").endswith("EditText")] if root is not None else []
    texts = [(n.get("text") or "").strip().lower() for n in edits]
    focused = [(n.get("text") or "").strip().lower() for n in edits if n.get("focused") == "true"]
    split = "alpha" in texts and "beta" in texts and focused == ["beta"]
    record("T: Enter splits a rich-text block and the caret follows into the new block",
           "PASS" if split else "FAIL", f"blocks seen: {[t for t in texts if t][:6]}; focused: {focused}")
    if split:
        sh("input keyevent KEYCODE_MOVE_HOME", check=False)
        settle(1)
        sh("input keyevent KEYCODE_DEL", check=False)
        settle(2)
        sh("input text 'X'", check=False)
        settle(1.5)
        root = dump("T-03-after-merge")
        smoke.screenshot("T-03-after-merge")
        edits = [n for n in smoke.nodes(root, PKG) if n.get("class", "").endswith("EditText")] if root is not None else []
        texts = [(n.get("text") or "").strip().lower() for n in edits]
        merged = "alphaxbeta" in texts and "beta" not in texts
        record("T: Backspace at a block's start merges it back and the caret lands at the join",
               "PASS" if merged else "FAIL", f"blocks seen: {[t for t in texts if t][:6]}")
    crash_free("T: alive after editing rich text")
    sh("input keyevent KEYCODE_BACK", check=False)  # the keyboard
    settle(1.5)
    sh("input keyevent KEYCODE_BACK", check=False)  # the form
    settle(2)
    root, hit = wait_for(r"^Unsaved changes$", "T-04-back-from-dirty-form", 8)
    record("T: Back from a half-filled form asks Save / Discard (predictive back on Android 16+)",
           "PASS" if hit else "FAIL", "" if hit else "no 'Unsaved changes' question; see T-04-back-from-dirty-form.png")
    smoke.screenshot("T-04-back-from-dirty-form")
    if hit:
        discard = smoke.find(root, r"^Discard$", PKG)
        if discard:
            tap_control(root, discard)
            settle(2)
    crash_free("T: alive after leaving the form")


def phase_production_apk():
    smoke.adb("uninstall", PKG, check=False)
    ok, out = smoke.install(PROD_APK)
    record("P: install the production-pointed release APK (fresh, so no stub token survives)",
           "PASS" if ok else "FAIL", "" if ok else out)
    if not ok:
        return
    package_facts("P")
    started = smoke.launch()
    smoke.save("P-01-am-start.txt", started)
    record("P: cold launch", "PASS" if "Status: ok" in started else "FAIL",
           (re.search(r"TotalTime: \d+", started) or [""])[0])
    root, hit = wait_for(r"^Login$", "P-01-login", 30)
    record("P: the sign-in screen renders (nothing typed: production is not touched)", "PASS" if hit else "FAIL")
    smoke.screenshot("P-01-login")
    crash_free("P: alive on the sign-in screen")
    sh(f"am force-stop {PKG}", check=False)
    smoke.adb("uninstall", PKG, check=False)


def logcat_scan():
    text = smoke.adb("logcat", "-d", check=False, timeout=120)
    smoke.save("logcat.txt", text)
    crash = smoke.adb("logcat", "-d", "-b", "crash", check=False, timeout=60)
    smoke.save("logcat-crash.txt", crash)
    fatal = [m.start() for m in re.finditer(r"FATAL EXCEPTION", text)]
    ours = [i for i in fatal if PKG in text[i:i + 600]]
    native = PKG in crash and ">>>" in crash
    anr = re.findall(rf"ANR in {re.escape(PKG)}", text)
    record("logcat: no FATAL EXCEPTION, native crash or ANR in this app over the whole run",
           "FAIL" if (ours or native or anr) else "PASS",
           f"FATAL EXCEPTION blocks naming the app: {len(ours)}; native: {native}; ANR: {len(anr)}"
           + (f"; other apps' FATAL EXCEPTIONs: {len(fatal) - len(ours)}" if len(fatal) > len(ours) else ""))


def main():
    os.makedirs(OUT, exist_ok=True)
    facts = {k: sh(f"getprop {k}", check=False).strip() for k in
             ("ro.build.version.sdk", "ro.build.version.release", "ro.product.model")}
    smoke.save("device.json", json.dumps(facts, indent=2))
    log(f"device: {facts}")
    sdk = int(facts["ro.build.version.sdk"] or "0")
    smoke.adb("logcat", "-c", check=False)
    smoke.adb("logcat", "-b", "crash", "-c", check=False)
    sh("input keyevent KEYCODE_WAKEUP", check=False)
    sh("wm dismiss-keyguard", check=False)
    # The emulator's own launcher, starved on a software-rendered image, raises "isn't responding"
    # dialogs that steal the taps typing into a form (run 37945614025, API 34). Hide every app's error
    # dialogs: this script's verdicts never read a dialog — a crash is the crash buffer, an ANR is
    # ActivityManager's "ANR in" line, both still written. A font-scale nudge makes the system re-read
    # the setting, which it otherwise does only on the next configuration change.
    sh("settings put global hide_error_dialogs 1", check=False)
    sh("settings put system font_scale 1.01", check=False)
    settle(1)
    sh("settings put system font_scale 1.0", check=False)
    server = http.server.ThreadingHTTPServer(("127.0.0.1", PORT), Stub)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    try:
        phase_release_against_stub(sdk)
    except Exception as exc:  # a broken step must still leave the logcat verdict behind
        record("R: the phase ran to its end", "FAIL", f"{type(exc).__name__}: {exc}")
    finally:
        server.shutdown()
        smoke.save("stub-requests.json", json.dumps(seen, indent=1))
    try:
        phase_production_apk()
    except Exception as exc:
        record("P: the phase ran to its end", "FAIL", f"{type(exc).__name__}: {exc}")
    logcat_scan()
    failed = [r for r in smoke.results if r[1] == "FAIL"]
    lines = ["| check | result | detail |", "|---|---|---|"]
    lines += [f"| {n} | {s} | {d.replace('|', '/')} |" for n, s, d in smoke.results]
    smoke.save("summary.md", "\n".join(lines) + "\n")
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as fh:
            fh.write(f"### Release APK on API {sdk}\n\n" + "\n".join(lines) + "\n")
    log(f"{len(smoke.results)} checks, {len(failed)} failed")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
