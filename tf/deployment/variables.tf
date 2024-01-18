variable container_port {
  type    = number
  default = 8080
}

variable region {
  type = string
  default ="ca-central-1"
}

variable env {
  type = string
  default = "dev"
}
  
variable zones {
  type = list
}

variable public_subnets {
  type = list
}

variable private_subnets {
  type = list
}

variable cidr {
  type = string
}
