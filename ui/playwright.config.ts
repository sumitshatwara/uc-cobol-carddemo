import { defineConfig, devices } from '@playwright/test';

/**
 * Functional (end-to-end) layer.  Starts the Spring Boot service (which also serves the built
 * console from ui/dist) unless one is already running on :8080.  Slices add their journeys as
 * e2e/<slice>.spec.ts.  Requires `make java-db java-build ui-build` (done by `make test-e2e`).
 */
const port = Number(process.env.CARDDEMO_PORT ?? 8080);

export default defineConfig({
  testDir: './e2e',
  timeout: 120_000,
  expect: { timeout: 30_000 },
  fullyParallel: false,
  workers: 1, // one run at a time: every run owns the carddemo schema
  reporter: [['list'], ['json', { outputFile: 'test-results/e2e-results.json' }], ['html', { open: 'never' }]],
  use: {
    baseURL: `http://localhost:${port}`,
    trace: 'off',
    screenshot: 'only-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'], viewport: { width: 1440, height: 900 } } }],
  webServer: {
    command: `java -jar ../java/app/target/carddemo-app.jar --server.port=${port}`,
    url: `http://localhost:${port}/api/health`,
    reuseExistingServer: true,
    timeout: 120_000,
  },
});
