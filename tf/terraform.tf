
terraform {
  backend "s3" {
    region  = "eu-central-1"
		bucket = "openmind-terraform-state-29031"
    key     = "terraform-state/terraform.tfstate"
    # dynamodb_table = "openmind-terraform-lock"
    profile = "openmind-eu"
  }
}

# #  arn:aws:acm:eu-central-1:445482884655:certificate/4eca6545-374a-43aa-88fc-9a4c74f1c7d6

# # variable "image_id" {
# # 	type = string
# # }


# ##### S3 for figures

# resource "aws_s3_bucket" "openmind-data" {
#   bucket = "openmind-datastore-bucket"
#   region = "eu-central-1"

#   versioning {
#     enabled = true
#   }

#   lifecycle {
#     prevent_destroy = true
#   }

#   cors_rule {
#     allowed_headers = ["*"]
#     allowed_methods = ["GET"]
#     allowed_origins = ["https://openmind.macroexpanse.com"]
#     expose_headers  = ["ETag"]
#     max_age_seconds = 100000
#   }
# }

##### ECS

# resource "aws_ecr_repository" "openmind" {
#   name = "openmind"
# }

# resource "aws_ecs_cluster" "openmind" {
# 	name = "openmind"
# }

# resource "aws_ecs_task_definition" "webserver" {
# 	network_mode = "awsvpc"
# 	family = "openmind"
# 	requires_compatibilities = ["FARGATE"]
# 	cpu = 512
# 	memory = 1024
# 	container_definitions = <<DEF
# [{"name": "openmind-webserver",
# 	 "essential": true,
# 	 "image": "${aws_ecr_repository.openmind.repository_url}:${var.image_id}",
# 	 "cpu": 512,
# 	 "memory": 1024,
# 	 "startTimeout": 120,
# 	 "portMappings": [{"containerPort":8080,
# 										 "hostPort":8080}],
# 		"environment": [{"name": "JVM_OPTS",
# 										 "value": ""},
# 										{"name": "ORCID_REDIRECT_URI",
# 										 "value": "/oauth2/orcid/redirect"}]
# 	}]
# DEF
# }

# resource "aws_ecs_service" "openmind" {
# 	name = "openmind"
# 	cluster = "${aws_ecs_cluster.openmind.id}"
# 	task_definition = "${aws_ecs_task_definition.webserver.id}"
# 	desired_count = 1
# 	launch_type = "FARGATE"
# }	

# 	output "ECR-URL" {
#   value = "${aws_ecr_repository.openmind.repository_url}"
# }
