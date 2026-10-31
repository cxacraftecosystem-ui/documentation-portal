<!-- GENERATED FILE — do not edit by hand.
     Regenerate with:  node docs/tools/check-docs.mjs --write
     Every count in this documentation set lives here and nowhere else, so that a migration or a new
     route makes exactly one file wrong and a scripted run makes it right again. -->

# Repository facts (generated)

Counts derived from the working tree by `docs/tools/check-docs.mjs`. **Do not restate these numbers
in prose** — link here instead. If a document quotes a count, that count is already rotting.

These figures describe the **working tree**, which is not the same thing as production. The deployed
API lags the tree by however many commits have not been deployed; see
[the deployed-versus-tree note](#deployed-versus-tree).

## Data model

| | Count |
|---|---|
| Prisma models | **89** |
| Prisma enums | **41** |
| `@@index` declarations | 181 |
| `@@unique` declarations | 36 |

Models: `User`, `PasswordResetToken`, `PasswordResetRequest`, `AccessRoster`, `AssignedTask`, `Feedback`, `UserPreference`, `AppRelease`, `AppClientVersion`, `CraftCategory`, `CraftSubCategory`, `Craft`, `CraftCode`, `CraftSpelling`, `ArtisanSharingConsent`, `CrossPortalArtisanLink`, `ArtisanCard`, `ArtisanCardEvent`, `CrossPortalAccessLog`, `GeoAlias`, `Location`, `Artisan`, `Workshop`, `WorkshopArtisan`, `WorkshopCraft`, `ProductDocumentation`, `ToolDocumentation`, `ToolArtisan`, `ToolCraft`, `MediaFile`, `TranscriptInsight`, `MediaProcessingJob`, `Questionnaire`, `QuestionnaireSection`, `QuestionnaireSectionStatus`, `QuestionnaireQuestion`, `QuestionnaireInterview`, `QuestionnaireInterviewArtisan`, `QuestionnaireInterviewResearcher`, `QuestionnaireRecording`, `QuestionnaireRecordingTarget`, `QuestionnaireRecordingSubject`, `QuestionnaireRecordingEvent`, `QuestionnaireAnswer`, `QuestionnaireAnswerRevision`, `QuestionnaireTakeDraft`, `QuestionnaireRespondent`, `QuestionnaireRecordingRespondent`, `QuestionnaireBinding`, `QuestionnaireTypeDefault`, `MediaUploadIntent`, `Consent`, `QuestionnaireResponse`, `QuestionnaireAnswerDraft`, `Process`, `ProcessStep`, `ReviewLog`, `AppSetting`, `ArtisanMerge`, `WorkshopAssignment`, `ManagedSecret`, `SecretTestResult`, `DataAccessGrant`, `DataAccessScopeItem`, `EntryComment`, `RecordRevision`, `UserAiCredential`, `EmailMessage`, `TwoFactorChallenge`, `IdempotencyKey`, `Notification`, `WeeklyDigest`, `SearchDocument`, `SearchIndexQueue`, `ArtisanDuplicateCandidate`, `SttProviderState`, `SttUsage`, `SttBudget`, `TranscriptionChunk`, `TranscriptCache`, `RecordingConsent`, `TranscriptVersion`, `TranscriptTurn`, `TranscriptTurnEdit`, `TranscriptSpeaker`, `TranscriptSpend`, `TranscriptGlossaryTerm`, `TranscriptGlossarySighting`, `TranscriptDraftCitation`.

Enums: `UserRole`, `AuthProvider`, `AccessStatus`, `RecordStatus`, `WorkshopType`, `MediaType`, `ProductType`, `MarketDemand`, `MakerType`, `TraditionType`, `ReviewRecordType`, `MediaProcessingJobType`, `ConsentState`, `ConsentSubjectType`, `ConsentPurpose`, `ConsentBasis`, `SessionState`, `SessionPurpose`, `SessionOrigin`, `ResearcherRole`, `SubjectRole`, `RepeatReason`, `TakeFlag`, `CapturedAtSource`, `RecordingFiling`, `RecordingProvenance`, `AnswerOrigin`, `AnswerAttribution`, `SectionStatus`, `RespondentKind`, `SessionSource`, `MediaProcessingJobStatus`, `ProcessStepType`, `DataAccessTier`, `DataAccessStatus`, `CredentialLinkPurpose`, `GiStatus`, `CraftStatus`, `DuplicateKind`, `DuplicateStatus`, `DuplicateSource`.

## API surface

**335 operations** in the working tree — 152 GET, 114 POST, 27 DELETE,
20 PATCH, 22 PUT. 2 of them (`/health`, `/health/ready`) are declared
on the app rather than on a router; the rest are spread across `backend/app/api/routes/`:

| Route module | Operations |
|---|---|
| `questionnaire_v2.py` | 52 |
| `media.py` | 21 |
| `workshops.py` | 21 |
| `transcripts.py` | 18 |
| `data_access.py` | 13 |
| `tasks.py` | 13 |
| `auth.py` | 11 |
| `cross_portal.py` | 11 |
| `review.py` | 10 |
| `crafts.py` | 9 |
| `artisans.py` | 8 |
| `tools.py` | 8 |
| `users.py` | 8 |
| `craft_categories.py` | 7 |
| `notifications.py` | 7 |
| `two_factor.py` | 7 |
| `artisan_cards.py` | 6 |
| `map_coverage.py` | 6 |
| `access_roster.py` | 5 |
| `ai_keys.py` | 5 |
| `answer_drafts.py` | 5 |
| `artisan_duplicates.py` | 5 |
| `artisan_merge.py` | 5 |
| `data_browser.py` | 5 |
| `datasets.py` | 5 |
| `processes.py` | 5 |
| `products.py` | 5 |
| `secrets.py` | 5 |
| `settings.py` | 5 |
| `craft_codes.py` | 4 |
| `preferences.py` | 4 |
| `recording_consent.py` | 4 |
| `transcript_insights.py` | 4 |
| `app_release.py` | 3 |
| `export.py` | 3 |
| `feedback.py` | 3 |
| `reference.py` | 3 |
| `search.py` | 3 |
| `transcription_admin.py` | 3 |
| `cross_portal_search.py` | 2 |
| `map_points.py` | 2 |
| `app_versions.py` | 1 |
| `completeness.py` | 1 |
| `dashboard.py` | 1 |
| `public.py` | 1 |
| `questionnaire.py` | 0 |

### Deployed versus tree

The number above counts decorators in this checkout. The number that matters operationally is what
the running API actually serves, which you read from the deployed schema rather than from the source:

```bash
curl -s https://d2b34i3e92al6i.cloudfront.net/openapi.json \
  | python -c "import json,sys,collections; d=json.load(sys.stdin); \
      c=collections.Counter(m for p in d['paths'].values() for m in p if m in ('get','post','put','patch','delete')); \
      print(sum(c.values()), dict(c))"
```

A gap between the two is normal and means "not deployed yet". A gap in the other direction means
someone deployed from a branch.

> Note: that command only works while `BACKEND_EXPOSE_DOCS` is true on the deployment. The default
> is now **false** — see [SECURITY.md](SECURITY.md). Once it is false in production, count from a
> checkout of the deployed commit instead.

## Role ladder

- `CROWDSOURCE_VOLUNTEER` — rank **10**
- `FIELD_CONTRIBUTOR` — rank **20**
- `RESEARCHER` — rank **30**
- `PROFESSOR` — rank **40**
- `ADMIN` — rank **50**
- `MASTER_ADMIN` — rank **60**

Source of truth: `ROLE_RANK` in `backend/app/core/roles.py`, mirrored in
`frontend/lib/permissions.ts`. The two are checked against each other by this script.

## Transcription provider chain

Default order: 1. `elevenlabs`  2. `deepgram`  3. `whisper`  4. `muse` — `DEFAULT_STT_PROVIDER_ORDER`
in `backend/app/services/app_settings.py`. A master admin can reorder it at runtime; a provider with
no key is skipped wherever it sits.

## Automated tests

| Surface | Files | Cases | Runner |
|---|---|---|---|
| Backend unit (`backend/tests/`) | 180 | 2326 `def test_` | `python -m pytest -q` from `backend/` |
| Web end-to-end (`frontend/e2e/`) | 130 | 1362 `test(` | Playwright, `frontend/playwright.config.ts` |
| Android unit (`android/app/src/test/`) | 165 | 1366 `@Test` | `./gradlew :app:testDebugUnitTest` from `android/` |
| Android instrumented | 11 | 41 `@Test` | not run in CI |

The backend case count is `def test_` occurrences; pytest reports a larger number because
parametrised cases expand. Neither the backend suite nor the e2e suite is a CI gate today — see
[CI.md](CI.md) and [QA_AUDIT.md](QA_AUDIT.md).

## Code volume

| Area | Tracked files | Tracked lines | Tree files | Tree lines |
|---|---|---|---|---|
| `backend/app` | 266 | 89,468 | 266 | 89,468 |
| `frontend/app` | 81 | 22,495 | 81 | 22,495 |
| `frontend/components` | 308 | 77,336 | 308 | 77,336 |
| `frontend/lib` | 167 | 45,764 | 167 | 45,764 |
| `android/app/src/main/java` | 255 | 118,465 | 255 | 118,465 |

Two columns because the two numbers get quoted interchangeably and disagree by however much work is
uncommitted. **Tracked** is `git ls-files`, which is the figure to use in a write-up — it is
reproducible from a clone. **Tree** includes files not yet committed, which is the figure to use when
reasoning about what is running locally. Neither is wrong; they answer different questions.
