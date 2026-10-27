import styled from "@emotion/styled";
import Skeleton from "@primitives/ui/Skeleton";

/** 넘치는 마지막 줄은 카드가 잘라 내요 */
const ROW_COUNT = 5;

/** 녹음 기록을 불러오는 동안 목록 자리를 채워 두는 뼈대. 피그마 「Card/RecordingHistory · 불러오는 중」이에요. */
export default function LoadingFallback() {
  return (
    <Container>
      {Array.from({ length: ROW_COUNT }, (_, index) => (
        <Row key={index}>
          <Skeleton width={2.25} height={2.25} /> {/* 36px */}
          <Lines>
            <Line>
              <Skeleton width={7.5} height={0.75} /> {/* 120px 12px */}
            </Line>
            <TitleLine>
              <Skeleton width={11.25} height={1.125} /> {/* 180px 18px */}
            </TitleLine>
            <Line>
              <Skeleton width={20} height={0.75} /> {/* 320px 12px */}
            </Line>
          </Lines>
        </Row>
      ))}
    </Container>
  );
}

const Container = styled.div`
  display: flex;
  flex: 1;
  flex-direction: column;
  min-height: 0;
  overflow: hidden;
`;

/** 피그마 Current: 안쪽 여백 20px 24px 18px, 동그라미와 글줄 사이 16px */
const Row = styled.div`
  display: flex;
  flex-shrink: 0;
  align-items: center;
  gap: 1rem; /* 16px */
  padding: 1.25rem 1.5rem 1.125rem; /* 20px 24px 18px */
`;

const Lines = styled.div`
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 0.25rem; /* 4px */
  min-width: 0;
`;

/** 글줄 높이 21px 가운데에 뼈대를 둬요 */
const Line = styled.div`
  display: flex;
  align-items: center;
  height: 1.3125rem; /* 21px */
`;

const TitleLine = styled(Line)`
  height: 1.6875rem; /* 27px */
`;
