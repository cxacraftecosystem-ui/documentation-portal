#!/usr/bin/env python3
import os
import re
import sys
import time
import urllib.parse

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import accept_0015 as A
from accept_0015 import (adb, sh, settle, dump, shot, step, find, find_all, all_nodes, label, rect, texts, tap,
                         screen_size, content_left, swipe, back, hide_ime, mark, record, names_in, scout_data, api,
                         go_dashboard, on_dashboard, PORTAL, PKG, WEB, CFG, STATUSES, EMPTY_CARD, incidents, alive, launch, entry_names)

COUNT = re.compile(r"^\d[\d,]* (folders?|files?)\b")
TAB_WORDS = {"Files", "Tables", "Transcripts"}


def labelled(root, left=0):
    out = []
    for n in all_nodes(root):
        t = label(n).strip()
        r = rect(n.get("bounds"))
        if t and r and r[2] > r[0] and r[3] > r[1] and r[0] >= left:
            out.append((t, r))
    return out


def card_title_loaded(root, name, left):
    items = labelled(root, left)
    for i, (t, r) in enumerate(items):
        if t == name or (len(name) > 20 and t.startswith(name[:20])):
            for t2, _ in items[i + 1:i + 5]:
                if COUNT.match(t2):
                    return t2
    return None


def count_line(root, left):
    for t, r in labelled(root, left):
        if COUNT.match(t):
            return t
    return None


def wait_loaded(name, names, t0, limit=12.0, left=None, empty_ok=True):
    if left is None:
        left = content_left()
    want = [n for n in names if n and n not in TAB_WORDS] or [n for n in names if n]
    while time.time() - t0 < limit:
        td = time.time()
        root = dump(None)
        line = card_title_loaded(root, name, left)
        hits = names_in(root, want, left)
        empty = empty_ok and find(root, EMPTY_CARD) is not None
        if line or (hits and count_line(root, left)) or (empty and not names):
            return {"t": round(td - t0, 2), "count": line or count_line(root, left), "hits": hits[:5], "empty": bool(empty)}, root
        if find(root, r"didn.t load"):
            return {"t": None, "error": "This folder didn't load"}, root
        settle(0.25)
    return None, dump(None)


def reveal_rows(root, names, left):
    want = [n for n in names if n and n not in TAB_WORDS] or names
    hits = names_in(root, want, left)
    if hits or not want:
        return hits, root, False
    w, h = screen_size()
    x = (left + w) // 2 if left else w // 2
    swipe(x, int(h * 0.8), int(h * 0.35))
    root = dump(None)
    return names_in(root, want, left), root, True


def find_pill(root, name):
    left = content_left()
    hits = find_all(root, rf"^{re.escape(name)}$")
    if left:
        hits = [h for h in hits if h[1][0] < left]
    hits.sort(key=lambda h: h[1][1])
    return hits[0] if hits else None


def tap_pill(name):
    w, h = screen_size()
    left = content_left()
    x = left // 2 if left else w // 2
    for attempt in range(7):
        root = dump(None)
        hit = find_pill(root, name)
        if hit:
            tap(hit[1])
            return True
        if attempt < 3:
            swipe(x, int(h * 0.35), int(h * 0.7), 300)
        else:
            swipe(x, int(h * 0.7), int(h * 0.45), 300)
    return False


def menu_x(root):
    for pat in (r"^Dashboard$", r"^Walkthrough$", r"^Record artisan$", r"^Logout$"):
        hit = find(root, pat)
        if hit:
            return (hit[1][0] + hit[1][2]) // 2
    return screen_size()[0] // 2


drawer_shots = []


def open_data(tag=""):
    go_dashboard()
    if A.tap_text(r"^Open menu$", timeout=10):
        settle(1.5)
        root = dump(None)
        x = menu_x(root)
        for i in range(12):
            hit = find(root, r"^Data$")
            if hit:
                mark("open-data-menu")
                t = time.time()
                tap(hit[1])
                return t, "menu"
            if tag:
                root, tg = step(f"{tag}-drawer-{i}")
                drawer_shots.append(tg + ".png")
            w, h = screen_size()
            swipe(x, int(h * 0.75), int(h * 0.45), 500)
            root = dump(None)
        back()
    root = go_dashboard()
    for _ in range(3):
        cat = find(root, r"^Show the \d+ cards under (Miscellaneous|Records)$")
        if not cat:
            break
        tap(cat[1])
        settle(1.5)
        root = dump(None)
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
        drawer_shots.append(tg + ".png")
    return None, None


def tax_by_path(path):
    return next((t for t in scout_data["taxonomies"] if t["path"] == path), None)


def ui_levels(path):
    tr = api.tree(path)
    crumbs = [c for c in (tr or {}).get("crumbs", []) if c.get("path")]
    if not crumbs or crumbs[-1].get("path") != path:
        crumbs.append({"name": path.split("/")[-1], "path": path})
    levels = []
    for c in crumbs:
        p = c["path"]
        sub = api.tree(p)
        names = entry_names(sub)
        lvl = {"name": c.get("name"), "path": p, "names": names, "total": (sub or {}).get("total"), "folders": sum(1 for e in (sub or {}).get("entries", []) if e.get("kind") == "folder")}
        if not levels:
            if tax_by_path(p):
                levels.append(lvl)
            continue
        if lvl["name"] in levels[-1]["names"]:
            levels.append(lvl)
    return levels


def scout_more():
    chain = scout_data.get("chain") or {}
    if chain.get("target"):
        scout_data["ui_chain"] = ui_levels(chain["target"])
    cands = []
    for p, f in scout_data["folders"].items():
        if f.get("files", 0) > 0:
            cands.append((f.get("folders", 0) + f.get("files", 0), p))
    cands.sort()
    pick = None
    for _, p in cands[:12]:
        tr = api.tree(p)
        files = [e for e in (tr or {}).get("entries", []) if e.get("kind") != "folder" and e.get("name")]
        imgs = [e for e in files if (e.get("mediaType") or "").upper() == "IMAGE" and e.get("mediaId")]
        if imgs:
            pick = (p, imgs[0])
            break
        if files and not pick:
            pick = (p, files[0])
    if pick:
        p, e = pick
        scout_data["small_file"] = {"folder": p, "name": e.get("name"), "mediaId": e.get("mediaId"), "mediaType": e.get("mediaType"), "levels": ui_levels(p)}
    A.save("scout.json", __import__("json").dumps(scout_data, indent=1, ensure_ascii=False))


def check_scenario(crit, title, name, names, t0, tag, folder_count=None, limit=12.0):
    left = content_left()
    got, root = wait_loaded(name, names, t0, limit=limit, left=left)
    hits, root2, scrolled = reveal_rows(root, names, left)
    root, tg = step(tag)
    ev = [tg + ".png", tg + ".xml"]
    if not got or got.get("t") is None:
        record(crit, title, "FAIL", f"not loaded within {limit}s; {got}; on screen {texts(root, 24)}", ev)
        return False
    ok = got["t"] <= 5.0
    shown = bool(hits) or (not [n for n in names if n not in TAB_WORDS] and got.get("empty"))
    detail = f"loaded {got['t']}s after the tap (card '{got.get('count')}'); rows on screen {hits[:4]}{' after one scroll' if scrolled else ''}; empty card={got.get('empty')}; api entries {len(names)}"
    if folder_count is not None and got.get("count") and "folder" in got["count"]:
        m = re.match(r"^([\d,]+) folder", got["count"])
        if m and int(m.group(1).replace(",", "")) != folder_count:
            detail += f"; COUNT MISMATCH api folders {folder_count}"
            ok = False
    record(crit, title, "PASS" if ok and shown else "FAIL", detail, ev)
    return ok and shown


def phase_c1():
    t_tap, how = open_data("c1")
    if not t_tap:
        record(1, "Data reachable", "FAIL", "no Data menu item or tile", drawer_shots[-6:])
        return
    default = next((t for t in scout_data["taxonomies"] if t.get("default")), None)
    st0 = STATUSES[0][0]
    if default:
        names = scout_data["names"].get(f"{default['path']}|{st0}", [])
        check_scenario(1, f"landing via {how} (default '{default['name']}', All)", default["name"], names, t_tap, "c1-landing")
    for t in scout_data["taxonomies"]:
        if not tap_pill(t["name"]):
            root, tg = step(f"c1-nopill-{t['path']}")
            record(1, f"organise by {t['name']}", "FAIL", f"pill not found; {texts(root, 25)}", [tg + ".png"])
            continue
        mark(f"taxonomy {t['path']}")
        t0 = time.time()
        names = scout_data["names"].get(f"{t['path']}|{st0}", [])
        check_scenario(1, f"organise by {t['name']} (All)", t["name"], names, t0, f"c1-tax-{t['path']}")
    if PORTAL == "fr":
        for tax in scout_data["taxonomies"]:
            if not tap_pill(tax["name"]):
                continue
            settle(4)
            for value, lab in STATUSES[1:] + STATUSES[:1]:
                if not tap_pill(lab):
                    root, tg = step(f"c1-nostatus-{tax['path']}-{value}")
                    record(1, f"status {lab} at {tax['name']}", "FAIL", f"status pill not found; {texts(root, 25)}", [tg + ".png"])
                    continue
                mark(f"status {value} {tax['path']}")
                t0 = time.time()
                tr = api.tree(tax["path"], value)
                names = entry_names(tr)
                fc = sum(1 for e in (tr or {}).get("entries", []) if e.get("kind") == "folder") if tr and not tr.get("nextCursor") else None
                check_scenario(1, f"status {lab} at {tax['name']}", tax["name"], names, t0, f"c1-status-{tax['path']}-{value.lower()}", folder_count=fc)


def tap_folder(name, left):
    w, h = screen_size()
    x = (left + w) // 2 if left else w // 2
    for i in range(10):
        root = dump(None)
        hits = [hh for hh in find_all(root, rf"^{re.escape(name)}$") if hh[1][0] >= left]
        if hits:
            hits.sort(key=lambda hh: -hh[1][1])
            tap(hits[0][1])
            return True
        swipe(x, int(h * 0.78), int(h * 0.42))
    return False


def descend(levels, crit, prefix, tablet_checks=True):
    left = content_left()
    tax = levels[0]
    if not tap_pill(tax["name"]):
        record(crit, f"taxonomy {tax['name']}", "FAIL", "pill not found")
        return 0
    mark(f"open {tax['path']}")
    t0 = time.time()
    check_scenario(crit, f"open '{tax['name']}'", tax["name"], tax["names"], t0, f"{prefix}-level0")
    if tablet_checks:
        tablet_check(tax)
    opened = 0
    for lvl in levels[1:]:
        mark(f"open {lvl['path']}")
        if not tap_folder(lvl["name"], left):
            root, tg = step(f"{prefix}-missing-{opened + 1}")
            record(crit, f"open '{lvl['name']}'", "FAIL", f"folder row not found; {texts(root, 25)}", [tg + ".png", tg + ".xml"])
            break
        t0 = time.time()
        opened += 1
        check_scenario(crit, f"descend to '{lvl['name']}'", lvl["name"], lvl["names"], t0, f"{prefix}-level{opened}", folder_count=lvl["folders"] if not lvl.get("total") or lvl["total"] == len(lvl["names"]) else None)
        if tablet_checks:
            tablet_check(lvl)
    return opened


def tablet_check(lvl):
    if A.PROFILE != "tablet":
        return
    left = content_left()
    root, tg = step(f"tablet-pane-{lvl['path'].replace('/', '_')[:60]}")
    folders = [n for n in lvl["names"] if n not in TAB_WORDS]
    hits = names_in(root, folders, left)
    empty = find(root, EMPTY_CARD)
    right = [t for t, _ in labelled(root, left)][:14]
    ok = bool(hits) or (not folders and empty)
    record(3, f"tablet content pane at '{lvl['name']}' shows content", "PASS" if ok else "FAIL", f"content-pane rows {hits[:5]}; right-pane labels {right}", [tg + ".png", tg + ".xml"])


def phase_c3():
    levels = scout_data.get("ui_chain") or []
    if len(levels) < 2:
        record(3, "descend chain", "FAIL", f"no usable chain: {levels}")
        return
    go_dashboard()
    t, how = open_data()
    settle(4)
    record(3, "chain", "INFO", " > ".join(l["name"] for l in levels))
    opened = descend(levels, 3, "c3")
    for i in range(opened, 0, -1):
        parent = levels[i - 1]
        mark(f"back to {parent['path']}")
        t0 = time.time()
        back()
        check_scenario(3, f"system Back to '{parent['name']}'", parent["name"], parent["names"], t0, f"c3-back-to-{i - 1}")
        tablet_check(parent)
    back()
    root, tg = step("c3-back-out", wait=1)
    record(3, "system Back from the top leaves Data", "PASS" if on_dashboard(root) else "FAIL", f"{texts(root, 12)}", [tg + ".png"])


def opened_ok(root, title):
    short = title[:28]
    items = labelled(root)
    for i, (t, r) in enumerate(items):
        if t.startswith(short):
            if any(COUNT.match(t2) for t2, _ in items[i + 1:i + 5]):
                return "folder"
    if find(root, r"^Close$") and any(t.startswith(short.rsplit(".", 1)[0][:24]) for t, _ in items):
        return "preview"
    if not find(root, r"^Jump to a record$") and not find(root, r"^(This app|Both portals)$") and any(t.startswith(short) for t, _ in items):
        return "record"
    return None


def phase_c4_file():
    sf = scout_data.get("small_file")
    if not sf:
        record(4, "tap a file", "FAIL", "scout found no file")
        return
    go_dashboard()
    open_data()
    settle(4)
    levels = sf["levels"]
    record(4, "file target", "INFO", f"{sf['name']} in {' > '.join(l['name'] for l in levels)}")
    descend(levels, 4, "c4", tablet_checks=False)
    hit = None
    w, h = screen_size()
    left = content_left()
    for i in range(10):
        root = dump(None)
        hit = find(root, rf"^{re.escape(sf['name'])}$")
        if hit:
            break
        swipe((left + w) // 2 if left else w // 2, int(h * 0.78), int(h * 0.42))
    if not hit:
        root, tg = step("c4-file-not-found")
        record(4, "tap a file row", "FAIL", f"'{sf['name']}' not on screen; {texts(root, 25)}", [tg + ".png", tg + ".xml"])
        return
    root, tg0 = step("c4-file-row")
    mark("tap file")
    tap(hit[1])
    root, tg1 = step("c4-file-open-2s", wait=2)
    root, tg2 = step("c4-file-open-8s", wait=5)
    in_list = find(root, r"^Jump to a record$") is not None and find(root, rf"^{re.escape(sf['name'])}$") is not None
    n = len(labelled(root))
    ok = n >= 6 and not in_list
    record(4, f"tap file '{sf['name']}' ({sf.get('mediaType')})", "PASS" if ok else "FAIL", f"{n} labelled nodes; {texts(root, 18)}", [tg0 + ".png", tg1 + ".png", tg2 + ".png", tg2 + ".xml"])
    mark("back from file")
    back()
    root, tg = step("c4-file-back", wait=2)
    record(4, "system Back from the opened file returns to Data", "PASS" if find(root, r"^(View data|Jump to a record)$") else "FAIL", f"{texts(root, 14)}", [tg + ".png"])


def type_jump(term):
    root = dump(None)
    clear = find(root, r"^Clear$")
    if clear:
        tap(clear[1])
        settle(1)
    hit = None
    for _ in range(4):
        root = dump(None)
        hit = find(root, r"^Jump to a record$")
        if hit:
            break
        w, h = screen_size()
        swipe(w // 2, int(h * 0.35), int(h * 0.7), 300)
    if not hit:
        return False
    tap(hit[1])
    settle(0.8)
    sh("input text " + A.shlex.quote(term))
    return True


def phase_c4_search():
    go_dashboard()
    open_data()
    settle(4)
    done = 0
    skip = {"Clear", "Everything", "Artisans", "Workshops", "Products", "Tools", "Media", "Design workshops", "Filters", "View data", "Downloads", "Back"} | {t["name"] for t in scout_data["taxonomies"]}
    for term in CFG["jump"]:
        if not type_jump(term):
            record(4, "quick jump field", "FAIL", "Jump to a record field not found")
            break
        settle(4)
        hide_ime()
        root, tg = step(f"c4-jump-{term}")
        rows = []
        for n, r in find_all(root, re.escape(term)):
            t = label(n).strip()
            if t in skip or t == term or t.startswith(("Search everything", "No matches", "Show all", "Showing")) or r[1] < 250:
                continue
            rows.append((t, r))
        if not rows:
            record(4, f"quick jump '{term}'", "INFO", f"no hits: {texts(root, 18)}", [tg + ".png"])
            continue
        title, r = rows[0]
        mark(f"jump open {title}")
        tap(r)
        root, tg2 = step(f"c4-jump-open-{term}-3s", wait=3)
        root, tg3 = step(f"c4-jump-open-{term}-8s", wait=5)
        kind = opened_ok(root, title)
        notice = find(root, r"isn.t filed in a folder yet")
        record(4, f"quick jump '{term}' opens '{title}'", "PASS" if kind and not notice else "FAIL", f"opened as {kind}; unfiled notice={bool(notice)}; {texts(root, 22)}", [tg + ".png", tg2 + ".png", tg3 + ".png", tg3 + ".xml"])
        done += 1
        back()
        if done >= 2:
            break
    if not done:
        record(4, "quick jump", "NT", f"no quick-jump hits in production for {CFG['jump']}")
    opened = 0
    for term in CFG["everything"]:
        go_dashboard()
        open_data()
        settle(4)
        if not type_jump(term):
            break
        settle(3)
        hide_ime()
        hit = A.tap_text(r"^Search everything", timeout=6)
        root, tg = step(f"c4-everything-{term}", wait=6)
        if not find(root, r"^(This app|Both portals)$"):
            record(4, f"search everything '{term}'", "FAIL" if hit else "INFO", f"search-everything screen did not open (button tapped={bool(hit)}); {texts(root, 18)}", [tg + ".png"])
            continue
        cards = []
        for n, r in find_all(root, re.escape(term)):
            t = label(n).strip()
            if t == term or re.match(r"^.+\s\d+$", t) and len(t) < 24 or t.startswith(("Search", "All ")) or r[1] < 350:
                continue
            cards.append((t, r))
        if not cards:
            record(4, f"search everything '{term}'", "INFO", f"no result cards: {texts(root, 25)}", [tg + ".png"])
            continue
        tried = []
        for title, r in cards[:3]:
            mark(f"everything open {title[:40]}")
            tap(r)
            root, tg2 = step(f"c4-everything-open-{term}-{len(tried)}-3s", wait=3)
            root, tg3 = step(f"c4-everything-open-{term}-{len(tried)}-8s", wait=5)
            still = find(root, r"^(This app|Both portals)$") is not None
            kind = None if still else opened_ok(root, title)
            notice = find(root, r"isn.t filed in a folder yet")
            tried.append(title)
            if still:
                record(4, f"search everything '{term}': tap '{title}'", "FAIL", f"tap did nothing, still on search results; {texts(root, 16)}", [tg2 + ".png", tg3 + ".png", tg3 + ".xml"])
                continue
            record(4, f"search everything '{term}' opens '{title}'", "PASS" if kind and not notice else "FAIL", f"opened as {kind}; unfiled notice={label(notice[0]) if notice else None}; {texts(root, 22)}", [tg + ".png", tg2 + ".png", tg3 + ".png", tg3 + ".xml"])
            opened += 1
            break
        if opened >= 2:
            break
    if not opened:
        record(4, "search everything", "NT", f"no openable results for {CFG['everything']}")


def deeplink(url, name, polls=0):
    mark(f"deeplink {name}")
    sh(f"am start -a android.intent.action.VIEW -d {A.shlex.quote(url)} {PKG}", check=False, timeout=60)
    seen = []
    t0 = time.time()
    for i in range(polls):
        root = dump(None)
        msg = [label(n) for n in all_nodes(root) if re.search(r"(saved view|link)", label(n), re.I)]
        icon = [label(n) for n in all_nodes(root) if label(n) in ("Problem", "Done")]
        seen.append({"t": round(time.time() - t0, 2), "message": msg[:2], "icon": icon[:2]})
        if msg and len([s for s in seen if s["message"]]) == 1:
            shot(f"c5-{name}-banner-{i}")
    root, tg1 = step(f"c5-{name}-a", wait=0 if polls else 2)
    root2, tg2 = step(f"c5-{name}-b", wait=5)
    return root, root2, [tg1 + ".png", tg1 + ".xml", tg2 + ".png", tg2 + ".xml"], seen


def phase_c5():
    sf = scout_data.get("small_file") or {}
    folder = sf.get("folder")
    left = content_left()
    if folder:
        go_dashboard()
        lvl = sf["levels"][-1] if sf.get("levels") else {"name": folder.split("/")[-1], "names": scout_data["folders"].get(folder, {}).get("names", [])}
        mark(f"deeplink path {folder}")
        t0 = time.time()
        sh(f"am start -a android.intent.action.VIEW -d {A.shlex.quote(WEB + '/data?path=' + urllib.parse.quote(folder, safe=''))} {PKG}", check=False, timeout=60)
        check_scenario(5, f"/data?path={folder}", lvl["name"], lvl["names"], t0, "c5-path")
        back()
    img = scout_data.get("image")
    if sf.get("mediaType", "").upper() == "IMAGE":
        img = {"name": sf["name"], "mediaId": sf["mediaId"], "folder": folder}
    if img and img.get("mediaId"):
        go_dashboard()
        url = f"{WEB}/data?path={urllib.parse.quote(img['folder'], safe='')}&preview={img['mediaId']}"
        r1, root, ev, _ = deeplink(url, "preview-image")
        img_desc = [n for n in all_nodes(root) if (n.get("content-desc") or "") == img["name"]]
        video = find(root, r"^(Play|Pause|Follow video)$")
        raw_title = find(root, rf"^{re.escape(img['mediaId'])}$")
        kind_line = find(root, r"^(Image|Video|Audio) · ")
        preview_open = find(root, r"^Close$") is not None
        ok = preview_open and bool(img_desc) and not video and not raw_title and (not kind_line or label(kind_line[0]).startswith("Image"))
        record(5, f"?preview=<image {img['mediaId']}>", "PASS" if ok else "FAIL", f"preview open={preview_open}; image element={bool(img_desc)}; kind line={label(kind_line[0]) if kind_line else None}; video controls={label(video[0]) if video else None}; raw-id title={bool(raw_title)}", ev)
        back()
        back()
    go_dashboard()
    root1, root, ev, seen = deeplink(f"{WEB}/data?viewId=bogus-accept-0015", "viewid-bogus", polls=10)
    A.save("c5-viewid-polls.json", __import__("json").dumps(seen, indent=1, ensure_ascii=False))
    any_msg = [s for s in seen if s["message"]]
    final = find(root, r"saved view isn.t available")
    problem = find(root, r"^Problem$") or find(root1, r"^Problem$")
    done = find(root, r"^Done$") or find(root1, r"^Done$")
    ok = bool(final or any_msg) and bool(problem or any(("Problem" in s["icon"]) for s in any_msg)) and not done and not any(("Done" in s["icon"]) for s in any_msg)
    record(5, "?viewId=bogus", "PASS" if ok else "FAIL", f"polls with a message {len(any_msg)}/{len(seen)} (first {any_msg[:1]}); message on the final screens={bool(final)}; icon Problem={bool(problem)}; icon Done={bool(done)}", ev + ["c5-viewid-polls.json"])
    back()
    art = scout_data.get("artisan")
    if art and art.get("id") and art.get("path"):
        go_dashboard()
        r1, root, ev, _ = deeplink(f"{WEB}/data/go/open/artisan/{art['id']}", "go-open-artisan")
        shown = find(root, re.escape(art.get("name") or "@@none@@")) is not None
        err = find(root, r"(link didn.t open|isn.t one the app can open)")
        record(5, f"/data/go/open/artisan/{art['id']}", "PASS" if shown and not err and not find(root, r"^Jump to a record$") else "FAIL", f"artisan '{art.get('name')}' on screen={shown}; error={label(err[0]) if err else None}; {texts(root, 14)}", ev)
        back()
    else:
        record(5, "/data/go/open/artisan/<id>", "NT", "production has no artisan record visible to this account")
        craft = scout_data.get("craft")
        if craft:
            go_dashboard()
            r1, root, ev, _ = deeplink(f"{WEB}/data/go/open/craft/{craft['id']}", "go-open-craft")
            shown = find(root, re.escape(craft.get("name") or "@@none@@")) is not None
            err = find(root, r"(link didn.t open|isn.t one the app can open)")
            record(5, f"substitute /data/go/open/craft/{craft['id']}", "PASS" if shown and not err else "FAIL", f"craft '{craft.get('name')}' on screen={shown}; error={label(err[0]) if err else None}", ev)
            back()


def phase_c6():
    go_dashboard()
    open_data()
    settle(4)
    tax = next((t for t in scout_data["taxonomies"] if [n for n in scout_data["names"].get(f"{t['path']}|{STATUSES[0][0]}", []) if n not in TAB_WORDS]), None)
    if not tax:
        record(6, "taxonomy with folders", "FAIL", "none")
        return
    names = scout_data["names"].get(f"{tax['path']}|{STATUSES[0][0]}", [])
    tap_pill(tax["name"])
    mark(f"open {tax['path']}")
    check_scenario(6, f"before rotation: '{tax['name']}'", tax["name"], names, time.time(), "c6-before")
    sh("settings put system accelerometer_rotation 0", check=False)
    sh("settings put system user_rotation 1", check=False)
    mark("rotate landscape")
    check_scenario(6, "after rotating to landscape", tax["name"], names, time.time(), "c6-landscape")
    sh("settings put system user_rotation 0", check=False)
    mark("rotate back")
    check_scenario(6, "after rotating back", tax["name"], names, time.time(), "c6-portrait")
    sh("settings put system font_scale 2.0", check=False)
    mark("font scale 2")
    check_scenario(6, "font scale 2 applied on Data", tax["name"], names, time.time(), "c6-font2")
    go_dashboard()
    t, how = open_data("c6-font2")
    if t:
        lvl_names = names
        got, root = wait_loaded(tax["name"], lvl_names, t, limit=12)
        hits, root, scrolled = reveal_rows(root, lvl_names, content_left())
        root, tg = step("c6-font2-reopened")
        record(6, f"font scale 2: Data reopened via {how}", "PASS" if got and got.get("t") is not None else "FAIL", f"{got}; rows {hits[:4]}", [tg + ".png", tg + ".xml"])
    else:
        record(6, "font scale 2: Data reopened from the menu or tile", "FAIL", "Data entry not found at font scale 2", drawer_shots[-8:])
    sh("settings put system font_scale 1.0", check=False)
    settle(3)


def phase_soak(heap):
    go_dashboard()
    open_data()
    settle(5)
    heap.sample("soak-start")
    start = time.time()
    cycle = 0
    taxes = list(scout_data["taxonomies"])
    chain = scout_data.get("ui_chain") or []
    while time.time() - start < A.SOAK_SECONDS:
        cycle += 1
        if taxes:
            t = taxes[cycle % len(taxes)]
            mark(f"soak-open taxonomy {t['path']}")
            tap_pill(t["name"])
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
            mark(f"soak-status {lab}")
            tap_pill(lab)
            settle(6)
        mark(f"soak-idle {cycle}")
        time.sleep(45)
        if cycle % 4 == 0:
            step(f"soak-cycle-{cycle}")
        if not alive():
            incidents.append(f"app not running during soak cycle {cycle}")
            launch()
            open_data()
            settle(5)
    mark("soak-end")
    heap.sample("soak-end")
    step("soak-end")
    return round(time.time() - start)


_orig_scout = A.scout


def scout():
    _orig_scout()
    scout_more()


A.scout = scout
A.open_data = open_data
A.phase_c1 = phase_c1
A.phase_c3 = phase_c3
A.phase_c4_file = phase_c4_file
A.phase_c4_search = phase_c4_search
A.phase_c5 = phase_c5
A.phase_c6 = phase_c6
A.phase_soak = phase_soak
A.scroll_to_top = lambda: None

if __name__ == "__main__":
    sys.exit(A.main())
