import styled from "@emotion/styled";
import Avatar from "@primitives/ui/Avatar";

interface NotionConnectionInfoProps {
  /** 연결한 노션 워크스페이스 이름 */
  workspaceName: string;
  /** 노션 워크스페이스 아이콘. 이모지 한 글자 또는 이미지 절대 주소, 없으면 null */
  workspaceIcon: string | null;
  /** 연결한 사람의 닉네임 */
  connectorNickname: string;
}

/**
 * 연결된 노션 카드의 회색 상자. 연결된 노션 워크스페이스, 연결한 사람, 보이는 범위를 줄마다 보여 줘요(SEARCH-R30, R31).
 *
 * 노션 워크스페이스 아이콘이 없으면 피그마의 빈 아이콘처럼 회색 네모에 이름 첫 글자를 넣어요.
 */
export default function NotionConnectionInfo({
  workspaceName,
  workspaceIcon,
  connectorNickname,
}: NotionConnectionInfoProps) {
  return (
    <Root>
      <Row>
        <Label>연결된 노션</Label>
        <Value>
          {workspaceIcon === null ? (
            <InitialIcon aria-hidden="true">
              {workspaceName.charAt(0)}
            </InitialIcon>
          ) : /^https?:\/\//.test(workspaceIcon) ? (
            <ImageIcon src={workspaceIcon} alt="" />
          ) : (
            <EmojiIcon aria-hidden="true">{workspaceIcon}</EmojiIcon>
          )}
          <span>{workspaceName}</span>
        </Value>
      </Row>
      <Row>
        <Label>연결한 사람</Label>
        <Value>
          {/* 닉네임을 바로 옆에 글자로 보여 줘서 아이콘은 읽지 않아요 */}
          <span aria-hidden="true">
            <Avatar
              label={connectorNickname}
              name={connectorNickname}
              size={24}
            />
          </span>
          <span>{connectorNickname}</span>
        </Value>
      </Row>
      <Row>
        <Label>보이는 범위</Label>
        <Value>{connectorNickname} 님이 노션에서 볼 수 있는 내용 전부</Value>
      </Row>
    </Root>
  );
}

/** 피그마 연결 정보: 안쪽 16px 20px, 줄 사이 12px */
const Root = styled.dl`
  display: flex;
  margin: 0;
  flex-direction: column;
  gap: 0.75rem; /* 12px */
  padding: 1rem 1.25rem; /* 16px 20px */
  border-radius: 1rem; /* 16px */
  background-color: ${({ theme }) => theme.neutral[100]};
`;

const Row = styled.div`
  display: flex;
  align-items: center;
  gap: 0.75rem; /* 12px */
  min-height: 1.5rem; /* 24px */
`;

const Label = styled.dt`
  flex-shrink: 0;
  width: 5.5rem; /* 88px */
  ${({ theme }) => theme.text.caption02};
  color: ${({ theme }) => theme.neutral[500]};
`;

const Value = styled.dd`
  display: flex;
  margin: 0;
  align-items: center;
  gap: 0.5rem; /* 8px */
  min-width: 0;
  ${({ theme }) => theme.text.body01};
  color: ${({ theme }) => theme.neutral[900]};
`;

/** 피그마 노션 워크스페이스 아이콘: 24px, 모서리 6px */
const EmojiIcon = styled.span`
  display: flex;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  width: 1.5rem; /* 24px */
  height: 1.5rem;
  border-radius: 0.375rem; /* 6px */
  font-size: 1rem; /* 16px */
  line-height: 1;
`;

/** 아이콘이 없을 때 피그마의 빈 아이콘처럼 Neutral/200 네모에 이름 첫 글자를 넣어요 */
const InitialIcon = styled(EmojiIcon)`
  background-color: ${({ theme }) => theme.neutral[200]};
  color: ${({ theme }) => theme.neutral[800]};
  ${({ theme }) => theme.text.caption01};
  font-weight: 500;
`;

const ImageIcon = styled(EmojiIcon.withComponent("img"))`
  object-fit: cover;
`;
