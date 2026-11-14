import fs from 'node:fs';
import path from 'node:path';
import { expect, test } from '@playwright/test';
import type { Page } from '@playwright/test';

// Online slice journeys: the CICS screens (COSGN00C -> COMEN01C -> COACTVWC) served by java/online,
// plus the PL/I CBFEECAL late-fee calculator and the HLASM COBDATFT date converter.

function evidenceDir(): string {
  // rootDir is ui/e2e; evidence lives in docs/evidence/online at the repo root.
  const dir = path.resolve(test.info().config.rootDir, '..', '..', 'docs', 'evidence', 'online');
  fs.mkdirSync(dir, { recursive: true });
  return dir;
}

async function signOn(page: Page, userId: string, password: string) {
  await page.goto('/#/online/signon');
  await expect(page.getByTestId('signon-userid')).toBeVisible();
  await page.getByTestId('signon-userid').fill(userId);
  await page.getByTestId('signon-password').fill(password);
  await page.getByTestId('signon-enter').click();
}

test.describe('[online] journey', () => {
  test.use({ deviceScaleFactor: 2 });

  test('[online] admin signs on, opens the menu and views a seeded account', async ({ page }) => {
    await signOn(page, 'ADMIN001', 'password');

    // Main menu (admin falls back to COMEN01C in this slice).
    await expect(page).toHaveURL(/#\/online\/menu$/);
    await expect(page.getByTestId('menu-option-01')).toHaveText('01. Account View');
    await expect(page.getByText('01. Account View')).toBeVisible();
    await expect(page.getByTestId('menu-admin-note')).toContainText('COADM01C');
    await page.getByTestId('menu-option-input').fill('1');
    await page.getByTestId('menu-enter').click();

    // Account view.
    await expect(page).toHaveURL(/#\/online\/accounts$/);
    await page.getByTestId('view-acctid').fill('00000000099');
    await page.getByTestId('view-enter').click();
    await expect(page.getByTestId('view-field-firstName')).toContainText('AVERY');
    await expect(page.getByTestId('view-field-lastName')).toContainText('SAMPLE');
    await expect(page.getByTestId('view-field-groupId')).toContainText('STANDARD');
    await expect(page.getByTestId('view-field-creditLimit')).toContainText('2,000.00');
    await expect(page.getByTestId('view-info')).toContainText('Enter or update id of account to display');
    const dir = evidenceDir();
    await page.screenshot({ path: path.join(dir, 'console.png'), fullPage: true });

    // Late-fee calculator, worked example 1 (STANDARD, 1234.56, 10, 30).
    await page.getByTestId('nav-online-fees').click();
    await expect(page).toHaveURL(/#\/online\/fees$/);
    await page.getByTestId('fee-example-1').click();
    await expect(page.getByTestId('fee-groupid')).toHaveValue('STANDARD');
    await expect(page.getByTestId('fee-cyclebal')).toHaveValue('1234.56');
    await expect(page.getByTestId('fee-latedays')).toHaveValue('10');
    await expect(page.getByTestId('fee-cycledays')).toHaveValue('30');
    await page.getByTestId('fee-calc').click();
    await expect(page.getByTestId('fee-amt')).toHaveText('30.86');
    await expect(page.getByTestId('fee-avg')).toHaveText('41.15');
    await expect(page.getByTestId('fee-rc')).toHaveText('0');
    await expect(page.getByTestId('fee-line')).not.toBeEmpty();
    await page.screenshot({ path: path.join(dir, 'console-fees.png'), fullPage: true });
  });
});

test('[online] sign-on rejects a wrong password', async ({ page }) => {
  await signOn(page, 'USER0001', 'WRONGPWD');
  await expect(page.getByTestId('signon-message')).toContainText('Wrong Password. Try again ...');
  await expect(page).toHaveURL(/#\/online\/signon$/);
});

test('[online] date converter reformats YYYYMMDD', async ({ page }) => {
  await page.goto('/#/online/dates');
  await page.getByTestId('date-intype').selectOption('1');
  await page.getByTestId('date-indate').fill('20250315');
  await page.getByTestId('date-outtype').selectOption('1');
  await page.getByTestId('date-convert').click();
  await expect(page.getByTestId('date-out')).toHaveText('2025-03-15');
  await expect(page.getByTestId('date-rc')).toHaveText('0');
});

test('[online] account update validates a change and PF12 cancels it', async ({ page }) => {
  await page.goto('/#/online/accounts/update');
  await page.getByTestId('upd-acctid').fill('00000000099');
  await page.getByTestId('upd-enter').click();
  await expect(page.getByTestId('upd-info')).toContainText('Update account details presented above.');
  await expect(page.getByTestId('upd-field-creditLimit')).toHaveValue('+      2,000.00');
  // first error wins
  await page.getByTestId('upd-field-acctStatus').fill('X');
  await page.getByTestId('upd-enter').click();
  await expect(page.getByTestId('upd-error')).toContainText('Account Status must be Y or N.');
  // valid change -> confirmation
  await page.getByTestId('upd-field-acctStatus').fill('Y');
  await page.getByTestId('upd-field-creditLimit').fill('2500.00');
  await page.getByTestId('upd-enter').click();
  await expect(page.getByTestId('upd-info')).toContainText('Changes validated.Press F5 to save');
  await expect(page.getByTestId('upd-pf5')).toBeEnabled();
  // PF12 re-reads the stored values (nothing saved)
  await page.getByTestId('upd-pf12').click();
  await expect(page.getByTestId('upd-field-creditLimit')).toHaveValue('+      2,000.00');
  await expect(page.getByTestId('upd-info')).toContainText('Update account details presented above.');
});
