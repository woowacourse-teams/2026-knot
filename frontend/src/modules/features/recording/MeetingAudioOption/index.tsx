import styled from "@emotion/styled";
import Button from "@primitives/ui/Button";

import { MEETING_AUDIO_COPY } from "./constants/meetingAudioCopy";
import { useMeetingAudioOption } from "./model/useMeetingAudioOption";

/**
 * 녹음 중에 회의 소리도 함께 녹음하는 선택지(STT-R44~R49).
 *
 * 녹음은 독 마이크로 시작해 마이크만 녹음하고(STT-R43), 이 선택지를 골라 공유한 탭·화면·창의 소리를 같은 녹음에 섞어요.
 * Chrome 계열이 아닌 브라우저에서는 선택지 대신 Chrome 계열에서 쓸 수 있다는 안내만 보여 주고, 마이크 녹음은 그대로예요.
 * 소리 없이 공유했거나 함께 녹음하다 공유가 멈추면 마이크만 녹음하면서 그 사실을 알려요.
 *
 * 자리와 문구는 디자인 전의 임시 값이에요.
 */
export default function MeetingAudioOption() {
  const { isSupported, isOptionVisible, notice, handleOptionClick } =
    useMeetingAudioOption();

  if (!isSupported) {
    return <Guide>{MEETING_AUDIO_COPY.UNSUPPORTED}</Guide>;
  }

  return (
    <Container>
      {isOptionVisible && (
        <Button size="sm" variant="outline" onClick={handleOptionClick}>
          {MEETING_AUDIO_COPY.OPTION}
        </Button>
      )}
      {/* 알림이 바뀔 때 화면 낭독기가 읽도록 영역은 늘 둬요 */}
      <Notice role="status">{notice}</Notice>
    </Container>
  );
}

const Container = styled.div`
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0.75rem; /* 12px */
`;

const Guide = styled.p`
  color: ${({ theme }) => theme.neutral[500]};
  ${({ theme }) => theme.text.caption01};
`;

const Notice = styled.p`
  color: ${({ theme }) => theme.sub.caution[700]};
  ${({ theme }) => theme.text.caption01};
`;
