import usePendingDocumentsQuery from "@api/queries/usePendingDocumentsQuery";
import styled from "@emotion/styled";
import useNavigateToDocument from "@hooks/domain/document/useNavigateToDocument";
import useWorkspaceAccessGuard from "@hooks/domain/workspace/useWorkspaceAccessGuard";

import { useTitleId } from "./model/useTitleId";
import { useWorkspaceId } from "./model/useWorkspaceId";
import PendingDocumentRow from "./ui/PendingDocumentRow";
import PendingDocumentsEmpty from "./ui/PendingDocumentsEmpty";
import PendingDocumentsLoadFailed from "./ui/PendingDocumentsLoadFailed";
import PendingDocumentsSkeleton from "./ui/PendingDocumentsSkeleton";
import { sumDocumentCounts } from "./utils/sumDocumentCounts";

/**
 * 홈의 「확인이 필요한 문서」 카드. 워크스페이스에서 내가 아직 확인하지 않은 문서를 모두 보여주고, 행의 「확인하기」를 누르면 그 문서 보기로 가요.
 *
 * 카드에서 바로 확인하지는 않아요. 확인은 문서 보기에서 문서를 읽고 해요.
 *
 * 문서가 많아도 카드의 높이는 그대로예요. 제목 줄은 그 자리에 두고 행만 카드 안에서 스크롤해요.
 *
 * 문서를 받지 못했거나 문서가 없을 때는 상태 화면이 카드 전체를 대신 그려요. 제목과 카드의 높이는 같고, 문서 수는 숨겨요.
 * - 불러오는 중: `PendingDocumentsSkeleton`. 401 · 403 · 404로 다른 화면에 보내는 동안에도 이 화면이에요.
 * - 불러오기 실패: `PendingDocumentsLoadFailed`. 이어 받는 도중에 실패한 경우도 받은 일부를 쓰지 않고 이 화면이에요. 「다시 시도」는 이 카드의 문서만 처음부터 다시 받아요.
 * - 확인할 문서 없음: `PendingDocumentsEmpty`.
 *
 * 문서를 한 번 받은 뒤에는 다시 불러오기만 실패해도 받은 문서를 계속 보여줘요.
 *
 * 주소의 워크스페이스 id는 숫자인지 확인하지 않고 그대로 요청해요. 잘못된 주소인지는 서버가 판단해요.
 */
export default function PendingDocumentsCard() {
  const { titleId } = useTitleId();
  const { workspaceId } = useWorkspaceId();
  const {
    data: pendingDocuments,
    error,
    isError,
    refetch,
  } = usePendingDocumentsQuery({ workspaceId });
  const { navigateToDocument } = useNavigateToDocument();

  // 로그인이 풀렸으면 로그인 화면으로, 워크스페이스에 들어갈 수 없으면 마지막으로 본 다른 워크스페이스나 선택 화면으로 보내고 알려요
  const { isRedirecting } = useWorkspaceAccessGuard({ error });

  // 화면을 새로고침하지 않고 이 카드의 문서만 다시 조회해요. 새로고침하면 브라우저 안에만 있는 진행 중인 녹음이 사라져요
  const handleRetry = () => {
    refetch();
  };

  // 받은 문서가 없고 실패했을 때 실패 안내를 보여줘요. 다른 화면으로 보내는 중이면 가는 동안 안내가 잠깐 보이지 않게 빼요
  const shouldShowLoadFailed =
    pendingDocuments === undefined && isError && !isRedirecting;

  if (shouldShowLoadFailed) {
    return <PendingDocumentsLoadFailed onRetry={handleRetry} />;
  }

  if (pendingDocuments === undefined) {
    return <PendingDocumentsSkeleton />;
  }

  if (pendingDocuments.items.length === 0) return <PendingDocumentsEmpty />;

  return (
    <Container aria-labelledby={titleId}>
      <Header>
        <Title id={titleId}>확인이 필요한 문서</Title>
        <Count>{sumDocumentCounts(pendingDocuments.topics)}</Count>
      </Header>
      <RowList>
        {pendingDocuments.items.map(({ id, title, createdAt }) => (
          <PendingDocumentRow
            key={id}
            title={title}
            createdAt={createdAt}
            onOpenDocument={() =>
              navigateToDocument({ workspaceId, documentId: id })
            }
          />
        ))}
      </RowList>
    </Container>
  );
}

/** 피그마 Card/ConfirmRequests: 652x312 고정, 안쪽 여백 20px, 머리와 행 사이 8px */
const Container = styled.section`
  display: flex;
  flex-direction: column;
  gap: 0.5rem; /* 8px */
  width: 40.75rem; /* 652px */
  max-width: 100%;
  height: 19.5rem; /* 312px */
  overflow: hidden;
  padding: 1.25rem; /* 20px */
  border-radius: 1.5rem; /* 24px */
  background-color: ${({ theme }) => theme.neutral[0]};
  box-shadow: ${({ theme }) => theme.shadow02};
`;

/** 피그마 Header: 좌우 4px · 아래 8px, 제목과 문서 수 사이 8px */
const Header = styled.div`
  display: flex;
  align-items: center;
  gap: 0.5rem; /* 8px */
  padding: 0 0.25rem 0.5rem; /* 0 4px 8px */
`;

const Title = styled.h2`
  color: ${({ theme }) => theme.neutral[900]};
  ${({ theme }) => theme.text.label01};
`;

const Count = styled.span`
  color: ${({ theme }) => theme.neutral[600]};
  ${({ theme }) => theme.text.label01};
`;

/** 피그마 Card/ConfirmRequests: 행 사이 8px. 행이 카드 높이를 넘으면 카드를 키우지 않고 이 안에서만 스크롤해요 */
const RowList = styled.ul`
  display: flex;
  flex-direction: column;
  gap: 0.5rem; /* 8px */
  overflow-y: auto;
`;
