#!/usr/bin/env bash
# End-to-end check of a running Beacon stack, through its public entry point.
#
#   scripts/smoke-test.sh [base-url]        default: http://localhost:4200
#
# Uses the demo accounts, so DEMO_USER_PASSWORD must match the stack's (default: beacon-demo).
# Alerts go to INGEST_URL (default: LocalStack's API Gateway) with INGEST_API_KEY (default: local-ingest-key).
# Requires curl and jq. Used by CI against docker compose and, later, against the AWS deployment.
set -euo pipefail

BASE_URL="${1:-http://localhost:4200}"
PASSWORD="${DEMO_USER_PASSWORD:-beacon-demo}"
INGEST_URL="${INGEST_URL:-http://localhost:4566/_aws/execute-api/beacon/local/alerts}"
INGEST_API_KEY="${INGEST_API_KEY:-local-ingest-key}"
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

echo "8. The alert endpoint refuses requests without the ingest key"
[[ $(send_alert wrong-key) == 401 ]] || fail "alert with a wrong key was not rejected"

echo "9. An alert becomes an incident (API Gateway -> Lambda -> SQS -> incident-service)"
[[ $(send_alert "$INGEST_API_KEY") == 202 ]] || fail "alert was not accepted"
FIRST_ID=$(wait_for_alert_count 1) || fail "alert did not become an incident within 90 s"

echo "10. A repeat of the same alert folds into that incident instead of opening another"
[[ $(send_alert "$INGEST_API_KEY") == 202 ]] || fail "repeat alert was not accepted"
SECOND_ID=$(wait_for_alert_count 2) || fail "repeat alert was not counted against the open incident"
[[ "$FIRST_ID" == "$SECOND_ID" ]] || fail "repeat alert opened a different incident"

echo "Smoke test passed against $BASE_URL"
