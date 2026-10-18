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
