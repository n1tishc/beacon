# The services' configuration, in SSM Parameter Store under /beacon/app. deploy/start.sh turns every
# parameter there into one line of the instance's .env, named after the last path segment.
# Secrets are SecureString (encrypted with the AWS-managed SSM key, no extra cost).
#
# Generated secrets are also in the Terraform state, which is why that lives in a private,
# encrypted bucket (see bootstrap/).

resource "random_password" "demo_user" {
  length  = 20
  special = false
}

resource "random_password" "notification_service_token" {
  length  = 40
  special = false
}

# JWT signing key. Stable across deploys, so sessions survive a restart.
resource "tls_private_key" "jwt" {
  algorithm = "RSA"
  rsa_bits  = 2048
}

locals {
  plain_parameters = {
    DB_URL             = "jdbc:postgresql://${aws_db_instance.main.address}:${aws_db_instance.main.port}/${aws_db_instance.main.db_name}?sslmode=require"
    DB_USERNAME        = aws_db_instance.main.username
    ALERT_QUEUE        = aws_sqs_queue.alerts.name
    ATTACHMENTS_BUCKET = aws_s3_bucket.attachments.id
    BEACON_PUBLIC_URL  = "https://${aws_cloudfront_distribution.web.domain_name}"
  }

  secret_parameters = merge(
    {
      DB_PASSWORD                = random_password.database.result
      JWT_SIGNING_KEY            = tls_private_key.jwt.private_key_pem_pkcs8
      DEMO_USER_PASSWORD         = coalesce(var.demo_user_password, random_password.demo_user.result)
      NOTIFICATION_SERVICE_TOKEN = random_password.notification_service_token.result
    },
    # SSM cannot store an empty value; leaving the parameter out turns Slack off.
    var.slack_webhook_url == "" ? {} : { SLACK_WEBHOOK_URL = var.slack_webhook_url },
  )
}

resource "aws_ssm_parameter" "plain" {
  for_each = local.plain_parameters
  name     = "${local.parameter_path}/${each.key}"
  type     = "String"
  value    = each.value
}

resource "aws_ssm_parameter" "secret" {
  # The keys are not secret, only the values; for_each needs the keys in the clear.
  for_each = nonsensitive(toset(keys(local.secret_parameters)))
  name     = "${local.parameter_path}/${each.key}"
  type     = "SecureString"
  value    = local.secret_parameters[each.key]
}

# For the deploy job's smoke test, which sends alerts like a monitoring tool would. Not under
# /beacon/app: no service needs it (the Lambda has it in its environment).
resource "aws_ssm_parameter" "ingest_api_key" {
  name  = "/beacon/ci/INGEST_API_KEY"
  type  = "SecureString"
  value = random_password.ingest_api_key.result
}
