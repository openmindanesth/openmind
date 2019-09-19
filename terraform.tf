provider "aws" {
  profile = "openmind"
  region  = "eu-central-1"
}

terraform {
	backend "s3" {
		encrypt = true
		bucket = "openmind-terraform-state"
		region = "eu-central-1"
		key = "terraform-state/terraform.tfstate"
		# dynamodb_table = "openmind-terraform-lock"
		profile = "openmind"
	}
}

# variable "image_id" {
# 	type = string
# }

#### DIY backend

resource "aws_s3_bucket" "terraform-state-backend" {
	bucket = "openmind-terraform-state"
	region = "eu-central-1"

	versioning {
		enabled = true
	}

	lifecycle {
		prevent_destroy = true
	}

	tags = {
		env = "prod"
		name = "Bucket to store terraform state"
		project = "openmind"
	}
}

resource "aws_dynamodb_table" "openmind-terraform-lock" {
	name = "openmind-lock-table"
	billing_mode = "PAY_PER_REQUEST"
	hash_key = "LockID"

	attribute {
		name = "LockID"
		type = "S"
	}

	tags = {
		name = "Openmind Terraform lock state table"
	}
}

##### S3 for figures

resource "aws_s3_bucket" "openmind-images" {
	bucket = "openmind-figure-bucket"
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
    allowed_origins = ["https://openmind-eu.herokuapp.com"]
    expose_headers  = ["ETag"]
    max_age_seconds = 100000
  }
}

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
