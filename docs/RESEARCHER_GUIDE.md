# The researcher's guide

**Who this is for:** the person doing the documentation — a craft scholar, a field researcher, a
student on a workshop. Not an engineer. Nothing below assumes you can read code, and nothing below
asks you to run a command.

**What this covers:** getting an account, installing the app, what each screen is *for*, what happens
to your material after you press Save, how to work where there is no signal, how to get your data
back out, and what to do when something goes wrong in the field.

**What it does not cover:** the step-by-step order of the ten documentation screens. That is
[WALKTHROUGH.md](WALKTHROUGH.md), which is also available inside the app at **Walkthrough**
(`/guide`) with each step linking to the screen it describes. Read that one first if you are about to
go into the field; read this one to understand the system you are working inside.

---

## 1. What this repository is, in one page

It is a permanent, citable archive of craft practice: the people, the objects, the tools, the
processes, and the interviews — with the photographs, video and audio that evidence them.

Three design decisions shape everything you will experience:

1. **The original file is the artifact.** Photographs and video go up **unchanged**. Nothing is
   re-compressed, nothing is resized, and the EXIF metadata your camera wrote is preserved. This is
   why uploads take a while on a bad connection, and it is not negotiable — a re-encoded photograph
   is a different object from the one you took.
2. **Everything is reviewed.** What you submit is *Pending* until somebody senior to you approves it.
   That is not distrust; it is what makes the dataset citable, and it means you are never the last
   check on your own work.
3. **Nothing you capture should be lost to a bad network.** Uploads start while you are still typing,
   resume when they fail, and a save with no signal at all is queued on the device and sent later.
   Section 6 is the whole story, and it is worth reading before your first field trip rather than
   after.

Live web app and API: **`https://d2b34i3e92al6i.cloudfront.net/api/`** (AWS, over HTTPS, reachable on
IPv6-only mobile data). The Android app and the web app talk to the same one, so a record made on a
phone appears on the laptop and vice versa. Nothing has to run on your own machine.

---

## 2. Getting an account

Two routes in, and which one you get depends on how your project is set up.

**Google sign-in** — tap *Continue with Google*. This works immediately and creates your account on
first use, but it creates it at the **lowest tier**, Crowdsource Volunteer. Somebody with admin
rights then raises you to Field Contributor, Researcher or Professor. Until they do, you can upload
media, answer questions in interviews somebody else opened, and comment — but you cannot start a new
artisan record. If your first hour with the app consists of "where is the New Artisan button", this
is why: ask to be promoted.

**Email and password** — an admin creates the account on the Users screen and gives you the
credentials. There is no public sign-up for email accounts.

If the Google button is missing, or Google returns a 403, the project's Google Cloud configuration is
incomplete — see §11.3. Email and password always works regardless.

---

## 3. Installing the Android app

1. **First install** is side-loaded: somebody shares the signed release APK with you (Drive,
   WhatsApp, email, USB).
2. **Allow the install.** Tap the APK; Android says *"your phone is not allowed to install unknown
   apps from this source"* → **Settings** → turn on **Allow from this source** → back → **Install**.
   (The exact path varies: *Settings → Apps → Special access → Install unknown apps*.)
3. **First launch** asks for **Camera, Microphone, Location** and **Media** permissions. Accept all
   four — capture and GPS tagging do not work without them.
4. **After that, updates are automatic.** The master admin publishes a new signed release from inside
   the app, and every device picks it up on next launch. You only side-load once.

Requirements: **Android 8.0 (API 26) or newer**, and an internet connection at some point — not
necessarily at the moment you are recording (§6).

The web app needs nothing installed. Open the URL, sign in.

---

## 4. What the six roles actually mean

Roles are a ladder. Each rung includes everything below it.

| Role | In practice |
|---|---|
| **Crowdsource Volunteer** | The default for a new Google sign-in. You can **add to** the archive — upload media, answer questions in an interview somebody else opened, comment on records — but you cannot **start** a new artisan, product, tool or process. |
| **Field Contributor** | The same, plus you can review a volunteer's submissions. **Still cannot create records** — this surprises people, and it is deliberate. |
| **Researcher** | The working tier. Create artisans, products, tools, processes and interviews; edit your own; fill in gaps on other people's. This is the tier most field staff should hold. |
| **Professor** | Everything a researcher does, plus: manage the craft and workshop vocabularies, edit the questionnaire itself, download the full dataset, review anyone below you, and edit records created by anyone below you. |
| **Admin** | Plus: create and delete user accounts, **delete records**, grant workshop access, assign tasks, and approve late submissions. |
| **Master Admin** | Plus: provider API keys, repository settings, publishing app updates. One account. |

Two rules that catch people out:

- **You can only review work by someone ranked strictly below you.** Two professors cannot approve
  each other; only the master admin reviews everyone. If a record you expected to see is not in your
  review queue, its author probably outranks you or matches you.
- **Only admins delete.** Everyone else's mistake is fixed by editing or by rejecting, not removing.
  The exception: you may always delete **media you uploaded yourself**, from the record's edit
  screen, without holding any delete rights.

The complete matrix, if you need to settle an argument, is [PERMISSIONS.md](PERMISSIONS.md).

---

## 5. Working inside a workshop

**Every record belongs to a workshop.** The workshop is the container — a documentation event at a
place, between two dates — and products, tools, processes and interviews all carry one. The Data
Browser opens filed *by workshop*, and exports are usually taken per workshop.

Practical consequences:

- **Create the workshop before you leave.** Getting it right once means the create forms preselect it
  and you never pick it again.
- **You may need access to it.** A workshop with an assigned roster is *curated*: you must hold a
  granted assignment at **Contribute** level or above to add records to it. If you do not, the form
  will tell you and point you at **Workshop access → Request**. An admin approves it. A workshop with
  no roster at all is open to anyone qualified, so this only bites on workshops somebody has
  deliberately curated.
- **Records made outside the workshop's dates are flagged.** They save, but they are marked
  *out-of-window*, pinned to Pending, and **only an admin can approve them** — a professor cannot,
  and neither can you, even if you would normally be allowed to approve your own work. Moving the
  record to a different workshop does not clear the flag. This is not a punishment; it is so that a
  dataset labelled "recorded at the Kutch workshop, 3–7 March" means that.

---

## 6. Working where there is no signal

This is the section to read twice.

### 6.1 What happens while you are typing

The moment you attach a photograph, **the upload starts** — not when you press Save. By the time you
have finished typing the artisan's details, the files are usually already in storage. The card tells
you: *"All uploaded ✓ — ready to save"*.

So a save that looks instant usually is. A save that spins is waiting for bytes that started moving
minutes ago, not for bytes that started when you pressed the button.

### 6.2 What happens when the connection is bad but alive

- Large files are **split into parts** and each part is retried on its own; a failure near the end of
  a 400 MB video does not restart the video.
- Long audio and video recorded on Android is **split into `PART_1`, `PART_2`, …** before upload, so
  each piece uploads reliably and each audio piece can be transcribed.
- A **slow** upload is allowed to be slow. It is only abandoned if no bytes move at all for a minute.
- Three files upload at a time; more than that starves each of them on a weak uplink.

### 6.3 What happens when there is no connection at all

The save goes into an **outbox** on the device — the record *and its photographs* — and is sent when
the network returns. On the web a banner names every queued entry, says plainly that they are sitting
in this browser, and offers **Sync now** for the case where the phone claims to be online but nothing
actually routes (hotel and airport Wi-Fi).

Four things to know about the outbox:

1. **The attachments are the part that cannot be recreated.** By the time signal returns the artisan
   has gone home. That is why the files are stored and not just the form.
2. **A queued entry lives in the browser or app that made it.** It is not on the server. Do not clear
   the app's data, and do not assume a colleague can see it.
3. **A replay that is interrupted resumes where it stopped.** It will not create the record twice.
4. **A conflict is shown, not swallowed.** If the server refuses the record because it clashes with
   an existing one — most often a duplicate Aadhaar number, or an interview that already exists for
   that exact set of artisans — the entry stays in the outbox with the reason on it, and you resolve
   it. Nothing is deleted behind your back.

**Before you leave a signal area, open the app and let the outbox drain.** The banner tells you when
it is empty.

---

## 7. Recording people: identity and privacy

The artisan record carries personal data, and two fields deserve explaining because they are
unusual.

**Aadhaar number.** Where it is collected, it is the **deduplication key** — it is what stops the same
person being entered twice under two spellings of their name across two workshops. It is validated
(a mistyped digit is caught before it is stored) and stored as the bare twelve digits.

Everywhere the data is *shared* — the Data Browser, the `.xlsx` report, CSV exports — it is shown
**masked**, as `XXXX XXXX 9012`: the last four digits only, which is enough to confirm you have the
right person and not enough to be a usable identifier. If you are shown a mask on an edit form and you
save without touching the field, the mask is recognised and the real number is left alone. You do not
have to retype it.

**Artisan Pehchan Card** (the PM Vishwakarma ID) is an ordinary government reference number, required
only when the artisan says they hold one.

**What you should know about the rest.** Names, phone numbers, addresses, GPS coordinates, interview
recordings and transcripts are stored **unencrypted at the column level**, and **media files are
readable by anyone who has the URL** — there is no login check on the file itself. Treat a media link
as public. Do not paste one into a public issue tracker, a shared document, or a message thread
outside the project. This is a known open risk and is tracked as P0 in [SECURITY.md](SECURITY.md).

**Consent is yours to obtain.** Nothing in the software asks for it or records that you did.

---

## 8. Location: two different questions

The location control asks for two things that look like one, and conflating them corrupted the
dataset once already.

- **Where the device was** — the GPS fix, captured automatically. This is *provenance*. On the live
  database, every artisan with a location sits within a few hundred metres of one point in Kharagpur,
  West Bengal, while the places their researchers typed are Bagru, Balotra, Kutch, Rudraprayag,
  Ballupur, Sanganer and Kappaladoddi. The coordinates were never wrong — they are real fixes **of
  the desk the record was typed at**, which is completely reasonable behaviour that the form had no
  way to express.
- **Where the artisan is** — state, district, village, pincode. This is a *statement by you*. A map or
  a lookup may offer you a value; only you can confirm one.

Fill in the second group. The first fills itself. If they disagree wildly the form will say so — that
is information, not an error, and usually means exactly what it did above.

> This part of the app is being reworked as this guide is written; the wording on screen may differ
> from the wording here. The distinction itself is settled.

---

## 9. The questionnaire

- **There is one interview per exact set of artisans.** If an interview already exists for the people
  in front of you, saving adds your answers to it — it never creates a second one. This is what stops
  five researchers producing five half-filled interviews with the same person.
- **Record, don't type.** Each question has a *"Record this question"* control. The audio is uploaded
  and transcribed on the server automatically, so you get the artisan's own voice *and* searchable
  text without typing during the conversation. Answer in the artisan's language; the system handles
  Hindi/English code-switching, and transcripts can be refined into clean interviewer/interviewee
  dialogue and translated.
- **Transcription is not instant.** It is queued, and the queue deliberately waits for the server to
  be idle. A transcript that has not appeared after an hour is normal; after a day, tell an admin.
- **Answer only what was actually asked.** Empty questions stay open for whoever continues the
  interview later.
- **A question somebody else answered is theirs.** You cannot overwrite it; an admin can.
- **Use "Check completion"** to see the artisans × sections matrix and find the gaps before you leave.

---

## 10. Getting the data back out

Dataset download is a **granted permission** — Professor and above by default, or an explicit grant.
If you do not have it, the Data Browser shows a restricted notice and **Search** is your tool instead.

With it, **View Data** (`/data`) presents the whole repository as a browsable folder tree, filed three
ways: **by workshop** (the default), **by uploader**, and **by media type**. From any folder you can:

| Take | You get |
|---|---|
| **Preview** | images, audio playback, and transcripts rendered as formatted text rather than raw markup |
| **Zip download** | the folder's files, filtered by any combination of *text, images, videos, audios, transcripts, documents, other*; assembled in your browser |
| **`.xlsx` report** | a **14-sheet workbook** of the whole subtree |

The workbook's sheets: *All records*, then one each for *Workshop*, *Craft*, *Artisan*, *Product*,
*Process*, *Process steps*, *Tool*, *Interview*, then *Questionnaire answers*, *Transcripts*, and
media indexed three ways — *by hierarchy*, *by uploader*, *by type*. Transcripts are written as Excel
rich text, so formatting survives rather than arriving as a page of asterisks.

Audio files are converted to `.mp4` (AAC) on download so they play everywhere, falling back to the
original if conversion fails. There are also two direct CSV exports, products and tools.

**Sharing with a colleague** who is not an admin: on **Sharing** (`/sharing`) you grant another
researcher access to *your* records at one of three levels — **Download**, **Comment**, or **Edit** —
either all your data or a chosen subset. They can also request it and you decide. Every edit made
under a shared grant is recorded with the old and new value and who made the change, so a shared
record is never quietly rewritten.

---

## 11. When something goes wrong

### 11.1 In the field

| Symptom | What it means | What to do |
|---|---|---|
| Save spins for a long time | Bytes are still moving on a slow link | Leave it. It is not stuck unless nothing moves for a minute. |
| *"Upload failed"* on one tile | That one file's transfer died | Press **Retry** on the tile. The others are unaffected. |
| A banner says entries are queued | You saved with no connection | Normal. Get signal and let it drain, or press **Sync now**. |
| The form will not let you pick a workshop | You have no granted access to it | **Workshop access → Request**, then ask an admin to approve. |
| The artisan dropdown is empty on a product form | You have not chosen a craft yet | Pick the linked craft first; the artisan list is filtered by it. |
| *"Only the original contributor or an admin can change…"* | You tried to alter a field somebody else filled in | You can fill **empty** fields on anyone's record, never overwrite a filled one. Ask the author, or an admin. |
| Duplicate-Aadhaar error | This person is already in the archive | Find the existing record and add to it. That is the field working. |

### 11.2 Accounts and access

| Symptom | Almost always |
|---|---|
| No **New Artisan** button anywhere | You are a Crowdsource Volunteer or Field Contributor. Ask to be promoted to Researcher. |
| **View Data** shows a padlock | You do not hold dataset-download. Use Search, or ask for the grant. |
| Suddenly signed out | The token lasts 7 days and then expires. Sign in again; nothing is lost. |
| A colleague cannot see your records | Cross-researcher access is per-owner. Grant it on **Sharing**. |

### 11.3 Google sign-in (a project-owner task, once)

Until the project's Google Cloud OAuth clients list the app's identifiers, Google sign-in returns 403
or *invalid token*. Email and password works throughout.

- **Android OAuth client** — needs package name `com.fieldrepository.app` and the signing certificate
  **SHA-1** for the build being distributed. Register the **release** keystore's SHA-1 for the APK
  researchers actually install; a debug fingerprint only covers a locally built debug APK.
- **Web OAuth client** — needs the web origin (the Vercel URL, and `http://localhost:3000` for local
  testing) under *Authorized JavaScript origins*.

> **Unverified:** an earlier version of this guide published a specific debug SHA-1. It has been
> removed rather than repeated — it described one developer's local debug keystore, not the release
> key that signs distributed builds, and pasting it into the console would not make sign-in work for
> anybody. Get the correct fingerprint from whoever holds the release keystore.

---

## 12. Field checklist

Run this while the artisan is still in front of you. A missing field is a phone call; a missing
recording is another trip.

- [ ] Every artisan you spoke to has a record, with **Do's and Don'ts** filled in — these are
      required, and they are the part of the archive that cannot be reconstructed later.
- [ ] Every product has its dimensions, cost of making and selling price.
- [ ] Every process has its steps **in order**, and the steps have **video** — a photograph shows the
      result, video shows the knowledge.
- [ ] Every tool has a material, a maker and a replacement cost.
- [ ] The questionnaire's completion matrix has no unexplained gaps.
- [ ] Anything you shot that belongs to no record is in **Miscellaneous Media**.
- [ ] The outbox banner is gone — everything has actually reached the server.

---

## Where to go next

| Document | For |
|---|---|
| [WALKTHROUGH.md](WALKTHROUGH.md) | The ten screens in the order you use them, field by field |
| [PERMISSIONS.md](PERMISSIONS.md) | The exact rules behind §4 and §5 |
| [SECURITY.md](SECURITY.md) | What is protected, what is not, and the open risks |
| [QA_AUDIT.md](QA_AUDIT.md) | Known failure modes, if something behaves oddly |

---

## How this document is kept true

This guide describes **behaviour a researcher sees**, which is the hardest class of claim to verify
mechanically — no test asserts what a sentence in a guide promises. So it is maintained by walking
it, not by running it.

| Section | Checked against |
|---|---|
| §2 accounts, §4 roles | `DEFAULT_SIGNUP_ROLE` in `backend/app/core/config.py`, and the role table in [PERMISSIONS.md](PERMISSIONS.md) — which *is* mechanically checked (`docs/tools/check-docs.mjs` fails if the backend and web ladders disagree). |
| §3 install | `minSdk` / `applicationId` in `android/app/build.gradle.kts`; the OTA flow in `backend/app/api/routes/app_release.py`. |
| §5 workshops | `backend/app/services/workshop_access.py`. |
| §6 offline | `frontend/lib/offline.ts` and `android/app/src/main/java/com/fieldrepository/app/data/Offline.kt`; the tactic matrix in [MEDIA_PIPELINE.md](MEDIA_PIPELINE.md) §4. |
| §7 Aadhaar handling | `backend/app/services/artisan_identity.py` and `backend/tests/` — masking is `mask_aadhaar`. |
| §9 questionnaire | `artisanSetKey` in `backend/prisma/schema.prisma`; the idle-time rule in `backend/app/services/media_queue.py`. |
| §10 exports | The sheet list in `backend/app/api/routes/data_browser.py`, and `backend/tests/test_xlsx_report.py`. |

**The real maintenance procedure:** before each field deployment, one person signs in as a
**Researcher-tier account** — not an admin — and walks §§4–10 on a phone. Anything that does not
behave as written is either a documentation bug or a product bug, and both are worth finding before a
researcher does, 300 km from a signal.

**Review triggers:** a change to `DEFAULT_SIGNUP_ROLE`, to any `require_*` dependency in
`backend/app/core/deps.py`, to the offline outbox on either client, or to the Data Browser's export
surface.

**Known unverified in this document:** §3's OTA update flow and §11.3's Google Cloud console state
are both operational facts about a deployment, not properties of this repository. Neither can be
confirmed from the code, and neither should be treated as confirmed here.
