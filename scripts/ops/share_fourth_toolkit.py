import argparse
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
from datetime import UTC, datetime
from pathlib import Path

DEFAULT_API = "https://d2b34i3e92al6i.cloudfront.net"
DEFAULT_FORM_ID = "qnr_4th_craft_toolkit_workshop"
DEFAULT_TITLE = "4th Craft Toolkit Workshop"
DEFAULT_REFERENCE_ID = "qnr_3rd_craft_toolkit_workshop"
DEFAULT_REFERENCE_TITLE = "3rd Craft Toolkit Workshop"
CONTENT_ADMIN_ROLES = {"ADMIN", "MASTER_ADMIN", "MINISTRY_ADMIN"}
FIELDS = ("id", "title", "isShared", "isActive", "isDefault", "deletedAt", "kind", "ownerId", "version")


class Refused(RuntimeError):
    pass


def stamp() -> str:
    return datetime.now(UTC).strftime("%Y-%m-%dT%H:%M:%SZ")


class Api:
    def __init__(self, base: str) -> None:
        self.base = base.rstrip("/")
        self.token = ""

    def call(self, method: str, path: str, body: object | None = None) -> tuple[int, object]:
        data = None if body is None else json.dumps(body).encode("utf-8")
        headers = {"accept": "application/json", "cache-control": "no-cache"}
        if data is not None:
            headers["content-type"] = "application/json"
        if self.token:
            headers["authorization"] = f"Bearer {self.token}"
        request = urllib.request.Request(self.base + path, data=data, method=method, headers=headers)
        try:
            with urllib.request.urlopen(request, timeout=180) as response:
                raw = response.read()
                status = response.status
        except urllib.error.HTTPError as err:
            raw = err.read()
            status = err.code
        try:
            parsed = json.loads(raw) if raw else None
        except ValueError:
            parsed = {"detail": raw[:300].decode("utf-8", "replace")}
        return status, parsed

    def sign_in(self, email_env: str, password_env: str, totp_env: str) -> dict:
        email = os.environ.get(email_env, "")
        password = os.environ.get(password_env, "")
        if not email or not password:
            raise Refused(f"set {email_env} and {password_env} (an admin account) before running")
        status, payload = self.call("POST", "/api/auth/login", {"email": email, "password": password})
        if status != 200 or not isinstance(payload, dict):
            raise Refused(f"sign-in answered {status}")
        if payload.get("two_factor_required"):
            code = os.environ.get(totp_env, "")
            if not code:
                raise Refused(f"this account has two-factor sign-in; put a current code in {totp_env} and run again")
            status, payload = self.call(
                "POST",
                "/api/auth/two-factor/verify",
                {"challenge_token": payload.get("challenge_token", ""), "code": code},
            )
            if status != 200 or not isinstance(payload, dict):
                raise Refused(f"two-factor verify answered {status}")
        self.token = payload.get("accessToken") or payload.get("access_token") or ""
        if not self.token:
            raise Refused("sign-in answered 200 with no token")
        return payload.get("user") or {}

    def forms(self) -> list[dict]:
        found: list[dict] = []
        page = 1
        while True:
            query = urllib.parse.urlencode({"activeOnly": "false", "page": page, "pageSize": 100})
            status, payload = self.call("GET", f"/api/questionnaires?{query}")
            if status != 200:
                raise Refused(f"GET /api/questionnaires answered {status}")
            if isinstance(payload, list):
                return payload
            if not isinstance(payload, dict):
                raise Refused("GET /api/questionnaires answered an unexpected shape")
            found.extend(payload.get("items") or [])
            if page >= int(payload.get("pages") or 1):
                return found
            page += 1


def summary(form: dict) -> dict:
    return {key: form.get(key) for key in FIELDS}


def pick(forms: list[dict], form_id: str, title: str) -> list[dict]:
    if form_id:
        return [f for f in forms if f.get("id") == form_id]
    wanted = title.strip().lower()
    exact = [f for f in forms if (f.get("title") or "").strip().lower() == wanted]
    if exact:
        return exact
    return [f for f in forms if (f.get("title") or "").strip().lower().startswith(wanted)]


def record(log_path: Path | None, entry: dict) -> None:
    line = json.dumps(entry, default=str)
    print(line)
    if log_path is not None:
        log_path.parent.mkdir(parents=True, exist_ok=True)
        with log_path.open("a", encoding="utf-8") as handle:
            handle.write(line + "\n")


def run(args: argparse.Namespace) -> int:
    api = Api(args.api)
    user = api.sign_in(args.email_env, args.password_env, args.totp_env)
    role = str(user.get("role") or "")
    print(f"signed in to {api.base} as role {role or '<unknown>'}")
    forms = api.forms()
    print(f"{len(forms)} forms visible")

    references = pick(forms, args.reference_id, args.reference_title)
    for ref in references:
        print("reference: " + json.dumps(summary(ref)))
    if not references:
        print(f"reference: no form matches {args.reference_id or args.reference_title!r}")

    targets = pick(forms, args.form_id, args.title)
    if not targets:
        print(f"target: no form matches {args.form_id or args.title!r}; nothing to share")
        for form in forms:
            title = (form.get("title") or "").lower()
            if "4th" in title or "fourth" in title:
                print("candidate: " + json.dumps(summary(form)))
        return 2
    for target in targets:
        print("target: " + json.dumps(summary(target)))
    if len(targets) > 1:
        print("more than one form matches; name one with --form-id")
        return 3
    target = targets[0]
    if target.get("deletedAt"):
        print("the target is deleted; restore it first, nothing was changed")
        return 3
    if target.get("isActive") is False:
        print("the target is retired (isActive false); nothing was changed")
        return 3
    if target.get("isShared"):
        print("already shared with all; nothing to do")
        return 0

    action = {"method": "PATCH", "path": f"/api/questionnaires/{target['id']}", "body": {"isShared": True}}
    if not args.apply:
        print("DRY RUN, would send: " + json.dumps(action))
        return 0
    if role not in CONTENT_ADMIN_ROLES:
        print(f"role {role!r} cannot share a form; sign in as one of {sorted(CONTENT_ADMIN_ROLES)}")
        return 4
    status, payload = api.call(action["method"], action["path"], action["body"])
    if status != 200:
        detail = payload.get("detail") if isinstance(payload, dict) else None
        print(f"PATCH answered {status}: {detail}")
        return 5
    after = [f for f in api.forms() if f.get("id") == target["id"]]
    shared = bool(after and after[0].get("isShared"))
    record(
        args.log,
        {
            "at": stamp(),
            "api": api.base,
            "action": "share",
            "formId": target["id"],
            "title": target.get("title"),
            "before": {"isShared": target.get("isShared")},
            "after": summary(after[0]) if after else None,
            "verified": shared,
        },
    )
    return 0 if shared else 6


def main() -> int:
    parser = argparse.ArgumentParser(description="Share one questionnaire with every user, through the app's own API.")
    parser.add_argument("--api", default=os.environ.get("SHARE_API_BASE", DEFAULT_API))
    parser.add_argument("--form-id", default=DEFAULT_FORM_ID)
    parser.add_argument("--title", default=DEFAULT_TITLE)
    parser.add_argument("--reference-id", default=DEFAULT_REFERENCE_ID)
    parser.add_argument("--reference-title", default=DEFAULT_REFERENCE_TITLE)
    parser.add_argument("--email-env", default="SHARE_ADMIN_EMAIL")
    parser.add_argument("--password-env", default="SHARE_ADMIN_PASSWORD")
    parser.add_argument("--totp-env", default="SHARE_ADMIN_TOTP")
    parser.add_argument("--log", type=Path, default=None)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--dry-run", dest="apply", action="store_false")
    mode.add_argument("--apply", dest="apply", action="store_true")
    parser.set_defaults(apply=False)
    args = parser.parse_args()
    try:
        return run(args)
    except Refused as exc:
        print(f"refused: {exc}")
        return 1


if __name__ == "__main__":
    sys.exit(main())
