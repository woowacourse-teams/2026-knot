import { afterEach, describe, expect, it } from "vitest";

import {
  forgetUnfinishedRecording,
  getUnfinishedRecording,
  rememberUnfinishedRecording,
} from ".";

const RECORDING = { workspaceId: 1, recordingId: 10 };

describe("unfinishedRecording", () => {
  afterEach(() => localStorage.clear());

  it("남긴 녹음을 돌려준다", () => {
    rememberUnfinishedRecording(RECORDING);

    expect(getUnfinishedRecording()).toEqual(RECORDING);
  });

  it("남긴 녹음이 없으면 null이다", () => {
    expect(getUnfinishedRecording()).toBeNull();
  });

  it("같은 녹음이면 지운다", () => {
    rememberUnfinishedRecording(RECORDING);

    forgetUnfinishedRecording(RECORDING.recordingId);

    expect(getUnfinishedRecording()).toBeNull();
  });

  it("그 사이 새 녹음을 시작했으면 새 녹음의 기록은 둔다", () => {
    rememberUnfinishedRecording(RECORDING);

    forgetUnfinishedRecording(RECORDING.recordingId + 1);

    expect(getUnfinishedRecording()).toEqual(RECORDING);
  });
});
