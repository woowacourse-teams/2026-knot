import useDisconnectSearchNotionConnectionMutation from "@api/mutations/useDisconnectSearchNotionConnectionMutation";
import useSearchNotionConnectionQuery from "@api/queries/useSearchNotionConnectionQuery";
import styled from "@emotion/styled";
import Button from "@primitives/ui/Button";
import Divider from "@primitives/ui/Divider";
import { useId, useState } from "react";
import { useParams } from "react-router";

import ChevronDownIcon from "@/assets/icons/chevronDown.svg";
import NotionLogo from "@/assets/logos/notion.svg";

import CardNotice from "./ui/CardNotice";
import NotionAbilityList from "./ui/NotionAbilityList";
import NotionConnectionInfo from "./ui/NotionConnectionInfo";

const STATUS_LABELS = {
  NOT_CONNECTED: "연결 전",
  CONNECTED: "연결됨",
  EXPIRED: "만료",
} as const;

/**
 * 연결된 앱 화면의 노션 앱 카드(SEARCH-R29~R34). 워크스페이스의 노션 연결을 보여 주고 연결하거나 끊어요.
 *
 * - 연결 전: 멤버 누구나 [연결]을 눌러요. 이미 연결돼 있으면 [연결]이 없어요
 * - 연결됨: 연결된 노션과 연결한 사람, 보이는 범위를 보여 줘요. 연결한 사람과 소유자에게만 [연결 끊기]가 있어요
 * - 만료: 멤버 누구나 [다시 연결]을 눌러요. 연결한 사람과 소유자에게는 [연결 끊기]도 있어요
 *
 * 노션이 하는 일은 기본으로 접어 두고 「자세히 보기」로 펼쳐요.
 * 불러오는 중이거나 불러오지 못하면 상태 칩과 버튼 없이 머리만 보여요.
 */
export default function NotionAppCard() {
  const titleId = useId();
  const workspaceId = Number(useParams().workspaceId);
  const { data: connection } = useSearchNotionConnectionQuery({ workspaceId });
  const { mutate: disconnect, isPending: isDisconnecting } =
    useDisconnectSearchNotionConnectionMutation();
  const [isDetailOpen, setIsDetailOpen] = useState(false);

  const status = connection?.status;

  const disconnectButton = connection?.canDisconnect && (
    <DisconnectButton
      size="sm"
      variant="outline"
      isLoading={isDisconnecting}
      onClick={() => disconnect({ workspaceId })}
    >
      연결 끊기
    </DisconnectButton>
  );

  return (
    <Container aria-labelledby={titleId}>
      <Head>
        <AppTile>
          <NotionLogo size={26} />
        </AppTile>
        <Summary>
          <NameRow>
            <Name id={titleId}>노션</Name>
            {status && (
              <StatusChip $status={status}>{STATUS_LABELS[status]}</StatusChip>
            )}
          </NameRow>
          <Role>탐색이 노션을 읽고 답하고, 확인을 받아 페이지를 만들어요.</Role>
        </Summary>
        <DetailToggle
          type="button"
          aria-expanded={isDetailOpen}
          $isOpen={isDetailOpen}
          onClick={() => setIsDetailOpen((isOpen) => !isOpen)}
        >
          {isDetailOpen ? "접기" : "자세히 보기"}
          <ChevronDownIcon size={12} />
        </DetailToggle>
      </Head>

      {isDetailOpen && <NotionAbilityList />}

      {status === "EXPIRED" && (
        <CardNotice
          title="노션 연결이 만료됐어요"
          description="다시 연결하기 전에는 탐색이 노션을 읽고 쓰지 못해요."
        />
      )}

      {status === "CONNECTED" && connection?.connectedBy && (
        <NotionConnectionInfo
          workspaceName={connection.notionWorkspaceName ?? ""}
          workspaceIcon={connection.notionWorkspaceIcon}
          connectorNickname={connection.connectedBy.nickname}
        />
      )}

      {/* 연결됐는데 끊을 수 없는 멤버에게는 누를 것이 없어 아래 줄을 두지 않아요 */}
      {status && (status !== "CONNECTED" || connection?.canDisconnect) && (
        <Foot>
          <Divider />
          <Actions>
            {status === "NOT_CONNECTED" && (
              <Hint>한 사람이 연결하면 멤버 모두가 써요.</Hint>
            )}
            <Buttons>
              {disconnectButton}
              {status === "NOT_CONNECTED" && (
                <Button size="sm">연결</Button>
              )}
              {status === "EXPIRED" && (
                <Button size="sm">다시 연결</Button>
              )}
            </Buttons>
          </Actions>
        </Foot>
      )}
    </Container>
  );
}

/** 피그마 앱 카드: 폭 652px, 안쪽 20px, 묶음 사이 20px */
const Container = styled.article`
  display: flex;
  flex-direction: column;
  gap: 1.25rem; /* 20px */
  width: 100%;
  max-width: 40.75rem; /* 652px */
  padding: 1.25rem; /* 20px */
  border-radius: 1.5rem; /* 24px */
  background-color: ${({ theme }) => theme.neutral[0]};
  box-shadow: ${({ theme }) => theme.shadow02};
  /* 좁은 화면에서 낱말 중간이 아니라 낱말 사이에서 줄을 바꿔요 */
  word-break: keep-all;
  overflow-wrap: anywhere;
`;

const Head = styled.div`
  display: flex;
  align-items: center;
  gap: 1rem; /* 16px */
`;

/** 피그마 앱 타일: 48px, 테두리 Neutral/200, 로고는 24 크기를 26으로 늘려 써요 */
const AppTile = styled.span`
  display: flex;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  width: 3rem; /* 48px */
  height: 3rem;
  border: 1px solid ${({ theme }) => theme.neutral[200]};
  border-radius: 1rem; /* 16px */
  background-color: ${({ theme }) => theme.neutral[0]};
`;

const Summary = styled.div`
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 0.25rem; /* 4px */
  min-width: 0;
`;

const NameRow = styled.div`
  display: flex;
  align-items: center;
  gap: 0.5rem; /* 8px */
`;

const Name = styled.h2`
  ${({ theme }) => theme.text.heading04};
  color: ${({ theme }) => theme.neutral[900]};
`;

/** 피그마 상태 칩: 연결 전은 회색, 연결됨은 청록, 만료는 붉은색 */
const StatusChip = styled.span<{ $status: keyof typeof STATUS_LABELS }>`
  padding: 0.125rem 0.5rem; /* 2px 8px */
  border-radius: 999px;
  ${({ theme }) => theme.text.caption01};
  white-space: nowrap;
  background-color: ${({ theme, $status }) =>
    ({
      NOT_CONNECTED: theme.neutral[100],
      CONNECTED: theme.sub.accent[100],
      EXPIRED: theme.sub.warning[50],
    })[$status]};
  color: ${({ theme, $status }) =>
    ({
      NOT_CONNECTED: theme.neutral[600],
      CONNECTED: theme.sub.accent[700],
      EXPIRED: theme.sub.warning[700],
    })[$status]};
`;

const Role = styled.p`
  ${({ theme }) => theme.text.caption02};
  color: ${({ theme }) => theme.neutral[600]};
`;

/** 피그마의 13px 글자. 같은 크기의 글꼴 토큰이 없어 caption02에 크기와 굵기만 바꿔요 */
const DetailToggle = styled.button<{ $isOpen: boolean }>`
  display: flex;
  flex-shrink: 0;
  align-items: center;
  gap: 0.25rem; /* 4px */
  ${({ theme }) => theme.text.caption02};
  font-size: 0.8125rem; /* 13px */
  font-weight: 500;
  color: ${({ theme }) => theme.neutral[600]};
  white-space: nowrap;

  & > svg {
    transform: ${({ $isOpen }) => ($isOpen ? "rotate(180deg)" : "none")};
  }

  &:focus-visible {
    outline: 2px solid ${({ theme }) => theme.sub.accent[500]};
    outline-offset: 2px;
  }
`;

const Foot = styled.div`
  display: flex;
  flex-direction: column;
  gap: 1rem; /* 16px */
`;

const Actions = styled.div`
  display: flex;
  align-items: center;
  gap: 0.5rem; /* 8px */
`;

const Hint = styled.p`
  ${({ theme }) => theme.text.caption02};
  color: ${({ theme }) => theme.neutral[500]};
`;

const Buttons = styled.div`
  display: flex;
  gap: 0.5rem; /* 8px */
  margin-left: auto;
`;

/** 피그마 Button / 연결 끊기: 흰 바탕 테두리 버튼에 Warning/600 글자 */
const DisconnectButton = styled(Button)`
  color: ${({ theme }) => theme.sub.warning[600]};
`;
