"""E-MAIL: the Amazon SES sender and the templates it sends.

The same construction as the Design Prototype Workshop's ``app/services/mailer.py``. This module
turns a message KIND and its parameters into a subject, a plain-text body and a small HTML body,
and hands those to SES. Queueing, retries and the send log are :mod:`app.services.email_outbox`,
the only caller of :func:`send`.

Mail is on exactly when ``MAIL_FROM_ADDRESS`` is set (:func:`mail_configured`). Unset is a complete,
quiet product: nothing is queued, ``GET /preferences/notifications`` answers ``available: false``,
and the web hides every e-mail control without a sentence about it.

No body ever reaches a log line, and no body is stored: the outbox keeps the kind and the template
parameters and renders at send time. SESv2 ``send_email`` with ``Content.Simple`` (subject, text and
HTML, UTF-8), using the media bucket's ``AWS_ACCESS_KEY_ID``/``AWS_SECRET_ACCESS_KEY`` — that IAM user
needs ``ses:SendEmail`` on the verified identity. A throttle or an SES fault is TRANSIENT and is
retried; a rejected message or an unverified sender is PERMANENT and is not.
"""

from __future__ import annotations

import html
import logging
from dataclasses import dataclass
from functools import lru_cache
from typing import Any

from app.core.config import Settings, get_settings

logger = logging.getLogger(__name__)

#: Message kinds. Stored on ``EmailMessage.kind``; renaming one orphans the rows already queued.
ACCESS_GRANTED = "ACCESS_GRANTED"
TASK_SUBMITTED = "TASK_SUBMITTED"
TASK_SENT_BACK = "TASK_SENT_BACK"
TASK_APPROVED = "TASK_APPROVED"
KINDS = frozenset({ACCESS_GRANTED, TASK_SUBMITTED, TASK_SENT_BACK, TASK_APPROVED})
#: The kinds a person can switch off in Settings (``UserPreference.emailTaskUpdates``).
TASK_KINDS = frozenset({TASK_SUBMITTED, TASK_SENT_BACK, TASK_APPROVED})

PRODUCT_NAME = "Field Repository"

#: SESv2 error codes after which sending the same message again cannot succeed.
PERMANENT_ERROR_CODES = frozenset(
    {
        "MessageRejected",
        "MailFromDomainNotVerifiedException",
        "BadRequestException",
        "NotFoundException",
        "AccountSuspendedException",
    }
)


class SendFailed(Exception):
    """SES did not accept the message. ``permanent`` says whether trying again could help."""

    def __init__(self, code: str, *, permanent: bool) -> None:
        super().__init__(code)
        self.code = code
        self.permanent = permanent


def mail_configured(settings: Settings | None = None) -> bool:
    settings = settings or get_settings()
    return bool((settings.mail_from_address or "").strip())


def _from_header(settings: Settings) -> str:
    name = (settings.mail_from_name or "").strip().replace('"', "")
    address = (settings.mail_from_address or "").strip()
    return f'"{name}" <{address}>' if name else address


@lru_cache(maxsize=4)
def _ses_client(region: str, access_key: str | None, secret_key: str | None) -> Any:
    import boto3
    from botocore.config import Config

    return boto3.client(
        "sesv2",
        region_name=region,
        aws_access_key_id=access_key or None,
        aws_secret_access_key=secret_key or None,
        config=Config(retries={"max_attempts": 2, "mode": "standard"}, connect_timeout=5, read_timeout=15),
    )


def send(*, to: str, subject: str, text: str, html_body: str, settings: Settings | None = None) -> str:
    """Hand one message to SES and return its message id. Blocking: call it from a thread."""
    from botocore.exceptions import BotoCoreError, ClientError

    settings = settings or get_settings()
    if not mail_configured(settings):
        raise SendFailed("MailNotConfigured", permanent=False)
    request: dict[str, Any] = {
        "FromEmailAddress": _from_header(settings),
        "Destination": {"ToAddresses": [to]},
        "Content": {
            "Simple": {
                "Subject": {"Data": subject, "Charset": "UTF-8"},
                "Body": {
                    "Text": {"Data": text, "Charset": "UTF-8"},
                    "Html": {"Data": html_body, "Charset": "UTF-8"},
                },
            }
        },
    }
    if (settings.mail_reply_to or "").strip():
        request["ReplyToAddresses"] = [settings.mail_reply_to.strip()]
    if (settings.mail_ses_configuration_set or "").strip():
        request["ConfigurationSetName"] = settings.mail_ses_configuration_set.strip()
    client = _ses_client(
        settings.mail_ses_region, settings.aws_access_key_id, settings.aws_secret_access_key
    )
    try:
        answer = client.send_email(**request)
    except ClientError as exc:
        code = str(exc.response.get("Error", {}).get("Code") or "ClientError")
        raise SendFailed(code, permanent=code in PERMANENT_ERROR_CODES) from None
    except BotoCoreError as exc:  # network, endpoint, credentials resolution
        raise SendFailed(type(exc).__name__, permanent=False) from None
    return str(answer.get("MessageId") or "")


# --------------------------------------------------------------------------------------
# Templates
# --------------------------------------------------------------------------------------


@dataclass(frozen=True)
class Rendered:
    subject: str
    text: str
    html: str


def app_url(path: str = "", settings: Settings | None = None) -> str:
    base = str((settings or get_settings()).next_public_app_url).rstrip("/")
    return f"{base}{path}"


def _e(value: Any) -> str:
    return html.escape(str(value or ""), quote=True)


def _greeting(name: Any) -> str:
    first = str(name or "").strip()
    return f"Dear {first}," if first else "Hello,"


def _html(paragraphs: list[str], *, button: tuple[str, str] | None = None) -> str:
    """A deliberately plain HTML part: one column, one button, no images, no tracking. Every value
    arrives here already escaped."""
    parts = [
        '<!doctype html><html><body style="margin:0;padding:24px;background:#f6f5fb;'
        'font-family:-apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif;color:#1f2328;">',
        '<div style="max-width:560px;margin:0 auto;background:#ffffff;border:1px solid #e2e0ea;'
        'border-radius:8px;padding:24px;">',
        f'<p style="margin:0 0 16px;font-size:13px;letter-spacing:.04em;color:#5d5874;">{_e(PRODUCT_NAME)}</p>',
    ]
    for paragraph in paragraphs:
        parts.append(f'<p style="margin:0 0 14px;font-size:15px;line-height:1.55;">{paragraph}</p>')
    if button is not None:
        label, href = button
        parts.append(
            f'<p style="margin:20px 0;"><a href="{href}" style="display:inline-block;background:#5b3fa8;'
            'color:#ffffff;text-decoration:none;padding:10px 18px;border-radius:6px;font-size:15px;">'
            f"{label}</a></p>"
            '<p style="margin:0 0 14px;font-size:13px;line-height:1.5;color:#5d5874;">'
            f"If the button does not open, copy this address into your browser:<br>{href}</p>"
        )
    parts.append("</div></body></html>")
    return "".join(parts)


def subject_for(kind: str, params: dict[str, Any]) -> str:
    title = str(params.get("taskTitle") or "a task").strip()
    if kind == ACCESS_GRANTED:
        return f"You can now sign in to the {PRODUCT_NAME}"
    if kind == TASK_SUBMITTED:
        return f"“{title}” is waiting for your approval"
    if kind == TASK_SENT_BACK:
        return f"“{title}” has been sent back to you"
    if kind == TASK_APPROVED:
        return f"“{title}” has been approved"
    raise ValueError(f"Unknown e-mail kind {kind!r}")


def render(kind: str, params: dict[str, Any], *, settings: Settings | None = None) -> Rendered:
    settings = settings or get_settings()
    subject = subject_for(kind, params)
    greeting = _greeting(params.get("recipientName"))
    sign_off = f"— {PRODUCT_NAME}"

    if kind == ACCESS_GRANTED:
        link = app_url("/login", settings)
        lead = (
            f"An administrator has given this address access to the {PRODUCT_NAME}. "
            "Sign in with Google using this e-mail address."
        )
        unasked = "If you were not expecting this, you can ignore this message."
        text = "\n\n".join([greeting, lead, f"Sign in: {link}", unasked, sign_off]) + "\n"
        return Rendered(subject, text, _html([_e(greeting), _e(lead), _e(unasked)], button=("Sign in", _e(link))))

    if kind in TASK_KINDS:
        title = str(params.get("taskTitle") or "a task")
        actor = str(params.get("actorName") or "Somebody")
        if kind == TASK_SUBMITTED:
            lead = f"{actor} has marked “{title}” finished. It is waiting for your approval on Tasks."
        elif kind == TASK_SENT_BACK:
            lead = f"{actor} has sent “{title}” back to you. It is on your list again as work still to do."
        else:
            lead = f"{actor} has approved your work on “{title}”."
        link = app_url("/tasks", settings)
        why = "You can turn these e-mails off in Settings."
        text = "\n\n".join([greeting, lead, f"Open Tasks: {link}", why, sign_off]) + "\n"
        return Rendered(subject, text, _html([_e(greeting), _e(lead), _e(why)], button=("Open Tasks", _e(link))))

    raise ValueError(f"Unknown e-mail kind {kind!r}")
