import { APIRequestContext, expect, Page } from '@playwright/test';

export const password = process.env['DEMO_USER_PASSWORD'] ?? 'beacon-demo';
const ingestUrl =
  process.env['INGEST_URL'] ?? 'http://localhost:4566/_aws/execute-api/beacon/local/alerts';
const ingestApiKey = process.env['INGEST_API_KEY'] ?? 'local-ingest-key';

/** A title no earlier run can have used, so assertions only ever match this run's incident. */
export function uniqueTitle(prefix: string): string {
  return `${prefix} ${new Date().toISOString()}`;
}

/** Signs in through the login form, the way a person would, and waits for the dashboard. */
export async function signIn(page: Page, username: string): Promise<void> {
  await page.goto('/login');
  await page.getByLabel('Username').fill(username);
  await page.getByLabel('Password').fill(password);
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page.getByRole('heading', { level: 1, name: 'Incidents' })).toBeVisible();
}

/** Sends an alert to the public ingest endpoint, as a monitoring tool would. */
export async function sendAlert(
  request: APIRequestContext,
  alert: { source: string; title: string; severity: string },
): Promise<void> {
  const response = await request.post(ingestUrl, {
    headers: { 'X-Beacon-Ingest-Key': ingestApiKey },
    data: alert,
  });
  expect(response.status(), await response.text()).toBe(202);
}
