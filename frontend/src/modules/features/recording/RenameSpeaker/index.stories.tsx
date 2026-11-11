import { resetRecordingSpeakerMockState } from "@api/mock/state/recordingSpeaker";
import type { Meta, StoryObj } from "@storybook/react-webpack5";
import { http, HttpResponse } from "msw";
import { MemoryRouter } from "react-router";
import RenameSpeaker from ".";
/** 녹음 원문의 참여자 이름을 눌러 같은 화자의 표시 이름을 한 번에 바꿔요. 직접 입력하거나 멤버 닉네임을 복사할 수 있고, 요약과 문서 본문은 바뀌지 않아요. */
const meta = {
  title: "Recording/RenameSpeaker",
  component: RenameSpeaker,
  args: {
    workspaceId: 1,
    recordingId: 101,
    speakerNumber: 1,
    displayName: "참여자 1",
    isInitiallyOpen: true,
  },
  parameters: {
    design: {
      type: "figma",
      url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=6331-43345",
    },
  },
  loaders: [
    () => {
      resetRecordingSpeakerMockState();
      return {};
    },
  ],
  decorators: [
    (Story) => (
      <MemoryRouter>
        <div style={{ minHeight: 700, padding: 32 }}>
          <Story />
        </div>
      </MemoryRouter>
    ),
  ],
} satisfies Meta<typeof RenameSpeaker>;
export default meta;
type Story = StoryObj<typeof meta>;
/** 이름을 누르면 열리는 기본 팝오버예요. 이름 선택 후 바꾸기를 눌러 저장해요. */
export const Default: Story = {};
/** 멤버를 불러오지 못해도 이름을 직접 입력할 수 있어요. 저장 실패 시 입력한 이름을 유지해요. */
export const MemberAndSaveFailure: Story = {
  parameters: {
    docs: { story: { inline: false, iframeHeight: 760 } },
    msw: {
      handlers: [
        http.get(
          "*/api/v1/workspaces/:workspaceId/members",
          () => new HttpResponse(null, { status: 503 }),
        ),
        http.patch(
          "*/api/v1/workspaces/:workspaceId/recordings/:recordingId/speakers/:speakerNumber",
          () =>
            HttpResponse.json(
              { code: "WORKSPACE_ACCESS_DENIED" },
              { status: 403 },
            ),
        ),
      ],
    },
  },
};
