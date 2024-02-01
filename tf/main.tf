terraform {
  backend "s3" {
    region         = "ca-central-1"
    bucket         = "openmind-terraform-state-9594"
    key            = "terraform-state/terraform.tfstate"
    dynamodb_table = "openmind-tf-lock-table"
    profile        = "openmind"
  }
}

module "dev" {
  # source = "git@github.com:openmindanesth/openmind//tf/deployment?ref=deployment-update"
  source = "./deployment"

  env    = "dev"
  region = "ca-central-1"

  container_port = 80

  dns_zone     = aws_route53_zone.openmind.id
  cdn_cert_arn = aws_acm_certificate.main.arn

  cidr            = "10.0.0.0/16"
  zones           = ["ca-central-1a", "ca-central-1b", "ca-central-1d"]
  public_subnets  = ["10.0.4.0/24", "10.0.5.0/24", "10.0.6.0/24"]
  private_subnets = ["10.0.128.0/24", "10.0.129.0/24", "10.0.130.0/24"]
}
