# Field Repository — the documentation walkthrough (for researchers)

This is the whole documentation process, in the order you actually perform it in the field:
from opening a workshop to exporting the finished dataset. Ten steps.

The same guide is available inside the app at **`/guide`** ("Walkthrough"), where each step links
straight to the screen it describes. This document is the version you can print, email, or read on
the bus on the way to the village.

The screens carry the **same names on the web and in the Android app**. Wherever this document
names a screen — *Artisan*, *Product*, *Process*, *Tool*, *Questionnaire*, *Miscellaneous Media*,
*View Data* — that is the name on the dashboard tile in both places.

---

## The process in one line

**Workshop → Craft → Artisan → Product → Process → Tool → Questionnaire → Miscellaneous Media →
Review → View Data**

Learn that order and you can work without the guide.

---

## Two things to know before you start

1. **Every record is scoped to a workshop.** Products, tools and interviews all carry a linked
   workshop, and the Data Browser opens on *By workshop*, filing the entire repository under the
   workshop each record was made in. On a create
   form, the most recent workshop you have access to is preselected — so getting the workshop right
   once saves you picking it on every screen afterwards.
2. **Everything you submit is reviewed.** Below the Professor tier the status chip on every form is
   locked: whatever you create is submitted as **Pending**. That is normal, not an error. A reviewer
   ranked above you then Approves it, Rejects it, or Sends it for revision with comments.

---

## 1. Workshop — *Record workshop*

**Screen:** Workshop (`/workshops`)

Open the workshop you are documenting under — or create it — before you record anything else.

**What the screen asks for:** Workshop title *(required)*, Place *(required)*, start and end date,
Description, Notes, Linked artisans, Crafts covered, Workshop media, Location (GPS fix or map pin).

**Why it exists.** The workshop is the container everything else drops into. Products, tools and
interviews all link to one, and *View Data* opens on **By workshop**, which files the whole
repository under the workshop each record was made in.

**Watch out for:**

- Create the workshop *before* you leave for the field.
- Records created outside a workshop's date window are flagged as **out-of-window** and need a
  reviewer's approval.

---

## 2. Craft — *Add craft*

**Screen:** Craft (`/crafts`)

Add the craft being documented so artisans, products and tools have something to hang off.

**What the screen asks for:** Craft name *(required)*, Local name, Category, Place, Description,
Craft media.

**Why it exists.** Craft is the shared vocabulary of the repository: artisans link to a craft,
products and tools inherit the craft name from it, and the Data Browser groups every workshop's
contents by craft. Adding it once keeps spellings consistent across everyone's records.

**Watch out for:**

- Check the list first — if the craft already exists, reuse it rather than creating a near-duplicate
  spelling.
- The local name matters as much as the English one. Record what the community actually calls it.

---

## 3. Artisan — *Record artisan*

**Screen:** Artisan (`/artisans/new`)

Record the person: who they are, where they work, how to reach them, and what they have learnt.

**What the screen asks for:** Name *(required)*, Local name, Workshop, **Craft** *(required)*, Or new
craft name, Place *(required)*, Gender, Phone, Email, Address, Notes, **Do's (positive prompt)**
*(required)*, **Don'ts (negative prompt)** *(required)*, Artisan media, Location (GPS fix or map
pin).

**Why it exists.** The artisan is the anchor of the dataset — products, processes, tools and
questionnaire interviews all link back to an artisan record. The Do's and Don'ts are the artisan's
own hard-won craft knowledge: the part of the archive that cannot be reconstructed later.

**Watch out for:**

- Do's and Don'ts are **required**. Press <kbd>Enter</kbd> for each new point — one lesson per line.
- You must either select an existing craft **or** type a new craft name. The form will not save with
  neither.
- Photo EXIF is retained and summarised into the notes automatically. Do not transcribe camera
  details by hand.

---

## 4. Product — *Record product*

**Screen:** Product (`/products/new`)

Record one thing this artisan makes, with its measurements, economics and photographs.

**What the screen asks for:** Product name *(required)*, Local name, Workshop, Product type, Linked
craft (fills craft name), Craft name *(required)*, Linked artisan (fills artisan + place), Artisan
name *(required)*, Place *(required)*, Time taken to complete, Size, Length (inches), Breadth
(inches), Height (inches), Cost of making, Selling price, Market demand, Raw materials used, Main
tools used, Function or use, Remarks, Product media, Location (GPS fix or map pin).

**Why it exists.** The product record is where the craft becomes measurable. Dimensions, cost of
making, selling price and market demand are the fields researchers compare across regions.

**Watch out for:**

- **Pick the linked craft first.** The artisan dropdown stays disabled until a craft is chosen, and
  then only lists that craft's artisans.
- Use **"Document using grid"** to photograph the piece against the measuring grid: it fills length,
  breadth and height for you *and* stores the photo as evidence.
- Choosing a linked artisan fills the artisan name and place; choosing a linked craft fills the
  craft name.

---

## 5. Process — *Document process*

**Screen:** Process (`/processes`)

Walk through how that product is made, one step at a time, filming each step as it happens.

**What the screen asks for:** Name of the process *(required)*, Artisan *(required)*, Product
*(required)*, then per step: Name of the step *(required)*, optional additional context notes, and
attached media.

**Why it exists.** The process is the craft itself. A product photograph shows the result; the
step-by-step record with per-step media shows the *knowledge* — the sequence, the hand movements,
the judgement calls that a text description always loses.

**Watch out for:**

- Add a step with **"Add Another Step"**, then pick **Sequential** for an ordered stage or
  **Group of activities** for things done together.
- **Video is the preferred format for steps** — capture the action as it happens rather than posing
  the result.
- Document the process against the product you already recorded, so the two stay linked.

---

## 6. Tool — *Record tool*

**Screen:** Tool (`/tools/new`)

Record the toolkit the artisan uses: what it is made of, how big it is, who made it, what it costs
to replace.

**What the screen asks for:** Toolkit name *(required)*, Local name, English name, Workshop, Linked
craft (fills craft name), Craft name *(required)*, Linked artisan (fills artisan + place), Artisan
name *(required)*, Place *(required)*, Process used in, Material, Years in use, Height, Width,
Length (inches), Breadth (inches), Thickness, Weight, Radius, Maker, Tradition type, Replacement
cost, Suggestions for improvement, Remarks, Process stages, Tool media, Location (GPS fix or map
pin).

**Why it exists.** Tools are the most quietly endangered part of a craft — the maker of a tool often
disappears before the craft does. Replacement cost, maker and tradition type are the fields that
record whether the toolchain behind the craft is still alive.

**Watch out for:**

- Fill only the dimensions that make sense for the tool. A blade has a length and a thickness; a
  wheel has a radius.
- **Process stages** archives your captures in order as `STAGE_STEP_1`, `STAGE_STEP_2`, … so shoot
  them in sequence.
- You can also hand tools to specific artisans later from **Assign tools to artisans** — for your
  own artisans, ones shared with you for editing, or any artisan if you are an admin.

---

## 7. Questionnaire — *Take interview*

**Screen:** Questionnaire (`/questionnaire`)

Sit down with the artisan and work through the interview sections, recording each answer as audio.

**What the screen asks for:** Interview title *(required)*, Date, Place, Language, Primary artisan,
Additional artisans, then per question either a **"Record this question"** audio clip or a typed
answer.

**Why it exists.** The questionnaire is the artisan speaking in their own voice and their own
language. Recorded audio is auto-transcribed on the server, so you get both the original recording
and searchable text without typing during the interview.

**Watch out for:**

- **There is one interview per exact set of artisans.** If an entry already exists for that set,
  saving adds your answers to it — it never creates a duplicate.
- Answer only the questions actually asked. Empty questions stay open for whoever picks the
  interview up next.
- Questions already answered by someone else can only be changed by that contributor or an admin.
- Use **"Check completion"** at the top of the screen to see the artisans × sections matrix and find
  the gaps.

---

## 8. Miscellaneous Media — *Upload media*

**Screen:** Miscellaneous Media (`/media`)

Upload the photographs, video, audio and files that do not belong to any single record.

**What the screen asks for:** Capture media (images, video, audio and documents), Media title /
object name, **Linked record type** *(required)*, Linked entry *(optional)*, Caption, Location (GPS
fix or map pin).

**Why it exists.** Field work produces context that no form has a slot for: the road into the
village, the market, an unplanned conversation. Miscellaneous Media keeps that material inside the
repository instead of on a phone that gets wiped.

**Watch out for:**

- **Upload stays disabled until you pick a Linked record type.** If the file belongs to nothing in
  particular, pick **Miscellaneous Media** and leave the entry blank.
- Audio uploaded here is queued for transcription after upload, exactly like interview audio.
- If the file does turn out to belong to a record, link it — misc media can be attached to a record
  afterwards.

---

## 9. Review — *Track your submissions*

**Screen:** Review (`/review`)

Everything you submit goes into the review queue and comes back **Approved**, **Rejected**, or
**Sent for revision**.

| Status | What it means |
| --- | --- |
| **Pending** | Submitted, waiting for a reviewer. |
| **Approved** | Final, counted in the dataset. |
| **Needs revision** | Comments explain what to change. |
| **Rejected** | Not going into the dataset. |

**Why it exists.** Review is what turns a pile of field notes into a dataset anyone can cite. It
also means you are never the last check on your own work.

**Watch out for:**

- Below Professor the status chip is locked: whatever you create is submitted as **Pending**.
- **"Send for revision" always carries mandatory comments.** Read them, fix the record, and saving
  resubmits it as Pending.
- Reviewers only see submissions from contributors ranked strictly below them; the master admin sees
  everyone.

---

## 10. View Data — *Browse records*

**Screen:** View Data / Data Browser (`/data`)

Browse the whole repository as a directory tree and export a report of any subtree. The root offers
the same records filed three ways:

- **By workshop** *(the view it opens on)* — every record filed under the workshop it was made in.
- **By uploader** — a workshop's records filed under the researcher who uploaded them.
- **By media type** — every file filed by what kind of file it is.

From any folder you can preview media and transcripts, download the folder as a **zip** with
content-type filters, or take the whole subtree as a **`.xlsx` report**.

**Watch out for:**

- Pick a folder, then use the breadcrumb to move back up. The tree loads lazily as you expand it.
- Transcripts and AI text render as formatted Markdown in the preview pane, not raw text.
- Dataset download is a **granted permission**. If your role does not have it, the browser shows a
  restricted notice — use **Search** to find records instead.

---

## Before you leave the field

A missing field is a phone call. A missing recording is another trip. Run this list while the
artisan is still in front of you.

- [ ] Every artisan you spoke to has a record, with Do's and Don'ts filled in.
- [ ] Every product you photographed has its dimensions, cost of making and selling price.
- [ ] Every process has its steps in order, and the steps have video.
- [ ] Every tool has a material, a maker and a replacement cost.
- [ ] The questionnaire's completion matrix has no unexplained gaps.
- [ ] Anything you shot that has no home is uploaded to Miscellaneous Media.

---

## Where to go next

| Screen | What it is for |
| --- | --- |
| **Dashboard** (`/dashboard`) | Every screen in this guide, one tap away. |
| **Review** (`/review`) | What is waiting on a decision. |
| **My Activity** (`/activity`) | Everything you have recorded so far. |
| **Search** (`/search`) | Find a record across the repository. |
| **Sharing** (`/sharing`) | Give a colleague access to your records. |
| **Give app feedback** (`/feedback`) | Tell us what slowed you down. |

For installing the app and getting an account, see **`docs/RESEARCHER_GUIDE.md`**.
