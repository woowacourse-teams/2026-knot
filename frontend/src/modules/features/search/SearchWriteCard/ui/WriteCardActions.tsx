import styled from "@emotion/styled";
import Button from "@primitives/ui/Button";

interface WriteCardActionsProps {
  onCancel: () => void;
  onExecute: () => void;
  /** 취소 요청을 보내고 응답을 기다리는 중인지 */
  isCanceling: boolean;
  /** 실행 요청을 보냈거나 서버가 실행 중인지 */
  isRunning: boolean;
  /** [만들기]를 누를 수 있는 카드인지 */
  canExecute: boolean;
  /** 버튼 아래 오른쪽에 둘 한 줄 안내 */
  caption?: string;
  /** 안내를 경고 색으로 보여 줄지 */
  isCaptionWarning?: boolean;
}

/**
 * 대기 카드의 [취소], [만들기]와 그 아래 한 줄 안내(SEARCH-R43, R50).
 *
 * 실행 중에는 두 버튼을 모두 누를 수 없고 [만들기]에 처리 중 표시가 떠요.
 */
export default function WriteCardActions({
  onCancel,
  onExecute,
  isCanceling,
  isRunning,
  canExecute,
  caption,
  isCaptionWarning = false,
}: WriteCardActionsProps) {
  return (
    <Container>
      <ButtonRow>
        <Button
          size="sm"
          variant="outline"
          isLoading={isCanceling}
          disabled={isRunning}
          onClick={onCancel}
        >
          취소
        </Button>
        <Button
          size="sm"
          variant="filled"
          isLoading={isRunning}
          disabled={!canExecute || isCanceling}
          onClick={onExecute}
        >
          만들기
        </Button>
      </ButtonRow>
      {caption && <Caption $isWarning={isCaptionWarning}>{caption}</Caption>}
    </Container>
  );
}

const Container = styled.div`
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 0.625rem; /* 10px */
`;

const ButtonRow = styled.div`
  display: flex;
  gap: 0.5rem; /* 8px */
`;

const Caption = styled.p<{ $isWarning: boolean }>`
  text-align: right;
  ${({ theme }) => theme.text.caption01};
  color: ${({ theme, $isWarning }) =>
    $isWarning ? theme.sub.warning[700] : theme.neutral[500]};
`;
