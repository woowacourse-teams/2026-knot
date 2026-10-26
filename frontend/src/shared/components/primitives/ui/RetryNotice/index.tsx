import InlineError from "@primitives/ui/InlineError";

interface RetryNoticeProps {
  /** 무엇이 안 됐는지 알리는 문구 */
  message: string;
  /** `다시 시도`를 눌렀을 때 실행할 동작 */
  onRetry: () => void;
}

/**
 * 잠깐의 실패를 알리고 다시 시도하게 하는 안내.
 *
 * 화면 안 한 영역이 아니라 화면이나 상태 하나를 통째로 대신할 때 써요.
 * 모양은 `InlineError`의 가운데 정렬 · 기본 크기와 같아서 그대로 그려요.
 * 낭독기가 바로 읽도록 `role="alert"`가 붙어 있어요.
 */
export default function RetryNotice({ message, onRetry }: RetryNoticeProps) {
  return <InlineError align="center" message={message} onRetry={onRetry} />;
}
