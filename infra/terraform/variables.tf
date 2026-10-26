variable "region" {
  description = "AWS region for everything except CloudFront, which is global."
  type        = string
  default     = "us-east-1"
}

variable "github_repository" {
  description = "owner/name of the GitHub repository whose Actions may deploy."
  type        = string
  default     = "n1tishc/beacon"
}

variable "github_deploy_auth" {
  description = "How the deploy job authenticates: oidc, oidc-existing-provider (the account already has GitHub's provider) or access-key (accounts that forbid identity providers). See github.tf."
  type        = string
  default     = "oidc"
  validation {
    condition     = contains(["oidc", "oidc-existing-provider", "access-key"], var.github_deploy_auth)
    error_message = "github_deploy_auth must be oidc, oidc-existing-provider or access-key."
  }
}

variable "budget_alert_email" {
  description = "Where AWS Budgets sends the cost alert."
  type        = string
}

variable "monthly_budget_usd" {
  description = "Alert when actual spend passes half of this, or forecast spend passes all of it."
  type        = number
  default     = 5
}

variable "instance_type" {
  description = "t3.micro is free-tier eligible. Four JVMs fit in its 1 GiB only with swap; t3.small doubles that."
  type        = string
  default     = "t3.micro"
}

variable "alert_lambda_jar" {
  description = "The alert-ingest Lambda, built by `./mvnw -pl alert-ingest-lambda -am package`. After the first apply, CI deploys new code."
  type        = string
  default     = "../../backend/alert-ingest-lambda/target/alert-ingest-lambda.jar"
}

variable "slack_webhook_url" {
  description = "Slack incoming webhook. Empty: Slack is off and notifications go to the mock sink only."
  type        = string
  default     = ""
  sensitive   = true
}

variable "demo_user_password" {
  description = "Password of the seeded admin/responder/viewer accounts. Null: a random one (read it from SSM)."
  type        = string
  default     = null
  sensitive   = true
}
