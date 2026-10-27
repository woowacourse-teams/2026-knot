import styled from "@emotion/styled";
import Skeleton from "@primitives/ui/Skeleton";

/** 녹음 묶음마다 그릴 문서 행의 수. 피그마 「문서 탭/녹음별 · 불러오는 중」처럼 묶음 세 개에 행 세 개 · 한 개 · 한 개를 둬요 */
const GROUP_ROW_COUNTS = [3, 1, 1];

/** 제목 · 요약 · 날짜 자리 덩어리의 굵기(rem). 피그마에서 14px이에요 */
const TEXT_SKELETON_HEIGHT = 0.875;

/**
 * 녹음별 문서 목록을 불러오는 동안 「녹음별」 탭의 목록 자리에 두는 회색 덩어리. 녹음 카드의 머리 행과 문서 행 자리를 채워요.
 *
 * 카드와 행의 간격 · 여백 · 높이를 실제 목록과 같게 지켜, 불러온 뒤 내용이 크게 움직이지 않게 해요.
 */
export default function RecordingDocumentListSkeleton() {
  return (
    <Groups role="status" aria-label="녹음별 문서 목록을 불러오고 있어요">
      {GROUP_ROW_COUNTS.map((rowCount, groupIndex) => (
        <Card key={groupIndex}>
          <HeaderRow>
            {/* 피그마의 마이크 배지 자리 36px 동그라미 */}
            <Skeleton width={2.25} height={2.25} radius={1.125} />
            <Info>
              <Line>
                {/* 피그마의 녹음 제목 자리 160px */}
                <Skeleton width={10} height={TEXT_SKELETON_HEIGHT} />
              </Line>
              <MetaLine>
                {/* 피그마의 날짜 · 길이 자리 120px */}
                <Skeleton width={7.5} />
              </MetaLine>
            </Info>
            {/* 피그마의 「녹음 보기」 자리 56 × 12px */}
            <Skeleton width={3.5} height={0.75} />
          </HeaderRow>
          {Array.from({ length: rowCount }, (_, rowIndex) => (
            <Row key={rowIndex}>
              <TextBlock>
                <Line>
                  {/* 피그마의 제목 자리 120px */}
                  <Skeleton width={7.5} height={TEXT_SKELETON_HEIGHT} />
                </Line>
                <Line>
                  {/* 피그마의 요약 자리 420px. 글 묶음 804px에 대한 비율로 옮겼어요 */}
                  <Skeleton width="52%" height={TEXT_SKELETON_HEIGHT} />
                </Line>
              </TextBlock>
            </Row>
          ))}
        </Card>
      ))}
    </Groups>
  );
}

/** 녹음 카드 사이 32px */
const Groups = styled.div`
  display: flex;
  flex-direction: column;
  gap: 2rem; /* 32px */
`;

const Card = styled.div`
  overflow: hidden;
  border-radius: 1.25rem; /* 20px */
  background-color: ${({ theme }) => theme.neutral[0]};
  box-shadow: ${({ theme }) => theme.shadow02};
`;

/** 실제 머리 행과 같은 여백(20px 24px 18px)과 간격 */
const HeaderRow = styled.div`
  display: flex;
  align-items: center;
  gap: 0.75rem; /* 12px */
  padding: 1.25rem 1.5rem 1.125rem; /* 20px 24px 18px */
`;

/** 제목 줄과 날짜 줄 사이 4px */
const Info = styled.div`
  display: flex;
  flex: 1 0 0;
  flex-direction: column;
  gap: 0.25rem; /* 4px */
`;

/** 실제 문서 행과 같은 여백(20px 24px)과 행 위 선 */
const Row = styled.div`
  display: flex;
  align-items: center;
  padding: 1.25rem 1.5rem; /* 20px 24px */
  border-top: 1px solid ${({ theme }) => theme.neutral[200]};
`;

/** 제목 줄과 요약 줄 사이 4px */
const TextBlock = styled.div`
  display: flex;
  flex: 1 0 0;
  flex-direction: column;
  gap: 0.25rem; /* 4px */
`;

/** 글 한 줄(16px × 줄 높이 1.5)과 같은 높이 */
const Line = styled.div`
  display: flex;
  align-items: center;
  height: 1.5rem; /* 24px */
`;

/** 날짜 줄(12px × 줄 높이 1.5)과 같은 높이 */
const MetaLine = styled.div`
  display: flex;
  align-items: center;
  height: 1.125rem; /* 18px */
`;
