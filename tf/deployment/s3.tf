##### Primary data store

resource "random_id" "data-bucket-id" {
  byte_length = 4
}

module "openmind-data" {
  source = "terraform-aws-modules/s3-bucket/aws"
  version = "3.15.2"
  bucket = "openmind-datastore-${var.env}-${random_id.data-bucket-id}"

  versioning {
    enabled = true
  }

  tags = {
    terraform = "true"
    env     = var.env
    name    = "extract-store"
  }
}

##### ALB access logging

resource "random_id" "log-bucket-id" {
  byte_length = 4
}

resource "aws_s3_bucket" "alb-logs" {
  bucket = "openmind-${env}-lb-logs-${random_id.log-bucket-id.dec}"
  acl    = "private"

  lifecycle_rule {
    id      = "${env}-alb-logs"
    enabled = true

    transition {
      days          = 30
      storage_class = "STANDARD_IA"
    }
  }
}

resource "aws_s3_bucket_policy" "alb-logs" {
  bucket = aws_s3_bucket.alb-logs.id

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
			"Resource": "arn:aws:s3:::${aws_s3_bucket.alb-logs.id}/*",
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
