import { DialogProvider } from "@provider/context/dialogContext";
import { ToastProvider } from "@provider/context/toastContext";
import { useRecordingStore } from "@store/recordingStore";
import type { Meta, StoryObj } from "@storybook/react-webpack5";
import { MemoryRouter } from "react-router";
import { spyOn, userEvent, within } from "storybook/test";

import MeetingAudioOption from ".";
import { MEETING_AUDIO_COPY } from "./constants/meetingAudioCopy";

const CHROME_UA =
  "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/155.0.0.0 Safari/537.36";
const SAFARI_UA =
  "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.6 Safari/605.1.15";
const ANDROID_CHROME_UA =
  "Mozilla/5.0 (Linux; Android 16; Pixel 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/155.0.0.0 Mobile Safari/537.36";

/** 스토리를 여는 브라우저와 상관없이 정한 브라우저처럼 보이게 해요 */
const pretendBrowser = (userAgent: string) => {
  const spy = spyOn(navigator, "userAgent", "get").mockReturnValue(userAgent);

  return () => spy.mockRestore();
};

/**
 * 녹음 화면의 「화상 회의 상대방 목소리」 줄이에요. 녹음 조작 바와 녹음 카드 사이에 한 줄로 놓여요.
 *
 * **동작 규칙**
 * - 녹음은 독 마이크로 바로 시작해 지금 나누는 대화만 녹음해요. 화상 회의(Zoom, Google Meet 등)의 상대방 목소리까지 담으려면 녹음 중에 이 줄의 「함께 녹음하기」를 눌러요. 시작 전에 고르게 하면 독 마이크를 누르면 바로 시작하는 흐름이 바뀌어서 녹음 중에 골라요.
 * - 누르면 연결 안내를 먼저 보여 줘요. 화상 회의를 어디서 열었는지(브라우저에서, 설치한 앱에서) 고르면 공유 창에서 누를 곳과 켤 오디오 공유를 예시 그림과 함께 짚어 주고, 녹음 고지를 함께 보여 줘요. 「공유할 화면 고르기」를 누르면 브라우저의 공유 창이 열려요.
 * - 공유 창에서 고르는 동안은 버튼이 로딩으로 바뀌어요. 소리가 있는 탭이나 화면을 고르면 그때부터 지금 대화와 상대방 목소리를 섞어 같은 녹음에 이어 담고, 버튼이 사라져요. 연결된 순간 「이어폰을 끼면 더 깨끗하게 녹음돼요」 토스트를 띄워요. 누른 직후엔 안내 모달과 공유 창이 화면을 덮어서 연결된 뒤에 띄워요. 일시정지하면 두 소리 모두 일시정지예요.
 * - 공유 창을 닫으면 아무 알림 없이 처음 모습으로 돌아가요. 소리 없이 공유했거나 함께 녹음하다 브라우저의 「공유 중지」를 누르면 녹음을 끝내지 않고 지금 대화만 이어 녹음하며, 토스트 대신 줄 안에 알림과 「다시 연결하기」를 남겨요. 뒤에 있는 탭에서도 사라지지 않게 하려는 거예요.
 * - 컴퓨터의 Chrome, Edge 같은 Chrome 계열 브라우저만 상대방 목소리를 줘요. Safari, Firefox와 모바일에서는 지원하지 않는다고만 알리고 마이크 녹음은 그대로예요.
 */
const meta = {
  title: "Recording/MeetingAudioOption",
  component: MeetingAudioOption,
  parameters: {
    layout: "padded",
    design: [
      {
        name: "녹음 · 온라인 회의 소리도 함께 녹음",
        type: "figma",
        url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=5403-3372",
      },
      {
        name: "녹음/화상 회의 · 연결 직후 (이어폰 토스트)",
        type: "figma",
        url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=5466-65312",
      },
    ],
  },
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
      <MemoryRouter>
        <ToastProvider>
          <DialogProvider>
            <Story />
          </DialogProvider>
        </ToastProvider>
      </MemoryRouter>
    ),
  ],
} satisfies Meta<typeof MeetingAudioOption>;

export default meta;

type Story = StoryObj<typeof meta>;

/** Chrome 계열에서 마이크만 녹음하는 중이에요. 독 마이크로 녹음을 막 시작한 녹음 화면의 모습이에요. */
export const Default: Story = {
  parameters: {
    design: {
      type: "figma",
      url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=5407-3453",
    },
  },
};

/**
 * 「함께 녹음하기」를 누른 직후예요. 공유 창보다 먼저 「브라우저에서」 기준의 연결 안내를 보여 줘요.
 * 「공유할 화면 고르기」를 누르면 실제 브라우저의 공유 창이 열려요.
 */
export const ConnectDialog: Story = {
  parameters: {
    design: {
      type: "figma",
      url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=5407-3542",
    },
  },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);

    await userEvent.click(
      canvas.getByRole("button", { name: MEETING_AUDIO_COPY.OPTION }),
    );
  },
};

/** 연결 안내에서 「설치한 앱에서」를 골랐어요. Zoom, Teams, Discord 앱처럼 브라우저 밖의 회의는 전체 화면과 시스템 오디오를 공유해요. */
export const ConnectDialogMeetingApp: Story = {
  parameters: {
    design: {
      type: "figma",
      url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=5407-3741",
    },
  },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);

    await userEvent.click(
      canvas.getByRole("button", { name: MEETING_AUDIO_COPY.OPTION }),
    );
    // 모달은 패널 밖(body)에 그려져요
    await userEvent.click(
      within(document.body).getByRole("radio", {
        name: new RegExp(MEETING_AUDIO_COPY.LOCATIONS.meetingApp.LABEL),
      }),
    );
  },
};

/** 공유 창이 열려 회의 화면을 고르는 중이에요. 고르거나 닫을 때까지 버튼이 로딩으로 바뀌어요. */
export const Connecting: Story = {
  parameters: {
    design: {
      type: "figma",
      url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=5407-3935",
    },
  },
  beforeEach: () => {
    const spy = spyOn(
      navigator.mediaDevices,
      "getDisplayMedia",
    ).mockReturnValue(new Promise(() => {}));

    return () => spy.mockRestore();
  },
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);

    await userEvent.click(
      canvas.getByRole("button", { name: MEETING_AUDIO_COPY.OPTION }),
    );
    await userEvent.click(
      within(document.body).getByRole("button", {
        name: MEETING_AUDIO_COPY.DIALOG.CONFIRM,
      }),
    );
  },
};

/** 지금 대화와 화상 회의 상대방 목소리를 함께 녹음하는 중이에요. 「공유 중지」로 멈출 수 있다고 알려 줘요. */
export const Together: Story = {
  parameters: {
    design: {
      type: "figma",
      url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=5407-4024",
    },
  },
  beforeEach: () => {
    useRecordingStore.setState({ meetingAudioStatus: "on" });
  },
};

/** 함께 녹음하다 일시정지했어요. 두 소리 모두 일시정지예요. */
export const TogetherPaused: Story = {
  parameters: {
    design: {
      type: "figma",
      url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=5407-4110",
    },
  },
  beforeEach: () => {
    useRecordingStore.setState({
      status: "paused",
      resumedAt: null,
      meetingAudioStatus: "on",
    });
  },
};

/** 공유는 했지만 소리가 오지 않았어요. 지금 대화만 녹음하며 알려요. 예: 탭을 공유하면서 「탭 오디오도 공유」를 끈 경우 */
export const NoAudio: Story = {
  parameters: {
    design: {
      type: "figma",
      url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=5407-4239",
    },
  },
  beforeEach: () => {
    useRecordingStore.setState({ meetingAudioStatus: "noAudio" });
  },
};

/** 함께 녹음하다 공유가 멈췄어요. 녹음은 지금 대화만으로 이어져요. 예: 브라우저의 「공유 중지」를 누르거나 회의 탭을 닫은 경우 */
export const Stopped: Story = {
  parameters: {
    design: {
      type: "figma",
      url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=5407-4328",
    },
  },
  beforeEach: () => {
    useRecordingStore.setState({ meetingAudioStatus: "stopped" });
  },
};

/** 상대방 목소리를 받을 수 없는 컴퓨터 브라우저예요. 「지원 안 함」과 다음 녹음은 Chrome이나 Edge에서 하라는 안내만 보여요. 예: Safari, Firefox */
export const Unsupported: Story = {
  beforeEach: () => pretendBrowser(SAFARI_UA),
  parameters: {
    design: {
      type: "figma",
      url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=5407-4417",
    },
  },
};

/** 휴대폰이에요. 공유 창이 없어 상대방 목소리는 녹음할 수 없고, 컴퓨터의 Chrome, Edge에서만 된다고 알려요. */
export const Mobile: Story = {
  beforeEach: () => pretendBrowser(ANDROID_CHROME_UA),
  globals: { viewport: { value: "mobile390", isRotated: false } },
  parameters: {
    design: {
      type: "figma",
      url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=5407-4503",
    },
  },
};
