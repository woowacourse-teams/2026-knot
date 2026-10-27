import styled from "@emotion/styled";
import Skeleton from "@primitives/ui/Skeleton";

/** 폴더마다 그릴 행의 수. 피그마 「문서 목록/불러오는 중」처럼 폴더 세 개에 행 두 개 · 한 개 · 한 개를 둬요 */
const FOLDER_ROW_COUNTS = [2, 1, 1];

/** 폴더 이름 · 제목 · 요약 자리 덩어리의 굵기(rem). 피그마에서 14px이에요 */
const TEXT_SKELETON_HEIGHT = 0.875;

/**
 * 문서 목록을 불러오는 동안 「주제별」 탭의 목록 자리에 두는 회색 덩어리. 폴더 이름 · 카드 · 행 자리를 채워요.
 *
 * 폴더와 행의 간격 · 여백 · 높이를 실제 목록과 같게 지켜, 불러온 뒤 내용이 크게 움직이지 않게 해요.
 */
export default function DocumentListSkeleton() {
  return (
    <Folders role="status" aria-label="문서 목록을 불러오고 있어요">
      {FOLDER_ROW_COUNTS.map((rowCount, folderIndex) => (
        <Folder key={folderIndex}>
          <TopicRow>
            {/* 피그마의 폴더 이름 자리 60px */}
            <Skeleton width={3.75} height={TEXT_SKELETON_HEIGHT} />
          </TopicRow>
          <Card>
            {Array.from({ length: rowCount }, (_, rowIndex) => (
              <Row key={rowIndex}>
                <TextBlock>
                  <Line>
                    {/* 피그마의 제목 자리 120px */}
                    <Skeleton width={7.5} height={TEXT_SKELETON_HEIGHT} />
                  </Line>
                  <Line>
                    {/* 피그마의 요약 자리 420px. 글 묶음 678px에 대한 비율로 옮겼어요 */}
                    <Skeleton width="62%" height={TEXT_SKELETON_HEIGHT} />
                  </Line>
                </TextBlock>
                <Meta>
                  {/* 피그마의 날짜 자리 90 × 10px */}
                  <Skeleton width={5.625} />
                  {/* 피그마의 녹음 길이 자리 40 × 12px */}
                  <Skeleton width={2.5} height={0.75} />
                </Meta>
              </Row>
            ))}
          </Card>
        </Folder>
      ))}
    </Folders>
  );
}

/** 폴더 사이 28px */
const Folders = styled.div`
  display: flex;
  flex-direction: column;
  gap: 1.75rem; /* 28px */
`;

/** 폴더 이름 줄과 카드 사이 12px */
const Folder = styled.div`
  display: flex;
  flex-direction: column;
  gap: 0.75rem; /* 12px */
`;

/** 폴더 이름 한 줄(label01 16px × 줄 높이 1.5)과 같은 높이, 왼쪽 여백 4px */
const TopicRow = styled.div`
  display: flex;
  align-items: center;
  height: 1.5rem; /* 24px */
  padding-left: 0.25rem; /* 4px */
`;

const Card = styled.div`
  overflow: hidden;
  border-radius: 1.25rem; /* 20px */
  background-color: ${({ theme }) => theme.neutral[0]};
  box-shadow: ${({ theme }) => theme.shadow02};
`;

/** 실제 행과 같은 여백(20px 24px)과 행 사이 선 */
const Row = styled.div`
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 1rem; /* 16px */
  padding: 1.25rem 1.5rem; /* 20px 24px */

  & + & {
    border-top: 1px solid ${({ theme }) => theme.neutral[200]};
  }
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

/** 피그마 Meta: 날짜 자리와 녹음 길이 자리 사이 8px. 실제 행의 날짜 묶음과 같아요 */
const Meta = styled.div`
  display: flex;
  flex-shrink: 0;
  align-items: center;
  gap: 0.5rem; /* 8px */
`;
