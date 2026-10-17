import {
  DOCUMENT_FROM_PARAM,
  DOCUMENT_FROM_RECORDING,
  NO_DECISION_SENTENCE,
} from "@constants/document";
import styled from "@emotion/styled";
import useConfirmDocument from "@hooks/domain/document/useConfirmDocument";
import Breadcrumb from "@primitives/ui/Breadcrumb";
import Chip from "@primitives/ui/Chip";
import DocumentBody from "@primitives/ui/DocumentBody";
import { formatDate } from "@utils/formatDate";
import { formatDurationFromSeconds } from "@utils/formatDurationFromSeconds";
import { useId } from "react";
import { useParams, useSearchParams } from "react-router";

import { useDocumentViewer } from "./model/useDocumentViewer";
import DocumentConfirmButton from "./ui/DocumentConfirmButton";
import DocumentConfirmCount from "./ui/DocumentConfirmCount";
import DocumentCopyButton from "./ui/DocumentCopyButton";
import DocumentLoadFailed from "./ui/DocumentLoadFailed";
import DocumentStepper from "./ui/DocumentStepper";
import DocumentSkeleton from "./ui/DocumentSkeleton";

/**
 * 문서 보기 섹션. 주소의 문서를 불러와 경로 · 복사 버튼 · 제목 · 만든 날짜 · 녹음 길이 · 확인 수 · 본문 · 확인 버튼을 보여줘요.
 *
 * 주소의 두 id는 숫자인지 확인하지 않고 그대로 요청해요. 잘못된 주소인지는 서버가 판단하고, 여기서는 그 응답에 따라 문서를 불러오지 못했다고 알려요.
 * 같은 판단을 프론트에도 두면 기준이 두 곳에 생기기 때문이에요.
 *
 * 받은 문서 값을 그리기만 하면 되는 것(경로 · 제목 · 날짜 · 녹음 길이 · 본문)은 여기서 직접 그려요.
 * 복사 버튼 · 확인 수 · 스테퍼 · 확인 버튼은 이 위젯의 부품(`ui/`)이고, 필요한 값과 동작을 여기서 넘겨줘요. 문서는 여기서 한 번만 조회해요.
 * 본문 아래 줄에는 왼쪽에 같은 녹음의 문서를 넘기는 스테퍼를, 오른쪽에 확인 버튼을 놓아요.
 * 스테퍼는 녹음 직후 정리 화면에서 넘어와 주소에 `?from=recording`이 있을 때만 그려요(DOC-R2). 문서 탭 · 홈 · 탐색에서 연 저장 문서에는 확인 버튼만 남아요.
 * 확인 버튼은 확인 대상에게만 보여요. 확인한 뒤에는 누를 수 없는 「확인했어요」로 바뀌고, 확인 대상이 아니면 그리지 않아요.
 * 결정이 없는 회의 문서는 본문의 결정 없음 문장을 흐리게 그려요(STT-R23).
 */
export default function DocumentViewer() {
  const titleId = useId();
  const params = useParams();
  const [searchParams] = useSearchParams();
  const workspaceId = Number(params.workspaceId);
  const documentId = Number(params.documentId);
  const isFromRecording =
    searchParams.get(DOCUMENT_FROM_PARAM) === DOCUMENT_FROM_RECORDING;
  const { status, documentDetail, retry } = useDocumentViewer({
    workspaceId,
    documentId,
  });
  const { confirmDocument, isConfirming } = useConfirmDocument({
    workspaceId,
    documentId,
  });

  if (status === "loading") {
    return (
      <Container aria-busy="true" aria-label="문서를 불러오고 있어요">
        <DocumentSkeleton />
      </Container>
    );
  }

  if (status === "error") {
    return <DocumentLoadFailed onRetry={retry} />;
  }

  return (
    // 스테퍼로 다른 문서로 넘어가면 「복사됨」 같은 앞 문서의 표시가 남지 않게, 문서마다 새로 그려요
    <Container key={documentDetail.id} aria-labelledby={titleId}>
      <Breadcrumb parent="문서" current={documentDetail.title} />
      <TopRow>
        <DocumentCopyButton
          title={documentDetail.title}
          content={documentDetail.content}
        />
      </TopRow>
      <TitleBlock>
        <Title id={titleId}>{documentDetail.title}</Title>
        <MetaRow>
          <Meta>
            <CreatedDate dateTime={documentDetail.createdAt}>
              {formatDate(documentDetail.createdAt)}
            </CreatedDate>
            <Chip>
              {formatDurationFromSeconds(
                documentDetail.recordingDurationSeconds,
              )}
            </Chip>
          </Meta>
          <DocumentConfirmCount
            workspaceId={workspaceId}
            documentId={documentId}
            confirmedCount={documentDetail.confirmationSummary.confirmedCount}
          />
        </MetaRow>
      </TitleBlock>
      <DocumentBody
        content={documentDetail.content}
        mutedLines={[NO_DECISION_SENTENCE]}
      />
      <ConfirmBar>
        {isFromRecording && (
          <DocumentStepper
            workspaceId={workspaceId}
            documentId={documentDetail.id}
            recordingSessionId={documentDetail.recordingSessionId}
          />
        )}
        {documentDetail.myConfirmationState !== "NOT_REQUIRED" && (
          <ConfirmButtonWrapper>
            <DocumentConfirmButton
              isConfirmed={documentDetail.myConfirmationState === "CONFIRMED"}
              isConfirming={isConfirming}
              onConfirm={confirmDocument}
            />
          </ConfirmButtonWrapper>
        )}
      </ConfirmBar>
    </Container>
  );
}

/** 피그마 문서 열(Standard): 폭 720px, 경로 · 복사 줄 · 제목 묶음 · 본문 사이 24px */
const Container = styled.section`
  display: flex;
  flex-direction: column;
  gap: 1.5rem; /* 24px */
  width: 100%;
  max-width: 45rem; /* 720px */
`;

/** 피그마 TopRow: 복사 버튼을 오른쪽 끝에 놓는 줄 */
const TopRow = styled.div`
  display: flex;
  justify-content: flex-end;
`;

/** 피그마 TitleBlock: 제목과 날짜 줄 사이 4px */
const TitleBlock = styled.div`
  display: flex;
  flex-direction: column;
  gap: 0.25rem; /* 4px */
`;

const Title = styled.h2`
  ${({ theme }) => theme.text.heading02};
  color: ${({ theme }) => theme.neutral[900]};
  overflow-wrap: break-word;
`;

/** 피그마 MetaRow: 왼쪽에 날짜 · 녹음 길이, 오른쪽 끝에 확인 수 */
const MetaRow = styled.div`
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.5rem; /* 8px */
`;

/** 피그마 Meta: 날짜와 녹음 길이 칩 사이 8px */
const Meta = styled.div`
  display: flex;
  align-items: center;
  gap: 0.5rem; /* 8px */
`;

const CreatedDate = styled.time`
  ${({ theme }) => theme.text.caption02};
  color: ${({ theme }) => theme.neutral[500]};
`;

/** 피그마 Confirm/하단 바: 본문 아래 구분선과 위아래 12px, 왼쪽에 스테퍼 · 오른쪽에 확인 버튼 */
const ConfirmBar = styled.div`
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0.75rem 0; /* 12px 0 */
  border-top: 1px solid ${({ theme }) => theme.neutral[200]};

  /* 스테퍼도 확인 버튼도 그리지 않을 때는 구분선과 여백을 남기지 않아요 */
  &:empty {
    display: none;
  }
`;

/** 스테퍼가 아직 그려지지 않았을 때도 확인 버튼이 오른쪽 끝에 있게 해요 */
const ConfirmButtonWrapper = styled.div`
  margin-left: auto;
`;
