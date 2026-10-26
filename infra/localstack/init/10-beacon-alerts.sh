#!/bin/bash
# Runs inside LocalStack once it is ready. Creates the same resources Terraform creates in AWS:
#
#   POST /alerts (API Gateway) -> beacon-alert-ingest (Lambda) -> beacon-alerts (SQS) -> incident-service
#                                                                   └─ after 3 failed receives -> beacon-alerts-dlq
#
# The API gets a fixed id ("beacon"), so its URL is stable:
#   http://localhost:4566/_aws/execute-api/beacon/local/alerts
set -euo pipefail

REGION="${AWS_DEFAULT_REGION:-us-east-1}"
ACCOUNT=000000000000
FUNCTION=beacon-alert-ingest

dlq_url=$(awslocal sqs create-queue --queue-name beacon-alerts-dlq \
  --attributes MessageRetentionPeriod=1209600 --query QueueUrl --output text)
dlq_arn=$(awslocal sqs get-queue-attributes --queue-url "$dlq_url" \
  --attribute-names QueueArn --query Attributes.QueueArn --output text)
queue_url=$(awslocal sqs create-queue --queue-name beacon-alerts --attributes "{
  \"VisibilityTimeout\": \"${ALERT_QUEUE_VISIBILITY_TIMEOUT:-30}\",
  \"RedrivePolicy\": \"{\\\"deadLetterTargetArn\\\":\\\"$dlq_arn\\\",\\\"maxReceiveCount\\\":\\\"3\\\"}\"
}" --query QueueUrl --output text)

awslocal lambda create-function \
  --function-name "$FUNCTION" \
  --runtime java21 \
  --handler dev.beacon.alerts.ingest.AlertIngestHandler::handleRequest \
  --memory-size 512 \
  --timeout 20 \
  --role "arn:aws:iam::$ACCOUNT:role/beacon-alert-ingest" \
  --zip-file fileb:///opt/beacon/alert-ingest-lambda.jar \
  --environment "Variables={ALERT_QUEUE_URL=$queue_url,INGEST_API_KEY=$INGEST_API_KEY}" \
  >/dev/null
awslocal lambda wait function-active-v2 --function-name "$FUNCTION"

api_id=$(awslocal apigateway create-rest-api --name beacon-alerts \
  --tags _custom_id_=beacon --query id --output text)
root_id=$(awslocal apigateway get-resources --rest-api-id "$api_id" \
  --query 'items[?path==`/`].id' --output text)
alerts_id=$(awslocal apigateway create-resource --rest-api-id "$api_id" \
  --parent-id "$root_id" --path-part alerts --query id --output text)
awslocal apigateway put-method --rest-api-id "$api_id" --resource-id "$alerts_id" \
  --http-method POST --authorization-type NONE >/dev/null
awslocal apigateway put-integration --rest-api-id "$api_id" --resource-id "$alerts_id" \
  --http-method POST --type AWS_PROXY --integration-http-method POST \
  --uri "arn:aws:apigateway:$REGION:lambda:path/2015-03-31/functions/arn:aws:lambda:$REGION:$ACCOUNT:function:$FUNCTION/invocations" \
  >/dev/null
awslocal apigateway create-deployment --rest-api-id "$api_id" --stage-name local >/dev/null

echo "Beacon alert ingestion ready: http://localhost:4566/_aws/execute-api/$api_id/local/alerts -> $queue_url"
