variable "github" {
  type = object({
    org        = string
    repository = string
  })
  description = "GitHub Organization and repository name"
  default = {
    org        = "pagopa"
    repository = "pagopa-ecommerce-transactions-service"
  }
}

variable "env" {
  type        = string
  description = "Environment"
}