# ─── ADOPT THE LIVE RESOURCES, DON'T DUPLICATE THEM ─────────────────────────────────────────────
# APPLIED 2026-10-18: every block below was imported into the S3 state (main.tf's backend) by an
# apply whose plan read "27 to import, 0 to add, 0 to change, 0 to destroy", and the next plan read
# "No changes". The blocks stay as the record of what was adopted; once in state they are no-ops.
# The resources were built by hand and with the AWS CLI (backend/DEPLOY_AWS.md §10), which is why
# they were imported rather than created. No access key is declared at all (see the note where
# `aws_iam_access_key.media` used to be in main.tf), so no plan can mint one. Read the whole plan
# before any apply; the AMI filter and instance settings in main.tf can force a rebuild.
import {
  to = aws_s3_bucket.media
  id = "fieldrepo-media-626159998512"
}
import {
  to = aws_s3_bucket_public_access_block.media
  id = "fieldrepo-media-626159998512"
}
import {
  to = aws_s3_bucket_policy.media_public_read
  id = "fieldrepo-media-626159998512"
}
import {
  to = aws_s3_bucket_cors_configuration.media
  id = "fieldrepo-media-626159998512"
}
import {
  to = aws_s3_bucket_lifecycle_configuration.media
  id = "fieldrepo-media-626159998512"
}
import {
  to = aws_iam_user.media
  id = "fieldrepo-media"
}
import {
  to = aws_iam_user_policy.media
  id = "fieldrepo-media:fieldrepo-media-s3"
}
import {
  to = aws_security_group.api
  id = "sg-0a3ffc8a00c9246f1"
}
import {
  to = aws_security_group.cloudfront_origin
  id = "sg-06f188c79829d38d7"
}
import {
  to = aws_instance.api
  id = "i-012eae3e379fbbeac"
}
import {
  to = aws_eip.api
  id = "eipalloc-03a87f17914bfa85d"
}
import {
  to = aws_iam_user.ci_sg
  id = "fieldrepo-ci-sg"
}
import {
  to = aws_iam_user_policy.ci_sg
  id = "fieldrepo-ci-sg:DeploySshWindow"
}

# ─── ADDED 2026-10-17: MEDIA AND BACKUP PROTECTION (backups.tf) ──────────────────────────────────
# Created live with the AWS CLI that day. The backup user's access key is left out for the same
# reason as the media key: its secret lives in SSM and the Actions secrets, never in state.
import {
  to = aws_s3_bucket_versioning.media
  id = "fieldrepo-media-626159998512"
}
import {
  to = aws_s3_bucket_replication_configuration.media
  id = "fieldrepo-media-626159998512"
}
import {
  to       = aws_s3_bucket.offsite
  id       = "fieldrepo-offsite-626159998512"
  provider = aws.offsite
}
import {
  to       = aws_s3_bucket_versioning.offsite
  id       = "fieldrepo-offsite-626159998512"
  provider = aws.offsite
}
import {
  to       = aws_s3_bucket_object_lock_configuration.offsite
  id       = "fieldrepo-offsite-626159998512"
  provider = aws.offsite
}
import {
  to       = aws_s3_bucket_server_side_encryption_configuration.offsite
  id       = "fieldrepo-offsite-626159998512"
  provider = aws.offsite
}
import {
  to       = aws_s3_bucket_public_access_block.offsite
  id       = "fieldrepo-offsite-626159998512"
  provider = aws.offsite
}
import {
  to       = aws_s3_bucket_ownership_controls.offsite
  id       = "fieldrepo-offsite-626159998512"
  provider = aws.offsite
}
import {
  to       = aws_s3_bucket_policy.offsite
  id       = "fieldrepo-offsite-626159998512"
  provider = aws.offsite
}
import {
  to       = aws_s3_bucket_lifecycle_configuration.offsite
  id       = "fieldrepo-offsite-626159998512"
  provider = aws.offsite
}
import {
  to = aws_iam_role.replication
  id = "fieldrepo-s3-replication"
}
import {
  to = aws_iam_role_policy.replication
  id = "fieldrepo-s3-replication:fieldrepo-s3-replication"
}
import {
  to = aws_iam_user.backup_writer
  id = "fieldrepo-backup-writer"
}
import {
  to = aws_iam_user_policy.backup_writer
  id = "fieldrepo-backup-writer:fieldrepo-backup-writer"
}
