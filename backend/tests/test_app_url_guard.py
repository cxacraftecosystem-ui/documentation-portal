from __future__ import annotations

from pathlib import Path

import pytest

from app.core import config


def _settings(app_env: str, url: str) -> config.Settings:
    return config.Settings(APP_ENV=app_env, NEXT_PUBLIC_APP_URL=url)


@pytest.mark.parametrize(
    "url",
    [
        "http://localhost:3000",
        "https://localhost:3000",
        "http://field-repository.vercel.app",
        "https://127.0.0.1",
        "https://10.0.0.4",
        "https://0.0.0.0:3000",
        "https://web.localhost",
        "",
    ],
)
def test_production_refuses_to_boot_on_a_local_or_plain_http_app_url(url: str) -> None:
    with pytest.raises(RuntimeError, match="NEXT_PUBLIC_APP_URL"):
        config.verify_app_url_configuration(_settings("production", url))


@pytest.mark.parametrize("url", ["https://field-repository.vercel.app", "https://designer-repository.vercel.app/"])
def test_production_boots_on_a_public_https_app_url(url: str) -> None:
    config.verify_app_url_configuration(_settings("production", url))
    config.verify_app_url_configuration(_settings(" Production ", url))


def test_development_keeps_the_localhost_default() -> None:
    config.verify_app_url_configuration(_settings("development", "http://localhost:3000"))
    assert config.Settings(APP_ENV="development").next_public_app_url


def test_create_app_runs_the_guard(monkeypatch: pytest.MonkeyPatch) -> None:
    from app import main

    monkeypatch.setenv("APP_ENV", "production")
    monkeypatch.setenv("NEXT_PUBLIC_APP_URL", "http://localhost:3000")
    config.get_settings.cache_clear()
    try:
        with pytest.raises(RuntimeError, match="Refusing to start in production"):
            main.create_app()
    finally:
        config.get_settings.cache_clear()


def test_the_release_pins_production_and_a_public_app_url() -> None:
    workflow = Path(__file__).resolve().parents[2] / ".github" / "workflows" / "deploy-backend.yml"
    text = workflow.read_text(encoding="utf-8")
    assert "printf 'APP_ENV=%s\\n' 'production' >> .env" in text
    pinned = [line.strip() for line in text.splitlines() if "printf 'NEXT_PUBLIC_APP_URL=%s" in line]
    assert pinned, "deploy-backend.yml must pin NEXT_PUBLIC_APP_URL"
    for line in pinned:
        value = line.split("'")[3]
        assert config.app_url_problem(value) is None, value
