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
