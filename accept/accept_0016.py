#!/usr/bin/env python3
import json
import os
import re
import subprocess
import sys
import threading
import time
import traceback
import urllib.parse

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import accept_0015 as A
import accept_v2 as V
from accept_0015 import (adb, sh, settle, dump, shot, step, find, find_all, all_nodes, label, rect, texts, tap,
                         screen_size, swipe, back, hide_ime, mark, record, names_in, scout_data, api,
                         go_dashboard, on_dashboard, PORTAL, PKG, WEB, OUT, STATUSES, EMPTY_CARD, incidents, alive, launch, entry_names)

try:
    from PIL import Image
except Exception:
    Image = None

HERE = os.path.dirname(os.path.abspath(__file__))
PANE = [None]
DENS = [None]
ONLY = [p for p in os.environ.get("ONLY", "").split(",") if p]
STALE = 0.15


def dens():
    if DENS[0] is None:
        DENS[0] = A.density()
    return DENS[0]


def dp(v):
    return v * dens() / 160.0


def content_left():
    if A.PROFILE != "tablet":
        return 0
    w, h = screen_size()
    if w < h:
        return 0
    if PANE[0]:
        return PANE[0]
    return int(dp(340 + 48))


A.content_left = content_left
V.content_left = content_left


def stem(n):
    return os.path.splitext(n)[0] if re.search(r"\.[A-Za-z0-9]{2,4}$", n or "") else n


def names_in(root, names, left=0):
    want = {}
    for n in names:
        if n:
            want[n] = n
            want[stem(n)] = n
    hits = []
    for nd in all_nodes(root):
        t = label(nd).strip()
        if t in want:
            r = rect(nd.get("bounds"))
            if r and r[2] > r[0] and r[3] > r[1] and r[0] >= left:
                hits.append(want[t])
    return hits


A.names_in = names_in
V.names_in = names_in
_orig_wait = V.wait_loaded


def wait_loaded(name, names, t0, limit=12.0, left=None, empty_ok=True):
    got, root = _orig_wait(name, names, t0, limit=min(limit, 4.0), left=left, empty_ok=empty_ok)
    if got:
        return got, root
    A.scroll_down_list(0.35)
    got, root = _orig_wait(name, names, time.time(), limit=max(limit - 4.0, 3.0), left=left, empty_ok=empty_ok)
    if got and got.get("t") is not None:
        got["t"] = round(time.time() - t0, 2)
        got["note"] = "after one scroll"
    return got, root


V.wait_loaded = wait_loaded
_orig_opened = V.opened_ok


def opened_ok(root, title):
    kind = _orig_opened(root, title)
    if kind:
        return kind
    if find(root, r"^View data$") and not find(root, r"^Search everything") and any(V.COUNT.match(t) for t, _ in V.labelled(root)):
        return "its folder"
    return None


V.opened_ok = opened_ok


def open_data(tag=""):
    go_dashboard()
    if A.tap_text(r"^Open menu$", timeout=10):
        settle(1.5)
        root = dump(None)
        x = V.menu_x(root)
        w, h = screen_size()
        for i in range(16):
            hit = find(root, r"^Data$")
            if hit:
                mark("open-data-menu")
                t = time.time()
                tap(hit[1])
                return t, "menu" + (f" after {i} drawer scrolls" if i else "")
            if tag:
                root, tg = step(f"{tag}-drawer-{i}")
                V.drawer_shots.append(tg + ".png")
            if i < 4:
                swipe(x, int(h * 0.35), int(h * 0.75), 400)
            else:
                swipe(x, int(h * 0.75), int(h * 0.45), 500)
            root = dump(None)
        back()
    root = go_dashboard()
    if A.tap_text(r"^(View Data|Browse records)$", timeout=6, scroll=6):
        settle(3)
        hit = None
        for _ in range(6):
            root = dump(None)
            hit = find(root, r"^(Open Data|Open the Data Browser)$")
            if hit:
                break
            A.scroll_down_list(0.4)
        if hit:
            mark("open-data-tile")
            t = time.time()
            tap(hit[1])
            return t, "tile"
    if tag:
        root, tg = step(f"{tag}-no-entry")
        V.drawer_shots.append(tg + ".png")
    return None, None


V.open_data = open_data
A.open_data = open_data


def reveal_tabs():
    root = dump(None)
    for _ in range(4):
        if tab_state(root):
            return root
        A.scroll_down_list(0.3)
        root = dump(None)
    return root


def reveal_files(names, left, skip_rows=0):
    w, h = screen_size()
    root = dump(None)
    for i in range(8):
        rows = [r for r in file_rows(root, names, left) if r[1][1] > h * 0.15 and r[1][3] < h * 0.85]
        if len(rows) > skip_rows:
            return rows, root, i
        A.scroll_down_list(0.3)
        root = dump(None)
    return [], root, 8


def divider(root):
    hit = find(root, r"^Resize folders panel$")
    return hit[1] if hit else None


def measure_pane(root=None):
    root = root if root is not None else dump(None)
    r = divider(root)
    if r:
        PANE[0] = r[2]
    return r


def ev(tag):
    return [tag + ".png", tag + ".xml"]


def png(tag):
    return os.path.join(OUT, tag + ".png")


def node_at(root, pattern, left=0):
    hits = [h for h in find_all(root, pattern) if h[1][0] >= left]
    hits.sort(key=lambda h: (h[1][1], h[1][0]))
    return hits[0] if hits else None


def tab_state(root):
    out = {}
    for n in all_nodes(root):
        t = label(n).strip()
        m = re.match(r"^(Files|Tables|Transcripts)\b", t)
        if m and rect(n.get("bounds")):
            sel = n.get("selected") == "true"
            out[m.group(1)] = out.get(m.group(1), False) or sel
    if not any(out.values()):
        for n in root.iter("node") if root is not None else []:
            if n.get("selected") == "true":
                for c in n.iter("node"):
                    m = re.match(r"^(Files|Tables|Transcripts)\b", label(c).strip())
                    if m:
                        out[m.group(1)] = True
    return out


def selected_tab(root):
    st = tab_state(root)
    on = [k for k, v in st.items() if v]
    return on[0] if on else None


def file_rows(root, names, left):
    want = {}
    for x in names:
        want[x] = x
        want[stem(x)] = x
    rows = []
    for n in all_nodes(root):
        t = label(n).strip()
        r = rect(n.get("bounds"))
        if t in want and r and r[0] >= left and r[2] > r[0] and r[3] > r[1]:
            rows.append((want[t], r))
    rows.sort(key=lambda x: x[1][1])
    return rows


def open_folder(levels):
    go_dashboard()
    t, how = V.open_data()
    if not t:
        return False
    settle(4)
    if A.PROFILE == "tablet":
        measure_pane()
    tax = levels[0]
    if not V.tap_pill(tax["name"]):
        return False
    settle(4)
    for lvl in levels[1:]:
        if not V.tap_folder(lvl["name"], content_left()):
            return False
        got, root = V.wait_loaded(lvl["name"], lvl["names"], time.time(), limit=15)
        if not got:
            settle(3)
    return True


def by_type_levels(token):
    path = f"by-type/{token}"
    tr = api.tree(path)
    if not tr:
        return None, []
    root = api.tree("by-type")
    files = [e for e in tr.get("entries", []) if e.get("kind") != "folder" and e.get("name")]
    lv = [{"name": next((t["name"] for t in scout_data["taxonomies"] if t["path"] == "by-type"), "By media type"), "path": "by-type", "names": entry_names(root)},
          {"name": next((e.get("name") for e in (root or {}).get("entries", []) if e.get("path") == path), token.title()), "path": path, "names": entry_names(tr)}]
    return lv, files


def backend_probe(tag):
    t = time.time()
    tr = api.tree("")
    d1 = round(time.time() - t, 2)
    t = time.time()
    s, _ = api.call("/dashboard/stats")
    d2 = round(time.time() - t, 2)
    record("backend", f"production API latency ({tag})", "INFO", f"/data/tree root {'ok' if tr else 'failed'} in {d1}s; /dashboard/stats {s} in {d2}s")


def scout_0016():
    backend_probe("start")
    extra = {}
    for token in ("images", "audios", "videos", "documents", "transcripts"):
        lv, files = by_type_levels(token)
        extra[token] = {"levels": lv, "files": [{"name": f.get("name"), "mediaId": f.get("mediaId"), "mediaType": f.get("mediaType"), "sizeBytes": f.get("sizeBytes")} for f in files[:60]], "count": len(files)}
    scout_data["bytype"] = extra
    s, crafts = api.call("/crafts?" + urllib.parse.urlencode({"page": 1, "pageSize": 50, "search": "ka"}))
    names = []
    if s == 200:
        items = crafts.get("items") if isinstance(crafts, dict) else crafts
        for c in items or []:
            if isinstance(c, dict) and c.get("name"):
                names.append(c["name"])
    scout_data["crafts_ka"] = {"status": s, "names": names[:50]}
    A.save("scout.json", json.dumps(scout_data, indent=1, ensure_ascii=False))


_orig_scout = A.scout


def scout():
    _orig_scout()
    V.scout_more()
    scout_0016()


def ime_top(root):
    ys = [rect(n.get("bounds"))[1] for n in (root.iter("node") if root is not None else []) if "inputmethod" in (n.get("package") or "") and rect(n.get("bounds"))]
    if ys:
        return min(ys), "uiautomator"
    out = sh("dumpsys window InputMethod", check=False)
    for key in ("mFrame=", "frame="):
        for m in re.finditer(re.escape(key) + r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", out):
            x1, y1, x2, y2 = map(int, m.groups())
            w, h = screen_size()
            if y2 >= h - 2 and y1 > h * 0.3:
                return y1, "dumpsys " + key
    m = re.search(r"touchable region=SkRegion\(\((\d+),(\d+),(\d+),(\d+)\)", out)
    if m:
        return int(m.group(2)), "touchable"
    return None, "unknown"


def login_with_ime_check():
    deadline = time.time() + 120
    root = None
    while time.time() < deadline:
        root = dump(None)
        if find(root, r"^Open menu$"):
            record("login", "login visible with the keyboard up", "NT", "already signed in")
            return A.login()
        if len([n for n in all_nodes(root) if n.get("class", "").endswith("EditText")]) >= 2:
            break
        if not A.dismiss_prompts(root):
            settle(2)
    fields = [rect(n.get("bounds")) for n in all_nodes(root) if n.get("class", "").endswith("EditText")]
    if len(fields) >= 2:
        tap(fields[1])
        settle(3)
        root, tg = step("login-ime-up", check=False)
        shown = A.ime_shown()
        top, how = ime_top(root)
        btn = find(root, r"^Login$")
        w, h = screen_size()
        if btn and top:
            ok = shown and btn[1][3] <= top + 1 and btn[1][1] >= 0
            detail = f"keyboard shown={shown}; Login button {btn[1]}; keyboard top y={top} ({how}); screen {w}x{h}"
        else:
            ok = False
            detail = f"keyboard shown={shown}; Login node={btn[1] if btn else None}; keyboard top={top} ({how}); {texts(root, 14)}"
        record("login", "login visible with the keyboard up", "PASS" if ok else "FAIL", detail, ev(tg))
        hide_ime()
        settle(1)
    return A.login()


def check_header_back(tag):
    root, tg = step(tag)
    w, h = screen_size()
    backs = [r for _, r in find_all(root, r"^Back$") if r[1] < h * 0.2]
    title = find(root, r"^View data$")
    record("wide", f"{A.PROFILE} Data header has Back", "PASS" if backs and title else "FAIL", f"Back nodes in the header {backs}; title {title[1] if title else None}", ev(tg))
    return root


def phase_landing():
    go_dashboard()
    t, how = V.open_data("land")
    if not t:
        record(1, "Data reachable", "FAIL", "no Data menu item or tile", V.drawer_shots[-6:])
        return
    default = next((x for x in scout_data["taxonomies"] if x.get("default")), scout_data["taxonomies"][0])
    names = scout_data["names"].get(f"{default['path']}|{STATUSES[0][0]}", [])
    got, root = V.wait_loaded(default["name"], names, t, limit=15, left=0)
    if A.PROFILE == "tablet":
        r = measure_pane(root)
        root, tg = step("land-tablet")
        folders = [n for n in names if n not in V.TAB_WORDS]
        hits = names_in(root, folders, content_left())
        empty = find(root, EMPTY_CARD)
        right = [t for t, _ in V.labelled(root, content_left())][:14]
        ok = bool(hits) or (not folders and empty)
        record("wide", "tablet landing shows content in the content pane", "PASS" if ok else "FAIL", f"default '{default['name']}'; divider {r}; content-pane rows {hits[:5]}; right-pane labels {right}", ev(tg))
    check_header_back("land-header")


def phase_pane():
    if A.PROFILE != "tablet":
        return
    go_dashboard()
    V.open_data()
    settle(5)
    root, tg0 = step("pane-before")
    r0 = divider(root)
    if not r0:
        record("pane", "tablet pane divider present", "FAIL", f"no 'Resize folders panel' node; {texts(root, 20)}", ev(tg0))
        return
    w, h = screen_size()
    x0 = (r0[0] + r0[2]) // 2
    y = (r0[1] + r0[3]) // 2
    dx = int(dp(160))
    mark("pane drag")
    sh(f"input swipe {x0} {y} {x0 + dx} {y} 1800")
    settle(2.5)
    root, tg1 = step("pane-after-drag")
    r1 = divider(root)
    moved = (r1[0] - r0[0]) if r1 else None
    left = r1[2] if r1 else None
    rows = names_in(root, [n for f in scout_data["folders"].values() for n in f["names"]], left or 0)
    record("pane", "tablet pane drags", "PASS" if moved and moved >= dx * 0.6 else "FAIL", f"divider {r0} -> {r1}; moved {moved}px for a {dx}px drag; content rows after drag {rows[:4]}", [tg0 + ".png", tg1 + ".png", tg1 + ".xml"])
    if r1:
        PANE[0] = r1[2]
    mark("pane restart")
    sh(f"am force-stop {PKG}", check=False)
    settle(2)
    launch()
    settle(4)
    root = dump(None)
    if not find(root, r"^Open menu$"):
        A.login()
    go_dashboard()
    V.open_data()
    settle(6)
    root, tg2 = step("pane-after-restart")
    r2 = divider(root)
    same = bool(r1 and r2 and abs(r2[0] - r1[0]) <= 4)
    record("pane", "pane width survives an app restart", "PASS" if same else "FAIL", f"after drag {r1}; after force-stop and relaunch {r2}", [tg1 + ".png", tg2 + ".png", tg2 + ".xml"])
    if r2:
        PANE[0] = r2[2]


def preview_kind(root, entry):
    close = find(root, r"^Close$") is not None
    mt = (entry.get("mediaType") or "").upper()
    meta = find(root, r"^(Image|Audio|Video|Pdf|File)( · |$)")
    play = find(root, r"^(Play|Pause)$")
    img = [n for n in all_nodes(root) if (n.get("content-desc") or "") == entry["name"]]
    still_list = find(root, r"^Jump to a record$") is not None
    return {"close": close, "meta": label(meta[0]) if meta else None, "play": label(play[0]) if play else None, "imageNode": bool(img), "listUnder": still_list, "type": mt}


def phase_previews():
    bt = scout_data.get("bytype", {})
    for token, mt in (("images", "IMAGE"), ("audios", "AUDIO"), ("videos", "VIDEO"), ("documents", "PDF")):
        info = bt.get(token) or {}
        files = [f for f in info.get("files", []) if f.get("name")]
        if not files or not info.get("levels"):
            record("preview", f"{mt.lower()} preview from a row tap", "NT", f"production has no {token} in by-type for this account")
            continue
        if not open_folder(info["levels"]):
            root, tg = step(f"pv-{token}-nofolder")
            record("preview", f"{mt.lower()} preview from a row tap", "FAIL", f"could not open {info['levels'][-1]['path']}; {texts(root, 20)}", ev(tg))
            continue
        left = content_left()
        names = [f["name"] for f in files]
        settle(3)
        rows, root, scrolled = reveal_files(names, left, skip_rows=3 if len(files) > 8 else 0)
        if len(files) > 8 and len(rows) > 3:
            rows = rows[3:]
        if not rows:
            root, tg = step(f"pv-{token}-norows")
            record("preview", f"{mt.lower()} preview from a row tap", "FAIL", f"no file rows on screen; {texts(root, 25)}", ev(tg))
            continue
        target_name, target_rect = rows[0]
        entry = next(f for f in files if f["name"] == target_name)
        root, tg0 = step(f"pv-{token}-before")
        before_tab = selected_tab(root)
        before_rows = [(n, r[1]) for n, r in file_rows(root, names, left)[:4]]
        mark(f"preview {token} {entry.get('mediaId')}")
        tap(target_rect)
        settle(3)
        root, tg1 = step(f"pv-{token}-open-3s")
        root, tg2 = step(f"pv-{token}-open-8s", wait=5)
        k = preview_kind(root, entry)
        if mt == "IMAGE":
            ok = k["close"] and (k["imageNode"] or (k["meta"] or "").startswith("Image")) and not k["play"]
        elif mt == "PDF":
            ok = k["close"] or find(root, r"^Page 1$") is not None
        else:
            ok = k["close"] and (k["meta"] or "").startswith(mt.title()) and k["play"] is not None
        record("preview", f"{mt.lower()} row tap opens the in-explorer preview", "PASS" if ok else "FAIL", f"tapped '{target_name}' after {scrolled} scrolls; {k}; {texts(root, 14)}", [tg0 + ".png", tg1 + ".png", tg2 + ".png", tg2 + ".xml"])
        mark(f"preview back {token}")
        back()
        settle(2)
        root, tg3 = step(f"pv-{token}-back")
        after_tab = selected_tab(root)
        after_rows = [(n, r[1]) for n, r in file_rows(root, names, left)[:4]]
        same_scroll = bool(before_rows) and bool(after_rows) and before_rows[0][0] == after_rows[0][0] and abs(before_rows[0][1] - after_rows[0][1]) <= 12
        in_preview = find(root, r"^Close$") is not None and preview_kind(root, entry)["meta"] is not None
        ok2 = same_scroll and before_tab == after_tab and not in_preview and find(root, r"^View data$") is not None
        record("preview", f"Back from the {mt.lower()} preview returns to the same folder, tab and scroll", "PASS" if ok2 else "FAIL", f"tab {before_tab} -> {after_tab}; first rows before {before_rows[:2]} after {after_rows[:2]}; preview still open={in_preview}", [tg0 + ".png", tg3 + ".png", tg3 + ".xml"])
        if mt == "VIDEO":
            scout_data["video_shot"] = tg2


def phase_transcripts_tab():
    info = (scout_data.get("bytype") or {}).get("audios") or {}
    if not info.get("files"):
        record("preview", "transcript preview from the Transcripts tab", "NT", "no audio with transcripts in production")
        return
    if not open_folder(info["levels"]):
        record("preview", "transcript preview from the Transcripts tab", "FAIL", "folder did not open")
        return
    if not A.tap_text(r"^Transcripts( \d+)?$", timeout=8):
        root, tg = step("tx-notab")
        record("preview", "transcript preview from the Transcripts tab", "FAIL", f"no Transcripts tab; {texts(root, 20)}", ev(tg))
        return
    settle(6)
    root, tg0 = step("tx-tab")
    tab0 = selected_tab(root)
    left = content_left()
    stems = [os.path.splitext(f["name"])[0] for f in info["files"]]
    cand = []
    for n in all_nodes(root):
        t = label(n).strip()
        r = rect(n.get("bounds"))
        if r and r[0] >= left and t and (t in stems or any(t.startswith(s[:24]) for s in stems[:20])):
            cand.append((t, r))
    read = node_at(root, r"^Read( original)?$", left)
    target = read[1] if read else (cand[0][1] if cand else None)
    if not target:
        record("preview", "transcript preview from the Transcripts tab", "FAIL", f"tab selected {tab0}; no transcript row; {texts(root, 30)}", ev(tg0))
        return
    mark("transcript open")
    tap(target)
    root, tg1 = step("tx-open", wait=5)
    opened = find(root, r"^Close$") is not None or len(V.labelled(root)) > len(V.labelled(dump(None))) - 1
    record("preview", "transcript row opens a preview", "PASS" if find(root, r"^Close$") else "FAIL", f"tab {tab0}; tapped {'Read' if read else 'row'}; {texts(root, 16)}", [tg0 + ".png", tg1 + ".png", tg1 + ".xml"])
    back()
    settle(2)
    root, tg2 = step("tx-back")
    tab1 = selected_tab(root)
    record("preview", "Back from the transcript keeps the Transcripts tab", "PASS" if tab1 == "Transcripts" and not find(root, r"^Close$") else "FAIL", f"tab {tab0} -> {tab1}; {texts(root, 12)}", [tg2 + ".png", tg2 + ".xml"])


def side_by_side(left_png, right_png, out_png, title=""):
    if Image is None:
        return None
    try:
        a = Image.open(left_png).convert("RGB")
        b = Image.open(right_png).convert("RGB")
        hgt = 1700
        a = a.resize((int(a.width * hgt / a.height), hgt))
        b = b.resize((int(b.width * hgt / b.height), hgt))
        c = Image.new("RGB", (a.width + b.width + 40, hgt), (255, 255, 255))
        c.paste(a, (0, 0))
        c.paste(b, (a.width + 40, 0))
        c.save(out_png)
        return os.path.basename(out_png)
    except Exception as exc:
        A.log(f"side by side failed: {exc}")
        return None


def phase_expand():
    info = (scout_data.get("bytype") or {}).get("audios") or {}
    files = [f for f in info.get("files", []) if f.get("mediaId")]
    if not files:
        record("expand", "audio Expand opens the board view", "NT", "production has no audio for this account (owner: accept previews on FR)")
        return
    if not open_folder(info["levels"]):
        record("expand", "audio Expand opens the board view", "FAIL", "audio folder did not open")
        return
    left = content_left()
    root = dump(None)
    rows = file_rows(root, [f["name"] for f in files], left)
    if not rows:
        root, tg = step("ex-norows")
        record("expand", "audio Expand opens the board view", "FAIL", f"no audio row; {texts(root, 20)}", ev(tg))
        return
    name, r = rows[0]
    tap(r)
    settle(5)
    root, tg0 = step("ex-preview")
    how = "preview Open record"
    if not A.tap_text(r"^Open record$", timeout=6):
        mid = next(f["mediaId"] for f in files if f["name"] == name)
        how = "deep link /data/go/open/media"
        back()
        sh(f"am start -a android.intent.action.VIEW -d {A.shlex.quote(WEB + '/data/go/open/media/' + mid)} {PKG}", check=False, timeout=60)
    settle(7)
    root, tg1 = step("ex-record")
    exp = find(root, r"^Expand$")
    if not exp:
        for _ in range(3):
            A.scroll_down_list(0.3)
            root = dump(None)
            exp = find(root, r"^Expand$")
            if exp:
                break
    if not exp:
        root, tg1b = step("ex-record-noexpand")
        record("expand", "compact player shows Expand", "FAIL", f"via {how}; no Expand control; {texts(root, 24)}", [tg0 + ".png", tg1 + ".png", tg1b + ".xml"])
        return
    record("expand", "compact player shows Expand", "PASS", f"via {how}; Expand at {exp[1]}", [tg1 + ".png", tg1 + ".xml"])
    mark("expand tap")
    tap(exp[1])
    settle(6)
    root, tg2 = step("ex-board")
    marks = {
        "close": find(root, r"^Close$") is not None,
        "play": find(root, r"^(Play|Pause)$") is not None,
        "waveform": find(root, r"(?i)waveform") is not None,
        "tabs": [t for t in ("Transcript", "English", "Details", "Find") if find(root, rf"^{t}") is not None],
        "save": find(root, r"^Save$") is not None,
    }
    sbs = side_by_side(png(tg2), os.path.join(HERE, "board-AndroidAudio.png"), os.path.join(OUT, "expand-impl-vs-board.png"))
    ok = marks["close"] and marks["play"] and (marks["waveform"] or marks["tabs"])
    record("expand", "audio Expand opens the board view", "PASS" if ok else "FAIL", f"{marks}; compare implementation (left) with the AndroidAudio board (right) in {sbs}", [tg2 + ".png", tg2 + ".xml"] + ([sbs] if sbs else []))
    back()
    settle(3)
    root, tg3 = step("ex-closed")
    record("expand", "Back from the expanded view returns to the record page", "PASS" if find(root, r"^Expand$") else "FAIL", f"{texts(root, 14)}", ev(tg3))


def tabs_and_report():
    levels = scout_data.get("ui_chain") or []
    if len(levels) < 2:
        record("tabs", "Tables tab", "NT", "no folder chain")
        return
    if not open_folder(levels[:2]):
        record("tabs", "Tables tab", "FAIL", "folder did not open")
        return
    root, tg0 = step("tabs-files")
    if A.tap_text(r"^Tables( \d+)?$", timeout=8):
        settle(7)
        root, tg1 = step("tabs-tables")
        t1 = selected_tab(root)
        back()
        settle(2)
        root, tg2 = step("tabs-tables-back")
        t2 = selected_tab(root)
        record("tabs", "Tables tab opens; Back returns to Files", "PASS" if t1 == "Tables" and t2 == "Files" and find(root, r"^View data$") else "FAIL", f"selected {t1} then after Back {t2}; tables labels {texts(dump(None), 0)}", [tg1 + ".png", tg2 + ".png", tg2 + ".xml"])
    else:
        record("tabs", "Tables tab", "FAIL", f"no Tables tab; {texts(root, 25)}", ev(tg0))
    if A.tap_text(r"^Transcripts( \d+)?$", timeout=6):
        settle(5)
        root, tg3 = step("tabs-transcripts")
        record("tabs", "Transcripts tab opens", "PASS" if selected_tab(root) == "Transcripts" else "FAIL", f"{tab_state(root)}; {texts(root, 18)}", ev(tg3))
        A.tap_text(r"^Files( \d+)?$", timeout=6)
        settle(3)
    before = set(texts(dump(None), 200))
    if A.tap_text(r"^Report \(\.xlsx\)$", timeout=8, scroll=3):
        settle(5)
        root, tg4 = step("report-sheet")
        new = [t for t in texts(root, 200) if t not in before]
        ok = len(new) >= 4
        record("report", "Report dialog opens (not generated)", "PASS" if ok else "FAIL", f"new labels {new[:16]}", ev(tg4))
        back()
        settle(2)
    else:
        root, tg4 = step("report-none")
        record("report", "Report dialog opens", "FAIL", f"no Report (.xlsx) button; {texts(root, 20)}", ev(tg4))


def crop_rect(img, r):
    return img.crop((int(r[0]), int(r[1]), int(r[2]), int(r[3])))


def dist(a, b):
    return abs(a[0] - b[0]) + abs(a[1] - b[1]) + abs(a[2] - b[2])


def frame_stats(crop, fill, tick, tick_full):
    px = list(crop.getdata())
    n = len(px) or 1
    f = sum(1 for p in px if dist(p, fill) < 90) / n
    t = sum(1 for p in px if dist(p, tick) < 90 and dist(p, fill) >= 90)
    return round(f, 3), round(t / tick_full, 3) if tick_full else 0.0


def dominant(px):
    counts = {}
    for p in px:
        q = (p[0] // 8, p[1] // 8, p[2] // 8)
        counts[q] = counts.get(q, 0) + 1
    q = max(counts.items(), key=lambda kv: kv[1])[0]
    return (q[0] * 8 + 4, q[1] * 8 + 4, q[2] * 8 + 4)


def tick_geometry(crop, fill):
    w, h = crop.size
    pts = [(x, y) for y in range(h) for x in range(w) if dist(crop.getpixel((x, y)), fill) >= 200]
    cols = {}
    for x, y in pts:
        cols.setdefault(x, []).append(y)
    line = sorted((x, sum(v) / len(v)) for x, v in cols.items() if len(v) >= 1)
    if len(line) < 8:
        return None
    corner = max(line, key=lambda p: p[1])
    segs = [[p for p in line if p[0] <= corner[0]], [p for p in line if p[0] >= corner[0]]]
    out = []
    for seg in segs:
        seg = seg[1:-1] if len(seg) > 4 else seg
        if len(seg) < 3:
            return None
        n = len(seg)
        mx = sum(p[0] for p in seg) / n
        my = sum(p[1] for p in seg) / n
        sxx = sum((p[0] - mx) ** 2 for p in seg) or 1
        slope = sum((p[0] - mx) * (p[1] - my) for p in seg) / sxx
        resid = max(abs(p[1] - (my + slope * (p[0] - mx))) for p in seg)
        out.append({"columns": n, "slope": round(slope, 3), "maxResidualPx": round(resid, 2)})
    return {"corner": (corner[0] / w, corner[1] / h), "segments": out, "box": (w, h)}


def burst(tag, r, n=14):
    frames = []
    t0 = time.time()
    for i in range(n):
        data = adb("exec-out", "screencap", "-p", binary=True, timeout=60)
        name = f"{tag}-f{i:02d}.png"
        with open(os.path.join(OUT, name), "wb") as fh:
            fh.write(data)
        frames.append((round(time.time() - t0, 2), name))
    return frames


def checkbox_canvas(r):
    cx = (r[0] + r[2]) / 2
    cy = (r[1] + r[3]) / 2
    half = dp(10)
    return (cx - half + 1, cy - half + 1, cx + half - 1, cy + half - 1)


def open_selection_folder():
    info = (scout_data.get("bytype") or {}).get("images") or {}
    files = [f for f in info.get("files", []) if f.get("mediaId")]
    if not files or not open_folder(info["levels"]):
        return None, []
    return info, files


def checks_on_screen(root, left):
    out = []
    for n in all_nodes(root):
        if n.get("checkable") == "true":
            r = rect(n.get("bounds"))
            if r and r[0] >= left and r[2] > r[0]:
                out.append((n.get("checked") == "true", r))
    out.sort(key=lambda x: x[1][1])
    return out


def row_name_for(root, r, names, left):
    cy = (r[1] + r[3]) / 2
    best = None
    for t, rr in file_rows(root, names, left):
        if rr[1] - 30 <= cy <= rr[3] + 30:
            best = t
    return best


def phase_checkbox_download():
    if Image is None:
        record("checkbox", "pixel tooling", "ERROR", "Pillow missing")
    info, files = open_selection_folder()
    if not info:
        record("checkbox", "checkbox frames", "FAIL", "image folder did not open")
        return
    left = content_left()
    names = [f["name"] for f in files]
    root = dump(None)
    rows = [x for x in file_rows(root, names, left) if x[1][1] > screen_size()[1] * 0.12]
    if not rows:
        root, tg = step("cb-norows")
        record("checkbox", "checkbox frames", "FAIL", f"no file rows; {texts(root, 20)}", ev(tg))
        return
    r = rows[0][1]
    mark("long-press to select")
    sh(f"input swipe {(r[0] + r[2]) // 2} {(r[1] + r[3]) // 2} {(r[0] + r[2]) // 2} {(r[1] + r[3]) // 2} 1200")
    settle(3)
    root, tg0 = step("cb-selecting")
    checks = checks_on_screen(root, left)
    unchecked = [c for c in checks if not c[0]]
    if not unchecked:
        record("checkbox", "checkbox frames", "FAIL", f"no unchecked box after long-press; checks {checks[:4]}; {texts(root, 16)}", ev(tg0))
    else:
        for k in ("window_animation_scale", "transition_animation_scale", "animator_duration_scale"):
            sh(f"settings put global {k} 10", check=False)
        settle(2)
        box = unchecked[0][1]
        canvas = checkbox_canvas(box)
        pre = shot("cb-burst-pre")
        mark("checkbox burst tap")
        sh(f"input tap {(box[0] + box[2]) // 2} {(box[1] + box[3]) // 2}")
        frames = burst("cb-burst", box)
        settle(6)
        post = shot("cb-burst-final")
        for k in ("window_animation_scale", "transition_animation_scale", "animator_duration_scale"):
            sh(f"settings put global {k} 1", check=False)
        if Image is not None:
            final = crop_rect(Image.open(os.path.join(OUT, post)).convert("RGB"), canvas)
            fill = dominant(list(final.getdata()))
            others = [p for p in final.getdata() if dist(p, fill) >= 200]
            tick = dominant(others) if others else (255, 255, 255)
            tick_full = sum(1 for p in final.getdata() if dist(p, tick) < 90 and dist(p, fill) >= 90)
            first = crop_rect(Image.open(os.path.join(OUT, pre)).convert("RGB"), canvas)
            stats = [("pre", 0.0) + frame_stats(first, fill, tick, tick_full)]
            for t, name in frames:
                stats.append((name, t) + frame_stats(crop_rect(Image.open(os.path.join(OUT, name)).convert("RGB"), canvas), fill, tick, tick_full))
            stats.append(("final", 99.0) + frame_stats(final, fill, tick, tick_full))
            mid = [s for s in stats if (STALE < s[2] < 0.8) or (s[2] >= 0.8 and 0.15 < s[3] < 0.85)]
            strip_w = (len(stats)) * 88
            strip = Image.new("RGB", (strip_w, 88), (255, 255, 255))
            for i, s in enumerate(stats):
                src = {"pre": pre, "final": post}.get(s[0], s[0])
                c = crop_rect(Image.open(os.path.join(OUT, src)).convert("RGB"), (canvas[0] - 6, canvas[1] - 6, canvas[2] + 6, canvas[3] + 6)).resize((84, 84), Image.NEAREST)
                strip.paste(c, (i * 88 + 2, 2))
            strip.save(os.path.join(OUT, "checkbox-burst-strip.png"))
            A.save("checkbox-burst.json", json.dumps({"canvas": canvas, "fill": fill, "tick": tick, "tickPixels": tick_full, "frames": stats}, indent=1))
            ok = not mid and stats[-1][2] >= 0.8 and tick_full > 10
            record("checkbox", "animator x10 burst: no intermediate check state", "PASS" if ok else "FAIL", f"{len(frames)} frames in {frames[-1][0]}s; fill/tick ratios {[(s[1], s[2], s[3]) for s in stats]}; intermediate {mid[:3]}", ["checkbox-burst-strip.png", "checkbox-burst.json", "screenrecord-*.mp4"])
            geo = tick_geometry(final, fill)
            zoom = final.resize((final.width * 8, final.height * 8), Image.NEAREST)
            zoom.save(os.path.join(OUT, "checkbox-tick-zoom.png"))
            exp1 = (0.70 - 0.52) / (0.42 - 0.24)
            exp2 = (0.32 - 0.70) / (0.77 - 0.42)
            if geo:
                s1, s2 = geo["segments"]
                straight = s1["maxResidualPx"] <= 1.2 and s2["maxResidualPx"] <= 1.2 and abs(s1["slope"] - exp1) <= 0.3 and abs(s2["slope"] - exp2) <= 0.3
                record("checkbox", "tick is two straight strokes (web geometry)", "PASS" if straight else "FAIL", f"{geo}; expected slopes {round(exp1, 3)} / {round(exp2, 3)}, corner (0.42, 0.70)", ["checkbox-tick-zoom.png"])
            else:
                record("checkbox", "tick is two straight strokes", "FAIL", "tick pixels not found", ["checkbox-tick-zoom.png"])
    phase_download(info, files, left)


def phase_download(info, files, left):
    names = [f["name"] for f in files]
    root = dump(None)
    if not find(root, r"selected$"):
        root, tg = step("dl-not-selecting")
        record("download", "selection mode", "FAIL", f"not in selection mode; {texts(root, 20)}", ev(tg))
        return
    checks = checks_on_screen(root, left)
    want = 3 if len(files) >= 3 else len(files)
    for on, r in checks:
        if sum(1 for c in checks_on_screen(dump(None), left) if c[0]) >= want:
            break
        if not on:
            tap(r)
            settle(1.2)
    root = dump(None)
    chosen = [row_name_for(root, r, names, left) for on, r in checks_on_screen(root, left) if on]
    chosen = [c for c in chosen if c]
    ids = [next((f["mediaId"] for f in files if f["name"] == c), None) for c in chosen]
    victim = ids[1] if len(ids) > 1 else (ids[0] if ids else None)
    if not victim:
        root, tg = step("dl-nochoice")
        record("download", "pick a small set", "FAIL", f"chosen {chosen}; {texts(root, 20)}", ev(tg))
        return
    sh(f"setprop debug.accept.victim {victim}", check=False)
    got = sh("getprop debug.accept.victim", check=False).strip()
    victim_name = chosen[ids.index(victim)]
    root, tg0 = step("dl-chosen")
    record("download", "small set chosen", "INFO", f"{len(chosen)} files {chosen}; the API will be asked for media 'accept-0016-missing' in place of '{victim_name}' (prop={got == victim})", ev(tg0))
    mark("download start")
    if not A.tap_text(r"^Download \d+", timeout=8):
        root, tg = step("dl-nobutton")
        record("download", "Download N selected", "FAIL", f"{texts(root, 25)}", ev(tg))
        return
    caught = False
    end = time.time() + 90
    while time.time() < end:
        root = dump(None)
        start = find(root, r"^(Download now|Start download|Download on Wi-Fi)$")
        if start and not find(root, r"^All \d+$"):
            tap(start[1])
            settle(0.5)
            continue
        alln = find(root, r"^All \d[\d,]*$")
        if alln:
            tap(alln[1])
            caught = True
            break
        settle(0.3)
    if not caught:
        root, tg = step("dl-noall")
        record("download", "All files sheet reached while the job ran", "FAIL", f"{texts(root, 25)}", ev(tg))
    end = time.time() + 180
    root = None
    while time.time() < end:
        root = dump(None)
        failed = find(root, r"^Failed$")
        sizes = [t for t in texts(root, 300) if re.match(r"^[\d.,]+ (B|KB|MB|GB)$", t)]
        if failed and len(sizes) >= len(chosen) - 1:
            break
        settle(2)
    settle(2)
    root, tg1 = step("dl-allfiles")
    rows = []
    for c in chosen:
        hit = find(root, rf"^{re.escape(c)}$")
        if hit:
            r = hit[1]
            line = [label(n).strip() for n in all_nodes(root) if rect(n.get("bounds")) and abs((rect(n.get("bounds"))[1] + rect(n.get("bounds"))[3]) / 2 - (r[1] + r[3]) / 2) < dp(12) and rect(n.get("bounds"))[0] > r[2] - 4]
            rows.append({"name": c, "rect": r, "end": line[:2], "victim": c == victim_name})
    A.save("download-rows.json", json.dumps(rows, indent=1))
    saved_rows = [x for x in rows if not x["victim"] and any(re.match(r"^[\d.,]+ (B|KB|MB|GB)$", e) for e in x["end"])]
    failed_rows = [x for x in rows if x["victim"] and "Failed" in x["end"]]
    record("download", "set downloads with one failed file", "PASS" if saved_rows and failed_rows else "FAIL", f"rows {[(x['name'][-28:], x['end']) for x in rows]}", ev(tg1) + ["download-rows.json"])
    if Image is not None and saved_rows and failed_rows:
        img = Image.open(png(tg1)).convert("RGB")
        res = []
        for x in (saved_rows[0], failed_rows[0]):
            res.append(badge_metrics(img, x["rect"], "saved" if not x["victim"] else "failed"))
        A.save("badge-metrics.json", json.dumps(res, indent=1))
        s, f = res
        ok = all(m.get("ok") for m in res) and abs(s["circle"][0] - f["circle"][0]) <= 1.0 and abs(s["glyph"][0] - f["glyph"][0]) <= 1.5
        record("download", "tick and ! centred on the same axis (pixel check)", "PASS" if ok else "FAIL",
               f"saved circle {s.get('circle')} glyph {s.get('glyph')} off {s.get('offset')}; failed circle {f.get('circle')} glyph {f.get('glyph')} off {f.get('offset')}; axis delta circle {round(s['circle'][0] - f['circle'][0], 2)}px glyph {round(s['glyph'][0] - f['glyph'][0], 2)}px",
               ["badge-metrics.json", "badge-zoom-saved.png", "badge-zoom-failed.png", tg1 + ".png"])
    back()
    settle(1.5)
    root = dump(None)
    if A.tap_text(r"^Downloads$", timeout=5):
        settle(3)
        root, tg2 = step("dl-sheet")
        record("download", "Downloads sheet after the set", "INFO", f"{texts(root, 30)}", ev(tg2))
        back()
    sh("setprop debug.accept.victim ''", check=False)
    for _ in range(2):
        root = dump(None)
        if find(root, r"selected$"):
            back()


def badge_metrics(img, name_rect, kind):
    cx = name_rect[0] - dp(10) - dp(11)
    cy = (name_rect[1] + name_rect[3]) / 2
    R = dp(11)
    box = (int(cx - R - dp(4)), int(cy - R - dp(4)), int(cx + R + dp(4)), int(cy + R + dp(4)))
    crop = img.crop(box)
    w, h = crop.size
    ring = crop.getpixel((int(w / 2), int(h / 2 - R * 0.86)))
    circle_px = [(x, y) for y in range(h) for x in range(w) if dist(crop.getpixel((x, y)), ring) < 30]
    if not circle_px:
        return {"kind": kind, "ok": False, "why": "no circle"}
    xs = [p[0] for p in circle_px]
    ys = [p[1] for p in circle_px]
    ccx = (min(xs) + max(xs)) / 2
    ccy = (min(ys) + max(ys)) / 2
    rad = (max(xs) - min(xs)) / 2
    glyph = [(x, y) for y in range(h) for x in range(w) if (x - ccx) ** 2 + (y - ccy) ** 2 < (rad * 0.85) ** 2 and dist(crop.getpixel((x, y)), ring) >= 120]
    if not glyph:
        return {"kind": kind, "ok": False, "why": "no glyph", "circle": (box[0] + ccx, box[1] + ccy)}
    gx = [p[0] for p in glyph]
    gy = [p[1] for p in glyph]
    gcx = (min(gx) + max(gx)) / 2
    gcy = (min(gy) + max(gy)) / 2
    crop.resize((w * 8, h * 8), Image.NEAREST).save(os.path.join(OUT, f"badge-zoom-{kind}.png"))
    off = (round(gcx - ccx, 2), round(gcy - ccy, 2))
    return {"kind": kind, "circle": (round(box[0] + ccx, 2), round(box[1] + ccy, 2)), "radius": rad, "glyph": (round(box[0] + gcx, 2), round(box[1] + gcy, 2)), "glyphBox": (min(gx), min(gy), max(gx), max(gy)), "offset": off, "ok": abs(off[0]) <= 1.5 and abs(off[1]) <= 1.5}


def open_search_everything(term):
    go_dashboard()
    V.open_data()
    settle(4)
    if not V.type_jump(term):
        return False
    settle(3)
    hide_ime()
    return bool(A.tap_text(r"^Search everything", timeout=8, scroll=5))


def chip_filters():
    go_dashboard()
    V.open_data()
    settle(4)
    w, h = screen_size()
    for _ in range(5):
        root = dump(None)
        hit = find(root, r"^Filters( · \d+)?$")
        if hit and hit[1][2] <= w:
            tap(hit[1])
            return True
        ev_chip = find(root, r"^Everything$")
        if not ev_chip:
            return False
        y = (ev_chip[1][1] + ev_chip[1][3]) // 2
        sh(f"input swipe {int(w * 0.85)} {y} {int(w * 0.15)} {y} 400")
        settle(1)
    return False


def phase_record_types():
    if chip_filters():
        return record_types_sheet("Data chips")
    if not open_search_everything("Test" if PORTAL == "dr" else "Heera"):
        root, tg = step("rt-nosearch")
        record("recordtypes", "Record types picker has Select all / Clear all", "FAIL", f"search everything did not open; {texts(root, 20)}", ev(tg))
        return
    settle(5)
    hit = None
    for _ in range(4):
        root = dump(None)
        hit = find(root, r"^Filters( · \d+)?$")
        if hit:
            break
        A.scroll_to_top()
    if not hit:
        root, tg = step("rt-nofilters")
        record("recordtypes", "Record types picker has Select all / Clear all", "FAIL", f"no Filters chip; {texts(root, 25)}", ev(tg))
        return
    tap(hit[1])
    return record_types_sheet("Search everything")


def record_types_sheet(where):
    settle(3)
    root, tg0 = step("rt-filters")
    if not A.tap_text(r"^Record types( \(\d+ selected\)|\. )", timeout=6, scroll=2):
        A.tap_text(r"^Record types$", timeout=4)
    settle(3)
    root, tg1 = step("rt-picker")
    sel = find(root, r"^Select all \d+ shown$")
    clr = find(root, r"^Clear all$")
    record("recordtypes", f"{PORTAL.upper()} Record types shows Select all / Clear all", "PASS" if sel and clr else "FAIL", f"via {where} Filters; select all={label(sel[0]) if sel else None}; clear all={bool(clr)}; {texts(root, 20)}", [tg0 + ".png", tg1 + ".png", tg1 + ".xml"])
    if sel:
        tap(sel[1])
        settle(1.5)
        root, tg2 = step("rt-all-picked")
        checked = sum(1 for n in all_nodes(root) if n.get("checkable") == "true" and n.get("checked") == "true")
        clr = find(root, r"^Clear all$")
        if clr:
            tap(clr[1])
            settle(1.5)
        root, tg3 = step("rt-cleared")
        checked2 = sum(1 for n in all_nodes(root) if n.get("checkable") == "true" and n.get("checked") == "true")
        record("recordtypes", "Select all ticks every type, Clear all clears them", "PASS" if checked >= 3 and checked2 == 0 else "FAIL", f"checked after Select all {checked}; after Clear all {checked2}", [tg2 + ".png", tg3 + ".png"])
    for _ in range(3):
        back()


def phase_quick_ka():
    want = (scout_data.get("crafts_ka") or {}).get("names") or []
    go_dashboard()
    V.open_data()
    settle(4)
    if not V.type_jump("ka"):
        record("quick", "quick search 'ka'", "FAIL", "jump field not found")
        return
    settle(5)
    hide_ime()
    root, tg = step("quick-ka")
    hits = names_in(root, want, 0)
    heading = find(root, r"^Crafts( \d+)?$")
    if not hits:
        A.scroll_down_list(0.4)
        root2, tg2 = step("quick-ka-scrolled")
        hits = names_in(root2, want, 0)
        heading = heading or find(root2, r"^Crafts( \d+)?$")
    if PORTAL == "dr":
        record("quick", "quick search 'ka' lists crafts (DR)", "PASS" if hits else "FAIL", f"api crafts matching 'ka': {len(want)} e.g. {want[:4]}; on screen {hits[:5]}; Crafts heading={bool(heading)}; {texts(root, 22)}", ev(tg))
    else:
        record("quick", "quick search 'ka' lists crafts", "INFO", f"api crafts {len(want)}; on screen {hits[:5]}", ev(tg))
    if A.tap_text(r"^Search everything", timeout=6, scroll=5):
        settle(6)
        root, tg3 = step("everything-ka")
        tab = find(root, r"^Crafts \d+$")
        record("quick", "Search everything 'ka' has a Crafts bucket", "PASS" if tab or names_in(root, want, 0) else ("FAIL" if PORTAL == "dr" else "INFO"), f"{texts(root, 24)}", ev(tg3))
        both = find(root, r"^Both portals$")
        if both:
            tap(both[1])
            settle(8)
            root, tg4 = step("everything-both")
            n = len([t for t in texts(root, 200) if re.search(r"(?i)ka", t)])
            other = find(root, r"(?i)(field repository|design workshop|designer repository|\bFR\b|\bDR\b)")
            record("search", "Search everything: Both portals returns rows", "PASS" if n >= 2 else "FAIL", f"rows mentioning 'ka' {n}; portal tag {label(other[0]) if other else None}; {texts(root, 26)}", ev(tg4))
        else:
            record("search", "Search everything: Both portals offered", "FAIL", f"no Both portals chip; {texts(root, 20)}", ev(tg3))


def phase_view_transcripts():
    info = (scout_data.get("bytype") or {}).get("audios") or {}
    folder = (info.get("levels") or [{}])[-1].get("path") if info.get("files") else None
    if not folder:
        chain = scout_data.get("ui_chain") or []
        folder = chain[1]["path"] if len(chain) > 1 else (chain[0]["path"] if chain else "by-type")
    go_dashboard()
    url = f"{WEB}/data?path={urllib.parse.quote(folder, safe='')}&view=transcripts"
    mark(f"deeplink view=transcripts {folder}")
    sh(f"am start -a android.intent.action.VIEW -d {A.shlex.quote(url)} {PKG}", check=False, timeout=60)
    settle(4)
    root, tg1 = step("dl-view-transcripts-4s")
    root, tg2 = step("dl-view-transcripts-10s", wait=6)
    st = tab_state(root) or tab_state(reveal_tabs())
    record("deeplink", "deep link view=transcripts opens Transcripts", "PASS" if st.get("Transcripts") else "FAIL", f"{folder}: tabs {st}; {texts(root, 16)}", [tg1 + ".png", tg2 + ".png", tg2 + ".xml"])
    back()


def phase_process_death():
    levels = scout_data.get("ui_chain") or []
    if len(levels) < 2:
        record("death", "process death restores Data", "NT", "no chain")
        return
    if not open_folder(levels[:2]):
        record("death", "process death restores Data", "FAIL", "folder did not open")
        return
    lvl = levels[1]
    settle(3)
    root, tg0 = step("death-before")
    before = names_in(root, lvl["names"], content_left())
    sh("input keyevent KEYCODE_HOME")
    settle(3)
    mark("process death")
    sh(f"am kill {PKG}", check=False)
    settle(3)
    gone = not alive()
    sh(f"monkey -p {PKG} -c android.intent.category.LAUNCHER 1", check=False)
    settle(10)
    root, tg1 = step("death-restored", check=False)
    after = names_in(root, lvl["names"], content_left())
    title = find(root, r"^View data$")
    ok = gone and bool(title) and (bool(after) or not before) and not on_dashboard(root)
    record("death", "process death restores Data", "PASS" if ok else "FAIL", f"process killed={gone}; folder '{lvl['name']}' rows before {before[:3]} after {after[:3]}; View data shown={bool(title)}", [tg0 + ".png", tg1 + ".png", tg1 + ".xml"])
    A.last_pid[0] = sh(f"pidof {PKG}", check=False).strip()


def phase_storm():
    levels = scout_data.get("ui_chain") or []
    if len(levels) < 2:
        return
    go_dashboard()
    V.open_data()
    settle(4)
    V.tap_pill(levels[0]["name"])
    settle(4)
    mark("storm")
    left = content_left()
    for i in range(6):
        root = dump(None)
        hit = node_at(root, rf"^{re.escape(levels[1]['name'])}$", left)
        if hit:
            tap(hit[1])
        settle(0.4)
        sh("input keyevent KEYCODE_BACK")
        settle(0.4)
    settle(5)
    root, tg = step("storm-after")
    names = [n for n in levels[0]["names"] if n not in V.TAB_WORDS]
    hits = names_in(root, names, left)
    record("storm", "descend/Back storm leaves Data intact", "PASS" if alive() and (hits or on_dashboard(root)) and not incidents else "FAIL", f"alive={alive()}; rows {hits[:3]}; dashboard={on_dashboard(root)}; incidents {incidents[-2:]}", ev(tg))


def phase_viewid_burst():
    go_dashboard()
    V.open_data()
    settle(5)
    url = f"{WEB}/data?viewId=bogus-accept-0016"
    mark("deeplink viewid burst")
    sh(f"am start -a android.intent.action.VIEW -d {A.shlex.quote(url)} {PKG}", check=False, timeout=60)
    frames = burst("viewid", None, n=10)
    root, tg = step("viewid-after")
    record("deeplink", "?viewId=bogus shows an error-toned message (screenshot burst)", "INFO", f"{len(frames)} screenshots over {frames[-1][0]}s; read them for the banner: {[f[1] for f in frames]}", [f[1] for f in frames] + ev(tg))


def phase_back_top():
    go_dashboard()
    V.open_data()
    settle(5)
    tax = next((t for t in scout_data["taxonomies"] if t["path"] == "by-type"), scout_data["taxonomies"][0])
    V.tap_pill(tax["name"])
    settle(4)
    root, tg0 = step("backtop-start")
    presses = 0
    tags = [tg0 + ".png"]
    for i in range(4):
        back()
        presses += 1
        root, tg = step(f"backtop-{presses}", wait=1)
        tags.append(tg + ".png")
        if on_dashboard(root) or not find(root, r"^View data$"):
            break
    left = on_dashboard(root) or not find(root, r"^View data$")
    record(3, "system Back from a taxonomy top leaves Data in one press", "PASS" if left and presses == 1 else "FAIL", f"left Data={left} after {presses} Back presses from '{tax['name']}'", tags)


def finish_analysis(raw, heap, soak_seconds):
    segments, minutes, oks = A.analyse_requests(raw)
    A.save("okhttp-segments.json", json.dumps(segments, indent=1, ensure_ascii=False))
    A.save("okhttp-per-minute.json", json.dumps(minutes, indent=1, ensure_ascii=False))
    lines = [l for l in raw.splitlines() if (" OkHttp" in l and ("--> " in l or "<-- " in l)) or "ACCEPT" in l]
    A.save("okhttp-requests.txt", "\n".join(lines))
    if oks == 0:
        record(2, "OkHttp logging present", "FAIL", "no OkHttp request lines")
        return
    opens = [s for s in segments if s["name"].startswith(("open ", "taxonomy ", "status ", "soak-open", "soak-status", "back to", "deeplink", "open-data"))]
    worst_tree = max((s["tree"] for s in opens), default=0)
    worst_lang = max((s["languages"] for s in opens), default=0)
    idles = [s for s in segments if s["name"].startswith("soak-idle")]
    idle_tree = sum(s["tree"] for s in idles)
    idle_lang = sum(s["languages"] for s in idles)
    idle_other = sum(s["other"] for s in idles)
    peak = max(minutes.items(), key=lambda kv: kv[1]["all"], default=(None, {"all": 0, "tree": 0}))
    tree_total = sum(s["tree"] for s in segments)
    detail = (f"{oks} requests in the session; /data/tree total {tree_total}; worst folder-open segment tree {worst_tree}, languages {worst_lang}; "
              f"soak idle ({len(idles)} x 45 s): tree {idle_tree}, languages {idle_lang}, other {idle_other}; busiest minute {peak[0]}: {peak[1]['all']} requests")
    bounded = worst_tree <= 10 and worst_lang <= 5 and idle_tree == 0 and idle_lang <= 1 and peak[1]["all"] <= 120
    record(2, "requests bounded (no storm)", "PASS" if bounded else "FAIL", detail, ["okhttp-segments.json", "okhttp-per-minute.json", "okhttp-requests.txt"])
    codes = {}
    for l in raw.splitlines():
        m = re.search(r"<-- (\d{3}) .*?/thumbnail", l)
        if m:
            codes[m.group(1)] = codes.get(m.group(1), 0) + 1
    record("thumbs", "thumbnail responses seen while browsing", "INFO", f"status counts {codes or 'none requested'} (AppThumbnails IAM statement not yet added; app falls back to the original image)")
    rows = [r for r in heap.rows if r.get("javaKB")]
    if rows:
        start = [r for r in rows if r.get("note") == "soak-start"] or rows[:1]
        end = [r for r in rows if r.get("note") == "soak-end"] or rows[-1:]
        soak = [r for r in rows if start[0]["t"] <= r["t"] <= end[-1]["t"]] or rows
        js, je = start[0]["javaKB"], end[-1]["javaKB"]
        pk = max(r["javaKB"] for r in soak)
        flat = je <= js * 1.5 + 20000 and pk < 300000
        record(2, "heap flat over the soak", "PASS" if flat else "FAIL", f"soak {soak_seconds}s; Java heap {js} KB -> {je} KB, soak peak {pk} KB over {len(soak)} samples; PSS {start[0].get('pssKB')} -> {end[-1].get('pssKB')} KB", ["heap.csv"])
    oom = [l for l in raw.splitlines() if "OutOfMemoryError" in l]
    anr = [l for l in raw.splitlines() if re.search(rf"ANR in {re.escape(PKG)}", l)]
    fatal = [l for i, l in enumerate(raw.splitlines()) if "FATAL EXCEPTION" in l]
    lines = raw.splitlines()
    fatal = [l for i, l in enumerate(lines) if "FATAL EXCEPTION" in l and any(PKG in x for x in lines[i + 1:i + 3])]
    record(2, "no OOM, ANR or crash", "PASS" if not (oom or anr or fatal or incidents) else "FAIL", f"OOM {len(oom)}; ANR {len(anr)}; FATAL {len(fatal)}; incidents {incidents[:6]}", ["logcat.txt", "logcat-crash.txt"])


def want(name):
    return not ONLY or name in ONLY


def main():
    if not (A.EMAIL and A.PASSWORD):
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
    sh("setprop debug.accept.victim ''", check=False)
    A.save("device.txt", sh("getprop ro.build.version.release") + sh("wm size") + sh("wm density"))
    heap = A.Heap()
    rec = A.Recorder()
    soak_seconds = 0
    try:
        scout()
        A.install()
        A.launch()
        rec.start()
        if not login_with_ime_check():
            root, tag = step("login-failed", check=False)
            record(0, "sign in as master admin", "FAIL", f"{texts(root, 20)}", [tag + ".png"])
            return 1
        record(0, "sign in as master admin", "PASS", "")
        heap.start()
        phases = [
            ("landing", phase_landing), ("c1", V.phase_c1), ("pane", phase_pane), ("c3", V.phase_c3),
            ("previews", phase_previews), ("transcripts", phase_transcripts_tab), ("expand", phase_expand),
            ("tabs", tabs_and_report), ("checkbox", phase_checkbox_download), ("recordtypes", phase_record_types),
            ("quick", phase_quick_ka), ("search", V.phase_c4_search), ("c5", V.phase_c5), ("viewid", phase_viewid_burst), ("viewtx", phase_view_transcripts), ("backtop", phase_back_top),
            ("death", phase_process_death), ("storm", phase_storm), ("c6", V.phase_c6),
        ]
        for name, fn in phases:
            if want(name):
                A.run(name, fn)
        if want("soak"):
            soak_seconds = A.run("soak", V.phase_soak, heap) or 0
    finally:
        sh("settings put system font_scale 1.0", check=False)
        sh("settings put system user_rotation 0", check=False)
        for k in ("window_animation_scale", "transition_animation_scale", "animator_duration_scale"):
            sh(f"settings put global {k} 1", check=False)
        heap.stop = True
        rec.finish()
        logcat.terminate()
        logf.close()
        with open(os.path.join(OUT, "logcat-raw.txt"), "rb") as fh:
            raw = fh.read().decode(errors="replace")
        os.remove(os.path.join(OUT, "logcat-raw.txt"))
        A.save("logcat.txt", raw)
        A.save("logcat-crash.txt", adb("logcat", "-d", "-b", "crash", check=False))
        try:
            backend_probe("end")
        except Exception:
            pass
        A.save("victim-lines.txt", "\n".join(l for l in raw.splitlines() if "VICTIM" in l or "accept-0016-missing" in l))
        try:
            finish_analysis(raw, heap, soak_seconds)
        except Exception as exc:
            record(2, "analysis", "ERROR", "".join(traceback.format_exception(exc))[-900:])
        A.save_json()
    return 0


if __name__ == "__main__":
    sys.exit(main())
