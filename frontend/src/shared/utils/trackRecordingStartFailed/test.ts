import { captureAnalyticsEvent } from "@utils/captureAnalyticsEvent";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import type { MicrophonePermission } from "@/shared/types/recording";

import { trackRecordingStartFailed } from ".";

vi.mock("@utils/captureAnalyticsEvent", () => ({
  captureAnalyticsEvent: vi.fn(),
}));

type StartFailedProperties = Parameters<
  typeof captureAnalyticsEvent<"recording_start_failed">
>[0]["properties"];

/** 실패한 뒤 읽는 권한 상태를 정해요. jsdom에는 권한 조회가 없어요 */
const stubPermissionAfter = (state: PermissionState) => {
  Object.defineProperty(navigator, "permissions", {
    configurable: true,
    value: { query: vi.fn().mockResolvedValue({ state }) },
  });
};

const failure = {
  errorName: null,
  surface: "dock",
  workspaceId: 3,
} as const;

const permissionBefore = () => Promise.resolve<MicrophonePermission>("prompt");

const getSentProperties = () =>
  vi
    .mocked(captureAnalyticsEvent)
    .mock.calls.map(([{ properties }]) => properties as StartFailedProperties);

describe("trackRecordingStartFailed", () => {
  beforeEach(() => {
    stubPermissionAfter("denied");
  });

  afterEach(() => {
    Reflect.deleteProperty(navigator, "permissions");
    vi.mocked(captureAnalyticsEvent).mockReset();
  });

  it.each([
    { result: "microphoneUnavailable", expected: "microphone_unavailable" },
    { result: "refused", expected: "refused" },
    { result: "networkFailed", expected: "network_failed" },
  ] as const)(
    "시작하지 못한 결과가 $result면 이유 $expected와 앞뒤 권한 상태 · 횟수 · 위치 · id를 보낸다",
    async ({ result, expected }) => {
      await trackRecordingStartFailed({
        ...failure,
        result,
        permissionBefore: permissionBefore(),
      });

      expect(captureAnalyticsEvent).toHaveBeenCalledWith({
        event: "recording_start_failed",
        properties: {
          reason: expected,
          permission_before: "prompt",
          permission_after: "denied",
          attempt: expect.any(Number),
          surface: "dock",
          workspace_id: 3,
        },
      });
    },
  );

  it("마이크를 받지 못한 오류 이름이 있으면 함께 보낸다", async () => {
    await trackRecordingStartFailed({
      ...failure,
      result: "microphoneUnavailable",
      errorName: "NotFoundError",
      permissionBefore: permissionBefore(),
      surface: "recorder_bar",
    });

    expect(getSentProperties()).toEqual([
      expect.objectContaining({
        reason: "microphone_unavailable",
        error_name: "NotFoundError",
        surface: "recorder_bar",
      }),
    ]);
  });

  it("오류 이름이 없으면 error_name을 빼고 보낸다", async () => {
    await trackRecordingStartFailed({
      ...failure,
      result: "refused",
      permissionBefore: permissionBefore(),
    });

    expect(getSentProperties()[0]).not.toHaveProperty("error_name");
  });

  it("보낼 때마다 이 탭에서 몇 번째 실패인지를 하나씩 늘린다", async () => {
    await trackRecordingStartFailed({
      ...failure,
      result: "refused",
      permissionBefore: permissionBefore(),
    });
    await trackRecordingStartFailed({
      ...failure,
      result: "networkFailed",
      permissionBefore: permissionBefore(),
    });

    const [first, second] = getSentProperties();
    expect(second.attempt).toBe(first.attempt + 1);
  });

  it("사용 기록을 보내지 못해도 오류를 던지지 않는다", async () => {
    vi.mocked(captureAnalyticsEvent).mockImplementationOnce(() => {
      throw new Error("PostHog를 불러오지 못했어요");
    });

    await expect(
      trackRecordingStartFailed({
        ...failure,
        result: "refused",
        permissionBefore: permissionBefore(),
      }),
    ).resolves.toBeUndefined();
  });
});
