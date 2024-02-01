data "aws_ssm_parameter" "openmind-container-id" {
  name = "/openmind/${var.env}/container-id"
}

locals {
  elastic-container-id = "docker.io/elasticsearch:7.4.0"
  internal-ns          = "${var.env}.openmind.local"
}

# This is mostly just ripped out of
# https://github.com/terraform-aws-modules/terraform-aws-ecs/blob/master/examples/ec2-autoscaling/main.tf
# without complete understanding.

module "ecs_cluster" {
  source  = "terraform-aws-modules/ecs/aws//modules/cluster"
  version = "5.7.4"

  cluster_name = "ecs-${var.env}"

  cluster_configuration = {
    execute_command_configuration = {
      logging = "OVERRIDE"
      log_configuration = {
        cloud_watch_log_group_name = "/aws/ecs/aws-ec2/${var.env}"
      }
    }
  }

  default_capacity_provider_use_fargate = true

  fargate_capacity_providers = {
    FARGATE_SPOT = {
      default_capacity_provider_strategy = {
        weight = 1
        base   = 1
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
    cidr_blocks = module.vpc.public_subnets_cidr_blocks
  }
}
module "elastic-service" {
  source  = "terraform-aws-modules/ecs/aws//modules/service"
  version = "5.7.4"

  create = false
  
  name        = "elastic"
  cluster_arn = module.ecs_cluster.arn

  cpu    = 256
  memory = 2048

  enable_autoscaling = false

  subnet_ids       = module.vpc.public_subnets
  assign_public_ip = true

  container_definitions = {
    elasticsearch = {
      cpu       = 256
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
    # We need to be able to fetch the docker container.
    out = {
      type        = "egress"
      from_port   = 0
      to_port     = 0
      protocol    = "-1"
      cidr_blocks = ["0.0.0.0/0"]
    }
    outipv6 = {
      type             = "egress"
      from_port        = 0
      to_port          = 0
      protocol         = "-1"
      ipv6_cidr_blocks = ["::/0"]
    }
    # ES instances need to talk to each other
    # REVIEW: Do I need to open 9300 as well?
    # outbound = {
    #   type                     = "egress"
    #   from_port                = 9200
    #   to_port                  = 9200
    #   protocol                 = "tcp"
    #   description              = "elasticsearch"
    #   source_security_group_id = aws_security_group.internal-services.id
    # }
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

  cpu    = 256
  memory = 1024

  enable_autoscaling = false

  subnet_ids       = module.vpc.public_subnets
  assign_public_ip = true

  service_connect_configuration = {
    namespace = local.internal-ns
  }

  health_check_grace_period_seconds = 600

  container_definitions = {
    openmind-service = {
      cpu       = 256
      memory    = 1024
      essential = true
      image     = "${aws_ecr_repository.openmind.repository_url}:${data.aws_ssm_parameter.openmind-container-id.value}"

      port_mappings = [
        {
          name          = "http"
          containerPort = var.container_port
          protocol      = "tcp"
        }
      ]

      environment = [
        {
          name  = "PORT"
          value = var.container_port
        },
        {
          name  = "ELASTIC_URL"
          value = "elasticsearch.${var.env}.openmind.local"
        },
        {
          name  = "ELASTIC_EXTRACT_INDEX"
          value = "extracts"
        },
        {
          name  = "JVM_OPTS"
          value = "-Xms900m -Xmx900m"
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

  security_group_rules = {
    alb_http_ingress = {
      type                     = "ingress"
      from_port                = var.container_port
      to_port                  = var.container_port
      protocol                 = "tcp"
      description              = "openmind service port"
      source_security_group_id = module.alb_sg.security_group_id
    }
    out = {
      type        = "egress"
      from_port   = 0
      to_port     = 0
      protocol    = "-1"
      cidr_blocks = ["0.0.0.0/0"]
    }
    outipv6 = {
      type             = "egress"
      from_port        = 0
      to_port          = 0
      protocol         = "-1"
      ipv6_cidr_blocks = ["::/0"]
    }
    # elastic = {
    #   type                     = "egress"
    #   from_port                = 9200
    #   to_port                  = 9200
    #   protocol                 = "tcp"
    #   description              = "elasticsearch"
    #   source_security_group_id = aws_security_group.internal-services.id
    # }
  }

  tags = {
    terraform = true
    env       = var.env
  }
}