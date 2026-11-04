import { expect, test } from '@playwright/test';

test('trantype update run shows return codes, SYSOUT, database snapshot, and parity', async ({ page }) => {
  await page.goto('/jobs');
  await expect(page.getByTestId('job-row-trantype-update')).toBeVisible();
  await page.getByTestId('case-select-trantype-update').selectOption('t07-mixed-batch');
  await page.getByTestId('run-button-trantype-update').click();

  await expect(page).toHaveURL(/\/runs\/[^/]+$/);
  await expect(page.getByTestId('run-maxrc')).toHaveText('0');
  await expect(page.getByTestId('rc-table').locator('tbody tr').first().locator('td'))
    .toHaveText(['MNTTRDB2', '0', 'STEP1', '0']);

  const sysout = page.getByRole('heading', { name: 'MNTTRDB2 · STEP1' }).locator('..').locator('pre');
  await expect(sysout).toContainText('RECORD INSERTED SUCCESSFULLY');
  await expect(sysout).toContainText('DSNRUN: COMMIT AT END OF TASK');

  await page.getByTestId('tab-db').click();
  await expect(page.getByRole('heading', { name: 'carddemo.transaction_type.csv' })).toBeVisible();
  await expect(page.getByText('07,Chargeback', { exact: false })).toBeVisible();

  await page.getByTestId('tab-parity').click();
  await page.getByTestId('parity-button').click();
  await expect(page.getByTestId('parity-verdict')).toHaveText('PASS');
});
