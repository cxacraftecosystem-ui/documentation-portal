from __future__ import annotations

import csv
import re
from collections.abc import Iterable, Mapping
from typing import Any

FORMULA_TRIGGERS = frozenset("=+-@\t\r＝＋－＠")

_PLAIN_NUMBER = re.compile(r"[+-]?(?:\d[\d,]*)?\.?\d+(?:[eE][+-]?\d+)?")


def neutralise(value: Any) -> Any:
    if not isinstance(value, str) or not value:
        return value
    if value[0] not in FORMULA_TRIGGERS:
        return value
    if value in {"-", "+"} or _PLAIN_NUMBER.fullmatch(value):
        return value
    return "'" + value


class SafeWriter:
    def __init__(self, target: Any, **options: Any) -> None:
        self._writer = csv.writer(target, **options)

    def writerow(self, row: Iterable[Any]) -> Any:
        return self._writer.writerow([neutralise(value) for value in row])

    def writerows(self, rows: Iterable[Iterable[Any]]) -> None:
        for row in rows:
            self.writerow(row)


class SafeDictWriter:
    def __init__(self, target: Any, fieldnames: list[str], **options: Any) -> None:
        self._writer = csv.DictWriter(target, fieldnames=fieldnames, **options)

    def writeheader(self) -> Any:
        return self._writer.writeheader()

    def writerow(self, row: Mapping[str, Any]) -> Any:
        return self._writer.writerow({key: neutralise(value) for key, value in row.items()})


def writer(target: Any, **options: Any) -> SafeWriter:
    return SafeWriter(target, **options)


def dict_writer(target: Any, fieldnames: list[str], **options: Any) -> SafeDictWriter:
    return SafeDictWriter(target, fieldnames, **options)
