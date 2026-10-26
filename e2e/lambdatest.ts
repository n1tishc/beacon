// Runs the suite on LambdaTest's cloud browsers instead of a local Chromium when LT_USERNAME and
// LT_ACCESS_KEY are set. The capability format follows LambdaTest's Playwright samples
// (github.com/LambdaTest/playwright-sample). A cloud browser can reach a public URL such as the
// AWS deployment directly; for a local stack, start the LambdaTest tunnel and set LT_TUNNEL=true.
export function lambdaTestEndpoint(): string | undefined {
  const user = process.env['LT_USERNAME'];
  const accessKey = process.env['LT_ACCESS_KEY'];
  if (!user || !accessKey) {
    return undefined;
  }
  const capabilities = {
    browserName: 'Chrome',
    browserVersion: 'latest',
    'LT:Options': {
      platform: 'Windows 11',
      build: process.env['LT_BUILD'] ?? 'Beacon E2E',
      name: 'Beacon incident lifecycle',
      user,
      accessKey,
      tunnel: process.env['LT_TUNNEL'] === 'true',
    },
  };
  return `wss://cdp.lambdatest.com/playwright?capabilities=${encodeURIComponent(JSON.stringify(capabilities))}`;
}
