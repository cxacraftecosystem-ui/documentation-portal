#!/usr/bin/env python3
"""Build ``backend/app/data/lgd_register.json``: an LGD code for every state and district the register holds.

Run me from the repo root:

    backend/.venv/Scripts/python.exe scripts/build_lgd_register.py --fetch

WHY THE CODES LIVE IN A FILE AND NOT ON THE RECORDS
---------------------------------------------------
``services/address.py`` holds the closed lists (36 states and union territories, 795 districts) by
NAME. The coverage map needs a stable key per unit that the Field Repository will agree with, and
names are not that key: districts are renamed (Aurangabad became Chhatrapati Sambhajinagar) while their
Local Government Directory code stays the same. So the register's names are joined to their LGD codes
here, once, and ``services/lgd.py`` reads the result. Nothing is stored on a record table: resolution
happens when the map is read, so a refreshed register re-keys every record at once.

SOURCES
-------
  base        github.com/planemad/india-local-government-directory ``administrative/2-district.csv``
              and ``1-state.csv`` — the LGD snapshot of 2022-12-14 that ``address.py`` was built from.
  amendments  lgdirectory.gov.in's own web service (``/webservices/lgdws/districtList``, a form POST
              per state code) for the districts ``address.DISTRICT_AMENDMENTS`` added after that
              snapshot. A district LGD does not list yet goes in ``pending`` with no code; nothing is
              guessed.

Both are cached under ``.boundary-cache/`` (gitignored). The output is deterministic: states and
districts sorted by code, pending sorted by state then district, two-space-free compact indentation.
The same file is committed byte for byte in the Field Repository; ``tests/test_lgd_register.py``
holds that when both checkouts exist.
"""

from __future__ import annotations

import argparse
import csv
import datetime as dt
import json
import pathlib
import sys
import urllib.parse
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "backend"))

from app.services.address import (  # noqa: E402
    DISTRICT_LIST_VERSION,
    DISTRICTS_BY_STATE,
    INDIAN_STATES_AND_UNION_TERRITORIES,
    canonical_district,
    canonical_state,
)

MIRROR = "https://raw.githubusercontent.com/planemad/india-local-government-directory/master/administrative"
LGD_SERVICE = "https://lgdirectory.gov.in/webservices/lgdws"
OUT = ROOT / "backend" / "app" / "data" / "lgd_register.json"

ABOUT = (
    "Local Government Directory (LGD) codes for every state, union territory and district in "
    "backend/app/services/address.py. Names equal the register's canonical names exactly. Built by "
    "scripts/build_lgd_register.py; byte-identical in the Designer Portal and the Field Repository. "
    "A district LGD does not list yet is under 'pending' with no code."
)


def _state_name(raw: str) -> str | None:
    text = raw.strip()
    if text.lower().startswith("the "):
        text = text[4:]
    return canonical_state(text.title()) or canonical_state(text)


def _district_name(state: str, raw: str) -> str | None:
    return canonical_district(state, raw) or canonical_district(state, raw.title())


def fetch(cache: pathlib.Path, refresh: bool) -> None:
    cache.mkdir(parents=True, exist_ok=True)
    (cache / "lgd").mkdir(exist_ok=True)
    for name in ("1-state.csv", "2-district.csv"):
        path = cache / name
        if refresh or not path.exists():
            path.write_bytes(urllib.request.urlopen(f"{MIRROR}/{name}", timeout=120).read())  # noqa: S310
    states_path = cache / "lgd" / "states.json"
    if refresh or not states_path.exists():
        request = urllib.request.Request(f"{LGD_SERVICE}/stateList", data=b"", method="POST")
        states_path.write_bytes(urllib.request.urlopen(request, timeout=120).read())  # noqa: S310
    for row in json.loads(states_path.read_text(encoding="utf-8")):
        code = int(row["stateCode"])
        path = cache / "lgd" / f"state-{code}.json"
        if refresh or not path.exists():
            body = urllib.parse.urlencode({"stateCode": code}).encode()
            request = urllib.request.Request(f"{LGD_SERVICE}/districtList", data=body, method="POST")
            path.write_bytes(urllib.request.urlopen(request, timeout=120).read())  # noqa: S310


def build(cache: pathlib.Path, as_of: str) -> dict:
    state_codes: dict[str, int] = {}
    for row in csv.DictReader((cache / "1-state.csv").open(encoding="utf-8")):
        name = _state_name(row["State Name"])
        if name is None:
            raise SystemExit(f"state not in the register: {row['State Name']!r}")
        state_codes[name] = int(row["State Code"])
    missing_states = set(INDIAN_STATES_AND_UNION_TERRITORIES) - set(state_codes)
    if missing_states:
        raise SystemExit(f"register states with no LGD code: {sorted(missing_states)}")

    codes: dict[tuple[str, str], int] = {}
    dropped: list[str] = []
    for row in csv.DictReader((cache / "2-district.csv").open(encoding="utf-8")):
        state = _state_name(row["State Name"])
        district = _district_name(state, row["District Name"]) if state else None
        if not state or not district:
            dropped.append(f"{row['State Name']}|{row['District Name']}")
            continue
        codes[(state, district)] = int(row["District Code"])

    by_state_code = {code: name for name, code in state_codes.items()}
    live: dict[tuple[str, str], int] = {}
    for path in sorted((cache / "lgd").glob("state-*.json")):
        state = by_state_code.get(int(path.stem.split("-", 1)[1]))
        if state is None:
            continue
        for row in json.loads(path.read_text(encoding="utf-8")):
            district = _district_name(state, row["districtNameEnglish"])
            if district:
                live[(state, district)] = int(row["districtCode"])

    drift = sorted(
        f"{s}|{d}: mirror {codes[(s, d)]} live {live[(s, d)]}"
        for (s, d) in codes
        if (s, d) in live and live[(s, d)] != codes[(s, d)]
    )
    if drift:
        raise SystemExit(f"the mirror and live LGD disagree on a code: {drift}")

    districts, pending = [], []
    for state, names in DISTRICTS_BY_STATE.items():
        for district in names:
            code = codes.get((state, district)) or live.get((state, district))
            if code is None:
                pending.append(
                    {
                        "state": state,
                        "district": district,
                        "reason": f"Not listed by lgdirectory.gov.in on {as_of}",
                    }
                )
                continue
            districts.append({"lgd": code, "stateLgd": state_codes[state], "name": district})

    seen = [row["lgd"] for row in districts]
    if len(seen) != len(set(seen)):
        raise SystemExit("two register districts share one LGD code")

    print(f"  states {len(state_codes)}  districts {len(districts)}  pending {len(pending)}")
    print(f"  mirror rows not in the register (dissolved or merged): {dropped}")
    return {
        "_about": ABOUT,
        "source": "LGD lgdirectory.gov.in district directory",
        "asOf": as_of,
        "listVersion": DISTRICT_LIST_VERSION,
        "states": [
            {"lgd": code, "name": name} for name, code in sorted(state_codes.items(), key=lambda i: i[1])
        ],
        "districts": sorted(districts, key=lambda row: row["lgd"]),
        "pending": sorted(pending, key=lambda row: (row["state"], row["district"])),
    }


def render(register: dict) -> str:
    """One row per line, so a diff of a refresh reads as the districts that changed."""
    lines = ["{"]
    for key in ("_about", "source", "asOf", "listVersion"):
        lines.append(f"  {json.dumps(key)}: {json.dumps(register[key], ensure_ascii=False)},")
    for index, key in enumerate(("states", "districts", "pending")):
        rows = register[key]
        lines.append(f"  {json.dumps(key)}: [")
        for n, row in enumerate(rows):
            comma = "," if n < len(rows) - 1 else ""
            lines.append(f"    {json.dumps(row, ensure_ascii=False)}{comma}")
        lines.append("  ]" + ("," if index < 2 else ""))
    lines.append("}")
    return "\n".join(lines) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fetch", action="store_true", help="re-download the sources")
    parser.add_argument("--cache", default=str(ROOT / ".boundary-cache"))
    parser.add_argument("--as-of", default=dt.date.today().isoformat(), help="the day LGD was read")
    args = parser.parse_args()
    cache = pathlib.Path(args.cache)
    fetch(cache, args.fetch)
    OUT.write_text(render(build(cache, args.as_of)), encoding="utf-8", newline="\n")
    print(f"  wrote {OUT.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
