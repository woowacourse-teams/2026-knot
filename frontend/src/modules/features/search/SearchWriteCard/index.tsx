import styled from "@emotion/styled";
import Button from "@primitives/ui/Button";
import { useId } from "react";

import NotionLogo from "@/assets/logos/notion.svg";

import {
  CONNECTION_RETRY_MESSAGE,
  FAILURE_MESSAGE,
  NOT_EXECUTABLE_CAPTION,
  RETRY_CAPTION,
  RUNNING_CAPTION,
} from "./constants/writeCardMessages";
import useWriteCardActions from "./model/useWriteCardActions";
import type { WriteCard } from "./types/writeCard";
import ConnectionRetryFooter from "./ui/ConnectionRetryFooter";
import ExistingPageNotice from "./ui/ExistingPageNotice";
import WriteCardActions from "./ui/WriteCardActions";
import WriteCardAlert from "./ui/WriteCardAlert";
import WriteCardFields from "./ui/WriteCardFields";

interface SearchWriteCardProps {
  /** 그릴 쓰기 확인 카드. 저장된 메시지의 카드이거나 생성 중 답변의 카드예요 */
  card: WriteCard;
}

const isConnectionRetryReason = (
  retryReason: WriteCard["retryReason"],
): retryReason is keyof typeof CONNECTION_RETRY_MESSAGE =>
  retryReason !== null && retryReason in CONNECTION_RETRY_MESSAGE;

const isCaptionRetryReason = (
  retryReason: WriteCard["retryReason"],
): retryReason is keyof typeof RETRY_CAPTION =>
  retryReason !== null && retryReason in RETRY_CAPTION;

/**
 * 탐색 답변에 붙은 노션 페이지 만들기 확인 카드(SEARCH-R40~R53).
 *
 * 무엇이 어디에 생기는지 보기 전용으로 보여주고, 노션에는 [만들기]를 눌렀을 때만 만들어요.
 * 누르면 바로 두 버튼을 막고 처리 중을 보여주며(SEARCH-R50), 끝나면 대화 메시지를 다시 받아 카드의 지금 상태를 그려요.
 * 카드 상태(대기, 실행 중, 만들었어요, 취소됨, 만들지 못했어요)와 대기로 돌아온 사유마다 아래 줄이 바뀌어요.
 * 실행과 취소는 질문한 사람만 할 수 있는데, 대화는 질문한 사람에게만 보여 화면에서 따로 가리지 않아요.
 */
export default function SearchWriteCard({ card }: SearchWriteCardProps) {
  const titleId = useId();
  const { execute, cancel, isExecuting, isCanceling } = useWriteCardActions({
    cardId: card.id,
  });
  // 실행 응답이 오기 전에도 서버의 실행 중과 같은 모습으로 막아요
  const status = isExecuting ? "RUNNING" : card.status;
  const { retryReason, failureReason, executable, existingPages } = card;

  const handleOpenCreatedPage = () => {
    if (card.createdPageUrl) {
      window.open(card.createdPageUrl, "_blank", "noopener,noreferrer");
    }
  };

  return (
    <Root aria-labelledby={titleId}>
      <Content $isFaded={status === "CANCELED"}>
        <Header>
          <AppTile>
            <NotionLogo size={20} />
          </AppTile>
          <Title id={titleId}>노션 페이지 만들기</Title>
        </Header>
        <WriteCardFields card={card} />
      </Content>

      {(status === "PENDING" || status === "RUNNING") &&
        existingPages.map(({ url, title }) => (
          <ExistingPageNotice key={url} url={url} title={title} />
        ))}

      <Footer aria-live="polite">
        {status === "PENDING" &&
          (isConnectionRetryReason(retryReason) ? (
            <ConnectionRetryFooter
              retryReason={retryReason}
              onCancel={cancel}
              onExecute={execute}
              isCanceling={isCanceling}
              canExecute={executable}
            />
          ) : (
            <WriteCardActions
              onCancel={cancel}
              onExecute={execute}
              isCanceling={isCanceling}
              isRunning={false}
              canExecute={executable}
              caption={
                !executable
                  ? NOT_EXECUTABLE_CAPTION
                  : isCaptionRetryReason(retryReason)
                    ? RETRY_CAPTION[retryReason].text
                    : undefined
              }
              isCaptionWarning={
                executable &&
                isCaptionRetryReason(retryReason) &&
                RETRY_CAPTION[retryReason].isWarning
              }
            />
          ))}

        {status === "RUNNING" && (
          <WriteCardActions
            onCancel={cancel}
            onExecute={execute}
            isCanceling={false}
            isRunning
            canExecute={false}
            caption={RUNNING_CAPTION}
          />
        )}

        {status === "DONE" && (
          <DoneRow>
            <DoneStatus>
              <DoneDot />
              만들었어요
            </DoneStatus>
            <Button size="sm" variant="outline" onClick={handleOpenCreatedPage}>
              노션에서 열기
            </Button>
          </DoneRow>
        )}

        {status === "CANCELED" && <CanceledStatus>취소됨</CanceledStatus>}

        {status === "FAILED" && failureReason && (
          <WriteCardAlert
            title="만들지 못했어요"
            description={FAILURE_MESSAGE[failureReason]}
          />
        )}
      </Footer>
    </Root>
  );
}

const Root = styled.section`
  display: flex;
  flex-direction: column;
  gap: 1rem; /* 16px */
  width: 100%;
  max-width: 42.5rem; /* 680px */
  padding: 1.25rem; /* 20px */
  border-radius: 1rem; /* 16px */
  background-color: ${({ theme }) => theme.neutral[0]};
  box-shadow: ${({ theme }) => theme.shadow01};
`;

/** 취소된 카드는 내용만 흐리게 하고 「취소됨」은 또렷하게 둬요 */
const Content = styled.div<{ $isFaded: boolean }>`
  display: flex;
  flex-direction: column;
  gap: 1rem; /* 16px */
  opacity: ${({ $isFaded }) => ($isFaded ? 0.5 : 1)};
`;

const Header = styled.div`
  display: flex;
  align-items: center;
  gap: 0.75rem; /* 12px */
`;

const AppTile = styled.span`
  display: flex;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  width: 2.25rem; /* 36px */
  height: 2.25rem; /* 36px */
  border: 1px solid ${({ theme }) => theme.neutral[200]};
  border-radius: 0.75rem; /* 12px */
  background-color: ${({ theme }) => theme.neutral[0]};
`;

const Title = styled.p`
  ${({ theme }) => theme.text.label01};
  color: ${({ theme }) => theme.neutral[900]};
`;

const Footer = styled.div`
  display: flex;
  flex-direction: column;
`;

const DoneRow = styled.div`
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.75rem; /* 12px */
`;

const DoneStatus = styled.p`
  display: flex;
  align-items: center;
  gap: 0.375rem; /* 6px */
  ${({ theme }) => theme.text.caption02};
  font-weight: 600;
  color: ${({ theme }) => theme.neutral[900]};
`;

const DoneDot = styled.span`
  flex-shrink: 0;
  width: 0.375rem; /* 6px */
  height: 0.375rem; /* 6px */
  border-radius: 50%;
  background-color: ${({ theme }) => theme.sub.accent[700]};
`;

const CanceledStatus = styled.p`
  ${({ theme }) => theme.text.caption02};
  font-weight: 600;
  color: ${({ theme }) => theme.neutral[500]};
`;
