import { MEDIA_QUERY } from "@constants/breakpoint";
import styled from "@emotion/styled";
import LoadingIndicator from "@primitives/ui/LoadingIndicator";

import { useCurrentRecording } from "./model/useCurrentRecording";
import { useRecordingHistory } from "./model/useRecordingHistory";
import { useRecordingTitle } from "./model/useRecordingTitle";
import { useTitleId } from "./model/useTitleId";
import CurrentRecordingItem from "./ui/CurrentRecordingItem";
import EmptyRecording from "./ui/EmptyRecording";
import LoadingFallback from "./ui/LoadingFallback";
import RecordingHistoryRow from "./ui/RecordingHistoryRow";
import RecordingLoadFailed from "./ui/RecordingLoadFailed";

/**
 * 홈의 「녹음 기록」 카드. 맨 위에 내 녹음 중 · 일시정지 녹음 한 줄을, 그 아래에 워크스페이스의 끝난 녹음을 최신순으로 보여 줘요(STT-R24 · R31~R35 · R51).
 *
 * 맨 위 줄은 이 탭의 녹음이면 「녹음 화면으로」로 녹음 화면에 갈 수 있고, 다른 탭의 녹음이면 그 버튼을 누를 수 없어요(STT-R28).
 * 맨 위 줄은 카드 머리 아래에 고정하고, 목록만 카드 안에서 세로로 스크롤하며 아래 40px을 흐리게 해요. 스크롤 끝에 닿으면 다음 묶음을 이어 받아요.
 * 행마다 처리 상태를 보여 주고, 「녹음 보기」로 그 녹음의 녹음 문서에 가요. 다시 시도할 수 있는 실패 행에는 「다시 시도」가 있어요.
 * 문서 정리 중 행이 있는 동안에는 목록을 3초마다 다시 받아, 정리가 끝나면 결과 상태로 바뀌어요.
 *
 * 목록을 받지 못한 동안에는 목록 자리를 상태 화면이 대신해요.
 * - 불러오는 중: `LoadingFallback`. 내 닉네임과 현재 녹음도 기다려요.
 * - 불러오기 실패: `RecordingLoadFailed`(ERR-09). 「다시 시도」는 이 카드의 목록만 다시 받아요(HOME-R6).
 * - 맨 위 줄과 목록 모두 없음: `EmptyRecording`. 카드 크기는 그대로예요.
 */
export default function RecordingHistoryCard() {
  const titleId = useTitleId();
  const { title, isLoading: isTitleLoading } = useRecordingTitle();
  const { currentRecording, isLoading: isCurrentRecordingLoading } =
    useCurrentRecording();
  const {
    workspaceId,
    recordings,
    isLoading: isListLoading,
    isLoadFailed,
    hasNextPage,
    isFetchingNextPage,
    sentinelRef,
    handleRetry,
  } = useRecordingHistory();

  const isLoading =
    isTitleLoading || isCurrentRecordingLoading || isListLoading;
  const isEmpty =
    currentRecording === null && recordings.length === 0 && !hasNextPage;

  return (
    <Container aria-labelledby={titleId} aria-busy={isLoading}>
      <Header>
        <Title id={titleId}>녹음 기록</Title>
      </Header>

      {!isLoading && currentRecording && (
        <>
          <CurrentRecordingItem
            recordingStatus={currentRecording.status}
            title={title}
            elapsedTime={currentRecording.elapsedTime}
            onOpenRecording={currentRecording.onOpenRecording}
          />
          <Divider />
        </>
      )}

      {isLoading ? (
        <LoadingFallback />
      ) : isLoadFailed ? (
        <RecordingLoadFailed onRetry={handleRetry} />
      ) : isEmpty ? (
        <EmptyRecording />
      ) : (
        <ListArea>
          <ScrollArea>
            <ul aria-labelledby={titleId}>
              {recordings.map((recording) => (
                <RecordingHistoryRow
                  key={recording.recordingId}
                  workspaceId={workspaceId}
                  recording={recording}
                />
              ))}
            </ul>
            {isFetchingNextPage && (
              <LoadMore>
                <LoadingIndicator
                  label="다음 녹음을 불러오고 있어요"
                  size="1.25rem"
                />
              </LoadMore>
            )}
            {/* 목록 끝 표시. 화면에 들어오면 다음 묶음을 받아요 */}
            <Sentinel ref={sentinelRef} aria-hidden />
          </ScrollArea>
        </ListArea>
      )}
    </Container>
  );
}

/** 피그마 Card/RecordingHistory: 652px × 554px(모바일 460px), 안쪽 20px, 머리 · 맨 위 줄 · 목록 사이 8px */
const Container = styled.section`
  display: flex;
  flex-direction: column;
  gap: 0.5rem; /* 8px */
  width: 40.75rem; /* 652px */
  max-width: 100%;
  height: 34.625rem; /* 554px */
  overflow: hidden;
  padding: 1.25rem; /* 20px */
  border-radius: 1.5rem; /* 24px */
  background-color: ${({ theme }) => theme.neutral[0]};
  box-shadow: ${({ theme }) => theme.shadow02};

  ${MEDIA_QUERY.mobile} {
    height: 28.75rem; /* 460px */
  }
`;

/** 피그마 Header: 좌우 4px · 아래 8px */
const Header = styled.div`
  display: flex;
  flex-shrink: 0;
  align-items: center;
  padding: 0 0.25rem 0.5rem; /* 0 4px 8px */
`;

const Title = styled.h2`
  color: ${({ theme }) => theme.neutral[900]};
  ${({ theme }) => theme.text.label01};
`;

/** 피그마 Divider: 위아래 4px · 좌우 12px 안에 1px 선 */
const Divider = styled.hr`
  flex-shrink: 0;
  height: 1px;
  margin: 0.25rem 0.75rem; /* 4px 12px */
  border: none;
  background-color: ${({ theme }) => theme.neutral[200]};
`;

/** 목록 자리. 아래 40px을 흐리게 덮어 더 내려 볼 수 있다는 걸 보여 줘요 */
const ListArea = styled.div`
  position: relative;
  flex: 1;
  min-height: 0;

  &::after {
    content: "";
    position: absolute;
    right: 0;
    bottom: 0;
    left: 0;
    height: 2.5rem; /* 40px */
    background: linear-gradient(
      to bottom,
      transparent,
      ${({ theme }) => theme.neutral[0]}
    );
    pointer-events: none;
  }
`;

const ScrollArea = styled.div`
  height: 100%;
  overflow-y: auto;
`;

/** 피그마 LoadMore: 위아래 16px에 가운데 정렬 */
const LoadMore = styled.div`
  display: flex;
  justify-content: center;
  padding: 1rem 0; /* 16px 0 */
`;

const Sentinel = styled.div`
  height: 1px;
`;
