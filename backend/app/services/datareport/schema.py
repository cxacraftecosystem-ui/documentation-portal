from __future__ import annotations

import functools
import json
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Any

HERE = Path(__file__).resolve().parent

SCALARS = {
    "String": "text",
    "Int": "int",
    "BigInt": "int",
    "Float": "number",
    "Decimal": "number",
    "Boolean": "bool",
    "DateTime": "datetime",
    "Json": "json",
    "Bytes": "bytes_raw",
}

_MODEL = re.compile(r"^model (\w+) \{(.*?)^\}", re.MULTILINE | re.DOTALL)
_ENUM = re.compile(r"^enum (\w+) \{(.*?)^\}", re.MULTILINE | re.DOTALL)
_RELATION_FIELDS = re.compile(r"@relation\((?:[^()]*?)fields:\s*\[([^\]]*)\]")


@dataclass(frozen=True)
class Field:
    model: str
    name: str
    type: str
    list: bool
    optional: bool
    enum: bool
    doc: str
    is_id: bool
    fk: str | None

    @property
    def source(self) -> str:
        return f"{self.model}.{self.name}"

    @property
    def kind(self) -> str:
        if self.enum:
            return "status" if self.name.lower().endswith(("status", "state")) else "text"
        if self.list:
            return "text"
        return SCALARS.get(self.type, "text")


@dataclass(frozen=True)
class Model:
    name: str
    doc: str
    fields: tuple[Field, ...]

    def field(self, name: str) -> Field | None:
        for f in self.fields:
            if f.name == name:
                return f
        return None


def _docs_before(lines: list[str], index: int) -> str:
    out: list[str] = []
    j = index - 1
    while j >= 0 and lines[j].strip().startswith("///"):
        out.append(lines[j].strip()[3:].strip())
        j -= 1
    text = " ".join(reversed([o for o in out if o]))
    sentence = re.split(r"(?<=[.!?])\s", text, maxsplit=1)[0]
    return sentence if len(sentence) <= 240 else sentence[:237].rstrip() + "..."


def parse_prisma(text: str) -> dict[str, Any]:
    text = text.replace("\r\n", "\n")
    enums = {
        name: [
            line.strip().split()[0]
            for line in body.split("\n")
            if line.strip() and not line.strip().startswith(("//", "@@"))
        ]
        for name, body in _ENUM.findall(text)
    }
    found = _MODEL.findall(text)
    names = {name for name, _ in found}
    models: dict[str, Any] = {}
    for name, body in found:
        head = text[: text.index(f"model {name} {{")].rstrip("\n").split("\n")
        model_doc = _docs_before([*head, ""], len(head))
        lines = body.split("\n")
        relation_target: dict[str, str] = {}
        for line in lines:
            parts = line.strip().split()
            if len(parts) >= 2 and parts[1].rstrip("?[]") in names:
                m = _RELATION_FIELDS.search(line)
                if m:
                    for raw_fk in m.group(1).split(","):
                        if raw_fk.strip():
                            relation_target[raw_fk.strip()] = parts[1].rstrip("?[]")
        primary: list[str] = []
        for line in lines:
            s = line.strip()
            if s.startswith("@@id("):
                primary = [p.strip() for p in s[s.index("[") + 1 : s.index("]")].split(",")]
        fields: list[dict[str, Any]] = []
        for index, line in enumerate(lines):
            s = line.strip()
            if not s or s.startswith(("//", "@@")):
                continue
            parts = s.split()
            if len(parts) < 2:
                continue
            raw = parts[1]
            base = raw.rstrip("?").rstrip("[]").rstrip("?")
            if base in names:
                continue
            fields.append(
                {
                    "name": parts[0],
                    "type": base,
                    "list": raw.endswith("[]"),
                    "optional": raw.endswith("?"),
                    "enum": base in enums,
                    "doc": _docs_before(lines, index),
                    "id": "@id" in s.split("//")[0] or parts[0] in primary,
                    "fk": relation_target.get(parts[0]),
                }
            )
        models[name] = {"doc": model_doc, "fields": fields}
    return {"models": models, "enums": enums}


def schema_file(portal: str) -> Path:
    return HERE / f"schema_{portal}.json"


def dump(parsed: dict[str, Any]) -> str:
    def line(value: Any) -> str:
        return json.dumps(value, sort_keys=True, ensure_ascii=False, separators=(",", ":"))

    models = ",\n".join(f" {line(k)}:{line(v)}" for k, v in sorted(parsed["models"].items()))
    enums = ",\n".join(f" {line(k)}:{line(v)}" for k, v in sorted(parsed["enums"].items()))
    return '{"enums":{\n' + enums + '\n},"models":{\n' + models + "\n}}\n"


@functools.cache
def load(portal: str) -> dict[str, Model]:
    path = schema_file(portal)
    if not path.is_file():
        return {}
    data = json.loads(path.read_text(encoding="utf-8"))
    out: dict[str, Model] = {}
    for name, spec in data["models"].items():
        out[name] = Model(
            name=name,
            doc=spec.get("doc") or "",
            fields=tuple(
                Field(
                    model=name,
                    name=f["name"],
                    type=f["type"],
                    list=bool(f["list"]),
                    optional=bool(f["optional"]),
                    enum=bool(f["enum"]),
                    doc=f.get("doc") or "",
                    is_id=bool(f.get("id")),
                    fk=f.get("fk"),
                )
                for f in spec["fields"]
            ),
        )
    return out


@functools.cache
def enums(portal: str) -> dict[str, list[str]]:
    path = schema_file(portal)
    if not path.is_file():
        return {}
    return dict(json.loads(path.read_text(encoding="utf-8")).get("enums") or {})


_SPLIT = re.compile(r"(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])")

_WORDS = {
    "id": "ID",
    "url": "URL",
    "sha256": "SHA-256",
    "gi": "GI",
    "dob": "Date of birth",
    "lgd": "LGD",
    "ai": "AI",
    "usd": "USD",
    "stt": "Speech-to-text",
    "dw": "Design workshop",
    "ist": "IST",
    "dos": "Do's",
    "donts": "Don'ts",
}


def humanize(name: str) -> str:
    words = _SPLIT.sub(" ", name).replace("_", " ").split()
    out: list[str] = []
    for index, word in enumerate(words):
        low = word.lower()
        if low in _WORDS:
            out.append(_WORDS[low])
        elif index == 0:
            out.append(word[:1].upper() + word[1:])
        else:
            out.append(word if word.isupper() and len(word) > 1 else low)
    text = " ".join(out)
    if text.endswith(" at"):
        text = text[:-3] + " on"
    if text.endswith(" by ID"):
        text = text[:-6] + " by"
    return text


def enum_label(value: Any) -> str:
    raw = getattr(value, "value", value)
    if raw is None:
        return ""
    text = str(raw)
    if text.isupper() or "_" in text:
        return text.replace("_", " ").capitalize()
    return text
