#!/bin/bash
# Runs inside LocalStack after 10-beacon-alerts.sh. Creates the postmortem attachment bucket the way
# Terraform creates it in AWS: private, and accepting browser uploads only from the web app's origin.
#
# Browsers upload straight to S3 with presigned PUT URLs, which is a cross-origin request, hence CORS.
# Downloads are plain navigations to a presigned GET URL and need no CORS rule.
set -euo pipefail

BUCKET="${ATTACHMENTS_BUCKET:-beacon-attachments}"
WEB_ORIGIN="${WEB_ORIGIN:-http://localhost:4200}"

awslocal s3api create-bucket --bucket "$BUCKET" >/dev/null
awslocal s3api put-public-access-block --bucket "$BUCKET" --public-access-block-configuration \
  BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true
awslocal s3api put-bucket-cors --bucket "$BUCKET" --cors-configuration "{
  \"CORSRules\": [{
    \"AllowedOrigins\": [\"$WEB_ORIGIN\"],
    \"AllowedMethods\": [\"PUT\"],
    \"AllowedHeaders\": [\"content-type\"],
    \"MaxAgeSeconds\": 600
  }]
}"

echo "Beacon attachments ready: s3://$BUCKET (uploads from $WEB_ORIGIN)"
