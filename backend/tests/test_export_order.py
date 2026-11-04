import asyncio
from types import SimpleNamespace
from typing import Any

from app.api.routes import export


def test_every_export_read_is_ordered_by_creation_then_id(monkeypatch):
    seen: dict[str, Any] = {}

    class _Rows:
        def __init__(self, name: str) -> None:
            self.name = name

        async def find_many(self, **kwargs: Any) -> list[Any]:
            seen[self.name] = kwargs.get("order")
            return []

    class _Tables:
        def __getattr__(self, name: str) -> Any:
            return _Rows(name)

    async def no_filter(*_args: Any, **_kw: Any) -> dict[str, Any]:
        return {}

    monkeypatch.setattr(export, "db", _Tables())
    monkeypatch.setattr(export, "can_download_dataset", lambda _user: True)
    monkeypatch.setattr(export, "owned_or_granted_where", no_filter)
    admin = SimpleNamespace(id="admin", email="admin@example.test", name="Admin", role="ADMIN")
    asyncio.run(export.dataset_manifest(ownerId=None, stream=False, current_user=admin))
    wanted = [{"createdAt": "asc"}, {"id": "asc"}]
    for table in ("workshop", "artisan", "productdocumentation", "tooldocumentation", "questionnaireinterview", "process"):
        assert seen.get(table) == wanted, table


def test_the_six_record_reads_run_together(monkeypatch):
    calls: list[str] = []
    gate = asyncio.Event()

    class _Rows:
        def __init__(self, name: str) -> None:
            self.name = name

        async def find_many(self, **_kwargs: Any) -> list[Any]:
            calls.append(self.name)
            if len(calls) >= 6:
                gate.set()
            await asyncio.wait_for(gate.wait(), 2)
            return []

    class _Tables:
        def __getattr__(self, name: str) -> Any:
            return _Rows(name)

    async def no_filter(*_args: Any, **_kw: Any) -> dict[str, Any]:
        return {}

    monkeypatch.setattr(export, "db", _Tables())
    monkeypatch.setattr(export, "can_download_dataset", lambda _user: True)
    monkeypatch.setattr(export, "owned_or_granted_where", no_filter)
    monkeypatch.setattr("app.services.concurrency.pool_width", lambda: 8)
    admin = SimpleNamespace(id="admin", email="admin@example.test", name="Admin", role="ADMIN")
    asyncio.run(export.dataset_manifest(ownerId=None, stream=False, current_user=admin))
    assert len(calls) >= 6
