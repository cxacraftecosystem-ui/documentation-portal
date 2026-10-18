###############################################################################
# MEDIA AND BACKUP PROTECTION (2026-10-17)
#
# Closes gaps 1-3 and 5 of docs/ops/RESTORE_DRILL_2026-10-17.md:
#
#   1. Versioning on the media bucket. A deleted or overwritten object becomes
#      a noncurrent version and stays recoverable for 90 days.
#   2. An off-site copy. Everything in the media bucket (media/ and backups/)
#      replicates to a bucket in ap-south-2 (Hyderabad), which keeps the data
#      in India. It has Object Lock: no version can be deleted for 35 days
#      after it lands. The copies are SSE-S3 (AES-256) by choice, the same as
#      the media bucket: the owner took option (b) on 2026-10-17 and the
#      dedicated off-site KMS key was removed. SSE-KMS for the nightly dumps
#      is deferred to the production launch, see D1 in
#      docs/DEFERRED_FOR_LAUNCH.md (IIT workspace).
#   3. The API's key can no longer write to backups/. The nightly dump runs
#      under its own user, `backup_writer`, which can add a dump and list
#      backups/documentation-portal/ and do nothing else.
#
# CREATED LIVE WITH THE AWS CLI ON 2026-10-17, NOT BY TERRAFORM, and
# imported into the S3 state on 2026-10-18 through the blocks in imports.tf
# (27 imported, 0 added, 0 changed, 0 destroyed). The configuration mirrors
# designer-portal/infra/terraform/backups.tf, which was applied through
# Terraform the same day. docs/ops/BACKUPS_HARDENING_2026-10-17.md holds the
# rollback for each resource and the restore runbook.
#
# WHAT IS NOT IN TERRAFORM, ON PURPOSE:
#   - The backup user's access key. A Terraform-made key keeps its secret in
#     state in plaintext. It was minted with the AWS CLI, stored as a
#     SecureString in SSM (/iit/documentation-portal/backup-writer-*) and in
#     the repository's Actions secrets.
#   - The one-off S3 Batch Replication job that copied the objects that
#     existed before replication was switched on, and its temporary role.
###############################################################################

data "aws_caller_identity" "current" {}

variable "offsite_region" {
  description = "Region of the off-site backup bucket. ap-south-2 (Hyderabad) keeps the copy in India."
  type        = string
  default     = "ap-south-2"
}

variable "noncurrent_version_days" {
  description = "Days a deleted or overwritten object version is kept, in both buckets."
  type        = number
  default     = 90
}

variable "weekly_dump_days" {
  description = "Days the weekly dump copy under backups/<portal>/weekly/ is kept, in both buckets."
  type        = number
  default     = 365
}

variable "offsite_lock_days" {
  description = "Object Lock (governance) retention on every version in the off-site bucket."
  type        = number
  default     = 35
}

provider "aws" {
  alias  = "offsite"
  region = var.offsite_region
}

############################ Versioning on media ##############################

# Turning versioning on changes nothing for current objects. Every later
# overwrite or delete keeps the old bytes as a noncurrent version, which the
# `noncurrent-versions` lifecycle rule in main.tf removes after 90 days.
# Versioning cannot be turned off again, only suspended.
resource "aws_s3_bucket_versioning" "media" {
  bucket = aws_s3_bucket.media.id
  versioning_configuration {
    status = "Enabled"
  }
}

############################ Off-site bucket (Hyderabad) ######################

resource "aws_s3_bucket" "offsite" {
  provider            = aws.offsite
  bucket              = "${var.project}-offsite-${data.aws_caller_identity.current.account_id}"
  object_lock_enabled = true

  tags = {
    Name    = "${var.project}-offsite"
    Project = var.project
  }

  lifecycle {
    prevent_destroy = true
  }
}

resource "aws_s3_bucket_versioning" "offsite" {
  provider = aws.offsite
  bucket   = aws_s3_bucket.offsite.id
  versioning_configuration {
    status = "Enabled"
  }
}

# Object Lock's default retention is per bucket, not per prefix, so it covers
# the media replicas as well as backups/. Governance mode: an operator holding
# s3:BypassGovernanceRetention can still remove a version early, nobody else can.
resource "aws_s3_bucket_object_lock_configuration" "offsite" {
  provider = aws.offsite
  bucket   = aws_s3_bucket.offsite.id

  rule {
    default_retention {
      mode = "GOVERNANCE"
      days = var.offsite_lock_days
    }
  }

  depends_on = [aws_s3_bucket_versioning.offsite]
}

resource "aws_s3_bucket_server_side_encryption_configuration" "offsite" {
  provider = aws.offsite
  bucket   = aws_s3_bucket.offsite.id

  # SSE-S3, the same as every replica already is. No KMS key: see D1 in
  # docs/DEFERRED_FOR_LAUNCH.md (IIT workspace) for the switch at launch.
  # SSE-C stays blocked, as S3 sets it on new buckets.
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
    blocked_encryption_types = ["SSE-C"]
  }
}

resource "aws_s3_bucket_public_access_block" "offsite" {
  provider                = aws.offsite
  bucket                  = aws_s3_bucket.offsite.id
  block_public_acls       = true
  ignore_public_acls      = true
  block_public_policy     = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_ownership_controls" "offsite" {
  provider = aws.offsite
  bucket   = aws_s3_bucket.offsite.id
  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

resource "aws_s3_bucket_policy" "offsite" {
  provider   = aws.offsite
  bucket     = aws_s3_bucket.offsite.id
  depends_on = [aws_s3_bucket_public_access_block.offsite]

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid       = "DenyInsecureTransport"
      Effect    = "Deny"
      Principal = "*"
      Action    = "s3:*"
      Resource  = [aws_s3_bucket.offsite.arn, "${aws_s3_bucket.offsite.arn}/*"]
      Condition = { Bool = { "aws:SecureTransport" = "false" } }
    }]
  })
}

# S3 does not replicate the delete markers that the source's lifecycle rules
# create, so the expiry of the dumps has to be repeated here. Nightly dumps:
# 35 days, which matches the lock. Weekly copies: 365 days, as in Mumbai. An
# expired version is then kept for 90 more. The nightly rule names the dated
# keys only (`…/20`), because the shorter of two overlapping expiries wins.
resource "aws_s3_bucket_lifecycle_configuration" "offsite" {
  provider = aws.offsite
  bucket   = aws_s3_bucket.offsite.id

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
    abort_incomplete_multipart_upload {
      days_after_initiation = 7
    }
  }

  rule {
    id     = "expire-database-backups"
    status = "Enabled"
    filter {
      prefix = "backups/documentation-portal/20"
    }
    expiration {
      days = var.offsite_lock_days
    }
  }

  rule {
    id     = "keep-weekly-dumps"
    status = "Enabled"
    filter {
      prefix = "backups/documentation-portal/weekly/"
    }
    expiration {
      days = var.weekly_dump_days
    }
  }

  depends_on = [aws_s3_bucket_versioning.offsite]
}

############################ Replication ######################################

resource "aws_iam_role" "replication" {
  name = "${var.project}-s3-replication"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "s3.amazonaws.com" }
      Action    = "sts:AssumeRole"
      Condition = {
        StringEquals = { "aws:SourceAccount" = data.aws_caller_identity.current.account_id }
      }
    }]
  })

  tags = {
    Name    = "${var.project}-s3-replication"
    Project = var.project
  }
}

# Read from the media bucket and write replicas to the off-site bucket.
# Nothing else: no delete of a version anywhere, and no KMS, because every
# object on both sides is SSE-S3.
resource "aws_iam_role_policy" "replication" {
  name = "${var.project}-s3-replication"
  role = aws_iam_role.replication.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "ReadSourceConfig"
        Effect   = "Allow"
        Action   = ["s3:GetReplicationConfiguration", "s3:ListBucket"]
        Resource = aws_s3_bucket.media.arn
      },
      {
        Sid    = "ReadSourceVersions"
        Effect = "Allow"
        Action = [
          "s3:GetObjectVersionForReplication",
          "s3:GetObjectVersionAcl",
          "s3:GetObjectVersionTagging",
          "s3:GetObjectRetention",
          "s3:GetObjectLegalHold"
        ]
        Resource = "${aws_s3_bucket.media.arn}/*"
      },
      {
        Sid      = "WriteReplicas"
        Effect   = "Allow"
        Action   = ["s3:ReplicateObject", "s3:ReplicateDelete", "s3:ReplicateTags"]
        Resource = "${aws_s3_bucket.offsite.arn}/*"
      }
    ]
  })
}

# The whole bucket, delete markers included, so the replica mirrors what the
# product shows. Replicated deletes only add a marker: the old version stays,
# locked for 35 days and kept for 90.
resource "aws_s3_bucket_replication_configuration" "media" {
  bucket = aws_s3_bucket.media.id
  role   = aws_iam_role.replication.arn

  rule {
    id       = "offsite-whole-bucket"
    status   = "Enabled"
    priority = 1
    filter {}

    delete_marker_replication {
      status = "Enabled"
    }

    # Replicas keep the source's encryption, SSE-S3 (AES-256), by choice.
    # No source_selection_criteria and no replica KMS key: nothing in the
    # media bucket is SSE-KMS. Deferred to launch: D1 in
    # docs/DEFERRED_FOR_LAUNCH.md (IIT workspace).
    destination {
      bucket        = aws_s3_bucket.offsite.arn
      storage_class = "STANDARD_IA"
    }
  }

  depends_on = [
    aws_s3_bucket_versioning.media,
    aws_s3_bucket_versioning.offsite,
    aws_iam_role_policy.replication,
  ]
}

############################ The backup job's own identity ####################

# Used only by .github/workflows/backup-db.yml (secrets AWS_BACKUP_*). It can
# add a dump and list backups/documentation-portal/. It cannot read a dump, delete
# one, or touch media/. With versioning on, a second upload under
# the same date adds a version and the first stays.
resource "aws_iam_user" "backup_writer" {
  name = "${var.project}-backup-writer"

  tags = {
    Name    = "${var.project}-backup-writer"
    Project = var.project
  }
}

resource "aws_iam_user_policy" "backup_writer" {
  name = "${var.project}-backup-writer"
  user = aws_iam_user.backup_writer.name

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "AddDumpsOnly"
        Effect   = "Allow"
        Action   = "s3:PutObject"
        Resource = "${aws_s3_bucket.media.arn}/backups/documentation-portal/*"
      },
      {
        Sid       = "VerifyByListingOnly"
        Effect    = "Allow"
        Action    = "s3:ListBucket"
        Resource  = aws_s3_bucket.media.arn
        Condition = { StringLike = { "s3:prefix" = ["backups/documentation-portal/*"] } }
      }
    ]
  })
}
