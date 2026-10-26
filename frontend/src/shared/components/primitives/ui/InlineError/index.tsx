import styled from "@emotion/styled";
import Button from "@primitives/ui/Button";

import AlertIcon from "@/assets/icons/alert.svg";

interface InlineErrorProps {
  /** 무엇이 안 됐는지 알리는 문구 */
  message: string;
  /** `다시 시도`를 눌렀을 때 실행할 동작. 다시 시도할 수 없는 실패면 넘기지 않고, 그때는 `다시 시도`를 그리지 않아요 */
  onRetry?: () => void;
  /**
   * 다시 시도 요청의 응답을 기다리는 중인지. 그동안 「다시 시도」를 누를 수 없고, 기본 크기에서는 버튼에 로딩을 그려요.
   * @default false
   */
  isRetrying?: boolean;
  /**
   * 문구와 `다시 시도`의 가로 정렬. 그 자리의 빈 상태 안내와 같은 정렬을 골라요.
   * 영역 전체를 대신할 때는 `center`(예: 홈 카드), 글 흐름 안에 들어갈 때는 `start`(예: 탐색 답변)예요.
   * @default "start"
   */
  align?: keyof typeof ALIGN;
  /**
   * 안내의 크기. 폭이 좁은 자리에는 `sm`을 골라요(예: 사이드바의 문서 목록).
   * `sm`은 아이콘 · 문구 · `다시 시도`가 모두 한 단계 작아요.
   * @default "md"
   */
  size?: keyof typeof SIZE;
}

const ALIGN = {
  start: "flex-start",
  center: "center",
} as const;

/** 크기별 치수. 주석의 px이 피그마 원본 값이에요 */
const SIZE = {
  md: {
    iconSize: 18,
    messageGap: "0.5rem" /* 8px */,
    gap: "0.75rem" /* 12px */,
  },
  sm: {
    iconSize: 16,
    messageGap: "0.375rem" /* 6px */,
    gap: "0.625rem" /* 10px */,
  },
} as const;

/**
 * 화면 안 한 영역이나 한 동작만 실패했을 때, 그 내용이 있어야 할 자리에 남기는 안내.
 *
 * 경고색은 아이콘에만 쓰고 문구는 본문 색이에요.
 * 같은 실패를 토스트로 또 알리지 않아요.
 * 낭독기가 바로 읽도록 `role="alert"`를 붙였어요.
 */
export default function InlineError({
  message,
  onRetry,
  isRetrying = false,
  align = "start",
  size = "md",
}: InlineErrorProps) {
  return (
    <Container role="alert" $align={align} $size={size}>
      <MessageRow $size={size}>
        <Icon aria-hidden size={SIZE[size].iconSize} />
        <Message $size={size}>{message}</Message>
      </MessageRow>
      {onRetry !== undefined &&
        (size === "sm" ? (
          <SmallRetryButton
            type="button"
            disabled={isRetrying}
            onClick={onRetry}
          >
            다시 시도
          </SmallRetryButton>
        ) : (
          <Button
            size="sm"
            variant="outline"
            isLoading={isRetrying}
            onClick={onRetry}
          >
            다시 시도
          </Button>
        ))}
    </Container>
  );
}

const Container = styled.div<{
  $align: keyof typeof ALIGN;
  $size: keyof typeof SIZE;
}>`
  display: flex;
  flex-direction: column;
  align-items: ${({ $align }) => ALIGN[$align]};
  gap: ${({ $size }) => SIZE[$size].gap};
`;

const MessageRow = styled.div<{ $size: keyof typeof SIZE }>`
  display: flex;
  align-items: center;
  gap: ${({ $size }) => SIZE[$size].messageGap};
`;

const Icon = styled(AlertIcon)`
  flex-shrink: 0;
  color: ${({ theme }) => theme.sub.warning[700]};
`;

const Message = styled.p<{ $size: keyof typeof SIZE }>`
  ${({ theme, $size }) =>
    $size === "sm" ? theme.text.caption02 : theme.text.body01};
  color: ${({ theme }) => theme.neutral[700]};
  overflow-wrap: break-word;
`;

/**
 * 피그마 State/InlineError 「크기=작게」의 다시 시도: 높이 30px · 좌우 12px · 둥근 끝 · 테두리 Neutral/200.
 * 공용 `Button`에는 이 크기가 없어 여기서 그려요. 글줄 18px에 위아래 6px을 더해 30px이에요
 */
const SmallRetryButton = styled.button`
  ${({ theme }) => theme.text.caption01};
  padding: 0.375rem 0.75rem; /* 6px 12px */
  border-radius: 62.4375rem; /* 999px */
  background-color: ${({ theme }) => theme.neutral[0]};
  box-shadow: inset 0 0 0 1px ${({ theme }) => theme.neutral[200]};
  color: ${({ theme }) => theme.neutral[700]};
  white-space: nowrap;

  &:focus-visible {
    outline: 2px solid ${({ theme }) => theme.sub.accent[500]};
    outline-offset: 2px;
  }
`;
