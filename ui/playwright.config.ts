import { defineConfig } from '@playwright/test';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const uiDir = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(uiDir, '..');
const reuseExistingServer = !process.env.CI;

export default defineConfig({
  testDir: './e2e',
  // specs share one PostgreSQL database; chain runs truncate tables the online seed uses
  workers: 1,
  fullyParallel: false,
  reporter: [['list'], ['html', { outputFolder: 'playwright-report', open: 'never' }]],
  use: {
    baseURL: 'http://127.0.0.1:4173',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  webServer: [
    {
      command: 'java -jar java/app/target/app-1.0.0-SNAPSHOT.jar',
      cwd: root,
      url: 'http://127.0.0.1:8080/api/jobs',
      reuseExistingServer,
      timeout: 120_000,
    },
    {
      command: 'npm run preview -- --host 127.0.0.1 --port 4173',
      cwd: uiDir,
      url: 'http://127.0.0.1:4173',
      reuseExistingServer,
      timeout: 60_000,
    },
  ],
});
