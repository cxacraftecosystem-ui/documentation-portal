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
| Prisma models | **37** |
| Prisma enums | **16** |
| `@@index` declarations | 93 |
| `@@unique` declarations | 14 |

Models: `User`, `AccessRoster`, `AssignedTask`, `Feedback`, `UserPreference`, `AppRelease`, `Craft`, `Location`, `Artisan`, `Workshop`, `WorkshopArtisan`, `WorkshopCraft`, `ProductDocumentation`, `ToolDocumentation`, `ToolArtisan`, `ToolCraft`, `MediaFile`, `MediaProcessingJob`, `Questionnaire`, `QuestionnaireSection`, `QuestionnaireSectionStatus`, `QuestionnaireQuestion`, `QuestionnaireInterview`, `QuestionnaireInterviewArtisan`, `QuestionnaireResponse`, `Process`, `ProcessStep`, `ReviewLog`, `AppSetting`, `WorkshopAssignment`, `ManagedSecret`, `SecretTestResult`, `DataAccessGrant`, `DataAccessScopeItem`, `EntryComment`, `RecordRevision`, `UserAiCredential`.

Enums: `UserRole`, `AuthProvider`, `AccessStatus`, `RecordStatus`, `WorkshopType`, `MediaType`, `ProductType`, `MarketDemand`, `MakerType`, `TraditionType`, `ReviewRecordType`, `MediaProcessingJobType`, `MediaProcessingJobStatus`, `ProcessStepType`, `DataAccessTier`, `DataAccessStatus`.

## API surface

**157 operations** in the working tree — 74 GET, 46 POST, 18 DELETE,
11 PATCH, 8 PUT. 2 of them (`/health`, `/health/ready`) are declared
on the app rather than on a router; the rest are spread across `backend/app/api/routes/`:

| Route module | Operations |
|---|---|
| `media.py` | 20 |
| `workshops.py` | 20 |
| `data_access.py` | 12 |
| `tasks.py` | 11 |
| `tools.py` | 8 |
| `artisans.py` | 7 |
| `access_roster.py` | 5 |
| `ai_keys.py` | 5 |
| `crafts.py` | 5 |
| `data_browser.py` | 5 |
| `datasets.py` | 5 |
| `processes.py` | 5 |
| `products.py` | 5 |
| `review.py` | 5 |
| `secrets.py` | 5 |
| `settings.py` | 5 |
| `users.py` | 5 |
| `auth.py` | 4 |
| `app_release.py` | 3 |
| `export.py` | 3 |
| `feedback.py` | 3 |
| `map_points.py` | 2 |
| `preferences.py` | 2 |
| `reference.py` | 2 |
| `dashboard.py` | 1 |
| `public.py` | 1 |
| `search.py` | 1 |
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

Source of truth: `ROLE_RANK` in `backend/app/core/deps.py`, mirrored in
`frontend/lib/permissions.ts`. The two are checked against each other by this script.

## Transcription provider chain

Default order: 1. `elevenlabs`  2. `deepgram`  3. `whisper` — `DEFAULT_STT_PROVIDER_ORDER`
in `backend/app/services/app_settings.py`. A master admin can reorder it at runtime; a provider with
no key is skipped wherever it sits.

## Automated tests

| Surface | Files | Cases | Runner |
|---|---|---|---|
| Backend unit (`backend/tests/`) | 59 | 955 `def test_` | `python -m pytest -q` from `backend/` |
| Web end-to-end (`frontend/e2e/`) | 40 | 599 `test(` | Playwright, `frontend/playwright.config.ts` |
| Android unit | present | — | `:app:testDebugUnitTest` reports NO-SOURCE |
| Android instrumented | **none** — the `src/androidTest` source set does not exist | — | not run in CI |

The backend case count is `def test_` occurrences; pytest reports a larger number because
parametrised cases expand. Neither the backend suite nor the e2e suite is a CI gate today — see
[CI.md](CI.md) and [QA_AUDIT.md](QA_AUDIT.md).

## Code volume

| Area | Tracked files | Tracked lines | Tree files | Tree lines |
|---|---|---|---|---|
| `backend/app` | 118 | 43,000 | 118 | 43,000 |
| `frontend/app` | 41 | 15,996 | 41 | 15,996 |
| `frontend/components` | 173 | 51,957 | 174 | 52,135 |
| `frontend/lib` | 72 | 27,328 | 72 | 27,328 |
| `android/app/src/main/java` | 80 | 72,289 | 81 | 72,466 |

Two columns because the two numbers get quoted interchangeably and disagree by however much work is
uncommitted. **Tracked** is `git ls-files`, which is the figure to use in a write-up — it is
reproducible from a clone. **Tree** includes files not yet committed, which is the figure to use when
reasoning about what is running locally. Neither is wrong; they answer different questions.
