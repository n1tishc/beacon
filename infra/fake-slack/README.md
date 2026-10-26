# fake-slack

A WireMock server that answers like a Slack incoming webhook (`200 ok`). docker-compose points
notification-service at it, so Slack delivery and its circuit breaker work locally without a Slack
workspace or a secret.

- What was "posted to Slack": `curl -s localhost:8089/__admin/requests | jq '.requests[].request.body'`
  (published on 127.0.0.1 only; `FAKE_SLACK_PORT` in `.env` changes the port)
- Slack outage: `docker compose stop fake-slack`, then `docker compose start fake-slack`
- A real workspace instead: set `SLACK_WEBHOOK_URL` in `.env`
