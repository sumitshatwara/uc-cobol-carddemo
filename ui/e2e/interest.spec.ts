import { expect, test, type Page } from '@playwright/test';

// Interest slice journeys: an operator runs INTCALC (Spring Batch intCalcJob) on a fixture case
// from the console and checks the return codes, SYSOUT, output datasets, parity with the legacy
// COBOL golden files, and the interest breakdown panel.

test.use({ deviceScaleFactor: 2 });

async function runCase(page: Page, caseName: string) {
  await page.goto('/#/jobs/interest');
  await page.getByTestId(`run-${caseName}`).click();
  await expect(page).toHaveURL(/#\/runs\//);
  await expect(page.getByTestId('run-maxrc')).toBeVisible();
}

test('[interest] i02-tie-half-cent: RC 0, interest truncated to 12.35 for account 2003, parity PASS', async ({ page }) => {
  await runCase(page, 'i02-tie-half-cent');
  await expect(page.getByTestId('run-maxrc')).toHaveText('0');
  await expect(page.getByTestId('rc-INTCALC-STEP15')).toContainText('0');
  await expect(page.getByTestId('parity-verdict')).toHaveText('PASS');

  const panel = page.getByTestId('interest-panel');
  await expect(panel).toBeVisible();
  const r2003 = panel.getByTestId('interest-row-2003-01-0001');
  await expect(r2003.getByTestId('interest-exact')).toHaveText('12.355000');
  await expect(r2003.getByTestId('interest-truncated')).toContainText('12.35');
  await expect(r2003.getByTestId('interest-truncated')).not.toContainText('12.36');
  await expect(r2003.getByTestId('interest-subcent')).toBeVisible();
  await expect(r2003.getByTestId('interest-java-amt')).toContainText('12.35');
  await expect(r2003.getByTestId('interest-updated')).toHaveText('updated');
  const r2004 = panel.getByTestId('interest-row-2004-01-0001');
  await expect(r2004.getByTestId('interest-truncated')).toContainText('12.34');
  await expect(r2004.getByTestId('interest-updated')).toHaveText('not updated - legacy end-of-file behaviour');

  // SYSOUT of INTCALC.STEP15 next to the legacy golden file
  await page.getByTestId('tab-sysout').click();
  await expect(page.getByTestId('java-file')).toContainText('START OF EXECUTION OF PROGRAM CBACT04C');
  await expect(page.getByTestId('java-file')).toContainText('000000020030100010000012355{');
  await expect(page.getByTestId('java-file')).toContainText('END OF EXECUTION OF PROGRAM CBACT04C');
  await expect(page.getByTestId('legacy-file')).toContainText('END OF EXECUTION OF PROGRAM CBACT04C');

  await page.screenshot({ path: '../docs/evidence/interest/console.png', fullPage: true });

  // output datasets: the new SYSTRAN generation carries 12.35 as zoned 0000000123E
  await page.getByTestId('tab-datasets').click();
  await page.getByRole('button', { name: 'AWS.M2.CARDDEMO.SYSTRAN.G0001V00.txt' }).click();
  await expect(page.getByTestId('java-file')).toContainText('Int. for a/c 00000002003');
  await expect(page.getByTestId('java-file')).toContainText('0000000123E');
  await page.getByTestId('tab-parity').click();
  await expect(page.getByTestId('parity-report')).toContainText('PASS');
});

test('[interest] i04-default-group-fallback: DEFAULT rate used and fallback lines in SYSOUT', async ({ page }) => {
  await runCase(page, 'i04-default-group-fallback');
  await expect(page.getByTestId('run-maxrc')).toHaveText('0');
  await expect(page.getByTestId('parity-verdict')).toHaveText('PASS');
  const panel = page.getByTestId('interest-panel');
  await expect(panel).toContainText('→ DEFAULT');
  await page.getByTestId('tab-sysout').click();
  await expect(page.getByTestId('java-file')).toContainText('DISCLOSURE GROUP RECORD MISSING');
  await expect(page.getByTestId('java-file')).toContainText('TRY WITH DEFAULT GROUP CODE');
});

test('[interest] i03-zero-rate-skipped: zero-rate category posts nothing', async ({ page }) => {
  await runCase(page, 'i03-zero-rate-skipped');
  await expect(page.getByTestId('run-maxrc')).toHaveText('0');
  await expect(page.getByTestId('parity-verdict')).toHaveText('PASS');
  await expect(page.getByTestId('interest-panel')).toContainText('zero rate · skipped');
});
