import { MEDIA_QUERY } from "@constants/breakpoint";
import styled from "@emotion/styled";
import useServerRecording from "@hooks/domain/recording/useServerRecording";

import { useOpenRecordingDocuments } from "./model/useOpenRecordingDocuments";
import { useRecordingTitle } from "./model/useRecordingTitle";
import { useRetryFailedRecording } from "./model/useRetryFailedRecording";
import { useThisTabRecording } from "./model/useThisTabRecording";
import { useTitleId } from "./model/useTitleId";
import CurrentRecordingItem from "./ui/CurrentRecordingItem";
import EmptyRecording from "./ui/EmptyRecording";
import LoadingFallback from "./ui/LoadingFallback";
import RecordingLoadFailed from "./ui/RecordingLoadFailed";

/**
 * 홈의 「진행 중인 녹음」 카드. 내 가장 최근 녹음 하나의 진행 상태를 보여 주고, 없으면 빈 상태 문구를 보여 줘요.
 *
 * 이 탭에서 시작한 녹음이 이어지고 있으면(녹음 탭) 이 탭의 상태와 시간을 보여 주고,
 * 「녹음 화면으로」로 녹음 화면에 갈 수 있게 해요.
 *
 * 그 밖에는(관찰 탭) 현재 `:workspaceId`의 현재 녹음 조회 응답을 보여 주고, 녹음 중·일시정지면 버튼을 숨겨요.
 * 응답의 누적 시간에 응답을 받은 뒤 흐른 시간을 더해 녹음 중이면 매초 늘리고, 일시정지면 멈춰 둬요.
 * 녹음을 끝내고 문서를 정리하는 중이면 시간 없이 「문서 정리 중」을 보여 주고, 「보기」로 그 녹음의 문서 정리 화면에 가요.
 * 정리가 끝나면 서버가 표시할 녹음이 없다고 답해 빈 상태로 돌아가요.
 * 전사나 문서 생성이 끝내 실패했으면 실패한 단계의 제목과 보관 안내를 보여 주고, 「다시 시도」로 그 단계를 다시 접수해 정리 중으로 돌아가요(STT-R24 · STT-R22).
 * 다시 시도할 수 없는 실패는 아직 시안이 없어 빈 상태로 보여요.
 * 조회에 실패하면 칸 자리에 실패 안내(ERR-09)를 두고, `다시 시도`는 이 카드의 녹음만 다시 조회해요.
 * 이 탭의 녹음은 조회와 관계없이 알 수 있어 조회에 실패해도 그대로 보여 줘요.
 */
export default function RecordingListCard() {
  const titleId = useTitleId();
  const { title, isLoading: isTitleLoading } = useRecordingTitle();
  const {
    status: thisTabStatus,
    elapsedTime: thisTabElapsedTime,
    handleOpenRecording,
  } = useThisTabRecording();
  const {
    status: serverStatus,
    recordingId: serverRecordingId,
    elapsedTime: serverElapsedTime,
    isLoading: isServerLoading,
    isLoadFailed: isServerLoadFailed,
    retry: retryServerRecording,
  } = useServerRecording();
  const { handleOpenRecordingDocuments } = useOpenRecordingDocuments({
    recordingId: serverRecordingId,
  });
  const isServerFailed =
    serverStatus === "transcriptionFailed" || serverStatus === "documentFailed";
  const { handleRetry, isRetrying } = useRetryFailedRecording({
    recordingId: isServerFailed ? serverRecordingId : null,
    failedStatus: isServerFailed ? serverStatus : null,
  });

  // 이 탭의 녹음은 현재 녹음 조회를 기다리지 않고 바로 보여 줘요. 녹음 이름은 어느 쪽이든 칸에 들어가 기다려요
  const isLoading =
    isTitleLoading || (thisTabStatus === "idle" && isServerLoading);

  return (
    <Container aria-labelledby={titleId} aria-busy={isLoading}>
      <Header>
        <Title id={titleId}>진행 중인 녹음</Title>
      </Header>

      {isLoading ? (
        <LoadingFallback />
      ) : thisTabStatus !== "idle" ? (
        <CurrentRecordingItem
          recordingStatus={thisTabStatus}
          title={title}
          elapsedTime={thisTabElapsedTime}
          onOpenRecording={handleOpenRecording}
        />
      ) : isServerLoadFailed ? (
        <RecordingLoadFailed onRetry={retryServerRecording} />
      ) : serverStatus === "organizing" ? (
        <CurrentRecordingItem
          recordingStatus={serverStatus}
          title={title}
          onOpenRecordingDocuments={handleOpenRecordingDocuments}
        />
      ) : isServerFailed ? (
        <CurrentRecordingItem
          recordingStatus={serverStatus}
          title={title}
          onRetry={handleRetry}
          isRetrying={isRetrying}
        />
      ) : serverStatus ? (
        <CurrentRecordingItem
          recordingStatus={serverStatus}
          title={title}
          elapsedTime={serverElapsedTime}
        />
      ) : (
        <EmptyRecording />
      )}
    </Container>
  );
}

const Container = styled.section`
  display: flex;
  flex-direction: column;
  gap: 0.5rem; /* 8px */
  width: 40.75rem; /* 652px */
  max-width: 100%;
  height: 13.375rem; /* 214px */
  overflow: hidden;
  padding: 1.25rem; /* 20px */

  /* 모바일은 버튼이 아래 줄로 내려가 높이를 내용에 맞춰요(RESP-R5) */
  ${MEDIA_QUERY.mobile} {
    height: auto;
    min-height: 13.375rem; /* 214px */
  }

  border-radius: 1.5rem; /* 24px */
  background-color: ${({ theme }) => theme.neutral[0]};
  box-shadow: ${({ theme }) => theme.shadow02};
`;

const Header = styled.div`
  display: flex;
  align-items: center;
  padding: 0 0.25rem 0.5rem; /* 0 4px 8px */
`;

const Title = styled.h2`
  color: ${({ theme }) => theme.neutral[900]};
  ${({ theme }) => theme.text.label01};
`;
