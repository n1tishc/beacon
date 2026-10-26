# syntax=docker/dockerfile:1
# LocalStack with Beacon's AWS pieces pre-wired: the alert-ingest Lambda, API Gateway in front of it,
# and the SQS queue + dead-letter queue behind it. Built by docker compose; the `backend` build
# context (see docker-compose.yml) supplies the Maven project.

FROM eclipse-temurin:21-jdk AS lambda
WORKDIR /workspace
COPY --from=backend . .
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -q -pl alert-ingest-lambda -am -DskipTests package \
    && cp alert-ingest-lambda/target/alert-ingest-lambda.jar /alert-ingest-lambda.jar

# 4.14 is the last LocalStack release that runs without an account token (2026.03+ require one).
FROM localstack/localstack:4.14
COPY --from=lambda /alert-ingest-lambda.jar /opt/beacon/alert-ingest-lambda.jar
COPY init/ /etc/localstack/init/ready.d/
