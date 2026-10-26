#!/usr/bin/env bash
# End-to-end check of a running Beacon stack, through its public entry point.
#
#   scripts/smoke-test.sh [base-url]        default: http://localhost:4200
#
# Uses the demo accounts, so DEMO_USER_PASSWORD must match the stack's (default: beacon-demo).
# Requires curl and jq. Used by CI against docker compose and, later, against the AWS deployment.
set -euo pipefail

BASE_URL="${1:-http://localhost:4200}"
PASSWORD="${DEMO_USER_PASSWORD:-beacon-demo}"
TITLE="Smoke test $(date -u +%Y%m%dT%H%M%SZ)"

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

echo "Smoke test passed against $BASE_URL"
