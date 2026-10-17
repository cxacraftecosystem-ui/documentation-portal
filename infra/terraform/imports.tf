# ─── ADOPT THE LIVE RESOURCES, DON'T DUPLICATE THEM ─────────────────────────────────────────────
# Nobody holds Terraform state for the live account: the box was built by hand and rebuilt with the
# AWS CLI (backend/DEPLOY_AWS.md §10). Without these blocks a first `apply` would launch a second
# instance and fail on every name that already exists. With them, `terraform plan` reads the live
# resources into state first and shows only real differences (checked read-only on 2026-10-17).
# The media access key is left out on purpose: its secret cannot be imported, so an apply would try
# to mint a third key (the user already has two, the limit) and fail there. Read the whole plan
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
