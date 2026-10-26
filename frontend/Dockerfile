# syntax=docker/dockerfile:1.7
# Local/dev image: nginx serves the Angular build and proxies /api to the backend,
# so the browser talks to a single origin (no CORS). In AWS the build is served from S3 + CloudFront instead.

FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci --no-audit --no-fund
COPY . .
RUN NG_CLI_ANALYTICS=false npx ng build

FROM nginx:1.29-alpine
# The official nginx image runs envsubst over /etc/nginx/templates/*.template at startup.
ENV API_UPSTREAM=http://incident-service:8081
COPY nginx/default.conf.template /etc/nginx/templates/default.conf.template
COPY --from=build /app/dist/beacon-web/browser /usr/share/nginx/html
