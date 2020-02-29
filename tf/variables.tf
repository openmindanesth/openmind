variable "image_id" {
  type = string
}

variable "cpu" {
  type    = number
  default = 512
}

variable "memory" {
  type    = number
  default = 1024
}

variable "container-port" {
  type    = number
  default = 8080
}

variable "host-port" {
  type    = number
  default = 8080
}

variable "jvm-opts" {
  type    = string
  default = "-Xmx900m"
}
