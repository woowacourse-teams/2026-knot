import { useRecordingStore } from "@store/recordingStore";
import type { Meta, StoryObj } from "@storybook/react-webpack5";
import { useEffect } from "react";

import RecordingConnectionNotice from ".";

const FIGMA_URL =
  "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=6061-42635";

interface NoticeStoryArgs {
  isChunkArchiveFailed: boolean;
  isEnding: boolean;
}

/** 저장소를 녹음 중이고 끊긴 상태로 맞춘 뒤 줄을 그려요 */
function DisconnectedNotice({ isChunkArchiveFailed, isEnding }: NoticeStoryArgs) {
  useEffect(() => {
    useRecordingStore.setState({
      status: "recording",
      isHeartbeatFailing: true,
      isChunkArchiveFailed,
      isEnding,
    });

    return () => useRecordingStore.getState().discardRecording();
  }, [isChunkArchiveFailed, isEnding]);

  return <RecordingConnectionNotice />;
}

const meta = {
  title: "features/recording/RecordingConnectionNotice",
  component: DisconnectedNotice,
  parameters: { design: { type: "figma", url: FIGMA_URL } },
  args: { isChunkArchiveFailed: false, isEnding: false },
} satisfies Meta<typeof DisconnectedNotice>;

export default meta;
type Story = StoryObj<typeof meta>;

/** 신호나 조각이 서버에 닿지 못할 때. 녹음은 계속되고, 돌아오면 끊긴 동안의 소리도 올려요 */
export const Lost: Story = {};

/** 브라우저가 조각 보관을 거절했을 때. 탭을 닫으면 끊긴 동안의 소리를 잃을 수 있어요 */
export const ArchiveFailed: Story = { args: { isChunkArchiveFailed: true } };

/** 끊긴 채 녹음 끝내기를 눌렀을 때. 남은 소리를 올릴 때까지 기다려요 */
export const Ending: Story = { args: { isEnding: true } };
