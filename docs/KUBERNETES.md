# Kubernetes

**Nothing in `infra/k8s/` is running. Production is one EC2 t3.micro behind nginx behind
CloudFront, deployed by `.github/workflows/deploy-backend.yml`, and this directory changes none of
that.** These manifests exist so that the move is a reviewable decision rather than a weekend of
guessing. They are written to be applied as-is; they have never been applied.

- [What is here](#what-is-here)
- [The connection ceiling](#the-connection-ceiling-read-this-one)
- [Layout and the three overlays](#layout-and-the-three-overlays)
- [Images](#images-two-of-them)
- [Secrets](#secrets)
- [Probes: why all three hit `/health`](#probes-why-all-three-hit-health)
- [Draining without cutting an upload](#draining-without-cutting-an-upload)
- [Migrations](#migrations)
- [Deploy runbook](#deploy-runbook)
- [What was validated, and what was not](#what-was-validated-and-what-was-not)
- [Differences from the EC2 deployment](#differences-from-the-ec2-deployment-that-would-bite)

---

## What is here

| File | What it is |
| --- | --- |
| `base/configmap.yaml` | Non-secret configuration. Tracks `backend/app/core/config.py`. |
| `base/externalsecret.yaml` | Which keys to pull from AWS Secrets Manager. No values, ever. |
| `base/deployment-api.yaml` | The API. One uvicorn worker per pod. |
| `base/deployment-queue.yaml` | The media/transcription queue. **Exactly one pod, permanently.** |
| `base/service.yaml` | ClusterIP for the API. The queue gets none — it serves no HTTP. |
| `base/ingress.yaml` | nginx ingress. Body size and timeouts mirror the EC2 nginx site. |
| `base/hpa.yaml` | The autoscaler, whose ceiling is a database budget. |
| `base/pdb.yaml` | PodDisruptionBudget for the API. Explains why the queue has none. |
| `base/job-migrate.yaml` | `prisma migrate deploy`, once per release. |
| `base/kustomization.yaml` | Ties the base together. Not deployable alone, by design. |
| `overlays/{dev,staging,prod}/` | The three environments. One `kustomization.yaml` each. |

Build any of them:

```bash
kubectl kustomize infra/k8s/overlays/dev      # render
kubectl apply -k  infra/k8s/overlays/dev      # apply
```

---

## The connection ceiling (read this one)

This is the constraint everything else is shaped around, because the project has already lost
production to it twice.

**What happened.** Prisma opens a connection pool per process. The EC2 box ran `--workers 2`, each
worker with `connection_limit=40`, and a supervisor that SIGKILLed a worker whose health-ping was
starved by transcription work — orphaning its query engine, one per kill cycle, each holding its
pool open. The Supabase pooler hit its client ceiling and returned `FATAL: (EMAXCONN) max client
connections reached`. Every database call 500'd. Login stopped working. `/health` stayed green the
whole time, because it does not touch the database. The fix was `connection_limit` 40 → 10, one web
worker, and a separate queue process.

**Why Kubernetes makes it worse before it makes it better.** Replicas multiply pools. An HPA that
answers load by adding replicas is, from the pooler's point of view, an automated attack on the
resource that is already exhausted — and it fails in the one direction it cannot recover from: more
pods → more connections → more failures → more load → more pods. **An autoscaler that scales into
connection exhaustion is worse than no autoscaler**, because the un-scaled version stays up and
merely gets slow.

**The ceiling.** 200 client connections, Supabase, per project, shared by everything — every pod,
every environment, every `psql`, the Studio tab you left open.

**The formula.** Every environment must satisfy:

```
(maxReplicas + 1 rollout surge + 1 queue pod) x DATABASE_CONNECTION_LIMIT   <=   its share
```

`+1 surge` is why `maxSurge` is pinned to the absolute `1` and never a percentage: a percentage
would make peak connection use scale with replica count precisely when the cluster is busiest.

**The whole budget, assuming the worst — all three environments against one Supabase project:**

| Consumer | Arithmetic | Connections |
| --- | --- | --- |
| prod API at ceiling | `6 x 10` | 60 |
| prod rollout surge | `1 x 10` | 10 |
| prod queue | `1 x 10` | 10 |
| staging (max 2) | `(2 + 1 + 1) x 5` | 20 |
| dev (pinned at 1) | `(1 + 1 + 1) x 5` | 15 |
| migration Job, transient | session pooler | 5 |
| EC2 box, if still serving | `(1 web + 1 queue) x 10` | 20 |
| **Worst case, everything at once** | | **140 / 200** |

60 spare. That is not slack to spend — it is the several minutes a failed rollout spends with surge
pods that never became ready, plus whoever is holding a `psql` session open trying to work out why.

**To change `maxReplicas`, change the product, not the number.** Going to 12 prod replicas requires
cutting `DATABASE_CONNECTION_LIMIT` to 5 *in the same commit*. This costs far less throughput than
it looks like it should: real query concurrency is gated by the pooler's ~15 **server** connections
no matter how many **client** connections are held, so a smaller per-pod pool mostly removes idle
reservations. That is the same trade that resolved the incident.

**Before adding a fourth environment, redo the table above.** It is currently at 70% of the hard
ceiling. It does not have room for a copy of staging.

---

## Layout and the three overlays

The base holds only what is true everywhere. Everything an environment could reasonably want
different is in that environment's `kustomization.yaml` — as an inline patch, so the difference
between two environments is one file you can read top to bottom rather than three near-identical
trees to compare by eye.

|  | dev | staging | prod |
| --- | --- | --- | --- |
| Namespace | `fieldrepo-dev` | `fieldrepo-staging` | `fieldrepo-prod` |
| `DATABASE_CONNECTION_LIMIT` | 5 | 5 | **10** |
| HPA min → max | 1 → 1 | 1 → 2 | 2 → 6 |
| Worst-case connections | 15 | 20 | 80 |
| `BACKEND_EXPOSE_DOCS` | **true** | false | false |
| `SECURITY_FORCE_HSTS` | false | true | true |
| Zone spread | ScheduleAnyway | ScheduleAnyway | **DoNotSchedule** |
| API requests | 100m / 256Mi | 150m / 320Mi | 200m / 384Mi |
| Ingress TLS | none | `letsencrypt-staging` | `letsencrypt-prod` |
| Secrets prefix | `fieldrepo/dev/*` | `fieldrepo/staging/*` | `fieldrepo/prod/*` |

Two things are **not** in that table because no overlay may change them:

- **The queue is one pod.** Not a starting point. `deployment-queue.yaml` uses `Recreate` rather
  than `RollingUpdate` for the same reason — a rolling update deliberately overlaps old and new,
  which is the two-workers state, and briefly is long enough to double-process a job and pay a
  speech-to-text provider twice for it.
- **One uvicorn worker per pod.** Workers are processes; each opens its own pool. In Kubernetes,
  replicas are the unit of concurrency and `--workers` is a way to make the budget above wrong by
  an integer factor nobody wrote down.

### The two `replacements` blocks

Each overlay ends with two `replacements` stanzas that look like machinery and exist to prevent
copy-paste drift:

1. `FIELDREPO_ENV` (one string in the ConfigMap patch) is substituted into the environment segment
   of all eleven remote secret paths in `base/externalsecret.yaml`. Without it every overlay would
   carry its own copy of all eleven, and the day a twelfth key is added, two of three copies get it.
   `FIELDREPO_ENV` is not read by the application — `Settings` is `extra="ignore"`.
2. The migrate image's tag is substituted into the migration Job's **name**, so each release gets a
   distinctly named Job. See [Migrations](#migrations).

If you switch prod to digest pinning, replacement 2 must change: it recovers the tag by splitting
the image reference on `:`, and a `@sha256:` digest has no tag in it.

---

## Images: two of them

```bash
# API and queue — the lean runtime image.
docker build -f backend/Dockerfile                 -t <registry>/fieldrepo-api:<tag> .
# Migrations — the intermediate stage that still has Node.
docker build -f backend/Dockerfile --target prisma -t <registry>/fieldrepo-migrate:<tag> .
```

Both build **from the repository root**, not from `backend/`.

**Why a second image.** `python -m prisma migrate deploy` is a thin wrapper that execs the Prisma
CLI, and the Prisma CLI is a Node program (`prisma/cli/prisma.py` → `node.run`). `backend/Dockerfile`
deliberately keeps Node out of the runtime stage. In that image the call falls through to
prisma-client-py's `nodeenv` fallback, which tries to *download a Node distribution at migration
time* into a cache directory — on a pod with a read-only root filesystem and no route to the npm
registry, that fails slowly and with a message about nodeenv rather than about Node being absent.
The Dockerfile's `prisma` stage already has Node, the CLI cache, the venv and `backend/prisma`, so
it is the migration image and no Dockerfile change is needed.

**Never `:latest`.** A restarted pod must come back as the same build as its neighbours. Prod should
pin `digest:` rather than `newTag:`; a tag can be re-pushed, and then a pod that restarts at 3am is
a different build with nothing in the cluster recording that it happened.

---

## Secrets

**There is no `kind: Secret` in this repository and there must never be one.** A core/v1 Secret is
base64, not encryption; committing one publishes it, and `git rm` does not unpublish it.

The cluster gets values from **AWS Secrets Manager** via the **External Secrets Operator**.
`base/externalsecret.yaml` names the keys; each overlay's `secretstore.yaml` says where from and as
whom. AWS rather than SOPS/Sealed Secrets/Vault because the deployment is already on AWS — no new
vendor, no new key-distribution story, and IRSA means the pod assumes a role and there is no
bootstrap credential sitting in the cluster to leak.

Once per cluster:

```bash
helm repo add external-secrets https://charts.external-secrets.io
helm install external-secrets external-secrets/external-secrets \
  -n external-secrets --create-namespace
```

Then, per environment, an IAM role trusted by the cluster's OIDC provider with
`secretsmanager:GetSecretValue` on `arn:aws:secretsmanager:<region>:<account>:secret:fieldrepo/<env>/*`
**and nothing above that prefix** — that scoping is the control that keeps dev out of production's
credentials, and it is the reason each namespace gets its own `SecretStore` rather than one
cluster-wide `ClusterSecretStore`.

The source of truth for the values is the file the EC2 deploy already uses: the `BACKEND_ENV`
GitHub secret. Migrating is copying it into one Secrets Manager entry per key.

**Rotation does not restart pods.** `envFrom` is read once at process start. After rotating
anything:

```bash
kubectl -n fieldrepo-prod rollout restart deployment/fieldrepo-api deployment/fieldrepo-queue
```

**Set `SECRETS_ENCRYPTION_KEY` explicitly before you ever rotate `JWT_SECRET`.** Unset, it is
derived from `JWT_SECRET`, so rotating that first makes every provider key stored in `ManagedSecret`
undecryptable and each one has to be re-entered by hand in the Settings hub.

---

## Probes: why all three hit `/health`

**The path is `/health`, not `/api/health`.** Both health routes are registered directly on the
FastAPI app in `app/main.py`, outside the `APIRouter` that carries the `/api` prefix. Verified
against production: `/health` → `200 {"status":"ok"}`; `/api/health` → `404`. Probing the wrong one
marks a healthy pod unhealthy forever.

Both endpoints exist and they answer different questions:

| | Touches the DB | Meaning |
| --- | --- | --- |
| `GET /health` | no | "this process is serving requests" |
| `GET /health/ready` | yes, `SELECT 1` with a deadline | `200 {"status":"ready","database":true,"latencyMs":…}` / `503` |

**Startup, liveness and readiness all use `/health`, and the readiness one is the deliberate
choice.** `/health/ready` is the right thing for *alerting* and the wrong thing to gate *traffic*
on in this topology: every replica talks to the same pooler, so a pooler blip fails readiness on all
of them in the same instant, the Service loses every endpoint, and the ingress starts refusing
requests the pods could still have served. The application is written to survive exactly that window
— it keeps serving while a background watchdog reconnects (`_keep_db_connected`) — and CloudFront is
pointed at `/health` for the same reason. Wiring readiness to `/health/ready` would rebuild, inside
the cluster, the outage the origin check was shaped to avoid.

If you want it anyway — for a single-replica dev cluster, say, where the tradeoff reverses:

```yaml
# overlays/dev/kustomization.yaml, in the fieldrepo-api patch
readinessProbe:
  httpGet:
    path: /health/ready
    port: http
  periodSeconds: 10
  timeoutSeconds: 5      # the endpoint has its own ~3s internal deadline
  failureThreshold: 3
```

The **startup** probe is what makes the liveness probe safe: 30 × 5s = 150s of grace while the
Prisma engine spawns and dials a pooler in another AWS region, and liveness does not begin until it
passes. A short liveness probe with no startup probe kills a slow boot and calls it a hang — and
because a cold start is slowest exactly when the pooler is busiest, that turns a slow database into
a crash loop.

`/health/ready` sends `cache-control: no-store`. Point uptime alerting at it, and alert on **rising
`latencyMs`**, not only on failure: the documented first symptom of pool exhaustion is a probe that
still succeeds but takes seconds. That alert fires while there is still time to act.

---

## Draining without cutting an upload

Four settings, and they only work together:

- **`terminationGracePeriodSeconds: 120`** (API). The EC2 nginx allows `proxy_read_timeout 300s`,
  which is the real upper bound on a legitimate request; 120s covers everything observed while
  bounding how long a node drain waits.
- **`terminationGracePeriodSeconds: 300`** (queue). This process can be mid-transcription, and the
  loop only checks for shutdown between jobs.
- **`preStop: sleep 15`** (API). Endpoint removal and pod shutdown happen **in parallel, not in
  order**. Without the pause uvicorn starts refusing connections while the ingress is still routing
  to it, which shows up as a handful of failed uploads on every single deploy. This uses the native
  `sleep` action (Kubernetes ≥ 1.30) rather than `exec: sleep 15`, because the image may ship no
  shell and an exec hook that cannot find `/bin/sleep` fails silently. On older clusters:
  `exec: { command: ["/bin/sleep", "15"] }`.
- **`maxUnavailable: 1`** in the PDB — **not `minAvailable: 1`**. On a workload that can sit at one
  replica, `minAvailable: 1` is a deadlock: the budget says one pod must survive, there is one pod,
  so the eviction API refuses forever and `kubectl drain` blocks until somebody deletes the PDB. Dev
  runs one replica and the base HPA's floor is 1, so this is the normal state, not a corner case.
  `unhealthyPodEvictionPolicy: AlwaysAllow` (≥ 1.27) covers the other half: without it a PDB blocks
  eviction of Running-but-not-Ready pods, so a crash-looping rollout makes its node undrainable
  precisely when you most need to drain it.

The queue Deployment gets **no** PDB — for a hard singleton the only budget that could protect it is
the deadlocking one, and nothing waits synchronously on a queue worker. Its 300s grace period is the
real protection, and it is a property of the pod rather than a promise extracted from the eviction
API.

Involuntary disruption — node dies, OOM kill — ignores PDBs entirely. The defence there is the
topology spread: `kubernetes.io/hostname` everywhere so replicas do not all land on one node, and
`topology.kubernetes.io/zone` promoted to `DoNotSchedule` in prod, where a single-AZ outage is the
scenario in which "the scheduler tried" and "the service stayed up" are different outcomes. Both
constraints carry `matchLabelKeys: [pod-template-hash]` (≥ 1.27) — without it the constraint counts
pods from the outgoing ReplicaSet, so a rollout measures skew against replicas that are about to
disappear and can wedge itself.

> **If your cluster is single-zone**, the prod `DoNotSchedule` constraint leaves every pod after the
> first `Pending`, forever, with a clear message. Change it back rather than deleting the
> constraint.

---

## Migrations

`base/job-migrate.yaml` runs `prisma migrate deploy`. **It is a Job, not an initContainer**, and the
distinction is the requirement.

An initContainer runs once per **pod**: three replicas run three concurrent migrations, and a
rolling update runs more on every surge pod while the previous version is still serving. Prisma
would mostly survive that by blocking on a lock — but a design that depends on a lock to save it
from a race it created on purpose is not a design.

Three independent layers keep it single-writer, because one is not enough:

1. **Job semantics.** `completions: 1`, `parallelism: 1`.
2. **Prisma's own Postgres advisory lock.** `migrate deploy` takes it before touching
   `_prisma_migrations`, so a second migrator waits rather than interleaving. This covers the hole
   layer 1 does not: when a node goes unreachable the Job controller may create a replacement while
   the original is still, from the database's point of view, alive and mid-statement.
   **This is why the Job uses `DATABASE_URL` untouched.** An advisory lock is scoped to a *session*,
   and pgbouncer in transaction mode hands a different server session to every statement — through
   the `:6543` pooler the lock would be taken and dropped between statements and protect nothing.
   The secret holds the session-pooler URL; `app/core/db.py` rewrites it to `:6543` for the runtime
   client only, and the Job's container deliberately gets no `envFrom` of the app ConfigMap so that
   nobody helpfully adds `DATABASE_USE_TRANSACTION_POOLER` to it.
3. **One Job object per release.** `metadata.name` inherits the image tag via `replacements`, so
   `kubectl apply -k` twice in a row is a no-op rather than an error about the immutable Job
   template, and two releases can never share one Job object where the second silently does nothing
   because the first is `Complete`.

`ttlSecondsAfterFinished: 3600` reaps the object an hour after it finishes; `activeDeadlineSeconds:
600` fails a migration that is not slow but blocked — almost always on that advisory lock, held by
something nobody knew was running. `backoffLimit: 2`, not the default 6: a migration fails either
transiently (no pooler session free — the EMAXCONNSESSION the EC2 script retries five times) or
permanently (the SQL is wrong, and five more attempts against a half-applied schema is the last
thing anyone wants).

**Nothing makes the API wait for the Job.** Kubernetes has no ordering primitive between a Job and a
Deployment; the runbook below does the waiting. Additive migrations make that forgiving. A
destructive one does not — for those, scale the API to zero first.

**One thing the EC2 deploy has to do that this does not:** stop the application before migrating.
There, uvicorn and the schema engine both draw from the *session* pooler, so the app had to release
its sessions first. Here the runtime client is rewritten to the transaction pooler and only the Job
uses session mode, so they are not competing for the same server-side pool. They still share the
200-client ceiling, which is why the Job is counted in the budget above.

---

## Deploy runbook

```bash
ENV=prod
NS=fieldrepo-$ENV
REL=2026.07.26-1      # short: the Job name is fieldrepo-migrate-$REL and must stay under 63 chars

# 0. Preflight. Never skip: a rendered diff is the last chance to see a wrong bucket or an
#    unreplaced REPLACE_ before it reaches a cluster.
kubectl kustomize infra/k8s/overlays/$ENV | grep -n 'REPLACE'   # must print nothing
kubectl diff -k infra/k8s/overlays/$ENV || true

# 1. Build and push both images at the same tag.
docker build -f backend/Dockerfile                 -t $REGISTRY/fieldrepo-api:$REL .
docker build -f backend/Dockerfile --target prisma -t $REGISTRY/fieldrepo-migrate:$REL .
docker push $REGISTRY/fieldrepo-api:$REL && docker push $REGISTRY/fieldrepo-migrate:$REL

# 2. Apply. This creates the migration Job as well as updating the Deployments; the Deployments
#    will not become ready before step 3 finishes anyway, because they are slower to roll.
kubectl apply -k infra/k8s/overlays/$ENV

# 3. WAIT FOR THE MIGRATION before trusting the rollout.
kubectl -n $NS wait --for=condition=complete --timeout=10m job/fieldrepo-migrate-$REL \
  || { kubectl -n $NS logs job/fieldrepo-migrate-$REL --tail=100; exit 1; }

# 4. Watch the rollout. progressDeadlineSeconds is 600, so this returns either way.
kubectl -n $NS rollout status deployment/fieldrepo-api --timeout=10m
kubectl -n $NS rollout status deployment/fieldrepo-queue --timeout=10m

# 5. Verify the thing that actually matters, not just that pods are Running.
kubectl -n $NS run smoke --rm -it --restart=Never --image=curlimages/curl -- \
  curl -fsS http://fieldrepo-api/health/ready
```

**Rollback** is `kubectl -n $NS rollout undo deployment/fieldrepo-api`. Note it rolls back *code*,
not *schema* — `prisma migrate deploy` has no down migrations. Old code against a newer schema is
fine for additive changes and is not fine for destructive ones, which is the whole reason to keep
migrations additive and drop columns a release later.

**First apply on a fresh cluster**, in order: install the External Secrets Operator; create the IAM
role and the Secrets Manager entries; `kubectl apply -k` the overlay; confirm the Secret
materialised before wondering why pods are stuck:

```bash
kubectl -n $NS get externalsecret fieldrepo-secrets   # STATUS should be SecretSynced
kubectl -n $NS describe externalsecret fieldrepo-secrets
```

Pods sitting in `CreateContainerConfigError` almost always mean the ExternalSecret has not synced —
`kubectl apply` sends every object at once and does not order them.

### When something is wrong

| Symptom | First thing to check |
| --- | --- |
| Pods `CreateContainerConfigError` | ExternalSecret has not synced. `kubectl describe externalsecret`. |
| `CrashLoopBackOff`, logs mention `REFUSING TO START` | The image entrypoint's remote-database guard. `ALLOW_REMOTE_DATABASE` is missing from the ConfigMap. |
| `CrashLoopBackOff`, logs mention `AWS_S3_BUCKET`/`MASTER_ADMIN_EMAIL` | The overlay left a `REPLACE_IN_OVERLAY` in place. Step 0 catches this. |
| Login 500s, `/health` still 200 | **The connection ceiling.** Check `maxReplicas × DATABASE_CONNECTION_LIMIT` against the table above, then look for orphaned engines and other environments. |
| Pods `Pending` in prod | Single-zone cluster meeting `DoNotSchedule`. See the note above. |
| A few uploads fail on every deploy | The preStop pause is too short for your ingress's endpoint propagation. Raise it *and* the grace period together. |
| Transcripts duplicated, provider bill doubled | Two queue pods. `kubectl get pods -l app.kubernetes.io/component=queue` must return exactly one. |
| `kubectl drain` hangs | A PDB. If someone changed `maxUnavailable` to `minAvailable`, that is why. |

---

## What was validated, and what was not

Validated on 2026-07-26, on this machine:

```
kubectl v1.36.1 (bundled Kustomize v5.8.1); kubeconform v0.7.0
```

- `kubectl kustomize` builds cleanly for `base` and all three overlays.
- `kubeconform -strict -kubernetes-version 1.31.0`, with the datreeio CRD catalog supplying the
  External Secrets schemas, over all three rendered overlays:
  **12 resources each, Valid: 12, Invalid: 0, Errors: 0, Skipped: 0.** Nothing was skipped, so the
  `ExternalSecret` and `SecretStore` CRDs were schema-checked too, not waved through.
- The `replacements` were checked in the rendered output: `fieldrepo/dev|staging|prod/*` on all
  eleven secret keys, and Job names `fieldrepo-migrate-dev` / `-staging` / `-REPLACE_RELEASE`.
- No `kind: Secret`, no `stringData`, and no credential-shaped string anywhere in the rendered
  output.

**Not validated, because there is no cluster reachable from here:**

- Nothing has been applied. No pod has ever started from these manifests.
- Admission-time checks — quotas, the `restricted` Pod Security Standard, webhooks — are unexercised.
- **The probes have never been run against a container.** The *paths* are verified against
  `app/main.py` and against production; the *timings* are reasoned from the measured baseline, not
  observed under a cold start in a pod.
- **`readOnlyRootFilesystem: true` is untested against a built image.** It should hold —
  `PYTHONDONTWRITEBYTECODE` is set in the image, `TMPDIR`/`HOME`/`XDG_CACHE_HOME` are pointed at the
  `/tmp` emptyDir, and prisma-client-py's runtime path reads the engine from `/opt/prisma-engines`
  without writing — but "should" is not "did". Expect this to be the first thing that fails on a
  real cluster, and expect it to fail loudly.
- **The migration Job's image has never been built or run.** In particular, the `prisma` stage runs
  as root and this Job forces `runAsUser: 10001`; if the cached engine binaries are not
  world-executable the Job will fail immediately with `EACCES`. That is a loud, obvious failure, not
  a silent one, but it has not been ruled out.
- The `maxReplicas` values are derived from the connection budget, not from a load test. The budget
  is the binding constraint; the *capacity* those replicas provide is unmeasured.

---

## Differences from the EC2 deployment that would bite

- **The queue's single-worker election does not survive the move, and silently.** On EC2 it is an
  `fcntl` lock on a file in the host temp directory (`app/main.py::_acquire_queue_worker_lock`),
  which elects one worker among processes *sharing a filesystem*. Pods do not share `/tmp`, so the
  lock is granted in every pod and elects nobody. `MEDIA_QUEUE_WORKER_ENABLED` therefore defaults to
  `false` in the ConfigMap **and** is pinned `false` at the container level in
  `deployment-api.yaml`, where no ConfigMap edit can turn it back on; the queue Deployment is the
  one place it is `true`. Note the application default is `true`, so *omitting* the key is not the
  same as setting it false.
  The real fix, if the queue ever needs to scale, is a database-level claim
  (`SELECT … FOR UPDATE SKIP LOCKED`, or a lease column) in the queue service — not more replicas.
- **`--host 0.0.0.0`, unlike the EC2 unit's `127.0.0.1`.** There the loopback bind is what stops
  anyone reaching uvicorn without passing through nginx. Here the equivalent boundary is the pod
  network and the Service, and a loopback bind would simply fail every probe.
- **The image entrypoint refuses remote databases.** `docker/backend/entrypoint.sh` exists so nobody
  can hand a throwaway container the production connection string. Supabase is remote by definition,
  so all three overlays would be stopped dead. It is *answered*, not bypassed:
  `ALLOW_REMOTE_DATABASE: "yes-i-mean-it"` in the ConfigMap, and the containers set `args` rather
  than `command` so the entrypoint still runs. Setting `command` would have skipped the script
  entirely — silently, with nothing failing — and lost its startup line naming the database host the
  pod actually attached to, which is the fastest way to catch an overlay pointed at the wrong
  project.
- **The ingress's body size and timeouts mirror `infra/terraform/user_data.sh`** (`200m`, `300s`).
  If the two ever disagree, uploads fail at whichever is smaller with a 413 or 504 that names
  neither. Note that neither is the timeout that has actually bitten this project — see
  [docs/CDN.md](CDN.md).
- **The application sets its own CORS and security headers.** `enable-cors: "false"` on the ingress
  is load-bearing: two `Access-Control-Allow-Origin` values make browsers reject the response, which
  looks exactly like the API being down and reproduces only in a browser.
- **The frontend is not here.** It is on Vercel and stays there; this ingress fronts the API only.

## See also

- [docs/CDN.md](CDN.md) — CloudFront cache classes, invalidation, and the 30s origin timeout.
- [docs/ENVIRONMENT.md](ENVIRONMENT.md) — every environment variable, with defaults.
- [docs/SECURITY.md](SECURITY.md) — the trust boundaries these manifests inherit.
- [backend/DEPLOY_AWS.md](../backend/DEPLOY_AWS.md) — the EC2/S3/CloudFront deployment in use today.
- [docs/CI.md](CI.md) — the GitHub Actions pipeline that deploys it.
