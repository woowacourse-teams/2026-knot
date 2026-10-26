import styled from "@emotion/styled";
import Skeleton from "@primitives/ui/Skeleton";

/**
 * 녹음 문서를 불러오는 동안 제목 · 날짜 줄 · 녹음 음성 · 회의 전체 요약 자리를 채우는 덩어리. 피그마 「녹음 문서/불러오는 중」이에요.
 *
 * 시안에는 「이 회의에서 나온 문서」 자리가 없어 요약 자리까지만 채워요.
 */
export default function RecordingDocumentSkeleton() {
  return (
    <Container>
      <TitleBlock>
        {/* 제목 자리: 280 × 26px, 모서리 13px */}
        <Skeleton width={17.5} height={1.625} radius={0.8125} />
        {/* 날짜 줄 자리: 180 × 12px, 모서리 6px */}
        <Skeleton width={11.25} height={0.75} radius={0.375} />
      </TitleBlock>
      {/* 녹음 음성 자리: 높이 64px, 모서리 14px */}
      <Skeleton height={4} radius={0.875} />
      <Lines>
        {/* 짧은 줄(96px)은 구역 제목 자리, 긴 줄은 그 아래 문장 자리예요 */}
        <Skeleton width={6} />
        <Skeleton />
        <Skeleton width="85%" />
        <Skeleton width={6} />
        <Skeleton width="70%" />
      </Lines>
    </Container>
  );
}

/** 피그마 Content: 묶음 사이 40px */
const Container = styled.div`
  display: flex;
  flex-direction: column;
  gap: 2.5rem; /* 40px */
`;

/** 피그마 불러오는 중 TitleBlock: 제목과 날짜 줄 사이 12px */
const TitleBlock = styled.div`
  display: flex;
  flex-direction: column;
  gap: 0.75rem; /* 12px */
`;

const Lines = styled.div`
  display: flex;
  flex-direction: column;
  gap: 1.5rem; /* 24px */
`;
