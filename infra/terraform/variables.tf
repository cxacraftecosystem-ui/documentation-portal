variable "aws_region" {
  description = "AWS region for S3 + EC2 (keep S3 and the API in the same region)."
  type        = string
  default     = "ap-south-1"
}

variable "project" {
  description = "Name prefix for created resources."
  type        = string
  default     = "fieldrepo"
}

variable "bucket_name" {
  description = "Globally-unique S3 bucket name for media."
  type        = string
}

variable "ssh_key_name" {
  description = "Name of an existing EC2 key pair for SSH into the API box."
  type        = string
}

variable "ssh_ingress_cidr" {
  description = "CIDR allowed to SSH (use YOUR.IP/32, not 0.0.0.0/0)."
  type        = string
}

variable "cors_allowed_origins" {
  description = "Origins allowed to PUT/GET media from the browser (the web frontend URL)."
  type        = list(string)
  default     = ["*"]
}
