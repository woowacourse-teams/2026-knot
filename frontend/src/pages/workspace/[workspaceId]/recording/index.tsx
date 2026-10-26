import { MEDIA_QUERY } from "@constants/breakpoint";
import styled from "@emotion/styled";
import MeetingAudioOption from "@features/recording/MeetingAudioOption";
import RecordingCard from "@features/recording/RecordingCard";
import RecorderBar from "@widgets/recording/RecorderBar";
import { markRecordingStart } from "@utils/recordingStartTrace";
import { useEffect } from "react";

/**
 * 녹음 화면 (`/workspace/:workspaceId/recording`)
 *
 * 독의 마이크로 시작한 녹음을 보여 주는 화면이에요. 위에는 녹음 조작 바를, 아래에는 녹음 카드를 둬요.
 * 둘 사이에는 마이크와 온라인 회의 중 무엇을 녹음하는지 보여 주는 「녹음하는 소리」 패널을 둬요.
 * 진행 중인 녹음 없이 들어오면 라우트의 `RecordingGuard`가 홈으로 보내요.
 * 녹음 상태는 워크스페이스 레이아웃이 들고 있어 다른 화면으로 옮겨 가도 녹음이 이어지고,
 * 그동안 하단 독이 녹음 시간과 이 화면으로 돌아오는 칩을 보여줘요.
 *
 * 녹음을 끝내 오디오 업로드가 확인되면 그 녹음의 문서 정리 화면으로 가고, 올리지 못했으면 홈으로 가요.
 *
 * @see https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=1562-2595 녹음/진행 중
 * @see https://www.figma.com/design/jyDFCKX5AIztZessq4H7nQ/knot?node-id=1635-2763 녹음/다른 화면 이동
 */
export default function RecordingPage() {
  // 독에서 누른 뒤 이 화면이 그려지기까지 걸린 시간을 개발 서버 콘솔에 남겨요
  useEffect(() => {
    const frame = requestAnimationFrame(() => markRecordingStart("screen-painted"));
    return () => cancelAnimationFrame(frame);
  }, []);

  return (
    <Container>
      <PageColumn>
        <RecorderBar />
        <MeetingAudioOption />
        <RecordingCard />
      </PageColumn>
    </Container>
  );
}

/** 좌우 여백과 아래 독 자리를 비워 두고 가운데 열을 놓는 자리예요. */
const Container = styled.div`
  display: flex;
  justify-content: center;
  min-height: 100%;
  padding: 1.75rem 1.5rem 7rem; /* 28px 24px 112px — 위는 GNB 아래 116px 지점, 아래는 하단 Dock 자리 */

  ${MEDIA_QUERY.mobile} {
    padding-right: 1rem; /* 16px */
    padding-left: 1rem;
  }
`;

const PageColumn = styled.div`
  display: flex;
  flex-direction: column;
  gap: 1rem; /* 16px */
  width: 100%;
  max-width: 60rem; /* 960px */
`;
