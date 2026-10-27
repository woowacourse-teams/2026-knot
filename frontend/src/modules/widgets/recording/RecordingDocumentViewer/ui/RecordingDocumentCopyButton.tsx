import useClipboard from "@hooks/common/useClipboard";
import useTimeout from "@hooks/common/useTimeout";
import Button from "@primitives/ui/Button";

/** 복사한 뒤 버튼 문구를 「복사됨」으로 보여주는 시간. 문서 복사 버튼과 같아요 */
const COPIED_DURATION_MS = 3000;

interface RecordingDocumentCopyButtonProps {
  /** 녹음 제목. `#` 제목으로 요약 앞에 붙여요 */
  title: string;
  /** 회의 전체 요약의 마크다운 본문 */
  content: string;
  /** 복사에 성공했을 때. 사용 기록을 보내요 */
  onCopied: () => void;
}

/**
 * 녹음 제목과 회의 전체 요약을 마크다운으로 클립보드에 복사하는 버튼(STT-R38).
 *
 * 맨 위에 녹음 제목을 `#` 제목으로, 그 아래 요약 본문을 서버가 준 그대로 이어요. 문서 복사(STT-R10)와 같은 방식이에요.
 * 복사하면 3초 동안 「복사됨」으로 바뀌고 토스트는 띄우지 않아요(DOC-R17). 실패하면 버튼은 그대로이고 `useClipboard`가 오류 토스트를 띄워요.
 * 요약이 완료일 때만 놓는 일은 쓰는 쪽이 맡아요.
 */
export default function RecordingDocumentCopyButton({
  title,
  content,
  onCopied,
}: RecordingDocumentCopyButtonProps) {
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
