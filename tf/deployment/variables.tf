variable "container_port" {
  type    = number
  default = 8080
}

variable "region" {
  type    = string
  default = "ca-central-1"
}

variable "env" {
  type    = string
  default = "dev"
}

variable "zones" {
  type = list(any)
}

variable "public_subnets" {
  type = list(any)
}

variable "private_subnets" {
  type = list(any)
}

variable "cidr" {
  type = string
}

variable "dns_zone" {
  type = string
}

variable "cdn_cert_arn" {
  type = string
}
