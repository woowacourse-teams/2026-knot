import { meResponse } from "@api/mock/responses/auth";
import {
  getIsOpenLastTimeKey,
  useTranscriptPanelStore,
} from "@store/transcriptPanelStore";
import type { Meta, StoryObj } from "@storybook/react-webpack5";
import { MemoryRouter } from "react-router";

import TranscriptToggle from ".";

/** 원문을 여닫은 적이 없는 처음 상태로 되돌려요. 열림 여부는 전역 저장소와 브라우저에 남아 스토리끼리 이어지기 때문이에요. */
const resetTranscriptPanel = () => {
  useTranscriptPanelStore.setState({
    isOpenThisVisit: null,
    recordingDocumentChoice: null,
  });
  localStorage.removeItem(getIsOpenLastTimeKey(meResponse.memberId));
};

/**
 * 문서를 보는 화면의 GNB 오른쪽에서 원문 패널을 여닫는 버튼이에요. 홈 · 탐색 화면에는 놓지 않아요.
 * 패널은 이 버튼이 그리지 않고 문서 화면 오른쪽 자리에 따로 놓여서, 이 스토리에는 버튼만 있어요.
 * 스토리는 열 때마다 원문을 여닫은 적이 없는 처음 상태로 되돌려요. 그래서 스토리북에서는 눌러 둔 상태가 새로고침 뒤에 이어지지 않아요.
 *
 * **동작 규칙**
 * - 원문이 열려 있으면 채워진 모양이고, 누르면 닫아요. 닫혀 있으면 흰 모양이고, 누르면 열어요.
 * - 문서를 처음 보는 회원에게는 원문을 열어 둬요(DOC-R3).
 * - 그다음부터는 회원이 마지막에 둔 상태로 시작해요. 문서마다가 아니라 회원마다 하나라서, 한 문서에서 닫으면 다른 문서도 닫힌 채로 열려요. 새로고침하거나 다시 들어와도 이어져요.
 * - 마지막 상태는 회원마다 브라우저에 남겨요. 서버에는 저장하지 않아 다른 브라우저에서는 이어지지 않아요. 같은 브라우저를 쓰는 다른 회원이 둔 상태는 따르지 않아요.
 * - 회원 정보를 받기 전에는 누가 둔 상태인지 알 수 없어 닫힌 모양이에요. 열린 모양으로 두면 닫아 둔 회원에게 패널이 잠깐 열렸다 닫히고, 원문 요청도 한 번 나가기 때문이에요.
 * - 회원 정보를 받지 못해도 닫힌 모양이에요. 이때나 받기 전에 누르면 열리지만, 누가 둔 상태인지 몰라 다음번까지 남기지는 않아요.
 * - 브라우저에 남길 수 없으면(예: 브라우저 설정에서 사이트 데이터 저장을 막아 둔 경우) 처음 보는 회원처럼 열린 채로 시작하고, 누른 상태는 이번 방문에만 적용해요.
 * - 녹음 문서 화면에서는 열 때마다 원문을 열어 둬요(STT-R39). 녹음 문서에서 여닫은 상태는 그 화면에만 적용하고, 문서 보기의 다음번 상태로 남기지 않아요.
 * - 이름(「원문 보기」·「원문 닫기」)은 화면에 쓰지 않고, 열려 있는지와 함께 스크린리더에만 알려요. 툴팁은 두지 않아요.
 */
const meta = {
  title: "Document/TranscriptToggle",
  component: TranscriptToggle,
  parameters: {
    design: {
      type: "figma",
      url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=2017-36143",
    },
  },
  beforeEach: resetTranscriptPanel,
  // 문서 보기와 녹음 문서가 여닫는 규칙이 달라 주소를 읽어요. 기본은 문서 보기 화면이에요
  decorators: [
    (Story, { parameters }) => (
      <MemoryRouter
        initialEntries={[parameters.pathname ?? "/workspace/1/documents/101"]}
      >
        <Story />
      </MemoryRouter>
    ),
  ],
} satisfies Meta<typeof TranscriptToggle>;

export default meta;

type Story = StoryObj<typeof meta>;

/** 문서를 처음 보는 회원이 문서를 열었을 때예요. 원문이 열려 있어 채워진 모양이고, 누르면 닫혀 흰 모양이 돼요. */
export const Default: Story = {};

/** 원문을 닫아 둔 회원이 문서를 열었을 때예요. 흰 모양이고, 누르면 원문이 열려 채워진 모양이 돼요. 회원 정보를 받기 전이나 받지 못했을 때도 이 모양이에요. 예: 앞 문서에서 원문을 닫고 다음 문서로 넘어온 회원 */
export const Closed: Story = {
  parameters: {
    design: {
      type: "figma",
      url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=2017-36100",
    },
    // 문서 페이지는 스토리를 한 화면에 함께 그리는데 전역 저장소는 화면에 하나뿐이라, 따로 그려 Default와 섞이지 않게 해요
    docs: { story: { inline: false, iframeHeight: 80 } },
  },
  // 브라우저에 남긴 값은 따로 그린 화면끼리도 함께 써서, 이번 방문의 값으로 닫아요
  beforeEach: () => {
    useTranscriptPanelStore.setState({ isOpenThisVisit: false });
  },
};

/** 녹음 문서를 열었을 때예요. 문서 보기에서 원문을 닫아 둔 회원에게도 원문이 열려 있어 채워진 모양이에요(STT-R39). 예: 문서 탭 「녹음별」 탭에서 녹음 행을 눌렀을 때 */
export const RecordingDocument: Story = {
  parameters: {
    pathname: "/workspace/1/recordings/42/document",
    design: {
      type: "figma",
      url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=5373-3187",
    },
    docs: { story: { inline: false, iframeHeight: 80 } },
  },
  beforeEach: () => {
    useTranscriptPanelStore.setState({ isOpenThisVisit: false });
  },
};
