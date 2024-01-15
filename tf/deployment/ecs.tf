resource "aws_ssm_parameter" "openmind-container-parameter" {
  name = "openmind/${var.env}/container-id"
  type = "String"
}

data "aws_ssm_parameter" "openmind-container-id" {
  name = "openmind/${var.env}/container-id"
}

locals {
  ecs-cluster-name     = "ecs-${var.env}"
  ecs-instance-tags    = {}
  elastic-container-id = "elasticsearch:7.4.0"
}

# This is mostly just ripped out of
# https://github.com/terraform-aws-modules/terraform-aws-ecs/blob/master/examples/ec2-autoscaling/main.tf
# without complete understanding.

module "autoscaling_sg" {
  source  = "terraform-aws-modules/security-group/aws"
  version = "~> 5.0"

  name        = "${var.env}-asg-sg"
  description = "Autoscaling group security group"
  vpc_id      = module.vpc.vpc_id

  computed_ingress_with_source_security_group_id = [
    {
      rule                     = "http-80-tcp"
      source_security_group_id = module.alb.security_group_id
    }
  ]
  number_of_computed_ingress_with_source_security_group_id = 1

  egress_rules = ["all-all"]

  tags = {
    terraform = true
    env       = var.env
  }
}

module "spots" {
  source  = "terraform-aws-modules/autoscaling/aws"
  version = "7.3.1"

  name = "${var.env}-asg"

  min_size          = 0
  max_size          = 2
  health_check_type = "EC2"

  credit_specification {
    cpu_credits = "standard"
  }

  ebs_optimized = false
  instance_type = "t4g.small"

  vpc_zone_identifier = module.vpc.private_subnets
  image_id            = jsondecode(data.aws_ssm_parameter.ecs_optimized_ami.value)["image_id"]

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
  mixed_instances_policy {
    instances_distribution {
      on_demand_base_capacity                  = 0
      on_demand_percentage_above_base_capacity = 0
      spot_allocation_strategy                 = "price-capacity-optimized"
    }

    override {
      instance_type     = "t4g.small"
      weighted_capacity = "1"
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

  default_provider_use_fargate = false

  cluster_configuration = {
    execute_command_configuration = {
      logging = "OVERRIDE"
      log_configuration = {
        cloud_watch_log_group_name = "/aws/ecs/aws-ec2/${var.env}"
      }
    }
  }

  autoscaling_capacity_providers = {
    one = {
      auto_scaling_group_arn = module.spots.autoscaling_groups_arn

      managed_scaling = {
        maximum_scaling_step_size = 1
        minimum_scaling_step_size = 1
        status                    = "ENABLED"
      }

      default_capacity_provider_strategy = {
        weight = 100
      }
    }
  }

  tags = {
    terraform   = true
    environment = var.env
  }
}

module "elastic-service" {
  source  = "terraform-aws-modules/ecs/aws//modules/service"
  version = "5.7.4"

  name        = "${var.env}-elasticsearch"
  cluster_arn = module.ecs_cluster.arn

  cpu    = 1024
  memory = 2048

  container_definitions = {
    elasticsearch = {
      cpu       = 1024
      memory    = 2048
      essential = true
      image     = local.elasic-container-id

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

  subnet_ids = module.vpc.private_subnets

  service_connect_configuration = {
    namespace = "openmind-${var.env}"

    service = {
      client_alias = {
        port     = 9200
        dns_name = "elasticsearch"
      }
      port_name      = "elasticsearch"
      discovery_name = "elasticsearch"
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

  name        = "${var.env}-openmind-service"
  cluster_arn = module.ecs_cluster.arn

  cpu    = 1024
  memory = 2048

  container_definitions = {
    openmind = {
      cpu       = 1024
      memory    = 2048
      essential = true
      image     = data.aws_ssm_parameter.openmind-container-id.value

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
      target_group_arn = module.alb.target_groups["openmind-service"].arn
      container_name   = "openmind"
      container_port   = 8080
    }
  }

  subnet_ids = module.vpc.private_subnets

  tags = {
    terraform = true
    env       = var.env
  }
}
