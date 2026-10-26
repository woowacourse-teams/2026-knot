import useRecordingAudioPlaybackQuery from "@api/queries/useRecordingAudioPlaybackQuery";
import styled from "@emotion/styled";
import useRedirectToLoginOnUnauthorized from "@hooks/domain/auth/useRedirectToLoginOnUnauthorized";
import InlineError from "@primitives/ui/InlineError";
import Skeleton from "@primitives/ui/Skeleton";
import { useParams } from "react-router";

import AudioPlaybackBar from "./ui/AudioPlaybackBar";

/**
 * 녹음 문서에서 그 녹음의 음성을 다시 듣는 플레이어(STT-R50). 피그마 Player/RecordingAudio예요.
 *
 * 주소의 녹음으로 재생 정보를 조회하고, 음성 상태에 따라 그려요.
 * - 들을 수 있음(`AVAILABLE`): 재생 · 일시정지 · 위치 이동과 지금 위치 · 전체 길이(`AudioPlaybackBar`)
 * - 음성 없음(`NO_AUDIO`): 지난 정리 정책으로 음성 파일이 지워진 녹음이에요. 실패가 아니라 다시 시도 없이 회색 안내만 둬요
 * - 준비 중(`PREPARING`): 조각 녹음의 음성을 아직 합치는 중이에요. 시안이 없어 음성 없음과 같은 회색 안내에 문구만 바꿨어요
 * - 불러오기 실패: 이 자리에만 안내와 「다시 시도」를 두고, 제목 · 요약 · 전사는 그대로 읽을 수 있어요
 *
 * 어느 처리 상태에서 플레이어를 둘지(완료 · 문서 생성 실패 · 정리할 내용 없음)는 이 플레이어를 놓는 쪽이 정해요.
 * 듣기는 녹음 · 전사 · 요약 · 처리 상태와 문서 확인 기록을 바꾸지 않아요.
 */
export default function RecordingAudioPlayer() {
  const params = useParams();
  const workspaceId = Number(params.workspaceId);
  const recordingId = Number(params.recordingId);
  const {
    data: audioPlayback,
    error,
    isError,
    refetch,
  } = useRecordingAudioPlaybackQuery({ workspaceId, recordingId });
  const { isUnauthorized } = useRedirectToLoginOnUnauthorized({ error });

  const handleRetry = () => {
    refetch();
  };

  if (audioPlayback === undefined) {
    if (isError && !isUnauthorized) {
      return (
        <Root role="group" aria-label="녹음 음성">
          <InlineError
            message="음성을 불러오지 못했어요. 다시 시도해 주세요."
            onRetry={handleRetry}
          />
        </Root>
      );
    }

    // 피그마 Player: 높이 64px, 모서리 14px
    return <Skeleton height={4} radius={0.875} />;
  }

  const { audioStatus, playbackUrl, expiresAt, recordingDurationMillis } =
    audioPlayback;

  if (audioStatus === "AVAILABLE" && playbackUrl !== null) {
    return (
      <Root role="group" aria-label="녹음 음성">
        <AudioPlaybackBar
          playbackUrl={playbackUrl}
          expiresAt={expiresAt}
          durationMillis={recordingDurationMillis}
          refreshPlaybackUrl={refetch}
        />
      </Root>
    );
  }

  return (
    <Root role="group" aria-label="녹음 음성">
      <Notice>
        {audioStatus === "PREPARING"
          ? "음성을 준비하고 있어요. 잠시 뒤에 들을 수 있어요."
          : "이 녹음은 음성이 남아 있지 않아 들을 수 없어요."}
      </Notice>
    </Root>
  );
}

/** 피그마 Player/RecordingAudio: Neutral/100 바탕, 모서리 14px, 안쪽 여백 14px 16px */
const Root = styled.div`
  padding: 0.875rem 1rem; /* 14px 16px */
  border-radius: 0.875rem; /* 14px */
  background-color: ${({ theme }) => theme.neutral[100]};
`;

/** 피그마 Player 상태=음성 없음: 회색 작은 안내 한 줄 */
const Notice = styled.p`
  ${({ theme }) => theme.text.caption02};
  color: ${({ theme }) => theme.neutral[500]};
`;
