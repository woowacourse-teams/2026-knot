import { afterEach, describe, expect, it, vi } from "vitest";

import {
  clearRecordingStartProof,
  completeRecordingReconnect,
  getRecordingControlProof,
  getRecordingResumeProof,
  getRecordingStartProof,
  prepareRecordingReconnect,
  saveRecordingResumeProof,
} from ".";

const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

// 서버 RecordingStartRequest의 controlToken 검증식(32바이트 패딩 없는 Base64URL)
const CONTROL_TOKEN_PATTERN = /^[A-Za-z0-9_-]{42}[AEIMQUYcgkosw048]$/;

describe("recordingControlProof", () => {
  afterEach(() => clearRecordingStartProof());

  it("시작 증명은 UUID 두 개와 서버 형식의 제어 증명으로 만든다", () => {
    const proof = getRecordingStartProof();

    expect(proof.requestId).toMatch(UUID_PATTERN);
    expect(proof.tabId).toMatch(UUID_PATTERN);
    expect(proof.controlToken).toMatch(CONTROL_TOKEN_PATTERN);
  });

  it("시작이 끝나기 전에는 같은 시작 증명을 다시 돌려준다", () => {
    expect(getRecordingStartProof()).toEqual(getRecordingStartProof());
  });

  it("녹음이 열리기 전의 시작 증명은 sessionStorage에 남기지 않는다", () => {
    getRecordingStartProof();

    expect(sessionStorage.length).toBe(0);
  });

  it("탭 ID는 페이지를 열 때마다 새로 만든다", async () => {
    const { tabId } = getRecordingStartProof();
    vi.resetModules();
    const reloaded = await import(".");

    expect(reloaded.getRecordingStartProof().tabId).not.toBe(tabId);
  });

  it("제어 증명은 시작 때 만든 탭 ID와 제어 증명을 돌려준다", () => {
    const { tabId, controlToken } = getRecordingStartProof();

    expect(getRecordingControlProof()).toEqual({ tabId, controlToken });
  });

  it("시작 전에는 제어 증명이 없다", () => {
    expect(getRecordingControlProof()).toBeNull();
  });

  it("시작 증명을 지우면 새 요청 ID와 제어 증명을 만들고 탭 ID는 유지한다", () => {
    const before = getRecordingStartProof();

    clearRecordingStartProof();
    const after = getRecordingStartProof();

    expect(getRecordingControlProof()).toEqual({
      tabId: after.tabId,
      controlToken: after.controlToken,
    });
    expect(after.tabId).toBe(before.tabId);
    expect(after.requestId).not.toBe(before.requestId);
    expect(after.controlToken).not.toBe(before.controlToken);
  });

  describe("이어 녹음 증명", () => {
    const SESSION = {
      workspaceId: 1,
      recordingId: 10,
      audioMode: "PCM16_CHUNKS_V1",
    } as const;

    it("녹음이 열리면 지금 증명을 sessionStorage에 남긴다", () => {
      const { tabId, controlToken } = getRecordingStartProof();

      saveRecordingResumeProof(SESSION);

      expect(getRecordingResumeProof()).toEqual({
        ...SESSION,
        tabId,
        controlToken,
      });
      expect(getRecordingControlProof()).toEqual({ tabId, controlToken });
    });

    it("녹음을 끝내면 이어 녹음 증명도 지운다", () => {
      saveRecordingResumeProof(SESSION);

      clearRecordingStartProof();

      expect(getRecordingResumeProof()).toBeNull();
    });

    it("증명이 없으면 이어 받을 수 없다", () => {
      expect(prepareRecordingReconnect()).toBeNull();
    });

    it("이어 받기는 이전 증명을 싣고, 이전 값과 다른 새 증명을 만든다", () => {
      const before = getRecordingStartProof();
      saveRecordingResumeProof(SESSION);

      const proof = prepareRecordingReconnect();

      expect(proof).toMatchObject({
        previousTabId: before.tabId,
        previousControlToken: before.controlToken,
      });
      expect(proof?.tabId).toMatch(UUID_PATTERN);
      expect(proof?.tabId).not.toBe(before.tabId);
      expect(proof?.controlToken).toMatch(CONTROL_TOKEN_PATTERN);
      expect(proof?.controlToken).not.toBe(before.controlToken);
    });

    it("응답을 받기 전의 재시도에는 같은 새 증명을 보낸다", () => {
      saveRecordingResumeProof(SESSION);

      expect(prepareRecordingReconnect()).toEqual(prepareRecordingReconnect());
    });

    it("이어 받은 뒤에는 새 증명으로 제어한다", () => {
      saveRecordingResumeProof(SESSION);
      const proof = prepareRecordingReconnect();

      completeRecordingReconnect();

      expect(getRecordingControlProof()).toEqual({
        tabId: proof?.tabId,
        controlToken: proof?.controlToken,
      });
      expect(getRecordingResumeProof()?.next).toBeUndefined();
    });
  });
});
