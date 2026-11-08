from __future__ import annotations

import io
import re
import zipfile
from datetime import UTC, datetime, timedelta
from types import SimpleNamespace
from typing import Any

from lxml import etree

from app.services.datareport import contract, i18n, links, portal


class FakeDelegate:
    def __init__(self, rows: list[Any] | None = None) -> None:
        self.rows = rows or []

    async def find_many(self, where: Any = None, take: int | None = None, **_: Any) -> list[Any]:
        if not where:
            return list(self.rows)
        (field, cond), *_rest = where.items()
        if isinstance(cond, dict) and "in" in cond:
            wanted = set(cond["in"])
            return [r for r in self.rows if str(getattr(r, field, "")) in wanted]
        if isinstance(cond, dict) and "is" in cond:
            return []
        if cond is None:
            return [r for r in self.rows if getattr(r, field, None) is None]
        return []

    async def find_first(self, where: Any = None, **_: Any) -> Any:
        found = await self.find_many(where)
        return found[0] if found else None

    async def find_unique(self, where: Any = None, **_: Any) -> Any:
        return await self.find_first(where)

    async def count(self, where: Any = None, **_: Any) -> int:
        return len(await self.find_many(where))

    async def create(self, data: dict[str, Any]) -> Any:
        row = SimpleNamespace(id=f"b{len(self.rows) + 1}", **data)
        self.rows.append(row)
        return row


class FakeDB:
    def __init__(self, tables: dict[str, list[Any]] | None = None, raw: Any = None) -> None:
        self._tables = {k.lower(): FakeDelegate(v) for k, v in (tables or {}).items()}
        self._raw = raw

    def __getattr__(self, name: str) -> FakeDelegate:
        if name.startswith("_"):
            raise AttributeError(name)
        return self._tables.setdefault(name, FakeDelegate())

    async def query_raw(self, sql: str, *args: Any) -> list[dict[str, Any]]:
        if self._raw is not None:
            return self._raw(sql, *args)
        return []


NAMES = ("Kamla Devi", "Sita Verma", "Mohan Lal")


def material(n_artisans: int = 3, n_media: int = 4) -> dict[str, Any]:
    now = datetime(2026, 11, 5, 10, 30, tzinfo=UTC)
    workshop = SimpleNamespace(id="w1", title="Bagru block print", status="APPROVED", createdAt=now, createdById="u1", locationId=None, startDate=now, endDate=now + timedelta(days=3), place="Bagru")
    craft = SimpleNamespace(id="c1", name="Block printing", createdAt=now, createdById="u1", status="APPROVED", subCategoryId=None)
    artisans = [
        SimpleNamespace(id=f"a{i}", name=NAMES[i % len(NAMES)] + (f" {i}" if i >= len(NAMES) else ""), craftId="c1", workshopId="w1", status="PENDING" if i % 2 else "APPROVED", phone="9999999999", aadhaarLast4="1234", aadhaarFingerprint="secret", createdAt=now, createdById="u1", locationId=None, dateOfBirth=datetime(1970, 1, 1, tzinfo=UTC))
        for i in range(n_artisans)
    ]
    media = [
        SimpleNamespace(id=f"m{i}", originalFilename=f"talk-{i}.m4a" if i % 2 == 0 else f"photo-{i}.jpg", mediaType="AUDIO" if i % 2 == 0 else "IMAGE", sizeBytes=1024 * 1024 * (i + 1), artisanId="a0", workshopId="w1", linkedRecordType=None, linkedRecordId=None, recordedAt=now, createdAt=now, uploadedById="u1", hasTranscript=i % 2 == 0, transcriptWithheld=False, transcriptStatus="COMPLETED" if i % 2 == 0 else None)
        for i in range(n_media)
    ]
    return {
        "workshops": [workshop],
        "crafts": [craft],
        "artisans": artisans,
        "products": [],
        "processes": [],
        "tools": [],
        "interviews": [],
        "media": media,
        "designWorkshops": [],
    }


def turns_raw(sql: str, *args: Any) -> list[dict[str, Any]]:
    ids = list(args[0]) if args else []
    if '"TranscriptVersion"' in sql and "DISTINCT ON" in sql:
        return [{"id": f"v-{m}", "mediaFileId": m, "version": 1, "languages": ["hi"], "durationMs": 65000, "translationStatus": "PENDING", "turnCount": 2} for m in ids if m in ("m0", "m2")]
    if 'FROM "TranscriptTurn"' in sql and "GROUP BY" in sql:
        return [{"id": v, "done": 1, "total": 2} for v in ids]
    if 'FROM "TranscriptTurn"' in sql:
        out = []
        for v in ids:
            mid = v.removeprefix("v-")
            out.append({"id": f"t1-{mid}", "versionId": v, "mediaFileId": mid, "position": 0, "speakerKey": "s0", "startMs": 0, "endMs": 4000, "language": "hi", "machineText": "नील का रंग", "editedText": None, "machineEnglish": "The indigo colour", "editedEnglish": None, "translationStatus": "TRANSLATED", "translationModel": "m", "editedAt": None, "editedById": None, "revision": 1, "createdAt": None, "updatedAt": None})
            out.append({"id": f"t2-{mid}", "versionId": v, "mediaFileId": mid, "position": 1, "speakerKey": "s1", "startMs": 4000, "endMs": 9000, "language": "hi", "machineText": "=SUM(A1) \x07 bell", "editedText": None, "machineEnglish": None, "editedEnglish": None, "translationStatus": "PENDING", "translationModel": None, "editedAt": None, "editedById": None, "revision": 1, "createdAt": None, "updatedAt": None})
        return out
    if '"MediaProcessingJob"' in sql:
        return [{"mediaFileId": "m0", "jobType": "TRANSCRIPT_INSIGHTS", "status": "PROCESSING", "error": None}, {"mediaFileId": "m2", "jobType": "TRANSCRIPT_INSIGHTS", "status": "FAILED", "error": "quota exceeded"}]
    if '"TranscriptSpeaker"' in sql:
        return [{"mediaFileId": m, "speakerKey": "s0", "name": "Kamla", "role": "ARTISAN"} for m in ids]
    return []


def context(options: contract.ReportOptions | None = None, *, n_artisans: int = 3, n_media: int = 4, db: FakeDB | None = None, locale: str = "en") -> contract.ReportContext:
    options = options or contract.ReportOptions(locale=locale)
    stored: dict[str, int] = {}

    async def keep(label: str, ids: list[str]) -> str:
        stored[label] = len(ids)
        return f"bundle{len(stored)}"

    ctx = contract.ReportContext(
        portal="designer",
        user=SimpleNamespace(id="u1", name="Asha Rao", email="asha@example.org"),
        scope=SimpleNamespace(records={}, media={}, design_workshops=True, design_workshop_downloads=True),
        path="by-workshop/w1",
        folder_name="Bagru block print",
        material={"records": material(n_artisans, n_media), "sheets": [], "level": "Bagru block print"},
        options=options,
        links=links.PortalLinks("https://portal.example.org", portal.DESIGNER, store=keep),
        t=i18n.translator(options.locale),
        data_version="1:2",
        generated_at="2026-11-05T16:00:00+05:30",
        portal_name="Design Prototype Workshop",
        generated_by="Asha Rao",
        filters=[("Answers", "Original and English")],
    )
    ctx.shared["db"] = db or FakeDB(raw=turns_raw)
    ctx.shared["bundles"] = stored
    return ctx


_REL_NS = "http://schemas.openxmlformats.org/package/2006/relationships"
_CT_NS = "http://schemas.openxmlformats.org/package/2006/content-types"
_MAIN = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
_CELL = re.compile(r"^([A-Z]+)(\d+)$")


def _col(letters: str) -> int:
    n = 0
    for ch in letters:
        n = n * 26 + ord(ch) - 64
    return n


def validate(payload: bytes) -> dict[str, Any]:
    assert payload[:4] == b"PK\x03\x04"
    found: dict[str, Any] = {"sheets": [], "tables": []}
    with zipfile.ZipFile(io.BytesIO(payload)) as z:
        assert z.testzip() is None
        names = set(z.namelist())
        parser = etree.XMLParser(resolve_entities=False, huge_tree=True)
        parsed = {}
        for name in names:
            if name.endswith((".xml", ".rels", ".vml")):
                parsed[name] = etree.fromstring(z.read(name), parser)
        types = parsed["[Content_Types].xml"]
        overrides = {e.get("PartName").lstrip("/") for e in types if e.tag == f"{{{_CT_NS}}}Override"}
        defaults = {e.get("Extension") for e in types if e.tag == f"{{{_CT_NS}}}Default"}
        for name in names:
            if name.endswith("/"):
                continue
            ext = name.rsplit(".", 1)[-1]
            assert name in overrides or ext in defaults, f"{name} has no content type"
        for override in overrides:
            assert override in names, f"content type for a missing part {override}"
        for name, root in parsed.items():
            if not name.endswith(".rels"):
                continue
            base = name.replace("_rels/", "").removesuffix(".rels")
            folder = base.rsplit("/", 1)[0] if "/" in base else ""
            for rel in root:
                if rel.get("TargetMode") == "External":
                    continue
                target = rel.get("Target")
                path = target.lstrip("/") if target.startswith("/") else "/".join(p for p in [folder, target] if p)
                parts: list[str] = []
                for piece in path.split("/"):
                    if piece == "..":
                        parts.pop()
                    elif piece and piece != ".":
                        parts.append(piece)
                assert "/".join(parts) in names, f"{name} points at a missing {target}"
        table_names: set[str] = set()
        for name, root in parsed.items():
            if name.startswith("xl/tables/"):
                table_name = root.get("name")
                assert table_name not in table_names
                table_names.add(table_name)
                cols = [c.get("name") for c in root.iter(f"{{{_MAIN}}}tableColumn")]
                assert len({c.lower() for c in cols}) == len(cols), f"duplicate table headings in {table_name}"
                found["tables"].append((table_name, root.get("ref"), cols))
        shared: list[str] = []
        if "xl/sharedStrings.xml" in parsed:
            for si in parsed["xl/sharedStrings.xml"]:
                shared.append("".join(si.itertext()))
        for name, root in sorted(parsed.items()):
            if not (name.startswith("xl/worksheets/sheet") and name.endswith(".xml")):
                continue
            last_row = 0
            for row in root.iter(f"{{{_MAIN}}}row"):
                r = int(row.get("r"))
                assert r > last_row, f"{name}: rows out of order"
                last_row = r
                last_col = 0
                for cell in row:
                    m = _CELL.match(cell.get("r"))
                    assert m and int(m.group(2)) == r
                    c = _col(m.group(1))
                    assert c > last_col, f"{name}: cells out of order in row {r}"
                    last_col = c
            found["sheets"].append(name)
    return found


HINDI = ("नील का रंग पक्का करने के लिए हम पहले कपड़े को धोते हैं", "फिर लकड़ी के ठप्पे से छपाई होती है और मिट्टी का लेप लगाते हैं", "यह काम हमारे परिवार में चार पीढ़ियों से चल रहा है", "धूप में सुखाने के बाद रंग और गहरा हो जाता है")
ENGLISH = ("To fix the indigo we first wash the cloth", "Then we print with the wooden block and apply the mud paste", "This work has run in our family for four generations", "After drying in the sun the colour gets deeper")
SPEAKERS = (("s0", "Ankit Shah", "RESEARCHER"), ("s1", "Om Prakash Chhipa", "ARTISAN"), ("s2", "Heera Singh", "ARTISAN"))


def production_material(recordings: int = 420, images: int = 540, artisans: int = 600, sittings: int = 60, answers_each: int = 62) -> dict[str, Any]:
    now = datetime(2026, 11, 5, 10, 30, tzinfo=UTC)
    workshops = [SimpleNamespace(id=f"w{i}", title=f"Workshop {i}", status="APPROVED", createdAt=now, createdById="u1", locationId=None, startDate=now, endDate=now + timedelta(days=3), place=f"Place {i}") for i in range(8)]
    crafts = [SimpleNamespace(id=f"c{i}", name=f"Craft {i}", createdAt=now, createdById="u1", status="APPROVED", subCategoryId=None) for i in range(12)]
    people = [
        SimpleNamespace(
            id=f"a{i}", name=f"Artisan {i} Kumar", craftId=f"c{i % 12}", workshopId=f"w{i % 8}", status="APPROVED" if i % 3 else "PENDING",
            phone="9999999999", createdAt=now, updatedAt=now, createdById="u1", locationId=None, dateOfBirth=datetime(1970, 1, 1, tzinfo=UTC),
            gender="Female" if i % 2 else "Male", village=f"Village {i % 40}", district=f"District {i % 9}", state="Rajasthan",
            languages=["Hindi", "Marwari"], yearsInCraft=10 + i % 30, familyMembers=4 + i % 5, notes=f"Learnt from the family; {HINDI[i % 4]}",
            biography=f"{ENGLISH[i % 4]}. {HINDI[(i + 1) % 4]}", awards="State award" if i % 7 == 0 else None, cooperative=f"Co-op {i % 11}",
            education=("Primary", "Secondary", "Graduate")[i % 3], maritalStatus="Married" if i % 4 else "Single", mainProduct=f"Stole {i % 17}",
            rawMaterials="Cotton, indigo, mud", marketChannel=("Haat", "Exporter", "Online")[i % 3], monthlyIncome=8000 + 50 * i,
            apprentices=i % 4, workplace="Home workshop", toolsOwned=f"{3 + i % 9} blocks", aspirations=ENGLISH[(i + 2) % 4],
        )
        for i in range(artisans)
    ]
    media = []
    for i in range(recordings + images):
        audio = i < recordings
        media.append(
            SimpleNamespace(
                id=f"m{i}", originalFilename=f"D_SEC_RECORDING_{i:06d}_240620261438.aac" if audio else f"photo-{i}.jpg", mediaType="AUDIO" if audio else "IMAGE",
                sizeBytes=1024 * 1024 * (3 + i % 40), artisanId=f"a{i % artisans}", workshopId=f"w{i % 8}", linkedRecordType=None, linkedRecordId=None,
                recordedAt=now, createdAt=now, updatedAt=now, uploadedById="u1", hasTranscript=audio, transcriptWithheld=False,
                transcriptStatus="COMPLETED" if audio else None, mimeType="audio/aac" if audio else "image/jpeg", status="APPROVED",
                contentSha256=f"{i:064x}", caption=f"Caption {i}", durationMs=600000 if audio else None,
                title=f"Take {i}", device=("Phone", "Recorder")[i % 2], place=f"Village {i % 40}", season=("Summer", "Monsoon", "Winter")[i % 3], notes=ENGLISH[i % 4] * (60 if i % 50 == 0 else 1),
                extraMetadata={"device": {"model": f"Phone {i % 7}", "app": "0.0.15"}, "fieldProvenance": {"caption": {"at": "2026-11-05T10:30:00Z", "by": "u1", "byName": "Asha Rao"}}},
            )
        )
    products = [SimpleNamespace(id=f"p{i}", productName=f"Indigo stole {i}", artisanId=f"a{i}", workshopId=f"w{i % 8}", status="APPROVED", createdAt=now, updatedAt=now, createdById="u1", locationId=None, material="Cotton", price=1200 + i, description=ENGLISH[i % 4]) for i in range(120)]
    tools = [SimpleNamespace(id=f"t{i}", toolkitName=f"Block {i}", artisanId=f"a{i}", status="APPROVED", createdAt=now, updatedAt=now, createdById="u1", locationId=None, material="Teak", description=ENGLISH[i % 4]) for i in range(120)]
    interviews = []
    for i in range(sittings):
        responses = [
            SimpleNamespace(id=f"r{i}-{k}", questionId=f"qq{k}", answerText=f"{HINDI[k % 4]} ({k})", question=SimpleNamespace(prompt=f"Question {k}?", sectionTitle=f"Section {k % 6}", sectionCode=f"S{k % 6}"))
            for k in range(answers_each)
        ]
        interviews.append(SimpleNamespace(id=f"i{i}", title=f"Sitting {i}", workshopId=f"w{i % 8}", status="APPROVED", createdAt=now, createdById="u1", locationId=None, questionnaireId="q1", responses=responses))
    return {"workshops": workshops, "crafts": crafts, "artisans": people, "products": products, "processes": [], "tools": tools, "interviews": interviews, "media": media, "designWorkshops": []}


def production_turns(version: str, segments_each: int) -> list[dict[str, Any]]:
    mid = version.removeprefix("v-")
    seed = sum(ord(ch) for ch in mid)
    out = []
    speaker = seed % 3
    for p in range(segments_each):
        if (p + seed) % 3 == 0:
            speaker = (speaker + 1) % 3
        out.append(
            {
                "id": f"t{p}-{mid}", "versionId": version, "mediaFileId": mid, "position": p, "speakerKey": f"s{speaker}",
                "startMs": p * 6000, "endMs": p * 6000 + 5500, "language": "hi",
                "machineText": f"{HINDI[(p + seed) % 4]}, {HINDI[(p + 1) % 4][: 20 + (seed + p) % 25]} {mid}.{p}" * (40 if p % 97 == 0 else 1), "editedText": None,
                "machineEnglish": f"{ENGLISH[(p + seed) % 4]}, {ENGLISH[(p + 1) % 4][: 20 + (seed + p) % 25]} {mid}.{p}" * (40 if p % 97 == 0 else 1), "editedEnglish": None,
                "translationStatus": "TRANSLATED", "translationModel": "claude", "editedAt": None, "editedById": None, "revision": 1,
                "createdAt": None, "updatedAt": None,
            }
        )
    return out


def production_raw(recordings: int = 420, segments_each: int = 49) -> Any:
    audio = {f"m{i}" for i in range(recordings)}

    def raw(sql: str, *args: Any) -> list[dict[str, Any]]:
        ids = list(args[0]) if args else []
        if '"TranscriptVersion"' in sql and "DISTINCT ON" in sql:
            return [{"id": f"v-{m}", "mediaFileId": m, "version": 1, "languages": ["hi"], "durationMs": segments_each * 6000, "translationStatus": "COMPLETED", "turnCount": segments_each} for m in ids if m in audio]
        if 'FROM "TranscriptTurn"' in sql and "GROUP BY" in sql:
            return [{"id": v, "done": segments_each, "total": segments_each} for v in ids]
        if 'FROM "TranscriptTurn"' in sql:
            return [t for v in ids for t in production_turns(v, segments_each)]
        if '"TranscriptSpeaker"' in sql:
            return [{"mediaFileId": m, "speakerKey": k, "name": n, "role": r} for m in ids for k, n, r in SPEAKERS]
        return []

    return raw


def production_context(options: contract.ReportOptions | None = None, *, recordings: int = 420, segments_each: int = 49) -> contract.ReportContext:
    ctx = context(options or contract.ReportOptions(extras={"everything": True}))
    ctx.path = ""
    ctx.folder_name = "Repository"
    ctx.material = {"records": production_material(recordings=recordings), "sheets": [], "level": "Repository"}
    ctx.shared["db"] = FakeDB(raw=production_raw(recordings, segments_each))
    return ctx


def rich_context(options: contract.ReportOptions | None = None, *, portal_key: str = "designer") -> contract.ReportContext:
    now = datetime(2026, 11, 5, 10, 30, tzinfo=UTC)
    n = SimpleNamespace
    statuses = ("APPROVED", "PENDING", "NEEDS_REVISION", "REJECTED")
    ctx = context(options or contract.ReportOptions())
    ctx.portal = portal_key
    ctx.path = ""
    records = ctx.material["records"]
    for i, artisan in enumerate(records["artisans"]):
        artisan.status = statuses[i % len(statuses)]
        artisan.updatedAt = now
    records["workshops"].append(n(id="w2", title="Sanganer dabu", status="REJECTED", createdAt=now, createdById="u1", locationId=None, place="Sanganer"))
    records["products"] = [n(id=f"p{i}", productName=f"Indigo stole {i}", artisanId=f"a{i}", workshopId="w1", status=statuses[i], createdAt=now, createdById="u1", locationId=None) for i in range(3)]
    records["processes"] = [n(id="pr1", name="Dabu resist", productId="p0", status="PENDING", createdAt=now, createdById="u1")]
    records["tools"] = [n(id=f"t{i}", toolkitName=f"Block {i}", artisanId=f"a{i}", status=statuses[i + 1], createdAt=now, createdById="u1", locationId=None) for i in range(2)]
    records["interviews"] = [
        n(id=f"i{i}", title=f"Sitting {i}", workshopId="w1", status=statuses[i], createdAt=now, createdById="u1", locationId=None, questionnaireId="q1",
          responses=[n(questionId="qq1", answerText="Indigo", question=n(prompt="Which dye do you use?", sectionTitle="Dyes", sectionCode="A"))])
        for i in range(2)
    ]
    records["media"][1].linkedRecordType = "product"
    records["media"][1].linkedRecordId = "p0"
    records["media"][3].questionnaireInterviewId = "i0"
    ctx.shared["db"] = FakeDB(
        {
            "ProcessStep": [n(id="s1", processId="pr1", sortOrder=1, name="Mud paste", createdAt=now)],
            "WorkshopArtisan": [n(workshopId="w1", artisanId=f"a{i}", createdAt=now) for i in range(3)],
            "QuestionnaireInterviewArtisan": [n(interviewId="i0", artisanId="a0", createdAt=now, sortOrder=0), n(interviewId="i1", artisanId="a1", createdAt=now, sortOrder=0)],
            "Questionnaire": [n(id="q1", title="Craft questionnaire", createdAt=now)],
            "QuestionnaireQuestion": [n(id="qq1", questionnaireId="q1", prompt="Which dye do you use?", sectionCode="A", isActive=True, lineageId="qq1", createdAt=now)],
            "QuestionnaireResponse": [n(id="r1", interviewId="i0", questionId="qq1", answerText="Indigo", createdAt=now, updatedAt=now, answeredById="u1")],
            "QuestionnaireRecording": [n(id="rec1", mediaFileId="m0", capturedAt=now, createdAt=now, recordedById="u1", removedAt=None)],
            "QuestionnaireRecordingTarget": [n(recordingId="rec1", targetKey="Q:qq1", questionLineageId="qq1", sectionId=None)],
            "QuestionnaireRecordingSubject": [n(recordingId="rec1", artisanId="a0")],
            "ReviewLog": [n(id="rl1", recordId="a0", recordType="ARTISAN", status="APPROVED", reviewerId="u1", note="Checked", createdAt=now)],
        },
        raw=rich_raw,
    )
    return ctx


def rich_raw(sql: str, *args: Any) -> list[dict[str, Any]]:
    if '"TranscriptGlossarySighting"' in sql:
        ids = list(args[0]) if args else []
        return [{"id": f"gs-{m}", "mediaFileId": m, "startMs": 1000, "termId": "g1", "term": "dabu", "gloss": "mud resist paste", "occurrences": len(ids), "craft": "Block printing", "namesCraft": None} for m in ids]
    return turns_raw(sql, *args)


_SI = re.compile(r"<si>(.*?)</si>", re.DOTALL)
_IS = re.compile(r"<is>(.*?)</is>", re.DOTALL)
_RUN = re.compile(r"<r>(.*?)</r>", re.DOTALL)
_TEXT = re.compile(r"<t(?:\s[^>]*)?>(.*?)</t>|<t(?:\s[^>]*)?/>", re.DOTALL)
_CONTROL = re.compile(r"[\x00-\x08\x0b\x0c\x0e-\x1f]|_x00(?:0[0-8]|0[bBcC]|0[eEfF]|1[0-9a-fA-F])_")


def _unescape(text: str) -> str:
    return text.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", '"').replace("&apos;", "'").replace("&amp;", "&")


def string_problems(payload: bytes) -> list[str]:
    problems: list[str] = []
    with zipfile.ZipFile(io.BytesIO(payload)) as z:
        parts = [(n, z.read(n).decode("utf-8")) for n in z.namelist() if n == "xl/sharedStrings.xml" or (n.startswith("xl/worksheets/sheet") and n.endswith(".xml"))]
    for name, xml in parts:
        items = _SI.findall(xml) if name.endswith("sharedStrings.xml") else _IS.findall(xml)
        for index, item in enumerate(items):
            where = f"{name}#{index}"
            runs = _RUN.findall(item)
            if runs:
                if len(runs) < 2:
                    problems.append(f"{where}: a rich string with {len(runs)} run")
                texts = []
                for run in runs:
                    found = _TEXT.search(run)
                    if found is None or not found.group(1):
                        problems.append(f"{where}: a run without text")
                        continue
                    texts.append(_unescape(found.group(1)))
                text = "".join(texts)
            else:
                found = _TEXT.search(item)
                text = _unescape(found.group(1) or "") if found else ""
            if _CONTROL.search(text):
                problems.append(f"{where}: control characters")
            if len(text) > 32767:
                problems.append(f"{where}: {len(text)} characters")
    return problems
