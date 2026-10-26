import styled from "@emotion/styled";
import InlineError from "@primitives/ui/InlineError";

interface RecordingDocumentListLoadFailedProps {
  /** `다시 시도`를 눌렀을 때 실행할 동작 */
  onRetry: () => void;
}

/**
 * 녹음별 문서 목록을 불러오지 못했을 때 「녹음별」 탭의 목록 자리에 두는 안내. 피그마 「문서 탭/녹음별 · 불러오기 실패」예요(DOC-R24).
 *
 * 화면 전체가 아니라 목록만 실패한 것이라, 제목 「문서」와 탭은 문서 탭 섹션이 그대로 두고 목록이 있어야 할 자리에만 안내를 남겨요.
 */
export default function RecordingDocumentListLoadFailed({
  onRetry,
}: RecordingDocumentListLoadFailedProps) {
  return (
    <InlineErrorWrapper>
      <InlineError
        message="목록을 불러오지 못했어요. 다시 시도해 주세요."
        onRetry={onRetry}
        align="center"
      />
    </InlineErrorWrapper>
  );
}

/**
 * 피그마 「문서 탭/녹음별 · 불러오기 실패」: 안내를 가로 가운데, 탭 아래 120px에 놓아요.
 * 탭과 이 자리 사이에 28px이 있으므로, 나머지 92px을 여기서 띄워요
 */
const InlineErrorWrapper = styled.div`
  display: flex;
  justify-content: center;
  padding-top: 5.75rem; /* 92px */
`;
