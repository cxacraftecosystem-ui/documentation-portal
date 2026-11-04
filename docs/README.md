# Documentation

Start here. Every document below states, in its own final section, **how it is kept true** — what
regenerates it, what to diff it against, and which changes should trigger a human re-read. A document
with no such section does not meet the bar and is listed as a gap.

---

## Where to start, by what you are doing

```mermaid
%%{init: {"theme":"base","fontFamily":"Inter, ui-sans-serif, system-ui, -apple-system, Segoe UI, Roboto, Helvetica, Arial, sans-serif","themeVariables":{"fontSize":"15px","primaryColor":"#F2E9FE","primaryTextColor":"#1E1B2E","primaryBorderColor":"#9148D2","secondaryColor":"#FAEECD","secondaryTextColor":"#1E1B2E","secondaryBorderColor":"#CD9200","tertiaryColor":"#F3F1FA","tertiaryTextColor":"#1E1B2E","tertiaryBorderColor":"#A7A3BC","mainBkg":"#F2E9FE","nodeBorder":"#9148D2","nodeTextColor":"#1E1B2E","lineColor":"#AA69E9","textColor":"#857F9E","edgeLabelBackground":"#F9F5FF","clusterBkg":"#F9F5FF","clusterBorder":"#D6B6FB","titleColor":"#762CB1","noteBkgColor":"#FAEECD","noteTextColor":"#1E1B2E","noteBorderColor":"#CD9200","actorBkg":"#F2E9FE","actorBorder":"#9148D2","actorTextColor":"#1E1B2E","actorLineColor":"#AA69E9","signalColor":"#AA69E9","signalTextColor":"#857F9E","labelBoxBkgColor":"#F2E9FE","labelBoxBorderColor":"#9148D2","labelTextColor":"#1E1B2E","loopTextColor":"#857F9E","activationBkgColor":"#E7D5FE","activationBorderColor":"#9148D2","sequenceNumberColor":"#FFFFFF","transitionColor":"#AA69E9","transitionLabelColor":"#857F9E","stateLabelColor":"#1E1B2E","stateBkg":"#F2E9FE","compositeBackground":"#F9F5FF","compositeTitleBackground":"#F2E9FE","attributeBackgroundColorOdd":"#FFFFFF","attributeBackgroundColorEven":"#F9F5FF","relationColor":"#AA69E9","relationLabelBackground":"#F9F5FF","relationLabelColor":"#1E1B2E"}}}%%
flowchart TD
  Q{What are you here for?}

  Q -->|"I'm documenting crafts in the field"| R1
  Q -->|"I'm new to the codebase"| D1
  Q -->|"I'm deploying or operating it"| O1
  Q -->|"I'm reviewing a change"| C1
  Q -->|"I need a number for a paper"| F1
  Q -->|"I'm working on transcripts or AI"| T1
  Q -->|"I'm writing a README or a diagram"| K1

  R1["<b>RESEARCHER_GUIDE.md</b><br/>accounts, install, offline, exports"] --> R2["<b>WALKTHROUGH.md</b><br/>the ten screens, field by field"]

  D1["<b>ARCHITECTURE.md</b><br/>the map"] --> D2["<b>DATA_MODEL.md</b><br/>the schema, and its surprises"]
  D2 --> D3["<b>PERMISSIONS.md</b><br/>who may do what"]
  D3 --> D4["<b>MEDIA_PIPELINE.md</b><br/>how bytes get in"]

  O1["<b>ENVIRONMENT.md</b><br/>every variable"] --> O2["<b>CI.md</b> · <b>DEPLOYMENT_VERCEL.md</b><br/>the pipeline"]
  O2 --> O3["<b>CDN.md</b> · <b>DOCKER.md</b> · <b>KUBERNETES.md</b><br/>other shapes"]

  C1["<b>SECURITY.md</b><br/>risk register"] --> C2["<b>QA_AUDIT.md</b><br/>what is and isn't tested"]
  C2 --> C3["<b>SCALABILITY.md</b><br/>what breaks first"]

  F1["<b>REPO_FACTS.md</b><br/>generated counts"] --> F2["<b>RESEARCH_NOTES.md</b><br/>dated results"]

  T1["<b>TRANSCRIPT_PIPELINE.md</b><br/>Scribe v2, Gemini, the viewer"] --> T2["<b>AI_FEATURES.md</b><br/>models, summaries, image tools"]
  T2 --> T3["<b>questionnaire/API.md</b><br/>sessions, takes, chips"]

  K1["<b>readme-kit/README_STYLE.md</b><br/>the visual system"]
```

---

## Every document

### The field team

| Document | Answers | Audience |
|---|---|---|
| [RESEARCHER_GUIDE.md](RESEARCHER_GUIDE.md) | How do I get an account, install the app, work with no signal, and get my data back out? | Craft scholars and field researchers |
| [WALKTHROUGH.md](WALKTHROUGH.md) | What does each of the ten screens ask for, and in what order? | Same. Also mirrored in-app at `/guide` |

### The system

| Document | Answers |
|---|---|
| [ARCHITECTURE.md](ARCHITECTURE.md) | What are the pieces, how does a request travel, where is the latency, how does transcription fail over, how does the offline outbox replay? |
| [DATA_MODEL.md](DATA_MODEL.md) | What is stored, how does it relate, and which four parts of the schema are not what they look like? |
| [ARTISAN_DUPLICATES.md](ARTISAN_DUPLICATES.md) | Duplicate artisans — the server-side score and its bands, the hold at save time on web and Android (offline creates are asked on sync), the admin merge screen, the 30-day undo and redirect, and the ids-only match the designer portal calls |
| [PERMISSIONS.md](PERMISSIONS.md) | The six-tier ladder, the full capability matrix, the review state machine, the late-submission gate, and the three access systems layered on top |
| [COVERAGE_MAP.md](COVERAGE_MAP.md) | The coverage view of `/map`: what each district is shaded by, the gaps, the Survey of India outline and the shape checks that hold it, LGD codes resolved at read time, unmatched places and their aliases, and the aggregate feed the designer portal reads |
| [CRAFTS.md](CRAFTS.md) | The craft master list: the categories, sub-categories and crafts both portals share, the catalogue every picker reads, the craft routes, and how crafts recorded before the list are mapped onto it |
| [RELEASE.md](RELEASE.md) | The data steps a release takes on production after its migrations, such as mapping crafts onto the master list |
| [MEDIA_PIPELINE.md](MEDIA_PIPELINE.md) | Every tactic both clients use to get a photograph off a phone on a bad network without losing it |
| [TRANSCRIPT_PIPELINE.md](TRANSCRIPT_PIPELINE.md) | Scribe v2 turns, Gemini 3.8 Flash English, the transcript API, its switches, budget, keys and backfill |
| [SECURITY.md](SECURITY.md) | Transport, secrets, PII and Aadhaar handling, the authorisation model's security properties, and the open risk register |
| [questionnaire/API.md](questionnaire/API.md) | Questionnaire v2 on the shared engine: sessions, takes, coverage, canonical answers, the routes both clients call |
| [questionnaire/ANDROID.md](questionnaire/ANDROID.md) | The Android half of the questionnaire engine and how it is kept identical to the designer portal's |
| [SEARCH.md](SEARCH.md) | One search across both portals: the per-portal index and its triggers, full-text, trigram and (behind the AI flag) meaning ranking, the signed peer search with the designer portal, consent, and the rollout order |

### Operating it

| Document | Answers |
|---|---|
| [ENVIRONMENT.md](ENVIRONMENT.md) | Every environment variable, per service: required? default? secret? what breaks without it? |
| [CI.md](CI.md) | What happens on a push to `main`, in what order, and why the order is a dependency |
| [RELEASING.md](RELEASING.md) | How an Android build reaches the fleet: the tag-driven signed pipeline, what each guard refuses, how to check a signer by hand, and the one-off uninstall that moving off the debug key costs every handset |
| [DEPLOYMENT_VERCEL.md](DEPLOYMENT_VERCEL.md) | The web deploy, and the two traps that ship a green pipeline over a broken site |
| [CDN.md](CDN.md) | CloudFront caching, the origin timeout that has already broken this system, and the invalidation runbook |
| [INFRA_ORIGIN_LOCK.md](INFRA_ORIGIN_LOCK.md) | Why the API box answers CloudFront and nothing else: the security group, the origin header, the ids, how to change the secret and how to roll it back |
| [DOCKER.md](DOCKER.md) | Running the whole stack in containers |
| [KUBERNETES.md](KUBERNETES.md) | Running it on a cluster, and the connection ceiling that governs how far it scales |

### Engineering results

| Document | Answers |
|---|---|
| [SCALABILITY.md](SCALABILITY.md) | What breaks first, what it costs to fix, and the measured finding that relations — not rows — drive the latency |
| [QA_AUDIT.md](QA_AUDIT.md) | What is tested, what is not, the open failure modes, and the regressions that were documented as working while broken |
| [OPEN_FINDINGS.md](OPEN_FINDINGS.md) | Which features users might look for are missing, which known defects they can meet, and what closing each one takes. These are recorded here because no screen talks about them. |
| [AI_FEATURES.md](AI_FEATURES.md) | The AI that is live (transcript summaries and the 0.0.10 model defaults: Gemini 3.8 Flash first, GPT-6 Luna and Claude Sonnet 5.5 only when selected) and the image tools that are not (background removal, layer separation, vectorisation): providers, costs, and how to turn one on |
| [RESEARCH_NOTES.md](RESEARCH_NOTES.md) | **A dated snapshot (July 2026).** The engineering results written for a paper: the latency law, the pooler, memory on a small box |
| [UX_AUDIT_2026-10-16.md](UX_AUDIT_2026-10-16.md) | **Dated.** The interface audit of 16 Oct 2026 and what it found |
| [ANDROID_OFFLINE_AUDIT.md](ANDROID_OFFLINE_AUDIT.md) | **Dated.** What the Android app keeps when there is no signal, screen by screen (16 Oct 2026) |
| [REPO_FACTS.md](REPO_FACTS.md) | **Generated.** Model and enum counts, the API surface, the role ladder, test counts, code volume |
| [DATASET_API.md](DATASET_API.md) | The bearer-token bulk export: how to authenticate, what datasets exist, the filters, and what the identity-number scope costs |
| [DATA_EXPLORER.md](DATA_EXPLORER.md) | The data page's server side for **people**: folders, sizes before a download, multi-select, streaming zips, previews, thumbnails, table search, transcript zips, and why none of it holds a whole archive in memory |
| [readme-kit/README_STYLE.md](readme-kit/README_STYLE.md) | How every README and diagram here is built: the SVG templates in light and dark, the mermaid palette, tables, motion and the CI check. Shared with designer-portal |
| [DESIGN-claude.md](DESIGN-claude.md) | **Observed, not generated.** The visual design system this interface borrows from, extracted from anthropic.com |

Also in the repository, outside `docs/`: [`../README.md`](../README.md) (orientation and local
setup) and [`../backend/DEPLOY_AWS.md`](../backend/DEPLOY_AWS.md) (the EC2/S3/CloudFront runbook).

---

## The rule about counts

**No hand-written document in this set states a count.** Not the number of models, not the number of
endpoints, not lines of code. Those all live in [REPO_FACTS.md](REPO_FACTS.md), which is generated
from the working tree, and prose links to it.

The reason is that a count is wrong the first time anybody adds a table, and it is wrong *silently* —
nothing about "32 models" looks stale. Centralising them means a migration makes exactly one file
wrong, and one command makes it right:

```bash
node docs/tools/check-docs.mjs --write
```

If you find a count written into prose anywhere in this set, that is a bug in the documentation, not
a detail to update.

---

## The checker

```bash
node docs/tools/check-docs.mjs           # verify — exit 1 on any failure
node docs/tools/check-docs.mjs --write   # regenerate REPO_FACTS.md, then verify
node docs/tools/check-docs.mjs --quiet   # failures only
```

It verifies the four things about documentation that *can* be verified:

| Check | Catches |
|---|---|
| **Generated counts are current** | A migration or a new route that nobody re-ran the generator for |
| **Every repository path mentioned exists** | A service or component path that a rename left behind. Paths resolve against the repo root and against `backend/`, `frontend/`, `android/`, because a runbook writes commands from the directory it told you to be in |
| **Line citations land inside their file** | `media.py:198-264` after the file shrank. It also *counts* citations per document and warns, because a citation that still fits is only possibly right — the durable fix is to cite symbol names |
| **The backend and web role ladders agree** | `ROLE_RANK` drifting between `backend/app/core/roles.py` and `frontend/lib/permissions.ts`. This is the one genuine correctness check in the set |
| **Every document has a maintenance section** | A new document shipping with no story for how it stays true |
| **Mermaid blocks are structurally sane** | An unclosed fence, a block with no diagram type, and the specific bug that broke a diagram here: a **semicolon inside a sequence-diagram message**, which Mermaid reads as a statement separator so everything after it parses as a new statement and the whole diagram renders as a red error box |

It deliberately does **not** check whether a sentence is true. Nothing can. That is what each
document's own maintenance table is for.

**Mermaid, for certainty.** The lint above is structural. The real render runs in CI since 0.0.10:
`.github/workflows/readme-check.yml` extracts every mermaid block in `README.md`, `docs/**` and
`.claude/skills/**`, renders each one with mermaid-cli, and fails on any that does not render. Every
block starts with the kit's `%%{init}%%` palette so diagrams read on GitHub's light and dark themes
alike ([readme-kit/README_STYLE.md §6](readme-kit/README_STYLE.md)). Two traps the out-of-tree parse
used to catch, and the render still does: a semicolon inside a sequence message, and HTML entities
(`&lt;`) inside one. **Normalise CRLF before matching**: some of these files are CRLF, and a
`
`-anchored regex silently finds zero blocks in them.

Findings in documents owned by another workstream are reported as **warnings**, not failures, so the
exit code speaks for the documents whose owner can act on it.

---

## Known gaps

Honest about its own state, since that is the standard the rest of the set is held to.

| Gap | Status |
|---|---|
| `OWNED_ELSEWHERE` in `docs/tools/check-docs.mjs` still names six documents | All six have had a maintenance section since 0.0.10, so the checker no longer warns about them; the set can be emptied in the next code change, which turns any future regression into a failure. |
| `SCALABILITY.md` pins 38 line numbers | They will drift. The checker reports the count so it cannot grow unnoticed. `MEDIA_PIPELINE.md` has been converted to symbol names; that is the pattern. |
| Android has no equivalent of the role-ladder parity check | The Kotlin client's permission mirror is believed to match and is not proven to. Noted in [PERMISSIONS.md](PERMISSIONS.md). |
| Console state — CloudFront, S3, Vercel, Google Cloud — cannot be verified from a checkout | Every such claim is marked **UNVERIFIED**. The better fix, where it is possible, is the one [CI.md §1](CI.md) took: assert it at deploy time instead of documenting it. |

---

## How this document is kept true

It is an index, so it has exactly two ways to be wrong: a document exists and is not listed, or a
document is listed and does not exist.

The second is checked — `docs/tools/check-docs.mjs` resolves every relative link here and fails on a
broken one. The first is not, and is the one to watch: **a new document must be added to the tables
above in the same commit that creates it.** `ls docs/*.md` against this page's tables is the check,
and it takes ten seconds.

The "Known gaps" table is kept true by being embarrassing. Each row names the thing that closes it.
