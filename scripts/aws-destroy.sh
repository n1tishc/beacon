#!/usr/bin/env bash
# Deletes the whole AWS deployment, data included: database, attachments, images, Terraform state.
# Also removes the GitHub deploy variables, so the deploy job goes back to being skipped.
#
#   scripts/aws-destroy.sh
#
# Same credentials and AWS_REGION as scripts/aws-up.sh. Terraform lists what it will delete and asks.
set -euo pipefail
cd "$(dirname "$0")/.."

REGION="${AWS_REGION:-us-east-1}"
REPO="$(gh repo view --json nameWithOwner --jq .nameWithOwner)"

terraform -chdir=infra/terraform/bootstrap init -input=false >/dev/null
STATE_BUCKET="$(terraform -chdir=infra/terraform/bootstrap output -raw state_bucket)"
terraform -chdir=infra/terraform init -input=false -reconfigure \
  -backend-config="bucket=$STATE_BUCKET" -backend-config="region=$REGION" >/dev/null

VARIABLES="$(terraform -chdir=infra/terraform output -json github_variables 2>/dev/null | jq -r 'keys[]' || true)"

# The e-mail address only matters to the budget being deleted; any value will do.
terraform -chdir=infra/terraform destroy -input=false \
  -var "region=$REGION" -var "github_repository=$REPO" -var "budget_alert_email=unused@example.com"
terraform -chdir=infra/terraform/bootstrap destroy -input=false -var "region=$REGION"

for name in $VARIABLES; do
  gh variable delete "$name" --repo "$REPO" || true
done
echo "Beacon's AWS deployment is gone."
