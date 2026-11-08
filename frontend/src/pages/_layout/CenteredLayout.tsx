import styled from "@emotion/styled";
import Spacing from "@primitives/layout/Spacing";
import { Suspense } from "react";
import { Outlet } from "react-router";

import LogoIcon from "@/assets/logos/logo.svg";

/**
 * knot 로고 + 중앙 카드 레이아웃
 *
 * 워크스페이스 진입 전 플로우(로그인, 온보딩, 워크스페이스 생성/참여, 팀원 초대, 초대 에러)가 공유한다.
 * 연한 배경 위에 로고를 띄우고 그 아래 화면 콘텐츠(카드)를 가로 가운데에 놓는다.
 *
 * 로고 자리의 위쪽은 화면 높이의 28%에 고정한다(AUTH-R6). 세로 가운데에 놓으면 카드 높이가 바뀔 때마다
 * 로고와 카드가 함께 움직이기 때문이다. 카드는 아래로만 늘어나고, 화면보다 길어지면 스크롤된다.
 *
 * @see https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=1788-4726 v2 온보딩 · 로그인
 * @see https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=600-10188 워크스페이스 생성 및 참여
 */
export default function CenteredLayout() {
  return (
    <Container>
      <Logo />
      <Spacing size={2.5} /> {/* 40px */}
      {/* 페이지 코드를 받는 동안 로고는 그대로 두고 카드 자리만 비워요 */}
      <Suspense fallback={null}>
        <Outlet />
      </Suspense>
    </Container>
  );
}

const Container = styled.main`
  display: flex;
  flex-direction: column;
  align-items: center;
  min-height: 100%;
  /* 위 여백을 %로 적으면 가로 폭을 기준으로 계산돼서, 화면 높이 기준인 vh로 적어요 */
  padding: 28vh 1.5rem 2.5rem;
  background-color: ${({ theme }) => theme.neutral[50]};
`;

const Logo = styled(LogoIcon)`
  flex-shrink: 0;
  width: 7.375rem; /* 118px */
  height: 2.5625rem; /* 41px */
  /* 피그마의 로고 자리는 높이 52px이에요. 로고의 크기는 그대로 두고, 그 자리의 가운데에 놓이도록 위아래를 띄워요 */
  margin: 0.34375rem 0; /* 5.5px */
  color: ${({ theme }) => theme.neutral[800]};
`;
