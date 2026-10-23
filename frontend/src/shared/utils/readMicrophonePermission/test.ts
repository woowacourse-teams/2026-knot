import { afterEach, describe, expect, it, vi } from "vitest";

import { readMicrophonePermission } from ".";

/** 브라우저의 권한 조회를 `query`로 바꿔요. jsdom에는 권한 조회가 없어요 */
const stubPermissions = (query: Permissions["query"]) => {
  Object.defineProperty(navigator, "permissions", {
    configurable: true,
    value: { query },
  });
};

describe("readMicrophonePermission", () => {
  afterEach(() => {
    Reflect.deleteProperty(navigator, "permissions");
  });

  it.each(["prompt", "granted", "denied"] as const)(
    "브라우저가 알려 준 마이크 권한(%s)을 그대로 돌려준다",
    async (state) => {
      const query = vi.fn().mockResolvedValue({ state });
      stubPermissions(query);

      expect(await readMicrophonePermission()).toBe(state);
      expect(query).toHaveBeenCalledWith({ name: "microphone" });
    },
  );

  it("권한 조회가 없는 브라우저면 unsupported를 돌려준다", async () => {
    expect(await readMicrophonePermission()).toBe("unsupported");
  });

  it("마이크 권한을 조회할 수 없는 브라우저(조회 거절)면 unsupported를 돌려준다", async () => {
    stubPermissions(
      vi.fn().mockRejectedValue(new TypeError("microphone is not supported")),
    );

    expect(await readMicrophonePermission()).toBe("unsupported");
  });
});
