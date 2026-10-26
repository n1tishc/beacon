# How GitHub Actions gets AWS credentials to deploy. Either way it gets the same permissions, defined
# once below: it can ship a release and nothing else (push images, upload the app, invalidate the CDN,
# update the Lambda's code, run the deploy script on the instance, read the two values the smoke test
# needs). It cannot change infrastructure; that is `terraform apply`, run by a person (scripts/aws-up.sh).
#
#   oidc                    (default) A role assumed through GitHub's OIDC provider. No AWS keys exist,
#                           so none can leak. Terraform creates the account's GitHub provider.
#   oidc-existing-provider  The same, reusing a GitHub provider the account already has (one per account).
#   access-key              For accounts whose guardrails forbid identity providers, such as AWS's
#                           project accounts (an SCP denies iam:*Provider*): an IAM user with the same
#                           policy. scripts/aws-up.sh creates its key and stores it straight in the GitHub
#                           `production` environment's secrets; the key is never in Terraform state.

locals {
  deploy_with_oidc = var.github_deploy_auth != "access-key"
}

resource "aws_iam_openid_connect_provider" "github" {
  count          = var.github_deploy_auth == "oidc" ? 1 : 0
  url            = "https://token.actions.githubusercontent.com"
  client_id_list = ["sts.amazonaws.com"]
}

data "aws_iam_openid_connect_provider" "github" {
  count = var.github_deploy_auth == "oidc-existing-provider" ? 1 : 0
  url   = "https://token.actions.githubusercontent.com"
}

locals {
  github_oidc_provider_arn = one(concat(
    aws_iam_openid_connect_provider.github[*].arn,
    data.aws_iam_openid_connect_provider.github[*].arn,
  ))
}

data "aws_iam_policy_document" "github_assume" {
  count = local.deploy_with_oidc ? 1 : 0

  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]
    principals {
      type        = "Federated"
      identifiers = [local.github_oidc_provider_arn]
    }
    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:aud"
      values   = ["sts.amazonaws.com"]
    }
    # Only jobs that run in this repository's `production` environment, not any branch or fork.
    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:sub"
      values   = ["repo:${var.github_repository}:environment:production"]
    }
  }
}

resource "aws_iam_role" "deploy" {
  count                = local.deploy_with_oidc ? 1 : 0
  name                 = "beacon-github-deploy"
  assume_role_policy   = data.aws_iam_policy_document.github_assume[0].json
  max_session_duration = 3600
}

resource "aws_iam_user" "deploy" {
  count = local.deploy_with_oidc ? 0 : 1
  name  = "beacon-github-deploy"
  # The access key is created outside Terraform (see above); this lets destroy remove the user anyway.
  force_destroy = true
}

data "aws_iam_policy_document" "deploy" {
  statement {
    sid       = "EcrLogin"
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"] # this action has no resource-level scoping
  }

  statement {
    sid = "PushImages"
    actions = [
      "ecr:BatchCheckLayerAvailability",
      "ecr:BatchGetImage",
      "ecr:DescribeImages", # a re-run skips images already pushed
      "ecr:InitiateLayerUpload",
      "ecr:UploadLayerPart",
      "ecr:CompleteLayerUpload",
      "ecr:PutImage",
    ]
    resources = [for repository in aws_ecr_repository.service : repository.arn]
  }

  statement {
    sid       = "ListApp"
    actions   = ["s3:ListBucket"]
    resources = [aws_s3_bucket.web.arn]
  }

  statement {
    sid       = "UploadApp"
    actions   = ["s3:PutObject", "s3:DeleteObject"]
    resources = ["${aws_s3_bucket.web.arn}/*"]
  }

  statement {
    sid       = "InvalidateCdn"
    actions   = ["cloudfront:CreateInvalidation", "cloudfront:GetInvalidation"]
    resources = [aws_cloudfront_distribution.web.arn]
  }

  statement {
    sid       = "UpdateLambda"
    actions   = ["lambda:UpdateFunctionCode", "lambda:GetFunction", "lambda:GetFunctionConfiguration"]
    resources = [aws_lambda_function.alert_ingest.arn]
  }

  # Run Command on this one instance, with the stock shell-script document only.
  statement {
    sid     = "RunDeployScript"
    actions = ["ssm:SendCommand"]
    resources = [
      aws_instance.app.arn,
      "arn:aws:ssm:${var.region}::document/AWS-RunShellScript",
    ]
  }

  statement {
    sid       = "ReadDeployOutput"
    actions   = ["ssm:GetCommandInvocation"]
    resources = ["*"] # this action has no resource-level scoping
  }

  statement {
    sid     = "ReadSmokeTestSecrets"
    actions = ["ssm:GetParameter"]
    resources = [
      aws_ssm_parameter.secret["DEMO_USER_PASSWORD"].arn,
      aws_ssm_parameter.ingest_api_key.arn,
    ]
  }
}

resource "aws_iam_role_policy" "deploy" {
  count  = local.deploy_with_oidc ? 1 : 0
  name   = "beacon-github-deploy"
  role   = aws_iam_role.deploy[0].id
  policy = data.aws_iam_policy_document.deploy.json
}

resource "aws_iam_user_policy" "deploy" {
  count  = local.deploy_with_oidc ? 0 : 1
  name   = "beacon-github-deploy"
  user   = aws_iam_user.deploy[0].name
  policy = data.aws_iam_policy_document.deploy.json
}
