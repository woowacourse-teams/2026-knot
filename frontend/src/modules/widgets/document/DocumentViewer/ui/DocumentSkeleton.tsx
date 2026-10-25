import styled from "@emotion/styled";
import Skeleton from "@primitives/ui/Skeleton";

/**
 * 문서를 불러오는 동안 복사 버튼 · 제목 · 날짜 줄 · 본문 자리를 채우는 덩어리.
 *
 * 줄마다 실제 화면과 같은 높이를 지켜, 불러온 뒤 내용이 아래로 밀리지 않게 해요.
 */
export default function DocumentSkeleton() {
  return (
    <Container>
      <TitleBlock>
        <TitleRow>
          {/* 덩어리 28px, 모서리 8px */}
          <Skeleton width="40%" height={1.75} radius={0.5} />
          {/* 복사 버튼 자리: 60 × 40px, 모서리 12px */}
          <Skeleton width={3.75} height={2.5} radius={0.75} />
        </TitleRow>
        <MetaRow>
          <Skeleton width={9} />
        </MetaRow>
      </TitleBlock>
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

/** 문서 열의 줄 사이 간격(피그마 Standard gap 24px)을 그대로 써요 */
const Container = styled.div`
  display: flex;
  flex-direction: column;
  gap: 1.5rem; /* 24px */
`;

/** 피그마 TitleBlock: 제목과 날짜 줄 사이 4px */
const TitleBlock = styled.div`
  display: flex;
  flex-direction: column;
  gap: 0.25rem; /* 4px */
`;

/** 제목 줄. 제목(39px)보다 높은 복사 버튼(40px)이 높이를 정하고, 버튼은 오른쪽 끝에 놓여요 */
const TitleRow = styled.div`
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 1rem; /* 16px */
  height: 2.5rem; /* 40px */
`;

/** 날짜 · 녹음 길이 · 확인 수가 놓이는 줄과 같은 높이 */
const MetaRow = styled.div`
  display: flex;
  align-items: center;
  height: 1.5625rem; /* 25px */
`;

/** 덩어리 10px + 간격 14px이 본문 한 줄 높이(body01 16px × 줄 높이 1.5 = 24px)와 같아요 */
const Lines = styled.div`
  display: flex;
  flex-direction: column;
  gap: 0.875rem; /* 14px */
`;
