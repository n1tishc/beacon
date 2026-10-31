# `terraform test` with a mocked AWS provider: no account, no credentials, nothing created. It checks
# the security and cost decisions the README makes, so a later edit cannot quietly undo one.

mock_provider "aws" {
  mock_data "aws_caller_identity" {
    defaults = { account_id = "123456789012" }
  }
  mock_data "aws_availability_zones" {
    defaults = { names = ["us-east-1a", "us-east-1b"] }
  }
  mock_data "aws_iam_policy_document" {
    defaults = { json = "{}" }
  }
  mock_data "aws_ssm_parameter" {
    defaults = { value = "ami-0123456789abcdef0" }
  }
  mock_data "aws_ec2_managed_prefix_list" {
    defaults = { id = "pl-3b927c52" }
  }
  mock_resource "aws_cloudfront_distribution" {
    defaults = {
      domain_name = "d1234example.cloudfront.net"
      arn         = "arn:aws:cloudfront::123456789012:distribution/E1234EXAMPLE"
    }
  }
  # Arguments the provider validates as ARNs need ARN-shaped mock values.
  mock_resource "aws_iam_role" {
    defaults = { arn = "arn:aws:iam::123456789012:role/beacon-mock" }
  }
  mock_resource "aws_cloudfront_function" {
    defaults = { arn = "arn:aws:cloudfront::123456789012:function/beacon-spa-routing" }
  }
  mock_resource "aws_api_gateway_rest_api" {
    defaults = { execution_arn = "arn:aws:execute-api:us-east-1:123456789012:abc123" }
  }
  mock_resource "aws_iam_openid_connect_provider" {
    defaults = { arn = "arn:aws:iam::123456789012:oidc-provider/token.actions.githubusercontent.com" }
  }
}

variables {
  budget_alert_email = "ops@example.com"
}

run "only_cloudfront_reaches_the_instance" {
  assert {
    condition     = aws_vpc_security_group_ingress_rule.gateway_from_cloudfront.prefix_list_id == "pl-3b927c52"
    error_message = "The gateway must admit CloudFront's origin-facing prefix list, nothing else."
  }
  assert {
    condition     = aws_vpc_security_group_ingress_rule.gateway_from_cloudfront.cidr_ipv4 == null
    error_message = "The gateway must not be open to an address range."
  }
  assert {
    condition     = aws_db_instance.main.publicly_accessible == false
    error_message = "RDS must not be publicly accessible."
  }
  assert {
    condition     = aws_vpc_security_group_ingress_rule.database_from_app.referenced_security_group_id == aws_security_group.app.id
    error_message = "Postgres must admit the app's security group only."
  }
}

run "instance_settings" {
  assert {
    condition     = aws_instance.app.metadata_options[0].http_tokens == "required"
    error_message = "IMDSv2 must be required."
  }
  assert {
    condition     = aws_instance.app.metadata_options[0].http_put_response_hop_limit == 2
    error_message = "Containers need a hop limit of 2 to reach the instance role's credentials."
  }
  assert {
    condition     = aws_instance.app.credit_specification[0].cpu_credits == "standard"
    error_message = "Unlimited CPU credits can bill beyond the free tier."
  }
  assert {
    condition     = aws_instance.app.root_block_device[0].encrypted && aws_db_instance.main.storage_encrypted
    error_message = "Disks must be encrypted."
  }
}

run "buckets_are_private" {
  assert {
    condition = alltrue([for block in [aws_s3_bucket_public_access_block.web, aws_s3_bucket_public_access_block.attachments] :
    block.block_public_acls && block.block_public_policy && block.ignore_public_acls && block.restrict_public_buckets])
    error_message = "Both buckets must block all public access."
  }
  assert {
    condition     = one(aws_s3_bucket_cors_configuration.attachments.cors_rule).allowed_origins == toset(["https://d1234example.cloudfront.net"])
    error_message = "Browsers may upload attachments from the app's own origin only."
  }
  assert {
    condition     = one(aws_s3_bucket_cors_configuration.attachments.cors_rule).allowed_methods == toset(["PUT"])
    error_message = "The attachment bucket's CORS rule is for uploads only."
  }
}

run "the_api_is_same_origin_and_uncached" {
  assert {
    condition     = aws_cloudfront_distribution.web.ordered_cache_behavior[0].path_pattern == "/api/*"
    error_message = "/api/* must go to the gateway through the app's own distribution."
  }
  assert {
    condition     = aws_cloudfront_distribution.web.ordered_cache_behavior[0].target_origin_id == "api"
    error_message = "/api/* must go to the gateway origin."
  }
  assert {
    condition     = aws_cloudfront_distribution.web.ordered_cache_behavior[0].viewer_protocol_policy == "https-only"
    error_message = "API calls carry tokens and must never be served over plain HTTP."
  }
  assert {
    condition     = length(aws_cloudfront_distribution.web.custom_error_response) == 0
    error_message = "Custom error pages would turn the API's 403/404 into index.html; client routes are rewritten by the function."
  }
}

run "the_deploy_role_cannot_change_infrastructure" {
  assert {
    condition = alltrue(flatten([for statement in data.aws_iam_policy_document.deploy.statement :
    [for action in statement.actions : !strcontains(action, "*") && !startswith(action, "iam:")]]))
    error_message = "The deploy role must list exact actions and none of IAM."
  }
  assert {
    condition = alltrue([for statement in data.aws_iam_policy_document.deploy.statement :
      statement.resources != toset(["*"]) || contains(["EcrLogin", "ReadDeployOutput"], statement.sid)
    ])
    error_message = "Only actions without resource-level scoping may use Resource \"*\"."
  }
  assert {
    condition = anytrue([for condition in data.aws_iam_policy_document.github_assume[0].statement[0].condition :
    condition.variable == "token.actions.githubusercontent.com:sub" && condition.values == tolist(["repo:n1tishc/beacon:environment:production"])])
    error_message = "Only this repository's production environment may assume the deploy role."
  }
}

# Accounts that forbid identity providers deploy with an IAM user's access key instead: same policy,
# and no OIDC provider or role is attempted at all, since creating one would be denied mid-apply.
run "an_access_key_deploy_user_gets_the_same_narrow_policy" {
  variables {
    github_deploy_auth = "access-key"
  }
  assert {
    condition     = length(aws_iam_openid_connect_provider.github) == 0 && length(aws_iam_role.deploy) == 0
    error_message = "access-key mode must not create an OIDC provider or role."
  }
  assert {
    condition     = aws_iam_user_policy.deploy[0].policy == data.aws_iam_policy_document.deploy.json
    error_message = "The deploy user must get exactly the deploy role's policy."
  }
  assert {
    condition     = output.deploy_user_name == "beacon-github-deploy" && !contains(keys(output.github_variables), "AWS_DEPLOY_ROLE_ARN")
    error_message = "aws-up.sh needs the user's name, and the deploy job must not see a role to assume."
  }
}

run "oidc_is_the_default" {
  assert {
    condition     = length(aws_iam_openid_connect_provider.github) == 1 && length(aws_iam_user.deploy) == 0
    error_message = "By default the deploy job uses OIDC and no IAM user exists."
  }
  assert {
    condition     = output.deploy_user_name == "" && contains(keys(output.github_variables), "AWS_DEPLOY_ROLE_ARN")
    error_message = "With OIDC the deploy job gets a role ARN and there is no deploy user."
  }
}

run "the_instance_role_is_scoped" {
  assert {
    condition = alltrue(flatten([for statement in data.aws_iam_policy_document.app.statement :
    [for action in statement.actions : !strcontains(action, "*")]]))
    error_message = "The instance role must list exact actions."
  }
  assert {
    condition = alltrue([for statement in data.aws_iam_policy_document.app.statement :
      statement.resources != toset(["*"]) || statement.sid == "PullImagesToken"
    ])
    error_message = "Only ecr:GetAuthorizationToken may use Resource \"*\"."
  }
}

run "slack_is_off_without_a_webhook" {
  assert {
    condition     = !contains(keys(aws_ssm_parameter.secret), "SLACK_WEBHOOK_URL")
    error_message = "No webhook, no parameter: SSM cannot hold an empty value, and absence turns Slack off."
  }
  assert {
    condition     = output.github_variables.EXPECT_SLACK == "off"
    error_message = "The deploy job's smoke test must expect Slack to be off."
  }
}
