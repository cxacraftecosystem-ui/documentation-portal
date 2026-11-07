#!/usr/bin/env python3
import json
import os
import re
import shlex
import subprocess
import sys
import threading
import time
import traceback
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET

PORTALS = {
    "dr": {
        "pkg": "com.designprototype.workshop",
        "api": "https://d3ekigkotd1xa2.cloudfront.net/api",
        "web": "https://designer-repository.vercel.app",
        "jump": ["Test", "Photo", "ka"],
        "everything": ["Photo", "IITKGP", "ka"],
    },
    "fr": {
        "pkg": "com.fieldrepository.app",
        "api": "https://d2b34i3e92al6i.cloudfront.net/api",
        "web": "https://field-repository.vercel.app",
        "jump": ["Giriraj", "Heera"],
        "everything": ["Giriraj", "Heera"],
    },
}
PORTAL = os.environ.get("PORTAL", "dr")
CFG = PORTALS[PORTAL]
PKG = CFG["pkg"]
API = CFG["api"]
WEB = CFG["web"]
OUT = os.environ.get("AUDIT_OUT", "accept-out")
APK = os.environ.get("AUDIT_APK", "")
PROFILE = os.environ.get("AUDIT_PROFILE", "phone")
EMAIL = os.environ.get("GATE_EMAIL", "")
PASSWORD = os.environ.get("GATE_PASSWORD", "")
SOAK_SECONDS = int(os.environ.get("SOAK_SECONDS", "900"))
STATUSES = [("ALL", "All"), ("APPROVED", "Approved"), ("PENDING", "Pending"), ("NEEDS_REVISION", "Needs revision"), ("REJECTED", "Rejected")] if PORTAL == "fr" else [(None, None)]
EMPTY_CARD = r"^Nothing recorded here yet$"

os.makedirs(OUT, exist_ok=True)
SECRETS = [s for s in (EMAIL, PASSWORD) if s]
results = []
criteria = {}
step_no = [0]
T0 = time.time()


def redact(text):
    text = str(text)
    for s in SECRETS:
        text = text.replace(s, "[gate-secret]")
    if EMAIL and "@" in EMAIL:
        text = text.replace(EMAIL.split("@")[0], "[gate-user]")
    text = re.sub(r"(?i)((?:token|signature|x-amz-[a-z-]+|sig|key-pair-id|policy)=)[^&\s\"]+", r"\1[redacted]", text)
    text = re.sub(r"(?i)(bearer\s+)[A-Za-z0-9._-]+", r"\1[redacted]", text)
    return text


def log(msg):
    print(f"[{PORTAL}:{PROFILE} {time.time() - T0:7.1f}s] {redact(msg)}", flush=True)


def save_json():
    with open(os.path.join(OUT, "results.json"), "w", encoding="utf-8") as fh:
        json.dump(results, fh, indent=1, ensure_ascii=False)
    with open(os.path.join(OUT, "criteria.json"), "w", encoding="utf-8") as fh:
        json.dump(criteria, fh, indent=1, ensure_ascii=False)


def record(crit, name, status, detail="", evidence=None):
    row = {"criterion": crit, "check": name, "status": status, "detail": redact(detail), "evidence": evidence or [], "at": round(time.time() - T0, 1)}
    results.append(row)
    criteria.setdefault(str(crit), []).append(row)
    log(f"C{crit} {status:5s} {name} :: {redact(detail)[:400]}")
    save_json()


def adb(*args, timeout=120, check=True, binary=False):
    proc = subprocess.run(["adb", *args], capture_output=True, timeout=timeout)
    if check and proc.returncode != 0:
        raise RuntimeError(f"adb {redact(' '.join(args))} exited {proc.returncode}: {redact(proc.stderr.decode(errors='replace').strip())}")
    return proc.stdout if binary else proc.stdout.decode(errors="replace")


def sh(command, **kw):
    return adb("shell", command, **kw)


def settle(seconds=2.0):
    time.sleep(seconds)


def save(name, text):
    with open(os.path.join(OUT, name), "w", encoding="utf-8") as fh:
        fh.write(redact(text))


def shot(name):
    try:
        png = adb("exec-out", "screencap", "-p", binary=True, timeout=60)
        with open(os.path.join(OUT, f"{name}.png"), "wb") as fh:
            fh.write(png)
    except Exception as exc:
        log(f"screenshot {name} failed: {exc}")
    return f"{name}.png"


def dump(name=None):
    for _ in range(4):
        out = sh("uiautomator dump /sdcard/accept-ui.xml", check=False, timeout=60)
        if "dumped to" in out:
            xml = sh("cat /sdcard/accept-ui.xml", timeout=60)
            if name:
                save(f"{name}.xml", xml)
            try:
                return ET.fromstring(xml)
            except ET.ParseError:
                return None
        settle(1.0)
    return None


BOUNDS = re.compile(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]")


def rect(text):
    m = BOUNDS.search(text or "")
    return tuple(int(v) for v in m.groups()) if m else None


def label(node):
    return (node.get("text") or "") or (node.get("content-desc") or "")


def all_nodes(root, package=PKG):
    if root is None:
        return []
    return [n for n in root.iter("node") if package is None or n.get("package") == package]


def find_all(root, pattern, package=PKG):
    rx = re.compile(pattern, re.I)
    found = []
    for n in all_nodes(root, package):
        if rx.search(n.get("text") or "") or rx.search(n.get("content-desc") or ""):
            r = rect(n.get("bounds"))
            if r and r[2] > r[0] and r[3] > r[1]:
                found.append((n, r))
    return found


def find(root, pattern, package=PKG):
    hits = find_all(root, pattern, package)
    return hits[0] if hits else None


def texts(root, limit=60):
    seen = []
    for n in all_nodes(root):
        t = label(n).strip()
        if t and t not in seen:
            seen.append(t)
    return seen[:limit]


def text_count(root):
    return sum(1 for n in all_nodes(root) if label(n).strip())


def tap(r):
    sh(f"input tap {(r[0] + r[2]) // 2} {(r[1] + r[3]) // 2}")


def screen_size():
    out = sh("wm size")
    m = re.findall(r"(\d+)x(\d+)", out)
    return tuple(int(v) for v in m[-1]) if m else (1080, 2400)


def density():
    out = sh("wm density")
    m = re.findall(r"(\d+)", out)
    return int(m[-1]) if m else 420


def content_left():
    if PROFILE != "tablet":
        return 0
    w, h = screen_size()
    if w < h:
        return 0
    return int(340 * density() / 160) + 8


def swipe(x, y1, y2, ms=400):
    sh(f"input swipe {x} {y1} {x} {y2} {ms}")
    settle(1.0)


def content_x():
    w, _ = screen_size()
    left = content_left()
    return (left + w) // 2 if left else w // 2


def scroll_down_list(fraction=0.45):
    w, h = screen_size()
    swipe(content_x(), int(h * 0.78), int(h * (0.78 - fraction)))


def scroll_to_top():
    w, h = screen_size()
    for _ in range(3):
        swipe(content_x(), int(h * 0.3), int(h * 0.85), 250)


def back():
    sh("input keyevent KEYCODE_BACK")
    settle(2)


def ime_shown():
    out = sh("dumpsys input_method", check=False)
    return "mInputShown=true" in out


def hide_ime():
    if ime_shown():
        sh("input keyevent KEYCODE_BACK")
        settle(1)


def mark(name):
    sh(f"log -t ACCEPT {shlex.quote('MARK ' + name)}", check=False)


def alive():
    return bool(sh(f"pidof {PKG}", check=False).strip())


last_pid = [None]
incidents = []


def crash_check(tag):
    pid = sh(f"pidof {PKG}", check=False).strip()
    crash = adb("logcat", "-d", "-b", "crash", check=False, timeout=60)
    blocks = [b for b in crash.split("--------- beginning of") if PKG in b]
    if blocks:
        save(f"crash-{tag}.txt", "\n".join(blocks))
        adb("logcat", "-b", "crash", "-c", check=False)
        incidents.append(f"crash at {tag}: {blocks[-1][:300]}")
    if last_pid[0] and pid and pid != last_pid[0]:
        incidents.append(f"process restarted at {tag}: {last_pid[0]} -> {pid}")
    if not pid:
        incidents.append(f"process gone at {tag}")
    last_pid[0] = pid or last_pid[0]
    root = dump(None)
    anr = find(root, r"(isn.t responding|keeps stopping|has stopped)", None)
    if anr:
        incidents.append(f"system dialog at {tag}: {label(anr[0])}")
        shot(f"sysdialog-{tag}")
        w = find(root, r"^(Wait|Close app|OK)$", None)
        if w:
            tap(w[1])
    return root


def step(name, wait=0.0, check=True):
    if wait:
        settle(wait)
    step_no[0] += 1
    tag = f"{step_no[0]:03d}-{name}"
    root = dump(tag)
    shot(tag)
    if check:
        crash_check(tag)
    return root, tag


def tap_text(pattern, timeout=10, scroll=0, package=PKG, x=None):
    deadline = time.time() + timeout
    tries = 0
    while True:
        root = dump(None)
        hit = find(root, pattern, package)
        if hit:
            tap(hit[1])
            return hit
        if tries < scroll:
            tries += 1
            w, h = screen_size()
            swipe(x if x is not None else content_x(), int(h * 0.75), int(h * 0.4))
            continue
        if time.time() > deadline:
            return None
        settle(1.0)


class Recorder:
    def __init__(self):
        self.stop = False
        self.n = 0
        self.thread = threading.Thread(target=self.run, daemon=True)

    def run(self):
        size = "--size 1280x800 " if PROFILE == "tablet" else ""
        while not self.stop:
            self.n += 1
            subprocess.run(["adb", "shell", f"screenrecord {size}--bit-rate 2000000 --time-limit 175 /sdcard/rec-{self.n:02d}.mp4"], capture_output=True)

    def start(self):
        self.thread.start()

    def finish(self):
        self.stop = True
        sh("pkill -INT screenrecord", check=False)
        settle(4)
        for i in range(1, self.n + 1):
            adb("pull", f"/sdcard/rec-{i:02d}.mp4", os.path.join(OUT, f"screenrecord-{i:02d}.mp4"), check=False, timeout=300)


class Heap(threading.Thread):
    def __init__(self):
        super().__init__(daemon=True)
        self.stop = False
        self.rows = []

    def sample(self, note=""):
        out = sh(f"dumpsys meminfo {PKG}", check=False, timeout=60)
        java = re.search(r"Java Heap:\s+(\d+)", out)
        total = re.search(r"TOTAL PSS:\s+(\d+)", out) or re.search(r"TOTAL\s+(\d+)", out)
        native = re.search(r"Native Heap:\s+(\d+)", out)
        row = {"t": round(time.time() - T0, 1), "javaKB": int(java.group(1)) if java else None, "nativeKB": int(native.group(1)) if native else None, "pssKB": int(total.group(1)) if total else None, "note": note}
        self.rows.append(row)
        with open(os.path.join(OUT, "heap.csv"), "w", encoding="utf-8") as fh:
            fh.write("t_seconds,java_heap_kb,native_heap_kb,total_pss_kb,note\n")
            for r in self.rows:
                fh.write(f"{r['t']},{r['javaKB']},{r['nativeKB']},{r['pssKB']},{r['note']}\n")
        return row

    def run(self):
        while not self.stop:
            try:
                self.sample()
            except Exception as exc:
                log(f"heap sample failed: {exc}")
            for _ in range(30):
                if self.stop:
                    return
                time.sleep(1)


class Api:
    def __init__(self):
        self.token = None
        self.calls = 0

    def call(self, path):
        self.calls += 1
        req = urllib.request.Request(API + path, headers={"accept": "application/json"})
        if self.token:
            req.add_header("authorization", f"Bearer {self.token}")
        try:
            with urllib.request.urlopen(req, timeout=120) as r:
                return r.status, json.loads(r.read() or b"null")
        except urllib.error.HTTPError as e:
            return e.code, None
        except Exception:
            return 0, None

    def login(self):
        body = json.dumps({"email": EMAIL, "password": PASSWORD}).encode()
        req = urllib.request.Request(API + "/auth/login", data=body, method="POST", headers={"accept": "application/json", "content-type": "application/json"})
        try:
            with urllib.request.urlopen(req, timeout=120) as r:
                payload = json.loads(r.read() or b"null") or {}
                self.token = payload.get("accessToken") or payload.get("access_token") or payload.get("token")
                return r.status
        except urllib.error.HTTPError as e:
            return e.code
        except Exception:
            return 0

    def tree(self, path="", status=None):
        q = {"path": path, "slim": 1}
        if status and status != "ALL":
            q["status"] = status
        s, body = self.call("/data/tree?" + urllib.parse.urlencode(q))
        return body if s == 200 and isinstance(body, dict) else None


api = Api()
scout_data = {"taxonomies": [], "names": {}, "folders": {}}


def entry_names(tree, limit=40):
    return [e.get("name") for e in (tree or {}).get("entries", [])[:limit] if e.get("name")]


def first_id(payload):
    if isinstance(payload, dict):
        for k in ("items", "data", "results", "artisans", "crafts", "rows"):
            if isinstance(payload.get(k), list) and payload[k]:
                return first_id(payload[k])
        if payload.get("id"):
            return payload.get("id"), payload.get("name") or payload.get("title")
    if isinstance(payload, list) and payload:
        return first_id(payload[0])
    return None


def scout():
    status = api.login()
    log(f"scout login status {status}")
    if not api.token:
        raise RuntimeError("scout sign-in failed")
    root = api.tree("")
    taxes = (root or {}).get("taxonomies") or []
    scout_data["taxonomies"] = [{"id": t.get("id"), "name": t.get("name"), "path": t.get("path"), "default": t.get("default")} for t in taxes]
    for value, _ in STATUSES:
        for t in taxes:
            tr = api.tree(t.get("path"), value)
            scout_data["names"][f"{t.get('path')}|{value}"] = entry_names(tr)
    queue = [(t.get("path"), 1) for t in taxes]
    seen = 0
    chain_candidates = []
    while queue and seen < 40:
        path, depth = queue.pop(0)
        tr = api.tree(path)
        seen += 1
        if not tr:
            continue
        scout_data["folders"][path] = {"names": entry_names(tr), "crumbs": [c.get("name") for c in tr.get("crumbs", [])], "folders": sum(1 for e in tr.get("entries", []) if e.get("kind") == "folder"), "files": sum(1 for e in tr.get("entries", []) if e.get("kind") != "folder")}
        for e in tr.get("entries", []):
            if e.get("kind") == "folder":
                p = e.get("path") or ""
                if "/artisans/" in p and "artisan" not in scout_data:
                    scout_data["artisan"] = {"name": e.get("name"), "path": p, "id": p.rstrip("/").split("/")[-1]}
                if depth < 5:
                    queue.append((p, depth + 1))
                chain_candidates.append((depth + 1, p, "/artisans/" in p))
            else:
                mt = (e.get("mediaType") or "").upper()
                if mt == "IMAGE" and "image" not in scout_data:
                    scout_data["image"] = {"name": e.get("name"), "mediaId": e.get("mediaId"), "folder": path}
                if "file" not in scout_data and e.get("name"):
                    scout_data["file"] = {"name": e.get("name"), "mediaId": e.get("mediaId"), "folder": path, "mediaType": mt}
    artisan_children = [c for c in chain_candidates if scout_data.get("artisan") and c[1].startswith(scout_data["artisan"]["path"] + "/")]
    if artisan_children:
        target = sorted(artisan_children)[0][1]
    elif scout_data.get("artisan"):
        target = scout_data["artisan"]["path"]
    else:
        preferred = [c for c in chain_candidates if c[1].startswith("by-design-workshop/") or c[1].startswith("by-workshop/")]
        pool = preferred or chain_candidates
        target = sorted(pool, key=lambda c: -c[0])[0][1] if pool else None
    if target:
        tr = api.tree(target)
        crumbs = (tr or {}).get("crumbs") or []
        scout_data["chain"] = {"target": target, "crumbs": [{"name": c.get("name"), "path": c.get("path")} for c in crumbs]}
        levels = []
        for c in crumbs:
            p = c.get("path") or ""
            if p:
                levels.append({"name": c.get("name"), "path": p, "names": entry_names(api.tree(p))})
        scout_data["chain"]["levels"] = levels
    s, artisans = api.call("/artisans?page=1&pageSize=1")
    got = first_id(artisans) if s == 200 else None
    if got and "artisan" not in scout_data:
        scout_data["artisan"] = {"name": got[1], "id": got[0], "path": None}
    s, crafts = api.call("/crafts?page=1&pageSize=1")
    got = first_id(crafts) if s == 200 else None
    if got:
        scout_data["craft"] = {"id": got[0], "name": got[1]}
    save("scout.json", json.dumps(scout_data, indent=1, ensure_ascii=False))
    log(f"scouted {api.calls} API reads; keys {sorted(scout_data)}")


def install():
    out = adb("install", "-r", "-g", APK, timeout=300, check=False)
    log("install: " + out.strip()[-120:])
    if "Success" not in out:
        raise RuntimeError("install failed")
    sh(f"pm grant {PKG} android.permission.POST_NOTIFICATIONS", check=False)
    save("apk-version.txt", sh(f"dumpsys package {PKG} | grep -E 'versionName|versionCode'", check=False))


def launch():
    sh(f"am start -W -n {PKG}/.MainActivity", timeout=120, check=False)
    settle(4)
    last_pid[0] = sh(f"pidof {PKG}", check=False).strip()


def type_into(field_rect, value):
    tap(field_rect)
    settle(0.8)
    escaped = value.replace("%", "\\%").replace(" ", "%s")
    proc = subprocess.run(["adb", "shell", "input text " + shlex.quote(escaped)], capture_output=True, timeout=60)
    if proc.returncode != 0:
        raise RuntimeError("typing into a field failed")
    settle(0.8)


def on_dashboard(root):
    return find(root, r"^What would you like to do\?$") is not None or find(root, r"^Your contribution$") is not None


def dismiss_prompts(root):
    hit = find(root, r"^(Skip|Not now|Later|Dismiss|Got it|Close|Maybe later)$")
    if hit:
        tap(hit[1])
        settle(2)
        return True
    return False


def login():
    deadline = time.time() + 120
    root = None
    while time.time() < deadline:
        root = dump(None)
        if find(root, r"^Open menu$"):
            return True
        if len([n for n in all_nodes(root) if n.get("class", "").endswith("EditText")]) >= 2:
            break
        if not dismiss_prompts(root):
            settle(2)
    step("login-screen", check=False)
    for attempt in range(3):
        root = dump(None)
        fields = [rect(n.get("bounds")) for n in all_nodes(root) if n.get("class", "").endswith("EditText")]
        if len(fields) < 2:
            break
        type_into(fields[0], EMAIL)
        root = dump(None)
        fields = [rect(n.get("bounds")) for n in all_nodes(root) if n.get("class", "").endswith("EditText")]
        type_into(fields[1], PASSWORD)
        hide_ime()
        root = dump(None)
        boxes = [n for n in all_nodes(root) if n.get("checkable") == "true" and n.get("checked") == "false"]
        agree = find(root, r"^I agree to the")
        if boxes:
            tap(rect(boxes[0].get("bounds")))
            settle(1)
        elif agree and find(root, r"Tick the box to sign in"):
            r = agree[1]
            sh(f"input tap {max(r[0] - 60, 10)} {(r[1] + r[3]) // 2}")
            settle(1)
        hide_ime()
        if not tap_text(r"^Login$", timeout=8):
            back()
            tap_text(r"^Login$", timeout=8)
        end = time.time() + 90
        while time.time() < end:
            root = dump(None)
            if find(root, r"^Open menu$") and not find(root, r"^Login$"):
                break
            if not dismiss_prompts(root):
                settle(2)
        for _ in range(4):
            root = dump(None)
            if not dismiss_prompts(root):
                break
        if find(root, r"^Open menu$"):
            step("signed-in")
            return True
        step(f"login-retry-{attempt}", check=False)
        for n in all_nodes(root):
            if n.get("class", "").endswith("EditText"):
                tap(rect(n.get("bounds")))
                sh("input keyevent KEYCODE_MOVE_END")
                for _ in range(3):
                    sh("input keyevent --longpress KEYCODE_DEL", check=False)
                sh("input keyevent " + " ".join(["KEYCODE_DEL"] * 40), check=False)
        hide_ime()
    return False


def go_dashboard():
    for _ in range(10):
        if not alive():
            launch()
            continue
        root = dump(None)
        if on_dashboard(root):
            return root
        home = find(root, r"^(Home|Dashboard)$")
        if home and find(root, r"^Open menu$") and not find(root, r"^View data$"):
            tap(home[1])
            settle(2)
            continue
        back()
    return dump(None)


def menu_x(root):
    for pat in (r"^Logout$", r"^Dashboard$", r"^Walkthrough$"):
        hit = find(root, pat)
        if hit:
            return (hit[1][0] + hit[1][2]) // 2
    return screen_size()[0] // 2


def open_data():
    go_dashboard()
    t_tap = None
    if tap_text(r"^Open menu$", timeout=10):
        settle(1.5)
        root = dump(None)
        x = menu_x(root)
        for i in range(10):
            hit = find(root, r"^Data$")
            if hit:
                mark("open-data-menu")
                t_tap = time.time()
                tap(hit[1])
                return t_tap, "menu"
            w, h = screen_size()
            swipe(x, int(h * 0.8), int(h * 0.45))
            root = dump(None)
        back()
    root = go_dashboard()
    if tap_text(r"^(View Data|Browse records)$", timeout=6, scroll=6):
        settle(3)
        hit = None
        for _ in range(6):
            root = dump(None)
            hit = find(root, r"^(Open Data|Open the Data Browser)$")
            if hit:
                break
            scroll_down_list(0.4)
        if hit:
            mark("open-data-tile")
            t_tap = time.time()
            tap(hit[1])
            return t_tap, "tile"
    return None, None


def names_in(root, names, left=0):
    want = set(n for n in names if n)
    hits = []
    for n in all_nodes(root):
        t = label(n).strip()
        if t in want:
            r = rect(n.get("bounds"))
            if r and r[2] > r[0] and r[3] > r[1] and r[0] >= left:
                hits.append(t)
    return hits


def wait_shown(names, t_start, limit=12.0, allow_empty=False, left=None):
    if left is None:
        left = content_left()
    first = None
    root = None
    scrolled = False
    while time.time() - t_start < limit:
        t_dump = time.time()
        root = dump(None)
        hits = names_in(root, names, left)
        empty = allow_empty and find(root, EMPTY_CARD) is not None
        if hits or empty:
            first = (round(t_dump - t_start, 2), hits[:6], bool(empty and not hits), "after one scroll" if scrolled else "no scroll")
            break
        if find(root, r"didn.t load"):
            return None, root, "error card"
        if not scrolled and time.time() - t_start > 2.5:
            scrolled = True
            scroll_down_list(0.35)
            continue
        settle(0.3)
    return first, root, None


def taxonomy_names():
    return [t["name"] for t in scout_data["taxonomies"]]


def expected(tax_path, status):
    return [n for n in scout_data["names"].get(f"{tax_path}|{status}", []) if n not in taxonomy_names()]


def all_expected(status):
    out = []
    for t in scout_data["taxonomies"]:
        out += expected(t["path"], status)
    return out


def phase_c1():
    t_tap, how = open_data()
    if not t_tap:
        root, tag = step("c1-no-data-entry")
        record(1, "Data reachable", "FAIL", f"no Data menu item or tile; on screen {texts(root, 20)}", [tag + ".png"])
        return False
    status0 = STATUSES[0][0]
    names = all_expected(status0)
    first, root, err = wait_shown(names, t_tap, limit=15, allow_empty=True)
    root, tag = step("c1-landing")
    if first and first[0] <= 5.0:
        record(1, f"landing (All) via {how}", "PASS", f"{'empty-state card' if first[2] else 'folders'} shown {first[0]}s after tapping Data; seen {first[1]}", [tag + ".png", tag + ".xml"])
    else:
        record(1, f"landing (All) via {how}", "FAIL", f"first={first} err={err}; on screen {texts(root, 30)}", [tag + ".png", tag + ".xml"])
    for t in scout_data["taxonomies"]:
        scroll_to_top()
        root = dump(None)
        pill = [h for h in find_all(root, rf"^{re.escape(t['name'])}$")]
        if not pill:
            record(1, f"organise by {t['name']}", "FAIL", f"pill not found; on screen {texts(root, 25)}")
            continue
        mark(f"taxonomy {t['path']}")
        t0 = time.time()
        tap(pill[0][1])
        exp = expected(t["path"], status0)
        first, root, err = wait_shown(exp, t0, limit=12, allow_empty=True)
        root, tag = step(f"c1-tax-{t['path']}")
        api_n = len(scout_data["names"].get(f"{t['path']}|{status0}", []))
        ok = first and first[0] <= 5.0 and (first[2] == (len(exp) == 0))
        record(1, f"organise by {t['name']} (All)", "PASS" if ok else "FAIL", f"api entries {api_n}; first={first} err={err}", [tag + ".png", tag + ".xml"])
    if PORTAL == "fr":
        tax = next((t for t in scout_data["taxonomies"] if t.get("default")), scout_data["taxonomies"][0] if scout_data["taxonomies"] else None)
        if tax:
            scroll_to_top()
            tap_text(rf"^{re.escape(tax['name'])}$", timeout=6)
            settle(4)
            for value, lab in STATUSES[1:] + STATUSES[:1]:
                scroll_to_top()
                root = dump(None)
                pill = find(root, rf"^{re.escape(lab)}$")
                if not pill:
                    record(1, f"status {lab}", "FAIL", f"status pill not found; {texts(root, 25)}")
                    continue
                mark(f"status {value}")
                t0 = time.time()
                tap(pill[1])
                exp = expected(tax["path"], value)
                first, root, err = wait_shown(exp, t0, limit=12, allow_empty=True)
                root, tag = step(f"c1-status-{value.lower()}")
                ok = first and first[0] <= 5.0 and (first[2] == (len(exp) == 0))
                record(1, f"status {lab} at {tax['name']}", "PASS" if ok else "FAIL", f"api entries {len(scout_data['names'].get(tax['path'] + '|' + value, []))}; first={first} err={err}", [tag + ".png", tag + ".xml"])
    return True


def tap_folder(name, left):
    for i in range(8):
        root = dump(None)
        hits = [h for h in find_all(root, rf"^{re.escape(name)}$") if h[1][0] >= left]
        if hits:
            hits.sort(key=lambda h: h[1][1])
            tap(hits[-1][1] if PROFILE == "tablet" and len(hits) > 1 and hits[-1][1][0] >= left else hits[0][1])
            return True
        scroll_down_list(0.4)
    return False


def phase_c3():
    chain = scout_data.get("chain")
    if not chain or not chain.get("levels"):
        record(3, "descend chain", "FAIL", "scout found no folder chain")
        return
    go_dashboard()
    t_tap, how = open_data()
    settle(4)
    levels = chain["levels"]
    left = content_left()
    record(3, "chain", "INFO", " > ".join(l["name"] for l in levels))
    tax = levels[0]
    scroll_to_top()
    if not tap_text(rf"^{re.escape(tax['name'])}$", timeout=8):
        record(3, f"taxonomy {tax['name']}", "FAIL", "pill not found")
        return
    mark(f"open {tax['path']}")
    first, root, err = wait_shown([n for n in tax["names"] if n not in taxonomy_names()], time.time(), limit=10, allow_empty=True)
    root, tag = step("c3-level0")
    tablet_check(root, tag, tax["name"], tax["names"])
    opened = 0
    for lvl in levels[1:]:
        scroll_to_top()
        mark(f"open {lvl['path']}")
        t0 = time.time()
        if not tap_folder(lvl["name"], left):
            root, tag = step(f"c3-missing-{opened + 1}")
            record(3, f"open '{lvl['name']}'", "FAIL", f"folder row not found; {texts(root, 25)}", [tag + ".png"])
            break
        first, root, err = wait_shown(lvl["names"], t0, limit=12, allow_empty=True)
        opened += 1
        root, tag = step(f"c3-level{opened}")
        ok = first is not None
        record(3, f"descend to '{lvl['name']}'", "PASS" if ok else "FAIL", f"shown after {first[0] if first else None}s; seen {first[1] if first else None}; err={err}", [tag + ".png", tag + ".xml"])
        tablet_check(root, tag, lvl["name"], lvl["names"])
    for i in range(opened, 0, -1):
        parent = levels[i - 1]
        mark(f"back to {parent['path']}")
        t0 = time.time()
        back()
        first, root, err = wait_shown([n for n in parent["names"] if n not in taxonomy_names()] or parent["names"], t0, limit=10, allow_empty=True)
        root, tag = step(f"c3-back-to-{i - 1}")
        record(3, f"system Back to '{parent['name']}'", "PASS" if first else "FAIL", f"after {first[0] if first else None}s; seen {first[1] if first else None}", [tag + ".png", tag + ".xml"])
        tablet_check(root, tag, parent["name"], parent["names"])
    back()
    root, tag = step("c3-back-out", wait=1)
    record(3, "system Back leaves Data", "PASS" if on_dashboard(root) else "FAIL", f"{texts(root, 15)}", [tag + ".png"])


def tablet_check(root, tag, name, names):
    if PROFILE != "tablet":
        return
    left = content_left()
    folders = [n for n in names if n not in taxonomy_names()]
    hits = names_in(root, folders, left)
    empty = find(root, EMPTY_CARD)
    labelled_right = [label(n) for n in all_nodes(root) if label(n).strip() and rect(n.get("bounds")) and rect(n.get("bounds"))[0] >= left]
    ok = bool(hits) or (not folders and empty)
    record(3, f"tablet content pane at '{name}' is not empty", "PASS" if ok else "FAIL", f"content-pane hits {hits[:5]}; right-pane labels {labelled_right[:12]}", [tag + ".png", tag + ".xml"])


def open_folder_path_via_link(path):
    url = f"{WEB}/data?path={urllib.parse.quote(path, safe='')}"
    sh(f"am start -W -a android.intent.action.VIEW -d {shlex.quote(url)} {PKG}", check=False, timeout=60)


def phase_c4_file():
    target = scout_data.get("image") or scout_data.get("file")
    if not target:
        record(4, "tap a file", "FAIL", "scout found no file")
        return
    go_dashboard()
    open_data()
    settle(4)
    folder = target["folder"]
    levels = []
    tr = scout_data["folders"].get(folder)
    tax = next((t for t in scout_data["taxonomies"] if folder.startswith(t["path"] + "/") or folder == t["path"]), None)
    if tax:
        scroll_to_top()
        tap_text(rf"^{re.escape(tax['name'])}$", timeout=8)
        settle(4)
        parts = folder[len(tax["path"]):].strip("/").split("/") if folder != tax["path"] else []
        cur = tax["path"]
        for p in parts:
            cur = f"{cur}/{p}"
            crumbs = scout_data["folders"].get(cur, {}).get("crumbs") or []
            nm = crumbs[-1] if crumbs else p
            levels.append(nm)
            tap_folder(nm, content_left())
            settle(5)
    root = None
    hit = None
    for i in range(10):
        root = dump(None)
        hit = find(root, rf"^{re.escape(target['name'])}$")
        if hit:
            break
        scroll_down_list(0.4)
    if not hit:
        root, tag = step("c4-file-not-found")
        record(4, "tap a file row", "FAIL", f"file '{target['name']}' not on screen after {levels}; {texts(root, 25)}", [tag + ".png", tag + ".xml"])
        return
    root, tag0 = step("c4-file-row")
    mark("tap file")
    tap(hit[1])
    root, tag1 = step("c4-file-open-2s", wait=2)
    root, tag = step("c4-file-open-8s", wait=6)
    still_list = find(root, rf"^{re.escape(target['name'])}$") is not None and find(root, r"^Jump to a record$") is not None
    n = text_count(root)
    ok = n >= 6 and not still_list
    record(4, f"tap file '{target['name']}'", "PASS" if ok else "FAIL", f"{n} labelled nodes; on screen {texts(root, 18)}", [tag0 + ".png", tag1 + ".png", tag + ".png", tag + ".xml"])
    back()
    root, tag = step("c4-file-back", wait=2)
    record(4, "Back from the opened file", "PASS" if find(root, r"^(View data|Jump to a record)$") else "FAIL", f"{texts(root, 15)}", [tag + ".png"])


def type_jump(term):
    scroll_to_top()
    root = dump(None)
    clear = find(root, r"^Clear$")
    if clear:
        tap(clear[1])
        settle(1)
    if not tap_text(r"^Jump to a record$", timeout=8):
        return False
    settle(0.8)
    sh("input text " + shlex.quote(term))
    return True


def phase_c4_search():
    go_dashboard()
    open_data()
    settle(4)
    jumped = False
    for term in CFG["jump"]:
        if not type_jump(term):
            record(4, "quick jump field", "FAIL", "Jump to a record field not found")
            break
        root, tag = step(f"c4-jump-{term}", wait=5)
        hide_ime()
        root = dump(None)
        skip = set(["Clear", "Everything", "Artisans", "Workshops", "Products", "View data", "Downloads", "Back", term]) | set(taxonomy_names())
        rows = [(n, r) for n, r in find_all(root, re.escape(term)) if label(n).strip() not in skip and not label(n).startswith("Search everything") and not label(n).startswith("No matches") and not label(n).startswith("Show all") and r[1] > 200]
        if not rows:
            record(4, f"quick jump '{term}'", "INFO", f"no hits: {texts(root, 20)}", [tag + ".png"])
            continue
        title = label(rows[0][0]).strip()
        mark(f"jump open {title}")
        tap(rows[0][1])
        root, tag2 = step(f"c4-jump-open-{term}-3s", wait=3)
        root, tag3 = step(f"c4-jump-open-{term}-9s", wait=6)
        found_title = find(root, rf"^{re.escape(title)}") is not None
        notice = find(root, r"isn.t filed in a folder yet")
        n = text_count(root)
        ok = found_title and n >= 6 and not notice
        record(4, f"quick jump '{term}' opens '{title}'", "PASS" if ok else "FAIL", f"title on screen={found_title}; unfiled notice={bool(notice)}; {texts(root, 22)}", [tag + ".png", tag2 + ".png", tag3 + ".png", tag3 + ".xml"])
        jumped = True
        back()
        break
    if not jumped:
        record(4, "quick jump", "NT", f"no quick-jump hits in production for {CFG['jump']}")
    go_dashboard()
    open_data()
    settle(4)
    opened = 0
    for term in CFG["everything"]:
        if not type_jump(term):
            break
        settle(3)
        hide_ime()
        if not tap_text(r"^Search everything", timeout=6):
            sh("input keyevent KEYCODE_ENTER")
        root, tag = step(f"c4-everything-{term}", wait=7)
        tabs = r"^(All|Artisans|Crafts|Media|Interviews|Transcripts|Workshops|Products|Design workshops)\s\d+$"
        cards = []
        for n, r in find_all(root, re.escape(term)):
            t = label(n).strip()
            if t == term or re.match(tabs, t) or t.startswith("Search") or r[1] < 300:
                continue
            cards.append((t, r))
        if not cards:
            record(4, f"search everything '{term}'", "INFO", f"no result cards: {texts(root, 25)}", [tag + ".png"])
            back()
            continue
        title, r = cards[0]
        mark(f"everything open {title}")
        tap(r)
        root, tag2 = step(f"c4-everything-open-{term}-3s", wait=3)
        root, tag3 = step(f"c4-everything-open-{term}-9s", wait=6)
        notice = find(root, r"isn.t filed in a folder yet")
        same = find(root, r"^(This app|Both portals)$") is not None
        hit_title = find(root, re.escape(title[:40])) is not None
        n = text_count(root)
        ok = n >= 6 and not same and not notice and hit_title
        record(4, f"search everything '{term}' opens '{title}'", "PASS" if ok else "FAIL", f"left search={not same}; title on screen={hit_title}; unfiled notice={label(notice[0]) if notice else None}; {texts(root, 22)}", [tag + ".png", tag2 + ".png", tag3 + ".png", tag3 + ".xml"])
        opened += 1
        back()
        settle(1)
        if opened >= 2:
            break
    if not opened:
        record(4, "search everything", "NT", f"no results in production for {CFG['everything']}")


def deeplink(url, name):
    mark(f"deeplink {name}")
    sh(f"am start -W -a android.intent.action.VIEW -d {shlex.quote(url)} {PKG}", check=False, timeout=60)
    root, tag1 = step(f"c5-{name}-2s", wait=2)
    root2, tag2 = step(f"c5-{name}-8s", wait=6)
    return root, root2, [tag1 + ".png", tag1 + ".xml", tag2 + ".png", tag2 + ".xml"]


def phase_c5():
    go_dashboard()
    img = scout_data.get("image")
    folder = (img or scout_data.get("file") or {}).get("folder")
    if folder:
        names = scout_data["folders"].get(folder, {}).get("names", [])
        _, root, ev = deeplink(f"{WEB}/data?path={urllib.parse.quote(folder, safe='')}", "path")
        hits = names_in(root, names, content_left())
        record(5, f"/data?path={folder}", "PASS" if hits else "FAIL", f"folder entries on screen {hits[:4]}; {texts(root, 18)}", ev)
        back()
    if img and img.get("mediaId"):
        go_dashboard()
        url = f"{WEB}/data?path={urllib.parse.quote(img['folder'], safe='')}&preview={img['mediaId']}"
        root1, root, ev = deeplink(url, "preview-image")
        image_node = find(root, rf"^{re.escape(img['name'])}$")
        img_desc = [n for n in all_nodes(root) if (n.get("content-desc") or "") == img["name"]]
        video = find(root, r"^(Play|Pause|Follow video)$")
        raw_title = find(root, rf"^{re.escape(img['mediaId'])}$")
        preview_open = find(root, r"^Close$") is not None
        ok = preview_open and bool(img_desc) and not video and not raw_title
        record(5, f"?preview=<image {img['mediaId']}>", "PASS" if ok else "FAIL", f"preview open={preview_open}; image node (content-desc=file name)={bool(img_desc)}; video controls={label(video[0]) if video else None}; raw-id title={bool(raw_title)}; {texts(root, 16)}", ev)
        back()
        back()
    go_dashboard()
    root1, root, ev = deeplink(f"{WEB}/data?viewId=bogus-accept-0015", "viewid-bogus")
    msg1 = find(root1, r"saved view isn.t available")
    msg2 = find(root, r"saved view isn.t available")
    msg = msg1 or msg2
    probe = root1 if msg1 else root
    problem = find(probe, r"^Problem$")
    done = find(probe, r"^Done$")
    ok = bool(msg) and bool(problem) and not done
    record(5, "?viewId=bogus", "PASS" if ok else "FAIL", f"message={label(msg[0]) if msg else None}; icon Problem={bool(problem)}; icon Done={bool(done)}; 2s {texts(root1, 14)}", ev)
    back()
    art = scout_data.get("artisan")
    if art and art.get("id"):
        go_dashboard()
        root1, root, ev = deeplink(f"{WEB}/data/go/open/artisan/{art['id']}", "go-open-artisan")
        shown = find(root, re.escape(art.get("name") or "@@none@@")) is not None
        err = find(root, r"(link didn.t open|isn.t one the app can open)")
        n = text_count(root)
        record(5, f"/data/go/open/artisan/{art['id']}", "PASS" if shown and not err and n >= 8 else "FAIL", f"artisan name '{art.get('name')}' on screen={shown}; error={label(err[0]) if err else None}; {texts(root, 18)}", ev)
        back()
    else:
        record(5, "/data/go/open/artisan/<id>", "NT", "production has no artisan for this account")
        craft = scout_data.get("craft")
        if craft:
            go_dashboard()
            root1, root, ev = deeplink(f"{WEB}/data/go/open/craft/{craft['id']}", "go-open-craft")
            shown = find(root, re.escape(craft.get("name") or "@@none@@")) is not None
            err = find(root, r"(link didn.t open|isn.t one the app can open|can.t open)")
            record(5, f"substitute /data/go/open/craft/{craft['id']}", "INFO", f"craft name on screen={shown}; error={label(err[0]) if err else None}; {texts(root, 18)}", ev)
            back()


def folders_visible(root):
    return names_in(root, all_expected(STATUSES[0][0]) + [n for f in scout_data["folders"].values() for n in f["names"]], 0)


def phase_c6():
    go_dashboard()
    open_data()
    settle(5)
    tax = next((t for t in scout_data["taxonomies"] if expected(t["path"], STATUSES[0][0])), None)
    if tax:
        scroll_to_top()
        tap_text(rf"^{re.escape(tax['name'])}$", timeout=8)
        settle(4)
    root, tag = step("c6-before-rotate")
    before = folders_visible(root)
    sh("settings put system accelerometer_rotation 0", check=False)
    sh("settings put system user_rotation 1", check=False)
    mark("rotate landscape")
    root, tag1 = step("c6-rotated", wait=5)
    hits = folders_visible(root)
    record(6, "rotate to landscape", "PASS" if hits else "FAIL", f"before {before[:4]}; after {hits[:4]}; {texts(root, 14)}", [tag + ".png", tag1 + ".png", tag1 + ".xml"])
    sh("settings put system user_rotation 0", check=False)
    root, tag2 = step("c6-rotated-back", wait=5)
    hits = folders_visible(root)
    record(6, "rotate back to natural", "PASS" if hits else "FAIL", f"{hits[:4]}; {texts(root, 14)}", [tag2 + ".png", tag2 + ".xml"])
    sh("settings put system font_scale 2.0", check=False)
    mark("font scale 2")
    root, tag3 = step("c6-font2-same-screen", wait=6)
    hits = folders_visible(root)
    if not hits:
        scroll_down_list(0.4)
        root, tag3 = step("c6-font2-same-screen-scrolled", wait=2)
        hits = folders_visible(root)
    record(6, "font scale 2 on Data", "PASS" if hits else "FAIL", f"{hits[:4]}; {texts(root, 14)}", [tag3 + ".png", tag3 + ".xml"])
    go_dashboard()
    t_tap, how = open_data()
    if t_tap:
        settle(6)
        root, tag4 = step("c6-font2-reopen")
        hits = folders_visible(root)
        if not hits:
            scroll_down_list(0.4)
            root, tag4 = step("c6-font2-reopen-scrolled", wait=2)
            hits = folders_visible(root)
        record(6, f"font scale 2, Data reopened via {how}", "PASS" if hits else "FAIL", f"{hits[:4]}; {texts(root, 14)}", [tag4 + ".png", tag4 + ".xml"])
    else:
        root, tag4 = step("c6-font2-no-entry")
        record(6, "font scale 2, Data reopened", "FAIL", f"Data entry not reachable at font scale 2; {texts(root, 20)}", [tag4 + ".png", tag4 + ".xml"])
    sh("settings put system font_scale 1.0", check=False)
    settle(3)


soak_marks = []


def phase_soak(heap):
    go_dashboard()
    open_data()
    settle(5)
    heap.sample("soak-start")
    start = time.time()
    cycle = 0
    taxes = [t for t in scout_data["taxonomies"]]
    chain = (scout_data.get("chain") or {}).get("levels") or []
    while time.time() - start < SOAK_SECONDS:
        cycle += 1
        if taxes:
            t = taxes[cycle % len(taxes)]
            scroll_to_top()
            mark(f"soak-open taxonomy {t['path']}")
            tap_text(rf"^{re.escape(t['name'])}$", timeout=6)
            settle(6)
            if len(chain) > 1 and t["path"] == chain[0]["path"]:
                mark(f"soak-open folder {chain[1]['path']}")
                if tap_folder(chain[1]["name"], content_left()):
                    settle(6)
                    mark("soak-back")
                    back()
                    settle(4)
        if PORTAL == "fr" and cycle % 3 == 0:
            lab = STATUSES[cycle % len(STATUSES)][1]
            scroll_to_top()
            mark(f"soak-status {lab}")
            tap_text(rf"^{re.escape(lab)}$", timeout=6)
            settle(6)
        mark(f"soak-idle {cycle}")
        idle_end = time.time() + 45
        while time.time() < idle_end:
            time.sleep(5)
        if cycle % 4 == 0:
            root, tag = step(f"soak-cycle-{cycle}")
        if not alive():
            incidents.append(f"app not running during soak cycle {cycle}")
            launch()
            open_data()
            settle(5)
    mark("soak-end")
    heap.sample("soak-end")
    root, tag = step("soak-end")
    return round(time.time() - start)


LINE = re.compile(r"^(\d\d-\d\d) (\d\d:\d\d:\d\d)\.\d+\s+\d+\s+\d+\s+\w\s+(\S+)\s*:\s?(.*)$")


def analyse_requests(raw):
    segments = []
    cur = {"name": "start", "tree": 0, "languages": 0, "other": 0, "first": None}
    minutes = {}
    oks = 0
    for line in raw.splitlines():
        m = LINE.match(line)
        if not m:
            continue
        tagname, msg = m.group(3), m.group(4)
        if tagname == "ACCEPT" and msg.startswith("MARK "):
            segments.append(cur)
            cur = {"name": msg[5:], "tree": 0, "languages": 0, "other": 0, "first": m.group(2)}
            continue
        if tagname.startswith("OkHttp") and msg.startswith("--> "):
            oks += 1
            minute = m.group(2)[:5]
            b = minutes.setdefault(minute, {"tree": 0, "languages": 0, "all": 0, "segment": cur["name"]})
            b["all"] += 1
            if "/data/tree" in msg:
                cur["tree"] += 1
                b["tree"] += 1
            elif "/data/transcripts/languages" in msg:
                cur["languages"] += 1
                b["languages"] += 1
            else:
                cur["other"] += 1
    segments.append(cur)
    return segments, minutes, oks


def finish_analysis(raw, heap, soak_seconds):
    segments, minutes, oks = analyse_requests(raw)
    save("okhttp-segments.json", json.dumps(segments, indent=1, ensure_ascii=False))
    save("okhttp-per-minute.json", json.dumps(minutes, indent=1, ensure_ascii=False))
    ok_lines = [l for l in raw.splitlines() if " OkHttp" in l and "--> " in l or "ACCEPT" in l]
    save("okhttp-requests.txt", "\n".join(ok_lines))
    if oks == 0:
        record(2, "OkHttp logging present", "FAIL", "no OkHttp request lines in logcat; counts cannot be trusted")
        return
    tree_total = sum(s["tree"] for s in segments)
    lang_total = sum(s["languages"] for s in segments)
    opens = [s for s in segments if s["name"].startswith(("open ", "taxonomy ", "status ", "soak-open", "soak-status", "back to", "deeplink", "open-data"))]
    worst_tree = max((s["tree"] for s in opens), default=0)
    worst_lang = max((s["languages"] for s in opens), default=0)
    idles = [s for s in segments if s["name"].startswith("soak-idle")]
    idle_tree = sum(s["tree"] for s in idles)
    idle_lang = sum(s["languages"] for s in idles)
    peak_min = max(minutes.items(), key=lambda kv: kv[1]["tree"] + kv[1]["languages"], default=(None, {"tree": 0, "languages": 0}))
    detail = (f"total /data/tree {tree_total}, /data/transcripts/languages {lang_total} over the whole session ({oks} OkHttp requests); "
              f"worst single folder-open segment: tree {worst_tree}, languages {worst_lang}; soak idle windows ({len(idles)} x 45 s): tree {idle_tree}, languages {idle_lang}; "
              f"peak minute {peak_min[0]}: tree {peak_min[1]['tree']}, languages {peak_min[1]['languages']}")
    bounded = worst_tree <= 4 and worst_lang <= 2 and idle_tree == 0 and idle_lang <= 1 and (peak_min[1]["tree"] + peak_min[1]["languages"]) <= 40
    record(2, "request counts bounded, no storm", "PASS" if bounded else "FAIL", detail, ["okhttp-segments.json", "okhttp-per-minute.json", "okhttp-requests.txt", "logcat.txt"])
    rows = [r for r in heap.rows if r.get("javaKB")]
    soak_rows = rows
    if rows:
        start = [r for r in rows if r.get("note") == "soak-start"] or rows[:1]
        end = [r for r in rows if r.get("note") == "soak-end"] or rows[-1:]
        js = start[0]["javaKB"]
        je = end[-1]["javaKB"]
        peak = max(r["javaKB"] for r in rows)
        pss_s = start[0].get("pssKB")
        pss_e = end[-1].get("pssKB")
        flat = je <= js * 1.5 + 20000 and peak < 300000
        record(2, "heap flat over the soak", "PASS" if flat else "FAIL", f"soak {soak_seconds}s; Java heap {js} KB -> {je} KB (peak {peak} KB over {len(rows)} samples); total PSS {pss_s} -> {pss_e} KB", ["heap.csv"])
    oom = [l for l in raw.splitlines() if "OutOfMemoryError" in l]
    anr = [l for l in raw.splitlines() if re.search(rf"ANR in {re.escape(PKG)}", l)]
    fatal = [l for l in raw.splitlines() if "FATAL EXCEPTION" in l]
    ok = not oom and not anr and not fatal and not incidents
    record(2, "no OOM, ANR or crash", "PASS" if ok else "FAIL", f"OOM lines {len(oom)}; ANR lines {len(anr)}; FATAL {len(fatal)}; incidents {incidents[:6]}", ["logcat.txt", "logcat-crash.txt"])


def run(name, fn, *a):
    try:
        return fn(*a)
    except Exception as exc:
        log(f"phase {name} raised: {exc}")
        record(0, f"phase {name} raised", "ERROR", "".join(traceback.format_exception(exc))[-900:])
        shot(f"phase-error-{name}")


def main():
    if not (EMAIL and PASSWORD):
        record(0, "secrets present", "FAIL", "GATE_EMAIL / GATE_PASSWORD missing")
        return 1
    adb("logcat", "-c", check=False)
    adb("logcat", "-b", "crash", "-c", check=False)
    sh("logcat -G 16M", check=False)
    logf = open(os.path.join(OUT, "logcat-raw.txt"), "wb")
    logcat = subprocess.Popen(["adb", "logcat", "-v", "threadtime"], stdout=logf, stderr=subprocess.STDOUT)
    for k in ("window_animation_scale", "transition_animation_scale", "animator_duration_scale"):
        sh(f"settings put global {k} 1", check=False)
    sh("svc power stayon true", check=False)
    sh("input keyevent KEYCODE_WAKEUP", check=False)
    sh("wm dismiss-keyguard", check=False)
    sh("settings put system font_scale 1.0", check=False)
    save("device.txt", sh("getprop ro.build.version.release") + sh("wm size") + sh("wm density"))
    heap = Heap()
    rec = Recorder()
    soak_seconds = 0
    try:
        scout()
        install()
        launch()
        if not login():
            root, tag = step("login-failed", check=False)
            record(0, "sign in as master admin", "FAIL", f"{texts(root, 20)}", [tag + ".png"])
            return 1
        record(0, "sign in as master admin", "PASS", "")
        rec.start()
        heap.start()
        run("c1", phase_c1)
        run("c3", phase_c3)
        run("c4-file", phase_c4_file)
        run("c4-search", phase_c4_search)
        run("c5", phase_c5)
        run("c6", phase_c6)
        soak_seconds = run("soak", phase_soak, heap) or 0
    finally:
        sh("settings put system font_scale 1.0", check=False)
        sh("settings put system user_rotation 0", check=False)
        heap.stop = True
        rec.finish()
        logcat.terminate()
        logf.close()
        with open(os.path.join(OUT, "logcat-raw.txt"), "rb") as fh:
            raw = fh.read().decode(errors="replace")
        os.remove(os.path.join(OUT, "logcat-raw.txt"))
        save("logcat.txt", raw)
        save("logcat-crash.txt", adb("logcat", "-d", "-b", "crash", check=False))
        save("app-pid-lines.txt", "\n".join(l for l in raw.splitlines() if re.search(r"(FATAL|AndroidRuntime|ANR in|OutOfMemory|StrictMode|Choreographer.*Skipped)", l)))
        try:
            finish_analysis(raw, heap, soak_seconds)
        except Exception as exc:
            record(2, "analysis", "ERROR", "".join(traceback.format_exception(exc))[-900:])
        save_json()
    return 0


if __name__ == "__main__":
    sys.exit(main())
