import { expect, test } from '@playwright/test';

test('posting overlimit rejection is visible and matches the legacy fixture', async ({ page }) => {
  await page.goto('/jobs');
  await page.getByTestId('case-select-posting').selectOption('p04-overlimit-reject');
  await page.getByTestId('run-button-posting').click();

  await expect(page.getByTestId('rc-table')).toContainText('POSTTRAN');
  const postingStep = page.getByTestId('rc-table').getByRole('row')
    .filter({ hasText: 'POSTTRAN' }).filter({ hasText: 'STEP15' });
  const reportStep = page.getByTestId('rc-table').getByRole('row')
    .filter({ hasText: 'TRANREPT' }).filter({ hasText: 'STEP10R' });
  await expect(postingStep).toContainText('4');
  await expect(reportStep).toContainText('0');

  await expect(page.getByTestId('sysout-POSTTRAN.STEP15').locator('..'))
    .toContainText('TRANSACTIONS REJECTED  :000000001');
  await page.getByTestId('tab-datasets').click();
  const rejects = page.getByTestId('dataset-AWS.M2.CARDDEMO.DALYREJS.G0001V00');
  await expect(rejects).toContainText('0102OVERLIMIT TRANSACTION');

  await page.getByTestId('tab-parity').click();
  await page.getByTestId('parity-button').click();
  await expect(page.getByTestId('parity-verdict')).toHaveText('PASS');
});
