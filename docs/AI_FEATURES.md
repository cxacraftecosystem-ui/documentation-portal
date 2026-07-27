# AI image features — background removal, layer separation, vectorisation

**Status: dormant.** Every capability described here is off by default, nothing in the API imports
the package, and none of its dependencies are installed anywhere. A fresh clone with no new
environment variables behaves exactly as it did before this document existed. That is deliberate:
the features were built so they are *ready*, not so they are *running*.

**Who this is for:** whoever later decides to turn one on. It says what each feature does, why you
would want it for craft documentation, what each provider costs in memory and money, and the exact
variables to set. Boot-time and configuration rules for the rest of the backend live in
[ENVIRONMENT.md](ENVIRONMENT.md); the media pipeline these would eventually plug into is
[MEDIA_PIPELINE.md](MEDIA_PIPELINE.md).

Code: `backend/app/ai_features/`. It is called `ai_features`, not `ai`, because
`backend/app/services/ai.py` is the existing transcription service and the two have nothing to do
with each other.

---

## 1. The three capabilities

| Capability | What you get back | Flag |
|---|---|---|
| **Background removal** | One PNG: the subject on transparency | `AI_BACKGROUND_REMOVAL_ENABLED` |
| **Foreground/background separation** | Three PNGs: subject layer, background layer, and the alpha matte between them | `AI_FOREGROUND_SEPARATION_ENABLED` |
| **Image vectorisation** | One SVG traced from the raster | `AI_IMAGE_VECTORISATION_ENABLED` |

Removal and separation are the same underlying operation — matting — exposed twice on purpose.
Removal is the common case and callers should not have to do their own compositing to get it;
separation is for the case where the background is as interesting as the subject.

### Why these, for this repository

**A product photograph taken in a working workshop is a photograph of a workshop.** Field images
arrive with a loom, a dye vat, a plastic stool and half a doorway behind the object being
documented. For a catalogue plate, a comparison grid, or anything printed, the object has to come
away from that. Background removal does it in one call; separation does it while keeping the
workshop — which for a *research* archive is often the point, because the setting is evidence about
how the craft is practised. The matte is returned separately so a researcher can re-composite
against a neutral card without re-running anything.

**A block-print motif is a shape, not a photograph.** Motifs, stamps, tool profiles and maker's
marks are line art that gets reused at every size: a thumbnail in the browser, a plate in a report,
a laser-cut stencil. Traced to SVG once, the same motif is crisp at any of those. A photograph of a
textile is the wrong input for this — it traces into thousands of paths and a very large file — and
the result's `notes` will tell you when the SVG has come back unusually big.

Neither feature overwrites anything. They take an image and hand back a new one; deciding whether
the derived asset is worth storing beside the original is the caller's problem, not the package's.

---

## 2. Providers, and what each one costs

Four providers, two per operation, one local and one hosted in each pair. `auto` (the default)
picks the first one that is installed and configured.

| Provider | Kind | Capabilities | Needs | Model download | Peak RAM | Latency | Money |
|---|---|---|---|---|---|---|---|
| `remove_bg` | hosted | removal, separation | `REMOVE_BG_API_KEY` (+ Pillow for separation) | none | ~45 MB *(ESTIMATED)* | 2–6 s *(ESTIMATED)* | 1 credit/image *(VENDOR-STATED)* |
| `rembg_local` | local | removal, separation | `rembg`, `onnxruntime`, Pillow | 176 MB | **1,032 MB** *(MEASURED)* | 1.3–2.4 s *(MEASURED)* | free |
| `vtracer_local` | local | vectorisation | `vtracer` | none | 273 MB *(MEASURED)* | 0.25–2.8 s *(MEASURED)* | free |
| `vectorizer_ai` | hosted | vectorisation | `VECTORIZER_AI_API_ID` + `_SECRET` | none | ~45 MB *(ESTIMATED)* | 3–10 s *(ESTIMATED)* | free in `test`, 1 credit in `production` *(VENDOR-STATED)* |

Every number in that table is labelled. **MEASURED** means it was run on a machine and the method
is in §7. **ESTIMATED** means somebody reasoned about it — plausible, unverified. **VENDOR-STATED**
means the vendor's own documentation said so and nobody here has an account to check it against.
The same labels are attached to the numbers in the code (`ResourceProfile.basis`) and are printed
by the probe, so nothing has to be looked up in a document to be trusted or distrusted correctly.

### The honest recommendation for the production box

Production is one **t3.micro: 2 burstable vCPU, 1 GiB of RAM total**, already running uvicorn, the
Prisma query engine and the media queue worker behind nginx behind CloudFront.

- **Do not run `rembg_local` there.** Measured on a laptop, `import rembg` alone took the process
  from 35 MB to 167 MB, building a U2Net session took it to 564 MB, and a single 1.9-megapixel
  matte peaked it at **1,032 MB** — more than the whole box, before uvicorn is counted. The lite
  model (`u2netp`, a 4.7 MB download instead of 176 MB) is not a rescue: it still peaked at 696 MB
  on the same image, because the full-resolution buffers cost more than the weights do.
- **For background removal and separation on the box as it stands, use `remove_bg`.** It needs no
  new packages for removal (`requests` is already a core dependency), holds nothing resident, and
  the RAM cost is the image in flight.
- **If local matting is genuinely wanted, give it its own machine.** A separate worker instance
  with 2 GiB or more, pulling from the same queue, is the shape that works. That is also where the
  176 MB model download and its ~17 s belong, not on the box serving requests.
- **`vtracer_local` is fine on the box** in the sense that it fits, but it is still 273 MB and
  seconds of CPU per image on two burstable vCPU. Run it from the queue and do not run two at once.

There is no configuration in this package that will make local U2Net fit in 1 GiB. Anybody who
needs it needs a bigger machine, and pretending otherwise in a document would cost somebody an
afternoon and an OOM kill.

### What leaves the building

The hosted providers upload the photograph to a third party. These are images of named artisans'
work, collected under a research agreement, so whether that is allowed is a consent question, not a
technical one — it belongs to whoever holds the agreement. If the answer is no, the options are
local inference on a machine that can take it, or not having the feature.

---

## 3. Environment variables

Read by `backend/app/ai_features/settings.py`, which checks the real environment first and falls
back to `backend/.env` — the same file the rest of the backend uses. **All flags default to off and
`pip install -e .` installs none of the dependencies.**

### Switches

| Variable | Default | Meaning |
|---|---|---|
| `AI_FEATURES_ENABLED` | `false` | Master switch. Everything below is inert while this is off. |
| `AI_BACKGROUND_REMOVAL_ENABLED` | `false` | Capability 2. Needs the master switch too. |
| `AI_FOREGROUND_SEPARATION_ENABLED` | `false` | Capability 1. Needs the master switch too. |
| `AI_IMAGE_VECTORISATION_ENABLED` | `false` | Capability 3. Needs the master switch too. |

Two levels because the master switch is what an incident responder flips: one variable stops the
whole package without having to work out which of three features is misbehaving.

### Provider choice

| Variable | Default | Values |
|---|---|---|
| `AI_BACKGROUND_REMOVAL_PROVIDER` | `auto` | `auto`, `remove_bg`, `rembg_local` |
| `AI_FOREGROUND_SEPARATION_PROVIDER` | `auto` | `auto`, `remove_bg`, `rembg_local` |
| `AI_IMAGE_VECTORISATION_PROVIDER` | `auto` | `auto`, `vtracer_local`, `vectorizer_ai` |

`auto` prefers **hosted for matting** (the box cannot host U2Net) and **local for vectorisation**
(VTracer has no model and no per-image charge). An explicit id is never silently substituted: if
you ask for `rembg_local` and it is not installed, you get an error saying so rather than a
remove.bg charge you did not ask for.

### Limits, shared by every capability

| Variable | Default | Meaning |
|---|---|---|
| `AI_FEATURES_MAX_IMAGE_BYTES` | `12582912` (12 MB) | Refused before any decode. Matches remove.bg's own ceiling. |
| `AI_FEATURES_MAX_IMAGE_PIXELS` | `24000000` (24 MP) | Read from the file header, before any pixels are decoded. |
| `AI_FEATURES_TIMEOUT_SECONDS` | `60` | Wall-clock budget for one call, including upload. |

### Local inference

| Variable | Default | Meaning |
|---|---|---|
| `AI_FEATURES_LOCAL_MODEL` | `u2net` | Any model rembg knows: `u2net`, `u2netp`, `isnet-general-use`, … |
| `AI_FEATURES_LOCAL_MODEL_DIR` | unset | Where weights live. Exported as `U2NET_HOME`; defaults to `~/.u2net`. |
| `AI_FEATURES_CACHE_LOCAL_SESSION` | `true` | Keep the ONNX session resident between calls. Turning it off trades ~4 s per call for hundreds of MB back. |

### remove.bg

| Variable | Default | Meaning |
|---|---|---|
| `REMOVE_BG_API_KEY` | unset | **Secret.** The only thing needed to enable hosted removal. |
| `REMOVE_BG_ENDPOINT` | `https://api.remove.bg/v1.0/removebg` | Override for a proxy or a test double. |
| `REMOVE_BG_SIZE` | `auto` | `preview` is documented by the vendor as free at ≤0.25 MP — the setting to use while trying it out. |

### vectorizer.ai

| Variable | Default | Meaning |
|---|---|---|
| `VECTORIZER_AI_API_ID` | unset | **Secret.** Half of a pair; both halves are needed. |
| `VECTORIZER_AI_API_SECRET` | unset | **Secret.** |
| `VECTORIZER_AI_ENDPOINT` | `https://vectorizer.ai/api/v1/vectorize` | Override for a proxy or a test double. |
| `VECTORIZER_AI_MODE` | `test` | `test` is free and watermarked; `production` spends a credit. The default is the free one so a mis-wired experiment cannot run up a bill. |

### VTracer tuning

| Variable | Default | Meaning |
|---|---|---|
| `AI_VECTOR_COLORMODE` | `color` | `color` or `binary`. Use `binary` for a single-colour block print. |
| `AI_VECTOR_FILTER_SPECKLE` | `4` | Discard blobs smaller than this. Raise it when the SVG comes back enormous. |
| `AI_VECTOR_COLOR_PRECISION` | `6` | Bits of colour kept. Lower means fewer, flatter regions. |

---

## 4. Turning one on

Nothing in these recipes affects a process that is already running: the settings are cached, so
restart the API and the queue worker (`sudo systemctl restart fieldrepo fieldrepo-queue`) after any
change.

### Background removal, hosted — the one that fits on the production box

```bash
# backend/.env
AI_FEATURES_ENABLED=true
AI_BACKGROUND_REMOVAL_ENABLED=true
REMOVE_BG_API_KEY=<key from remove.bg>
REMOVE_BG_SIZE=preview     # free at <=0.25 MP while you are trying it out
```

No `pip install` at all: removal through remove.bg needs only `requests`, which is already a core
dependency. Confirm with the probe (§6), then try one image:

```bash
cd backend
python -c "
from app.ai_features import remove_background
result = remove_background('sample.jpg')
open('cutout.png','wb').write(result.image)
print(result.as_dict())
"
```

### Separation, hosted

```bash
AI_FOREGROUND_SEPARATION_ENABLED=true
```

plus `pip install -e '.[ai]'` — the two layers are composited locally from the alpha remove.bg
returns, and that is Pillow. (Asking the API for a mask separately would be a second credit for
information already in hand.) The probe will say `remove_bg: missing packages: PIL` until you do,
and the call fails before it uploads anything, so a missing Pillow never costs a credit.

### Vectorisation, local

```bash
AI_FEATURES_ENABLED=true
AI_IMAGE_VECTORISATION_ENABLED=true
```

plus `pip install -e '.[ai]'` (which brings `vtracer`). For a single-colour motif, also set
`AI_VECTOR_COLORMODE=binary`.

### Local matting — read §2 first

```bash
AI_FEATURES_ENABLED=true
AI_BACKGROUND_REMOVAL_ENABLED=true
AI_BACKGROUND_REMOVAL_PROVIDER=rembg_local
AI_FEATURES_LOCAL_MODEL_DIR=/var/lib/fieldrepo/models   # must be writable
```

plus `pip install -e '.[ai-local]'` (~400 MB of wheels) and a 176 MB model download on first call.
**Not on the t3.micro.** The first call is slow — import, session build, download — and every call
after it holds the session resident.

---

## 5. Calling it

```python
from app.ai_features import Capability, AiFeatureError, is_available, remove_background

if is_available(Capability.BACKGROUND_REMOVAL):
    try:
        result = remove_background(original_bytes)      # or a path
        upload(result.image, content_type=result.mime_type)
    except AiFeatureError as exc:
        log.warning("keeping the original: %s", exc.message)
```

Three entry points, all with the same shape:

| Function | Returns | Fields |
|---|---|---|
| `remove_background(source)` | `CutoutResult` | `.image` (PNG bytes), `.mime_type`, `.duration_ms`, `.notes` |
| `separate_foreground(source)` | `SeparationResult` | `.foreground`, `.background`, `.matte`, `.duration_ms`, `.notes` |
| `vectorise_image(source)` | `VectorResult` | `.svg` (UTF-8 markup, not a data URL), `.duration_ms`, `.notes` |

`source` is bytes or a path. Each takes optional `provider=` and `settings=` overrides. Every result
has `as_dict()` for persisting beside the asset, and `notes` carries things worth keeping — credits
charged, a matte that had to be resized, an SVG that came back at a megabyte.

**Layer arithmetic.** In a separation, both layers are the original pixels wearing opposite alpha.
Compositing the foreground over the background returns the original exactly where the matte is
fully 0 or 255, and slightly darker in the soft band between — the unavoidable arithmetic of
splitting one image into two straight-alpha layers. That is why the matte is also returned on its
own.

### These belong on the queue, not in a request

A hosted cutout is seconds; a local matte is tens of seconds; CloudFront gives the origin **30 s**
before it returns a 504, and this backend has already been bitten by exactly that on media uploads.
Put these where the media pipeline already puts slow work: enqueue a job, run it on
`fieldrepo-queue`, write the derived asset to S3, record it against the media row. The functions are
written for that — bytes or a path in, bytes plus metadata out, no database access, no globals
beyond one cached model session.

### Failure is always an exception

Nothing returns `None` and nothing returns the original pretending it was processed — silently
writing an untouched photograph back as if it were a cutout is worse than any error. Every failure
is an `AiFeatureError` subclass with a machine-readable `code`, a `remediation` sentence, and
`as_dict()` for a future route.

| Class | `code` | Means | Do |
|---|---|---|---|
| `FeatureDisabled` | `disabled` | The flag is off. **The default state, not an error.** | Nothing. Use the original. |
| `DependencyMissing` | `dependency_missing` | Flag on, package absent | `pip install -e '.[ai]'` (or `.[ai-local]`) |
| `ProviderNotConfigured` | `not_configured` | Flag on, credential absent or rejected | Set the named variable |
| `UnknownProvider` | `unknown_provider` | A provider id that does not exist | Fix the `*_PROVIDER` variable |
| `UnsupportedImageType` | `unsupported_type` | Not a readable PNG/JPEG/WebP | Convert it |
| `ImageTooLarge` | `too_large` | Over the byte or pixel ceiling | Downscale, or raise the ceiling |
| `ProviderTimeout` | `timeout` | The budget ran out | Raise the timeout, or use the queue |
| `ProviderRateLimited` | `rate_limited` | Hosted 429; carries `retry_after` | Retry later, slow the queue |
| `ProviderFailed` | `provider_failed` | Anything else, including out-of-credit | Read the message |

Configuration failures are logged **once per process** — a batch of 925 images with a missing key
writes one line, not 925. Per-image failures are logged per image, because each is about a
different image.

---

## 6. The probe: why is nothing happening?

A dormant feature is indistinguishable from a broken one from the outside, and there are four
candidate explanations: the master flag, the capability flag, a missing package, an unset key. The
probe names which one, without importing a provider or spending a credit.

```bash
cd backend
python -c "from app.ai_features import format_probe; print(format_probe())"
```

On a default installation:

```
AI image features: disabled (default)
  [off] background_removal: AI_FEATURES_ENABLED is off - the whole package is dormant
        - remove_bg: unset settings: REMOVE_BG_API_KEY
        - rembg_local: missing packages: rembg, onnxruntime, PIL
  [off] foreground_separation: AI_FEATURES_ENABLED is off - the whole package is dormant
        - remove_bg: missing packages: PIL; unset settings: REMOVE_BG_API_KEY
        - rembg_local: missing packages: rembg, onnxruntime, PIL
  [off] vectorisation: AI_FEATURES_ENABLED is off - the whole package is dormant
        - vtracer_local: missing packages: vtracer
        - vectorizer_ai: unset settings: VECTORIZER_AI_API_ID, VECTORIZER_AI_API_SECRET
  Nothing above runs until AI_FEATURES_ENABLED=true - see docs/AI_FEATURES.md.
```

`probe()` returns the same thing as a JSON-safe dict — flags, per-provider readiness, the limits in
force, every resource profile with its basis, and the *names* (never the values) of the variables
that are set. It is safe to paste into a ticket and safe for a future admin route to return
verbatim.

What it deliberately does not tell you: whether a hosted provider is reachable or a key is valid.
That costs a network round trip and possibly a credit, and a probe that spends money is a probe
nobody runs. "Ready" means installed and configured.

---

## 7. How the measured numbers were measured

Both local providers were actually run, in throwaway virtualenvs, on 2026-07-26. Neither venv was
the backend's, and nothing was installed into the repository's environment.

- **Machine:** Intel i5-10300H (4 cores / 8 threads), Windows 11, CPython 3.12.10.
- **Metric:** `PeakWorkingSetSize` from `GetProcessMemoryInfo` for the whole process, sampled after
  each stage. Linux RSS will differ in detail; the order of magnitude is the point.
- **Input:** a synthetic flat-colour motif generated with stdlib `zlib` — deliberately the *easy*
  case for a tracer. A photograph is harder on both time and output size.

**rembg / U2Net**, 1600×1200 (1.9 MP): 35 MB at rest → 167 MB after `import rembg` → 564 MB after
`new_session("u2net")` → **1,032 MB** after one inference. At 3000×2000 (6 MP) the peak was
1,123 MB. Inference itself was 2.0 s then 1.3 s (warm). Import took 4.9 s; the session took 21.3 s
including the 176 MB model download at ~11 MB/s. With `u2netp` (4.7 MB) at 1.9 MP: 198 MB after the
session, **696 MB** after inference, 0.85 s per image.

**VTracer**, three runs per size: 0.5 MP → 48 MB peak, 253–318 ms; 1.9 MP → 111 MB peak,
723–1,086 ms; 6 MP → 273 MB peak, 2.4–2.8 s. Interpreter baseline 27/35/59 MB respectively;
`import vtracer` added nothing measurable. The 6 MP trace produced a **1.1 MB SVG**.

**One bug found while measuring, and worked around in the code:**
`vtracer.convert_raw_image_to_svg` (the bytes API) segfaulted CPython 3.14.6 with vtracer 0.6.15 on
every input tried, including a 64×64 PNG, while the path-based `convert_image_to_svg_py` returned
correct SVG for the same bytes. On CPython 3.12.10 both worked. A segfault cannot be caught, so the
provider uses the path API and writes a temporary file — negligible next to the trace itself.

The hosted providers were **not** measured: that needs an account, and the calls cost credits. Their
RAM and latency figures are labelled ESTIMATED and their pricing VENDOR-STATED for that reason.
Before enabling either, re-check the vendor's current pricing page.

---

## 8. Tests

```bash
cd backend
./.venv/Scripts/python.exe -m pytest tests/test_ai_features.py tests/test_ai_features_providers.py -q
```

55 tests, and they run **with none of the optional dependencies installed** — which is the point.
They assert that every module imports on stdlib alone, that a clean interpreter importing
`app.ai_features` loads no `rembg`/`onnxruntime`/`torch`/`numpy`/`PIL`/`vtracer` (checked in a
subprocess, so an unrelated test cannot pollute the result), that the flags default off, and that
calling a disabled or uninstalled capability raises a typed error naming the variable or package
rather than an `ImportError`.

The hosted providers are exercised end to end against a stub placed in `sys.modules` — possible
only because the real import happens inside the function, so the lazy-import rule buys testability
as well as boot time. Every status code they can return is covered: 402 out of credits, 429 with
`Retry-After`, 403 bad key, timeouts, an unreachable host, a 200 that is not an image, and a
response too large to buffer.

What the automated tests cannot cover: a provider actually producing a good cutout. That needs the
dependency installed and, for the hosted pair, an account. §4 has the one-liner to try it by hand.

Both **local** providers were run by hand through the public entry points on 2026-07-26, in the
same throwaway venvs used for the measurements:

- `vectorise_image()` through `vtracer_local` returned 25 KB of real SVG for a 400×300 motif in
  142 ms.
- `remove_background()` and `separate_foreground()` through `rembg_local` (with `u2netp`) returned
  a PNG cutout and three layers at the source resolution — foreground RGBA, background RGBA, matte
  L. The layer invariants were checked pixel by pixel: the foreground's alpha is exactly the matte,
  the background's alpha is exactly its inverse, and the two layers carry identical RGB.

The hosted providers have never been run against the real services. Their request shape follows the
vendors' documentation and is covered by the stub tests; the first person with an account should
expect to spend ten minutes confirming it.

---

## 9. What is deliberately not done

- **No route, no queue job, no UI.** Nothing calls this package. Wiring it into
  `fieldrepo-queue` and storing derived assets is a separate decision with its own storage and
  cost consequences.
- **No fields in `app/core/config.py`.** The package reads its own settings so that a strictly
  optional feature cannot break the file that must never fail to load. When these features are
  turned on for real, the 23 names in `ai_features/settings.py::ENV_VARS` can be moved into
  `Settings` verbatim — the aliases are already the variable names — and `get_ai_settings()`
  changed to read from it. Every other module in the package goes through that one function, so it
  is a one-file change.
- **No entry in `backend/.env.example`.** This document is the reference until somebody enables a
  capability; adding twenty-three commented-out variables to the template would suggest they are
  expected.
- **No local `potrace` provider.** It is the better tool for pure black-and-white line art, but it
  is an external binary rather than a wheel, and VTracer's `binary` colormode covers the block-print
  case adequately. If the tracing quality on real motifs turns out not to be good enough, potrace
  is the next provider to add: implement the interface in `providers/base.py`, add a descriptor to
  `registry.py`, keep every import inside the method.
