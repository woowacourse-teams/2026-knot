import useDocumentQuery from "@api/queries/useDocumentQuery";
import useWorkspaceQuery from "@api/queries/useWorkspaceQuery";
import { MEDIA_QUERY } from "@constants/breakpoint";
import {
  DOCUMENT_FROM_PARAM,
  DOCUMENT_FROM_RECORDING,
} from "@constants/document";
import styled from "@emotion/styled";
import useWorkspaceNav from "@hooks/domain/workspace/useWorkspaceNav";
import Avatar from "@primitives/ui/Avatar";
import Skeleton from "@primitives/ui/Skeleton";
import { useParams, useSearchParams } from "react-router";

import ChevronRightIcon from "@/assets/icons/chevronRight.svg";

/** 내비 필의 탭 순서(홈, 탐색, 문서)대로 둔 탭 이름. `useWorkspaceNav`의 지금 탭 자리로 골라요 */
const TAB_NAMES = ["홈", "탐색", "문서"];

/**
 * GNB 왼쪽 패널 버튼 옆에서 지금 있는 워크스페이스와 탭을 알려 주는 위치 표시(WS-R15).
 *
 * 워크스페이스 아이콘과 이름 뒤에 지금 탭 이름을 `›`로 이어요. 문서 하나를 보는 화면에서는 `문서 › 문서 제목`이에요.
 * 녹음, 녹음 직후 문서 확인(`?from=recording`)처럼 탭이 아닌 화면과 1024 미만에서는 아이콘과 워크스페이스 이름만 보여요.
 * 767 이하에서는 GNB 왼쪽에 자리가 없어 위치 표시를 숨겨요.
 * 보여 주기만 하고 눌러도 아무 일도 없어요. 워크스페이스 전환은 사이드바 위쪽 이름에서 해요(WS-07).
 * 이름은 200px까지, 문서 제목은 남은 자리만큼 한 줄로 두고 넘치면 말줄임해요.
 * 불러오는 중에는 이름 · 제목 자리를 회색 덩어리로 채우고 아이콘은 기본 모양으로 둬요.
 * 문서를 불러오지 못하면 문서 보기가 실패 화면을 그리므로 제목 자리 없이 `문서`에서 끝내요.
 * 워크스페이스와 문서는 레이아웃 · 문서 보기와 같은 키로 조회해서 요청이 더 나가지 않아요.
 */
export default function WorkspaceLocation() {
  const params = useParams();
  const [searchParams] = useSearchParams();
  const workspaceId = Number(params.workspaceId);
  const isDocumentScreen = params.documentId !== undefined;
  const isFromRecording =
    searchParams.get(DOCUMENT_FROM_PARAM) === DOCUMENT_FROM_RECORDING;
  const { activeTabIndex } = useWorkspaceNav();
  const { data: workspace } = useWorkspaceQuery({ workspaceId });
  const { data: documentDetail, isError: isDocumentError } = useDocumentQuery({
    workspaceId,
    documentId: Number(params.documentId),
    isEnabled: isDocumentScreen,
  });

  // 녹음처럼 탭이 아닌 화면은 자리가 -1이라 탭 이름이 없어요. 녹음 직후 문서 확인도 녹음의 이어지는 단계라 탭으로 보지 않아요
  const tabName = isFromRecording ? undefined : TAB_NAMES[activeTabIndex];
  // 받은 제목이 있거나 아직 불러오는 중일 때만 제목 자리를 둬요
  const hasDocumentTitle =
    isDocumentScreen && (documentDetail !== undefined || !isDocumentError);

  return (
    <Container role="group" aria-label="지금 위치">
      {/* 이름을 바로 옆에 글자로 보여 줘서 아이콘은 읽지 않아요 */}
      <AvatarWrapper aria-hidden="true">
        {workspace ? (
          <Avatar label="워크스페이스" name={workspace.name} size={24} />
        ) : (
          <LoadingAvatar label="워크스페이스" size={24} />
        )}
      </AvatarWrapper>
      <Path>
        {workspace ? (
          <WorkspaceName>{workspace.name}</WorkspaceName>
        ) : (
          <Skeleton width={5} height={0.75} />
        )}
        {tabName && (
          <Trail>
            <ChevronRightIcon size={12} />
            <TabName>{tabName}</TabName>
            {hasDocumentTitle && (
              <>
                <ChevronRightIcon size={12} />
                {documentDetail ? (
                  <DocumentTitle>{documentDetail.title}</DocumentTitle>
                ) : (
                  <Skeleton width={7.5} height={0.75} />
                )}
              </>
            )}
          </Trail>
        )}
      </Path>
    </Container>
  );
}

/** 피그마 GNB/WorkspaceLocation: 패널 버튼과 12px, 아이콘과 이름 사이 8px */
const Container = styled.div`
  display: flex;
  align-items: center;
  gap: 0.5rem; /* 8px */
  min-width: 0;
  /* 버튼 사이 간격(8px)에 4px을 더해 패널 버튼과 12px을 띄워요 */
  margin-left: 0.25rem; /* 4px */

  /* 모바일 GNB 왼쪽은 패널 버튼만으로 차서, 가운데 탭에 가리지 않게 숨겨요 */
  ${MEDIA_QUERY.mobile} {
    display: none;
  }
`;

const AvatarWrapper = styled.span`
  display: flex;
  flex-shrink: 0;
`;

/** 피그마의 불러오는 중 아이콘: 연한 바탕에 기본 사람 모양 */
const LoadingAvatar = styled(Avatar)`
  background-color: ${({ theme }) => theme.neutral[200]};
  color: ${({ theme }) => theme.neutral[400]};
`;

/** 이름 · 탭 · 문서 제목 사이 6px, 글자는 14px 한 줄 */
const Path = styled.div`
  display: flex;
  align-items: center;
  gap: 0.375rem; /* 6px */
  min-width: 0;
  ${({ theme }) => theme.text.caption02};
`;

/** 피그마의 14px 굵은 글자. 같은 크기의 굵은 글꼴 토큰이 없어 caption02에 굵기만 더해요 */
const WorkspaceName = styled.span`
  max-width: 12.5rem; /* 200px */
  overflow: hidden;
  color: ${({ theme }) => theme.neutral[800]};
  font-weight: 600;
  white-space: nowrap;
  text-overflow: ellipsis;
`;

const Trail = styled.div`
  display: flex;
  align-items: center;
  gap: 0.375rem; /* 6px */
  min-width: 0;

  & > svg {
    flex-shrink: 0;
    color: ${({ theme }) => theme.neutral[400]};
  }

  /* 1024 미만에서는 자리가 모자라 아이콘과 워크스페이스 이름만 남겨요(WS-R15) */
  ${MEDIA_QUERY.belowDesktop} {
    display: none;
  }
`;

const TabName = styled.span`
  flex-shrink: 0;
  color: ${({ theme }) => theme.neutral[500]};
`;

const DocumentTitle = styled.span`
  overflow: hidden;
  color: ${({ theme }) => theme.neutral[700]};
  white-space: nowrap;
  text-overflow: ellipsis;
`;
