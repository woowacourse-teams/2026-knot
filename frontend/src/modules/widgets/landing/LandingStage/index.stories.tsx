import { PATH_ROUTE } from "@routes/PATH_ROUTE";
import type { Meta, StoryObj } from "@storybook/react-webpack5";
import { MemoryRouter, Route, Routes } from "react-router";

import LandingStage from ".";

/**
 * 공개 랜딩(`/`)의 9장면 무대예요. 소개 → 문제 → 녹음 → 정리 → 확인 → 탐색 → 녹음 기술 → 탐색 기술 → 시작 순서로 넘어가요.
 * 기술 소개는 사용자 가치, 처리 흐름, 실제 복구 사례와 조건을 붙인 응답 시간 비교를 보여 줘요.
 * 좁은 화면은 기술 소개 안을 스크롤해서 읽고, 끝에서 다음 장면으로 이동해요. 동작 줄이기를 켜도 정보를 모두 읽을 수 있어요.
 *
 * **시작 자리 (AUTH-R11)**
 * - 첫 장면(소개)과 마지막 장면(시작)에는 로그인 수단 버튼을 두지 않고 「시작하기」 버튼 하나만 둬요. 버튼 아래에는 안내 문구를 두지 않아요.
 * - 누르면 로그인 화면(`/login`)으로 가고, 그 화면에서 GitHub, Google, 카카오 중 하나를 골라요. 「처음 가입한 방법으로 로그인해야 같은 계정이에요」 안내도 로그인 화면에만 있어요.
 * - 페이지를 옮기는 동작이라 링크예요. 모양은 `Shared/Button`의 lg filled(높이 56, 최대 너비 320px)와 같고, 키보드로 가면 포커스 테두리가 보여요.
 * - 보이지 않는 장면의 링크는 눌리지 않고 스크린리더에도 읽히지 않아요.
 */
const meta = {
  title: "Landing/LandingStage",
  component: LandingStage,
  parameters: {
    layout: "fullscreen",
    design: [
      {
        name: "LAND-01 · 소개",
        type: "figma",
        url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=4308-42635",
      },
      {
        name: "LAND · 녹음 기술",
        type: "figma",
        url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=5781-591",
      },
      {
        name: "LAND · 탐색 기술",
        type: "figma",
        url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=5781-592",
      },
      {
        name: "LAND-07 · 시작",
        type: "figma",
        url: "https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=4308-42641",
      },
    ],
  },
  decorators: [
    // 「시작하기」를 눌렀을 때 어디로 가는지 보이도록 로그인 화면 자리를 둬요
    (Story) => (
      <MemoryRouter initialEntries={[PATH_ROUTE.HOME]}>
        <Routes>
          <Route path={PATH_ROUTE.HOME} element={<Story />} />
          <Route path={PATH_ROUTE.LOGIN} element={<p>로그인 화면</p>} />
        </Routes>
      </MemoryRouter>
    ),
  ],
} satisfies Meta<typeof LandingStage>;

export default meta;

type Story = StoryObj<typeof meta>;

/** 랜딩에 처음 들어온 모습이에요. 첫 장면 아래에 「시작하기」가 있어요. */
export const Default: Story = {};
