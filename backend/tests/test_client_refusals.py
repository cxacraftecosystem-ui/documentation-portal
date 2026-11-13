from __future__ import annotations

import importlib.util
import sys
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "deploy" / "client_refusals.py"
spec = importlib.util.spec_from_file_location("client_refusals", SCRIPT)
client_refusals = importlib.util.module_from_spec(spec)
sys.modules["client_refusals"] = client_refusals
spec.loader.exec_module(client_refusals)


def _line(time: str, method: str, target: str, status: int, agent: str = "okhttp/5.5.1") -> str:
    return f'10.0.0.5 - - [13/Nov/2026:{time} +0000] "{method} {target} HTTP/1.1" {status} 112 "-" "{agent}"\n'


def test_a_burst_of_android_422s_on_tool_saves_is_reported_by_route_not_by_id() -> None:
    lines = [_line(f"05:26:{10 + i:02d}", "POST", "/api/tools", 422) for i in range(5)]
    report = client_refusals.scan(lines)
    alerts = report.alerts(3, 3, 10)
    assert alerts == ["5x 422 on POST /api/tools from android"]


def test_a_stale_session_update_is_reported_with_the_id_folded() -> None:
    lines = [_line(f"05:02:{11 + i:02d}", "PATCH", "/api/questionnaire/sessions/cmweidcqq030lcbta7din3wq5", 409) for i in range(3)]
    alerts = client_refusals.scan(lines).alerts(3, 3, 10)
    assert alerts == ["3x 409 on PATCH /api/questionnaire/sessions/{id} from android"]


def test_staged_clips_deleted_in_a_burst_and_twice_each_are_reported() -> None:
    keys = [f"media/2026/11/13/staged-{n}.m4a" for n in range(3)]
    lines = [
        _line("05:25:29", "DELETE", f"/api/media/object?objectKey={key}", 204)
        for key in keys
        for _ in range(2)
    ]
    alerts = client_refusals.scan(lines).alerts(3, 3, 10)
    assert "6 staged-object deletes within 10s from one client" in alerts
    assert "3 staged objects deleted more than once" in alerts


def test_reads_successes_and_browser_noise_stay_quiet() -> None:
    lines = [
        _line("06:00:00", "POST", "/api/tools", 201),
        _line("06:00:01", "GET", "/api/tools?page=2", 422),
        _line("06:00:02", "POST", "/api/tools", 422, agent="Mozilla/5.0"),
        _line("06:00:40", "DELETE", "/api/media/object?objectKey=media/staged-1.m4a", 204),
        "garbage\n",
    ]
    assert client_refusals.scan(lines).alerts(3, 3, 10) == []


def test_the_reader_resumes_after_the_last_line_and_restarts_on_rotation(tmp_path: Path) -> None:
    log = tmp_path / "access.log"
    state = tmp_path / "state.json"
    log.write_text(_line("05:00:00", "POST", "/api/tools", 422), encoding="utf-8")
    assert len(client_refusals.read_new(str(log), str(state))) == 1
    assert client_refusals.read_new(str(log), str(state)) == []
    with log.open("a", encoding="utf-8") as handle:
        handle.write(_line("05:00:01", "POST", "/api/tools", 422))
    assert len(client_refusals.read_new(str(log), str(state))) == 1
    log.unlink()
    log.write_text(_line("05:00:02", "POST", "/api/tools", 422), encoding="utf-8")
    assert len(client_refusals.read_new(str(log), str(state))) == 1
