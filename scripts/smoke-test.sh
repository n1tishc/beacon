#!/usr/bin/env bash
# End-to-end check of a running Beacon stack, through its public entry point.
#
#   scripts/smoke-test.sh [base-url]        default: http://localhost:4200
#
# Uses the demo accounts, so DEMO_USER_PASSWORD must match the stack's (default: beacon-demo).
# Alerts go to INGEST_URL (default: LocalStack's API Gateway) with INGEST_API_KEY (default: local-ingest-key).
# EXPECT_SLACK says what Slack delivery should look like:
#   sent (default)  every Slack notification was delivered (locally: to the fake-slack container)
#   down            Slack is unreachable: deliveries fail, the circuit opens, incidents still work
#   off             no Slack webhook is configured, so only the mock sink is notified
# Requires curl and jq. Used by CI against docker compose and, later, against the AWS deployment.
set -euo pipefail

BASE_URL="${1:-http://localhost:4200}"
PASSWORD="${DEMO_USER_PASSWORD:-beacon-demo}"
INGEST_URL="${INGEST_URL:-http://localhost:4566/_aws/execute-api/beacon/local/alerts}"
INGEST_API_KEY="${INGEST_API_KEY:-local-ingest-key}"
EXPECT_SLACK="${EXPECT_SLACK:-sent}"
TITLE="Smoke test $(date -u +%Y%m%dT%H%M%SZ)"
ALERT_TITLE="Smoke alert $(date -u +%Y%m%dT%H%M%SZ)"

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

# Prints only the HTTP status code of a request.
status_of() {
  curl -s -o /dev/null -w '%{http_code}' "$@"
}

login() {
  curl -fsS -X POST "$BASE_URL/api/auth/login" \
    -H 'Content-Type: application/json' \
    -d "{\"username\": \"$1\", \"password\": \"$PASSWORD\"}" | jq -er .accessToken
}

create_incident() {
  status_of -X POST "$BASE_URL/api/incidents" \
    -H "Authorization: Bearer $1" \
    -H 'Content-Type: application/json' \
    -d "{\"title\": \"$TITLE\", \"severity\": \"LOW\"}"
}

# incident_command <token> <incident-id> <acknowledge|resolve> <version>: prints the status, keeps the body in $BODY_FILE.
BODY_FILE=$(mktemp)
trap 'rm -f "$BODY_FILE"' EXIT
incident_command() {
  curl -s -o "$BODY_FILE" -w '%{http_code}' -X POST "$BASE_URL/api/incidents/$2/$3" \
    -H "Authorization: Bearer $1" \
    -H 'Content-Type: application/json' \
    -d "{\"version\": $4}"
}

echo "1. Anonymous API requests are rejected"
[[ $(status_of "$BASE_URL/api/incidents") == 401 ]] || fail "expected 401 without a token"

echo "2. Wrong passwords are rejected"
[[ $(status_of -X POST "$BASE_URL/api/auth/login" -H 'Content-Type: application/json' \
  -d '{"username": "responder", "password": "wrong-password"}') == 401 ]] || fail "expected 401 for a bad password"

echo "3. A responder can trigger an incident"
RESPONDER_TOKEN=$(login responder) || fail "responder could not log in"
[[ $(create_incident "$RESPONDER_TOKEN") == 201 ]] || fail "responder could not create an incident"

echo "4. A viewer can see it, but cannot trigger incidents"
VIEWER_TOKEN=$(login viewer) || fail "viewer could not log in"
curl -fsS "$BASE_URL/api/incidents?size=100" -H "Authorization: Bearer $VIEWER_TOKEN" \
  | jq -e --arg title "$TITLE" '.content | any(.title == $title)' >/dev/null || fail "new incident not listed"
[[ $(create_incident "$VIEWER_TOKEN") == 403 ]] || fail "viewer was allowed to create an incident"
INCIDENT_ID=$(curl -fsS "$BASE_URL/api/incidents?size=100" -H "Authorization: Bearer $VIEWER_TOKEN" \
  | jq -er --arg title "$TITLE" '.content | map(select(.title == $title)) | first | .id')

echo "5. The lifecycle runs TRIGGERED -> ACKNOWLEDGED -> RESOLVED, and only responders may drive it"
[[ $(incident_command "$VIEWER_TOKEN" "$INCIDENT_ID" acknowledge 0) == 403 ]] || fail "viewer was allowed to acknowledge"
[[ $(incident_command "$RESPONDER_TOKEN" "$INCIDENT_ID" acknowledge 0) == 200 ]] || fail "responder could not acknowledge"
jq -e '.status == "ACKNOWLEDGED" and .version == 1' "$BODY_FILE" >/dev/null || fail "unexpected state after acknowledge"

echo "6. A command based on a stale version is rejected with 409"
[[ $(incident_command "$RESPONDER_TOKEN" "$INCIDENT_ID" resolve 0) == 409 ]] || fail "stale resolve was not rejected"
jq -e '.title == "Incident changed"' "$BODY_FILE" >/dev/null || fail "stale resolve did not return the 'Incident changed' problem"
[[ $(incident_command "$RESPONDER_TOKEN" "$INCIDENT_ID" resolve 1) == 200 ]] || fail "responder could not resolve"

echo "7. The timeline records every step, oldest first, with who did it"
curl -fsS "$BASE_URL/api/incidents/$INCIDENT_ID/timeline" -H "Authorization: Bearer $VIEWER_TOKEN" \
  | jq -e 'map(.type) == ["TRIGGERED", "ACKNOWLEDGED", "RESOLVED"] and all(.actor == "responder")' >/dev/null \
  || fail "timeline does not match the lifecycle"

# wait_for_notifications <incident-id> <count>: polls until the incident has <count> notifications,
# none still PENDING, and prints them. Delivery is asynchronous and, with Slack down, includes retries.
wait_for_notifications() {
  local deadline=$((SECONDS + 30)) found
  while ((SECONDS < deadline)); do
    found=$(curl -fsS "$BASE_URL/api/notifications?incidentId=$1" -H "Authorization: Bearer $VIEWER_TOKEN")
    if jq -e --argjson n "$2" 'length == $n and all(.status != "PENDING")' <<<"$found" >/dev/null; then
      echo "$found"
      return 0
    fi
    sleep 1
  done
  return 1
}

slack_circuit() {
  curl -fsS "$BASE_URL/api/notifications/channels" -H "Authorization: Bearer $VIEWER_TOKEN" \
    | jq -r '.[] | select(.channel == "SLACK") | .circuitState // "none"'
}

echo "8. Every lifecycle step was notified (mock sink + Slack expected '$EXPECT_SLACK')"
case "$EXPECT_SLACK" in
  off) EXPECTED_COUNT=3 ;;
  *) EXPECTED_COUNT=6 ;;
esac
NOTIFICATIONS=$(wait_for_notifications "$INCIDENT_ID" "$EXPECTED_COUNT") \
  || fail "expected $EXPECTED_COUNT finished notifications for the incident within 30 s"
jq -e '[.[] | select(.channel == "MOCK")] | length == 3 and all(.status == "SENT")' <<<"$NOTIFICATIONS" >/dev/null \
  || fail "the mock sink did not receive all three lifecycle events"
case "$EXPECT_SLACK" in
  sent)
    jq -e '[.[] | select(.channel == "SLACK")] | all(.status == "SENT")' <<<"$NOTIFICATIONS" >/dev/null \
      || fail "not every Slack notification was sent"
    [[ $(slack_circuit) == CLOSED ]] || fail "Slack circuit breaker is not CLOSED"
    ;;
  down)
    jq -e '[.[] | select(.channel == "SLACK")] | all(.status == "FAILED" or .status == "SKIPPED")' \
      <<<"$NOTIFICATIONS" >/dev/null || fail "Slack is down, yet a Slack notification was not failed or skipped"
    [[ $(slack_circuit) == OPEN ]] || fail "Slack circuit breaker did not open"
    ;;
  off)
    [[ $(slack_circuit) == none ]] || fail "Slack is reported as configured"
    ;;
  *) fail "unknown EXPECT_SLACK value: $EXPECT_SLACK" ;;
esac

echo "9. notification-service's internal API is not reachable from outside"
[[ $(status_of -X POST "$BASE_URL/internal/notifications" -H 'Content-Type: application/json' -d '{}') != 200 ]] \
  || fail "/internal/notifications answered through the public entry point"
[[ $(status_of -X POST "$BASE_URL/api/internal/notifications" -H "Authorization: Bearer $RESPONDER_TOKEN" \
  -H 'Content-Type: application/json' -d '{}') == 404 ]] || fail "expected 404 for the internal API through the gateway"

# send_alert <api-key>: prints the status of POSTing one alert to the ingest endpoint.
send_alert() {
  status_of -X POST "$INGEST_URL" \
    -H "X-Beacon-Ingest-Key: $1" \
    -H 'Content-Type: application/json' \
    -d "{\"source\": \"smoke-test\", \"title\": \"$ALERT_TITLE\", \"severity\": \"HIGH\"}"
}

# wait_for_alert_count <n>: polls until exactly one incident has the alert title and n alerts; prints its id.
# The first alert pays the Lambda cold start, hence the generous deadline.
wait_for_alert_count() {
  local deadline=$((SECONDS + 90)) found
  while ((SECONDS < deadline)); do
    found=$(curl -fsS "$BASE_URL/api/incidents?size=100" -H "Authorization: Bearer $VIEWER_TOKEN" \
      | jq -r --arg title "$ALERT_TITLE" --argjson n "$1" \
        '[.content[] | select(.title == $title)] | if length == 1 and .[0].alertCount == $n then .[0].id else empty end')
    if [[ -n "$found" ]]; then
      echo "$found"
      return 0
    fi
    sleep 1
  done
  return 1
}

echo "10. The alert endpoint refuses requests without the ingest key"
[[ $(send_alert wrong-key) == 401 ]] || fail "alert with a wrong key was not rejected"

echo "11. An alert becomes an incident (API Gateway -> Lambda -> SQS -> incident-service)"
[[ $(send_alert "$INGEST_API_KEY") == 202 ]] || fail "alert was not accepted"
FIRST_ID=$(wait_for_alert_count 1) || fail "alert did not become an incident within 90 s"

echo "12. A repeat of the same alert folds into that incident instead of opening another"
[[ $(send_alert "$INGEST_API_KEY") == 202 ]] || fail "repeat alert was not accepted"
SECOND_ID=$(wait_for_alert_count 2) || fail "repeat alert was not counted against the open incident"
[[ "$FIRST_ID" == "$SECOND_ID" ]] || fail "repeat alert opened a different incident"

POSTMORTEM_URL="$BASE_URL/api/incidents/$INCIDENT_ID/postmortem"

echo "13. A responder writes the resolved incident's postmortem; a viewer can read it but not edit it"
[[ $(status_of -X PUT "$POSTMORTEM_URL" -H "Authorization: Bearer $VIEWER_TOKEN" \
  -H 'Content-Type: application/json' -d '{"summary": "Viewer edit"}') == 403 ]] \
  || fail "a viewer could write the postmortem"
curl -fsS -X PUT "$POSTMORTEM_URL" -H "Authorization: Bearer $RESPONDER_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"summary": "Smoke test postmortem", "rootCause": "None, this is a test"}' \
  | jq -e '.version == 0 and .createdBy == "responder"' >/dev/null || fail "the responder could not write the postmortem"
curl -fsS "$POSTMORTEM_URL" -H "Authorization: Bearer $VIEWER_TOKEN" \
  | jq -e '.summary == "Smoke test postmortem"' >/dev/null || fail "the viewer could not read the postmortem"

echo "14. An attachment goes straight to S3 with a presigned URL, is checked, and downloads intact"
ATTACHMENT_FILE=$(mktemp)
trap 'rm -f "$BODY_FILE" "$ATTACHMENT_FILE"' EXIT
printf '%s\n' "12:01 checkout-api OOMKilled" "12:02 rollback started" >"$ATTACHMENT_FILE"
SIZE=$(wc -c <"$ATTACHMENT_FILE" | tr -d ' ')
TICKET=$(curl -fsS -X POST "$POSTMORTEM_URL/attachments" -H "Authorization: Bearer $RESPONDER_TOKEN" \
  -H 'Content-Type: application/json' \
  -d "{\"fileName\": \"smoke.log\", \"contentType\": \"text/plain\", \"sizeBytes\": $SIZE}")
UPLOAD_URL=$(jq -er .uploadUrl <<<"$TICKET")
ATTACHMENT_ID=$(jq -er .attachment.id <<<"$TICKET")
# The URL is signed for this type and size only: S3 itself refuses anything else.
[[ $(status_of -X PUT "$UPLOAD_URL" -H 'Content-Type: text/html' --data-binary @"$ATTACHMENT_FILE") == 403 ]] \
  || fail "the upload URL accepted a different content type"
[[ $(status_of -X PUT "$UPLOAD_URL" -H 'Content-Type: text/plain' --data-binary @"$ATTACHMENT_FILE") == 200 ]] \
  || fail "the upload to S3 failed"
curl -fsS -X POST "$POSTMORTEM_URL/attachments/$ATTACHMENT_ID/complete" -H "Authorization: Bearer $RESPONDER_TOKEN" \
  | jq -e '.status == "UPLOADED"' >/dev/null || fail "the upload was not confirmed"
DOWNLOAD_URL=$(curl -fsS "$POSTMORTEM_URL/attachments/$ATTACHMENT_ID/download" \
  -H "Authorization: Bearer $VIEWER_TOKEN" | jq -er .url)
curl -fsS "$DOWNLOAD_URL" | cmp -s - "$ATTACHMENT_FILE" || fail "the downloaded file differs from the upload"

echo "Smoke test passed against $BASE_URL"
