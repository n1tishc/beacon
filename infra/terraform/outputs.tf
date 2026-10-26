output "app_url" {
  description = "The deployed app."
  value       = "https://${aws_cloudfront_distribution.web.domain_name}"
}

output "ingest_url" {
  description = "Where monitoring tools POST alerts (with the X-Beacon-Ingest-Key header)."
  value       = "${aws_api_gateway_stage.prod.invoke_url}/alerts"
}

output "demo_password_parameter" {
  description = "aws ssm get-parameter --with-decryption --name <this> shows the demo accounts' password."
  value       = aws_ssm_parameter.secret["DEMO_USER_PASSWORD"].name
}

# scripts/aws-up.sh stores these as GitHub Actions variables for the deploy job. None is a secret:
# the job reads the two secrets it needs from SSM with its deploy credentials. AWS_DEPLOY_ROLE_ARN
# exists only with OIDC; with an access key, the key itself is in the environment's secrets.
output "github_variables" {
  value = merge(local.deploy_with_oidc ? { AWS_DEPLOY_ROLE_ARN = aws_iam_role.deploy[0].arn } : {}, {
    AWS_REGION                 = var.region
    ECR_REGISTRY               = local.registry
    WEB_BUCKET                 = aws_s3_bucket.web.id
    CLOUDFRONT_DISTRIBUTION_ID = aws_cloudfront_distribution.web.id
    INSTANCE_ID                = aws_instance.app.id
    ALERT_LAMBDA               = aws_lambda_function.alert_ingest.function_name
    APP_URL                    = "https://${aws_cloudfront_distribution.web.domain_name}"
    INGEST_URL                 = "${aws_api_gateway_stage.prod.invoke_url}/alerts"
    DEMO_PASSWORD_PARAMETER    = aws_ssm_parameter.secret["DEMO_USER_PASSWORD"].name
    INGEST_KEY_PARAMETER       = aws_ssm_parameter.ingest_api_key.name
    # Whether a webhook is set is not secret, only the webhook itself.
    EXPECT_SLACK = nonsensitive(var.slack_webhook_url == "") ? "off" : "sent"
  })
}

output "deploy_user_name" {
  description = "The IAM user whose access key aws-up.sh stores in GitHub (access-key mode), or empty."
  value       = try(aws_iam_user.deploy[0].name, "")
}
