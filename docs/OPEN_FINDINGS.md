# Open findings

Missing features and known defects that users can run into but that no screen talks about. Until
2026-10-10 several of these were explained to users on the screens themselves, in sentences such as
"…is coming soon" or "the repository has no column for who changed it". Text that users see now
says only what happened and what they can do. The gap is recorded here, where the people who can
close it will read it.

Each entry says what is missing, where users meet it, what they see today, and roughly how big the
fix is: **S** means days or less, **M** days to weeks, **L** weeks and several surfaces.

---

## Missing features

### F7. A review queue on Android — M

**What is missing.** The web has a review queue. On Android, the "Review" row opens the record
browser, which is where a reviewer reads a submission and acts on it.

**What users see.** The walkthrough (`ui/WalkthroughSteps.kt`, the "review" step) describes what the
row does. It no longer says that the handset has no queue.

**To close it.** A queue screen on Android backed by the same listing the web queue uses. When it
exists, update the walkthrough step and `BODY_EXTENDED["review"]` in `WalkthroughStepsTest`.

### F25a. Who changed a task's status — S–M

**What is missing.** When an admin approves a task, marks it done for someone, or overrides its
status, nothing stores who did it. After a reload, the status reads exactly as if the assignee had
done it.

**What users see.** The confirmation on Android (`ui/TaskAdminScreen.kt`) says "This marks it done
for them. If you only think it's done, ask them first." The web board
(`components/tasks/AccountabilityBoard.tsx`) shows the status alone.

**To close it.** An actor column, plus a timestamp, on the task's status change. Return it from the
task API, and show "marked done by …" on both clients.

### F25b. A message when a task is sent back — M

**What is missing.** Sending a submitted task back can't carry a reason. The assignee sees only that
the task is unfinished again.

**What users see.** The assignee is e-mailed that the task came back (when mail is on), with no
reason. The confirmation asks the admin to tell them directly: "Let them know why."
(Android) and "{who} won't see a reason here, so let them know why." (web,
`components/tasks/TaskPrimitives.tsx`).

**To close it.** An optional reason field on send-back. Store it with the status change from F25a,
and show it on the assignee's task on both clients.

### F27. Tracing materials differ between the web and Android — S

**What is missing.** The two tracing engines carry different subject lists:

| Client | Materials |
|---|---|
| Web | "Wood & stone carving" as one material |
| Android | "Wood carving" and "Stone carving" as two materials, plus "Metalwork" |

The web has no metalwork material. A trace made on the web can't record which kind of carving was
meant.

**What users see.** The Android picker offers its twelve materials with no note about the web
(`ui/trace/TraceEnginePresets.kt`; `TRACE_SUBJECT_DIVERGENCE_NOTES` is empty, and
`TraceEnginePresetsTest` pins it so). If the web's single carving id reaches Android, the phone
answers "On this phone “Wood & stone carving” is two materials… Choose one of those."

**To close it.** Add the three materials to the web's subject register, or decide which register
wins and give both engines the same one.

### Links, text colour and fonts in rich text — L

**What is missing.** The rich-text fields offer bold, italic, lists and the like. They don't offer
links, text colour or font choices, because the stored document format can't carry them.

**What users see.** The editor offers only what it supports. Its help panel
(`components/richtext/RichTextEditor.tsx`) no longer lists what it doesn't.

### Android trace settings the phone's engine lacks — S

**What is missing.** The Android tracing engine is a different version from the web's, so a few
settings exist on only one side. One of them is the choice between the Zhang–Suen and Guo–Hall
thinning kernels, which both clients leave at the engine's default.

**What users see.** The Android trace panel (`ui/trace/TracePanel.kt`) says "N of M settings aren't
available on this phone… The trace still works." and hides those settings.

**To close it.** Re-vendor the two engines from the same upstream release.

## Known defects

### Rich-text formatting is lost in exports — M

**What happens.** A field formatted in the rich-text editor is stored as a document. These places
read the raw stored form, so users see the stored form instead of the sentence they typed:

- the CSV and Excel exports
- the review panel
- the Android app

**What users see.** The help panel used to advise leaving such fields unformatted. That advice has
been removed along with the admission.

**To close it.** One shared renderer from the stored document to plain text, used by the backend
exports, the review panel and Android.

### A failed media job can show the raw error — S

**What happens.** When a media job (transcription, refinement, measuring) fails with an exception
nobody anticipated, `_handle_job_failure` in `backend/app/services/media_queue.py` stores `str(exc)`,
with secrets redacted, as the job's error. The job panel shows that error. Anticipated failures
carry plain sentences. An unanticipated one can show developer text.

**To close it.** Store a plain sentence for any exception that isn't already a user-facing one
("This file couldn't be processed. Try again."), and write the exception to the log.

### Transcription order on a backend that predates it — S

**What happens.** The transcription-order panel in Settings (web: `ProviderOrderPanel.tsx`; Android:
`ui/ProviderOrderPanel.kt` and `ui/TranscriptionProviders.kt`) reads a backend route. A backend
deployed before that route existed answers 404.

**What users see.** "The transcription order isn't available right now." The panel shows the default
order, and its controls wait until the order loads.

**To close it.** Deploy the current backend everywhere the clients run. The 404 state then never
appears.

---

## How this document is kept true

| What | Check |
|---|---|
| An entry is closed | Delete the entry in the same change that ships the fix. If the fix restores something to a screen, check that the screen's text still says only what happens. |
| A new gap or defect is found | Record it here. Don't explain it on a screen. Text users see says what happened and what they can do, and never what the product lacks. |
| The file paths above | `node docs/tools/check-docs.mjs` fails on a path that no longer exists. |
| The screens still say nothing about these gaps | Search the client sources for the old sentences: `grep -rn "coming soon\|no column for who\|has no separate review queue\|no metalwork" frontend/app frontend/components frontend/lib android/app/src/main`. It should find nothing outside comments. |
