import useDocumentsQuery from "@api/queries/useDocumentsQuery";
import styled from "@emotion/styled";
import useWorkspaceAccessGuard from "@hooks/domain/workspace/useWorkspaceAccessGuard";
import { getRouterPath } from "@routes/PATH_ROUTE";
import { groupDocumentsByTopic } from "@utils/groupDocumentsByTopic";

import { useWorkspaceId } from "./model/useWorkspaceId";
import DocumentFolder from "./ui/DocumentFolder";
import DocumentListEmpty from "./ui/DocumentListEmpty";
import DocumentListLoadFailed from "./ui/DocumentListLoadFailed";
import DocumentListSkeleton from "./ui/DocumentListSkeleton";
import DocumentRow from "./ui/DocumentRow";

/**
 * 문서 탭 「주제별」 탭의 목록. 워크스페이스의 모든 문서를 폴더(주제)별로 모아 보여주고, 행을 누르면 그 문서 보기로 가요(DOC-R1).
 *
 * 제목 「문서」와 탭은 문서 탭 섹션(`DocumentArchive`)이 그리고, 여기서는 탭이 가리키는 목록만 그려요.
 *
 * 폴더는 이름순, 폴더 안의 문서는 최근 회의순(최근에 시작한 녹음에서 나온 문서가 위)이에요(DOC-R15 · R16). 확인 여부나 문서 상태로 묶거나 정렬하지 않아요(DOC-R11).
 *
 * 문서를 받지 못한 동안에는 상태 화면이 목록 자리를 대신 그려요.
 * - 불러오는 중: `DocumentListSkeleton`. 401 · 403 · 404로 다른 화면에 보내는 동안에도 이 화면이에요.
 * - 불러오기 실패: `DocumentListLoadFailed`. 이어 받는 도중에 실패한 경우도 받은 일부를 쓰지 않고 이 화면이에요. 「다시 시도」는 처음부터 다시 받아요.
 * - 문서 없음: `DocumentListEmpty`.
 *
 * 문서를 한 번 받은 뒤에는 다시 불러오기만 실패해도 받은 문서를 계속 보여줘요.
 *
 * 주소의 워크스페이스 id는 숫자인지 확인하지 않고 그대로 요청해요. 잘못된 주소인지는 서버가 판단하고, 여기서는 그 응답에 따라 목록을 불러오지 못했다고 알려요.
 * 같은 판단을 프론트에도 두면 기준이 두 곳에 생기기 때문이에요.
 */
export default function TopicDocumentList() {
  const { workspaceId } = useWorkspaceId();
  const {
    data: documentList,
    error,
    isError,
    refetch,
  } = useDocumentsQuery({ workspaceId });

  // 로그인이 풀렸으면 로그인 화면으로, 워크스페이스에 들어갈 수 없으면 마지막으로 본 다른 워크스페이스나 선택 화면으로 보내고 알려요
  const { isRedirecting } = useWorkspaceAccessGuard({ error });

  // 화면을 새로고침하지 않고 목록만 다시 조회해요. 새로고침하면 브라우저 안에만 있는 진행 중인 녹음이 사라져요
  const handleRetry = () => {
    refetch();
  };

  // 받은 문서가 없고 실패했을 때 실패 안내를 보여줘요. 다른 화면으로 보내는 중이면 가는 동안 안내가 잠깐 보이지 않게 빼요
  const shouldShowLoadFailed =
    documentList === undefined && isError && !isRedirecting;

  if (shouldShowLoadFailed) {
    return <DocumentListLoadFailed onRetry={handleRetry} />;
  }

  if (documentList === undefined) {
    return <DocumentListSkeleton />;
  }

  const folders = groupDocumentsByTopic({
    topics: documentList.topics,
    documents: documentList.items,
  });

  if (folders.length === 0) return <DocumentListEmpty />;

  return (
    <Folders>
      {folders.map(({ topic, documentCount, documents }) => (
        <DocumentFolder key={topic} topic={topic} documentCount={documentCount}>
          {documents.map(
            ({ id, title, summary, createdAt, recordingDurationSeconds }) => (
              <DocumentRow
                key={id}
                href={getRouterPath({
                  routeKey: "DOCUMENT",
                  params: {
                    workspaceId: String(workspaceId),
                    documentId: String(id),
                  },
                })}
                title={title}
                summary={summary}
                createdAt={createdAt}
                recordingDurationSeconds={recordingDurationSeconds}
              />
            ),
          )}
        </DocumentFolder>
      ))}
    </Folders>
  );
}

/** 피그마 List/Docs: 폴더 사이 28px */
const Folders = styled.div`
  display: flex;
  flex-direction: column;
  gap: 1.75rem; /* 28px */
`;
