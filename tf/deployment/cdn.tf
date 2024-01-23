module "cdn" {
  source  = "terraform-aws-modules/cloudfront/aws"
  version = "3.2.1"

  enabled             = true
  is_ipv6_enabled     = true
  price_class         = "PriceClass_All"
  retain_on_delete    = false
  wait_for_deployment = false

  create_origin_access_identity = true

  origin_access_identities = {
    static_assets = "static assets for site"
    datastore     = "extract data"
  }

  logging_config = {
    bucket = module.cdn-logs.s3_bucket_bucket_domain_name
  }

  origin = {
    openmind_service = {
      domain_name = aws_lb.openmind.dns_name

      custom_origin_config = {
        http_port = 80
        # FIXME: No ssl to backend in current impl.
        https_port             = 443
        origin_protocol_policy = "match-viewer"
        origin_ssl_protocols   = ["TLSv1.2"]
      }
    }

    static_assets = {
      domain_name = module.openmind-assets.s3_bucket_bucket_domain_name
      s3_origin_config = {
        origin_access_identity = "static_assets"
      }
    }

    extract_store = {
      domain_name = module.openmind-data.s3_bucket_bucket_domain_name
      s3_origin_config = {
        origin_access_identity = "datastore"
      }
    }
  }

  default_cache_behavior = {
    target_origin_id       = "openmind_service"
    viewer_protocol_policy = "redirect-to-https"

    cache_policy_id          = "4135ea2d-6df8-44a3-9df3-4b5a84be39ad"
    origin_request_policy_id = "216adef6-5c7f-47e4-b989-5492eafa07d3"

    # Pass all methods back to origin
    allowed_methods = ["GET", "HEAD", "OPTIONS", "POST", "PUT", "DELETE", "PATCH"]

    compress = true

    use_forwarded_values = false
  }

  ordered_cache_behavior = [
    {
      path_pattern           = "/assets/*"
      target_origin_id       = "static_assets"
      viewer_protocol_policy = "redirect-to-https"

      allowed_methods = ["GET", "HEAD", "OPTIONS"]
      cached_methods  = ["GET", "HEAD"]
      compress        = true
      query_string    = true
    },
    {
      path_pattern           = "/extracts/gen1/*"
      target_origin_id       = "extract_store"
      viewer_protocol_policy = "redirect-to-https"

      allowed_methods = ["GET", "HEAD", "OPTIONS"]
      cached_methods  = ["GET", "HEAD"]
      compress        = true
      query_string    = true
    }
  ]

  aliases = ["openmind.macroexpanse.com"]

  viewer_certificate = {
    minimum_protocol_version = "TLSv1.2_2021"
    acm_certificate_arn      = var.cdn_cert_arn
    ssl_support_method       = "sni-only"
  }
}

resource "aws_route53_record" "openmind" {
  zone_id = var.dns_zone
  name    = "openmind.macroexpanse.com"
  type    = "A"

  alias {
    name    = module.cdn.cloudfront_distribution_domain_name
    zone_id = module.cdn.cloudfront_distribution_hosted_zone_id

    evaluate_target_health = false
  }
}
