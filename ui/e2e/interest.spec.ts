import { expect, test } from '@playwright/test';

test('interest tie-half-cent run matches its golden output', async ({ page }) => {
  await page.goto('/jobs');
  await page.getByTestId('case-select-interest').selectOption('i02-tie-half-cent');
  await page.getByTestId('run-button-interest').click();
  await page.waitForURL(/\/runs\/[^/]+$/);

  const step15 = page.getByTestId('rc-table').locator('tbody tr')
    .filter({ hasText: 'INTCALC' })
    .filter({ hasText: 'STEP15' });
  await expect(step15.locator('td').nth(3)).toHaveText('0');
  await expect(page.getByTestId('run-maxrc')).toHaveText('0');

  const sysout = page.getByTestId('sysout-INTCALC.STEP15').locator('xpath=../pre');
  await expect(sysout).toContainText('START OF EXECUTION OF PROGRAM CBACT04C');
  await expect(sysout).toContainText('000000020030100010000012355{');

  await page.getByTestId('tab-datasets').click();
  const systran = page.getByTestId('dataset-AWS.M2.CARDDEMO.SYSTRAN.G0001V00');
  await expect(systran).toBeVisible();
  await expect(systran.getByRole('row').filter({ hasText: 'TRAN-AMT' }))
    .toContainText('12.35');
  await expect(page.getByTestId('dataset-AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS'))
    .toBeVisible();

  await page.getByTestId('tab-parity').click();
  await page.getByTestId('parity-button').click();
  await expect(page.getByTestId('parity-verdict')).toHaveText('PASS');
});
