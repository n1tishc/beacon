import { defineConfig, devices } from '@playwright/test';

import { lambdaTestEndpoint } from './lambdatest';

// Runs against a stack that is already up; it never starts one. The variables match
// scripts/smoke-test.sh, so the same suite can point at the AWS deployment:
//   E2E_BASE_URL     the web app (default http://localhost:4200)
//   INGEST_URL       the alert endpoint (default: LocalStack's API Gateway)
//   INGEST_API_KEY   its key (default: local-ingest-key)
//   DEMO_USER_PASSWORD
//
// Keep the local default on "localhost", not 127.0.0.1: the attachment bucket's CORS rule allows
// exactly http://localhost:4200, and a browser upload from any other origin is refused.
const baseURL = process.env['E2E_BASE_URL'] ?? 'http://localhost:4200';
const lambdaTest = lambdaTestEndpoint();

export default defineConfig({
  testDir: './tests',
  // The tests share one stack, and the gateway allows 10 logins a minute per IP.
  workers: 1,
  fullyParallel: false,
  forbidOnly: !!process.env['CI'],
  retries: 0,
  timeout: 3 * 60_000,
  expect: { timeout: 15_000 },
  reporter: process.env['CI'] ? [['list'], ['html', { open: 'never' }]] : 'list',
  use: {
    baseURL,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    ...(lambdaTest ? { connectOptions: { wsEndpoint: lambdaTest } } : {}),
  },
  projects: [
    { name: lambdaTest ? 'lambdatest-chrome' : 'chromium', use: devices['Desktop Chrome'] },
  ],
});
