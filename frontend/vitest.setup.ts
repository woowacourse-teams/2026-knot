import "@testing-library/jest-dom/vitest";
import { afterAll, afterEach, beforeAll, vi } from "vitest";

import { mockServer } from "@api/mock/server";
import { resetDocumentMockState } from "@api/mock/state/document";
import { resetRecordingMockState } from "@api/mock/state/recording";

import { installFakeMedia } from "./vitest.media";

// 핸들러 없는 요청은 테스트 실패로 드러내고, 테스트 사이 핸들러 누수는 resetHandlers로 막아요.
// 요청으로 바뀐 mock 상태(문서 확인)도 다음 테스트로 넘어가지 않게 지워요
beforeAll(() => mockServer.listen({ onUnhandledRequest: "error" }));
// jsdom에는 마이크·녹음 API가 없어 가짜를 깔아 둬요
beforeAll(() => installFakeMedia());
// jsdom은 캔버스를 그리지 못해요. 입자 캔버스는 문맥이 없으면 그리지 않으므로 「구현되지 않음」 경고 대신 조용히 null을 돌려줘요
beforeAll(() => {
  HTMLCanvasElement.prototype.getContext = () => null;
});
// jsdom에는 ResizeObserver가 없어요. 크기가 바뀌지 않는 환경이라 알릴 일이 없는 빈 구현을 둬요
beforeAll(() => {
  globalThis.ResizeObserver ??= class {
    observe() {}
    unobserve() {}
    disconnect() {}
  };
});
afterEach(() => {
  mockServer.resetHandlers();
  // 요청으로 바뀐 mock 상태가 다음 테스트의 응답을 바꾸지 않게 지워요
  resetDocumentMockState();
  resetRecordingMockState();
  vi.restoreAllMocks();
});
afterAll(() => mockServer.close());
