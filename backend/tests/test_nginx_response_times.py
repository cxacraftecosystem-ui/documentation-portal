from pathlib import Path

DEPLOY = Path(__file__).resolve().parents[2] / ".github" / "workflows" / "deploy-backend.yml"


def _step() -> str:
    text = DEPLOY.read_text(encoding="utf-8")
    start = text.index("- name: Log response times in nginx")
    end = text.index("REMOTE\n", text.index("<<'REMOTE'", start) + 10)
    return text[start:end]


def test_the_deploy_logs_request_and_upstream_times():
    step = _step()
    assert "$request_time" in step
    assert "$upstream_response_time" in step
    assert "access_log /var/log/nginx/response-times.log response_times;" in step


def test_a_refused_config_is_rolled_back_before_any_reload():
    step = _step()
    test_at = step.index("if ! sudo nginx -t; then")
    rollback_at = step.index("/tmp/response-times.prev \"$CONF\"", test_at)
    reload_at = step.index("sudo systemctl reload nginx")
    assert test_at < rollback_at < reload_at
