import { defineConfig } from '@playwright/test';

// The browser walk (e2e/): a real browser against the built UI image, which proxies to a real
// service on a real database. Nothing is mocked, so the walk needs the stack up first:
// scripts/verify-admin-ui.sh brings it up with Compose and runs this; E2E_BASE_URL points at
// any other instance (a laptop's `docker compose up`, say). One worker, serial files: the
// tests share one deployment and build on each other's state.
export default defineConfig({
  testDir: 'e2e',
  fullyParallel: false,
  workers: 1,
  retries: process.env.CI ? 1 : 0,
  timeout: 30_000,
  expect: { timeout: 10_000 },
  reporter: process.env.CI ? [['list'], ['html', { open: 'never' }]] : 'list',
  use: {
    baseURL: process.env.E2E_BASE_URL ?? 'http://localhost:18084',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [{ name: 'chromium', use: { browserName: 'chromium' } }],
});
