# API origin lock: the box answers CloudFront and nothing else

Done on 2026-10-17, live, with the owner's go-ahead ("do everything that is required for both the
servers, do it now"). The sibling designer portal was locked the same way the same day.

## Why

The API box sat on the open internet: port 80 (and 443, on which nothing listens) open to
`0.0.0.0/0`. Anyone who knew the address could skip CloudFront and talk to nginx directly, and
then put whatever they liked in `X-Forwarded-For`. That makes the client-address hop count in
`backend/app/scale/rate_limit.py` (`TRUSTED_PROXY_HOPS`) a guess about who sent the request.
With the box reachable only through this portal's CloudFront distribution, the chain is fixed:

```
browser / app  ->  CloudFront (appends the viewer's address)  ->  nginx (appends the CloudFront edge)  ->  uvicorn
```

so the client is always the **2nd entry from the right**, and `TRUSTED_PROXY_HOPS=2` is exact.

## What is in place

Two independent locks; either one alone would stop a direct caller.

| Lock | Where | What it does |
|---|---|---|
| Security group | `sg-06f188c79829d38d7` (`cloudfront-origin-only-documentation`) | Port 80 only from the AWS-managed prefix list `pl-9aa247f3` (`com.amazonaws.global.cloudfront.origin-facing`). That list weighs about 55 rules against the 60-per-group quota, which is why it has a group of its own. |
| Origin header | CloudFront `E1VULEK6B74RRA`, origin `ec2-origin` | Sends `X-Origin-Verify: <secret>` on every request to the box. |
| nginx check | `/etc/nginx/snippets/origin-verify.conf`, included by the `fieldrepo` site | 403 unless `X-Origin-Verify` matches; loopback (`127.0.0.1`) is let through for on-box checks. |

The prefix list on its own would still admit *somebody else's* CloudFront distribution pointed at
this address. The header is what makes it this portal's distribution.

**The secret** is the Actions secret `ORIGIN_VERIFY_SECRET` (48 letters and digits). It is also on
the box as `ORIGIN_VERIFY_SECRET=` in `/home/ubuntu/app/backend/.env`, and in the CloudFront origin
config. The API itself does not read it.

**It survives a redeploy and a rebuild.** `deploy-backend.yml`'s step "Require CloudFront's origin
header in nginx" rewrites the snippet and the `.env` line from the secret on every deploy (it fails
the deploy if the secret is unset, and puts the old config back if `nginx -t` refuses the new one).
`infra/terraform/user_data.sh` writes the include plus a fail-closed snippet (`allow 127.0.0.1;
deny all;`), so a freshly built box turns everyone away until its first deploy. `infra/terraform/main.tf` declares
the new group and drops the open 80/443 rules. This repository holds no Terraform state, so that is
documentation of the live estate rather than something that was applied.

TLS: CloudFront talks to this origin over plain HTTP (`http-only`), so the box holds no certificate
and there is no Let's Encrypt renewal to keep alive.

## Ids

| Thing | Id |
|---|---|
| Instance | `i-012eae3e379fbbeac` (`fieldrepo-api`, t3.small, Elastic IP `15.207.145.174`) |
| Old group (kept, still attached) | `sg-0a3ffc8a00c9246f1` (`fieldrepo-api`) |
| New group | `sg-06f188c79829d38d7` (`cloudfront-origin-only-documentation`) |
| Prefix list | `pl-9aa247f3` |
| Distribution | `E1VULEK6B74RRA` (`d2b34i3e92al6i.cloudfront.net`) |
| VPC | `vpc-0010b4077934ea06d` |

The stopped rollback box `i-06f177db5c4e3b0af` (`fieldrepo-api-2404-stopped`) shares the old group
and therefore lost its open 80/443 too. If it is ever started again, attach the new group to it as well
(and point the distribution's origin at it, as before).

## Before and after

| | Before | After |
|---|---|---|
| `sg-0a3ffc8a00c9246f1` inbound | 22, 80, 443 from `0.0.0.0/0` | nothing standing; 22 from the deploying runner's `/32` during a deploy (since 2026-10-17) |
| `sg-06f188c79829d38d7` inbound | did not exist | 80 from `pl-9aa247f3` |
| Origin custom headers | none | `X-Origin-Verify` |
| nginx | proxies everything | 403 without the header |

Verified on 2026-10-17 (status codes):

| Request | Before | After the nginx check | After the security group |
|---|---|---|---|
| `https://d2b34i3e92al6i.cloudfront.net/health` | 200 | 200 | 200 |
| `POST …cloudfront.net/api/auth/login`, wrong password | 401 | 401 | 401 |
| `/health/ready` and a CORS preflight through CloudFront | 200 | 200 | 200 |
| `http://15.207.145.174/health`, no header | 200 | 403 | no answer (timeout) |
| the same with a guessed `X-Origin-Verify` | 200 | 403 | no answer (timeout) |
| `https://15.207.145.174/` | no answer | no answer | no answer |

Nothing else called the box directly: the web app and both Android apps use the CloudFront URL, and
the deploy's health checks run on the box against `127.0.0.1:8000`, which skips nginx.

**The deploy checks CloudFront, not just the box.** After writing the snippet and reloading nginx,
"Require CloudFront's origin header in nginx" asks `https://d2b34i3e92al6i.cloudfront.net/health` (through CloudFront)
for up to two minutes. Loopback is exempt from the check, so only that answer proves the Actions
secret equals the CloudFront header. Anything but 200 (a mismatch shows as 403) puts the previous
nginx config back and fails the deploy before the new code is switched in.

## Port 22

Since 2026-10-17 `sg-0a3ffc8a00c9246f1` has no standing SSH rule (it was open to `0.0.0.0/0`).
`deploy-backend.yml` opens 22 for its runner's `/32` at the start of the job and closes it in an
`if: always()` step at the end, as the IAM user `fieldrepo-ci-sg` (Actions secrets
`DEPLOY_SG_AWS_ACCESS_KEY_ID`, `DEPLOY_SG_AWS_SECRET_ACCESS_KEY`, `DEPLOY_SG_ID`), which may only add
and remove rules on that one group. People reach the box with
`aws ssm start-session --target i-012eae3e379fbbeac`. To put the old rule back in an emergency:
`aws ec2 authorize-security-group-ingress --group-id sg-0a3ffc8a00c9246f1 --protocol tcp --port 22 --cidr <your ip>/32`.

`infra/terraform/imports.tf` adopts the live groups, instance, Elastic IP, bucket and IAM users, so
`terraform plan` shows the live account rather than a fresh one. Read-only on 2026-10-17 it planned
13 imports and one create (a media access key, which cannot be imported; the user already has two,
so an apply stops there).

## Changing the secret

Do it without a 403 window. Have the box accept both values while CloudFront switches over:

1. Generate 48 letters and digits. On the box (SSM), add a second line to
   `/etc/nginx/snippets/origin-verify.conf`:
   `if ($http_x_origin_verify = "<new>") { set $origin_verified 1; }`, then
   `nginx -t && systemctl reload nginx`.
2. Put `<new>` in the distribution's `X-Origin-Verify` origin header and wait for `Deployed`.
3. `gh secret set ORIGIN_VERIFY_SECRET` with `<new>`, then run "Deploy backend to EC2". It rewrites the
   snippet with the new value only and replaces the `.env` line.

## Rollback

In this order (open the box first, then stop sending the header):

```bash
# 1. Re-open port 80/443 on the old group and detach the new one
aws ec2 authorize-security-group-ingress --region ap-south-1 --group-id sg-0a3ffc8a00c9246f1 \
  --ip-permissions 'IpProtocol=tcp,FromPort=80,ToPort=80,IpRanges=[{CidrIp=0.0.0.0/0,Description="HTTP (nginx)"}]' \
                   'IpProtocol=tcp,FromPort=443,ToPort=443,IpRanges=[{CidrIp=0.0.0.0/0,Description="HTTPS (nginx)"}]'
aws ec2 modify-instance-attribute --region ap-south-1 --instance-id i-012eae3e379fbbeac --groups sg-0a3ffc8a00c9246f1
aws ec2 delete-security-group --region ap-south-1 --group-id sg-06f188c79829d38d7

# 2. Drop the nginx check (as root on the box, via SSM). Reload, never restart.
sed -i '/snippets\/origin-verify.conf/d' /etc/nginx/sites-available/fieldrepo
nginx -t && systemctl reload nginx

# 3. Remove the X-Origin-Verify header: get-distribution-config, delete it from
#    Origins.Items[0].CustomHeaders, update-distribution --if-match <ETag>.
```

Before re-deploying after a rollback, remove the "Require CloudFront's origin header in nginx" step
from `deploy-backend.yml` too, or it puts the check straight back. Then set `TRUSTED_PROXY_HOPS`
back to 1, because without the lock the 2nd-from-right entry is caller-controlled.

## How this document is kept true

Everything above is live AWS state as of 2026-10-17, and each claim has a one-line check:

| Claim | Kept true by |
|---|---|
| The groups and their rules | `aws ec2 describe-instances --region ap-south-1 --instance-ids i-012eae3e379fbbeac --query "Reservations[].Instances[].SecurityGroups"` and `aws ec2 describe-security-groups --region ap-south-1 --group-ids sg-06f188c79829d38d7` |
| The origin header is sent | `aws cloudfront get-distribution --id E1VULEK6B74RRA --query "Distribution.DistributionConfig.Origins.Items[0].CustomHeaders.Items[].HeaderName"` lists `X-Origin-Verify` (names only; never print the value) |
| The lock holds | `curl -s -o /dev/null -w '%{http_code}' https://d2b34i3e92al6i.cloudfront.net/health` is 200, and `curl -m 10 http://15.207.145.174/health` gets no answer |
| The deploy keeps it | `grep -n "Require CloudFront's origin header" .github/workflows/deploy-backend.yml` and `grep -n origin-verify infra/terraform/user_data.sh` |
