import Tabs from "@composites/Tabs";
import styled from "@emotion/styled";
import RecordingDocumentList from "@features/document/RecordingDocumentList";
import TopicDocumentList from "@features/document/TopicDocumentList";
import { useId } from "react";

import { DOCUMENT_ARCHIVE_TABS } from "./constants/documentArchiveTabs";
import { useDocumentArchiveTab } from "./model/useDocumentArchiveTab";

/**
 * 문서 탭 섹션. 제목 「문서」 아래에 「녹음별」 · 「주제별」 두 탭을 두고, 고른 탭의 목록을 보여줘요(DOC-R20).
 *
 * 어느 탭인지는 주소가 정해요. 「녹음별」은 `/documents/recordings`, 「주제별」은 `/documents/topics`이고,
 * 탭을 누르면 그 주소로 가요. 「녹음별」 목록은 `RecordingDocumentList`, 「주제별」 목록은 `TopicDocumentList`가 그려요.
 * 탭을 바꿔도 사이드바의 주제 목록은 그대로예요. 사이드바는 이 섹션 밖에서 따로 그리기 때문이에요.
 */
export default function DocumentArchive() {
  const titleId = useId();
  const tabsId = useId();
  const panelId = useId();
  const { tab, selectTab } = useDocumentArchiveTab();

  return (
    <Container aria-labelledby={titleId}>
      <Header>
        <Title id={titleId}>문서</Title>
        <Description>녹음하고 정리한 내용을 주제별로 모아둬요</Description>
      </Header>
      <Tabs
        id={tabsId}
        items={DOCUMENT_ARCHIVE_TABS}
        value={tab}
        onChange={selectTab}
        panelId={panelId}
        aria-label="문서 보기 방식"
      />
      <Panel id={panelId} role="tabpanel" aria-labelledby={`${tabsId}-${tab}`}>
        {tab === "recording" ? (
          <RecordingDocumentList />
        ) : (
          <TopicDocumentList />
        )}
      </Panel>
    </Container>
  );
}

/** 피그마 List/Docs: 폭 880px, 머리 · 탭 · 목록 사이 28px */
const Container = styled.section`
  display: flex;
  flex-direction: column;
  gap: 1.75rem; /* 28px */
  width: 100%;
  max-width: 55rem; /* 880px */
`;

/** 피그마 머리: 제목과 설명 사이 6px */
const Header = styled.div`
  display: flex;
  flex-direction: column;
  gap: 0.375rem; /* 6px */
`;

const Title = styled.h2`
  ${({ theme }) => theme.text.heading02};
  color: ${({ theme }) => theme.neutral[900]};
`;

const Description = styled.p`
  ${({ theme }) => theme.text.body01};
  color: ${({ theme }) => theme.neutral[600]};
`;

const Panel = styled.div`
  display: flex;
  flex-direction: column;
`;
