##### S3 bucket for data


resource "random_id" "data-bucket-id" {
  byte_length = 4
}

module "openmind-data" {
  source = "terraform-aws-modules/s3-bucket/aws"
  version = "3.15.2"
  bucket = "openmind-datastore-${var.env}-${random_id.data-bucket-id}"

  versioning {
    enabled = true
  }

  tags = {
    terraform = "true"
    env     = var.env
    name    = "extract-store"
  }
}

##### Security


## logs


## lb


resource "aws_lb_target_group" "openmind" {
  name        = "openmind-lb"
  port        = 8080
  protocol    = "HTTP"
  slow_start  = 75
  target_type = "ip"
  vpc_id      = aws_vpc.openmind.id

  health_check {
    enabled  = true
    path     = "/"
    port     = "traffic-port"
    interval = 120
    timeout  = 90
    matcher  = "200-299"
  }
}

resource "aws_lb_listener" "openmind" {
  load_balancer_arn = aws_lb.openmind.arn
  port              = "443"
  protocol          = "HTTPS"
  ssl_policy        = "ELBSecurityPolicy-TLS-1-2-Ext-2018-06"
  certificate_arn   = "arn:aws:acm:eu-central-1:445482884655:certificate/4eca6545-374a-43aa-88fc-9a4c74f1c7d6"
  depends_on        = [aws_lb_target_group.openmind]

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.openmind.arn
  }
}

resource "aws_lb_listener" "redirect_http_to_https" {
  load_balancer_arn = aws_lb.openmind.arn
  port              = "80"
  protocol          = "HTTP"

  default_action {
    type = "redirect"

    redirect {
      port        = "443"
      protocol    = "HTTPS"
      status_code = "HTTP_301"
    }
  }
}

##### Elastic Search

resource "aws_iam_service_linked_role" "es" {
  aws_service_name = "es.amazonaws.com"
}

resource "aws_security_group" "es" {
  name   = "openmind-elasticsearch"
  vpc_id = aws_vpc.openmind.id

  ingress {
    from_port = 443
    to_port   = 443
    protocol  = "tcp"

    cidr_blocks = ["10.0.0.0/16", ]
  }
}

resource "aws_elasticsearch_domain" "openmind" {
  domain_name           = "openmind-production"
  elasticsearch_version = "7.4"

  cluster_config {
    instance_type  = "t2.small.elasticsearch"
    instance_count = 1
  }

  snapshot_options {
    automated_snapshot_start_hour = 0
  }

  ebs_options {
    ebs_enabled = true
    volume_type = "standard"
    volume_size = 10
  }

  domain_endpoint_options {
    enforce_https       = true
    tls_security_policy = "Policy-Min-TLS-1-2-2019-07"
  }

  vpc_options {
    subnet_ids         = [aws_subnet.www.id]
    security_group_ids = [aws_security_group.es.id]
  }

  depends_on = [aws_iam_service_linked_role.es]
}

resource "aws_elasticsearch_domain_policy" "access" {
  domain_name = aws_elasticsearch_domain.openmind.domain_name

  access_policies = <<POLICIES
{
		"Version": "2012-10-17",
		"Statement": [
				{
						"Action": "es:*",
						"Principal": "*",
						"Effect": "Allow",
						"Resource": "${aws_elasticsearch_domain.openmind.arn}/*"
				}
		]
}
POLICIES
}

output "ES_URL" {
  value = aws_elasticsearch_domain.openmind.endpoint
}

##### ECS
