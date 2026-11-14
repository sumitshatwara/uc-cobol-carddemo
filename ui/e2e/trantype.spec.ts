import { expect, test, type Page } from '@playwright/test';
import path from 'node:path';

// Transaction type maintenance (MNTTRDB2 / MNTTRDYN) journeys: an operator runs the Java job on a
// fixture case from the console and checks RC, SYSOUT, the table changes and parity with the legacy run.

async function runCase(page: Page, chain: string, caseName: string) {
  await page.goto(`/#/jobs/${chain}`);
  await expect(page.getByTestId(`case-row-${caseName}`)).toBeVisible();
  await page.getByTestId(`run-${caseName}`).click();
  await expect(page).toHaveURL(/#\/runs\//);
  await expect(page.getByTestId('parity-verdict')).toHaveText('PASS');
}

test('[trantype] MNTTRDB2 t07-mixed-batch: add, update, comment, delete with RC 0 and parity PASS', async ({ page }) => {
  await runCase(page, 'trantype-update', 't07-mixed-batch');
  await expect(page.getByTestId('run-maxrc')).toHaveText('0');
  await expect(page.getByTestId('rc-MNTTRDB2-STEP1')).toContainText('0');

  const sysout = page.getByTestId('java-file');
  await expect(sysout).toContainText('DSNRUN: DSN ATTACHED, RUNNING COBTUPDT');
  await expect(sysout).toContainText('RECORD INSERTED SUCCESSFULLY');
  await expect(sysout).toContainText('RECORD UPDATED SUCCESSFULLY');
  await expect(sysout).toContainText('IGNORING COMMENTED LINE');
  await expect(sysout).toContainText('RECORD DELETED SUCCESSFULLY');
  await expect(sysout).toContainText('DSNRUN: COMMIT AT END OF TASK');
  expect(await sysout.textContent()).toBe(await page.getByTestId('legacy-file').textContent());

  const panel = page.getByTestId('trantype-panel');
  await expect(panel.getByTestId('trantype-row-07')).toHaveAttribute('data-status', 'added');
  await expect(panel.getByTestId('trantype-row-01')).toHaveAttribute('data-status', 'changed');
  await expect(panel.getByTestId('trantype-row-02')).toHaveAttribute('data-status', 'deleted');
  await expect(panel.getByTestId('trantype-row-03')).toHaveAttribute('data-status', 'unchanged');
  await expect(panel.getByTestId('trantype-counts')).toContainText('1 added');

  await page.getByTestId('tab-db').click();
  await expect(page.getByTestId('java-file')).toContainText('07,Chargeback');

  const shot = await page.context().browser()!.newContext({ viewport: { width: 1440, height: 1100 }, deviceScaleFactor: 2 });
  const p2 = await shot.newPage();
  await p2.goto(page.url());
  await expect(p2.getByTestId('trantype-row-07')).toBeVisible();
  await p2.screenshot({ path: path.resolve(process.cwd(), '../docs/evidence/trantype/console.png'), fullPage: true });
  await shot.close();
});

test('[trantype] MNTTRDYN d03-bad-statement: failed dynamic SQL aborts the unit of work, RC 4, parity PASS', async ({ page }) => {
  await runCase(page, 'trantype-dynamic', 'd03-bad-statement');
  await expect(page.getByTestId('run-maxrc')).toHaveText('4');
  await expect(page.getByTestId('rc-MNTTRDYN-STEP1')).toContainText('4');

  const sysout = page.getByTestId('java-file');
  await expect(sysout).toContainText('CBTRNDYN: EXECUTE SQLCODE -0000000400');
  await expect(sysout).toContainText('CBTRNDYN: CALLING COBTUPDT');
  await expect(sysout).toContainText('Error accessing: TRANSACTION_TYPE table. SQLCODE: -400');
  await expect(sysout).not.toContainText('CBTRNDYN: END');
  expect(await sysout.textContent()).toBe(await page.getByTestId('legacy-file').textContent());

  const panel = page.getByTestId('trantype-panel');
  await expect(panel.getByTestId('trantype-counts')).toContainText('0 added');
  await expect(panel.getByTestId('trantype-counts')).toContainText('0 changed');
  await expect(panel.getByTestId('trantype-row-01')).toHaveAttribute('data-status', 'unchanged');

  await page.getByTestId('tab-parity').click();
  await expect(page.getByTestId('parity-report')).toContainText('PASS');
});

test('[trantype] transaction types page lists the table rows via GET /api/trantype/types', async ({ page, request }) => {
  const res = await request.get('/api/trantype/types');
  expect(res.ok()).toBeTruthy();
  const rows = (await res.json()) as { trType: string }[];
  await page.goto('/');
  await page.getByTestId('nav-trantype').click();
  await expect(page.getByRole('heading', { name: 'Transaction types' })).toBeVisible();
  await expect(page.getByTestId('trantype-types').locator('tbody tr')).toHaveCount(rows.length);
});
