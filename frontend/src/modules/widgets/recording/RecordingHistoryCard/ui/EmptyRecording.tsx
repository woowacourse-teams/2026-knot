import styled from "@emotion/styled";

/** 맨 위 줄과 녹음 목록이 모두 없을 때 목록 자리에 보여 주는 안내(STT-R35). */
export default function EmptyRecording() {
  return (
    <Container>
      <Message>아직 녹음이 없어요</Message>
      <Description>
        독의 마이크로 녹음을 시작하면 여기에서 녹음과 정리 상태를 볼 수 있어요
      </Description>
    </Container>
  );
}

/** 피그마 Empty: 안쪽 12px 24px, 두 줄 사이 2px, 가운데 정렬 */
const Container = styled.div`
  display: flex;
  flex: 1;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 0.125rem; /* 2px */
  width: 100%;
  min-height: 0;
  padding: 0.75rem 1.5rem; /* 12px 24px */
  text-align: center;
`;

const Message = styled.p`
  color: ${({ theme }) => theme.neutral[700]};
  ${({ theme }) => theme.text.body01};
`;

const Description = styled.p`
  color: ${({ theme }) => theme.neutral[500]};
  ${({ theme }) => theme.text.caption02};
`;
