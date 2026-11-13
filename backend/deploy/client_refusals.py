from __future__ import annotations

import json
import os
import re
import sys
import urllib.request
from collections import Counter, defaultdict
from dataclasses import dataclass, field
from datetime import datetime
from urllib.parse import parse_qs, urlsplit

LINE = re.compile(
    r'^(?P<ip>\S+) \S+ \S+ \[(?P<time>[^\]]+)\] "(?P<method>[A-Z]+) (?P<target>\S+) [^"]*" '
    r'(?P<status>\d{3}) \S+ "[^"]*" "(?P<agent>[^"]*)"'
)
WRITE_METHODS = {"POST", "PUT", "PATCH"}
WATCHED_STATUSES = {"409", "413", "422"}
STAGED_DELETE = "/api/media/object"
ID_SEGMENT = re.compile(r"/(?=[^/]*\d)[A-Za-z0-9_-]{12,}(?=/|$)")


@dataclass
class Report:
    refusals: Counter = field(default_factory=Counter)
    staged_deletes: dict = field(default_factory=lambda: defaultdict(list))
    repeated_deletes: Counter = field(default_factory=Counter)

    def alerts(self, refusal_threshold: int, burst_threshold: int, burst_seconds: int) -> list[str]:
        out = []
        for (method, route, status, agent), count in sorted(self.refusals.items()):
            if count >= refusal_threshold:
                out.append(f"{count}x {status} on {method} {route} from {agent}")
        for stamps in self.staged_deletes.values():
            stamps.sort()
            start = 0
            widest = 0
            for end in range(len(stamps)):
                while (stamps[end] - stamps[start]).total_seconds() > burst_seconds:
                    start += 1
                widest = max(widest, end - start + 1)
            if widest >= burst_threshold:
                out.append(f"{widest} staged-object deletes within {burst_seconds}s from one client")
        repeated = sum(1 for count in self.repeated_deletes.values() if count > 1)
        if repeated:
            out.append(f"{repeated} staged objects deleted more than once")
        return out


def route_of(path: str) -> str:
    return ID_SEGMENT.sub("/{id}", path)


def agent_family(agent: str) -> str:
    lowered = agent.lower()
    if "okhttp" in lowered:
        return "android"
    if "mozilla" in lowered:
        return "browser"
    return "other"


def scan(lines) -> Report:
    report = Report()
    for raw in lines:
        match = LINE.match(raw)
        if not match:
            continue
        method, target, status = match["method"], match["target"], match["status"]
        parts = urlsplit(target)
        if not parts.path.startswith("/api/"):
            continue
        family = agent_family(match["agent"])
        if method in WRITE_METHODS and status in WATCHED_STATUSES:
            report.refusals[(method, route_of(parts.path), status, family)] += 1
        if method == "DELETE" and parts.path == STAGED_DELETE and status.startswith("2"):
            key = (parse_qs(parts.query).get("objectKey") or [""])[0]
            if "staged-" in key:
                stamp = datetime.strptime(match["time"], "%d/%b/%Y:%H:%M:%S %z")
                report.staged_deletes[match["ip"]].append(stamp)
                report.repeated_deletes[key] += 1
    return report


def read_new(log_path: str, state_path: str):
    try:
        with open(state_path, encoding="utf-8") as handle:
            state = json.load(handle)
    except (OSError, ValueError):
        state = {}
    stat = os.stat(log_path)
    offset = state.get("offset", 0) if state.get("inode") == stat.st_ino and state.get("offset", 0) <= stat.st_size else 0
    with open(log_path, encoding="utf-8", errors="replace") as handle:
        handle.seek(offset)
        lines = handle.readlines()
        offset = handle.tell()
    with open(state_path, "w", encoding="utf-8") as handle:
        json.dump({"inode": stat.st_ino, "offset": offset}, handle)
    return lines


def notify(url: str, portal: str, alerts: list[str]) -> None:
    body = json.dumps({"text": f"{portal}: client refusals\n" + "\n".join(alerts)}).encode()
    request = urllib.request.Request(url, data=body, headers={"Content-Type": "application/json"})
    urllib.request.urlopen(request, timeout=10).close()


def main() -> int:
    log_path = os.environ.get("CLIENT_REFUSALS_LOG", "/var/log/nginx/access.log")
    state_path = os.environ.get("CLIENT_REFUSALS_STATE", "/var/lib/client-refusals/state.json")
    portal = os.environ.get("CLIENT_REFUSALS_PORTAL", "api")
    os.makedirs(os.path.dirname(state_path), exist_ok=True)
    report = scan(read_new(log_path, state_path))
    alerts = report.alerts(
        int(os.environ.get("CLIENT_REFUSALS_THRESHOLD", "3")),
        int(os.environ.get("CLIENT_REFUSALS_BURST", "3")),
        int(os.environ.get("CLIENT_REFUSALS_BURST_SECONDS", "10")),
    )
    for alert in alerts:
        print(f"<4>client-refusals: {alert}", flush=True)
    url = os.environ.get("CLIENT_REFUSALS_WEBHOOK", "")
    if alerts and url:
        try:
            notify(url, portal, alerts)
        except OSError as exc:
            print(f"<3>client-refusals: webhook failed: {exc}", flush=True)
    if not alerts:
        print("client-refusals: nothing to report", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
