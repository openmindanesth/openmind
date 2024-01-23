data "aws_ssm_parameter" "openmind-container-id" {
  name = "/openmind/${var.env}/container-id"
}

locals {
  ecs-cluster-name     = "ecs-${var.env}"
  elastic-container-id = "elasticsearch:7.4.0"
  ecs-instance-tags    = {}
  internal-ns          = "${var.env}.openmind.local"
}

# This is mostly just ripped out of
# https://github.com/terraform-aws-modules/terraform-aws-ecs/blob/master/examples/ec2-autoscaling/main.tf
# without complete understanding.

module "autoscaling_sg" {
  source  = "terraform-aws-modules/security-group/aws"
  version = "5.1.0"

  name        = "${var.env}-asg-sg"
  description = "Autoscaling group security group"
  vpc_id      = module.vpc.vpc_id

  computed_ingress_with_source_security_group_id = [
    {
      rule                     = "http-80-tcp"
      source_security_group_id = module.alb_sg.security_group_id
    }
  ]
  number_of_computed_ingress_with_source_security_group_id = 1

  egress_rules = ["all-all"]

  tags = {
    terraform = true
    env       = var.env
  }
}

data "aws_ssm_parameter" "ecs_optimized_ami" {
  name = "/aws/service/ecs/optimized-ami/amazon-linux-2023/arm64/recommended"
}

module "spots" {
  source  = "terraform-aws-modules/autoscaling/aws"
  version = "7.3.1"

  name = "${var.env}-asg"

  min_size          = 0
  max_size          = 2
  health_check_type = "EC2"

  credit_specification = {
    cpu_credits = "standard"
  }

  ebs_optimized = false
  instance_type = "t4g.small"

  vpc_zone_identifier = module.vpc.private_subnets

  image_id = jsondecode(data.aws_ssm_parameter.ecs_optimized_ami.value)["image_id"]

  autoscaling_group_tags = {
    AmazonECSManaged = true
  }

  create_iam_instance_profile = true
  iam_role_name               = local.ecs-cluster-name
  iam_role_description        = "ECS role for ${local.ecs-cluster-name}"
  iam_role_policies = {
    AmazonEC2ContainerServiceforEC2Role = "arn:aws:iam::aws:policy/service-role/AmazonEC2ContainerServiceforEC2Role"
    AmazonSSMManagedInstanceCore        = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
  }

  security_groups = [module.autoscaling_sg.security_group_id]

  use_mixed_instances_policy = true
  mixed_instances_policy = {
    instances_distribution = {
      on_demand_base_capacity                  = 0
      on_demand_percentage_above_base_capacity = 0
      spot_allocation_strategy                 = "price-capacity-optimized"
    }
  }

  user_data = base64encode(<<-EOT
        #!/bin/bash

        cat <<'EOF' >> /etc/ecs/ecs.config
        ECS_CLUSTER=${local.ecs-cluster-name}
        ECS_LOGLEVEL=debug
        ECS_CONTAINER_INSTANCE_TAGS=${jsonencode(local.ecs-instance-tags)}
        ECS_ENABLE_TASK_IAM_ROLE=true
        ECS_ENABLE_SPOT_INSTANCE_DRAINING=true
        EOF
      EOT
  )

  tags = {
    terraform = true
    env       = var.env
  }
}

module "ecs_cluster" {
  source  = "terraform-aws-modules/ecs/aws//modules/cluster"
  version = "5.7.4"

  cluster_name = local.ecs-cluster-name

  cluster_configuration = {
    execute_command_configuration = {
      logging = "OVERRIDE"
      log_configuration = {
        cloud_watch_log_group_name = "/aws/ecs/aws-ec2/${var.env}"
      }
    }
  }

  default_capacity_provider_use_fargate = false

  autoscaling_capacity_providers = {
    default = {
      auto_scaling_group_arn = module.spots.autoscaling_group_arn

      managed_scaling = {
        maximum_scaling_step_size = 1
        minimum_scaling_step_size = 1
        status                    = "ENABLED"
      }

      default_capacity_provider_strategy = {
        weight = 1
        # REVIEW: What does `base` do?
        base = 1
      }
    }
  }

  tags = {
    terraform   = true
    environment = var.env
  }
}

locals {
}

resource "aws_service_discovery_private_dns_namespace" "openmind" {
  name        = local.internal-ns
  description = "Internal ECS service discovery domain"
  vpc         = module.vpc.vpc_id
}

resource "aws_security_group" "internal-services" {
  name        = "openmind-${var.env}-ecs-internal"
  description = "Allows traffic to services from private subnets."
  vpc_id      = module.vpc.vpc_id

  ingress {
    description = "elastic"
    from_port   = 9200
    to_port     = 9200
    protocol    = "tcp"
    cidr_blocks = module.vpc.private_subnets_cidr_blocks
  }
}
module "elastic-service" {
  source  = "terraform-aws-modules/ecs/aws//modules/service"
  version = "5.7.4"

  name        = "elastic"
  cluster_arn = module.ecs_cluster.arn

  cpu    = 1024
  memory = 2048

  # launch_type = "EC2"
  requires_compatibilities = ["EC2"]

  container_definitions = {
    elasticsearch = {
      cpu       = 1024
      memory    = 2048
      essential = true
      image     = local.elastic-container-id

      port_mappings = [
        {
          name          = "elasticsearch"
          containerPort = 9200
          protocol      = "tcp"
        },
        {
          name          = "elasticsearch-internal"
          containerPort = 9300
          protocol      = "tcp"
        }
      ]
    }
  }

  capacity_provider_strategy = {
    default = {
      capacity_provider = module.ecs_cluster.autoscaling_capacity_providers["default"].name
      weight            = 1
      base              = 1
    }
  }

  subnet_ids = module.vpc.private_subnets

  service_connect_configuration = {
    namespace = local.internal-ns

    service = {
      client_alias = {
        port     = 9200
        dns_name = "elasticsearch"
      }
      port_name      = "elasticsearch"
      discovery_name = "elasticsearch"
    }
  }

  security_group_rules = {
    inbound = {
      type                     = "ingress"
      from_port                = 9200
      to_port                  = 9200
      protocol                 = "tcp"
      description              = "elasticsearch"
      source_security_group_id = aws_security_group.internal-services.id
    }
    # ES instances need to talk to each other
    # REVIEW: Do I need to open 9300 as well?
    outbound = {
      type                     = "egress"
      from_port                = 9200
      to_port                  = 9200
      protocol                 = "tcp"
      description              = "elasticsearch"
      source_security_group_id = aws_security_group.internal-services.id
    }
  }
  tags = {
    terraform = true
    env       = var.env
  }
}

module "openmind-service" {
  source  = "terraform-aws-modules/ecs/aws//modules/service"
  version = "5.7.4"

  name        = "openmind-backend"
  cluster_arn = module.ecs_cluster.arn

  cpu    = 1024
  memory = 1800

  # launch_type = "EC2"
  requires_compatibilities = ["EC2"]

  service_connect_configuration = {
    namespace = local.internal-ns
  }

  capacity_provider_strategy = {
    default = {
      capacity_provider = module.ecs_cluster.autoscaling_capacity_providers["default"].name
      weight            = 1
      base              = 1
    }
  }

  container_definitions = {
    openmind-service = {
      cpu       = 1024
      memory    = 1800
      essential = true
      image     = "${aws_ecr_repository.openmind.repository_url}:${data.aws_ssm_parameter.openmind-container-id.value}"

      port_mappings = [
        {
          name          = "http"
          containerPort = 8080
          protocol      = "tcp"
        }
      ]
    }
  }

  load_balancer = {
    openmind-service = {
      target_group_arn = aws_lb_target_group.openmind.arn
      container_name   = "openmind-service"
      container_port   = var.container_port
    }
  }

  subnet_ids = module.vpc.private_subnets

  security_group_rules = {
    alb_http_ingress = {
      type                     = "ingress"
      from_port                = var.container_port
      to_port                  = var.container_port
      protocol                 = "tcp"
      description              = "openmind service port"
      source_security_group_id = module.alb_sg.security_group_id
    }
    elastic = {
      type                     = "egress"
      from_port                = 9200
      to_port                  = 9200
      protocol                 = "tcp"
      description              = "elasticsearch"
      source_security_group_id = aws_security_group.internal-services.id
    }
  }

  tags = {
    terraform = true
    env       = var.env
  }
}
