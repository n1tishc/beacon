# Alert ingestion, as locally in LocalStack (infra/localstack/init/10-beacon-alerts.sh):
#
#   POST /alerts (API Gateway) -> beacon-alert-ingest (Lambda) -> beacon-alerts (SQS) -> incident-service
#                                                                   └─ after 3 failed receives -> beacon-alerts-dlq

resource "aws_sqs_queue" "alerts_dlq" {
  name                      = "beacon-alerts-dlq"
  message_retention_seconds = 1209600 # 14 days, the maximum, to give someone time to look
  sqs_managed_sse_enabled   = true
}

resource "aws_sqs_queue" "alerts" {
  name                       = "beacon-alerts"
  visibility_timeout_seconds = 30
  sqs_managed_sse_enabled    = true
  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.alerts_dlq.arn
    maxReceiveCount     = 3
  })
}

resource "random_password" "ingest_api_key" {
  length  = 40
  special = false
}

# --- Lambda ---

data "aws_iam_policy_document" "lambda_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["lambda.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "alert_ingest" {
  name               = "beacon-alert-ingest"
  assume_role_policy = data.aws_iam_policy_document.lambda_assume.json
}

data "aws_iam_policy_document" "alert_ingest" {
  statement {
    sid       = "PublishAlerts"
    actions   = ["sqs:SendMessage"]
    resources = [aws_sqs_queue.alerts.arn]
  }

  statement {
    sid       = "WriteLogs"
    actions   = ["logs:CreateLogStream", "logs:PutLogEvents"]
    resources = ["${aws_cloudwatch_log_group.alert_ingest.arn}:*"]
  }
}

resource "aws_iam_role_policy" "alert_ingest" {
  name   = "beacon-alert-ingest"
  role   = aws_iam_role.alert_ingest.id
  policy = data.aws_iam_policy_document.alert_ingest.json
}

resource "aws_cloudwatch_log_group" "alert_ingest" {
  name              = "/aws/lambda/beacon-alert-ingest"
  retention_in_days = 14
}

resource "aws_lambda_function" "alert_ingest" {
  function_name = "beacon-alert-ingest"
  role          = aws_iam_role.alert_ingest.arn
  runtime       = "java21"
  handler       = "dev.beacon.alerts.ingest.AlertIngestHandler::handleRequest"
  memory_size   = 512
  timeout       = 20

  filename = var.alert_lambda_jar
  # fileexists: `terraform destroy` and the tests must work without a built jar.
  source_code_hash = fileexists(var.alert_lambda_jar) ? filebase64sha256(var.alert_lambda_jar) : null

  environment {
    variables = {
      ALERT_QUEUE_URL = aws_sqs_queue.alerts.url
      INGEST_API_KEY  = random_password.ingest_api_key.result
    }
  }

  depends_on = [aws_cloudwatch_log_group.alert_ingest, aws_iam_role_policy.alert_ingest]

  lifecycle {
    # The deploy job ships new code with UpdateFunctionCode; Terraform only creates the function.
    ignore_changes = [filename, source_code_hash]
  }
}

# --- API Gateway ---

resource "aws_api_gateway_rest_api" "alerts" {
  name = "beacon-alerts"
  endpoint_configuration {
    types = ["REGIONAL"]
  }
}

resource "aws_api_gateway_resource" "alerts" {
  rest_api_id = aws_api_gateway_rest_api.alerts.id
  parent_id   = aws_api_gateway_rest_api.alerts.root_resource_id
  path_part   = "alerts"
}

# No API Gateway authorizer: the Lambda checks X-Beacon-Ingest-Key itself, locally and in AWS alike.
resource "aws_api_gateway_method" "post_alert" {
  rest_api_id   = aws_api_gateway_rest_api.alerts.id
  resource_id   = aws_api_gateway_resource.alerts.id
  http_method   = "POST"
  authorization = "NONE"
}

resource "aws_api_gateway_integration" "post_alert" {
  rest_api_id             = aws_api_gateway_rest_api.alerts.id
  resource_id             = aws_api_gateway_resource.alerts.id
  http_method             = aws_api_gateway_method.post_alert.http_method
  type                    = "AWS_PROXY"
  integration_http_method = "POST"
  uri                     = aws_lambda_function.alert_ingest.invoke_arn
}

resource "aws_lambda_permission" "api_gateway" {
  statement_id  = "AllowApiGatewayInvoke"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.alert_ingest.function_name
  principal     = "apigateway.amazonaws.com"
  source_arn    = "${aws_api_gateway_rest_api.alerts.execution_arn}/*/POST/alerts"
}

resource "aws_api_gateway_deployment" "alerts" {
  rest_api_id = aws_api_gateway_rest_api.alerts.id
  triggers = {
    redeployment = sha1(jsonencode([
      aws_api_gateway_resource.alerts.id,
      aws_api_gateway_method.post_alert.id,
      aws_api_gateway_integration.post_alert.id,
    ]))
  }
  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_api_gateway_stage" "prod" {
  rest_api_id   = aws_api_gateway_rest_api.alerts.id
  deployment_id = aws_api_gateway_deployment.alerts.id
  stage_name    = "prod"
}

# A ceiling on what a leaked or misbehaving client can cost: requests over it get 429.
resource "aws_api_gateway_method_settings" "prod" {
  rest_api_id = aws_api_gateway_rest_api.alerts.id
  stage_name  = aws_api_gateway_stage.prod.stage_name
  method_path = "*/*"
  settings {
    throttling_rate_limit  = 20
    throttling_burst_limit = 40
  }
}
