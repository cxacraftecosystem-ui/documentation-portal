# Questionnaire v2 on Android

The Android half of the questionnaire redesign (design and build plan: `C:/dev/IIT/docs/questionnaire/`). It switches on when the server's `GET /api/questionnaire/config` answers `v2Enabled: true`. Until then the old questionnaire screen is unchanged, and old builds keep working through the server's shims.

## Where things are

| Part | File |
|---|---|
| Recording service (foreground, `microphone`; Pause and Stop in the notification) | `android/app/src/main/java/com/fieldrepository/app/recording/RecordingService.kt` |
| AAC ADTS recorder, remux to `.m4a`, kill recovery (P13) | `recording/AdtsRecorder.kt`, `recording/Remuxer.kt`, `recording/RecoveryScanner.kt`, `recording/RecordingDraft.kt` |
| One ordered outbox unit per session | `android/app/src/main/java/com/fieldrepository/app/data/SessionOutbox.kt`, `data/QuestionnaireTransport.kt` |
| Old-format entries drained first, then the v2 queue | `data/QuestionnaireV2.kt` (`QuestionnaireSync`), `data/OutboxSyncWorker.kt` |
| Offline coverage (P2 from the snapshot plus the outbox overlay) | `data/CoverageCache.kt` |
| Join by QR (offline: live camera or a picked image) or code (online) | `data/JoinQr.kt`, `ui/questionnaire/JoinScanner.kt`, `ui/questionnaire/LiveQrScanner.kt` |
| Generated titles and names (anyascii transliteration, the shared golden table) | `data/Naming.kt` |
| Shared enums and strings, generated from `shared/` by `scripts/generate_shared.py` | `data/SharedEnums.kt`, `res/values/strings_questionnaire.xml` |
| Clock headers (`X-Client-Time`, `X-App-Version`) | `data/ClientClock.kt` |
| Typed API client and models | `data/QuestionnaireApi.kt`, `data/QuestionnaireV2Models.kt` |
| Copy: the shared table's words verbatim, plus a short pinned Android-only list | `data/QuestionnaireCopy.kt` |
| On-device tests (app switch, locked screen, Back, a call, a kill mid-take) | `app/src/androidTest/`, run by `android/scripts/recording-device-tests.sh` |
| Screens | `android/app/src/main/java/com/fieldrepository/app/ui/questionnaire/` |

## Rules the code keeps

- No screen asks for a title. The start sheet previews the generated title, and the server stores the real one.
- The app never names an uploaded clip. The server mints the name at presign, and downloads use its `downloadName`.
- Consent is asked inline and never blocks recording. Transcription shows "Transcription paused", and no control can start it.
- Every create carries one `Idempotency-Key` for all its retries, and its body is frozen when it is queued.
- "Answered by" is kept whenever it is confirmed: in the take's draft, in the queued body, or refiled online with `PATCH /recordings/{id}` once the take has landed.
- A failed clip blocks nothing. A missing file reads "The file for Section D (7 Jul) is missing on this device." and is never ticked.
