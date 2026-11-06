import { act, renderHook, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import useScreenWakeLock from ".";

const installWakeLock = () => {
  const release = vi.fn(() => Promise.resolve());
  const request = vi.fn(() => Promise.resolve({ release }));
  Object.defineProperty(navigator, "wakeLock", {
    value: { request },
    configurable: true,
  });

  return { request, release };
};

const setVisibility = (state: DocumentVisibilityState) => {
  Object.defineProperty(document, "visibilityState", {
    value: state,
    configurable: true,
  });
  document.dispatchEvent(new Event("visibilitychange"));
};

describe("useScreenWakeLock", () => {
  afterEach(() => {
    Reflect.deleteProperty(navigator, "wakeLock");
    Reflect.deleteProperty(document, "visibilityState");
  });

  it("켜 두면 화면 잠금을 잡는다", async () => {
    const { request } = installWakeLock();

    renderHook(() => useScreenWakeLock({ isEnabled: true }));

    await waitFor(() => expect(request).toHaveBeenCalledWith("screen"));
  });

  it("꺼 두면 잡지 않는다", () => {
    const { request } = installWakeLock();

    renderHook(() => useScreenWakeLock({ isEnabled: false }));

    expect(request).not.toHaveBeenCalled();
  });

  it("꺼지면 잡은 잠금을 놓는다", async () => {
    const { request, release } = installWakeLock();
    const { rerender } = renderHook(
      ({ isEnabled }) => useScreenWakeLock({ isEnabled }),
      { initialProps: { isEnabled: true } },
    );
    await waitFor(() => expect(request).toHaveBeenCalledTimes(1));
    await act(async () => {});

    rerender({ isEnabled: false });

    expect(release).toHaveBeenCalledTimes(1);
  });

  it("잠금을 받기 전에 꺼지면 받자마자 놓는다", async () => {
    const { release } = installWakeLock();
    const { unmount } = renderHook(() =>
      useScreenWakeLock({ isEnabled: true }),
    );

    unmount();

    await waitFor(() => expect(release).toHaveBeenCalledTimes(1));
  });

  it("탭이 가려졌다가 다시 보이면 다시 잡는다", async () => {
    const { request } = installWakeLock();
    renderHook(() => useScreenWakeLock({ isEnabled: true }));
    await waitFor(() => expect(request).toHaveBeenCalledTimes(1));

    setVisibility("hidden");
    expect(request).toHaveBeenCalledTimes(1);

    setVisibility("visible");
    await waitFor(() => expect(request).toHaveBeenCalledTimes(2));
  });

  it("지원하지 않는 브라우저에서는 조용히 넘어간다", () => {
    expect(() =>
      renderHook(() => useScreenWakeLock({ isEnabled: true })),
    ).not.toThrow();
  });

  it("브라우저가 거절해도 오류를 내지 않는다", async () => {
    const { request } = installWakeLock();
    request.mockRejectedValue(new DOMException("거절", "NotAllowedError"));

    renderHook(() => useScreenWakeLock({ isEnabled: true }));

    await waitFor(() => expect(request).toHaveBeenCalled());
  });
});
