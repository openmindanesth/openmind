
terraform {
  backend "s3" {
    region         = "eu-central-1"
    bucket         = "openmind-terraform-state-29031"
    key            = "terraform-state/terraform.tfstate"
    dynamodb_table = "openmind-tf-lock-table"
    profile        = "openmind-eu"
  }
}

##### S3 bucket for data

resource "aws_s3_bucket" "openmind-data" {
  bucket = "openmind-datastore-bucket-1"
  region = "eu-central-1"

  versioning {
    enabled = true
  }

  lifecycle {
    prevent_destroy = true
  }

  cors_rule {
    allowed_headers = ["*"]
    allowed_methods = ["GET"]
    allowed_origins = ["https://openmind.macroexpanse.com"]
    expose_headers  = ["ETag"]
    max_age_seconds = 100000
  }
}

##### Security

resource "aws_security_group" "lb" {
  name        = "Load Balancer"
  description = "Allow inbound traffic over http/https"
  vpc_id      = aws_vpc.openmind.id

  ingress {
    from_port   = 443
    to_port     = 443
    protocol    = 6
    cidr_blocks = ["0.0.0.0/0"]
  }
  ingress {
    from_port   = 80
    to_port     = 80
    protocol    = 6
    cidr_blocks = ["0.0.0.0/0"]
  }
  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

resource "aws_security_group" "ecs" {
  name   = "ECS security group"
  vpc_id = aws_vpc.openmind.id

  ingress {
    from_port   = var.container-port
    to_port     = var.container-port
    protocol    = 6
    cidr_blocks = ["0.0.0.0/0"]
  }
  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

##### Load Balancer

## vpc

resource "aws_vpc" "openmind" {
  cidr_block           = "10.0.0.0/16"
  enable_dns_support   = true
  enable_dns_hostnames = true
}

resource "aws_internet_gateway" "gw" {
  vpc_id = aws_vpc.openmind.id
}

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.openmind.id

  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.gw.id
  }
}

resource "aws_subnet" "www" {
  vpc_id            = aws_vpc.openmind.id
  availability_zone = "eu-central-1a"
  cidr_block        = "10.0.0.0/24"

  map_public_ip_on_launch = true
  depends_on              = [aws_internet_gateway.gw]
}

resource "aws_subnet" "www2" {
  vpc_id            = aws_vpc.openmind.id
  availability_zone = "eu-central-1b"
  cidr_block        = "10.0.1.0/24"

  map_public_ip_on_launch = true
  depends_on              = [aws_internet_gateway.gw]
}

resource "aws_route_table_association" "www" {
  subnet_id      = aws_subnet.www.id
  route_table_id = aws_route_table.public.id
}


resource "aws_route_table_association" "www2" {
  subnet_id      = aws_subnet.www2.id
  route_table_id = aws_route_table.public.id
}

## logs 

resource "aws_s3_bucket" "prod-lb-logs" {
  bucket = "openmind-prod-lb-logs"
  acl    = "private"

  lifecycle_rule {
    id      = "prod-lb-logs"
    enabled = true

    transition {
      days          = 30
      storage_class = "STANDARD_IA"
    }
  }
}

resource "aws_s3_bucket_policy" "prod-lb-logs" {
  bucket = aws_s3_bucket.prod-lb-logs.id

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
      "Resource": "arn:aws:s3:::${aws_s3_bucket.prod-lb-logs.id}/*",
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

## lb

resource "aws_lb" "openmind" {
  name               = "openmind-lb"
  internal           = false
  load_balancer_type = "application"
  security_groups    = [aws_security_group.lb.id]

  subnets      = [aws_subnet.www.id, aws_subnet.www2.id]
  enable_http2 = true

  access_logs {
    bucket  = aws_s3_bucket.prod-lb-logs.bucket
    prefix  = "openmind"
    enabled = false
  }
}

resource "aws_lb_target_group" "openmind" {
  name        = "openmind-lb"
  port        = 8080
  protocol    = "HTTP"
  slow_start  = 75
  target_type = "ip"
  vpc_id      = aws_vpc.openmind.id

  health_check {
    enabled  = true
    path     = "/"
    port     = "traffic-port"
    interval = 120
    timeout  = 90
    matcher  = "200-299"
  }
}

resource "aws_lb_listener" "openmind" {
  load_balancer_arn = aws_lb.openmind.arn
  port              = "443"
  protocol          = "HTTPS"
  ssl_policy        = "ELBSecurityPolicy-2016-08"
  certificate_arn   = "arn:aws:acm:eu-central-1:445482884655:certificate/4eca6545-374a-43aa-88fc-9a4c74f1c7d6"
  depends_on        = [aws_lb_target_group.openmind]

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.openmind.arn
  }
}

resource "aws_lb_listener" "redirect_http_to_https" {
  load_balancer_arn = aws_lb.openmind.arn
  port              = "80"
  protocol          = "HTTP"

  default_action {
    type = "redirect"

    redirect {
      port        = "443"
      protocol    = "HTTPS"
      status_code = "HTTP_301"
    }
  }
}

##### ECS

resource "aws_ecr_repository" "openmind" {
  name                 = "openmind"
  image_tag_mutability = "IMMUTABLE"
}

resource "aws_ecs_cluster" "openmind" {
  name               = "openmind"
  capacity_providers = ["FARGATE"]

  default_capacity_provider_strategy {
    capacity_provider = "FARGATE"
  }
}

resource "aws_ecs_service" "openmind" {
  name            = "openmind"
  cluster         = aws_ecs_cluster.openmind.id
  task_definition = aws_ecs_task_definition.openmind-web.arn
  desired_count   = 1
  launch_type     = "FARGATE"

  network_configuration {
    assign_public_ip = true
    subnets          = [aws_subnet.www.id, aws_subnet.www2.id]
    security_groups  = [aws_security_group.ecs.id]
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.openmind.arn
    container_name   = "openmind-webserver"
    container_port   = var.container-port
  }
}

output "ECR-URL" {
  value = aws_ecr_repository.openmind.repository_url
}

resource "aws_iam_role" "ecs-task-role" {
  name = "ecs-task-role"

  assume_role_policy = <<EOF
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Action": "sts:AssumeRole",
      "Principal": {
        "Service": [
          "ecs.amazonaws.com",
          "ecs-tasks.amazonaws.com"
        ]
      },
      "Effect": "Allow",
      "Sid": ""
    }
  ]
}
EOF

}

resource "aws_iam_role" "ecs-execution-role" {
  name = "ecs-execution-role"

  assume_role_policy = <<EOF
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Action": "sts:AssumeRole",
      "Principal": {
        "Service": [
          "ecs.amazonaws.com",
          "ecs-tasks.amazonaws.com"
        ]
      },
      "Effect": "Allow",
      "Sid": ""
    }
  ]
}
EOF
}

resource "aws_iam_role_policy_attachment" "attach-ecs-task-execution-policy" {
  role       = aws_iam_role.ecs-execution-role.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

resource "aws_ecs_task_definition" "openmind-web" {
  family                   = "openmind"
  network_mode             = "awsvpc"
  requires_compatibilities = ["FARGATE"]
  cpu                      = var.cpu
  memory                   = var.memory
  task_role_arn            = aws_iam_role.ecs-task-role.arn
  execution_role_arn       = aws_iam_role.ecs-execution-role.arn
  container_definitions    = <<DEF
[{"name": "openmind-webserver",
	 "essential": true,
	 "image": "${aws_ecr_repository.openmind.repository_url}:${var.image_id}",
   "networkMode": "awsvpc",
	 "cpu": ${var.cpu},
	 "memory": ${var.memory},
	 "startTimeout": 120,
	 "portMappings": [{"containerPort":${var.container-port},
										 "hostPort":${var.host-port}}],
		"environment": [{"name": "JVM_OPTS",
										 "value": "${var.jvm-opts}"},
                    {"name": "PORT",
                     "value": "${var.host-port}"},
										{"name": "ORCID_REDIRECT_URI",
										 "value": "/oauth2/orcid/redirect"}]
	}]
DEF
}

##### CI User