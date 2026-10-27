import useClipboard from "@hooks/common/useClipboard";
import useTimeout from "@hooks/common/useTimeout";
import Button from "@primitives/ui/Button";

/** 복사한 뒤 버튼 문구를 「복사됨」으로 보여주는 시간. 문서 복사 버튼과 같아요 */
const COPIED_DURATION_MS = 3000;

interface TranscriptCopyButtonProps {
  /** 복사할 원문. 패널에 보이는 발화 줄을 한 줄씩 이은 글이에요 */
  text: string; /** 복사에 성공했을 때. 사용 기록을 보내요 */
  onCopied: () => void;
}

/**
 * 원문 전체를 클립보드에 복사하는 버튼. 원문 패널 머리의 오른쪽 끝에 놓여요.
 *
 * 복사하면 3초 동안 「복사됨」으로 바뀌고, 토스트는 띄우지 않아요. 문서 보기의 복사 버튼과 같은 모양과 동작이에요.
 */
export default function TranscriptCopyButton({
  text,
  onCopied,
}: TranscriptCopyButtonProps) {
  const { copy } = useClipboard();
  const { start: startCopiedTimeout, isTimedOut: isCopied } = useTimeout({
    timeout: COPIED_DURATION_MS,
  });

  const handleCopy = () => {
    copy({
      text,
      onCopySuccess: () => {
        startCopiedTimeout();
        onCopied();
      },
    });
  };

  return (
    <Button size="sm" variant="outline" onClick={handleCopy}>
      {isCopied ? "복사됨" : "복사"}
    </Button>
  );
}
