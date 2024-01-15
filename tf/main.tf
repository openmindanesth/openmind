terraform {
  backend "s3" {
    region         = var.region
    bucket         = module.s3_bucket.openmind-tf-state.s3_bucket_id
    key            = "terraform-state/terraform.tfstate"
    dynamodb_table = aws_dynamodb_table.openmind-terraform-lock.id
    profile        = var.profile
  }
}

module "deployment" "dev" {
  source = "git@github.com:openmindanesth/openmind//tf/deployment?ref=master"

  env            = "dev"
  region         = "ca-central-1"
  container_port = 8080

  # Just one zone for testing. 
  cidr            = "10.0.0.0/16"
  zones             = ["ca-central-1a", "ca-central-1b", "ca-central-1d"]
  public_subnets  = ["10.0.1.0/24", "10.0.2.0/24", "10.0.3.0/24"]
  private_subnets = ["10.0.128.0/24", "10.0.129.0/24", "10.0.130.0/24"]
}
