import { defineConfig, devices } from "@playwright/test";

const BASE_URL = "http://localhost:3000";
const isCI = Boolean(process.env.CI);

export default defineConfig({
  testDir: "./src/__test__", // E2E 테스트는 test-strategy에 따라 전역 __test__ 폴더에 둬요
  testMatch: "**/*.test.ts",
  fullyParallel: true,
  forbidOnly: isCI, // CI에서 test.only가 남아 있으면 실패시켜요
  retries: isCI ? 2 : 0,
  workers: isCI ? 1 : undefined,
  reporter: "html",
  use: {
    baseURL: BASE_URL,
    trace: "on-first-retry", // 재시도할 때만 트레이스를 남겨요
    // CI에서 실패한 테스트는 보고서에서 화면을 바로 보도록 스크린샷과 영상을 남겨요. 로컬은 기본값(남기지 않음) 그대로예요
    screenshot: isCI ? "only-on-failure" : "off",
    video: isCI ? "retain-on-failure" : "off",
    // dev 서버 mock이 이 쿠키로 로그인 상태를 판정해요(src/shared/api/mock/handlers/dev).
    // E2E는 로그인 플로우가 아니라 화면 플로우를 확인하므로 로그인된 상태에서 시작해요
    storageState: {
      cookies: [
        {
          name: "KNOT_MOCK_AUTH",
          value: "member",
          domain: "localhost",
          path: "/",
          expires: -1,
          httpOnly: false,
          secure: false,
          sameSite: "Lax" as const,
        },
      ],
      origins: [],
    },
  },
  projects: [
    {
      name: "chromium",
      use: {
        ...devices["Desktop Chrome"],
        // 녹음 E2E가 마이크 권한 창 없이 가짜 마이크로 녹음을 시작하게 해요
        launchOptions: {
          args: [
            "--use-fake-ui-for-media-stream",
            "--use-fake-device-for-media-stream",
          ],
        },
      },
    },
  ],
  webServer: {
    command: "pnpm dev --no-open", // 브라우저 자동 열림 없이 dev 서버를 띄워요
    url: BASE_URL,
    reuseExistingServer: !isCI, // 로컬에서 이미 떠 있는 dev 서버가 있으면 재사용해요
    timeout: 120_000,
    // 로컬 .env.development가 API_MOCKING=false여도 E2E는 늘 msw mock 응답으로 돌려요
    env: { API_MOCKING: "true" },
  },
});
