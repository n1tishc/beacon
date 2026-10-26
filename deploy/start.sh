#!/bin/bash
# Runs on the EC2 instance, as root, through SSM Run Command (the deploy job sends it):
#
#   start.sh <image-tag>
#
# Reads the app's configuration and secrets from SSM Parameter Store into a root-only .env, pulls the
# tagged images from ECR and starts them, waiting until every service reports healthy. Rolling back
# is running it again with an older tag.
#
# /opt/beacon/instance.env (written by the instance's user data) supplies AWS_REGION, REGISTRY and
# PARAMETER_PATH. Pulls authenticate through the ECR credential helper with the instance role.
set -euo pipefail

TAG="${1:?usage: start.sh <image-tag>}"
HERE="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=/dev/null
source /opt/beacon/instance.env

umask 077
ENV_FILE=/opt/beacon/.env
TMP_ENV="$(mktemp /opt/beacon/.env.XXXXXX)"
trap 'rm -f "$TMP_ENV"' EXIT

# One line per parameter: /beacon/app/DB_PASSWORD becomes DB_PASSWORD="...". Values are double
# quoted so the multi-line JWT key survives; a value containing a quote, a backslash or a dollar sign
# would be mangled by Compose's .env parsing, so it is refused instead.
aws ssm get-parameters-by-path --region "$AWS_REGION" --path "$PARAMETER_PATH" --with-decryption \
  --output json --query 'Parameters[].{name: Name, value: Value}' \
  | jq -r '.[]
      | if (.value | test("[\"\\\\$]")) then error("\(.name) contains a quote, backslash or dollar sign") else . end
      | "\(.name | split("/") | last)=\"\(.value)\""' >"$TMP_ENV"
{
  echo "AWS_REGION=$AWS_REGION"
  echo "REGISTRY=$REGISTRY"
  echo "IMAGE_TAG=$TAG"
} >>"$TMP_ENV"
mv "$TMP_ENV" "$ENV_FILE"

compose() {
  docker compose --project-directory /opt/beacon --env-file "$ENV_FILE" -f "$HERE/docker-compose.yml" "$@"
}

compose pull --quiet
compose up --detach --remove-orphans --wait --wait-timeout 840
# Keep only the images now running, or old releases slowly fill the root volume.
docker image prune --all --force >/dev/null
compose ps --format 'table {{.Service}}\t{{.Status}}'
echo "Beacon $TAG is running"
