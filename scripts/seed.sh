#!/usr/bin/env bash
# Fills a running Beacon stack with a realistic mix of incidents, so the dashboard has something to show.
#
#   scripts/seed.sh [base-url]        default: http://localhost:4200
#
# Goes through the public API and the alert endpoint, not the database, so every incident has a real
# timeline, notifications and alert counts. Creates:
#   - incidents in every state and severity, with notes and resolution notes
#   - alert-sourced incidents, one of them repeated (alert count > 1)
#   - one resolved incident with a full postmortem
# Uses the same variables as scripts/smoke-test.sh (DEMO_USER_PASSWORD, INGEST_URL, INGEST_API_KEY).
# Run it once; a second run is refused unless FORCE=1, since it would add everything again.
# Requires curl and jq.
set -euo pipefail

BASE_URL="${1:-http://localhost:4200}"
PASSWORD="${DEMO_USER_PASSWORD:-beacon-demo}"
INGEST_URL="${INGEST_URL:-http://localhost:4566/_aws/execute-api/beacon/local/alerts}"
INGEST_API_KEY="${INGEST_API_KEY:-local-ingest-key}"
MARKER="Checkout API returning 502s"

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

# One login for the whole run: the gateway allows 10 a minute per IP.
TOKEN=$(curl -fsS -X POST "$BASE_URL/api/auth/login" -H 'Content-Type: application/json' \
  -d "$(jq -n --arg p "$PASSWORD" '{username: "responder", password: $p}')" | jq -er .accessToken) \
  || fail "could not log in as responder at $BASE_URL"

api() {
  local method=$1 path=$2 body=${3:-}
  curl -fsS -X "$method" "$BASE_URL/api$path" -H "Authorization: Bearer $TOKEN" \
    -H 'Content-Type: application/json' ${body:+-d "$body"}
}

if [[ "${FORCE:-0}" != 1 ]] && api GET '/incidents?size=100' \
  | jq -e --arg t "$MARKER" '.content | any(.title == $t)' >/dev/null; then
  echo "This stack is already seeded (found \"$MARKER\"). Set FORCE=1 to add everything again."
  exit 0
fi

# trigger <title> <severity> <description>: prints the new incident as JSON.
trigger() {
  api POST /incidents "$(jq -n --arg t "$1" --arg s "$2" --arg d "$3" '{title: $t, severity: $s, description: $d}')" >/dev/null
  api GET '/incidents?size=100' | jq -e --arg t "$1" '.content | map(select(.title == $t)) | first'
}

note() {
  api POST "/incidents/$1/notes" "$(jq -n --arg t "$2" '{text: $t}')" >/dev/null
}

acknowledge() {
  api POST "/incidents/$1/acknowledge" "{\"version\": $2}" | jq -er .version
}

resolve() {
  api POST "/incidents/$1/resolve" "$(jq -n --argjson v "$2" --arg n "$3" '{version: $v, note: $n}')" >/dev/null
}

alert() {
  curl -fsS -o /dev/null -X POST "$INGEST_URL" -H "X-Beacon-Ingest-Key: $INGEST_API_KEY" \
    -H 'Content-Type: application/json' \
    -d "$(jq -n --arg src "$1" --arg t "$2" --arg s "$3" --arg d "$4" '{source: $src, title: $t, severity: $s, description: $d}')"
}

echo "Resolved, with a postmortem"
INCIDENT=$(trigger "$MARKER" CRITICAL "About 40% of checkout requests fail at the load balancer.")
ID=$(jq -r .id <<<"$INCIDENT")
note "$ID" "Started right after the 14:05 deploy of checkout-api v2.31."
VERSION=$(acknowledge "$ID" 0)
note "$ID" "Pods are OOMKilled under peak load. Rolling back to v2.30."
resolve "$ID" "$VERSION" "Rollback to v2.30 finished; error rate back under 0.1%."
api PUT "/incidents/$ID/postmortem" "$(jq -n '{
  version: null,
  summary: "A checkout-api release ran out of memory at peak traffic, and about 40% of checkouts failed for 23 minutes.",
  impact: "Customers in every region saw 502s at checkout from 14:07 to 14:30. Roughly 1,900 orders failed.",
  rootCause: "v2.31 loads the full product catalog into memory on start. Under peak load the pods hit their 512 MiB limit and were killed.",
  actionItems: "- Load-test releases at peak traffic before they ship\n- Alert on OOMKilled pods, not only on 5xx rates\n- Page the owning team on a deploy-correlated error spike"
}')" >/dev/null

echo "Resolved, no postmortem yet"
ID=$(trigger "Password reset e-mails delayed" MEDIUM "Reset e-mails arrive 10-15 minutes late." | jq -r .id)
VERSION=$(acknowledge "$ID" 0)
resolve "$ID" "$VERSION" "The mail provider cleared its queue backlog."

echo "Acknowledged, being worked on"
ID=$(trigger "Search results missing new products" HIGH "Products added since yesterday don't appear in search." | jq -r .id)
VERSION=$(acknowledge "$ID" 0)
note "$ID" "The indexer's consumer group is 2 days behind; it has been restarting in a loop."

ID=$(trigger "Admin dashboard slow to load" LOW "The orders page takes about 8 seconds." | jq -r .id)
acknowledge "$ID" 0 >/dev/null

echo "Triggered, nobody on it yet"
trigger "Nightly invoice export failed" MEDIUM "The 02:00 export job exited with code 1." >/dev/null

echo "From alerts (through the ingest endpoint, as a monitoring tool would send them)"
alert prometheus "Disk 91% full on db-replica-2" HIGH "node_filesystem_avail_bytes below 10% for 5 minutes."
alert grafana "p99 latency above 2s on payments-api" CRITICAL "p99 latency 2.4s over the last 10 minutes."
# The same alert three times folds into one incident with an alert count of 3.
for _ in 1 2 3; do
  alert cloudwatch "5xx rate above 5% on api-gateway" HIGH "HTTPCode_Target_5XX_Count above threshold."
done

echo "Done. Open $BASE_URL and sign in as responder (alert incidents appear within a few seconds)."
