###############################################################################
# Field Repository infrastructure: S3 (media) + IAM (programmatic media access)
# + EC2 (FastAPI behind nginx; a t3.micro until the 26.04 rebuild approved on
# 2026-10-09, a t3.small from it). Database stays on Supabase, so the box
# is stateless and can be rebuilt anytime without data loss.
#
# Usage:
#   cd infra/terraform
#   terraform init
#   terraform apply \
#     -var="aws_region=ap-south-1" \
#     -var="bucket_name=YOUR-GLOBALLY-UNIQUE-BUCKET" \
#     -var="ssh_key_name=your-ec2-keypair"
#
# THERE IS NO STATE FOR THE LIVE ACCOUNT YET. imports.tf adopts the live resources a first `apply`
# would otherwise duplicate; read `terraform plan` in full before any apply.
#
# NEVER commit terraform.tfstate or *.tfvars (already gitignored): state can
# contain the generated IAM secret key. DO commit .terraform.lock.hcl: it is what
# makes every `init` resolve the same provider build, and it holds no secrets.
###############################################################################

# Versions, as of 2026-10-09: Terraform 1.16.5 and hashicorp/aws 6.68.0 were the latest
# releases. The floors below are those lines, and .terraform.lock.hcl (committed, with hashes
# for linux, macOS and Windows on amd64 and arm64) pins the exact provider build; Dependabot's
# terraform entry proposes the next one. Refresh the lock by hand with
#   terraform providers lock -platform=linux_amd64 -platform=linux_arm64 \
#     -platform=darwin_amd64 -platform=darwin_arm64 -platform=windows_amd64
#
# The move from provider 5.x to 6.x needed no change to this module: v6's breaking changes that could
# touch it are already met (`aws_ami` names its owners, `aws_eip` uses `domain` rather than the
# removed `vpc`, nothing sets `cpu_core_count` or a bucket `region`). One v6 behaviour to know:
# `aws_instance.user_data` is stored in state in clear text. user_data.sh carries no secret — the
# .env reaches the box through the deploy workflow, never through this file — so that is acceptable,
# and it must stay that way.
terraform {
  required_version = ">= 1.16"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.68"
    }
  }
}

provider "aws" {
  region = var.aws_region
}

############################# S3 bucket for media #############################

resource "aws_s3_bucket" "media" {
  bucket = var.bucket_name
}

resource "aws_s3_bucket_public_access_block" "media" {
  bucket                  = aws_s3_bucket.media.id
  block_public_acls       = true
  ignore_public_acls      = true
  block_public_policy     = false
  restrict_public_buckets = false
}

# Public read for objects under media/ only; uploads stay private (presigned PUT).
resource "aws_s3_bucket_policy" "media_public_read" {
  bucket     = aws_s3_bucket.media.id
  depends_on = [aws_s3_bucket_public_access_block.media]
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid       = "PublicReadMedia"
        Effect    = "Allow"
        Principal = "*"
        Action    = "s3:GetObject"
        Resource  = "${aws_s3_bucket.media.arn}/media/*"
      },
      # Added 2026-10-17. The API's key holds no grant on backups/ (see
      # aws_iam_user_policy.media); this deny keeps it that way even if somebody
      # widens that policy later. The backup job runs as aws_iam_user.backup_writer.
      {
        Sid       = "ApiKeyNeverTouchesBackupsOrState"
        Effect    = "Deny"
        Principal = { AWS = aws_iam_user.media.arn }
        Action    = "s3:*"
        Resource = [
          "${aws_s3_bucket.media.arn}/backups/*",
          "${aws_s3_bucket.media.arn}/tfstate/*"
        ]
      }
    ]
  })
}

# CORS so the web frontend's presigned PUT/GET work from the browser.
resource "aws_s3_bucket_cors_configuration" "media" {
  bucket = aws_s3_bucket.media.id
  cors_rule {
    allowed_headers = ["*"]
    allowed_methods = ["PUT", "GET", "HEAD"]
    allowed_origins = var.cors_allowed_origins
    expose_headers  = ["ETag"]
    max_age_seconds = 3000
  }
}

######################### Lifecycle: expire DB backups ########################

# ─── WHY A LIFECYCLE RULE AT ALL ──────────────────────────────────────────────────────────
# `.github/workflows/backup-db.yml` writes one gzipped `pg_dump` under `backups/` every night and
# holds NO delete right anywhere in this account — deliberately, because the identity it runs as
# also sits on an internet-facing box. A job that can write but not delete cannot express "keep
# thirty days", so retention is a property of the BUCKET and is declared here. Without this rule
# the workflow keeps working and the bucket grows forever: a cost problem, not a data-loss one,
# which is the right way round.
#
# THIRTY DAYS IS A JUDGEMENT, NOT A STANDARD — and it is the same judgement the sibling product
# made (designer-portal/infra/terraform/main.tf:318-337), kept identical on purpose so the two do
# not drift into two different answers. The failures a backup protects against here are a bad
# migration, a mistaken bulk delete, or a provider account problem, all of which are noticed in
# hours or days, not months. If a retention obligation is ever placed on this ministry data, this
# number is the one to change and this is the only place to change it.
#
# THE FILTER IS THE WHOLE SAFETY PROPERTY. `prefix = "backups/"` scopes the rule to the dumps.
# Without a filter — or with an empty one — this rule expires EVERY OBJECT IN THIS BUCKET after
# thirty days, which is 1,071 objects and 11.5 GB of artisan photographs, audio and video as of
# 2026-09-13. Versioning (backups.tf, 2026-10-17) would turn that into delete markers that can be
# undone, but only for 90 days and only if somebody notices. Any edit to this resource must be read
# with that sentence in mind.
#
# The second rule is the one place an empty filter is safe, because it names no current-version
# expiry. It removes versions 90 days after they stop being current (an overwritten or deleted
# file, an expired dump) and delete markers with nothing left behind them.
#
# `abort_incomplete_multipart_upload` is here because `aws s3 cp` uses multipart for larger objects
# and a run killed mid-upload leaves parts that are invisible to `ls` and billable forever. The
# API's own cancelled uploads land in the same trap, and until 2026-09-13 its policy did not even
# grant `s3:AbortMultipartUpload` to clean them up — see `aws_iam_user_policy.media` below.
resource "aws_s3_bucket_lifecycle_configuration" "media" {
  bucket = aws_s3_bucket.media.id

  rule {
    id     = "expire-database-backups"
    status = "Enabled"

    filter {
      prefix = "backups/"
    }

    expiration {
      days = 30
    }

    abort_incomplete_multipart_upload {
      days_after_initiation = 7
    }
  }

  rule {
    id     = "noncurrent-versions"
    status = "Enabled"
    filter {}

    noncurrent_version_expiration {
      noncurrent_days = var.noncurrent_version_days
    }
    expiration {
      expired_object_delete_marker = true
    }
  }

  depends_on = [aws_s3_bucket_versioning.media]
}

####################### IAM user for the API (S3 access) ######################

resource "aws_iam_user" "media" {
  name = "${var.project}-media"
}

# ─── ONE STATEMENT SINCE 2026-10-17 ───────────────────────────────────────────────────────
#
# The restore drill of 2026-10-17 found that the write-only grant on `backups/` still let this
# key OVERWRITE a dump. `BackupWriteOnlyDropBox` and `BackupVerifyByListingOnly` were removed that
# day and moved, narrowed to `backups/documentation-portal/*`, to `aws_iam_user.backup_writer`
# (backups.tf), which backup-db.yml now uses. The bucket policy also denies this user `backups/*`
# outright. The history below explains why the split existed; only `AppMediaObjects` is live.
#
# ─── WHY THIS POLICY WAS THREE STATEMENTS AND NOT ONE (2026-09-13) ───────────────────────
#
# It was one statement — Put/Get/Delete on `${bucket}/*` — from the day the bucket was created
# until 2026-09-13. That is the whole bucket, and the reason it stopped being acceptable is that
# THIS CREDENTIAL LIVES ON THE PRODUCTION API BOX: the same key pair is in `BACKEND_ENV` and in
# `/home/ubuntu/app/backend/.env` on an internet-facing t3.micro. So "what this user may do" is
# also "what anything that compromises the API may do", and the moment the nightly `pg_dump`
# below starts landing in `backups/`, a whole-bucket grant means the API's own `.env` can read
# every database dump this project has ever taken, and delete them.
#
# The prefix was never what made that safe. This policy is what makes it safe, and the designer
# portal's bucket was split the same way on 2026-09-03 for the same reason
# (designer-portal/infra/terraform/main.tf:439-475 — kept deliberately identical so the two
# products do not drift into two different answers about one threat).
#
#   AppMediaObjects            Everything the running app does, confined to `media/*`. The four
#                              actions are exactly the seven boto3 calls in
#                              `backend/app/services/s3.py`: put_object/get_object/delete_object
#                              and the multipart quartet (create/upload_part/complete map to
#                              s3:PutObject; abort needs s3:AbortMultipartUpload, which the old
#                              policy DID NOT GRANT — a cancelled upload left its parts billing
#                              silently). Every key the app mints is `media/<user_id>/…`
#                              (`s3.py:105`) and every route refuses a key outside that prefix
#                              (`media.py:157`, `:693`), so narrowing to `media/*` takes nothing
#                              away: verified 2026-09-13, all 1,071 objects in this bucket are
#                              under `media/`.
#   BackupWriteOnlyDropBox     PutObject and NOTHING ELSE on `backups/*`. The backup workflow can
#                              write a dump; it cannot read one back and cannot delete one. A
#                              write-only drop box is the strongest shape available to a key that
#                              also sits on a public box.
#   BackupVerifyByListingOnly  ListBucket conditioned on the `backups/` prefix, so the workflow's
#                              proof step can measure the object it just wrote. It is ListBucket
#                              and NOT head-object on purpose: there is no HEAD-only permission in
#                              S3 — granting the HEAD grants the GET — so `head-object` would hand
#                              back exactly the read this split exists to remove. Listing yields
#                              the name and the size, which is all the assert ever used.
#
# If a third prefix is ever added to this bucket, it is a change to THIS policy and to
# `aws_s3_bucket_policy.media_public_read` above, not a change to a path string somewhere.
resource "aws_iam_user_policy" "media" {
  name = "${var.project}-media-s3"
  user = aws_iam_user.media.name
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "AppMediaObjects"
        Effect = "Allow"
        Action = [
          "s3:PutObject",
          "s3:GetObject",
          "s3:DeleteObject",
          "s3:AbortMultipartUpload"
        ]
        Resource = "${aws_s3_bucket.media.arn}/media/*"
      }
    ]
  })
}

resource "aws_iam_access_key" "media" {
  user = aws_iam_user.media.name
}

############################### EC2 (API server) ##############################

# Ubuntu 26.04 LTS (resolute) for any box built from here since 2026-10-09. Its archive carries
# nginx 1.28 and ffmpeg 8; the API's interpreter does not come from it (Ubuntu's python3.14 is
# 3.14.4): user_data.sh and the deploy install upstream CPython 3.14.8, the release CI tests, from a
# pinned and checksummed python-build-standalone build under /opt/cpython, on 24.04 and 26.04 alike.
# The box running on 2026-10-09 (i-06f177db5c4e3b0af) is still 24.04, built from the noble filter
# that stood here.
#
# The instance ignores changes to the AMI and user_data (see `lifecycle` on aws_instance.api):
# Canonical publishes a new 26.04 build every few days, and without that every `apply` would rebuild
# production. A rebuild is a deliberate `terraform apply -replace=aws_instance.api`: the box is
# stateless (Supabase holds the data, S3 the media) and the Elastic IP is reattached, but keep
# backend/DEPLOY_AWS.md's restore steps (nginx, the two systemd units) to hand.
data "aws_ami" "ubuntu" {
  most_recent = true
  owners      = ["099720109477"] # Canonical
  filter {
    name   = "name"
    values = ["ubuntu/images/hvm-ssd-gp3/ubuntu-resolute-26.04-amd64-server-*"]
  }
  filter {
    name   = "virtualization-type"
    values = ["hvm"]
  }
}

# No standing ingress (2026-10-17). Port 22 was open to 0.0.0.0/0; it is now opened only for the
# deploying runner's /32 by deploy-backend.yml (as `fieldrepo-ci-sg` below) and closed again at the
# end of the job. People reach the box over SSM. Port 80 is the cloudfront_origin group's.
# The description is what the live group was created with: changing it replaces the group.
resource "aws_security_group" "api" {
  name        = "${var.project}-api"
  description = "Field Repository API: SSH (restricted) + HTTP/HTTPS via nginx"

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

# ─── PORT 80 ANSWERS CLOUDFRONT ONLY (2026-10-17) ────────────────────────────────────────────────
# Port 80 used to be open to 0.0.0.0/0, along with 443, which nothing listens on: CloudFront reaches
# this origin over plain HTTP (origin protocol policy `http-only`), so the box holds no certificate.
# Anyone who knew the address could skip CloudFront and send the API a forged X-Forwarded-For. Now:
#   1. this group admits port 80 only from AWS's managed prefix list of CloudFront's origin-facing
#      servers. That list weighs about 55 rules against the 60-rule-per-group quota, so it lives in
#      its own group rather than in `api` above;
#   2. nginx answers 403 unless the request carries the `X-Origin-Verify` header that only this
#      portal's distribution sends (deploy-backend.yml writes the check from ORIGIN_VERIFY_SECRET).
# The prefix list on its own would still admit SOMEBODY ELSE's CloudFront distribution pointed at
# this address; the header is what makes it this portal's. docs/INFRA_ORIGIN_LOCK.md has the ids
# and the rollback. Name and description are what the live group was created with: changing either
# replaces the group.
data "aws_ec2_managed_prefix_list" "cloudfront_origin_facing" {
  name = "com.amazonaws.global.cloudfront.origin-facing"
}

resource "aws_security_group" "cloudfront_origin" {
  name        = "cloudfront-origin-only-documentation"
  description = "HTTP to nginx from CloudFront origin-facing servers only (documentation-portal)"
  tags        = { Name = "cloudfront-origin-only-documentation" }

  ingress {
    description     = "CloudFront origin-facing"
    from_port       = 80
    to_port         = 80
    protocol        = "tcp"
    prefix_list_ids = [data.aws_ec2_managed_prefix_list.cloudfront_origin_facing.id]
  }
  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

# t3.small (2 GiB) and an ENCRYPTED root volume: what the owner approved on 2026-10-09 for the 26.04
# rebuild. The t3.micro this replaces had 909 MB, ran the API and the queue with 122 MB available and
# 215 MB in swap that day, and its root volume was never encrypted (nor is EBS encryption on by
# default in this account). Both change the instance on an `apply`, as the AMI filter above does. The
# rebuild itself is done with the AWS CLI, because this repository holds no state:
# backend/DEPLOY_AWS.md §10 has the exact run-instances call, with the same values as this resource.
resource "aws_instance" "api" {
  ami                    = data.aws_ami.ubuntu.id
  instance_type          = "t3.small"
  key_name               = var.ssh_key_name
  vpc_security_group_ids = [aws_security_group.api.id, aws_security_group.cloudfront_origin.id]
  user_data              = file("${path.module}/user_data.sh")
  # The SSM instance profile the live box was given BY HAND on 2026-06-17 (role `fieldrepo-ssm`, with
  # AmazonSSMManagedInstanceCore attached), which is how the box is inspected without SSH. It is not
  # created by this module, and until 2026-10-09 it was not named here either, so a read-only
  # `terraform plan` against the live box showed the replacement that the 26.04 AMI filter forces
  # launching a box WITHOUT it, unreachable over SSM. Naming it keeps a rebuilt box reachable; bringing
  # the role and the profile themselves under this module (import both) is the fuller fix.
  iam_instance_profile = "fieldrepo-ssm"

  root_block_device {
    volume_size = 30
    volume_type = "gp3"
    encrypted   = true
    tags        = { Name = "${var.project}-api", Project = var.project }
  }

  tags = {
    Name    = "${var.project}-api"
    Project = var.project
  }

  lifecycle {
    ignore_changes = [ami, user_data]
  }
}

# The deploy workflow's only AWS identity: it may add and remove rules on the API group and read
# them back, nothing else. Its access key lives in the DEPLOY_SG_AWS_* Actions secrets and is not
# managed here, so no secret lands in state.
resource "aws_iam_user" "ci_sg" {
  name = "${var.project}-ci-sg"
  tags = { Project = var.project }
}

resource "aws_iam_user_policy" "ci_sg" {
  name = "DeploySshWindow"
  user = aws_iam_user.ci_sg.name
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "OpenAndCloseDeploySshOnTheApiSecurityGroupOnly"
        Effect   = "Allow"
        Action   = ["ec2:AuthorizeSecurityGroupIngress", "ec2:RevokeSecurityGroupIngress"]
        Resource = aws_security_group.api.arn
      },
      {
        Sid      = "ReadTheRuleBackForVerification"
        Effect   = "Allow"
        Action   = ["ec2:DescribeSecurityGroups"]
        Resource = "*"
      }
    ]
  })
}

resource "aws_eip" "api" {
  instance = aws_instance.api.id
  domain   = "vpc"
  tags     = { Name = "${var.project}-api" }
}
