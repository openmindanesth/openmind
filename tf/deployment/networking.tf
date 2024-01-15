module "vpc" {
  source  = "terraform-aws-modules/vpc/aws"
  version = "5.5.0"

  name = "openmind-${env}-vpc"

  cidr            = var.cidr
  azs             = var.zones
  private_subnets = var.public_subnets
  public_subnets  = var.private_subnets

  enable_nat_gateway = true
  single_nat_gateway = true

  tags = {
    terraform   = "true"
    environment = var.env
  }
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
        target_group_key = "openmind-service"
      }
    }
  }

  target_groups = {
    openmind-service = {
      name_prefix = "h1"
      protocol    = "HTTP"
      port        = 8080
      target_type = "instance"
    }
  }

  tags = {
    terraform   = "true"
    environment = var.env
  }
}
