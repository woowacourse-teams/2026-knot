import { DialogProvider } from "@provider/context/dialogContext";
import { useRecordingStore } from "@store/recordingStore";
import type { Meta, StoryObj } from "@storybook/react-webpack5";
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
 * - 파형은 마이크에 들어오는 소리 크기를 막대 높이로 그리고, 새 소리가 오른쪽 끝에서 들어와 왼쪽으로 흘러가요. 끝의 몇 개는 아직 들어오지 않은 소리 자리라 흐리게 둬요. 장식이라 화면 낭독기에서는 읽지 않아요.
 * - 「녹음 끝내기」를 누르면 바로 끝내지 않고 「녹음을 끝낼까요?」 모달로 한 번 더 물어요. 「계속 녹음」을 누르거나 바깥·ESC를 누르면 모달만 닫히고 녹음은 이어져요.
 * - 모달에서 「녹음 끝내기」를 고르면 녹음을 끝내고, 마이크를 끈 뒤 녹음 파일을 올리고 워크스페이스 홈으로 나가요.
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
        <Story />
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
 * 「이어서 녹음」을 눌렀지만 마이크를 받지 못한 상태예요. 모달로 알리고 녹음은 일시정지로 남아요.
 * 「다시 시도」는 마이크를 다시 받고, 「닫기」는 모달만 닫아요.
 * 예: 녹음 중 마이크가 빠진 뒤 주소창에서 마이크 권한을 꺼 둔 경우
 */
export const MicrophoneUnavailable: Story = {
  parameters: {
    design: {
      type: "figma",
      url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=2167-24736",
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
