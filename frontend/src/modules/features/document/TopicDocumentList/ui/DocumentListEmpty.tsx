import styled from "@emotion/styled";

/**
 * 문서가 하나도 없을 때 「주제별」 탭의 목록 자리에 두는 안내. 피그마 「문서 목록/빈 상태」예요.
 *
 * 제목 「문서」와 탭은 문서 탭 섹션이 그대로 두고, 목록 자리만 이 안내로 바뀌어요.
 */
export default function DocumentListEmpty() {
  return (
    <Notice>
      <NoticeTitle>아직 문서가 없어요</NoticeTitle>
      <NoticeDescription>
        독의 마이크로 회의를 녹음하면 주제별로 정리된 문서가 폴더에 모여요
      </NoticeDescription>
    </Notice>
  );
}

/** 피그마 Empty: 높이 320px의 가운데에 글 두 줄, 줄 사이 4px */
const Notice = styled.div`
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 0.25rem; /* 4px */
  height: 20rem; /* 320px */
  text-align: center;
`;

const NoticeTitle = styled.p`
  ${({ theme }) => theme.text.body01};
  color: ${({ theme }) => theme.neutral[700]};
`;

const NoticeDescription = styled.p`
  ${({ theme }) => theme.text.caption02};
  color: ${({ theme }) => theme.neutral[600]};
`;
