# The EC2 instance that runs deploy/docker-compose.yml. Nothing on it is reachable except the
# gateway's port, and only from CloudFront. There is no SSH: deploys and debugging go through SSM.

data "aws_ssm_parameter" "al2023" {
  name = "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64"
}

# CloudFront's origin-facing address ranges, maintained by AWS.
data "aws_ec2_managed_prefix_list" "cloudfront" {
  name = "com.amazonaws.global.cloudfront.origin-facing"
}

resource "aws_security_group" "app" {
  name        = "beacon-app"
  description = "Beacon gateway, reachable from CloudFront only"
  vpc_id      = aws_vpc.main.id
}

# The gateway trusts one proxy hop of X-Forwarded-For for its per-client rate limit. That is only
# sound if nobody but CloudFront can connect, which is what this rule ensures.
resource "aws_vpc_security_group_ingress_rule" "gateway_from_cloudfront" {
  security_group_id = aws_security_group.app.id
  description       = "Gateway, from CloudFront"
  ip_protocol       = "tcp"
  from_port         = 8080
  to_port           = 8080
  prefix_list_id    = data.aws_ec2_managed_prefix_list.cloudfront.id
}

# ECR, SQS, SSM and Slack are all HTTPS on the internet; there is no NAT gateway to narrow this.
resource "aws_vpc_security_group_egress_rule" "app_https" {
  security_group_id = aws_security_group.app.id
  description       = "AWS APIs, image pulls, Slack"
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
  cidr_ipv4         = "0.0.0.0/0"
}

resource "aws_vpc_security_group_egress_rule" "app_to_database" {
  security_group_id            = aws_security_group.app.id
  description                  = "Postgres"
  ip_protocol                  = "tcp"
  from_port                    = 5432
  to_port                      = 5432
  referenced_security_group_id = aws_security_group.database.id
}

resource "aws_instance" "app" {
  ami                         = data.aws_ssm_parameter.al2023.value
  instance_type               = var.instance_type
  subnet_id                   = aws_subnet.public.id
  vpc_security_group_ids      = [aws_security_group.app.id]
  iam_instance_profile        = aws_iam_instance_profile.app.name
  associate_public_ip_address = true

  # Standard mode: when the CPU credits run out the instance slows down instead of billing for more.
  # T3 instances launch in unlimited mode unless told otherwise.
  credit_specification {
    cpu_credits = "standard"
  }

  # IMDSv2 only. The hop limit of 2 lets containers on Docker's bridge network reach the metadata
  # service, which is where the AWS SDK in each service gets the instance role's credentials.
  metadata_options {
    http_endpoint               = "enabled"
    http_tokens                 = "required"
    http_put_response_hop_limit = 2
  }

  root_block_device {
    volume_type = "gp3"
    volume_size = 20
    encrypted   = true
  }

  user_data = templatefile("${path.module}/templates/user-data.sh.tftpl", {
    region          = var.region
    registry        = local.registry
    parameter_path  = local.parameter_path
    compose_version = "v5.6.0"
    compose_sha256  = "40343e21ca777173e69cff5dbafeb37c6f81f3b0d57d9e597f036e95eb63e76a"
    swap_size_mib   = 2048
  })

  tags = { Name = "beacon-app" }

  lifecycle {
    # A newer Amazon Linux image must not silently replace the running instance on the next apply.
    ignore_changes = [ami]
  }
}

# --- Instance role: exactly what the four services and the deploy script use. ---

data "aws_iam_policy_document" "ec2_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ec2.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "app" {
  name               = "beacon-app"
  assume_role_policy = data.aws_iam_policy_document.ec2_assume.json
}

resource "aws_iam_instance_profile" "app" {
  name = "beacon-app"
  role = aws_iam_role.app.name
}

# Lets the deploy job run commands on the instance through SSM instead of SSH.
resource "aws_iam_role_policy_attachment" "app_ssm" {
  role       = aws_iam_role.app.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

data "aws_iam_policy_document" "app" {
  statement {
    sid = "ConsumeAlerts"
    actions = [
      "sqs:GetQueueUrl",
      "sqs:GetQueueAttributes",
      "sqs:ReceiveMessage",
      "sqs:DeleteMessage",
      "sqs:ChangeMessageVisibility",
    ]
    resources = [aws_sqs_queue.alerts.arn]
  }

  # Attachments live under incidents/; HEAD needs GetObject.
  statement {
    sid       = "Attachments"
    actions   = ["s3:PutObject", "s3:GetObject", "s3:DeleteObject"]
    resources = ["${aws_s3_bucket.attachments.arn}/incidents/*"]
  }

  # Without ListBucket, S3 answers 403 instead of 404 for a key that does not exist yet, and the
  # service could not tell "not uploaded yet" from "access denied".
  statement {
    sid       = "AttachmentsNotFound"
    actions   = ["s3:ListBucket"]
    resources = [aws_s3_bucket.attachments.arn]
    condition {
      test     = "StringLike"
      variable = "s3:prefix"
      values   = ["incidents/*"]
    }
  }

  statement {
    sid       = "ReadConfiguration"
    actions   = ["ssm:GetParametersByPath", "ssm:GetParameters", "ssm:GetParameter"]
    resources = ["arn:aws:ssm:${var.region}:${local.account_id}:parameter${local.parameter_path}", "arn:aws:ssm:${var.region}:${local.account_id}:parameter${local.parameter_path}/*"]
  }

  statement {
    sid       = "PullImagesToken"
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"] # this action has no resource-level scoping
  }

  statement {
    sid       = "PullImages"
    actions   = ["ecr:BatchGetImage", "ecr:GetDownloadUrlForLayer", "ecr:BatchCheckLayerAvailability"]
    resources = [for repository in aws_ecr_repository.service : repository.arn]
  }
}

resource "aws_iam_role_policy" "app" {
  name   = "beacon-app"
  role   = aws_iam_role.app.id
  policy = data.aws_iam_policy_document.app.json
}
