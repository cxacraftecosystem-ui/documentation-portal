###############################################################################
# Field Repository infrastructure: S3 (media) + IAM (programmatic media access)
# + EC2 t3.micro (FastAPI behind nginx). Database stays on Supabase, so the box
# is stateless and can be rebuilt anytime without data loss.
#
# Usage:
#   cd infra/terraform
#   terraform init
#   terraform apply \
#     -var="aws_region=ap-south-1" \
#     -var="bucket_name=YOUR-GLOBALLY-UNIQUE-BUCKET" \
#     -var="ssh_key_name=your-ec2-keypair" \
#     -var="ssh_ingress_cidr=YOUR.IP.ADDR.ESS/32"
#
# NEVER commit terraform.tfstate or *.tfvars (already gitignored): state can
# contain the generated IAM secret key.
###############################################################################

terraform {
  required_version = ">= 1.5.0"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
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
    Statement = [{
      Sid       = "PublicReadMedia"
      Effect    = "Allow"
      Principal = "*"
      Action    = "s3:GetObject"
      Resource  = "${aws_s3_bucket.media.arn}/media/*"
    }]
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
# 2026-09-13. There is no undo for that and no versioning on this bucket to fall back on. Any edit
# to this resource must be read with that sentence in mind.
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
}

####################### IAM user for the API (S3 access) ######################

resource "aws_iam_user" "media" {
  name = "${var.project}-media"
}

# ─── WHY THIS POLICY IS THREE STATEMENTS AND NOT ONE ──────────────────────────────────────
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
      },
      {
        Sid      = "BackupWriteOnlyDropBox"
        Effect   = "Allow"
        Action   = "s3:PutObject"
        Resource = "${aws_s3_bucket.media.arn}/backups/*"
      },
      {
        Sid      = "BackupVerifyByListingOnly"
        Effect   = "Allow"
        Action   = "s3:ListBucket"
        Resource = aws_s3_bucket.media.arn
        Condition = {
          StringLike = {
            "s3:prefix" = ["backups/*"]
          }
        }
      }
    ]
  })
}

resource "aws_iam_access_key" "media" {
  user = aws_iam_user.media.name
}

############################### EC2 (API server) ##############################

data "aws_ami" "ubuntu" {
  most_recent = true
  owners      = ["099720109477"] # Canonical
  filter {
    name   = "name"
    values = ["ubuntu/images/hvm-ssd-gp3/ubuntu-noble-24.04-amd64-server-*"]
  }
  filter {
    name   = "virtualization-type"
    values = ["hvm"]
  }
}

resource "aws_security_group" "api" {
  name        = "${var.project}-api"
  description = "Field Repository API: SSH (restricted) + HTTP/HTTPS via nginx"

  ingress {
    description = "SSH"
    from_port   = 22
    to_port     = 22
    protocol    = "tcp"
    cidr_blocks = [var.ssh_ingress_cidr]
  }
  ingress {
    description = "HTTP (nginx)"
    from_port   = 80
    to_port     = 80
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }
  ingress {
    description = "HTTPS (nginx)"
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }
  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

resource "aws_instance" "api" {
  ami                    = data.aws_ami.ubuntu.id
  instance_type          = "t3.micro"
  key_name               = var.ssh_key_name
  vpc_security_group_ids = [aws_security_group.api.id]
  user_data              = file("${path.module}/user_data.sh")

  root_block_device {
    volume_size = 30
    volume_type = "gp3"
  }

  tags = {
    Name    = "${var.project}-api"
    Project = var.project
  }
}

resource "aws_eip" "api" {
  instance = aws_instance.api.id
  domain   = "vpc"
  tags     = { Name = "${var.project}-api" }
}
