import { MEDIA_QUERY } from "@constants/breakpoint";
import styled from "@emotion/styled";
import MemberGreeting from "@features/member/MemberGreeting";
import PendingDocumentsCard from "@widgets/document/PendingDocumentsCard";
import RecordingHistoryCard from "@widgets/recording/RecordingHistoryCard";
import WorkspaceInviteMemberCard from "@widgets/workspace/WorkspaceInviteMemberCard";

/**
 * 워크스페이스 홈 화면 (`/workspace/:workspaceId`)
 *
 * 워크스페이스에 입장한 뒤의 기본 화면이다.
 * 초대 링크 복사, 채팅 응답 완료는 별도 라우트가 아니라
 * 이 화면 위에서 처리되는 상태 변형이다.
 *
 * GNB·사이드바·하단 Dock은 `WorkspaceLayout`이 담당하고, 이 페이지는 인사와 카드를 두 열로 놓기만 한다.
 * 왼쪽 열은 녹음 기록이 두 줄을 차지하고, 오른쪽 열은 윗줄(312px)에 확인이 필요한 문서, 아랫줄(214px)에 팀원 초대다(HOME-R1 · R2).
 * 카드는 저마다 따로 불러와, 한 카드가 실패해도 그 카드 안에서만 알린다(HOME-R6).
 *
 * @see https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=1562-2616 홈/기본
 * @see https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=1917-18135 홈/처음 사용
 * @see https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=3805-38611 홈/불러오는 중
 * @see https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=5422-4668 홈/녹음 기록
 */
export default function WorkspaceHomePage() {
  return (
    <Root>
      <PageColumn>
        <MemberGreeting />
        <CardGrid>
          <RecordingHistoryCard />
          <PendingDocumentsCard />
          <WorkspaceInviteMemberCard />
        </CardGrid>
      </PageColumn>
    </Root>
  );
}

const Root = styled.div`
  display: flex;
  justify-content: center;
  min-height: 100%;
  padding: 1.375rem 1.5rem 7rem; /* 22px 24px 112px — 위는 피그마의 화면 위 110px 자리, 아래는 하단 Dock 자리 */

  ${MEDIA_QUERY.mobile} {
    padding-right: 1rem; /* 16px */
    padding-left: 1rem;
  }
`;

/** 피그마 PageColumn: 폭 1080px, 인사말과 카드 묶음 사이 28px */
const PageColumn = styled.div`
  display: flex;
  flex-direction: column;
  gap: 1.75rem; /* 28px */
  width: 100%;
  max-width: 67.5rem; /* 1080px */

  ${MEDIA_QUERY.mobile} {
    gap: 1.25rem; /* 20px */
  }
`;

/**
 * 피그마 Grid: 왼쪽 열 652px · 오른쪽 열 400px, 윗줄 312px · 아랫줄 214px, 사이 28px.
 * 녹음 기록은 왼쪽 열의 두 줄을 차지해요. 화면이 좁으면 왼쪽 열부터 줄어들고, 카드는 칸의 높이를 채워요.
 *
 * 태블릿은 2열을 지키고 오른쪽 열만 330px로 줄여요. 모바일은 한 열로 쌓고 카드 높이는 카드가 정해요(RESP-R5).
 * 모바일 순서는 녹음 기록, 확인이 필요한 문서, 팀원 초대예요.
 */
const CardGrid = styled.div`
  display: grid;
  grid-template-rows: 19.5rem 13.375rem; /* 312px 214px */
  grid-template-columns: minmax(0, 40.75rem) minmax(0, 25rem); /* 652px 400px */
  gap: 1.75rem; /* 28px */

  /* 녹음 기록(첫 번째)은 왼쪽 열의 두 줄을 차지해요 */
  & > :first-child {
    grid-row: span 2;
  }

  ${MEDIA_QUERY.belowDesktop} {
    grid-template-columns: minmax(0, 1fr) 20.625rem; /* 330px */
  }

  ${MEDIA_QUERY.mobile} {
    grid-template-rows: none;
    grid-template-columns: minmax(0, 1fr);
    gap: 1rem; /* 16px */

    & > :first-child {
      grid-row: auto;
    }
  }
`;
