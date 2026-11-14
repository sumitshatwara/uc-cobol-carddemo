import { expect, test } from '@playwright/test';
import path from 'node:path';

test.use({ deviceScaleFactor: 2 });

// Posting slice: POSTTRAN (CBTRN02C) + TRANREPT (CBTRN03C) on Spring Batch, run from the console.

async function runCase(page: import('@playwright/test').Page, caseName: string) {
  await page.goto('/#/jobs/posting');
  await expect(page.getByTestId(`case-row-${caseName}`)).toBeVisible();
  await page.getByTestId(`run-${caseName}`).click();
  await expect(page.getByTestId('run-maxrc')).toBeVisible();
}

test('[posting] p04-overlimit-reject: RC 4 on STEP15, reject reason 102, parity PASS', async ({ page }) => {
  await runCase(page, 'p04-overlimit-reject');
  await expect(page.getByTestId('run-maxrc')).toHaveText('4');
  await expect(page.getByTestId('rc-POSTTRAN-STEP15')).toContainText('4');
  await expect(page.getByTestId('parity-verdict')).toHaveText('PASS');
  const panel = page.getByTestId('posting-panel');
  await expect(panel.getByTestId('posting-step15-rc')).toHaveText('4');
  await expect(panel.getByTestId('posting-reject-count')).toHaveText('1');
  await expect(panel.getByTestId('posting-reject-reason-0')).toContainText('102');
  await expect(panel.getByTestId('posting-reject-0')).toContainText('OVERLIMIT TRANSACTION');
  await expect(panel.getByTestId('posting-report')).toContainText('Daily Transaction Report');
  await expect(panel.getByTestId('posting-report')).toContainText('Grand Total');
  // SYSOUT of CBTRN02C next to the legacy golden
  await page.getByTestId('tab-sysout').click();
  await page.getByRole('button', { name: 'POSTTRAN.STEP15.txt' }).click();
  await expect(page.getByTestId('java-file')).toContainText('TRANSACTIONS REJECTED  :000000001');
  await expect(page.getByTestId('legacy-file')).toContainText('TRANSACTIONS REJECTED  :000000001');
  // output datasets
  await page.getByTestId('tab-datasets').click();
  await expect(page.getByTestId('file-list')).toContainText('AWS.M2.CARDDEMO.DALYREJS.G0001V00');
  await expect(page.getByTestId('file-list')).toContainText('AWS.M2.CARDDEMO.TRANREPT.G0001V00');
  await page.getByTestId('tab-sysout').click();
  await page.getByTestId('posting-panel').scrollIntoViewIfNeeded();
  await page.setViewportSize({ width: 1440, height: 1100 });
  await page.screenshot({ path: path.resolve(process.cwd(), '../docs/evidence/posting/console.png'), fullPage: true });
});

test('[posting] p01-baseline-mixed: everything posts, RC 0, report with account totals, parity PASS', async ({ page }) => {
  await runCase(page, 'p01-baseline-mixed');
  await expect(page.getByTestId('run-maxrc')).toHaveText('0');
  await expect(page.getByTestId('parity-verdict')).toHaveText('PASS');
  const panel = page.getByTestId('posting-panel');
  await expect(panel.getByTestId('posting-no-rejects')).toBeVisible();
  await expect(panel.getByTestId('posting-report')).toContainText('Account Total');
  await page.getByTestId('tab-sysout').click();
  await page.getByRole('button', { name: 'TRANREPT.STEP10R.txt' }).click();
  await expect(page.getByTestId('java-file')).toContainText('TRAN-AMT 00000001575-');
});
