import { setupSentryEvents } from "@provider/sentry/mock";
import { describe, expect, it, vi } from "vitest";

import Microphone from "./microphone";

const createMicrophone = () =>
  new Microphone({ onData: () => undefined, onLost: () => undefined });

describe("Microphone 연결 실패 보고", () => {
  const { flushEvents } = setupSentryEvents();

  it("다른 앱이 마이크를 쓰고 있어 받지 못하면(NotReadableError) 지금처럼 null을 돌려주고 그 오류를 한 번 보고한다", async () => {
    vi.spyOn(navigator.mediaDevices, "getUserMedia").mockRejectedValue(
      new DOMException("Could not start audio source", "NotReadableError"),
    );

    expect(await createMicrophone().connect()).toBeNull();

    const events = await flushEvents();
    expect(events).toHaveLength(1);
    expect(events[0].tags).toMatchObject({ feature: "recording" });
    expect(events[0].exception?.values?.[0]?.value).toContain(
      "Could not start audio source",
    );
  });

  it("마이크를 받은 뒤 녹음기를 만들지 못해도 null을 돌려주고 그 오류를 보고한다", async () => {
    vi.spyOn(window, "MediaRecorder").mockImplementation(() => {
      throw new DOMException("녹음기를 만들지 못했어요", "NotSupportedError");
    });

    expect(await createMicrophone().connect()).toBeNull();

    const events = await flushEvents();
    expect(events).toHaveLength(1);
    expect(events[0].exception?.values?.[0]?.value).toContain(
      "녹음기를 만들지 못했어요",
    );
  });

  // 마이크 안내 창이 권한과 장치를 확인하라고 안내해요
  it.each(["NotAllowedError", "NotFoundError"])(
    "권한 거절이나 마이크 없음(%s)은 null만 돌려주고 보고하지 않는다",
    async (name) => {
      vi.spyOn(navigator.mediaDevices, "getUserMedia").mockRejectedValue(
        new DOMException("마이크를 받지 못했어요", name),
      );

      expect(await createMicrophone().connect()).toBeNull();

      expect(await flushEvents()).toHaveLength(0);
    },
  );
});

describe("Microphone 연결하지 못한 이유", () => {
  it("마이크를 받지 못하면 그 오류 이름을 남긴다", async () => {
    vi.spyOn(navigator.mediaDevices, "getUserMedia").mockRejectedValue(
      new DOMException("", "NotFoundError"),
    );
    const microphone = createMicrophone();

    await microphone.connect();

    expect(microphone.errorName).toBe("NotFoundError");
  });

  it("권한을 기다리는 사이 끊은 뒤 거절되면 이유를 남기지 않는다", async () => {
    let denyPermission = () => {};
    vi.spyOn(navigator.mediaDevices, "getUserMedia").mockReturnValue(
      new Promise((_, reject) => {
        denyPermission = () => reject(new DOMException("", "NotAllowedError"));
      }),
    );
    const microphone = createMicrophone();
    const connecting = microphone.connect();

    microphone.disconnect();
    denyPermission();

    expect(await connecting).toBeNull();
    expect(microphone.errorName).toBeNull();
  });
});
