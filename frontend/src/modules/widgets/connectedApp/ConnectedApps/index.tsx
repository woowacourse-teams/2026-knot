import { MEDIA_QUERY } from "@constants/breakpoint";
import styled from "@emotion/styled";
import NotionAppCard from "@features/connectedApp/NotionAppCard";
import { useId } from "react";

/**
 * 연결된 앱 화면의 본문 섹션. 탐색이 읽고 쓰는 앱을 카드로 모아 보여 줘요(SEARCH-R29~R34).
 *
 * 지금은 노션을 연결할 수 있어요. 카드마다 연결 상태를 스스로 불러와요.
 */
export default function ConnectedApps() {
  const titleId = useId();

  return (
    <Container aria-labelledby={titleId}>
      <Head>
        <Title id={titleId}>연결된 앱</Title>
        <Description>
          탐색이 연결한 앱의 내용을 읽고 답해요. 앱에 쓰는 일은 확인을 받은
          뒤에만 해요.
        </Description>
      </Head>
      <Grid>
        <NotionAppCard />
      </Grid>
    </Container>
  );
}

/** 피그마 PageColumn: 폭 1080px, 머리와 카드 묶음 사이 28px */
const Container = styled.section`
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 1.75rem; /* 28px */
  width: 100%;
  max-width: 67.5rem; /* 1080px */

  ${MEDIA_QUERY.mobile} {
    gap: 1.25rem; /* 20px */
  }
`;

const Head = styled.div`
  display: flex;
  flex-direction: column;
  gap: 0.5rem; /* 8px */
  text-align: center;
  overflow-wrap: break-word;
  word-break: keep-all;
`;

const Title = styled.h1`
  ${({ theme }) => theme.text.title01};
  color: ${({ theme }) => theme.neutral[800]};
`;

const Description = styled.p`
  ${({ theme }) => theme.text.body01};
  color: ${({ theme }) => theme.neutral[600]};
`;

/** 앱 카드는 가운데에 놓여요. 화면이 좁으면 카드가 폭을 채워요 */
const Grid = styled.div`
  display: flex;
  justify-content: center;
  width: 100%;
`;
