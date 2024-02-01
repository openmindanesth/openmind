module "vpc" {
  source  = "terraform-aws-modules/vpc/aws"
  version = "5.5.0"

  name = "openmind-${var.env}-vpc"

  cidr = var.cidr
  azs  = var.zones
  # private_subnets = var.private_subnets
  public_subnets = var.public_subnets

  enable_nat_gateway = false
  # single_nat_gateway = true

  tags = {
    terraform   = "true"
    environment = var.env
  }
}

# module "alb" {
#   source  = "terraform-aws-modules/alb/aws"
#   version = "9.4.1"

#   name   = "openmind-${var.env}-alb"
#   vpc_id = module.vpc.vpc_id

#   subnets = module.vpc.private_subnets

#   enable_deletion_protection = var.env == "prod" ? true : false

#   access_logs = {
#     bucket = module.alb-logs.s3_bucket_id
#   }

#   security_group_ingress_rules = {
#     all_http = {
#       from_port   = 80
#       to_port     = 80
#       ip_protocol = "tcp"
#       description = "HTTP web traffic"
#       cidr_ipv4   = "0.0.0.0/0"
#     }
#   }
#   security_group_egress_rules = {
#     all = {
#       ip_protocol = "-1"
#       from_port   = 0
#       to_port     = 0
#       cidr_ipv4   = "0.0.0.0/0"
#     }
#   }

#   listeners = {
#     # cloudfront will handle https redirect
#     primary = {
#       port     = 80
#       protocol = "HTTP"

#       forward = {
#         target_group_key = "openmind-service"
#       }
#     }
#   }

#   target_groups = {
#     openmind-service = {
#       name_prefix = "alb1"
#       protocol    = "HTTP"
#       target_type = "ip"
#       port        = var.container_port
#       # TODO: implement health checks server side
#       # health_check {
#       #   path                  = "/health"
#       #   protocol              = "HTTP"
#       #   matcher               = "200"
#       #   port                  = "traffic-port"
#       #   healthy_threshold     = 2
#       #   unhealthy_threshold   = 2
#       #   timeout               = 10
#       #   interval              = 30
#       # }
#     }
#   }

#   tags = {
#     terraform   = "true"
#     environment = var.env
#   }
# }

##### Manual ALB setup, need to figure out module

data "aws_ec2_managed_prefix_list" "cloudfront" {
  name = "com.amazonaws.global.cloudfront.origin-facing"
}

module "alb_sg" {
  source  = "terraform-aws-modules/security-group/aws"
  version = "5.1.0"

  name        = "${var.env}-alb-sg"
  description = "Allow ingress from cloudfront only"
  vpc_id      = module.vpc.vpc_id

  use_name_prefix = false

  ingress_prefix_list_ids = [data.aws_ec2_managed_prefix_list.cloudfront.id]
  ingress_with_prefix_list_ids = [
    {
      from_port = 80
      to_port   = var.container_port
      protocol  = "tcp"
    }
  ]

  egress_with_cidr_blocks = [
    {
    from_port   = 0
    to_port     = 0
    protocol    = "tcp"
    cidr_blocks = "0.0.0.0/0"
  }
  ]

  tags = {
    terraform = true
    env       = var.env
  }
}

resource "aws_lb" "openmind" {
  name               = "openmind-${var.env}"
  internal           = false
  load_balancer_type = "application"
  subnets            = module.vpc.public_subnets
  security_groups    = [module.alb_sg.security_group_id]

  tags = {
    terraform = true
    env       = var.env
  }
}

resource "aws_lb_target_group" "openmind" {
  name        = "openmind-${var.env}"
  port        = var.container_port
  protocol    = "HTTP"
  target_type = "ip"
  vpc_id      = module.vpc.vpc_id
  # TODO: Implement health check in server.
  health_check {
    path                = "/"
    protocol            = "HTTP"
    matcher             = "200"
    port                = "traffic-port"
    healthy_threshold   = 2
    unhealthy_threshold = 10
    timeout             = 10
    interval            = 30
  }

  tags = {
    terraform = true
    env       = var.env
  }
}

# resource "aws_lb_listener" "listener" {
#   load_balancer_arn = aws_lb.openmind.arn
#   # SSL handled by CDN for the time being
#   port     = "80"
#   protocol = "HTTP"

#   default_action {
#     type             = "forward"
#     target_group_arn = aws_lb_target_group.openmind.arn
#   }

#   tags = {
#     terraform = true
#     env       = var.env
#   }

# }
