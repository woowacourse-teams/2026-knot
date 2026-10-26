import styled from "@emotion/styled";
import AppProviders from "@provider/AppProviders";
import { getRouterPath, PATH_ROUTE } from "@routes/PATH_ROUTE";
import type { Meta, StoryObj } from "@storybook/react-webpack5";
import { useEffect, useState } from "react";
import {
  createMemoryRouter,
  Outlet,
  RouterProvider,
  type RouteObject,
} from "react-router";

import WorkspaceLayout from ".";

const WORKSPACE_ID = "1";
const HOME_PATH = getRouterPath({
  routeKey: "WORKSPACE_HOME",
  params: { workspaceId: WORKSPACE_ID },
});
/**
 * 레이아웃째 그리는 스토리는 문서 페이지에서 따로 그려요.
 * 한 페이지에 레이아웃이 둘이면 고정 패널 자리(DOM id)와 화면 아무 데서나 치는 글자를 서로 가져가요.
 */
const ISOLATED_LAYOUT_DOCS = { story: { inline: false, iframeHeight: 760 } };
const REDUCED_MOTION_QUERY = "(prefers-reduced-motion: reduce)";

const Screen = styled.div`
  display: flex;
  justify-content: center;
  min-height: 100%;
  padding: 1.5rem 1.5rem 7rem; /* 24px 24px 112px — 아래는 하단 독 자리 */
`;

const ScreenColumn = styled.div`
  display: flex;
  flex-direction: column;
  gap: 1rem; /* 16px */
  width: 100%;
  max-width: 67.5rem; /* 1080px */
`;

const ScreenTitle = styled.h2`
  color: ${({ theme }) => theme.neutral[800]};
  ${({ theme }) => theme.text.title02};
`;

const ScreenCard = styled.div`
  padding: 1.25rem; /* 20px */
  border-radius: 1.25rem; /* 20px */
  background-color: ${({ theme }) => theme.neutral[0]};
  color: ${({ theme }) => theme.neutral[600]};
  box-shadow: ${({ theme }) => theme.shadow02};
  ${({ theme }) => theme.text.body02};
`;

interface PreviewScreenProps {
  title: string;
  cards: string[];
}

/**
 * 레이아웃 스토리의 본문 자리에 그리는 화면.
 * 실제 화면 대신 제목과 카드 이름만 둬서 본문이 들어오는 모션을 보기 쉽게 해요.
 */
function PreviewScreen({ title, cards }: PreviewScreenProps) {
  return (
    <Screen>
      <ScreenColumn>
        <ScreenTitle>{title}</ScreenTitle>
        {cards.map((card) => (
          <ScreenCard key={card}>{card}</ScreenCard>
        ))}
      </ScreenColumn>
    </Screen>
  );
}

const HOME_SCREEN = (
  <PreviewScreen
    title="홈"
    cards={["녹음 기록", "확인이 필요한 문서", "팀원 초대"]}
  />
);
const CHAT_SCREEN = (
  <PreviewScreen
    title="탐색"
    cards={["무엇이든 물어보세요. 팀의 회의 기록에서 찾아 드려요."]}
  />
);
const DOCUMENTS_SCREEN = (
  <PreviewScreen title="문서" cards={["회의록", "기획", "회고"]} />
);

const LAYOUT_ROUTES: RouteObject[] = [
  {
    handle: { hasDock: true },
    element: <WorkspaceLayout />,
    children: [
      { path: PATH_ROUTE.WORKSPACE_HOME, element: HOME_SCREEN },
      { path: PATH_ROUTE.CHAT, element: CHAT_SCREEN },
      { path: PATH_ROUTE.CHAT_SESSION, element: CHAT_SCREEN },
      { path: PATH_ROUTE.DOCUMENTS, element: DOCUMENTS_SCREEN },
      { path: PATH_ROUTE.DOCUMENT, element: DOCUMENTS_SCREEN },
    ],
  },
];

/** 실제 워크스페이스 레이아웃(GNB, 본문, 독)에 위 화면들을 넣어 그려요 */
function LayoutPreview() {
  const [router] = useState(() =>
    createMemoryRouter(
      [
        {
          element: (
            <AppProviders routes={LAYOUT_ROUTES}>
              <Outlet />
            </AppProviders>
          ),
          children: LAYOUT_ROUTES,
        },
      ],
      { initialEntries: [HOME_PATH] },
    ),
  );

  return (
    <LayoutFrame>
      <RouterProvider router={router} />
    </LayoutFrame>
  );
}

const LayoutFrame = styled.div`
  height: 100vh;
`;

/** 지금 브라우저가 움직임 줄이기 상태인지 화면 구석에 알려 줘요. 설정을 바꾸면 바로 따라 바뀌어요 */
function ReducedMotionStatus() {
  const [isReduced, setIsReduced] = useState(
    () => window.matchMedia(REDUCED_MOTION_QUERY).matches,
  );

  useEffect(() => {
    const query = window.matchMedia(REDUCED_MOTION_QUERY);
    const handleChange = () => setIsReduced(query.matches);

    query.addEventListener("change", handleChange);
    return () => query.removeEventListener("change", handleChange);
  }, []);

  return (
    <StatusBadge role="status">
      {isReduced ? "움직임 줄이기 켜짐" : "움직임 줄이기 꺼짐"}
    </StatusBadge>
  );
}

const StatusBadge = styled.p`
  position: fixed;
  bottom: 1rem; /* 16px */
  left: 1rem;
  padding: 0.5rem 0.75rem; /* 8px 12px */
  border-radius: 0.75rem; /* 12px */
  background-color: ${({ theme }) => theme.neutral[800]};
  color: ${({ theme }) => theme.neutral[0]};
  ${({ theme }) => theme.text.caption02};
`;

/**
 * 워크스페이스 입장 뒤의 홈, 탐색, 문서 화면이 함께 쓰는 레이아웃이에요. 위에 GNB, 가운데 본문, 아래에 독이 있어요.
 *
 * **탭 전환 모션** (정책: Figma UI/UX Policy 「공통 · 탭 전환」, 노션 공통 UI 규칙 「탭 전환 모션」)
 * - GNB 탭 칸을 누르는 동안 살짝 작아지고, 떼면 통 튀어 원래 크기로 돌아와요.
 * - 진한 표시 하나가 이전 탭에서 새 탭으로 미끄러지고, 글자색도 같은 시간에 바뀌어요.
 * - 표시는 미끄러지는 동안 진행 방향으로 살짝 늘어났다가, 새 탭에 도착하며 물방울이 맺히듯 한 번 부풀고 한 번 되돌아 자리잡아요.
 *   늘어남은 가로 1.08배, 세로 0.94배, 부풂은 1.06배까지만이고 미끄러짐과 맺힘이 0.4초 안에 끝나요. 이보다 크면 장난스러워 보여서예요.
 * - 지금 탭을 누르면 표시도 칸과 함께 눌렸다가, 떼면 제자리에서 톡 맺혀요. 화면은 그대로예요.
 * - 표시가 움직이는 중에 다른 탭을 누르면 지금 자리와 모양에서 이어서 새 탭으로 가요. 크기가 튀거나 모션이 쌓이지 않아요.
 * - 본문은 탭 방향(홈, 탐색, 문서 순)에서 8px 안쪽으로 살짝 밀려 들어오며 또렷해져요. 반쯤 보이는 데서 시작해 빈 화면이 보이는 순간이 없어요.
 * - GNB, 독, 양옆 패널은 본문과 함께 움직이지 않아요. 탐색으로 가면 독은 기존 펼침 모션으로 펼쳐져요.
 * - 같은 탭 안에서 옮길 때, 처음 들어올 때, 새로고침할 때는 본문이 움직이지 않아요.
 * - 처음 들어올 때와 창 크기가 바뀔 때는 표시가 늘어나거나 맺히지 않고 제자리에 놓여요.
 * - 운영체제에서 움직임 줄이기를 켜 두면 눌림, 표시 이동과 물방울, 본문 이동 없이 바로 바뀌어요.
 */
const meta = {
  title: "Workspace/WorkspaceLayout",
  component: WorkspaceLayout,
  parameters: {
    layout: "fullscreen",
    design: {
      type: "figma",
      url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=1364-879",
    },
    docs: ISOLATED_LAYOUT_DOCS,
  },
} satisfies Meta<typeof WorkspaceLayout>;

export default meta;

type Story = StoryObj<typeof meta>;

/**
 * 탭을 직접 눌러 전환을 체험할 때 써요. 실제 워크스페이스 레이아웃에 홈, 탐색, 문서 자리 화면을 넣었어요.
 * 표시가 물방울처럼 늘어나며 미끄러져 톡 맺히는 것, 누를 때의 눌림, 본문이 탭 방향에서 들어오는 것, 탐색에서 독이 펼쳐지는 것을 함께 볼 수 있어요.
 * 지금 탭을 눌렀다 떼면 제자리에서 맺히는 것만, 탭을 빠르게 연달아 누르면 모양이 끊기지 않고 이어지는 것을 볼 수 있어요.
 */
export const TabTransitionInLayout: Story = {
  render: () => <LayoutPreview />,
};

/**
 * 움직임 줄이기를 켠 사람에게 어떻게 보이는지 확인할 때 써요.
 * Chrome 개발자 도구의 Rendering 패널에서 `prefers-reduced-motion: reduce`를 고르거나, macOS의 손쉬운 사용에서 동작 줄이기를 켜고 탭을 눌러 보세요.
 * 왼쪽 아래에 지금 설정이 보이고, 켜져 있으면 눌림, 표시 이동과 물방울, 본문 이동 없이 바로 바뀌어요.
 */
export const TabTransitionWithReducedMotion: Story = {
  render: () => (
    <>
      <LayoutPreview />
      <ReducedMotionStatus />
    </>
  ),
};
