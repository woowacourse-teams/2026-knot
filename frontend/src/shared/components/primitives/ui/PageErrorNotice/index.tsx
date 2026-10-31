import styled from "@emotion/styled";
import Button from "@primitives/ui/Button";
import Logo from "@primitives/ui/Logo";
import { useId } from "react";

import InvalidInvitationUrlIllustration from "@/assets/illustrations/invalidInvitationUrl.svg";

interface PageErrorNoticeProps {
  /**
   * 어떤 실패인지. 문구와 버튼 이름이 정해져요.
   * - `badRequest`: 잘못된 주소나 더 이상 유효하지 않은 요청(ERR-01)
   * - `network`: 응답을 받지 못한 실패. 네트워크 끊김과 timeout(ERR-02)
   * - `server`: 서버 오류와 화면을 그리지 못한 코드 오류(ERR-03)
   * - `loginFailed`: GitHub, Google, 카카오 로그인 처리 실패와 원인 미상(ERR-15)
   */
  variant: keyof typeof PAGE_ERROR;
  /** 버튼을 눌렀을 때 실행할 동작. ERR-01은 홈으로 이동, ERR-02와 ERR-03은 새로고침, ERR-15는 로그인 화면으로 이동이에요 */
  onAction: () => void;
}

/** 피그마 에러 페이지(ERR-01~03, ERR-15)의 문구. 설명은 시안의 줄 나눔대로 한 줄씩 적어요 */
const PAGE_ERROR = {
  badRequest: {
    title: "요청을 처리할 수 없어요",
    descriptions: ["잘못된 주소이거나", "더 이상 유효하지 않은 요청이에요."],
    actionLabel: "홈으로 이동",
  },
  network: {
    title: "연결이 잠시 끊겼어요",
    descriptions: ["네트워크 상태를 확인한 뒤", "다시 시도해 주세요."],
    actionLabel: "다시 시도",
  },
  server: {
    title: "잠시 문제가 생겼어요",
    descriptions: ["서버에서 생긴 문제예요.", "잠시 후에 다시 시도해 주세요."],
    actionLabel: "다시 시도",
  },
  loginFailed: {
    title: "로그인하지 못했어요",
    descriptions: ["로그인 중 문제가 생겼어요.", "잠시 후 다시 시도해 주세요."],
    actionLabel: "로그인 화면으로",
  },
} as const;

/**
 * 화면 전체를 그리지 못했을 때 그 화면을 대신하는 공통 에러 페이지(ERR-01~03, ERR-15).
 *
 * 로고 · 그림 · 제목 · 설명 · 버튼 순서로 놓고, GNB와 독은 두지 않아요.
 * 레이아웃 밖(앱 루트 Boundary, 워크스페이스 레이아웃 대신)에서도 그려지므로 로고와 배경, 위 여백을 직접 가져요.
 * 위 여백은 `CenteredLayout`처럼 화면 높이의 28%예요.
 * 낭독기가 바로 읽도록 `role="alert"`를 붙였어요.
 * 버튼만 따로 읽어도 무엇을 하는 버튼인지 알 수 있게 제목과 설명을 버튼의 설명으로 이어요(AUTH-R8).
 */
export default function PageErrorNotice({
  variant,
  onAction,
}: PageErrorNoticeProps) {
  const { title, descriptions, actionLabel } = PAGE_ERROR[variant];
  const titleId = useId();
  const descriptionId = useId();

  return (
    <Root>
      <Container role="alert">
        <Content>
          <PageLogo width={7.375} /* 118px */ />
          <Illustration />

          <Header>
            <Title id={titleId}>{title}</Title>
            <Description id={descriptionId}>
              {descriptions.map((description) => (
                <p key={description}>{description}</p>
              ))}
            </Description>
          </Header>
        </Content>

        <Button
          size="lg"
          isFullWidth
          onClick={onAction}
          aria-describedby={`${titleId} ${descriptionId}`}
        >
          {actionLabel}
        </Button>
      </Container>
    </Root>
  );
}

const Root = styled.main`
  display: flex;
  justify-content: center;
  min-height: 100%;
  /* 위 여백을 %로 적으면 가로 폭을 기준으로 계산돼서, 화면 높이 기준인 vh로 적어요 */
  padding: 28vh 1.5rem 2.5rem;
  background-color: ${({ theme }) => theme.neutral[50]};
`;

const Container = styled.div`
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4rem; /* 64px */
  width: 100%;
  max-width: 22.5rem; /* 360px */
`;

const Content = styled.div`
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2.5rem; /* 40px */
`;

/** 피그마의 로고 자리는 높이 52px이에요. `CenteredLayout`과 같은 크기로 두고 그 자리의 가운데에 놓이도록 위아래를 띄워요 */
const PageLogo = styled(Logo)`
  flex-shrink: 0;
  margin: 0.34375rem 0; /* 5.5px */
`;

const Illustration = styled(InvalidInvitationUrlIllustration)`
  flex-shrink: 0;
  width: 3rem; /* 48px */
  height: 3rem;
  color: ${({ theme }) => theme.neutral[800]};
`;

const Header = styled.div`
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 1rem; /* 16px */
  text-align: center;
  overflow-wrap: break-word;
`;

const Title = styled.h1`
  color: ${({ theme }) => theme.neutral[800]};
  ${({ theme }) => theme.text.heading02};
`;

const Description = styled.div`
  color: ${({ theme }) => theme.neutral[700]};
  ${({ theme }) => theme.text.body02};
`;
