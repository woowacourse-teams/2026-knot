import useClipboard from "@hooks/common/useClipboard";
import useTimeout from "@hooks/common/useTimeout";
import Button from "@primitives/ui/Button";

/** 복사한 뒤 버튼 문구를 「복사됨」으로 보여주는 시간 */
const COPIED_DURATION_MS = 3000;

interface DocumentCopyButtonProps {
  /** 복사할 문서의 제목. `#` 제목으로 본문 앞에 붙여요 */
  title: string;
  /** 복사할 문서의 마크다운 본문 */
  content: string; /** 복사에 성공했을 때. 사용 기록을 보내요 */
  onCopied: () => void;
}

/**
 * 문서를 마크다운으로 클립보드에 복사하는 버튼(STT-R10).
 *
 * 본문에는 제목이 없어서 제목을 `#` 제목으로 앞에 붙여 복사해요.
 * 복사하면 3초 동안 「복사됨」으로 바뀌고, 토스트는 띄우지 않아요. 실패하면 버튼은 그대로이고 `useClipboard`가 오류 토스트를 띄워요.
 * 문서는 직접 조회하지 않고, 문서 보기가 받은 제목과 본문을 받아요.
 */
export default function DocumentCopyButton({
  title,
  content,
  onCopied,
}: DocumentCopyButtonProps) {
  const { copy } = useClipboard();
  const { start: startCopiedTimeout, isTimedOut: isCopied } = useTimeout({
    timeout: COPIED_DURATION_MS,
  });

  const handleCopy = () => {
    copy({
      text: `# ${title}\n\n${content}`,
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
