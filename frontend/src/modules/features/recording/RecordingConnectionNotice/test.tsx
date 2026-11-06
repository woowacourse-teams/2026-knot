import { ThemeProvider } from "@emotion/react";
import { theme } from "@provider/themeProvider";
import { useRecordingStore } from "@store/recordingStore";
import { act, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it } from "vitest";

import { CONNECTION_NOTICE_COPY } from "./constants/connectionNoticeCopy";

import RecordingConnectionNotice from ".";

const renderNotice = () =>
  render(
    <ThemeProvider theme={theme}>
      <RecordingConnectionNotice />
    </ThemeProvider>,
  );

describe("RecordingConnectionNotice", () => {
  beforeEach(async () => {
    await act(async () => {
      await useRecordingStore.getState().connectMicrophone();
      useRecordingStore
        .getState()
        .startRecording({ workspaceId: 1, recordingId: 10 });
    });
  });

  afterEach(() => useRecordingStore.getState().discardRecording());

  it("연결이 살아 있으면 보이지 않는다", () => {
    renderNotice();

    expect(screen.queryByRole("status")).not.toBeInTheDocument();
  });

  it("신호가 닿지 않으면 끊김 안내를 보이고, 회복되면 없앤다", () => {
    renderNotice();

    act(() => useRecordingStore.getState().setHeartbeatFailing(true));
    expect(
      screen.getByText(CONNECTION_NOTICE_COPY.lost.description),
    ).toBeInTheDocument();

    act(() => useRecordingStore.getState().setHeartbeatFailing(false));
    expect(screen.queryByRole("status")).not.toBeInTheDocument();
  });

  it("조각 전송이 밀려도 끊김 안내를 보인다", () => {
    useRecordingStore.setState({ isChunkTransferStalled: true });
    renderNotice();

    expect(
      screen.getByText(CONNECTION_NOTICE_COPY.lost.title),
    ).toBeInTheDocument();
  });

  it("보관을 거절당했으면 탭을 닫을 때 소리를 잃을 수 있다고 알린다", () => {
    useRecordingStore.setState({
      isHeartbeatFailing: true,
      isChunkArchiveFailed: true,
    });
    renderNotice();

    expect(
      screen.getByText(CONNECTION_NOTICE_COPY.archiveFailed.description),
    ).toBeInTheDocument();
  });

  it("끊긴 채 끝내는 중이면 기다린다고 알린다", () => {
    useRecordingStore.setState({ isHeartbeatFailing: true, isEnding: true });
    renderNotice();

    expect(
      screen.getByText(CONNECTION_NOTICE_COPY.ending.title),
    ).toBeInTheDocument();
  });
});
