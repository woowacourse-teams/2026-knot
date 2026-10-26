import { DialogProvider } from "@provider/context/dialogContext";
import { useRecordingStore } from "@store/recordingStore";
import type { Meta, StoryObj } from "@storybook/react-webpack5";
import { spyOn, userEvent, within } from "storybook/test";

import MeetingAudioOption from ".";
import { MEETING_AUDIO_COPY } from "./constants/meetingAudioCopy";

const CHROME_UA =
  "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/155.0.0.0 Safari/537.36";
const SAFARI_UA =
  "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.6 Safari/605.1.15";

/** 스토리를 여는 브라우저와 상관없이 정한 브라우저처럼 보이게 해요 */
const pretendBrowser = (userAgent: string) => {
  const spy = spyOn(navigator, "userAgent", "get").mockReturnValue(userAgent);

  return () => spy.mockRestore();
};

/**
 * 녹음 화면에서 회의 소리도 함께 녹음하는 선택지예요. 녹음 조작 바와 녹음 카드 사이에 놓여요.
 *
 * **동작 규칙**
 * - 녹음은 독 마이크로 바로 시작해 마이크 소리만 녹음해요. 온라인 회의(Zoom, Discord, Google Meet 등)의 상대방 목소리까지 담으려면 녹음 중에 이 선택지를 골라요. 시작 전에 고르게 하면 독 마이크를 누르면 바로 시작하는 흐름이 바뀌어서 녹음 중에 골라요.
 * - 누르면 상대방 목소리도 녹음된다는 고지와 이어폰 권장을 먼저 보여 주고, 확인하면 브라우저의 공유 창을 열어요. 스피커로 들으면 회의 소리가 마이크로 다시 들어가 겹치기 때문이에요.
 * - 공유 창에서 소리가 있는 탭, 화면, 창을 고르면 그때부터 마이크와 회의 소리를 섞어 같은 녹음에 이어 담아요. 함께 녹음하는 동안에는 선택지가 사라져요. 일시정지, 끝내기, 2시간 상한은 마이크만 녹음할 때와 같아요.
 * - 공유 창을 닫으면 아무 알림 없이 마이크만 녹음해요. 소리 없이 공유했거나 함께 녹음하다 브라우저의 공유 중지를 누르면 녹음을 끝내지 않고 마이크만으로 이어 가며 알리고, 선택지를 다시 보여 줘요.
 * - Chrome, Edge 같은 Chrome 계열 브라우저만 회의 소리를 줘요. Safari 등에서는 선택지 대신 안내만 보이고 마이크 녹음은 그대로예요.
 * - 디자인 전이라 자리와 문구는 임시예요.
 */
const meta = {
  title: "Recording/MeetingAudioOption",
  component: MeetingAudioOption,
  parameters: { layout: "padded" },
  beforeEach: () => {
    // 녹음은 전역 저장소에 있어 스토리끼리 이어지므로 처음 상태에서 다시 시작해요
    useRecordingStore.getState().discardRecording();
    useRecordingStore.setState({
      status: "recording",
      accumulatedMs: 0,
      resumedAt: Date.now(),
    });

    return pretendBrowser(CHROME_UA);
  },
  decorators: [
    (Story) => (
      <DialogProvider>
        <Story />
      </DialogProvider>
    ),
  ],
} satisfies Meta<typeof MeetingAudioOption>;

export default meta;

type Story = StoryObj<typeof meta>;

/** Chrome 계열에서 마이크만 녹음하는 중이에요. 독 마이크로 녹음을 막 시작한 녹음 화면의 모습이에요. */
export const Default: Story = {};

/**
 * 선택지를 누른 직후예요. 공유 창보다 먼저 녹음 고지와 이어폰 권장을 보여 줘요.
 * 「공유할 화면 고르기」를 누르면 실제 브라우저의 공유 창이 열려요.
 */
export const Notice: Story = {
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);

    await userEvent.click(
      canvas.getByRole("button", { name: MEETING_AUDIO_COPY.OPTION }),
    );
  },
};

/** 공유는 했지만 소리가 오지 않았어요. 마이크만 녹음하며 알려요. 예: 탭을 공유하면서 「탭 오디오도 공유」를 끈 경우 */
export const NoAudio: Story = {
  beforeEach: () => {
    useRecordingStore.setState({ meetingAudioStatus: "noAudio" });
  },
};

/** 함께 녹음하다 공유가 멈췄어요. 녹음은 마이크만으로 이어져요. 예: 브라우저의 「공유 중지」를 누르거나 회의 탭을 닫은 경우 */
export const Stopped: Story = {
  beforeEach: () => {
    useRecordingStore.setState({ meetingAudioStatus: "stopped" });
  },
};

/** 회의 소리를 받을 수 없는 브라우저예요. 선택지 대신 안내만 보여요. 예: Safari, Firefox */
export const Unsupported: Story = {
  beforeEach: () => pretendBrowser(SAFARI_UA),
};
