from __future__ import annotations

import argparse
import asyncio
import json
import re
import subprocess
import sys
import tempfile
import time
import warnings
import zipfile
from pathlib import Path


def build(out: Path, root: Path) -> None:
    sys.path.insert(0, str(root))
    import app
    from app.services.datareport import build as report_build
    from tests import report_fixtures as rf

    where = Path(app.__file__).resolve()
    if root.resolve() not in where.parents:
        raise SystemExit(f"imported app from {where}, not from {root}")
    ctx = rf.production_context()
    started = time.monotonic()
    if hasattr(report_build, "render_parts"):
        parts = asyncio.run(report_build.render_parts(ctx))
        books = {"main": parts.main, "companion": parts.companion}
    else:
        books = {"main": asyncio.run(report_build.render(ctx)), "companion": None}
    seconds = time.monotonic() - started
    out.mkdir(parents=True, exist_ok=True)
    for label, payload in books.items():
        if payload:
            (out / f"{label}.xlsx").write_bytes(payload)
    (out / "build.json").write_text(json.dumps({"seconds": round(seconds, 1)}))
    print(f"built in {seconds:.1f}s: {', '.join(k for k, v in books.items() if v)}")


def stats(path: Path) -> dict:
    with zipfile.ZipFile(path) as z:
        xml = sum(i.file_size for i in z.infolist())
        book = z.read("xl/workbook.xml").decode()
        rels = z.read("xl/_rels/workbook.xml.rels").decode()
        targets = dict(re.findall(r'Id="([^"]+)"[^>]*Target="([^"]+)"', rels))
        targets.update({b: a for a, b in re.findall(r'Target="([^"]+)"[^>]*Id="([^"]+)"', rels)})
        sheets = []
        for name, rid in re.findall(r'<sheet name="([^"]+)" sheetId="\d+"(?: state="hidden")? r:id="([^"]+)"', book):
            xml_sheet = z.read("xl/" + targets[rid].lstrip("/").removeprefix("xl/")).decode()
            sheets.append(
                {
                    "sheet": name,
                    "rows": xml_sheet.count("<row "),
                    "links": xml_sheet.count("<hyperlink "),
                    "formulas": xml_sheet.count("<f>") + xml_sheet.count("<f "),
                    "tables": xml_sheet.count("<tablePart "),
                    "mb": round(len(xml_sheet.encode()) / 1e6, 2),
                }
            )
    started = time.monotonic()
    with warnings.catch_warnings():
        warnings.simplefilter("ignore")
        import openpyxl

        openpyxl.load_workbook(path)
    load = time.monotonic() - started
    return {
        "file": path.name,
        "sheets": len(sheets),
        "rows": sum(s["rows"] for s in sheets),
        "links": sum(s["links"] for s in sheets),
        "formulas": sum(s["formulas"] for s in sheets),
        "xml_mb": round(xml / 1e6, 1),
        "zip_mb": round(path.stat().st_size / 1e6, 1),
        "openpyxl_load_s": round(load, 1),
        "per_sheet": sorted(sheets, key=lambda s: -s["mb"])[:10],
    }


def convert(path: Path, timeout: int) -> float | None:
    with tempfile.TemporaryDirectory() as tmp:
        started = time.monotonic()
        try:
            result = subprocess.run(
                ["soffice", "--headless", "--norestore", f"-env:UserInstallation=file://{tmp}/profile", "--convert-to", "csv", "--outdir", tmp, str(path)],
                capture_output=True,
                text=True,
                timeout=timeout,
                check=False,
            )
        except subprocess.TimeoutExpired:
            return None
        seconds = time.monotonic() - started
        if result.returncode != 0 or not any(Path(tmp).glob("*.csv")):
            return None
        return seconds


def clip_lines(value, lines: int):
    from openpyxl.cell.rich_text import CellRichText, TextBlock

    if not isinstance(value, (CellRichText, str)):
        return value
    blocks = list(value) if isinstance(value, CellRichText) else [value]
    kept = []
    seen = 0
    for block in blocks:
        text = block.text if isinstance(block, TextBlock) else str(block)
        room = lines - seen
        if room <= 0:
            break
        parts = text.split("\n")
        if len(parts) - 1 >= room:
            text = "\n".join(parts[:room]) + " …"
            seen = lines
        else:
            seen += len(parts) - 1
        kept.append(TextBlock(block.font, text) if isinstance(block, TextBlock) else text)
    return CellRichText(kept) if isinstance(value, CellRichText) else "".join(kept)


def shot(path: Path, sheet: str, out: Path, rows: int, cols: list[str], lines: int) -> None:
    import openpyxl
    from openpyxl.utils import get_column_letter

    with warnings.catch_warnings():
        warnings.simplefilter("ignore")
        book = openpyxl.load_workbook(path, rich_text=True)
    if sheet not in book.sheetnames:
        print(f"{path.name} has no sheet {sheet}")
        return
    for name in list(book.sheetnames):
        if name != sheet:
            book.remove(book[name])
    ws = book[sheet]
    for name in list(ws.tables):
        del ws.tables[name]
    if ws.max_row > rows:
        ws.delete_rows(rows + 1, ws.max_row - rows)
    header = [c.value for c in ws[5]]
    if cols:
        for index, label in enumerate(header, start=1):
            ws.column_dimensions[get_column_letter(index)].hidden = label not in cols
    for row in ws.iter_rows(min_row=6):
        for cell in row:
            if lines and isinstance(cell.value, object) and cell.value is not None and not isinstance(cell.value, (int, float)):
                cell.value = clip_lines(cell.value, lines)
        ws.row_dimensions[row[0].row].height = None
    ws.print_area = None
    ws.sheet_properties.pageSetUpPr.fitToPage = True
    ws.page_setup.fitToWidth = 1
    ws.page_setup.fitToHeight = 0
    ws.auto_filter.ref = None
    book.active = 0
    with tempfile.TemporaryDirectory() as tmp:
        trimmed = Path(tmp) / f"{out.stem}.xlsx"
        book.save(trimmed)
        result = subprocess.run(
            ["soffice", "--headless", "--norestore", f"-env:UserInstallation=file://{tmp}/profile", "--convert-to", "pdf", "--outdir", tmp, str(trimmed)],
            capture_output=True,
            text=True,
            timeout=600,
            check=False,
        )
        pdf = Path(tmp) / f"{out.stem}.pdf"
        if not pdf.is_file():
            print(f"no PDF for {path.name} / {sheet}: {result.stdout[-400:]} {result.stderr[-400:]}")
            return
        out.parent.mkdir(parents=True, exist_ok=True)
        made = subprocess.run(["pdftoppm", "-png", "-r", "110", "-f", "1", "-l", "1", "-singlefile", str(pdf), str(out.with_suffix(""))], capture_output=True, text=True, check=False)
        if out.is_file():
            print(f"wrote {out}")
        else:
            print(f"no PNG for {path.name} / {sheet}: {made.stderr[-400:]}")


def main() -> None:
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="cmd", required=True)
    b = sub.add_parser("build")
    b.add_argument("--root", required=True)
    b.add_argument("--out", required=True)
    s = sub.add_parser("stats")
    s.add_argument("files", nargs="+")
    s.add_argument("--convert-timeout", type=int, default=900)
    p = sub.add_parser("shot")
    p.add_argument("file")
    p.add_argument("sheet")
    p.add_argument("out")
    p.add_argument("--rows", type=int, default=40)
    p.add_argument("--cols", default="")
    p.add_argument("--lines", type=int, default=0)
    args = parser.parse_args()
    if args.cmd == "build":
        build(Path(args.out), Path(args.root))
    elif args.cmd == "stats":
        table = []
        for name in args.files:
            path = Path(name)
            if not path.is_file():
                continue
            found = stats(path)
            found["soffice_csv_s"] = convert(path, args.convert_timeout)
            table.append(found)
            print(json.dumps(found, indent=2))
        print("| file | sheets | rows | links | formulas | XML MB | zip MB | openpyxl load s | soffice csv s |")
        print("|---|---|---|---|---|---|---|---|---|")
        for f in table:
            print(f"| {f['file']} | {f['sheets']} | {f['rows']:,} | {f['links']:,} | {f['formulas']:,} | {f['xml_mb']} | {f['zip_mb']} | {f['openpyxl_load_s']} | {f['soffice_csv_s']} |")
    else:
        shot(Path(args.file), args.sheet, Path(args.out), args.rows, [c for c in args.cols.split(",") if c], args.lines)


if __name__ == "__main__":
    main()
