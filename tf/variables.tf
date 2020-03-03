variable "image-id" {
  type = string
}

variable "cpu" {
  type    = number
  default = 256
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

variable "orcid-redirect-uri" {
	type = string
	default = "https://openmind.macroexpanse.com/oauth2/orcid/redirect"
}

variable "orcid-client-id" {
	type = string
}

variable "orcid-client-secret" {
	type = string
}
