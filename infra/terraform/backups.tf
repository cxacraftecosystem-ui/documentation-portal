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
#      after it lands. Its own KMS key is the default encryption, but the
#      replicas are SSE-S3 (AES-256), not KMS: S3 only puts a replica under
#      the replica key when the source object is SSE-KMS, and this media
#      bucket is SSE-S3 throughout. See "Open points" in the hardening report.
#   3. The API's key can no longer write to backups/. The nightly dump runs
#      under its own user, `backup_writer`, which can add a dump and list
#      backups/documentation-portal/ and do nothing else.
#
# CREATED LIVE WITH THE AWS CLI ON 2026-10-17, NOT BY TERRAFORM. This
# directory still holds no state for the live account, so every resource
# below has an import block in imports.tf. The configuration mirrors
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

resource "aws_kms_key" "offsite" {
  provider                = aws.offsite
  description             = "${var.project}: encrypts the off-site copy of the media bucket"
  enable_key_rotation     = true
  deletion_window_in_days = 30

  tags = {
    Name    = "${var.project}-offsite"
    Project = var.project
  }
}

resource "aws_kms_alias" "offsite" {
  provider      = aws.offsite
  name          = "alias/${var.project}-offsite"
  target_key_id = aws_kms_key.offsite.key_id
}

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

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm     = "aws:kms"
      kms_master_key_id = aws_kms_key.offsite.arn
    }
    bucket_key_enabled = true
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
# create, so the 30-day expiry of backups/ has to be repeated here. 35 days
# matches the lock, and the old version is then kept for 90 more.
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
      prefix = "backups/"
    }
    expiration {
      days = var.offsite_lock_days
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

# Read from the media bucket, write replicas to the off-site bucket, encrypt
# with the off-site key. Nothing else: no delete of a version anywhere, and
# the key can only be used through S3 in the off-site region.
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
      },
      {
        Sid      = "EncryptReplicas"
        Effect   = "Allow"
        Action   = ["kms:Encrypt", "kms:GenerateDataKey"]
        Resource = aws_kms_key.offsite.arn
        Condition = {
          StringEquals = { "kms:ViaService" = "s3.${var.offsite_region}.amazonaws.com" }
        }
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

    # S3 refuses a replica KMS key without this block. Nothing in the media
    # bucket is KMS-encrypted (it is SSE-S3), so it changes nothing today.
    # Replicas do not inherit the destination's default encryption, and the
    # replica key below applies only to SSE-KMS source objects. So today every
    # replica is SSE-S3 (AES-256). Checked with head-object on 2026-10-17.
    source_selection_criteria {
      sse_kms_encrypted_objects {
        status = "Enabled"
      }
    }

    destination {
      bucket        = aws_s3_bucket.offsite.arn
      storage_class = "STANDARD_IA"
      encryption_configuration {
        replica_kms_key_id = aws_kms_key.offsite.arn
      }
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
