module "vpc" {
  source  = "terraform-aws-modules/vpc/aws"
  version = "5.5.0"

  name = "openmind-${env}-vpc"

  cidr            = var.cidr
  azs             = var.zones
  private_subnets = var.public_subnets
  public_subnets  = var.private_subnets

  enable_nat_gateway = true

  tags = {
    terraform   = "true"
    environment = var.env
  }
}

# Access logging

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
			"Sid": "Stmt1429136633762",
			"Action": [
				"s3:PutObject"
			],
			"Effect": "Allow",
			"Resource": "arn:aws:s3:::${aws_s3_bucket.alb-logs.id}/*",
			"Principal": {
				"AWS": [
					"985666609251"
				]
			}
		}
	]
}
POLICY
}

module "alb" {
  source  = "terraform-aws-modules/alb/aws"
  version = "9.4.1"

  name   = "openmind-${var.env}-alb"
  vpc_id = module.vpc.vpc_id

  access_logs = {
    bucket = aws_s3_bucket.alb-logs.id
  }

  security_group_ingress_rules = {
    all_http = {
      from_port   = 80
      to_port     = 80
      ip_protocol = "tcp"
      description = "HTTP web traffic"
      cidr_ipv4   = "0.0.0.0/0"
    }
  }
  security_group_egress_rules = {
    all = {
      ip_protocol = "-1"
      from_port   = 0
      to_port     = 0
      cidr_ipv4   = "0.0.0.0/0"
    }
  }

  listeners = {
    # cloudfront will handle https redirect
    primary = {
      port     = 80
      protocol = "HTTP"

      forward = {
        target_group_key = "server-backend"
      }
    }
  }

  target_groups = {
    server-backend = {
      name_prefix = "h1"
      protocol    = "HTTP"
      port        = 80
      target_type = "instance"
    }
  }

  tags = {
    terraform   = "true"
    environment = var.env
  }
}
