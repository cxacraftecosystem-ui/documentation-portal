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
from datetime import UTC, datetime
from functools import lru_cache
from typing import Any

from app.core.config import Settings, get_settings

logger = logging.getLogger(__name__)

#: Message kinds. Stored on ``EmailMessage.kind``; renaming one orphans the rows already queued.
ACCESS_GRANTED = "ACCESS_GRANTED"
TASK_SUBMITTED = "TASK_SUBMITTED"
TASK_SENT_BACK = "TASK_SENT_BACK"
TASK_APPROVED = "TASK_APPROVED"
#: Somebody got past the password and then entered too many wrong verification codes. A security
#: notice: always sent (no opt-out), carries no code and no address, only when the pause ends.
SECOND_FACTOR_LOCKED = "SECOND_FACTOR_LOCKED"
#: A single-use set-password link, from an administrator ("Email them a password link") or from the
#: person themselves ("Forgot your password?"). The link never sits in ``params``: it travels sealed on
#: the outbox row (``EmailMessage.sealed``) and reaches :func:`render` as ``secret``.
PASSWORD_LINK = "PASSWORD_LINK"
#: SECURITY NOTICES (2026-10-16): a password was added to an account that had none, and an account's
#: password was changed (by its owner, through a link, or by an administrator). Always sent, no
#: opt-out, to the account's own address; they carry no password and no link, only when it happened
#: and what to do if it was not them.
PASSWORD_ADDED = "PASSWORD_ADDED"
PASSWORD_CHANGED = "PASSWORD_CHANGED"
KINDS = frozenset(
    {
        ACCESS_GRANTED,
        TASK_SUBMITTED,
        TASK_SENT_BACK,
        TASK_APPROVED,
        SECOND_FACTOR_LOCKED,
        PASSWORD_LINK,
        PASSWORD_ADDED,
        PASSWORD_CHANGED,
    }
)
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


def locked_until_words(value: Any) -> str:
    """``2026-10-16T11:06:00+00:00`` as "11:06 UTC on 16 October 2026"; the raw value if unreadable."""
    try:
        moment = datetime.fromisoformat(str(value)).astimezone(UTC)
    except ValueError:
        return str(value or "later today")
    return f"{moment:%H:%M} UTC on {moment.day} {moment:%B %Y}"


def subject_for(kind: str, params: dict[str, Any]) -> str:
    title = str(params.get("taskTitle") or "a task").strip()
    if kind == PASSWORD_LINK:
        if params.get("purpose") == "INVITE":
            return f"Your {PRODUCT_NAME} account is ready"
        return f"Set a new password for the {PRODUCT_NAME}"
    if kind == ACCESS_GRANTED:
        return f"You can now sign in to the {PRODUCT_NAME}"
    if kind == TASK_SUBMITTED:
        return f"“{title}” is waiting for your approval"
    if kind == TASK_SENT_BACK:
        return f"“{title}” has been sent back to you"
    if kind == TASK_APPROVED:
        return f"“{title}” has been approved"
    if kind == SECOND_FACTOR_LOCKED:
        return f"Someone used your {PRODUCT_NAME} password"
    if kind == PASSWORD_ADDED:
        return "A password was added to your account"
    if kind == PASSWORD_CHANGED:
        return "Your password was changed"
    raise ValueError(f"Unknown e-mail kind {kind!r}")


def render(
    kind: str, params: dict[str, Any], *, secret: str | None = None, settings: Settings | None = None
) -> Rendered:
    """Subject, plain text and HTML for one message. ``secret`` is the set-password link, if any."""
    settings = settings or get_settings()
    subject = subject_for(kind, params)
    greeting = _greeting(params.get("recipientName"))
    sign_off = f"— {PRODUCT_NAME}"

    if kind == PASSWORD_LINK:
        if not secret:
            raise ValueError("A password-link message needs its link")
        expires = locked_until_words(params.get("expiresAt"))
        if params.get("purpose") == "INVITE":
            lead = (
                f"An administrator has made you an account on the {PRODUCT_NAME}. "
                "Choose your password with the link below, then sign in with this e-mail address."
            )
            label = "Choose your password"
        elif params.get("requestedBy") == "self":
            lead = (
                f"Somebody asked for a link to set a new password for your {PRODUCT_NAME} account. "
                "Setting it signs you out everywhere you are signed in."
            )
            label = "Set a new password"
        else:
            lead = (
                f"An administrator has issued you a link to set a new password for the {PRODUCT_NAME}. "
                "Setting it signs you out everywhere you are signed in."
            )
            label = "Set a new password"
        rule = f"The link works once and expires at {expires}."
        unasked = "If you did not ask for this, ignore this message; nothing changes until the link is used."
        text = "\n\n".join([greeting, lead, secret, rule, unasked, sign_off]) + "\n"
        body = _html([_e(greeting), _e(lead), _e(rule), _e(unasked)], button=(_e(label), _e(secret)))
        return Rendered(subject, text, body)

    if kind == ACCESS_GRANTED:
        link = app_url("/login", settings)
        lead = (
            f"An administrator has given this address access to the {PRODUCT_NAME}. "
            "Sign in using this e-mail address."
        )
        unasked = "If you were not expecting this, you can ignore this message."
        text = "\n\n".join([greeting, lead, f"Sign in: {link}", unasked, sign_off]) + "\n"
        return Rendered(subject, text, _html([_e(greeting), _e(lead), _e(unasked)], button=("Sign in", _e(link))))

    if kind == SECOND_FACTOR_LOCKED:
        until = locked_until_words(params.get("lockedUntil"))
        lead = (
            f"Someone entered your {PRODUCT_NAME} password and then several wrong verification codes. "
            f"To protect your account, two-step sign-in is paused until {until}."
        )
        act = (
            "If this was not you, change your password now — or ask an administrator to change it — "
            "because whoever did this knows it. If you sign in with Google, Microsoft or Yahoo, change "
            "the password of that account."
        )
        # Only the six-digit pause leaves a way in; a recovery-code pause says nothing it cannot keep.
        later = ["Your recovery codes still work while the pause lasts."] if params.get("codeKind") != "recovery" else []
        link = app_url("/login", settings)
        text = "\n\n".join([greeting, lead, act, *later, sign_off]) + "\n"
        return Rendered(
            subject,
            text,
            _html([_e(greeting), _e(lead), _e(act), *(_e(line) for line in later)], button=("Open the sign-in page", _e(link))),
        )

    if kind in (PASSWORD_ADDED, PASSWORD_CHANGED):
        when = locked_until_words(params.get("changedAt"))
        if kind == PASSWORD_ADDED:
            lead = f"A password was added to your {PRODUCT_NAME} account at {when}. You can now sign in with it."
        else:
            lead = f"The password of your {PRODUCT_NAME} account was changed at {when}."
        act = (
            "If this wasn't you, ask an administrator to sign you out everywhere and reset your "
            "password."
        )
        link = app_url("/login", settings)
        text = "\n\n".join([greeting, lead, act, sign_off]) + "\n"
        return Rendered(
            subject, text, _html([_e(greeting), _e(lead), _e(act)], button=("Open the sign-in page", _e(link)))
        )

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
