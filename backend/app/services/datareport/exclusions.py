from __future__ import annotations

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

MODELS: dict[str, str] = {
    "AppRelease": "Android release catalogue, not collected data.",
    "AppClientVersion": "Which app version each account last used: device telemetry.",
    "AppSetting": "Platform settings (transcription switches, budgets), not collected data.",
    "UserPreference": "Per-account display and e-mail preferences.",
    "Feedback": "Feedback about the app itself, kept on the Feedback page.",
    "FeedbackReport": "Bug reports about the app itself, kept on the Feedback page.",
    "PasswordResetToken": PASSWORD,
    "PasswordResetRequest": PASSWORD,
    "TwoFactorChallenge": TWO_FACTOR,
    "ManagedSecret": SECRET,
    "UserAiCredential": SECRET,
    "SecretTestResult": SECRET,
    "IdempotencyKey": "Request de-duplication cache.",
    "AadhaarOtpThrottle": AADHAAR,
    "AadhaarLogArchive": AADHAAR,
    "AadhaarLegalHold": AADHAAR,
    "RecordAccessToken": TOKEN,
    "RecordAccessTokenRedemption": TOKEN,
    "AccessRoster": "Sign-in admission roster (who may sign in), managed by administrators.",
    "DataAccessGrant": "Access-control grants, managed on the Sharing page.",
    "DataAccessScopeItem": "Access-control grants, managed on the Sharing page.",
    "UsageEvent": "Anonymous request telemetry.",
    "UsageConsentDecision": USAGE,
    "EmailMessage": "Outgoing e-mail queue.",
    "Notification": "In-app notifications to accounts.",
    "WeeklyDigest": "Weekly digest e-mails to accounts.",
    "SearchDocument": "Search index, rebuilt from the records exported here.",
    "SearchIndexQueue": "Search index queue.",
    "SttProviderState": "Speech-to-text provider health.",
    "SttUsage": "Speech-to-text billing log, kept on the Transcription settings page.",
    "SttBudget": "Speech-to-text budget settings.",
    "TranscriptionChunk": "Intermediate pieces of a transcription job; the finished transcript is exported.",
    "TranscriptCache": "Provider response cache keyed by audio hash.",
    "MediaProcessingJob": "Background job queue; each transcript's status and failure reason are exported on the Transcripts sheet.",
    "MediaUploadIntent": "Resumable-upload bookkeeping before a file exists.",
    "MediaWaveform": "Waveform peaks drawn by the audio player.",
    "CrossPortalAccessLog": "Audit log of cross-portal lookups, kept for administrators.",
    "ArtisanDuplicateCandidate": "Duplicate-detection work queue for administrators.",
    "ArtisanPeerScan": "Duplicate-detection scan bookkeeping.",
    "ArtisanMerge": "Merge undo snapshots for administrators; the surviving artisan is exported.",
    "GeoAlias": "Spelling aliases for state and district names (reference data).",
    "CraftCode": "National craft-code register (reference data, the same in every report); each craft's code is exported.",
    "CraftSpelling": "Spelling variants of craft names (reference data).",
    "DataReportPreference": "Each account's remembered Report options.",
    "DataFolderVisit": "When each account last opened a folder.",
    "DataSavedView": "Saved views of the data explorer.",
    "DataBundle": "Download-bundle links created for reports.",
    "DataReportShare": "Share-link and visitor metadata, not report data.",
    "DataReportShareView": "Share-link and visitor metadata, not report data.",
    "DataReportSchedule": "Scheduled-report settings of each account.",
    "DataReportScheduleRun": "Scheduled-report delivery log.",
    "DwDictationDailyUsage": "Daily dictation usage counters (rate limiting).",
    "DwAiVerbDailyUsage": "Daily AI usage counters (rate limiting).",
    "DesignerRoster": "Programme administration (Ministry pages), not part of the data folders.",
    "SanctionOrderImport": "Programme administration import log (Ministry pages).",
    "WorkshopTypeOption": "Programme administration settings (Ministry pages).",
    "RegionalDirectorState": "Programme administration settings (Ministry pages).",
    "MinistryDataExport": "Ministry export jobs (Ministry exports page).",
    "MinistryExportRecipient": "Ministry export recipients (Ministry exports page).",
    "MinistryExportPack": "Ministry export packs (Ministry exports page).",
    "MinistryExportDownload": "Ministry export download log.",
    "AnnualPlanEntry": "Programme planning (Annual plan page); a design workshop's own details are exported.",
}

FIELDS: dict[str, str] = {
    "User.passwordHash": PASSWORD,
    "User.passwordSetAt": PASSWORD,
    "User.mustChangePassword": PASSWORD,
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
