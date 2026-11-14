import { expect, test } from '@playwright/test';

// Foundation smoke journey: the console shell lists every batch chain from the job library
// and opens a chain with its JCL steps and fixture cases.  Slice journeys live in e2e/<slice>.spec.ts.

test('console lists the four batch chains', async ({ page }) => {
  await page.goto('/');
  await expect(page.getByRole('heading', { name: 'Batch jobs' })).toBeVisible();
  for (const chain of ['posting', 'interest', 'trantype-update', 'trantype-dynamic']) {
    await expect(page.getByTestId(`job-row-${chain}`)).toBeVisible();
  }
});

test('a chain shows its JCL steps and fixture cases', async ({ page }) => {
  await page.goto('/#/jobs/interest');
  await expect(page.getByRole('heading', { name: 'interest', exact: true })).toBeVisible();
  await expect(page.getByText('INTCALC').first()).toBeVisible();
  await expect(page.getByText('CBACT04C').first()).toBeVisible();
  await expect(page.getByTestId('case-row-i02-tie-half-cent')).toBeVisible();
});

test('parity reports screen renders', async ({ page }) => {
  await page.goto('/#/parity');
  await expect(page.getByRole('heading', { name: 'Parity reports' })).toBeVisible();
});
