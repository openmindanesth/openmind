provider "aws" {
  profile = "openmind-eu"
  region  = "eu-central-1"
}

#### DIY backend

resource "random_id" "tc-rmstate" {
  byte_length = 2
}

resource "aws_s3_bucket" "openmind-tf-state" {
  bucket = "openmind-terraform-state-${random_id.tc-rmstate.dec}"
  region = "eu-central-1"

  versioning {
    enabled = true
  }

  lifecycle {
    prevent_destroy = true
  }

  tags = {
    env     = "prod"
    name    = "Bucket to store terraform state"
    project = "openmind"
  }
}

resource "aws_dynamodb_table" "openmind-terraform-lock" {
  name           = "openmind-tf-lock-table"
  billing_mode   = "PROVISIONED"
  read_capacity  = 20
  write_capacity = 20
  hash_key       = "LockID"

  attribute {
    name = "LockID"
    type = "S"
  }

  tags = {
    name = "Openmind Terraform lock state table"
  }
}
