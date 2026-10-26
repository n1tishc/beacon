terraform {
  required_version = ">= 1.10"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.7"
    }
    tls = {
      source  = "hashicorp/tls"
      version = "~> 4.1"
    }
  }

  # The bucket comes from bootstrap/ and is passed at init time:
  #   terraform init -backend-config="bucket=<state bucket>" -backend-config="region=<region>"
  # use_lockfile is S3-native state locking (no DynamoDB table).
  backend "s3" {
    key          = "beacon/terraform.tfstate"
    encrypt      = true
    use_lockfile = true
  }
}

provider "aws" {
  region = var.region

  default_tags {
    tags = {
      Project   = "beacon"
      ManagedBy = "terraform"
    }
  }
}

data "aws_caller_identity" "current" {}

locals {
  account_id = data.aws_caller_identity.current.account_id
  # Bucket names are global, so they carry the account id.
  name_suffix    = "${local.account_id}-${var.region}"
  registry       = "${local.account_id}.dkr.ecr.${var.region}.amazonaws.com"
  services       = toset(["auth-service", "incident-service", "notification-service", "gateway"])
  parameter_path = "/beacon/app"
}
