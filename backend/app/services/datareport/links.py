from __future__ import annotations

from collections.abc import Awaitable, Callable
from typing import Literal
from urllib.parse import quote, urlencode

from app.services.datareport.portal import PortalConfig

BundleStore = Callable[[str, list[str]], Awaitable[str]]


def _q(value: str) -> str:
    return quote(str(value), safe="")


class PortalLinks:
    def __init__(self, base: str, portal: PortalConfig, store: BundleStore | None = None) -> None:
        self.base = base.rstrip("/")
        self.portal = portal
        self._store = store
        self._bundles: dict[tuple[str, ...], str] = {}

    def _web(self, path: str, query: dict[str, str] | None = None) -> str:
        tail = f"?{urlencode(query)}" if query else ""
        return f"{self.base}{path}{tail}"

    def download(self, media_id: str) -> str:
        return self._web(f"/data/go/media/{_q(media_id)}/download")

    def open_media(self, media_id: str) -> str:
        return self._web("/data", {"preview": str(media_id)})

    def open_record(self, kind: str, record_id: str) -> str:
        return self._web(f"/data/go/open/{_q(kind)}/{_q(record_id)}")

    def record_path(self, kind: str, record_id: str) -> str | None:
        pattern = self.portal.open_paths.get(kind)
        if pattern is None:
            return None
        return pattern.format(id=_q(record_id))

    def play_from(self, media_id: str, start_ms: int) -> str:
        seconds = max(0, int(start_ms or 0) // 1000)
        return self._web("/data", {"preview": str(media_id), "t": str(seconds)})

    def transcript_file(self, media_id: str, fmt: Literal["docx", "srt"]) -> str:
        return self._web(f"/data/go/transcript/{_q(media_id)}/{_q(fmt)}")

    def section(self, interview_id: str, section_code: str) -> str:
        return self._web(
            f"/questionnaire/sessions/{_q(interview_id)}/view", {"section": str(section_code)}
        )

    def data_folder(self, path: str) -> str:
        return self._web("/data", {"path": str(path or "")})

    async def bundle(self, label: str, media_ids: list[str]) -> str:
        ids = tuple(dict.fromkeys(str(i) for i in media_ids if i))
        if not ids:
            return self.data_folder("")
        found = self._bundles.get(ids)
        if found is not None:
            return found
        if self._store is None:
            url = self.data_folder("")
        else:
            bundle_id = await self._store(label, list(ids))
            url = self._web("/data", {"bundle": bundle_id})
        self._bundles[ids] = url
        return url
