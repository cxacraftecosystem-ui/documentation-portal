from __future__ import annotations

import json
import os
import urllib.error
import urllib.request
from collections.abc import Callable
from pathlib import Path
from typing import Any

HERE = Path(__file__).resolve().parent
SAFE_METHODS = frozenset({"GET", "HEAD", "OPTIONS"})


def read_only_posts(path: Path = HERE / "read_only_posts.txt") -> frozenset[str]:
    lines = path.read_text(encoding="utf-8").splitlines()
    return frozenset(line.split()[0] for line in lines if line.strip())


READ_ONLY_POSTS = read_only_posts()


def allowed(method: str, path: str) -> bool:
    method = method.upper()
    bare = path.split("?", 1)[0]
    return method in SAFE_METHODS or (method == "POST" and bare in READ_ONLY_POSTS)


class WriteRefused(RuntimeError):
    pass


class GateFailed(AssertionError):
    pass


def out_dir() -> Path:
    return Path(os.environ.get("GATE_OUT") or Path.cwd() / "release-gate-out")


class Gate:
    def __init__(
        self,
        name: str,
        *,
        kind: str = "python",
        out: Path | None = None,
        api: str | None = None,
        email: str | None = None,
        password: str | None = None,
        opener: Callable[..., Any] = urllib.request.urlopen,
    ) -> None:
        self.name = name
        self.kind = kind
        self.api = (api if api is not None else os.environ.get("GATE_API", "")).rstrip(
            "/"
        )
        self.email = email if email is not None else os.environ.get("GATE_EMAIL", "")
        self.password = (
            password if password is not None else os.environ.get("GATE_PASSWORD", "")
        )
        self.dir = (out or out_dir()) / name
        self.dir.mkdir(parents=True, exist_ok=True)
        self.token = ""
        self.counts: dict[str, Any] = {}
        self.rows: list[dict[str, Any]] = []
        self.blocked: list[str] = []
        self.error = ""
        self._open = opener

    def request(self, method: str, path: str, body: Any = None) -> tuple[int, bytes]:
        if not allowed(method, path):
            self.blocked.append(f"{method.upper()} {path}")
            raise WriteRefused(
                f"{method.upper()} {path} could change production, and the release gate is read-only. "
                "List it in scripts/release_gate/read_only_posts.txt only if it changes nothing."
            )
        data = None if body is None else json.dumps(body).encode("utf-8")
        headers = {"accept": "application/json", "cache-control": "no-cache"}
        if data is not None:
            headers["content-type"] = "application/json"
        if self.token:
            headers["authorization"] = f"Bearer {self.token}"
        request = urllib.request.Request(
            self.api + path, data=data, method=method.upper(), headers=headers
        )
        try:
            with self._open(request, timeout=180) as response:
                return response.status, response.read()
        except urllib.error.HTTPError as err:
            return err.code, err.read()

    def get(self, path: str) -> bytes:
        status, body = self.request("GET", path)
        if status != 200:
            raise GateFailed(f"GET {path} answered {status}")
        return body

    def get_json(self, path: str) -> Any:
        return json.loads(self.get(path))

    def sign_in(self) -> dict[str, Any]:
        if not self.email or not self.password:
            raise GateFailed("GATE_EMAIL and GATE_PASSWORD are not set")
        status, body = self.request(
            "POST", "/api/auth/login", {"email": self.email, "password": self.password}
        )
        if status != 200:
            raise GateFailed(f"sign-in answered {status}")
        payload = json.loads(body)
        self.token = payload.get("accessToken") or payload.get("access_token") or ""
        if not self.token:
            raise GateFailed("sign-in answered 200 with no token")
        return payload

    def count(self, **values: Any) -> None:
        self.counts.update(values)

    def expect(
        self, item: str, expected: Any, actual: Any, ok: bool | None = None
    ) -> bool:
        passed = (expected == actual) if ok is None else bool(ok)
        self.rows.append(
            {"item": item, "expected": expected, "actual": actual, "ok": passed}
        )
        return passed

    def save(self, name: str, content: bytes) -> Path:
        path = self.dir / name
        path.write_bytes(content)
        return path

    @property
    def ok(self) -> bool:
        return (
            not self.error and not self.blocked and all(row["ok"] for row in self.rows)
        )

    def finish(self) -> bool:
        result = {
            "check": self.name,
            "kind": self.kind,
            "ok": self.ok,
            "error": self.error,
            "blocked": self.blocked,
            "counts": self.counts,
            "rows": self.rows,
        }
        (self.dir / "result.json").write_text(
            json.dumps(result, indent=2, default=str) + "\n", encoding="utf-8"
        )
        return self.ok
