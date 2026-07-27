# CDN and caching

Three separate caches sit in front of this system and they are usually confused with each other.
Most of what follows is about the first one, because it is the only one this repository configures
and the only one that has caused an outage.

| Edge | Fronts | Configured where | Caches today |
| --- | --- | --- | --- |
| **CloudFront** `d2b34i3e92al6i.cloudfront.net` | the **API** — EC2 nginx → uvicorn | AWS console, by hand | **nothing, and must keep caching nothing** |
| **Vercel** | the Next.js frontend | `frontend/next.config.ts`, framework defaults | hashed assets, HTML/RSC |
| **S3** (no CDN) | media objects, the Android APK | presigned URLs from `backend/app/services/s3.py` | nothing; every URL is signed and short-lived |

CloudFront exists here for **TLS and IPv6**, not for caching. It was put in front of the API because
Vercel serves the web app over HTTPS and a plain-HTTP origin is blocked as mixed content, and
because EC2 has no `AAAA` record so IPv6-only mobile networks — ordinary Jio and Airtel data —
cannot reach it at all. Caching was never the reason, and it is not switched on.

- [The origin timeout](#the-origin-timeout-the-one-that-has-already-broken-this-system)
- [Cache-Control by content class](#cache-control-by-content-class)
- [Why the API distribution must not cache](#why-the-api-distribution-must-not-cache)
- [Invalidation runbook](#invalidation-runbook)
- [Verifying what is actually happening](#verifying-what-is-actually-happening)
- [If media ever goes behind a CDN](#if-media-ever-goes-behind-a-cdn)

---

## The origin timeout: the one that has already broken this system

**Field users hit HTTP 504 on media upload. The 504 came from CloudFront, not from the
application, and not from either nginx.**

Three timeouts sit on the request path, and the smallest one wins:

| Hop | Timeout | Set in |
| --- | --- | --- |
| **CloudFront → origin** | **30 s** (the AWS default) | AWS console only. Not in this repo. |
| nginx → uvicorn | 300 s | `infra/terraform/user_data.sh` |
| ingress → Service (k8s) | 300 s | `infra/k8s/base/ingress.yaml` |

Both nginx layers were configured generously and correctly. Neither ever fired. The CDN — the
component nobody thinks of as having a timeout — gave up at 30 s and returned a 504 that named
nothing useful, while the origin was still working on the response.

**Why the origin got slow enough to matter.** The media queue (ffmpeg chunking plus a
speech-to-text call, reading whole files into memory) ran *inside both uvicorn workers* on a
t3.micro. It burned the CPU credits and starved request handling. Two fixes landed: the queue moved
into its own process, and the Android client learned to retry safe requests on 502/503/504 with
backoff (`ApiClient.kt`; record-*creating* calls are deliberately never retried, so a 504 can never
produce a duplicate record).

**What is still not done.** Raising the origin response timeout is a console action:

> CloudFront → Distributions → `d2b34i3e92al6i…` → **Origins** → edit the origin →
> **Additional settings** → **Response timeout**: `30` → `60`.
>
> Also raise **Keep-alive timeout** (default 5 s) to ~30 s. The API is a single-origin distribution
> and every dropped keep-alive costs a fresh TCP and TLS handshake to Mumbai on the next request.
>
> 60 s is the console maximum; above that needs a service-quota increase for
> *Origin response timeout*. If you find yourself wanting 180 s, the answer is not a bigger timeout.

**Why this is still live.** From the measured baseline on an otherwise idle box:

| Endpoint | Median | Fraction of the 30 s budget |
| --- | --- | --- |
| `/api/dashboard/stats` | 10,567 ms | 35% |
| search | 8,920 ms | 30% |
| tools | 4,633 ms | 15% |
| media | 3,074 ms | 10% |
| `/health` | 128 ms | network floor |

A dashboard load already spends a third of the timeout with nobody else using the system. Three
concurrent researchers on a burstable instance is not a hypothetical; it is a Tuesday.

**The rule.** Any endpoint that can legitimately take longer than the origin timeout must not be a
synchronous request. Large media never touches this path at all — it goes **direct to S3** through
presigned URLs precisely so that the bytes never traverse CloudFront. Long exports and
transcriptions belong on the queue, with the client polling. Raising the timeout buys headroom; it
does not make a slow synchronous endpoint safe, it just moves the cliff.

---

## Cache-Control by content class

### 1. Immutable hashed assets — `public, max-age=31536000, immutable`

`/_next/static/**`, served by Vercel, filename contains a content hash. Vercel sets this header
itself and it needs no configuration here.

The property that makes a year-long TTL safe is not the header, it is that **the URL changes when
the bytes change**. Content-addressed assets are never invalidated because there is nothing to
invalidate — the old URL is still correct for the old bytes, and nothing requests it any more.
`immutable` additionally stops the browser from sending a revalidating request on reload, which is
the difference between a fast reload and a round trip per asset.

Never apply this to a URL whose content can change under it. One `max-age=31536000` on a
non-hashed path is a mistake with a one-year blast radius, and invalidating CloudFront does not
reach the copy already in the user's browser.

### 2. API responses — `no-store`, and caching disabled at the distribution

This is the safety rule; the next section explains it. Nothing under `/api/*` may be cached by a
shared cache, ever.

The application already sets this on the two responses where a stale answer would be actively
misleading:

| Route | Header | Why |
| --- | --- | --- |
| `/health/ready` | `cache-control: no-store` | A remembered "ready" is exactly the reporting-success-while-broken failure this endpoint exists to detect. |
| `/api/app-release/download` | `Cache-Control: no-store, max-age=0` | It is a 307 to a short-lived presigned S3 URL. Caching the *redirect* hands the next phone a signature that has already expired. |

Everything else relies on the distribution's cache policy rather than per-route headers, which is
the right place for it: a policy applies to routes that do not exist yet, and a header only protects
the route somebody remembered.

### 3. Media objects — `private`, and never longer than the signature

Media is not behind a CDN today. Every object is reached through a presigned URL:

| Operation | Signature lifetime | `s3.py` |
| --- | --- | --- |
| Upload (`PUT`) | 900 s | `presign_put_url` |
| Multipart part upload | 3600 s | `presign_upload_part` |
| Download (`GET`, forced filename/type) | 900 s default | `presign_get_url` |

Two constraints follow, and both are easy to get backwards:

- **`max-age` must never exceed the remaining signature lifetime.** A cached presigned URL that
  outlives its signature is not a stale response, it is a `403 SignatureDoesNotExist` served
  confidently from cache to a user who did nothing wrong.
- **`private`, never `public`.** These are researcher records — media is scoped by role, workshop
  and grant. A shared cache holding one researcher's audio and serving it on another's request is a
  data breach, not a caching bug.

The APK object is the one piece of media with a different profile: it is polled by the Android
updater and replaced on every release, so it wants a short TTL (`public, max-age=300`) rather than
either extreme.

### 4. HTML and RSC payloads

Vercel's business, configured by Next.js, not by anything here. `frontend/next.config.ts` adds
security headers to every route and does not touch caching.

---

## Why the API distribution must not cache

Every response under `/api/*` is a function of **who is asking**. This system has a six-tier role
ladder, per-workshop scoping, per-record grants, and Aadhaar masking that depends on the caller's
permissions. `GET /api/artisans` returns different bytes to a professor and to a crowdsource
volunteer, from the same URL.

A shared cache keyed on the URL will serve the first one to the second. That is not a stale-data
bug; it is a confidentiality failure, it is silent, and the person affected has no way to notice.

So the distribution's behaviour is configured as:

- **Cache policy: `Managed-CachingDisabled`.** Not "a short TTL". `Authorization` is a request
  header, and a cache policy that does not include it in the key will happily serve one user's
  response to another. Disabled removes the question.
- **Origin request policy: `Managed-AllViewerExceptHostHeader`.** `Authorization`, `Origin` and the
  rest must reach the origin or authentication and CORS both break. `ExceptHostHeader` because the
  origin is an EC2 box that should see its own hostname.
- **Allowed methods: all seven**, including `POST`, `PUT`, `PATCH` and `DELETE`. A GET/HEAD-only
  distribution turns every write into a 403 from the edge.
- **Compress objects automatically: on.** Gzip is free latency on JSON, and it is orthogonal to
  caching.

> Verify these in the console rather than trusting this file. Policy names are stable; the managed
> policy IDs quoted in blog posts are not always the ones your account resolves.

If you ever find yourself invalidating the API distribution, **the invalidation is not the fix** —
something is caching that should not be, and the cache policy is what to look at.

---

## Invalidation runbook

Invalidation applies to CloudFront only. Vercel's hashed assets do not need it (see class 1), and
S3 media is not behind a CDN.

```bash
DIST=<distribution-id>          # aws cloudfront list-distributions --query \
                                #   "DistributionList.Items[].{id:Id,domain:DomainName}"

# 1. CONFIRM SOMETHING IS ACTUALLY CACHED. Do this first — most "cache problems" are not.
curl -sSI https://d2b34i3e92al6i.cloudfront.net/api/health \
  | grep -iE 'x-cache|age|cache-control'
#   X-Cache: Miss from cloudfront   -> nothing is cached; invalidating changes nothing.
#   X-Cache: Hit from cloudfront    -> something is cached. On /api/* that is the bug.

# 2. Invalidate the narrowest path that covers it. Wildcards are fine and cost the same.
aws cloudfront create-invalidation --distribution-id "$DIST" --paths '/api/settings/*'

# 3. Everything, only when you cannot identify the path.
aws cloudfront create-invalidation --distribution-id "$DIST" --paths '/*'

# 4. Wait for it. It is not instant.
aws cloudfront wait invalidation-completed \
  --distribution-id "$DIST" --id <invalidation-id-from-step-2>

# 5. Confirm, from more than one place. An edge in Mumbai and an edge in Frankfurt purge
#    independently, and "it works for me" usually means "my POP purged".
curl -sSI https://d2b34i3e92al6i.cloudfront.net/api/settings | grep -iE 'x-cache|age'
```

**Things worth knowing before you reach for this:**

- It is **not instant** — typically a few minutes, and each edge location purges on its own
  schedule. Do not use it in a deploy pipeline as though it were synchronous.
- It is **not a rollback**. It removes cached copies; it does nothing about the copy already in a
  user's browser under a long `max-age`. Only a URL change reaches that.
- **Cost:** the first 1,000 paths per month are free, then roughly $0.005 per path. `/*` counts as
  one path, so the blunt option is also the cheap one. A CI job that invalidates on every deploy is
  how people end up paying for this.
- **A versioned URL beats an invalidation every time.** Class 1 above is the pattern: change the
  key, not the cache.
- If the answer is "we invalidate after every deploy", the actual problem is that something
  mutable is being served from an immutable-looking URL.

---

## Verifying what is actually happening

```bash
# Which layer is answering, and is anything cached?
curl -sSI https://d2b34i3e92al6i.cloudfront.net/api/health | grep -iE 'x-cache|age|via|x-amz-cf'

# Is the origin timeout the thing biting you? Compare edge and origin for the same endpoint.
# A 504 from the first and a slow 200 from the second is the CloudFront timeout, conclusively.
time curl -sS -o /dev/null -w '%{http_code} %{time_total}s\n' \
  https://d2b34i3e92al6i.cloudfront.net/api/dashboard/stats
time curl -sS -o /dev/null -w '%{http_code} %{time_total}s\n' \
  http://15.207.145.174/api/dashboard/stats

# IPv6, which is half of why this distribution exists. CloudFront answers; EC2 has no AAAA.
curl -sS -6 -o /dev/null -w '%{http_code}\n' https://d2b34i3e92al6i.cloudfront.net/health
```

| Header | Reading it |
| --- | --- |
| `X-Cache: Miss from cloudfront` | Went to the origin. Expected on every `/api/*` request. |
| `X-Cache: Hit from cloudfront` | Served from the edge. On `/api/*`, investigate the cache policy. |
| `Age: <n>` | Seconds this response has sat in the cache. Absent means not cached. |
| `X-Amz-Cf-Pop` | Which edge answered. Different POPs purge independently. |

---

## If media ever goes behind a CDN

The hook already exists and needs no code change: `AWS_S3_PUBLIC_BASE_URL` (see
`s3.py::public_url_for_key`) makes stored media URLs point at any base you give it. Point it at a
CloudFront distribution with an Origin Access Control over the bucket, and stored URLs follow.

Three things make this an easy change rather than a risky one:

1. **The keys are already immutable.** `make_object_key` is
   `media/<user-id>/<uuid4>/<safe-filename>`, so a given key's bytes never change and a key is never
   reused. That is the class-1 property, which means `public, max-age=31536000, immutable` is
   correct and invalidation is never needed for media.
2. **The dual-stack promotion must survive.** `_promote_dualstack` rewrites `s3.<region>` to
   `s3.dualstack.<region>` so media resolves on IPv6-only mobile networks, and it deliberately does
   **not** touch a custom base URL. A CloudFront domain is dual-stack natively, so this is fine — but
   any other CDN must be checked, or media silently stops loading on mobile data while working
   perfectly on office Wi-Fi.
3. **Signed URLs and public caching are mutually exclusive.** Today's access control *is* the
   signature. Fronting the bucket with a public CDN removes it, so the replacement — CloudFront
   signed URLs or signed cookies — must be in place first. `presign_get_url` also carries
   `response-content-disposition` and `response-content-type` overrides, which S3 honours **only on
   a signed request**; anything replacing it has to reproduce those, or downloads arrive with the
   wrong filename and the wrong type.

---

## See also

- [docs/KUBERNETES.md](KUBERNETES.md) — the ingress mirrors these timeouts; §"Differences from the EC2 deployment".
- [docs/MEDIA_PIPELINE.md](MEDIA_PIPELINE.md) — presign, multipart, and the client retry policy.
- [docs/DEPLOYMENT_VERCEL.md](DEPLOYMENT_VERCEL.md) — §7.7 for the upload-timeout symptom.
- [docs/SECURITY.md](SECURITY.md) — risk P1: the CloudFront→origin hop is plaintext HTTP.
- [backend/DEPLOY_AWS.md](../backend/DEPLOY_AWS.md) — how the distribution was created.
