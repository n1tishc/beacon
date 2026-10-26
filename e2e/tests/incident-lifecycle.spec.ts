import { expect, test } from '@playwright/test';

import { sendAlert, signIn, uniqueTitle } from './support';

// The whole on-call journey in a real browser against the real stack:
// login -> alert -> live dashboard -> acknowledge -> resolve -> postmortem with an attachment.
test('a responder takes an alert from the dashboard to a written postmortem', async ({
  page,
  request,
}) => {
  const title = uniqueTitle('E2E alert');

  await test.step('sign in as a responder', async () => {
    await signIn(page, 'responder');
    await expect(page.getByTestId('current-user')).toBeVisible();
  });

  await test.step('an alert shows up on the open dashboard without a reload', async () => {
    await sendAlert(request, { source: 'e2e', title, severity: 'HIGH' });
    // The dashboard refreshes itself every 5 s. The first alert may also pay the Lambda's cold start.
    const row = page.getByTestId('incident-row').filter({ hasText: title });
    await expect(row).toBeVisible({ timeout: 90_000 });
    await expect(row.getByTestId('origin')).toHaveText(/e2e · 1 alert/);
    await expect(row).toContainText('TRIGGERED');
    await row.getByRole('link', { name: title }).click();
  });

  await test.step('acknowledge, then resolve with a note', async () => {
    await expect(page.getByRole('heading', { level: 1, name: title })).toBeVisible();
    await expect(page.getByTestId('status')).toHaveText('TRIGGERED');

    await page.getByTestId('acknowledge').click();
    await expect(page.getByTestId('status')).toHaveText('ACKNOWLEDGED');

    await page.getByLabel(/Resolution note/).fill('Rolled back the bad deploy.');
    await page.getByTestId('resolve').click();
    await expect(page.getByTestId('status')).toHaveText('RESOLVED');

    const timeline = page.getByTestId('timeline-entry');
    await expect(timeline).toHaveCount(3);
    await expect(timeline.nth(1)).toContainText('responder acknowledged');
    await expect(timeline.nth(2)).toContainText('responder resolved');
    await expect(timeline.nth(2)).toContainText('Rolled back the bad deploy.');
  });

  await test.step('write the postmortem', async () => {
    await page.getByTestId('postmortem-link').click();
    await expect(page.getByText('The postmortem opens once')).toHaveCount(0);
    await page.getByLabel('Summary', { exact: true }).fill('A bad deploy took checkout down.');
    await page.getByLabel(/Root cause/).fill('The new build ran out of memory under load.');
    await page.getByTestId('save').click();
    await expect(page.getByTestId('last-saved')).toContainText('Last saved by responder');
  });

  // The one path the curl smoke test can't cover: the browser's CORS preflight and PUT straight to
  // S3 with a presigned URL, then the confirmation back through the API.
  await test.step('attach a file, uploaded straight to S3', async () => {
    await page.getByTestId('file-input').setInputFiles({
      name: 'checkout-oom.log',
      mimeType: 'text/plain',
      buffer: Buffer.from('12:01 checkout-api OOMKilled\n12:02 rollback started\n'),
    });
    await expect(page.getByTestId('attachments')).toContainText('checkout-oom.log');
    await expect(page.getByTestId('attachment-error')).toHaveCount(0);
  });

  await test.step('everything is still there after a reload', async () => {
    await page.reload();
    await expect(page.getByLabel('Summary', { exact: true })).toHaveValue(
      'A bad deploy took checkout down.',
    );
    await expect(page.getByTestId('attachments')).toContainText('checkout-oom.log');
  });
});
