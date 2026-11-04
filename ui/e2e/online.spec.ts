import { expect, test } from '@playwright/test';

test.beforeEach(async ({ request }) => {
  const response = await request.post('http://127.0.0.1:8080/api/online/demo-data/reset');
  expect(response.ok()).toBeTruthy();
});

test('sign-on succeeds for a regular user and routes to the main menu', async ({ page }) => {
  await page.goto('/online/signon');
  await page.getByTestId('signon-userid').fill('user0001');
  await page.getByTestId('signon-password').fill('password');
  await page.getByTestId('signon-submit').click();
  await expect(page.getByTestId('signon-result')).toBeVisible();
  await expect(page.getByTestId('signon-commarea-nextProgram')).toHaveText('COMEN01C');
  await expect(page.getByTestId('signon-commarea-userId')).toHaveText('USER0001');
  await expect(page.getByTestId('signon-commarea-userType')).toHaveText('U');
  await expect(page.getByTestId('signon-error')).toHaveCount(0);
});

test('sign-on fails with a wrong password', async ({ page }) => {
  await page.goto('/online/signon');
  await page.getByTestId('signon-userid').fill('USER0001');
  await page.getByTestId('signon-password').fill('WRONGPW1');
  await page.getByTestId('signon-submit').click();
  await expect(page.getByTestId('signon-error')).toHaveText('Wrong Password. Try again ...');
  await expect(page.getByTestId('signon-result')).toHaveCount(0);
});

test('account view shows the account and customer with a formatted SSN', async ({ page }) => {
  await page.goto('/online/accounts');
  await page.getByTestId('account-id').fill('00000000001');
  await page.getByTestId('account-submit').click();
  await expect(page.getByTestId('account-details')).toBeVisible();
  await expect(page.getByTestId('account-field-acctId')).toHaveText('00000000001');
  await expect(page.getByTestId('account-field-currBal')).toHaveText('194.00');
  await expect(page.getByTestId('customer-field-lastName')).toHaveText('SAMPLE');
  await expect(page.getByTestId('customer-field-ssn')).toHaveText('123-45-6789');
});

test('account view rejects a zero account number', async ({ page }) => {
  await page.goto('/online/accounts');
  await page.getByTestId('account-id').fill('00000000000');
  await page.getByTestId('account-submit').click();
  await expect(page.getByTestId('account-error')).toHaveText('Account Filter must  be a non-zero 11 digit number');
});

test('late-fee calculator applies 2.5% truncated and the GOLD waiver', async ({ page }) => {
  await page.goto('/online/fees');
  await page.getByTestId('fee-submit').click();
  await expect(page.getByTestId('fee-out-feeAmt')).toHaveText('30.86');
  await expect(page.getByTestId('fee-out-avgDaily')).toHaveText('41.15');
  await expect(page.getByTestId('fee-out-rc')).toHaveText('0');

  await page.getByTestId('fee-group-id').fill('GOLD');
  await page.getByTestId('fee-cycle-bal').fill('80.00');
  await page.getByTestId('fee-submit').click();
  await expect(page.getByTestId('fee-out-feeAmt')).toHaveText('0.00');
  await expect(page.getByTestId('fee-out-rc')).toHaveText('4');
});
