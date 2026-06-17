output "api_public_ip" {
  description = "Stable Elastic IP of the API server. Point the apps at http://<ip>/api/."
  value       = aws_eip.api.public_ip
}

output "s3_bucket" {
  description = "Media bucket name (set AWS_S3_BUCKET to this)."
  value       = aws_s3_bucket.media.bucket
}

output "s3_public_base_url" {
  description = "Set AWS_S3_PUBLIC_BASE_URL to this."
  value       = "https://${aws_s3_bucket.media.bucket}.s3.${var.aws_region}.amazonaws.com"
}

output "media_access_key_id" {
  description = "IAM access key id for the API (AWS_ACCESS_KEY_ID)."
  value       = aws_iam_access_key.media.id
}

output "media_secret_access_key" {
  description = "IAM secret key for the API (AWS_SECRET_ACCESS_KEY). Sensitive."
  value       = aws_iam_access_key.media.secret
  sensitive   = true
}
