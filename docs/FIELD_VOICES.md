# Field voices for KalaVigyan

What leaves this portal for KalaVigyan, and how an admin takes something back. The same page in
both repositories. The contract is `science-behind-craft/docs/contracts/field-voices.md`
(version 2, decision 0084); this page says how the portal side meets it.

## What leaves

Two streams, read by KalaVigyan over three signed peer routes:

| Route | What it answers |
|---|---|
| `GET /api/cross-portal/peer/field-voices` | the state of both streams: live and gone counts, a digest of the live items, the newest cursor, and when the last refresh finished |
| `GET /api/cross-portal/peer/field-voices/transcripts?cursor=&view=all\|gone` | full transcripts of audio and video recordings, 20 changes and at most 8 MiB a page |
| `GET /api/cross-portal/peer/field-voices/images?cursor=&view=all\|gone` | every image with a 15-minute link to a neutral copy, 100 changes a page |

The owner's direction of 2026-11-18 is that everything in the portal comes across: every artisan
is an adult and has consented, and KalaVigyan publishes the full transcripts. Only the legal
floor below is held back.

- **Transcripts:** every audio and video recording that has a transcript and a craft, whatever it
  answers: whole-session and whole-section takes, takes that answer identity or contact
  questions, consent clips, dictation clips, and on the designer portal every stage recording
  (market-survey voice notes, the closing video and designer-defined fields included). Every
  speaker as the portal records them, the session, section and question or the design workshop
  stage, the district and state, the crafts, the segments with their timings, languages and
  English. Never the audio or video.
- **Images:** every image wherever it is filed: products, tools, processes, steps and crafts; an
  artisan, a session, a workshop or a design workshop (`belongsTo` kinds `ARTISAN`, `SESSION`,
  `WORKSHOP`, `DESIGN_WORKSHOP`, used when the image has no record or stage placement); a
  designer's profile photograph (`DESIGNER`); and on the designer portal every stage field (event,
  opening, cover, participant, mood board, market, closing, follow-up and logo images, images
  inside rich text, designer-defined fields), except the legal floor. An image filed on an artisan
  (`MediaFile.artisanId` set or `linkedRecordType` `artisan`), on a questionnaire session
  (`questionnaireInterviewId` set or `linkedRecordType` `questionnaire`, `questionnaireinterview` or
  `questionnaire_session`) or in a participant field is sent with `"artisanLinked": true`, whatever
  else it links to; KalaVigyan stores it hidden until a Master Admin chooses to show it. Never the original file: a copy with the orientation applied,
  the long edge at most 2,400 px, re-encoded with no EXIF, XMP, IPTC, ICC or GPS data, stored
  under `field-voices/<sha256>.<ext>` in this portal's media bucket. That key is the one
  deliberate exception to the naming convention; `naming.field_voice_copy_key` builds it and
  `shared/naming-golden.json` (`fieldVoiceKeys`) pins it.
- **Credit:** everyone by the name the portal records (`Artisan.name`, the designer's profile or
  account name), with the artisan's own craft (`craft`: `Artisan.craftId` followed through craft
  merges), years in craft, district and state, and each speaker's role in the recording.
- **Place:** district and state are the finest place in any structured field. Village and place
  names inside transcript text, titles, captions, names and labels are sent as written.

## The legal floor

This is law, not policy: the Aadhaar Act prohibits publishing Aadhaar numbers, and identity
documents, signatures, bank details and account credentials are never craft content.

- **Scrubbed from all text** (transcript text and its English, titles, captions, names and every
  label): email addresses, written or spoken, become `[email]`; these become `[number]`: Aadhaar-like
  numbers (twelve digits, grouped by spaces, hyphens or dots), phone-like runs of ten or more
  digits (which also catch bank account numbers of that length), PAN-like codes (`ABCDE1234F`),
  voter ID-like codes (`ABC1234567`), IFSC-like codes (`SBIN0001234`), and the linked people's own
  phone numbers and Pehchan card numbers. The linked people are every artisan the item is linked
  to (the take's subjects, mapped speakers, the session's participants, the file's own artisan,
  stage row artisans, and the artisans of the product, tool, process or step it is filed on) and
  the design workshop's designers. No pattern catches a number spoken in words, a Pehchan number
  of someone the item does not link to, or a nine-digit account number; the owner accepted that
  on 2026-11-18. A label is scrubbed the same way and sent as null only when nothing but
  placeholders is left. `scrubbing` reports version 2 and the counts of `EMAIL` and `NUMBER`.
- **Never sent as an image, when the portal can tell what it is:** an identity photograph
  (`extraMetadata.identityPhotograph`, any decision: on the designer portal a photograph is marked
  `PENDING` on the server the moment its card is read, before the designer keeps or deletes it,
  through `POST /api/design-workshops/ocr/identity/read` or the `mediaId` the card reader sends
  with the photograph), consent evidence (`Consent.evidenceMediaId`, signed or thumb-printed forms),
  a designer's signature or CV, the designer portal's `workshopPlan.designerSignature`,
  `workshopPlan.designerCv`, `certificate.signatureImage` and `certificate.certificateFile`, and
  any image whose own caption, the stage caption beside it, the caption it would be sent with or
  its file name names Aadhaar, Pehchan, an identity card, document or proof, an ID card or proof,
  a voter or PAN card, a passport or licence, a signature, consent, a fingerprint or thumb
  impression, a passbook, cheque, bank account or statement, IFSC or UPI. "Brand identity",
  "craft identity" and "a valid card" are ordinary craft words and are not withheld
  (`shared/field-voices-scrub-vectors.json`, `identityImage`).
- **What the portal cannot tell:** an identity card photographed with no mark, and no caption or
  file name that names it, looks like any other photograph. Researchers have filed such cards on
  artisan profiles, the documentation portal has no identity mark at all, and on the designer
  portal a card read before the `PENDING` mark shipped carries none. Such a picture is filed on an
  artisan, a session or a participant field, so it is sent `artisanLinked`, and the owner's
  direction for those images is "Bring it, but do not show": KalaVigyan stores it privately and
  shows it on no page, list or image route. The Aadhaar Act forbids publishing or displaying an
  Aadhaar number; a held copy does neither. Before a Master Admin shows held images, each one in
  KalaVigyan's held list (which names the portal and the file's id) is opened in its portal, and an
  identity document is withdrawn (**Withdraw** on the file) or removed there, which sends its
  tombstone.
- **Never sent in any field:** Aadhaar and Pehchan numbers, the Aadhaar verification and
  fingerprint fields, dates of birth, ages, gender, phone numbers, email addresses, bank details,
  passwords and every other credential, villages, addresses, PIN codes and coordinates as
  structured fields, prices, notes, file names, object keys, uploaders and reviewers. Every page is
  checked for the contract's never-sent keys before it is signed, and refused with an error when
  one is found.

What still keeps an item out: no transcript (recordings), no craft, a draft, rejected, removed or
deleted record, a recorded refusal (below), and an image that cannot be decoded.

## How it is built

| File | What it holds |
|---|---|
| `backend/app/services/field_voices/` | the shared package: `contract.py` (names, limits, never-sent keys), `scrub.py`, `people.py`, `sources.py`, `items.py`, `stages.py`, `transcripts.py`, `images.py`, `ledger.py` (stamps, revision, digest, cursor), `gone.py`, `refresh.py`, `pages.py`, `withdrawals.py` |
| `backend/app/services/field_voices_portal.py` | this portal's adapter: merges, extra refusals, design workshops, stage placements (every field but the legal floor), designers and their profile photographs |
| `backend/app/api/routes/cross_portal_field_voices.py` | the three peer routes |
| `backend/app/api/routes/field_voices_admin.py` | `GET` and `POST /api/field-voices/withdrawals/{subjectType}/{subjectId}` for admins |
| `backend/app/scripts/refresh_field_voices.py` | one refresh by hand: `python -m app.scripts.refresh_field_voices --dry-run` prints the counts and writes nothing |
| `backend/prisma/migrations/20261218100000_field_voices/migration.sql` | `PUBLICATION` in `ConsentPurpose`, and the `FieldVoiceItem`, `FieldVoiceRefresh` and `FieldVoiceCopyRelease` tables |
| `frontend/components/fieldVoices/FieldVoicesPanel.tsx`, `frontend/lib/fieldVoices/` | the admin control and its strings (`strings.json`) |
| `infra/terraform/main.tf` (`FieldVoiceCopies`) | the API key's Put, Get and Delete on `field-voices/*`; per portal, not shared |

Answers are read from the `FieldVoiceItem` ledger. The queue worker refreshes it every 15
minutes while the switch is on: it builds every candidate, writes the changed ones, and turns a
live item that no longer qualifies into a tombstone (`DELETED`, `WITHDRAWN`, `REPLACED`, `MERGED`
or `UNLISTED`). It makes at most 200 image copies a run. Every ledger write holds one advisory
lock and stamps rows with strictly increasing microsecond times, so a reader paging by cursor never
skips a row. A refresh never turns back to live a tombstone written after it read the batch; the
next refresh decides it again. The state route runs the gone pass first, so a withdrawal or
deletion is a tombstone before KalaVigyan reads: each live row keeps, outside its payload, every
media item, artisan, workshop and account whose refusal withholds it, and the gone pass checks
all of them, the payload's people, the session, the design workshop and stage rows, and, for an
image, whether any record it belongs to is left. A copy no live row names any more is queued in
`FieldVoiceCopyRelease` and deleted at the end of the refresh; a delete that fails stays queued
and is tried again at the next refresh. The files in this table, the shared tests and this page are kept the same
in both repositories by `shared/field-voices.sha256`.

## Switches

Off by default. Nothing leaves until both of these are set on the box, which the deploy pins
from the Actions variables of the same names ("Pin the field voices settings"):

| Variable | Meaning |
|---|---|
| `CROSS_PORTAL_FIELD_VOICES_ENABLED` | `true` turns on the three routes and the refresh. Needs `CROSS_PORTAL_TOKEN` of 32 or more characters; it does not need the identity switch or the Aadhaar key. Off: the routes answer 404 and the worker does nothing. |
| `FIELD_VOICES_RIGHTS_HOLDER` | the rights holder named on every image, at most 200 characters. Unset: the images route answers 404 and no copies are made. |

After the first deploy with the switch on, run one refresh by hand on the box
(`python -m app.scripts.refresh_field_voices`), then let the worker keep it current.

## Withdrawing an item

The owner's direction of 2026-11-18 is the consent for every record already in the portal, so an
item needs no grant. A recorded refusal always wins. An admin, in admin view, uses the
**KalaVigyan** panel ("Shown on KalaVigyan", **Withdraw**, **Show again**) on the artisan, file and
workshop pages in both portals, on the design workshop and designer profile pages in the designer
portal, and on the account page in the documentation portal. The panel calls
`POST /api/field-voices/withdrawals/{ARTISAN|RECORDING|WORKSHOP|USER}/{id}` with the body
`{"shown": false, "basis": "VERBAL_RECORDED" | "VERBAL_WITNESSED" | "SIGNED_FORM", "note": "..."}`.
That writes a `PUBLICATION` refusal to the consent ledger; `{"shown": true, ...}` shows it again.
The item leaves KalaVigyan at its next state read. A refusal of outside processing or of
cross-portal sharing withholds the item as well, and so does deleting the record.

## How this document is kept true

| Claim | Kept true by |
|---|---|
| The scrub patterns, the label scrub, the legal-floor images (a read card marked `PENDING`, a stage caption naming an identity document, the image pattern's vectors), the held-back numbers (the records' artisans included), identity-question, whole-session, dictation and consent recordings listed, place names kept, every image placement and the artisan-linked mark, the never-sent keys, revision, digest, cursor, page sizes, the rate window, the neutral copy, its key and its Terraform grant | `backend/tests/test_field_voices_units.py` |
| Designer portal only: a photograph is marked `PENDING` before its card is read, a keep or delete replaces the mark, and only a photograph in a workshop the reader can edit is marked | `backend/tests/test_identity_photo_retention.py`, `frontend/e2e/identity-photo-retention-unit.spec.ts` |
| The admin control's bases, request and the pages it is on | `frontend/e2e/field-voices-unit.spec.ts` |
| The shared files are the same in both repositories, and the schema holds the shared models | `backend/tests/test_field_voices_parity.py` |
| The routes, switch, signature, access log, paging, tombstones, consent and deletion rules, the respondent questions listed with their numbers scrubbed, place names kept, the portrait sent and marked artisan-linked, the stored subjects, the tombstone race and the copy release retry against a database | `backend/tests/test_cross_portal_field_voices.py` |
