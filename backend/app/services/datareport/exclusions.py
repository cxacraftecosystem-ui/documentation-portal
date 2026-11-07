from __future__ import annotations

import json
from pathlib import Path

SECRET = "Secret or credential material: never leaves the server."
TWO_FACTOR = "Two-factor authentication material: never exported."
PASSWORD = "Password material: never exported."
AADHAAR = "Raw or derived Aadhaar identity data: never exported (UIDAI rules)."
TOKEN = "Access token, link secret or hash: never exported."
STORAGE = "Storage location inside the bucket; the report links to the file through a permission-checked portal link instead."
PLAYER = "Word timings and confidence offsets used by the transcript player; the turn text, times and status are exported."
ACCOUNT = "Account security and permission settings, managed on the Users page, not collected data."
USAGE = "Usage-analytics consent bookkeeping, not collected data."
JOIN = "Bookkeeping of a link row; the link itself is exported as the two names it joins."
DUPLICATE = "Shown in full on another sheet of this report."

MODELS: dict[str, str] = json.loads((Path(__file__).resolve().parent / "excluded_models.json").read_text(encoding="utf-8"))

FIELDS: dict[str, str] = {
    "User.passwordHash": PASSWORD,
    "User.passwordSetAt": PASSWORD,
    "User.mustChangePassword": PASSWORD,
    "User.passwordIssuerKnown": PASSWORD,
    "User.firstPasswordCodeAttempts": PASSWORD,
    "User.firstPasswordCodeWindowStartedAt": PASSWORD,
    "User.sessionsValidFrom": ACCOUNT,
    "User.avatarUrl": STORAGE,
    "User.canManageQuestionnaire": ACCOUNT,
    "User.canManageCrafts": ACCOUNT,
    "User.canManageWorkshops": ACCOUNT,
    "User.canReview": ACCOUNT,
    "User.canViewProvenance": ACCOUNT,
    "User.canDownloadDataset": ACCOUNT,
    "User.usageConsent": USAGE,
    "User.usageConsentAt": USAGE,
    "User.usageConsentBasis": USAGE,
    "User.usageConsentVersion": USAGE,
    "Artisan.aadhaarFingerprint": AADHAAR,
    "Artisan.aadhaarLast4": AADHAAR,
    "Artisan.aadhaarVerifyLast4": AADHAAR,
    "Artisan.aadhaarVerifyName": AADHAAR,
    "Artisan.aadhaarVerifyBirthYear": AADHAAR,
    "Artisan.aadhaarVerifyDob": AADHAAR,
    "Artisan.aadhaarVerifyGender": AADHAAR,
    "Artisan.aadhaarVerifyTxn": AADHAAR,
    "Artisan.aadhaarVerifySignedAt": AADHAAR,
    "MediaFile.bucket": STORAGE,
    "MediaFile.objectKey": STORAGE,
    "MediaFile.url": STORAGE,
    "MediaFile.cleanedObjectKey": STORAGE,
    "AppRelease.objectKey": STORAGE,
    "MediaFile.transcriptSegments": "Provider timings kept for subtitles; the same lines are exported as transcript segments.",
    "TranscriptTurn.words": PLAYER,
    "TranscriptTurn.lowConfidence": PLAYER,
    "TranscriptSpend.keyRef": SECRET,
    "DwReportDelivery.tokenHash": TOKEN,
    "DwReportDelivery.storageKey": STORAGE,
    "DwReportExport.storageKey": STORAGE,
    "DesignWorkshopViewer.tokenId": TOKEN,
    "DesignWorkshopAccessRequest.tokenId": TOKEN,
    "DesignWorkshopAccessRequest.scannedCode": TOKEN,
    "DesignWorkshopProvisionalMember.viaTokenId": TOKEN,
    "DwRoundSnapshot.stages": DUPLICATE,
    "ArtisanCard.keyVersion": SECRET,
    "DwStageEntry.searchText": "Search index text built from the stage entry fields, which are exported.",
}

USER_FIELDS = (
    "id",
    "name",
    "email",
    "role",
    "authProvider",
    "firstLoginAt",
    "lastLoginAt",
    "createdAt",
    "updatedAt",
)

_SENSITIVE = ("password", "secret", "token", "hash", "otp", "fingerprint", "recoverycode", "keyref", "valueenc")
SENSITIVE_ALLOWED = frozenset(
    {
        "MediaFile.contentSha256",
        "TranscriptInsight.sourceHash",
        "DwReportExport.checksumSha256",
        "MediaFile.checksum",
        "TranscriptVersion.promptVersion",
        "TranscriptInsight.promptVersion",
        "QuestionnaireAnswerRevision.sourceTranscriptHash",
        "DwReportExport.inputFingerprint",
        "SanctionOrder.sanctionOrderKey",
        "QuestionnaireInterview.artisanSetKey",
        "TranscriptVersion.keytermCount",
        "TranscriptSpend.inputTokens",
        "TranscriptSpend.outputTokens",
        "Artisan.aadhaarVerifyStatus",
        "Artisan.aadhaarVerifyMethod",
        "Artisan.aadhaarVerifiedAt",
        "Artisan.aadhaarVerifyNameMatch",
        "Artisan.aadhaarVerifyById",
        "Artisan.aadhaarVerifyDecidedById",
        "Artisan.aadhaarVerifyDecidedAt",
        "Artisan.aadhaarNotShared",
    }
)


def excluded(model: str, name: str | None = None) -> str | None:
    if model in MODELS:
        return MODELS[model]
    if name is None:
        return None
    if model == "User" and name not in USER_FIELDS:
        return FIELDS.get(f"User.{name}") or (TWO_FACTOR if name.startswith("twoFactor") else AADHAAR if name.startswith("aadhaar") else ACCOUNT)
    return FIELDS.get(f"{model}.{name}")


def looks_sensitive(model: str, name: str) -> bool:
    if f"{model}.{name}" in SENSITIVE_ALLOWED:
        return False
    low = name.lower()
    return any(part in low for part in _SENSITIVE) or low.startswith("aadhaar")
