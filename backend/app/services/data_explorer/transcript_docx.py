from __future__ import annotations

import io
import zipfile

DOCUMENT = "word/document.xml"
PAGE_BREAK = '<w:p><w:r><w:br w:type="page"/></w:r></w:p>'
_BODY_OPEN = "<w:body>"
_SECTION = "<w:sectPr"


def split(single: bytes) -> tuple[dict[str, bytes], str, str, str]:
    with zipfile.ZipFile(io.BytesIO(single)) as archive:
        parts = {name: archive.read(name) for name in archive.namelist() if name != DOCUMENT}
        xml = archive.read(DOCUMENT).decode("utf-8")
    start = xml.index(_BODY_OPEN) + len(_BODY_OPEN)
    end = xml.rindex(_SECTION)
    return parts, xml[:start], xml[start:end], xml[end:]


class Combined:
    def __init__(self) -> None:
        self._buffer = io.BytesIO()
        self._archive = zipfile.ZipFile(self._buffer, "w", zipfile.ZIP_DEFLATED)
        self._stream: io.BufferedIOBase | None = None
        self._tail = ""
        self.count = 0

    def add(self, single: bytes) -> None:
        parts, head, body, tail = split(single)
        if self._stream is None:
            for name, payload in parts.items():
                self._archive.writestr(name, payload)
            self._stream = self._archive.open(DOCUMENT, "w")
            self._stream.write(head.encode("utf-8"))
        else:
            self._stream.write(PAGE_BREAK.encode("utf-8"))
        self._stream.write(body.encode("utf-8"))
        self._tail = tail
        self.count += 1

    def finish(self) -> bytes | None:
        if self._stream is None:
            self._archive.close()
            return None
        self._stream.write(self._tail.encode("utf-8"))
        self._stream.close()
        self._archive.close()
        return self._buffer.getvalue()
