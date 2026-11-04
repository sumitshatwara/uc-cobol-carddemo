import { expect, test } from '@playwright/test';

test('jobs page lists all fixture chains and main navigation works', async ({ page }) => {
  await page.goto('/jobs');
  const table = page.getByTestId('jobs-table');
  await expect(table.locator('tbody tr[data-testid^="job-row-"]')).toHaveCount(4);
  for (const chain of ['posting', 'interest', 'trantype-update', 'trantype-dynamic']) {
    await expect(page.getByTestId(`job-row-${chain}`)).toBeVisible();
  }
  await page.getByRole('link', { name: /Runs/ }).click();
  await expect(page.getByRole('heading', { name: 'Runs' })).toBeVisible();
  await page.getByRole('link', { name: /Parity report/ }).click();
  await expect(page.getByRole('heading', { name: 'Parity report' })).toBeVisible();
});
