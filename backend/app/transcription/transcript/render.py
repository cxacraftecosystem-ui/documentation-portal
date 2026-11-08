from __future__ import annotations

import io
import re
import zipfile
from dataclasses import dataclass, field
from xml.sax.saxutils import escape

ORIGINAL = "original"
ENGLISH = "english"
BOTH = "both"
TRACKS = (ORIGINAL, ENGLISH, BOTH)

ALREADY_ENGLISH = "ALREADY_ENGLISH"


@dataclass
class RenderTurn:
    id: str
    speaker_name: str
    start_ms: int
    end_ms: int
    text: str
    english: str | None = None
    translation_status: str | None = None


@dataclass
class RenderDoc:
    title: str
    languages: list[str]
    duration_ms: int | None
    speaker_count: int
    turns: list[RenderTurn] = field(default_factory=list)
    english_text: str | None = None


ENGLISH_HEADING = "## English"


def timestamp(ms: int | None) -> str:
    total = max(0, int(ms or 0)) // 1000
    return f"{total // 3600:02d}:{total % 3600 // 60:02d}:{total % 60:02d}"


def length(ms: int | None) -> str:
    total = max(0, int(ms or 0)) // 1000
    hours, minutes, seconds = total // 3600, total % 3600 // 60, total % 60
    if hours:
        return f"{hours}:{minutes:02d}:{seconds:02d}"
    return f"{minutes}:{seconds:02d}"


_NAME_STRIP = re.compile(r"[*`|]")


def clean_name(name: str) -> str:
    return " ".join(_NAME_STRIP.sub("", str(name or "")).split()) or "Speaker"


def english_of(turn: RenderTurn) -> str:
    if turn.translation_status == ALREADY_ENGLISH:
        return turn.text
    return turn.english if turn.english and turn.english.strip() else turn.text


def quote_of(turn: RenderTurn) -> str | None:
    if turn.translation_status == ALREADY_ENGLISH or not (turn.english and turn.english.strip()):
        return None
    return "\n".join(f"> {line}".rstrip() for line in turn.english.strip().split("\n"))


def header(doc: RenderDoc) -> str:
    languages = ", ".join(doc.languages) or "Unknown"
    return (
        f"# {doc.title}\n"
        f"| Language | {languages} | Length | {length(doc.duration_ms)} | Speakers | {doc.speaker_count} |"
    )


def body(turn: RenderTurn, track: str) -> str:
    if track == ENGLISH:
        return english_of(turn).strip()
    text = turn.text.strip()
    if track == BOTH:
        quote = quote_of(turn)
        return f"{text}\n{quote}" if quote else text
    return text


def block(turn: RenderTurn, track: str, *, timestamps: bool = True) -> str:
    if not timestamps:
        return f"**{clean_name(turn.speaker_name)}**\n{body(turn, track)}"
    return f"**{clean_name(turn.speaker_name)}** · `{timestamp(turn.start_ms)}`\n{body(turn, track)}"


def copy_all(doc: RenderDoc, track: str, *, timestamps: bool = True) -> str:
    manual = (doc.english_text or "").strip()
    if manual and track == ENGLISH:
        return "\n\n".join([header(doc), manual])
    parts = [header(doc), *(block(turn, track, timestamps=timestamps) for turn in doc.turns)]
    if manual and track == BOTH:
        parts += [ENGLISH_HEADING, manual]
    return "\n\n".join(parts)


def copy_selected(doc: RenderDoc, turn_ids: list[str], track: str) -> str:
    wanted = set(turn_ids)
    return "\n\n".join(block(turn, track) for turn in doc.turns if turn.id in wanted)


def copy_single(turn: RenderTurn, track: str) -> str:
    return body(turn, track)



def _cue_time(ms: int, separator: str) -> str:
    ms = max(0, int(ms or 0))
    return f"{timestamp(ms)}{separator}{ms % 1000:03d}"


def _cue_text(text: str) -> str:
    lines = [line.strip() for line in str(text or "").replace("-->", "→").splitlines()]
    return "\n".join(line for line in lines if line)


def _cues(doc: RenderDoc, track: str) -> list[tuple[int, int, str, str]]:
    cues = []
    for turn in doc.turns:
        text = _cue_text(english_of(turn) if track == ENGLISH else turn.text)
        if not text:
            continue
        end = turn.end_ms if turn.end_ms > turn.start_ms else turn.start_ms + 1000
        cues.append((turn.start_ms, end, clean_name(turn.speaker_name), text))
    return cues


def srt(doc: RenderDoc, track: str = ORIGINAL) -> str:
    return "\n\n".join(
        f"{n}\n{_cue_time(start, ',')} --> {_cue_time(end, ',')}\n{name}: {text}"
        for n, (start, end, name, text) in enumerate(_cues(doc, track), start=1)
    )


def vtt(doc: RenderDoc, track: str = ORIGINAL) -> str:
    cues = [
        f"{n}\n{_cue_time(start, '.')} --> {_cue_time(end, '.')}\n<v {name.replace('>', '')}>{text}"
        for n, (start, end, name, text) in enumerate(_cues(doc, track), start=1)
    ]
    return "\n\n".join(["WEBVTT", *cues])


_XML_INVALID = dict.fromkeys([*range(0x09), 0x0B, 0x0C, *range(0x0E, 0x20)])

_CONTENT_TYPES = (
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
    '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
    '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
    '<Default Extension="xml" ContentType="application/xml"/>'
    '<Override PartName="/word/document.xml" '
    'ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>'
    "</Types>"
)
_RELS = (
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
    '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
    '<Relationship Id="rId1" '
    'Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" '
    'Target="word/document.xml"/>'
    "</Relationships>"
)


def _x(text: str) -> str:
    return escape(str(text or "").translate(_XML_INVALID))


def _run(text: str, *, bold: bool = False, italic: bool = False, size: int | None = None, mono: bool = False) -> str:
    props = ""
    if bold:
        props += "<w:b/>"
    if italic:
        props += "<w:i/>"
    if mono:
        props += '<w:rFonts w:ascii="Consolas" w:hAnsi="Consolas"/>'
    if size:
        props += f'<w:sz w:val="{size}"/>'
    lines = str(text or "").split("\n")
    pieces = []
    for index, line in enumerate(lines):
        if index:
            pieces.append("<w:br/>")
        pieces.append(f'<w:t xml:space="preserve">{_x(line)}</w:t>')
    return f"<w:r>{f'<w:rPr>{props}</w:rPr>' if props else ''}{''.join(pieces)}</w:r>"


def _para(*runs: str, indent: int = 0, after: int = 120) -> str:
    ppr = f'<w:pPr><w:spacing w:after="{after}"/>{f"<w:ind w:left=\"{indent}\"/>" if indent else ""}</w:pPr>'
    return f"<w:p>{ppr}{''.join(runs)}</w:p>"


def docx(doc: RenderDoc, track: str = BOTH, *, timestamps: bool = True) -> bytes:
    paragraphs = [
        _para(_run(doc.title, bold=True, size=32), after=80),
        _para(
            _run(
                f"Language: {', '.join(doc.languages) or 'Unknown'} · Length: {length(doc.duration_ms)}"
                f" · Speakers: {doc.speaker_count}",
                italic=True,
                size=18,
            ),
            after=240,
        ),
    ]
    manual = (doc.english_text or "").strip()
    for turn in [] if manual and track == ENGLISH else doc.turns:
        speaker = [_run(clean_name(turn.speaker_name), bold=True)]
        if timestamps:
            speaker += [_run(" · "), _run(timestamp(turn.start_ms), mono=True, size=18)]
        paragraphs.append(_para(*speaker, after=0))
        if track == ENGLISH:
            paragraphs.append(_para(_run(english_of(turn).strip())))
            continue
        paragraphs.append(_para(_run(turn.text.strip()), after=60 if track == BOTH else 120))
        if track == BOTH and quote_of(turn) is not None:
            paragraphs.append(_para(_run(str(turn.english).strip(), italic=True), indent=360))
    if manual and track in (ENGLISH, BOTH):
        if track == BOTH:
            paragraphs.append(_para(_run("English", bold=True, size=26), after=120))
        paragraphs += [_para(_run(p.strip())) for p in re.split(r"\n\s*\n", manual) if p.strip()]
    document = (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        '<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">'
        f"<w:body>{''.join(paragraphs)}"
        '<w:sectPr><w:pgSz w:w="11906" w:h="16838"/>'
        '<w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440" w:header="708" '
        'w:footer="708" w:gutter="0"/></w:sectPr></w:body></w:document>'
    )
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("[Content_Types].xml", _CONTENT_TYPES)
        archive.writestr("_rels/.rels", _RELS)
        archive.writestr("word/document.xml", document)
    return buffer.getvalue()
