import { expect, test } from '@playwright/test';

import { signIn } from './support';

test('a wrong password keeps you on the login page with an error', async ({ page }) => {
  await page.goto('/incidents');
  // The guard sends anonymous visitors to the login page.
  await expect(page).toHaveURL(/\/login(\?|$)/);

  await page.getByLabel('Username').fill('responder');
  await page.getByLabel('Password').fill('not-the-password');
  await page.getByRole('button', { name: 'Sign in' }).click();

  await expect(page.getByRole('alert')).toBeVisible();
  await expect(page).toHaveURL(/\/login(\?|$)/);
});

test('a viewer can read incidents but not change them', async ({ page }) => {
  await signIn(page, 'viewer');
  await expect(page.getByRole('heading', { name: 'Trigger an incident' })).toHaveCount(0);

  // Files run in name order, so the lifecycle test has always left an incident to open.
  const first = page.getByTestId('incident-row').first();
  await expect(first).toBeVisible();
  await first.getByRole('link').click();

  await expect(page.getByTestId('status')).toBeVisible();
  await expect(page.getByTestId('acknowledge')).toHaveCount(0);
  await expect(page.getByTestId('resolve')).toHaveCount(0);
  await expect(page.getByLabel('Add a note')).toHaveCount(0);

  await page.getByRole('button', { name: 'Sign out' }).click();
  await expect(page).toHaveURL(/\/login(\?|$)/);
});
