import styled from "@emotion/styled";
import Divider from "@primitives/ui/Divider";
import Skeleton from "@primitives/ui/Skeleton";

/** 회원 정보를 받는 동안 카드를 대신하는 뼈대. 제목·도움말·안내 두 줄과 같은 배치예요. */
export default function LoadingFallback() {
  return (
    <Container aria-busy="true">
      <Lines>
        <Line $height={3.25}>
          {/* 52px — 제목 글줄 높이 */}
          <Skeleton width={12.5} height={2} />
        </Line>
        <Line>
          <Skeleton width={18.75} height={0.75} />
        </Line>
      </Lines>

      <Line $height={3.25}>
        <Skeleton width={4.5} height={1} />
        <Skeleton width={15} height={3.25} />
      </Line>
      <Divider />

      <Lines>
        <Line>
          <Skeleton width={40} height={0.75} />
        </Line>
        <Line>
          <Skeleton width={30} height={0.75} />
        </Line>
      </Lines>
    </Container>
  );
}

/** 카드 틀은 내용이 들어온 카드와 같아요. */
const Container = styled.div`
  display: flex;
  flex-direction: column;
  gap: 1.25rem; /* 20px */
  width: 100%;
  padding: 1.75rem 2rem; /* 28px 32px */
  border-radius: 1.5rem; /* 24px */
  background-color: ${({ theme }) => theme.neutral[0]};
`;

const Lines = styled.div`
  display: flex;
  flex-direction: column;
  gap: 0.5rem; /* 8px */
`;

/** 글줄 높이만큼 자리를 잡아, 내용이 들어와도 카드 높이가 바뀌지 않아요. 기본은 본문 글줄(21px)이에요. */
const Line = styled.div<{ $height?: number }>`
  display: flex;
  align-items: center;
  height: ${({ $height = 1.3125 }) => $height}rem;
`;
