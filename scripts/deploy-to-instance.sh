#!/usr/bin/env bash
# Deploys a release to the EC2 instance through SSM Run Command (no SSH):
#
#   scripts/deploy-to-instance.sh <instance-id> <image-tag>
#
# Sends deploy/ (the compose file and start.sh) inline with the command, runs start.sh with the tag,
# waits for it and prints its output. Fails if the deploy fails, including when a service never
# becomes healthy. The images must already be in ECR. Used by the deploy job; needs AWS credentials
# allowed to run commands on the instance, and AWS_REGION.
set -euo pipefail

INSTANCE_ID="${1:?usage: deploy-to-instance.sh <instance-id> <image-tag>}"
TAG="${2:?usage: deploy-to-instance.sh <instance-id> <image-tag>}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

# A few KB of base64, well within Run Command's parameter size limit.
BUNDLE="$(tar -czf - -C "$ROOT/deploy" . | base64 | tr -d '\n')"
RELEASE_DIR="/opt/beacon/releases/$TAG"

COMMAND_ID="$(aws ssm send-command \
  --instance-ids "$INSTANCE_ID" \
  --document-name AWS-RunShellScript \
  --comment "Deploy Beacon $TAG" \
  --timeout-seconds 120 \
  --parameters "$(jq -n --arg bundle "$BUNDLE" --arg dir "$RELEASE_DIR" --arg tag "$TAG" '{
      executionTimeout: ["900"],
      commands: [
        "set -euo pipefail",
        "mkdir -p \($dir)",
        "echo \($bundle) | base64 -d | tar -xzf - -C \($dir)",
        "\($dir)/start.sh \($tag)"
      ]
    }')" \
  --query Command.CommandId --output text)"
echo "SSM command $COMMAND_ID: deploying $TAG to $INSTANCE_ID"

# The CLI's own waiter gives up after 100 s; four JVMs booting one after another take longer.
deadline=$((SECONDS + 960))
while :; do
  STATUS="$(aws ssm get-command-invocation --command-id "$COMMAND_ID" --instance-id "$INSTANCE_ID" \
    --query Status --output text 2>/dev/null || echo Pending)"
  case "$STATUS" in
    Pending | InProgress | Delayed) ;;
    *) break ;;
  esac
  if ((SECONDS > deadline)); then
    echo "Gave up waiting for the deploy (last status: $STATUS)" >&2
    exit 1
  fi
  sleep 10
done

aws ssm get-command-invocation --command-id "$COMMAND_ID" --instance-id "$INSTANCE_ID" \
  --query '[StandardOutputContent, StandardErrorContent]' --output text
[[ "$STATUS" == Success ]] || { echo "Deploy failed: $STATUS" >&2; exit 1; }
