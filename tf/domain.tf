# Certificates for cloudfront need to be in us-east-1.
# I don't know why.
provider "aws" {
  alias   = "us-east"
  region  = "us-east-1"
  profile = var.profile
}

resource "aws_route53_zone" "openmind" {
  name = "openmind.macroexpanse.com"

  tags = {
    terraform = true
  }
}

resource "aws_acm_certificate" "main" {
  provider = aws.us-east

  domain_name       = "openmind.macroexpanse.com"
  validation_method = "DNS"

  lifecycle {
    create_before_destroy = true
  }

  tags = {
    terraform = true
  }
}

resource "aws_route53_record" "record_validation" {
  zone_id = aws_route53_zone.openmind.id

  name    = tolist(aws_acm_certificate.main.domain_validation_options)[0].resource_record_name
  type    = tolist(aws_acm_certificate.main.domain_validation_options)[0].resource_record_type
  records = [tolist(aws_acm_certificate.main.domain_validation_options)[0].resource_record_value]

  ttl             = "60"
  allow_overwrite = true
}

resource "aws_acm_certificate_validation" "certificate_validation" {
  provider = aws.us-east

  certificate_arn         = aws_acm_certificate.main.arn
  validation_record_fqdns = [aws_route53_record.record_validation.fqdn]
}

