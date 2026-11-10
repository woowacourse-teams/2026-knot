import styled from "@emotion/styled";
import Button from "@primitives/ui/Button";

interface UpdatePreparingNoticeProps {
  /** 전체 페이지를 새로고침하지 않고 해당 기능만 다시 조회해요 */
  onCheck: () => void;
}

/** 지원되지 않는 기능 안에 놓는 중립 안내. 기존 화면은 유지하고 지원 여부만 다시 확인해요. */
export default function UpdatePreparingNotice({
  onCheck,
}: UpdatePreparingNoticeProps) {
  return (
    <Container>
      <Message role="status">업데이트 준비 중</Message>
      <Button size="sm" variant="outline" onClick={onCheck}>
        다시 확인
      </Button>
    </Container>
  );
}

const Container = styled.div`
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 0.75rem;
  padding: 1.5rem;
  text-align: center;
`;

const Message = styled.p`
  ${({ theme }) => theme.text.caption02};
  color: ${({ theme }) => theme.neutral[600]};
`;
