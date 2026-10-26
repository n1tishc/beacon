#!/bin/bash
# Last init script. The compose healthcheck waits for the marker file, so dependants start only once
# the resources the earlier scripts create really exist: if one of them failed, a check below fails
# and the marker is never written.
set -euo pipefail

awslocal sqs get-queue-url --queue-name beacon-alerts >/dev/null
awslocal lambda get-function --function-name beacon-alert-ingest >/dev/null
awslocal s3api head-bucket --bucket "${ATTACHMENTS_BUCKET:-beacon-attachments}" >/dev/null
touch /tmp/beacon-ready
