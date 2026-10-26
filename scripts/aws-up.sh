#!/usr/bin/env bash
# Creates or updates the AWS deployment and connects GitHub Actions to it. Run it yourself, with
# administrator credentials for the account in your shell (aws configure, aws sso login, or env vars):
#
#   BUDGET_ALERT_EMAIL=you@example.com scripts/aws-up.sh
#
# Optional: AWS_REGION (default us-east-1), TF_VAR_slack_webhook_url, TF_VAR_demo_user_password.
# Terraform shows its plan and asks before changing anything.
#
# Afterwards, every push to main deploys (the `deploy` job in .github/workflows/ci.yml). For the
# first release, run the CI workflow on main by hand: gh workflow run ci.yml --ref main
#
# Needs terraform, gh (logged in, admin on the repository), jq, and JDK 21 for the Lambda jar.
set -euo pipefail
cd "$(dirname "$0")/.."

REGION="${AWS_REGION:-us-east-1}"
: "${BUDGET_ALERT_EMAIL:?Set BUDGET_ALERT_EMAIL to the address that should get cost alerts}"
REPO="$(gh repo view --json nameWithOwner --jq .nameWithOwner)"

echo "==> Building the alert Lambda (Terraform creates the function from it)"
(cd backend && ./mvnw -B -q -pl alert-ingest-lambda -am -DskipTests package)

echo "==> State bucket"
terraform -chdir=infra/terraform/bootstrap init -input=false
terraform -chdir=infra/terraform/bootstrap apply -input=false -var "region=$REGION"
STATE_BUCKET="$(terraform -chdir=infra/terraform/bootstrap output -raw state_bucket)"

echo "==> Beacon"
terraform -chdir=infra/terraform init -input=false -reconfigure \
  -backend-config="bucket=$STATE_BUCKET" -backend-config="region=$REGION"
terraform -chdir=infra/terraform apply -input=false \
  -var "region=$REGION" -var "github_repository=$REPO" -var "budget_alert_email=$BUDGET_ALERT_EMAIL"

echo "==> GitHub: the production environment, deployable from main only"
gh api --method PUT "repos/$REPO/environments/production" \
  -F 'deployment_branch_policy[protected_branches]=false' \
  -F 'deployment_branch_policy[custom_branch_policies]=true' >/dev/null
gh api --method POST "repos/$REPO/environments/production/deployment-branch-policies" \
  -f name=main >/dev/null 2>&1 || true # already there on a re-run

echo "==> GitHub: deploy variables (none of them secret)"
terraform -chdir=infra/terraform output -json github_variables \
  | jq -r 'to_entries[] | "\(.key)\t\(.value)"' \
  | while IFS=$'\t' read -r name value; do
      gh variable set "$name" --repo "$REPO" --body "$value"
    done

echo
echo "App:    $(terraform -chdir=infra/terraform output -raw app_url)"
echo "Alerts: $(terraform -chdir=infra/terraform output -raw ingest_url)"
echo "Demo password: aws ssm get-parameter --with-decryption --query Parameter.Value --output text \\"
echo "                 --region $REGION --name $(terraform -chdir=infra/terraform output -raw demo_password_parameter)"
echo "Deploy the first release with: gh workflow run ci.yml --ref main"
