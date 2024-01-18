provider "aws" {
  profile = var.profile
  region  = var.region
}

#### DIY backend

resource "random_id" "tf-rmstate" {
  byte_length = 2
}

module "openmind-tf-state" {
  source  = "terraform-aws-modules/s3-bucket/aws"
  version = "4.0.1"

  bucket = "openmind-terraform-state-${random_id.tf-rmstate.dec}"

  acl = "private"

  control_object_ownership = true
  object_ownership = "BucketOwnerPreferred"

  versioning = {
    enabled = true
  }

  tags = {
    terraform = "true"
    env       = "prod"
    name      = "tf-state-bucket"
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
    terraform = "true"
    env       = "prod"
    name      = "tf-state-lock"
  }
}

# These are only needed to configure the S3 backend.
#
# output state_bucket {
#   value = module.openmind-tf-state.s3_bucket_id
# }

# output state_table {
#   value = aws_dynamodb_table.openmind-terraform-lock.id
# }
