module "cdn" {
  source  = "terraform-aws-modules/cloudfront/aws"
  version = "3.2.1"

  aliases = ["openmind.macroexpanse.com"]

  enabled             = true
  is_ipv6_enabled     = true
  price_class         = "PriceClass_All"
  retain_on_delete    = false
  wait_for_deployment = false

  create_origin_access_identity = true

  origin_access_identities = {
    static_assets = "static assets for site"
    datastore = "extract data"
  }

  logging_config = {
    bucket = module.cdn-logs.s3_bucket_id
  }

  origin = {
    openmind_service = {
      domain_name = module.alb.dns_name

      custom_origin_config = {
        http_port              = 80
        https_port             = 443
        origin_protocol_policy = "match-viewer"
        origin_ssl_protocols   = ["TLSv1", "TLSv1.1", "TLSv1.2"]
      }
    }

    static_assets = {
      domain_name = module.openmind-assets.s3_bucket_id
      s3_origin_config = {
        origin_access_identity = "static_assets"
      }
    }

    extract_store = {
      domain_name = module.openmind-data.s3_bucket_id
      s3_origin_config = {
        origin_access_identity = "datastore"
      }
    }
  }

  default_cache_behavior = {
    target_origin_id           = "openmind_service"
    viewer_protocol_policy     = "allow-all"

    # TODO: Set methods
    # allowed_methods = ["GET", "HEAD", "OPTIONS", "POST"]
    cached_methods  = []
    compress        = true
    query_string    = true
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

  # FIXME: Cert for openmind.macroexpanse.com

  # viewer_certificate = {
  #   acm_certificate_arn = "???"
  #   ssl_support_method  = "sni-only"
  # }
}
