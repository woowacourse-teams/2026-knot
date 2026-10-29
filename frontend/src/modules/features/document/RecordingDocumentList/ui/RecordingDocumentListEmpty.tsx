import styled from "@emotion/styled";

import DocumentEmptyIllustration from "@/assets/illustrations/documentEmpty.svg";

/**
 * 문서가 나온 녹음이 하나도 없을 때 「녹음별」 탭의 목록 자리에 두는 안내. 피그마 「문서 탭/녹음별 · 빈 상태」예요(DOC-R24).
 *
 * 제목 「문서」와 탭은 문서 탭 섹션이 그대로 두고, 목록 자리만 이 안내로 바뀌어요.
 */
export default function RecordingDocumentListEmpty() {
  return (
    <Notice>
      <Illustration size={48} aria-hidden />
      <NoticeTitle>아직 문서가 나온 녹음이 없어요</NoticeTitle>
      <NoticeDescription>
        녹음을 마치면 그 녹음에서 나온 문서가 여기에 모여요
      </NoticeDescription>
    </Notice>
  );
}

/** 피그마 Empty: 높이 320px의 가운데에 그림과 글 두 줄. 그림과 제목 사이 12px, 제목과 설명 사이 4px */
const Notice = styled.div`
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 0.25rem; /* 4px */
  height: 20rem; /* 320px */
  text-align: center;
`;

const Illustration = styled(DocumentEmptyIllustration)`
  flex-shrink: 0;
  margin-bottom: 0.5rem; /* 12px - 4px */
  color: ${({ theme }) => theme.neutral[700]};
`;

const NoticeTitle = styled.p`
  ${({ theme }) => theme.text.label01};
  color: ${({ theme }) => theme.neutral[800]};
`;

const NoticeDescription = styled.p`
  ${({ theme }) => theme.text.caption02};
  color: ${({ theme }) => theme.neutral[600]};
`;
