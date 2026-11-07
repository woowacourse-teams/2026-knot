import { DialogProvider } from "@provider/context/dialogContext";
import { ToastProvider } from "@provider/context/toastContext";
import { useRecordingStore } from "@store/recordingStore";
import type { Meta, StoryObj } from "@storybook/react-webpack5";
import {
  clearRecordingStartProof,
  getRecordingStartProof,
  saveRecordingResumeProof,
} from "@utils/recordingControlProof";
import { currentRecordingResponse } from "@api/mock/responses/recording";
import { http, HttpResponse } from "msw";
import { MemoryRouter, Route, Routes } from "react-router";
import { spyOn, userEvent, within } from "storybook/test";

import RecorderBar from ".";

/** 녹음이 없는 처음 상태로 되돌려요. 녹음은 전역 저장소에 있어 스토리끼리 이어지기 때문이에요. */
const resetRecording = () => {
  useRecordingStore.getState().discardRecording();
};

/**
 * 마이크 대신 소리 크기를 돌려주는 분석기예요.
 * 스토리에서 실제 마이크 권한을 묻지 않고도 파형이 소리를 따라 움직이는 모습을 보여 줘요.
 */
const createFakeAnalyser = () =>
  ({
    fftSize: 2048,
    getFloatTimeDomainData: (array: Float32Array) => {
      array.fill(Math.random() * 0.6);
    },
  }) as unknown as AnalyserNode;

/** 마이크 권한을 받지 못한 브라우저처럼 마이크 요청을 거절해요. */
const denyMicrophone = () => {
  const spy = spyOn(navigator.mediaDevices, "getUserMedia").mockRejectedValue(
    new DOMException("", "NotAllowedError"),
  );

  return () => spy.mockRestore();
};

/**
 * 녹음 화면 맨 위에 놓이는 녹음 조작 바예요.
 *
 * 왼쪽에 녹음 상태·녹음한 시간·파형을, 오른쪽에 「일시정지」(일시정지 중이면 「이어서 녹음」)와 「녹음 끝내기」 버튼을 둬요.
 *
 * **동작 규칙**
 * - 녹음은 하단 독의 마이크로 시작해요. 녹음 화면은 진행 중인 녹음을 보여 주기만 하고, 다른 화면에 다녀와도 이어지던 녹음을 그대로 보여 줘요. 녹음 상태가 전역에 있어 화면을 떠나도 끊기지 않습니다.
 * - 파형은 랜딩 녹음 장면처럼 입자로 그려요. 마이크에 들어오는 소리 크기만큼 입자가 세로로 퍼지고, 새 소리가 오른쪽 끝에서 들어와 왼쪽으로 흘러가요. 끝의 몇 개는 아직 들어오지 않은 소리 자리라 흐리게 둬요. 장식이라 화면 낭독기에서는 읽지 않아요.
 * - 「녹음 끝내기」를 누르면 바로 끝내지 않고 「녹음을 끝낼까요?」 모달로 한 번 더 물어요. 「계속 녹음」을 누르거나 바깥·ESC를 누르면 모달만 닫히고 녹음은 이어져요.
 * - 모달에서 「녹음 끝내기」를 고르면 녹음을 끝내고, 마이크를 끈 뒤 녹음 파일을 올리고 그 녹음의 문서 정리 화면으로 가요. 다시 시도해도 올리지 못했으면 「녹음 파일을 올리지 못해 문서로 정리하지 못했어요」 주의 토스트와 함께 워크스페이스 홈으로 나가요.
 * - 끝내기 요청이 실패하면 녹음을 그대로 이어 가고 「녹음을 끝내지 못했어요」 오류 토스트로 알려요.
 * - 「이어서 녹음」 요청이 실패하면 일시정지로 남고 「이어서 녹음하지 못했어요」 오류 토스트로 알려요.
 * - 녹음 중에 마이크가 빠지면 저절로 일시정지해요. 「이어서 녹음」을 누르면 마이크를 다시 받고, 받지 못하면 모달로 알리고 일시정지를 유지해요.
 * - 녹음을 시작한 사람 혼자 쓰는 화면이라 권한에 따른 구분은 없어요.
 * - 시간이 한 시간을 넘으면 `분:초`에서 `시:분:초`로 바뀌어요. 시간이 길어져도 상태 글자 칸은 폭이 고정이라 옆 글자가 밀리지 않아요.
 * - 바의 폭이 좁아지면 파형이 끝부터 잘려요.
 */
const meta = {
  title: "Recording/RecorderBar",
  component: RecorderBar,
  parameters: {
    layout: "padded",
    design: [
      {
        name: "Recorder/Bar",
        type: "figma",
        url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=1705-3280",
      },
      {
        name: "녹음 종료/종료 확인",
        type: "figma",
        url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=2017-35790",
      },
    ],
  },
  beforeEach: resetRecording,
  decorators: [
    (Story) => (
      <DialogProvider>
        {/* 끝내기와 이어서 녹음의 결과로 화면을 옮기거나 토스트를 띄워요. 현재 녹음 조회는 주소의 워크스페이스를 읽어요 */}
        <MemoryRouter initialEntries={["/workspace/1/recording"]}>
          <Routes>
            <Route
              path="/workspace/:workspaceId/*"
              element={
                <ToastProvider>
                  <Story />
                </ToastProvider>
              }
            />
          </Routes>
        </MemoryRouter>
      </DialogProvider>
    ),
  ],
} satisfies Meta<typeof RecorderBar>;

export default meta;

type Story = StoryObj<typeof meta>;

/** 독에서 시작한 녹음이 이어지는 상태예요. 시간이 1초마다 늘어나고, 파형이 소리를 따라 움직여요. */
export const Default: Story = {
  beforeEach: () => {
    useRecordingStore.setState({
      status: "recording",
      accumulatedMs: 0,
      resumedAt: Date.now(),
      analyser: createFakeAnalyser(),
    });
  },
};

/**
 * 녹음하던 탭을 새로고침한 상태예요. 소리는 멈춰 있고 서버의 시간이 계속 흘러요. 「이어서 녹음」을 누르면 같은 녹음이 이어져요.
 * 예: 실수로 새로고침한 사용자
 */
export const Reloaded: Story = {
  parameters: {
    design: {
      type: "figma",
      url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=6061-42635",
    },
  },
  beforeEach: () => {
    getRecordingStartProof();
    saveRecordingResumeProof({
      workspaceId: 1,
      recordingId: currentRecordingResponse.recordingId,
      audioMode: "PCM16_CHUNKS_V1",
    });

    return clearRecordingStartProof;
  },
};

/**
 * 녹음을 잠시 멈춘 상태예요. 상태 글자와 점이 회색이 되고, 파형이 모두 흐려지며, 버튼이 「이어서 녹음」으로 바뀌어요.
 * 예: 회의 중 쉬는 시간
 */
export const Paused: Story = {
  parameters: {
    design: {
      type: "figma",
      url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=1746-10197",
    },
  },
  beforeEach: () => {
    useRecordingStore.setState({
      status: "paused",
      accumulatedMs: (12 * 60 + 48) * 1000,
      resumedAt: null,
    });
  },
};

/** 한 시간을 넘긴 녹음이에요. 시간이 `시:분:초`로 바뀌어도 상태 글자는 제자리에 있어요. 예: 긴 정기 회의 */
export const OverAnHour: Story = {
  beforeEach: () => {
    useRecordingStore.setState({
      status: "recording",
      accumulatedMs: (60 * 60 + 2 * 60 + 3) * 1000,
      resumedAt: Date.now(),
    });
  },
};

/**
 * 「이어서 녹음」을 눌렀지만 마이크를 받지 못한 상태예요. 권한을 허용하는 방법을 알림으로 알리고 녹음은 일시정지로 남아요.
 * 「확인」은 알림만 닫고, 마이크는 「이어서 녹음」을 다시 누를 때 다시 받아요.
 * 예: 녹음 중 마이크가 빠진 뒤 주소창에서 마이크 권한을 꺼 둔 경우
 */
export const MicrophoneUnavailable: Story = {
  parameters: {
    design: {
      type: "figma",
      url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=2017-35624",
    },
  },
  beforeEach: () => {
    useRecordingStore.setState({
      status: "paused",
      accumulatedMs: (12 * 60 + 48) * 1000,
      resumedAt: null,
    });

    return denyMicrophone();
  },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);

    await userEvent.click(canvas.getByRole("button", { name: "이어서 녹음" }));
  },
};

/**
 * 모달에서 「녹음 끝내기」를 골랐지만 끝내기 요청이 실패한 상태예요. 녹음은 그대로 이어지고 오류 토스트로 알려요(ERR-21).
 * 「녹음 끝내기」를 다시 누르면 처음부터 다시 끝내요.
 * 예: 끝내기를 누른 순간 서버가 잠깐 응답하지 못한 경우
 */
export const EndFailed: Story = {
  parameters: {
    design: {
      type: "figma",
      url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=3812-892",
    },
    msw: {
      handlers: {
        endRecording: http.post(
          "*/api/v1/workspaces/:workspaceId/recordings/:recordingId/end",
          () => new HttpResponse(null, { status: 500 }),
        ),
      },
    },
  },
  beforeEach: () => {
    // 끝내기 요청에는 이 탭의 녹음 세션과 최초 탭 증명이 있어야 해요
    getRecordingStartProof();
    useRecordingStore.setState({
      status: "recording",
      session: {
        workspaceId: 1,
        recordingId: 10,
        audioMode: "FINAL_WEBM_V1",
      },
      accumulatedMs: 0,
      resumedAt: Date.now(),
      analyser: createFakeAnalyser(),
    });

    return clearRecordingStartProof;
  },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);

    await userEvent.click(canvas.getByRole("button", { name: "녹음 끝내기" }));
    const dialog = within(
      await canvas.findByRole("dialog", { name: "녹음을 끝낼까요?" }),
    );
    await userEvent.click(dialog.getByRole("button", { name: "녹음 끝내기" }));
  },
};
