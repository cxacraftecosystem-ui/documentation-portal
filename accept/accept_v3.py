#!/usr/bin/env python3
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import accept_0015 as A
import accept_v2 as V
from accept_0015 import (sh, settle, dump, shot, step, find, find_all, all_nodes, label, rect, texts, tap, back, hide_ime,
                         mark, scout_data, api, go_dashboard, PORTAL, PKG, WEB, API, CFG, log)

SEARCH_TYPES = ["artisans", "crafts", "clusters", "workshops", "designWorkshops", "interviews", "transcripts", "reports", "products", "tools", "processes", "media"]
LOCATE_TYPES = {"artisans": "artisan", "crafts": "craft", "workshops": "workshop", "designWorkshops": "designWorkshop", "interviews": "interview", "transcripts": "media", "media": "media", "products": "product", "tools": "tool", "processes": "process", "reports": "designWorkshop"}
STOP = {"files", "folder", "folders", "images", "audios", "documents", "videos", "other", "jpeg", "photo", "details", "this", "that", "with", "from", "pdf", "docx", "xlsx", "json", "txt", "mp4", "m4a", "wav", "png"}
HEALTH_URL = API.rsplit("/api", 1)[0] + "/health"
health_log = []


def healthy(tag):
    start = time.time()
    probes = []
    while time.time() - start < 600:
        t0 = time.time()
        status = 0
        try:
            with urllib.request.urlopen(urllib.request.Request(HEALTH_URL), timeout=20) as r:
                status = r.status
        except urllib.error.HTTPError as e:
            status = e.code
        except Exception:
            status = 0
        ms = int((time.time() - t0) * 1000)
        probes.append(f"{status}/{ms}ms")
        if 0 < status < 500 and ms < 3000:
            break
        time.sleep(20)
    waited = round(time.time() - start, 1)
    health_log.append({"tag": tag, "waitedSeconds": waited, "probes": probes[-6:], "at": round(time.time() - A.T0, 1)})
    A.save("health.json", json.dumps(health_log, indent=1))
    if waited > 3:
        log(f"backend health wait before {tag}: {waited}s {probes[-4:]}")
    return waited


def backend_probe():
    out = []
    t0 = time.time()
    try:
        with urllib.request.urlopen(urllib.request.Request(HEALTH_URL), timeout=30) as r:
            out.append(f"health {r.status} {int((time.time() - t0) * 1000)}ms")
    except Exception as exc:
        out.append(f"health error {type(exc).__name__} {int((time.time() - t0) * 1000)}ms")
    t0 = time.time()
    tr = api.tree("")
    out.append(f"tree root {'200' if tr else 'failed'} {int((time.time() - t0) * 1000)}ms")
    return "; ".join(out)


_record = A.record


def record(crit, name, status, detail="", evidence=None):
    if status == "FAIL" and crit in (1, 3, 4, 5, 6):
        detail = f"{detail}; backend right after: {backend_probe()}"
    return _record(crit, name, status, detail, evidence)


A.record = record
V.record = record
_swipe = A.swipe


def swipe(x, y1, y2, ms=400):
    hide_ime()
    return _swipe(x, y1, y2, ms)


A.swipe = swipe
V.swipe = swipe
_tap_pill = V.tap_pill


def tap_pill(name):
    hide_ime()
    return _tap_pill(name)


V.tap_pill = tap_pill


def on_search(root):
    return find(root, r"^(This app|Both portals)$") is not None


def error_banner(root):
    return find(root, r"^Problem$")


def opened_ok(root, title):
    kind = V_opened_ok(root, title)
    if kind:
        return kind
    if find(root, r"^Close$") and find(root, r"^(Image|Video|Audio|Document|PDF|Transcript)\b"):
        return "preview"
    return None


V_opened_ok = V.opened_ok
V.opened_ok = opened_ok


def search_terms():
    terms = list(CFG["everything"]) + list(CFG["jump"])
    pool = []
    for k in ("artisan", "craft"):
        v = scout_data.get(k) or {}
        if v.get("name"):
            pool.append(v["name"])
    for names in scout_data["names"].values():
        pool += names
    for f in scout_data["folders"].values():
        pool += f.get("names", [])
    seen = {t.lower() for t in terms}
    for p in pool:
        for w in re.findall(r"[A-Za-z]{4,}", p or ""):
            if w.lower() not in seen and w.lower() not in STOP:
                seen.add(w.lower())
                terms.append(w)
    extra = ["craft", "design", "hand", "work", "product", "tool", "process", "interview", "report", "cluster", "village", "weaving", "pottery", "bamboo"]
    terms = list(dict.fromkeys(terms))
    return terms[:60] + [e for e in extra if e not in seen]


def scout_search():
    found = {}
    tried = []
    terms = search_terms()
    for scope in ("local", "all"):
        for term in terms:
            missing = [t for t in SEARCH_TYPES if t not in found]
            if not missing and (scope == "local" or "@remote" in found):
                break
            if len(tried) >= 90:
                break
            s, body = api.call("/search/unified?" + urllib.parse.urlencode({"q": term, "limit": 5, "scope": scope}))
            tried.append(f"{scope}:{term}:{s}")
            if s != 200 or not isinstance(body, dict):
                continue
            for g in body.get("groups") or []:
                typ = g.get("type")
                items = [i for i in (g.get("items") or []) if (i.get("title") or i.get("id"))]
                if scope == "local":
                    items = [i for i in items if i.get("local", True)]
                if not items:
                    continue
                if typ not in found:
                    it = items[0]
                    found[typ] = {"type": typ, "term": term, "scope": scope, "label": g.get("label") or "", "total": g.get("total"), "title": (it.get("title") or it.get("id")).strip(), "id": it.get("id"), "href": it.get("href"), "local": it.get("local", True)}
                if scope == "all" and "@remote" not in found:
                    rem = [i for i in items if not i.get("local", True)]
                    if rem:
                        it = rem[0]
                        found["@remote"] = {"type": typ, "term": term, "scope": scope, "label": g.get("label") or "", "total": g.get("total"), "title": (it.get("title") or it.get("id")).strip(), "id": it.get("id"), "href": it.get("href"), "local": False}
    for typ, f in found.items():
        lt = LOCATE_TYPES.get(f["type"])
        if f.get("local") and lt and f["type"] != "clusters":
            oid = f["id"]
            if f["type"] == "reports" and f.get("href") and "/design-workshops/" in f["href"]:
                oid = f["href"].split("/design-workshops/", 1)[1].split("?", 1)[0] or oid
            s, body = api.call("/data/locate?" + urllib.parse.urlencode({"type": lt, "id": oid}))
            path = (body or {}).get("path") if s == 200 else None
            f["locatePath"] = path
            if path:
                tr = api.tree(path)
                crumbs = (tr or {}).get("crumbs") or []
                f["locateFolder"] = crumbs[-1].get("name") if crumbs else None
    scout_data["search"] = {"found": found, "tried": tried, "missing": [t for t in SEARCH_TYPES if t not in found]}
    A.save("scout.json", json.dumps(scout_data, indent=1, ensure_ascii=False))
    log(f"search scout: types {sorted(found)}; missing {scout_data['search']['missing']}; {len(tried)} queries")


def open_unified(term, scope):
    go_dashboard()
    V.open_data()
    settle(4)
    if not V.type_jump(term):
        return None, "Jump to a record field not found"
    settle(3)
    hide_ime()
    hit = A.tap_text(r"^Search everything", timeout=6)
    if not hit:
        fld = find(dump(None), rf"^{re.escape(term)}$")
        if fld:
            tap(fld[1])
            settle(0.8)
        sh("input keyevent KEYCODE_ENTER")
    root = None
    for _ in range(20):
        root = dump(None)
        if on_search(root) and (find(root, r"^All \d+$") or find(root, r"^No matches")):
            break
        settle(1)
    if not on_search(root):
        return root, "search-everything screen did not open"
    want = "Both portals" if scope == "all" else "This app"
    sc = find(root, rf"^{want}$")
    if sc:
        tap(sc[1])
        settle(2)
        for _ in range(20):
            root = dump(None)
            if find(root, r"^All \d+$") or find(root, r"^No matches"):
                break
            settle(1)
    hide_ime()
    return dump(None), None


def tap_type_pill(lab):
    pat = rf"^{re.escape(lab)} \d+$"
    w, h = A.screen_size()
    for attempt in range(8):
        root = dump(None)
        hit = find(root, pat)
        if hit:
            tap(hit[1])
            settle(3)
            return True, hit[1][3]
        allp = find(root, r"^All \d+$")
        if allp:
            y = (allp[1][1] + allp[1][3]) // 2
            if attempt < 5:
                sh(f"input swipe {int(w * 0.9)} {y} {int(w * 0.15)} {y} 500")
            else:
                sh(f"input swipe {int(w * 0.15)} {y} {int(w * 0.9)} {y} 500")
            settle(1)
        else:
            settle(1)
    return False, 0


def find_card(title, floor):
    w, h = A.screen_size()
    for i in range(8):
        root = dump(None)
        hits = [hh for hh in find_all(root, rf"^{re.escape(title)}$") if hh[1][1] > floor and not hh[0].get("class", "").endswith("EditText")]
        if hits:
            return root, hits[0]
        _swipe(w // 2, int(h * 0.78), int(h * 0.45))
        floor = 0
    return dump(None), None


def check_type(key, f):
    typ = f["type"]
    tag = f"c4-type-{key.strip('@')}"
    scope_name = "Both portals" if f["scope"] == "all" else "This app"
    name = f"search everything [{scope_name}] {typ}{' (other portal)' if not f.get('local', True) else ''}: '{f['title'][:60]}' (term '{f['term']}')"
    healthy(tag)
    root, err = open_unified(f["term"], f["scope"])
    if err:
        r, tg = step(f"{tag}-noscreen")
        record(4, name, "FAIL", f"{err}; {texts(r, 18)}", [tg + ".png", tg + ".xml"])
        return
    lab = f.get("label") or typ
    ok, floor = tap_type_pill(lab)
    if not ok:
        r, tg = step(f"{tag}-nopill")
        record(4, name, "FAIL", f"type pill '{lab} N' not found; {texts(r, 22)}", [tg + ".png", tg + ".xml"])
        return
    root, hit = find_card(f["title"], floor)
    if not hit:
        r, tg = step(f"{tag}-nocard")
        record(4, name, "FAIL", f"result card '{f['title']}' not on screen; {texts(r, 22)}", [tg + ".png", tg + ".xml"])
        return
    target = hit[1]
    action = "card"
    if typ == "transcripts" and f.get("local", True):
        btns = [hh for hh in find_all(root, r"^Open in data$") if hh[1][1] > hit[1][1]]
        btns.sort(key=lambda hh: hh[1][1])
        if not btns:
            r, tg = step(f"{tag}-nobutton")
            record(4, name, "FAIL", f"'Open in data' button not found under the transcript card; {texts(r, 22)}", [tg + ".png", tg + ".xml"])
            return
        target = btns[0][1]
        action = "Open in data"
    elif typ == "transcripts":
        btns = [hh for hh in find_all(root, r"^Open in Field Repository|^Open in Design") if hh[1][1] > hit[1][1]]
        if btns:
            target = btns[0][1]
            action = label(btns[0][0])
    r0, tg0 = step(f"{tag}-card")
    mark(f"type-open {typ}")
    tap(target)
    root = None
    left = None
    t0 = time.time()
    while time.time() - t0 < 15:
        root = dump(None)
        if not on_search(root):
            left = round(time.time() - t0, 1)
            break
        settle(0.5)
    settle(4)
    root, tg = step(f"{tag}-opened")
    ev = [tg0 + ".png", tg + ".png", tg + ".xml"]
    if left is None:
        record(4, name, "FAIL", f"tap on the {action} did nothing, still on search results after 15 s; {texts(root, 18)}", ev)
        back()
        return
    banner = error_banner(root)
    notice = find(root, r"isn.t filed in a folder yet")
    items = [t for t, _ in V.labelled(root)]
    if not f.get("local", True):
        shown = any(t.startswith(f["title"][:24]) for t in items)
        ok = shown and not banner
        record(4, name, "PASS" if ok else "FAIL", f"opened the other portal's record via {action} after {left}s; title on screen={shown}; error banner={bool(banner)}; {texts(root, 18)}", ev)
    elif typ == "clusters":
        shown = any(f["title"] in t for t in items)
        dw = find(root, r"(Design workshops?|workshops?)") is not None
        ok = shown and dw and not banner and not find(root, r"^Jump to a record$")
        record(4, name, "PASS" if ok else "FAIL", f"cluster tap left search after {left}s; cluster name on screen={shown}; design-workshop list={dw}; error banner={bool(banner)}; {texts(root, 18)}", ev)
    else:
        kind = opened_ok(root, f["title"])
        folder = f.get("locateFolder")
        at_folder = bool(folder) and (V.card_title_loaded(root, folder, A.content_left()) is not None or any(t == folder for t in items))
        ok = (kind or at_folder) and not banner and not notice
        record(4, name, "PASS" if ok else "FAIL", f"{action} tap left search after {left}s; opened as {kind}; locate path {f.get('locatePath')} folder '{folder}' on screen={at_folder}; unfiled notice={label(notice[0]) if notice else None}; error banner={label(banner[0]) if banner else None}; {texts(root, 18)}", ev)
    back()
    settle(1)


def phase_c4_types():
    found = (scout_data.get("search") or {}).get("found") or {}
    missing = (scout_data.get("search") or {}).get("missing") or []
    record(4, "result types found in production search", "INFO", f"found {sorted(found)}; not returned by any of {len(scout_data['search']['tried'])} read-only queries: {missing}", ["scout.json"])
    for t in SEARCH_TYPES:
        if t in found:
            try:
                check_type(t, found[t])
            except Exception as exc:
                record(4, f"search everything {t}", "ERROR", f"{type(exc).__name__}: {exc}")
        else:
            record(4, f"search everything {t}", "NT", "production search returned no result of this type for this account")
    if "@remote" in found and found["@remote"]["type"] not in [t for t in found if t != "@remote" and not found[t].get("local", True)]:
        try:
            check_type("@remote", found["@remote"])
        except Exception as exc:
            record(4, "search everything other-portal result", "ERROR", f"{type(exc).__name__}: {exc}")


def phase_c4_search():
    V_phase_c4_search()
    phase_c4_types()


V_phase_c4_search = V.phase_c4_search


def banner_link(url, name, pattern, from_data):
    healthy(f"c5-{name}")
    go_dashboard()
    where = "Dashboard"
    if from_data:
        V.open_data()
        settle(6)
        where = "Data"
    r, tg_before = step(f"c5-{name}-before")
    mark(f"deeplink {name}")
    sh(f"am start -a android.intent.action.VIEW -d {A.shlex.quote(url)} {PKG}", check=False, timeout=60)
    t0 = time.time()
    polls = []
    shot_tag = None
    while time.time() - t0 < 25:
        root = dump(None)
        msg = find(root, pattern)
        prob = find(root, r"^Problem$")
        done = find(root, r"^Done$")
        polls.append({"t": round(time.time() - t0, 1), "message": label(msg[0]) if msg else None, "problem": bool(prob), "done": bool(done)})
        if msg and not shot_tag:
            r2, shot_tag = step(f"c5-{name}-banner")
            break
        settle(0.5)
    settle(3)
    root, tg = step(f"c5-{name}-after")
    msg_final = find(root, pattern)
    prob_final = find(root, r"^Problem$")
    seen = [p for p in polls if p["message"]]
    ok = bool(seen) and (seen[0]["problem"] or bool(prob_final)) and not any(p["done"] for p in seen)
    ev = [tg_before + ".png"] + ([shot_tag + ".png", shot_tag + ".xml"] if shot_tag else []) + [tg + ".png", tg + ".xml"]
    first = seen[0] if seen else None
    record(5, f"{url.replace(WEB, '')} from {where}", "PASS" if ok else "FAIL", f"banner '{first['message'] if first else None}' at {first['t'] if first else None}s; error tone (Problem icon)={first['problem'] if first else False}; success icon={first['done'] if first else False}; still shown 3 s later={bool(msg_final)}; {len(polls)} polls; on screen {texts(root, 14)}", ev)
    A.save(f"c5-{name}-polls.json", json.dumps(polls, indent=1, ensure_ascii=False))
    back()


def go_open(kind, rid, rname):
    healthy(f"c5-go-open-{kind}")
    go_dashboard()
    mark(f"deeplink go-open-{kind}")
    url = f"{WEB}/data/go/open/{kind}/{rid}"
    sh(f"am start -a android.intent.action.VIEW -d {A.shlex.quote(url)} {PKG}", check=False, timeout=60)
    t0 = time.time()
    shown_at = None
    root = None
    while time.time() - t0 < 25:
        root = dump(None)
        if rname and find(root, re.escape(rname)) and not find(root, r"^Loading"):
            shown_at = round(time.time() - t0, 1)
            break
        settle(0.7)
    root, tg = step(f"c5-go-open-{kind}", wait=2)
    err = find(root, r"(link didn.t open|isn.t one the app can open)") or error_banner(root)
    shown = rname is not None and find(root, re.escape(rname)) is not None
    ok = shown and not err and not find(root, r"^Jump to a record$")
    record(5, f"/data/go/open/{kind}/{rid}", "PASS" if ok else "FAIL", f"{kind} '{rname}' on screen={shown} (first seen {shown_at}s); error={label(err[0]) if err else None}; {texts(root, 16)}", [tg + ".png", tg + ".xml"])
    back()


def phase_c5():
    sf = scout_data.get("small_file") or {}
    folder = sf.get("folder")
    if folder:
        healthy("c5-path")
        go_dashboard()
        lvl = sf["levels"][-1] if sf.get("levels") else {"name": folder.split("/")[-1], "names": scout_data["folders"].get(folder, {}).get("names", [])}
        mark(f"deeplink path {folder}")
        t0 = time.time()
        sh(f"am start -a android.intent.action.VIEW -d {A.shlex.quote(WEB + '/data?path=' + urllib.parse.quote(folder, safe=''))} {PKG}", check=False, timeout=60)
        V.check_scenario(5, f"/data?path={folder}", lvl["name"], lvl["names"], t0, "c5-path")
        back()
    else:
        record(5, "/data?path=", "FAIL", "scout found no folder with a file")
    img = scout_data.get("image")
    if (sf.get("mediaType") or "").upper() == "IMAGE":
        img = {"name": sf["name"], "mediaId": sf["mediaId"], "folder": folder}
    if img and img.get("mediaId"):
        healthy("c5-preview")
        go_dashboard()
        url = f"{WEB}/data?path={urllib.parse.quote(img['folder'], safe='')}&preview={img['mediaId']}"
        mark("deeplink preview-image")
        sh(f"am start -a android.intent.action.VIEW -d {A.shlex.quote(url)} {PKG}", check=False, timeout=60)
        root = None
        t0 = time.time()
        while time.time() - t0 < 20:
            root = dump(None)
            if [n for n in all_nodes(root) if (n.get("content-desc") or "") == img["name"]]:
                break
            settle(0.7)
        settle(2)
        root, tg = step("c5-preview-image")
        img_desc = [n for n in all_nodes(root) if (n.get("content-desc") or "") == img["name"]]
        video = find(root, r"^(Play|Pause|Follow video)$")
        raw_title = find(root, rf"^{re.escape(img['mediaId'])}$")
        kind_line = find(root, r"^(Image|Video|Audio) · ")
        preview_open = find(root, r"^Close$") is not None
        big = None
        if img_desc:
            r = rect(img_desc[0].get("bounds"))
            big = (r[2] - r[0]) * (r[3] - r[1]) if r else None
        ok = preview_open and bool(img_desc) and not video and not raw_title and (not kind_line or label(kind_line[0]).startswith("Image"))
        record(5, f"?preview=<image {img['mediaId']}> renders as an image", "PASS" if ok else "FAIL", f"preview open={preview_open}; image element (content-desc = file name)={bool(img_desc)} area {big}px; kind line={label(kind_line[0]) if kind_line else None}; video controls={label(video[0]) if video else None}; raw-id title={bool(raw_title)}", [tg + ".png", tg + ".xml"])
        back()
        back()
    else:
        record(5, "?preview=<image>", "NT", "scout found no image")
    banner_link(f"{WEB}/data?viewId=bogus-accept-0015b", "viewid-bogus-dashboard", r"saved view isn.t available", False)
    banner_link(f"{WEB}/data?viewId=bogus-accept-0015b-data", "viewid-bogus-data", r"saved view isn.t available", True)
    banner_link(f"{WEB}/data/go/bogus-kind/x1", "go-unknown", r"isn.t one the app can open", False)
    banner_link(f"{WEB}/data/go/bogus-kind/x2", "go-unknown-data", r"isn.t one the app can open", True)
    banner_link(f"{WEB}/data?bundle=accept-0015b-missing-bundle", "bundle-unknown", r"link didn.t open", False)
    if PORTAL == "fr":
        art = scout_data.get("artisan")
        if art and art.get("id"):
            go_open("artisan", art["id"], art.get("name"))
        else:
            record(5, "/data/go/open/artisan/<id>", "FAIL", "scout found no artisan")
    else:
        craft = scout_data.get("craft")
        if craft and craft.get("id"):
            go_open("craft", craft["id"], craft.get("name"))
        else:
            record(5, "/data/go/open/craft/<id>", "FAIL", "scout found no craft")


_scout = A.scout


def scout():
    _scout()
    scout_search()


_run = A.run


def run(name, fn, *a):
    healthy(f"phase-{name}")
    return _run(name, fn, *a)


A.scout = scout
A.run = run
A.phase_c4_search = phase_c4_search
A.phase_c5 = phase_c5
A.phase_c6 = V.phase_c6

if __name__ == "__main__":
    sys.exit(A.main())
