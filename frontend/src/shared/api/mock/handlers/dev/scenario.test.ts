import { currentRecordingResponse } from "@api/mock/responses/recording";
import { mockServer } from "@api/mock/server";
import { afterEach, beforeEach, describe, expect, it } from "vitest";

import {
  applyMockScenarioFromQuery,
  devScenarioHandlers,
  MOCK_SCENARIO_STORAGE_KEY,
  readMockScenario,
  writeMockScenario,
} from "./scenario";

const CURRENT_URL = "http://localhost:3000/api/v1/workspaces/1/recordings/current";

beforeEach(() => mockServer.use(...devScenarioHandlers));

afterEach(() => {
  localStorage.removeItem(MOCK_SCENARIO_STORAGE_KEY);
});

describe("개발 브라우저 전용 상황 핸들러", () => {
  it("아무것도 고르지 않았으면 녹음이 없다(204)", async () => {
    const response = await fetch(CURRENT_URL);

    expect(response.status).toBe(204);
  });

  it("고른 상황대로 현재 녹음을 답한다", async () => {
    writeMockScenario({ currentRecording: "paused" });

    const response = await fetch(CURRENT_URL);

    expect(response.status).toBe(200);
    await expect(response.json()).resolves.toEqual({ ...currentRecordingResponse, status: "PAUSED" });
  });

  it("주소의 mock 파라미터로도 고를 수 있다", () => {
    expect(applyMockScenarioFromQuery("?mock.currentRecording=recording")).toBe(true);
    expect(readMockScenario().currentRecording).toBe("recording");

    expect(applyMockScenarioFromQuery("?mock.currentRecording=nope")).toBe(false);
    expect(readMockScenario().currentRecording).toBe("recording");
  });

  it("저장된 값이 깨졌으면 기본값이다", () => {
    localStorage.setItem(MOCK_SCENARIO_STORAGE_KEY, "{broken");

    expect(readMockScenario()).toEqual({
      currentRecording: "none",
      teammateRecordings: "none",
    });
  });

  it("주소의 mock.teammateRecordings로 팀원 진행 중 녹음 상황을 고른다", () => {
    expect(applyMockScenarioFromQuery("?mock.teammateRecordings=some")).toBe(
      true,
    );
    expect(readMockScenario().teammateRecordings).toBe("some");
  });
});
