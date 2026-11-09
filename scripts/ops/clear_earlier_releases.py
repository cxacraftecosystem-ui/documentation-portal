import argparse
import http.client
import json
import os
import re
import subprocess
import sys
import urllib.parse
from datetime import UTC, datetime
from pathlib import Path

DEFAULT_API = "https://d2b34i3e92al6i.cloudfront.net"
DEFAULT_REPO = "cxacraftecosystem-ui/documentation-portal"
DEFAULT_APP = "FieldRepository"
DEFAULT_KEEP = "0.0.1"
S3_HOST = re.compile(r"^(?P<bucket>[a-z0-9.-]+)\.s3(?:\.dualstack)?\.(?P<region>[a-z0-9-]+)\.amazonaws\.com$")


class Refused(RuntimeError):
    pass


def stamp() -> str:
    return datetime.now(UTC).strftime("%Y-%m-%dT%H:%M:%SZ")


def redact(key: str) -> str:
    parts = key.split("/")
    if len(parts) >= 4 and parts[0] == "media":
        parts[1] = "<uploader>"
    return "/".join(parts)


def run(cmd: list[str], payload: str | None = None) -> str:
    env = dict(os.environ)
    env["MSYS_NO_PATHCONV"] = "1"
    done = subprocess.run(cmd, input=payload, capture_output=True, text=True, env=env, check=False)
    if done.returncode != 0:
        raise Refused(f"{' '.join(cmd[:3])} failed: {done.stderr.strip()[:400]}")
    return done.stdout


def live_object(api: str) -> tuple[str, str, str]:
    parsed = urllib.parse.urlsplit(api.rstrip("/") + "/api/app/download")
    conn = http.client.HTTPSConnection(parsed.netloc, timeout=60)
    conn.request("GET", parsed.path, headers={"cache-control": "no-cache"})
    response = conn.getresponse()
    location = response.getheader("location") or ""
    response.read()
    conn.close()
    if response.status not in (302, 307) or not location:
        raise Refused(f"GET /api/app/download answered {response.status} with no redirect, so the live build is unknown")
    target = urllib.parse.urlsplit(location)
    match = S3_HOST.match(target.hostname or "")
    if not match:
        raise Refused(f"the download redirects to {target.hostname}, not an S3 bucket host")
    key = urllib.parse.unquote(target.path.lstrip("/"))
    return match["bucket"], match["region"], key


def s3_apk_versions(bucket: str, region: str, pattern: re.Pattern) -> tuple[list[dict], list[dict]]:
    out = run(
        [
            "aws", "s3api", "list-object-versions", "--bucket", bucket, "--region", region, "--prefix", "media/",
            "--query", "{v: Versions[?ends_with(Key, '.apk')], d: DeleteMarkers[?ends_with(Key, '.apk')]}",
            "--output", "json",
        ]
    )
    data = json.loads(out or "{}")
    versions = [v for v in (data.get("v") or []) if pattern.match(v["Key"])]
    markers = [m for m in (data.get("d") or []) if pattern.match(m["Key"])]
    return versions, markers


def gh_json(args: list[str]) -> object:
    out = run(["gh", "api", *args])
    return json.loads(out) if out.strip() else None


def github_assets(repo: str) -> list[dict]:
    releases = gh_json(["--paginate", "--slurp", f"repos/{repo}/releases?per_page=100"]) or []
    flat: list[dict] = []
    for page in releases:
        for release in page if isinstance(page, list) else [page]:
            for asset in release.get("assets") or []:
                flat.append(
                    {
                        "tag": release.get("tag_name"),
                        "releaseId": release.get("id"),
                        "assetId": asset.get("id"),
                        "name": asset.get("name"),
                        "size": asset.get("size"),
                        "updatedAt": asset.get("updated_at"),
                    }
                )
    return flat


def log(path: Path, entry: dict) -> None:
    line = json.dumps(entry, default=str)
    print(line)
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a", encoding="utf-8") as handle:
        handle.write(line + "\n")


def main() -> int:
    parser = argparse.ArgumentParser(description="Delete every Android build but the live one from S3 and GitHub Releases.")
    parser.add_argument("--api", default=DEFAULT_API)
    parser.add_argument("--repo", default=DEFAULT_REPO)
    parser.add_argument("--app", default=DEFAULT_APP)
    parser.add_argument("--keep", default=DEFAULT_KEEP)
    parser.add_argument("--skip-s3", action="store_true")
    parser.add_argument("--skip-github", action="store_true")
    parser.add_argument("--log", type=Path, default=None)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--dry-run", dest="apply", action="store_false")
    mode.add_argument("--apply", dest="apply", action="store_true")
    parser.set_defaults(apply=False)
    args = parser.parse_args()
    log_path = args.log or Path(f"clear-earlier-releases-{args.app}-{datetime.now(UTC).strftime('%Y%m%dT%H%M%SZ')}.jsonl")
    try:
        return clear(args, log_path)
    except Refused as exc:
        print(f"refused: {exc}")
        return 1


def clear(args: argparse.Namespace, log_path: Path) -> int:
    expected_name = f"{args.app}_Android-App_Release-Build_v{args.keep}.apk"
    pattern = re.compile(rf"^media/[^/]+/[^/]+/{re.escape(args.app)}_Android-App_Release-Build_v[0-9][0-9A-Za-z.+-]*\.apk$")
    bucket, region, live_key = live_object(args.api)
    print(f"live build: s3://{bucket}/{redact(live_key)}")
    if live_key.rsplit("/", 1)[-1] != expected_name:
        message = (
            f"the live download is {live_key.rsplit('/', 1)[-1]}, not {expected_name}. Publish {args.keep}, check "
            "GET /api/app/download serves it, then run this again."
        )
        if args.apply:
            raise Refused(message + " Nothing was deleted.")
        print(f"PREVIEW ONLY: {message} The plan below keeps today's live build instead.")
    head = json.loads(run(["aws", "s3api", "head-object", "--bucket", bucket, "--region", region, "--key", live_key]))
    live_size = int(head["ContentLength"])
    live_version = head.get("VersionId")
    print(f"live object: {live_size} bytes, version {live_version}, modified {head.get('LastModified')}")

    s3_plan: list[dict] = []
    if not args.skip_s3:
        versions, markers = s3_apk_versions(bucket, region, pattern)
        for v in versions:
            if v["Key"] == live_key and (v.get("IsLatest") or v.get("VersionId") == live_version):
                print(f"keep   s3 {redact(v['Key'])} {v['Size']} bytes (the live build)")
                continue
            s3_plan.append({"Key": v["Key"], "VersionId": v["VersionId"], "Size": v.get("Size"), "LastModified": v.get("LastModified")})
        for m in markers:
            s3_plan.append({"Key": m["Key"], "VersionId": m["VersionId"], "Size": 0, "LastModified": m.get("LastModified")})
        if any(p["Key"] == live_key and p["VersionId"] == live_version for p in s3_plan):
            raise Refused("the plan would delete the live object; nothing was deleted")
        total = sum(int(p["Size"] or 0) for p in s3_plan)
        print(f"s3: {len(s3_plan)} object versions to delete, {total} bytes")
        for p in s3_plan:
            print(f"delete s3 {redact(p['Key'])} version {p['VersionId']} {p['Size']} bytes {p['LastModified']}")

    gh_plan: list[dict] = []
    if not args.skip_github:
        assets = github_assets(args.repo)
        kept = False
        for a in assets:
            if a["tag"] == f"v{args.keep}" and a["name"] == expected_name and int(a["size"] or -1) == live_size:
                print(f"keep   github {a['tag']} {a['name']} {a['size']} bytes (same size as the live build)")
                kept = True
                continue
            gh_plan.append(a)
        if not kept:
            print(f"warning: no asset on release v{args.keep} matches the live build ({live_size} bytes); GitHub will hold no APK after this")
        total = sum(int(a["size"] or 0) for a in gh_plan)
        print(f"github: {len(gh_plan)} release assets to delete, {total} bytes (tags and releases stay)")
        for a in gh_plan:
            print(f"delete github {a['tag']} asset {a['assetId']} {a['name']} {a['size']} bytes {a['updatedAt']}")

    if not s3_plan and not gh_plan:
        print("nothing to delete")
        return 0
    if not args.apply:
        print("DRY RUN: nothing was deleted. Run again with --apply.")
        return 0

    for start in range(0, len(s3_plan), 500):
        batch = s3_plan[start:start + 500]
        body = json.dumps({"Objects": [{"Key": p["Key"], "VersionId": p["VersionId"]} for p in batch], "Quiet": False})
        out = json.loads(run(["aws", "s3api", "delete-objects", "--bucket", bucket, "--region", region, "--delete", body]) or "{}")
        done = {(d.get("Key"), d.get("VersionId")) for d in out.get("Deleted") or []}
        for p in batch:
            ok = (p["Key"], p["VersionId"]) in done
            log(log_path, {"at": stamp(), "store": "s3", "bucket": bucket, "key": redact(p["Key"]), "versionId": p["VersionId"], "size": p["Size"], "result": "deleted" if ok else "failed"})
        for e in out.get("Errors") or []:
            print(f"s3 error: {redact(e.get('Key', ''))} {e.get('Code')} {e.get('Message')}")
    for a in gh_plan:
        try:
            run(["gh", "api", "-X", "DELETE", f"repos/{args.repo}/releases/assets/{a['assetId']}"])
            result = "deleted"
        except Refused as exc:
            result = f"failed: {exc}"
        log(log_path, {"at": stamp(), "store": "github", "repo": args.repo, "tag": a["tag"], "assetId": a["assetId"], "name": a["name"], "size": a["size"], "result": result})
    print(f"logged to {log_path.resolve()}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
