"""What the API and the queue worker load at start-up, pinned (2026-10-19).

Measured that day on the t3.small: the API held ~840 MB and the queue worker ~780 MB, nearly all of it
in swap, at idle, with an almost empty database. Nearly all of that was one import: the generated
``prisma/types.py`` (28 MB, 84,774 TypedDicts), which ``import prisma`` used to execute in full.
app/prisma_lazy_types.py now defers it until something reads it, and nothing at run time does. The
rest was libraries the processes hold without using: the worker loaded FastAPI, the tasks router,
boto3 and the request dependencies; the API loaded reportlab, segno and google-auth at start-up for
features that are switched off or used on one rare request.

Measured the same way before and after, in a python:3.14 container on the lock (RSS once idle, as
the units start them): API 841 MB -> 167 MB, worker 796 MB -> 111 MB, and each ready in seconds
instead of twelve minutes (backend/DEPLOY_AWS.md, section 14).

These tests fail when any of that creeps back: a module-level import of a heavy library in a module
the worker or the API start-up imports, or anything that reads ``prisma.types`` on the way.
They run each process's import in a fresh interpreter, because this one has imported half the app.
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
import textwrap
import uuid
from pathlib import Path

import pytest

BACKEND = Path(__file__).resolve().parents[1]

#: Never in the queue worker's import graph. The web framework and the request layer, and every
#: library the worker either never uses or reaches only inside the function that needs it.
WORKER_FORBIDDEN = (
    "fastapi",
    "uvicorn",
    "starlette.applications",
    "starlette.routing",
    "app.main",
    "app.api",
    "app.core.deps",
    "boto3",
    "botocore",
    "reportlab",
    "PIL",
    "openpyxl",
    "segno",
    "anthropic",
    "pydub",
    "google.auth",
    "google.oauth2",
    "pypdf",
    "redis",
    "numpy",
    "pandas",
)

#: Not loaded by the API's start-up: features switched off by default (Claude, the Redis cache), and
#: features that load their library on the request that uses it (the artisan card PDF, the two-step
#: QR, Google sign-in, S3).
API_STARTUP_FORBIDDEN = (
    "reportlab",
    "segno",
    "anthropic",
    "pydub",
    "google.auth",
    "google.oauth2",
    "boto3",
    "botocore",
    "pypdf",
    "redis",
    "numpy",
    "pandas",
)

#: Feature switches that must be OFF for the "switched off costs nothing" check: removed from the
#: child's environment so it runs on the defaults in app/core/config.py.
_SWITCHES = (
    "AI_ANSWER_DRAFTS_ENABLED",
    "UNIFIED_SEARCH_ENABLED",
    "SEARCH_EMBEDDINGS_ENABLED",
    "CROSS_PORTAL_SEARCH_ENABLED",
    "CROSS_PORTAL_IDENTITY_ENABLED",
    "SCALE_CACHE_ENABLED",
    "SCALE_CACHE_BACKEND",
    "GOOGLE_CLIENT_ID",
    "GOOGLE_ANDROID_CLIENT_ID",
    "FIELDREPO_EAGER_PRISMA_TYPES",
)

_REPORT = textwrap.dedent(
    """
    import json, sys
    __import__(sys.argv[1])
    from app import prisma_lazy_types
    print(json.dumps({"modules": sorted(sys.modules), "types_loaded": prisma_lazy_types.is_loaded(),
                      "types_class": type(sys.modules.get("prisma.types")).__name__}))
    """
)


def _child_env() -> dict[str, str]:
    env = {k: v for k, v in os.environ.items() if k not in _SWITCHES}
    env.setdefault("DATABASE_URL", "postgresql://ci:ci@ci.invalid:5432/no_such_database")
    env.setdefault("JWT_SECRET", "ci-placeholder-not-a-secret-0123456789abcdef")
    env.setdefault("AWS_ACCESS_KEY_ID", "ci-placeholder")
    env.setdefault("AWS_SECRET_ACCESS_KEY", "ci-placeholder")
    env.setdefault("AWS_S3_BUCKET", "ci-placeholder")
    env.setdefault("MASTER_ADMIN_EMAIL", "ci@example.invalid")
    env["PYTHONDONTWRITEBYTECODE"] = "1"
    return env


def _generated() -> bool:
    import importlib.util

    spec = importlib.util.find_spec("prisma")
    return bool(spec and spec.submodule_search_locations) and any(
        (Path(d) / "types.py").is_file() for d in spec.submodule_search_locations
    )


needs_client = pytest.mark.skipif(not _generated(), reason="needs `prisma generate` (CI runs it)")


def _import_report(module: str) -> dict:
    out = subprocess.run(
        [sys.executable, "-c", _REPORT, module],
        cwd=BACKEND,
        env=_child_env(),
        capture_output=True,
        text=True,
        timeout=300,
        check=False,
    )
    assert out.returncode == 0, out.stderr[-4000:]
    return json.loads(out.stdout.strip().splitlines()[-1])


def _present(modules: list[str], forbidden: tuple[str, ...]) -> list[str]:
    loaded = set(modules)
    return sorted(f for f in forbidden if f in loaded or any(m.startswith(f + ".") for m in loaded))


@needs_client
def test_the_queue_worker_imports_no_web_layer_and_no_unused_library():
    report = _import_report("app.worker")
    assert _present(report["modules"], WORKER_FORBIDDEN) == []
    assert report["types_class"] == "LazyPrismaTypes"
    assert report["types_loaded"] is False


@needs_client
def test_the_api_starts_without_loading_switched_off_or_on_demand_features():
    report = _import_report("app.main")
    assert _present(report["modules"], API_STARTUP_FORBIDDEN) == []
    assert report["types_loaded"] is False


@needs_client
def test_queries_build_and_run_without_loading_the_generated_types():
    """The client's own query paths (find, count, include, order, update, delete) through a stand-in
    engine: none of them reads ``prisma.types``."""
    script = textwrap.dedent(
        """
        import asyncio, json
        from app.core.db import db
        from app import prisma_lazy_types

        sent = []

        class Engine:
            async def query(self, content, *, tx_id=None):
                sent.append(content)
                if '"count"' in content or "aggregate" in content:
                    return {"data": {"result": {"_count": {"_all": 0}}}}
                if "updateMany" in content or "deleteMany" in content:
                    return {"data": {"result": {"count": 0}}}
                if "findUnique" in content or "findFirst" in content:
                    return {"data": {"result": None}}
                return {"data": {"result": []}}

        db._internal_engine = Engine()

        async def main():
            await db.user.find_many(where={"email": {"contains": "a"}}, order={"createdAt": "desc"}, take=5)
            await db.artisan.find_many(where={"OR": [{"name": "x"}, {"craft": {"is": {"name": "y"}}}]}, include={"craft": True})
            await db.user.find_unique(where={"id": "u1"})
            await db.workshop.find_first(where={"id": "w"}, order=[{"createdAt": "asc"}])
            await db.user.count(where={"role": "ADMIN"})
            await db.user.update_many(where={"id": "u1"}, data={"name": "n"})
            await db.artisan.delete_many(where={"id": {"in": ["a"]}})

        asyncio.run(main())
        print(json.dumps({"queries": len(sent), "types_loaded": prisma_lazy_types.is_loaded()}))
        """
    )
    out = subprocess.run(
        [sys.executable, "-c", script], cwd=BACKEND, env=_child_env(), capture_output=True, text=True, timeout=300, check=False
    )
    assert out.returncode == 0, out.stderr[-4000:]
    report = json.loads(out.stdout.strip().splitlines()[-1])
    assert report == {"queries": 7, "types_loaded": False}


# --- The lazy module itself, on a small stand-in for the generated package -----------------------


@pytest.fixture
def fake_client(tmp_path, monkeypatch):
    """A package shaped like the generated client: ``__init__`` imports names from ``.types`` and
    binds ``types``; ``types.py`` aliases ``_types`` names, defines TypedDicts, counts its own runs and
    ends by importing itself back, as the generated file does."""
    name = f"fakeprisma_{uuid.uuid4().hex[:8]}"
    pkg = tmp_path / name
    pkg.mkdir()
    (pkg / "_types.py").write_text(
        "class SortOrder: pass\nclass SortMode: pass\nclass MetricsFormat: pass\n"
        "class HttpConfig: pass\nclass DatasourceOverride: pass\n",
        encoding="utf-8",
    )
    (pkg / "types.py").write_text(
        textwrap.dedent(
            """
            from typing_extensions import TypedDict
            from . import _types
            import builtins
            builtins.FAKE_TYPES_RUNS = getattr(builtins, "FAKE_TYPES_RUNS", 0) + 1
            SortOrder = _types.SortOrder
            SortMode = _types.SortMode
            MetricsFormat = _types.MetricsFormat
            HttpConfig = _types.HttpConfig
            DatasourceOverride = _types.DatasourceOverride

            class UserWhereInput(TypedDict, total=False):
                id: str

            from . import types
            """
        ),
        encoding="utf-8",
    )
    (pkg / "__init__.py").write_text(
        "from ._types import SortOrder as _so\n"
        "from .types import DatasourceOverride, HttpConfig, MetricsFormat\n"
        "from . import types as types\n",
        encoding="utf-8",
    )
    monkeypatch.syspath_prepend(str(tmp_path))
    import builtins

    monkeypatch.setattr(builtins, "FAKE_TYPES_RUNS", 0, raising=False)
    yield name
    for key in [k for k in sys.modules if k == name or k.startswith(name + ".")]:
        del sys.modules[key]


def test_the_package_imports_without_running_the_types_body(fake_client):
    import builtins
    import importlib

    from app import prisma_lazy_types as lazy

    assert lazy.install(fake_client) is True
    package = importlib.import_module(fake_client)
    private = importlib.import_module(f"{fake_client}._types")
    assert builtins.FAKE_TYPES_RUNS == 0
    assert isinstance(package.types, lazy.LazyPrismaTypes)
    assert not lazy.is_loaded(fake_client)
    # The start-up names are the very objects the generated file would alias.
    assert package.DatasourceOverride is private.DatasourceOverride
    assert package.types.SortOrder is private.SortOrder
    assert builtins.FAKE_TYPES_RUNS == 0


def test_any_other_name_runs_the_real_body_once_into_the_same_module(fake_client):
    import builtins
    import importlib

    from app import prisma_lazy_types as lazy

    lazy.install(fake_client)
    package = importlib.import_module(fake_client)
    types_module = package.types
    where = types_module.UserWhereInput
    assert where.__annotations__ == {"id": str}
    assert builtins.FAKE_TYPES_RUNS == 1
    assert lazy.is_loaded(fake_client)
    assert sys.modules[f"{fake_client}.types"] is types_module
    assert types_module.types is types_module  # the generated file's own `from . import types`
    assert types_module.SortOrder is importlib.import_module(f"{fake_client}._types").SortOrder
    assert "UserWhereInput" in dir(types_module)
    with pytest.raises(AttributeError):
        types_module.NoSuchType  # noqa: B018 - the attribute read is the test
    assert builtins.FAKE_TYPES_RUNS == 1


def test_it_leaves_an_imported_or_ungenerated_package_alone(fake_client, monkeypatch):
    import importlib

    from app import prisma_lazy_types as lazy

    importlib.import_module(fake_client)  # imported the eager way first
    assert lazy.install(fake_client) is False
    assert lazy.is_loaded(fake_client)
    assert lazy.install(f"no_such_package_{uuid.uuid4().hex[:8]}") is False


def test_the_escape_hatch_restores_the_eager_import(fake_client, monkeypatch):
    import builtins
    import importlib

    from app import prisma_lazy_types as lazy

    monkeypatch.setenv("FIELDREPO_EAGER_PRISMA_TYPES", "1")
    assert lazy.install(fake_client) is False
    importlib.import_module(fake_client)
    assert builtins.FAKE_TYPES_RUNS == 1
