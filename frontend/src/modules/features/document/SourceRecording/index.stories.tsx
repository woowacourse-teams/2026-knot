import { RECORDING_DOCUMENT_API_PATH } from "@api/fetch/api/v1/workspaces/[workspaceId]/recordings/[recordingId]/document";
import { documentDetailsResponse } from "@api/mock/responses/document";
import type { Meta, StoryObj } from "@storybook/react-webpack5";
import { delay, http, HttpResponse } from "msw";
import { MemoryRouter } from "react-router";

import SourceRecording from ".";

const WORKSPACE_ID = 1;
const RECORDING_ID = documentDetailsResponse[0].recordingSessionId;
const RECORDING_DOCUMENT_REQUEST = `*${RECORDING_DOCUMENT_API_PATH(WORKSPACE_ID, RECORDING_ID)}`;

// 응답을 바꾸는 스토리만 따로 그려 문서 페이지에서 다른 스토리의 응답과 섞이지 않게 해요
const ISOLATED_DOCS = { story: { inline: false, iframeHeight: 200 } };

/**
 * 문서 보기의 「이 문서가 나온 녹음」이에요. 문서 본문 아래, 확인 버튼 줄 위에 놓여 이 문서를 만든 녹음을 카드 하나로 보여 줘요.
 * 카드를 누르면 그 녹음의 녹음 문서(회의 전체 요약, 원문, 녹음 음성)로 가요.
 *
 * **동작 규칙**
 * - 카드는 녹음 문서의 「이 회의에서 나온 문서」 카드와 같은 모양이에요. 문서와 녹음 사이를 양쪽으로 오가는 길이 같은 모양으로 보이게 했어요.
 * - 녹음 제목은 녹음 문서와 같은 제목이에요. AI 제목이 없는 녹음은 서버가 「OO 님의 녹음」으로 채워 줘요.
 * - 녹음 제목을 받는 동안은 제목 자리만 회색으로 채워요. 받지 못하면 「녹음 보기」로 보여 줘요. 어느 때든 누르면 녹음 문서로 가요.
 * - 녹음 직후 문서 화면을 포함해 어느 입구로 연 문서 보기에서나 같아요.
 * - 넓은 화면에서는 「이 회의에서 나온 문서」의 두 열 중 한 칸 폭이고, 좁은 화면에서는 본문 폭을 채워요.
 */
const meta = {
  title: "Document/SourceRecording",
  component: SourceRecording,
  args: { workspaceId: WORKSPACE_ID, recordingId: RECORDING_ID },
  parameters: {
    design: [
      {
        name: "문서/확인한 사람 보기",
        type: "figma",
        url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=1535-2817",
      },
      {
        name: "문서/이 문서가 나온 녹음 · 카드 상태",
        type: "figma",
        url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=5584-72163",
      },
    ],
  },
  decorators: [
    (Story) => (
      <MemoryRouter>
        {/* 문서 보기처럼 720px 열 안에 놓아요 */}
        <div style={{ maxWidth: "45rem" }}>
          <Story />
        </div>
      </MemoryRouter>
    ),
  ],
} satisfies Meta<typeof SourceRecording>;

export default meta;

type Story = StoryObj<typeof meta>;

/** 녹음 제목을 받은 기본 모양이에요 */
export const Default: Story = {};

/** 녹음 제목을 받는 동안이에요. 제목 자리만 회색이고, 눌러도 녹음 문서로 가요 */
export const Loading: Story = {
  parameters: {
    msw: {
      handlers: [
        http.get(RECORDING_DOCUMENT_REQUEST, async () => {
          await delay("infinite");
          return new HttpResponse(null, { status: 500 });
        }),
      ],
    },
    docs: ISOLATED_DOCS,
  },
};

/** 녹음 제목을 받지 못했을 때예요. 「녹음 보기」로 보여 주고, 눌러도 녹음 문서로 가요 */
export const TitleLoadFailed: Story = {
  parameters: {
    msw: {
      handlers: [
        http.get(
          RECORDING_DOCUMENT_REQUEST,
          () => new HttpResponse(null, { status: 500 }),
        ),
      ],
    },
    docs: ISOLATED_DOCS,
  },
};
