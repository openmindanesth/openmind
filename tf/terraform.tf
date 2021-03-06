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

resource "aws_s3_bucket" "openmind-logs" {
	bucket = "openmind-prod-logging"
	acl = "log-delivery-write"
}

resource "aws_s3_bucket" "openmind-data" {
  bucket = "openmind-datastore-bucket-1"
  region = "eu-central-1"

  versioning {
    enabled = true
  }

  lifecycle {
    prevent_destroy = true
  }

	logging {
		target_bucket = aws_s3_bucket.openmind-logs.id
		target_prefix = "openmind-datastore-logs/"
	}

  cors_rule {
    allowed_headers = ["*"]
    allowed_methods = ["GET"]
    allowed_origins = ["https://openmind.macroexpanse.com"]
    expose_headers  = ["ETag"]
    max_age_seconds = 32000000
  }
}

resource "aws_s3_bucket_policy" "openmind-data-public-access" {
  bucket = aws_s3_bucket.openmind-data.id

  policy = <<EOF
{
  "Version":"2012-10-17",
  "Statement":[
    {
      "Sid":"PublicRead",
      "Effect":"Allow",
      "Principal": "*",
      "Action":["s3:GetObject"],
      "Resource":["${aws_s3_bucket.openmind-data.arn}/*"]
    },
    {
      "Sid":"PublicList",
      "Effect":"Allow",
      "Principal": "*",
      "Action":["s3:ListBucket"],
      "Resource":["${aws_s3_bucket.openmind-data.arn}"]
    }
  ]
}
EOF
}

resource "aws_s3_bucket" "openmind-test-logs" {
	bucket = "openmind-test-data-logs"
	acl = "log-delivery-write"
}

resource "aws_s3_bucket" "openmind-test-data" {
  bucket = "test-data-17623"
  region = "eu-central-1"

	versioning {
		enabled = true
	}

	logging {
		target_bucket = aws_s3_bucket.openmind-test-logs.id
		target_prefix = "openmind-datastore-logs/"
	}

  cors_rule {
    allowed_headers = ["*"]
    allowed_methods = ["GET"]
    allowed_origins = ["*"]
    expose_headers  = ["ETag"]
    max_age_seconds = 32000000
  }

}

resource "aws_s3_bucket_policy" "test-data-public-access" {
  bucket = aws_s3_bucket.openmind-test-data.id

  policy = <<EOF
{
  "Version":"2012-10-17",
  "Statement":[
    {
      "Sid":"PublicRead",
      "Effect":"Allow",
      "Principal": "*",
      "Action":["s3:GetObject"],
      "Resource":["${aws_s3_bucket.openmind-test-data.arn}/*"]
    },
    {
      "Sid":"PublicList",
      "Effect":"Allow",
      "Principal": "*",
      "Action":["s3:ListBucket"],
      "Resource":["${aws_s3_bucket.openmind-test-data.arn}"]
    }
  ]
}
EOF
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
    cidr_blocks = ["10.0.0.0/16"]
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
  ssl_policy        = "ELBSecurityPolicy-TLS-1-2-Ext-2018-06"
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

##### Elastic Search

resource "aws_iam_service_linked_role" "es" {
  aws_service_name = "es.amazonaws.com"
}

resource "aws_security_group" "es" {
  name   = "openmind-elasticsearch"
  vpc_id = aws_vpc.openmind.id

  ingress {
    from_port = 443
    to_port   = 443
    protocol  = "tcp"

    cidr_blocks = ["10.0.0.0/16", ]
  }
}

resource "aws_elasticsearch_domain" "openmind" {
  domain_name           = "openmind-production"
  elasticsearch_version = "7.4"

  cluster_config {
    instance_type  = "t2.small.elasticsearch"
    instance_count = 1
  }

  snapshot_options {
    automated_snapshot_start_hour = 0
  }

  ebs_options {
    ebs_enabled = true
    volume_type = "standard"
    volume_size = 10
  }

  domain_endpoint_options {
    enforce_https       = true
    tls_security_policy = "Policy-Min-TLS-1-2-2019-07"
  }

  vpc_options {
    subnet_ids         = [aws_subnet.www.id]
    security_group_ids = [aws_security_group.es.id]
  }

  depends_on = [aws_iam_service_linked_role.es]
}

resource "aws_elasticsearch_domain_policy" "access" {
  domain_name = aws_elasticsearch_domain.openmind.domain_name

  access_policies = <<POLICIES
{
		"Version": "2012-10-17",
		"Statement": [
				{
						"Action": "es:*",
						"Principal": "*",
						"Effect": "Allow",
						"Resource": "${aws_elasticsearch_domain.openmind.arn}/*"
				}
		]
}
POLICIES
}

output "ES_URL" {
  value = aws_elasticsearch_domain.openmind.endpoint
}

##### ECS

resource "aws_ecr_repository" "openmind" {
  name                 = "openmind"
  image_tag_mutability = "IMMUTABLE"
}

resource "aws_ecr_lifecycle_policy" "retention-policy" {
  repository = aws_ecr_repository.openmind.name

  policy = <<EOF
{
    "rules": [
        {
            "rulePriority": 1,
            "description": "Only keep 3 most recent images",
            "selection": {
                "tagStatus": "untagged",
                "countType": "imageCountMoreThan",
                "countNumber": 3
            },
            "action": {
                "type": "expire"
            }
        }
    ]
}
EOF
}

output "ECR-URL" {
  value = aws_ecr_repository.openmind.repository_url
}

resource "aws_ecs_cluster" "openmind" {
  name               = "openmind"
  capacity_providers = ["FARGATE"]

  default_capacity_provider_strategy {
    capacity_provider = "FARGATE"
  }
}

################################################################################
# The ECS service and initial task definition were created with terraform, but
# removed via `terraform state rm ...` so that they could be handled by the CD
# server without it needing permission to access the rest of the terraform
# state. I think that's better architecturally, but it means that this service
# isn't really managed by anything in code. It must be deleted and rebuilt
# manually if it ever needs to change. The config here is left as an example.
################################################################################

# resource "aws_ecs_service" "openmind" {
#   name            = "openmind"
#   cluster         = aws_ecs_cluster.openmind.id
#   task_definition = aws_ecs_task_definition.openmind-web.arn
#   desired_count   = 1
#   launch_type     = "FARGATE"

#   network_configuration {
#     assign_public_ip = true
#     subnets          = [aws_subnet.www.id, aws_subnet.www2.id]
#     security_groups  = [aws_security_group.ecs.id]
#   }

#   load_balancer {
#     target_group_arn = aws_lb_target_group.openmind.arn
#     container_name   = "openmind-webserver"
#     container_port   = var.container-port
#   }
# }

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
					"s3.amazonaws.com",
					"ec2.amazonaws.com",
					"ecs.amazonaws.com",
					"ecs-tasks.amazonaws.com"
				]
			},
			"Effect": "Allow",
			"Sid": "base"
		}
	]
}
EOF
}

resource "aws_iam_role_policy" "s3-data" {
  name = "openmind-s3-data-access"
  role = aws_iam_role.ecs-task-role.id

  policy = <<-EOF
  {
    "Version": "2012-10-17",
    "Statement": [
      {
			"Action": "s3:PutObject",
			"Resource": ["${aws_s3_bucket.openmind-data.arn}/*"],
			"Effect": "Allow",
			"Sid": "S3Access"
      }
    ]
  }
  EOF
}

resource "aws_iam_role" "dev-access" {
  name = "openmind-dev-role"

  assume_role_policy = <<EOF
{
	"Version": "2012-10-17",
	"Statement": [
		{
			"Action": "sts:AssumeRole",
			"Principal": {
				"Service": [
					"s3.amazonaws.com"
				]
			},
			"Effect": "Allow",
			"Sid": ""
		}
	]
}
EOF
}

resource "aws_iam_role_policy" "s3-test-data" {
  name = "openmind-s3-test-data-access"
  role = aws_iam_role.dev-access.id

  policy = <<-EOF
  {
    "Version": "2012-10-17",
    "Statement": [
      {
			"Action": "s3:PutObject",
			"Resource": ["${aws_s3_bucket.openmind-data.arn}/*"],
			"Effect": "Allow",
			"Sid": "S3Access"
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

output "task-arn" {
  value = aws_iam_role.ecs-task-role.arn
}

output "execution-arn" {
  value = aws_iam_role.ecs-execution-role.arn
}

## CI User


resource "aws_iam_group" "ci" {
  name = "ci"
}

resource "aws_iam_group_policy" "access-to-ecs" {
  name  = "openmind-ci-access-ecs"
  group = aws_iam_group.ci.id

  policy = <<EOF
{
  "Version": "2012-10-17",
  "Statement": [
   {

	 	"Action": [
               "iam:PassRole"
              ],
		"Resource": ["${aws_iam_role.ecs-task-role.arn}",
                 "${aws_iam_role.ecs-execution-role.arn}"],
		"Effect": "Allow",
		"Sid": "passrole"
	 },
   {

	 	"Action": [
               "ecs:RegisterTaskDefinition",
               "ecs:DescribeServices",
               "ecs:UpdateService"
              ],
		"Resource": ["*"],
		"Effect": "Allow",
		"Sid": "ecsaccess"
	 },
    {
		"Action": [
              "ecr:GetAuthorizationToken",
              "ecr:BatchCheckLayerAvailability",
              "ecr:GetDownloadUrlForLayer",
              "ecr:GetRepositoryPolicy",
              "ecr:DescribeRepositories",
              "ecr:ListImages",
              "ecr:DescribeImages",
              "ecr:BatchGetImage",
              "ecr:GetLifecyclePolicy",
              "ecr:GetLifecyclePolicyPreview",
              "ecr:ListTagsForResource",
              "ecr:DescribeImageScanFindings",
              "ecr:InitiateLayerUpload",
              "ecr:UploadLayerPart",
              "ecr:CompleteLayerUpload",
              "ecr:PutImage"
              ],
		"Resource": ["*"],
		"Effect": "Allow",
		"Sid": "ECRAccess"
		},
    {
      "Effect": "Allow",
      "Action": ["s3:ListBucket"],
      "Resource": ["${aws_s3_bucket.openmind-test-data.arn}"]
    },
    {
      "Effect": "Allow",
      "Action": [
        "s3:PutObject",
        "s3:GetObject",
        "s3:DeleteObject"
      ],
      "Resource": ["${aws_s3_bucket.openmind-test-data.arn}/*"]
    }
  ]
}
EOF
}
