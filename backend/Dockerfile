# syntax=docker/dockerfile:1.7
# One Dockerfile for every backend service:
#   docker build --build-arg SERVICE=incident-service backend/
# Maven runs inside the image, so building needs Docker only (no local JDK).

FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY . .
ARG SERVICE
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -q -pl "${SERVICE}" -am -DskipTests package \
    && cp "${SERVICE}/target/${SERVICE}.jar" /workspace/app.jar

FROM eclipse-temurin:21-jre
RUN groupadd --system beacon && useradd --system --gid beacon --no-create-home beacon
WORKDIR /app
COPY --from=build /workspace/app.jar app.jar
USER beacon

# Small-footprint defaults: in the AWS deployment several JVMs share one 1 GB t3.micro.
# Override per environment with JAVA_OPTS.
ENV JAVA_OPTS="-XX:+UseSerialGC -XX:MaxRAMPercentage=75 -Xss512k"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
