import styled from "@emotion/styled";

import { useTitleId } from "../model/useTitleId";

/**
 * 확인할 문서가 없을 때 보여 주는 「확인이 필요한 문서」 카드. 제목만 두고 문서 수를 숨긴 채, 행 자리에 안내를 둬요. 피그마 「홈/처음 사용」이에요.
 *
 * 문서가 하나도 없는 워크스페이스와, 문서를 모두 확인한 경우가 같은 안내를 보여줘요.
 *
 * 카드의 틀과 제목까지 여기서 그려요. 문서를 받은 화면(`PendingDocumentsCard`)과 따로 바뀌는 일이 많아, 틀을 함께 쓰지 않아요.
 */
export default function PendingDocumentsEmpty() {
  const { titleId } = useTitleId();

  return (
    <Container aria-labelledby={titleId}>
      <Header>
        <Title id={titleId}>확인이 필요한 문서</Title>
      </Header>
      <Notice>
        <NoticeTitle>아직 문서가 없어요</NoticeTitle>
        <NoticeDescription>
          녹음을 끝내면 정리된 문서가 여기에 쌓여요
        </NoticeDescription>
      </Notice>
    </Container>
  );
}

/** 피그마 Card/ConfirmRequests: 652x312 고정, 안쪽 여백 20px, 머리와 안내 사이 8px */
const Container = styled.section`
  display: flex;
  flex-direction: column;
  gap: 0.5rem; /* 8px */
  width: 40.75rem; /* 652px */
  max-width: 100%;
  height: 19.5rem; /* 312px */
  overflow: hidden;
  padding: 1.25rem; /* 20px */
  border-radius: 1.5rem; /* 24px */
  background-color: ${({ theme }) => theme.neutral[0]};
  box-shadow: ${({ theme }) => theme.shadow02};
`;

/** 피그마 Header: 좌우 4px · 아래 8px */
const Header = styled.div`
  display: flex;
  align-items: center;
  padding: 0 0.25rem 0.5rem; /* 0 4px 8px */
`;

const Title = styled.h2`
  color: ${({ theme }) => theme.neutral[900]};
  ${({ theme }) => theme.text.label01};
`;

/** 피그마 Empty: 머리 아래 남은 영역(612x232)을 채우고 그 가운데에 두 줄을 놓아요. 두 줄 사이 4px */
const Notice = styled.div`
  display: flex;
  flex: 1;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 0.25rem; /* 4px */
  text-align: center;
`;

const NoticeTitle = styled.p`
  color: ${({ theme }) => theme.neutral[700]};
  ${({ theme }) => theme.text.body01};
`;

const NoticeDescription = styled.p`
  color: ${({ theme }) => theme.neutral[600]};
  ${({ theme }) => theme.text.caption02};
`;
