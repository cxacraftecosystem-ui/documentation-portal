"""The SES sender and the templates (``app/services/mailer.py``), with SES stubbed.

No network and no database: ``_ses_client`` is replaced by a recorder, so what is asserted is the
SESv2 request this module builds and how a refusal is classified for the outbox's retry rule.
"""

from __future__ import annotations

from typing import Any

import pytest
from botocore.exceptions import ClientError, EndpointConnectionError

from app.core.config import get_settings
from app.services import mailer


def _settings(**overrides: Any) -> Any:
    base = {
        "mail_from_address": "no-reply@repo.example.org",
        "mail_from_name": "Field Repository",
        "mail_reply_to": "help@repo.example.org",
        "mail_ses_region": "ap-south-1",
        "mail_ses_configuration_set": None,
        "next_public_app_url": "https://repo.example.org",
    }
    base.update(overrides)
    return get_settings().model_copy(update=base)


class _FakeSes:
    def __init__(self) -> None:
        self.error: Exception | None = None
        self.requests: list[dict[str, Any]] = []

    def send_email(self, **request: Any) -> dict[str, Any]:
        self.requests.append(request)
        if self.error is not None:
            raise self.error
        return {"MessageId": "ses-1"}


@pytest.fixture
def ses(monkeypatch: pytest.MonkeyPatch) -> dict[str, Any]:
    seen: dict[str, Any] = {"fake": _FakeSes()}

    def client(region: str, access_key: Any, secret_key: Any) -> _FakeSes:
        seen["region"] = region
        return seen["fake"]

    monkeypatch.setattr(mailer, "_ses_client", client)
    return seen


def test_mail_is_on_exactly_when_a_from_address_is_set() -> None:
    assert mailer.mail_configured(_settings())
    assert not mailer.mail_configured(_settings(mail_from_address=None))


def test_the_request_carries_both_parts_the_sender_and_the_reply_to(ses: dict[str, Any]) -> None:
    assert (
        mailer.send(
            to="asha@example.org", subject="S", text="T", html_body="<p>H</p>",
            settings=_settings(mail_ses_configuration_set="repo-events"),
        )
        == "ses-1"
    )
    assert ses["region"] == "ap-south-1"
    (request,) = ses["fake"].requests
    assert request["FromEmailAddress"] == '"Field Repository" <no-reply@repo.example.org>'
    assert request["ReplyToAddresses"] == ["help@repo.example.org"]
    assert request["ConfigurationSetName"] == "repo-events"
    body = request["Content"]["Simple"]["Body"]
    assert body["Text"]["Data"] == "T" and body["Html"]["Data"] == "<p>H</p>"


@pytest.mark.parametrize(
    ("error", "permanent"),
    [
        (ClientError({"Error": {"Code": "MessageRejected"}}, "SendEmail"), True),
        (ClientError({"Error": {"Code": "TooManyRequestsException"}}, "SendEmail"), False),
        (EndpointConnectionError(endpoint_url="https://email.ap-south-1.amazonaws.com"), False),
    ],
)
def test_a_refusal_is_classified_for_the_retry_rule(ses: dict[str, Any], error: Exception, permanent: bool) -> None:
    ses["fake"].error = error
    with pytest.raises(mailer.SendFailed) as raised:
        mailer.send(to="a@example.org", subject="s", text="t", html_body="h", settings=_settings())
    assert raised.value.permanent is permanent


def test_the_access_e_mail_points_at_sign_in() -> None:
    rendered = mailer.render(mailer.ACCESS_GRANTED, {"recipientName": "Asha"}, settings=_settings())
    assert rendered.subject == "You can now sign in to the Field Repository"
    assert "https://repo.example.org/login" in rendered.text
    assert rendered.text.startswith("Dear Asha,")


def test_task_notices_name_the_task_escape_it_and_offer_the_opt_out() -> None:
    params = {"recipientName": "Asha", "taskTitle": "Photograph <the> looms", "actorName": "Ravi"}
    sent_back = mailer.render(mailer.TASK_SENT_BACK, params, settings=_settings())
    assert sent_back.subject == "“Photograph <the> looms” has been sent back to you"
    assert "&lt;the&gt;" in sent_back.html and "<the>" not in sent_back.html
    assert "https://repo.example.org/tasks" in sent_back.text
    assert "turn these e-mails off in Settings" in sent_back.text
    assert "waiting for your approval" in mailer.render(mailer.TASK_SUBMITTED, params, settings=_settings()).text
    assert "approved your work" in mailer.render(mailer.TASK_APPROVED, params, settings=_settings()).text
