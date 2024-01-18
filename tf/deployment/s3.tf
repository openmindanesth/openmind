##### Primary data store

resource "random_id" "data-bucket-id" {
  byte_length = 4
}

module "openmind-data" {
  source  = "terraform-aws-modules/s3-bucket/aws"
  version = "4.0.1"
  bucket  = "openmind-datastore-${var.env}-${random_id.data-bucket-id.dec}"

  versioning = {
    enabled = true
  }

  acl = "private"

  control_object_ownership = true
  object_ownership         = "BucketOwnerPreferred"

  tags = {
    terraform = "true"
    env       = var.env
  }
}

##### Front end static assets

resource "random_id" "asset-bucket-id" {
  byte_length = 4
}

module "openmind-assets" {
  source  = "terraform-aws-modules/s3-bucket/aws"
  version = "4.0.1"
  bucket  = "openmind-assets-${var.env}-${random_id.asset-bucket-id.dec}"

  acl = "private"

  control_object_ownership = true
  object_ownership         = "BucketOwnerPreferred"

  tags = {
    terraform = "true"
    env       = var.env
  }
}

##### ALB access logging

resource "random_id" "log-bucket-id" {
  byte_length = 4
}

module "alb-logs" {
  source  = "terraform-aws-modules/s3-bucket/aws"
  version = "4.0.1"
  bucket  = "openmind-${var.env}-lb-logs-${random_id.log-bucket-id.dec}"

  acl = "log-delivery-write"

  control_object_ownership = true
  object_ownership         = "BucketOwnerPreferred"

  lifecycle_rule = [{
    id      = "${var.env}-alb-logs"
    enabled = true

    transition = {
      days          = 30
      storage_class = "STANDARD_IA"
    }
  }]
}

data "aws_caller_identity" "current" {}

resource "aws_s3_bucket_policy" "alb-logs" {
  bucket = module.alb-logs.s3_bucket_id

  policy = <<POLICY
{
	"Id": "load-balancer-access",
	"Version": "2012-10-17",
	"Statement": [
		{
			"Sid": "lb-bucket-write-${var.env}",
			"Action": [
				"s3:PutObject"
			],
			"Effect": "Allow",
			"Resource": "arn:aws:s3:::${module.alb-logs.s3_bucket_id}/*",
			"Principal": {
				"AWS": [
					"${data.aws_caller_identity.current.account_id}"
				]
			}
		}
	]
}
POLICY
}

##### Cloudfront access logs

resource "random_id" "cdn-log-bucket-id" {
  byte_length = 4
}

module "cdn-logs" {
  source  = "terraform-aws-modules/s3-bucket/aws"
  version = "4.0.1"
  bucket  = "openmind-${var.env}-cloudfront-logs-${random_id.cdn-log-bucket-id.dec}"

  acl = "log-delivery-write"

  control_object_ownership = true
  object_ownership         = "BucketOwnerPreferred"

  lifecycle_rule = [{
    id      = "${var.env}-cdn-logs"
    enabled = true

    transition = {
      days          = 30
      storage_class = "STANDARD_IA"
    }
  }]
}
